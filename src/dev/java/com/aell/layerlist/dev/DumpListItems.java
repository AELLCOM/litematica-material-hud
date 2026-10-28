package com.aell.layerlist.dev;

import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Дамп E — список материалов для схемы с баннерами и горшками: расщепляются ли клетки с
 * данными блока по узорам, что в подписи, и считает ли «есть в инвентаре» только точное
 * совпадение. Схема та же, что у дампа D («магазин на сервере», LL_DATA_SCHEMATIC).
 *
 * Два прохода: сначала список как есть (в инвентаре ничего), потом в инвентарь клиента
 * кладутся три копии первого предмета с данными — у его строки «есть» должно стать 3,
 * у остальных узоров того же баннера остаться 0.
 */
@Environment(EnvType.CLIENT)
public final class DumpListItems implements CovJob
{
    private static final BlockPos ORIGIN = new BlockPos(0, 72, 16);
    private static final long SETTLE_MS = 8000L;
    private static final long COUNT_MS = 40000L;
    private static final long STABLE_MS = 3000L;
    private static final String HEADER = String.join(",",
            "pass", "label", "item", "has_data", "total", "missing", "mismatch", "available");

    private CovOut out;
    private long deadline;
    private boolean placed;
    private int pass;
    private List<MaterialListEntry> before;
    private ItemStack given = ItemStack.EMPTY;
    private int shotTicks = -1;
    private long shotAt;
    private String lastSignature = "";
    private long settledAt;

    @Override
    public String name()
    {
        return "E/list-items.csv";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            this.out = CovOut.open("list-items.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("не открыть list-items.csv: " + e);
            return false;
        }

        String name = System.getenv("LL_DATA_SCHEMATIC");

        if (name == null || name.isBlank())
        {
            name = "магазин на сервере.litematic";
        }

        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(name);

        if (! Files.isRegularFile(path))
        {
            CovOut.log("схемы нет: " + path);
            return false;
        }

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("схема не загрузилась: " + path);
            return false;
        }

        SchematicPlacement placement = SchematicPlacement.createFor(schematic, ORIGIN, "dev-list", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        CovOut.log("схема «" + name + "» положена в " + ORIGIN + ", жду мира схемы и пересчёта");
        this.placed = true;
        this.deadline = System.currentTimeMillis() + SETTLE_MS;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (! this.placed || mc.player == null)
        {
            return true;
        }

        LayerCountManager manager = LayerCountManager.getInstance();

        if (this.before == null)
        {
            if (SchematicWorldHandler.getSchematicWorld() == null && System.currentTimeMillis() < this.deadline)
            {
                return false;
            }

            this.before = manager.getEntries();
            manager.markDirty();
            this.deadline = System.currentTimeMillis() + COUNT_MS;
            return false;
        }

        // Третий этап: долистать HUD до страницы со строкой с данными и снять экран.
        if (this.shotTicks >= 0)
        {
            if (++this.shotTicks == 1)
            {
                if (! manager.isHudEnabled())
                {
                    manager.toggleHud();
                }

                for (int i = 0; i < 80 && manager.getPageEntries().stream().noneMatch(e -> DataItems.hasData(e.getStack())); ++i)
                {
                    manager.scrollPage(1);
                }

                CovOut.log("HUD на странице " + (manager.getPage() + 1) + ", строк с данными на ней: "
                        + manager.getPageEntries().stream().filter(e -> DataItems.hasData(e.getStack())).count());
            }

            // Харнесс дёргает step несколько раз за тик, а кадр с новой страницей рисуется между
            // тиками — ждём по часам, а не по числу вызовов.
            if (this.shotTicks == 1)
            {
                this.shotAt = System.currentTimeMillis() + 2500L;
            }

            if (this.shotTicks == 2)
            {
                if (System.currentTimeMillis() < this.shotAt)
                {
                    --this.shotTicks;
                    return false;
                }

                Screenshot.grab(mc, false);
                CovOut.log("снимок HUD снят (run/screenshots), страница " + (manager.getPage() + 1));
                this.shotAt = System.currentTimeMillis() + 1500L;
            }

            if (this.shotTicks >= 3)
            {
                if (System.currentTimeMillis() < this.shotAt)
                {
                    --this.shotTicks;
                    return false;
                }

                return true;
            }

            return false;
        }

        List<MaterialListEntry> entries = manager.getEntries();
        boolean fresh = manager.hasData() && entries != this.before && ! entries.isEmpty();

        if (! fresh)
        {
            if (System.currentTimeMillis() < this.deadline)
            {
                return false;
            }

            CovOut.log("пересчёт не пришёл за " + COUNT_MS / 1000 + " с, строк " + entries.size());
            return true;
        }

        // Первый пересчёт приходит раньше, чем верификатор обойдёт все чанки схемы: узорные клетки
        // ещё считаются обычными. Снимаем проход только когда список не менялся STABLE_MS.
        String signature = signature(entries);

        if (! signature.equals(this.lastSignature))
        {
            this.lastSignature = signature;
            this.settledAt = System.currentTimeMillis() + STABLE_MS;
            return false;
        }

        if (System.currentTimeMillis() < this.settledAt)
        {
            return false;
        }

        this.lastSignature = "";
        this.dump(entries);

        if (this.pass == 0)
        {
            // Второй проход: три копии первого предмета с данными — в инвентарь клиента.
            // Берём узорную строку, у которой в списке есть и строка того же предмета БЕЗ узора:
            // так проверяется, что узорный баннер не идёт в зачёт обычному.
            for (MaterialListEntry entry : entries)
            {
                if (! DataItems.hasData(entry.getStack()))
                {
                    continue;
                }

                boolean plainToo = entries.stream().anyMatch(e -> ! DataItems.hasData(e.getStack())
                        && e.getStack().getItem() == entry.getStack().getItem());

                if (plainToo || this.given.isEmpty())
                {
                    this.given = entry.getStack().copyWithCount(3);
                }

                if (plainToo)
                {
                    break;
                }
            }

            if (! this.given.isEmpty())
            {
                mc.player.getInventory().add(this.given.copy());
                CovOut.log("положил в инвентарь: " + DataItems.label(this.given) + " ×3");
            }

            if (this.given.isEmpty())
            {
                CovOut.log("в списке нет ни одной строки с данными блока — второй проход не нужен");
                return true;
            }

            this.pass = 1;
            this.before = entries;
            manager.markDirty();
            this.deadline = System.currentTimeMillis() + COUNT_MS;
            return false;
        }

        if (this.pass == 1)
        {
            // Третий проход: к узорным баннерам добавляем 2 ОБЫЧНЫХ баннера того же предмета —
            // у строки без узора «есть» должно стать 2, у узорных строк остаться как было.
            ItemStack plain = ItemStack.EMPTY;

            for (MaterialListEntry entry : entries)
            {
                if (! DataItems.hasData(entry.getStack()) && entry.getStack().getItem() == this.given.getItem())
                {
                    plain = new ItemStack(entry.getStack().getItem(), 2);
                    break;
                }
            }

            if (! plain.isEmpty())
            {
                mc.player.getInventory().add(plain.copy());
                CovOut.log("положил в инвентарь: " + DataItems.label(plain) + " ×2 (без узора)");
                this.pass = 2;
                this.before = entries;
                manager.markDirty();
                this.deadline = System.currentTimeMillis() + COUNT_MS;
                return false;
            }
        }

        this.shotTicks = 0;
        return false;
    }

    private static String signature(List<MaterialListEntry> entries)
    {
        StringBuilder sb = new StringBuilder();

        for (MaterialListEntry entry : entries)
        {
            sb.append(DataItems.label(entry.getStack())).append('=').append(entry.getCountTotal())
                    .append('/').append(entry.getCountAvailable()).append(';');
        }

        return sb.toString();
    }

    private void dump(List<MaterialListEntry> entries)
    {
        int withData = 0;

        for (MaterialListEntry entry : entries)
        {
            ItemStack stack = entry.getStack();
            boolean data = DataItems.hasData(stack);
            withData += data ? 1 : 0;
            this.out.row(List.of(String.valueOf(this.pass), DataItems.label(stack),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), String.valueOf(data),
                    String.valueOf(entry.getCountTotal()), String.valueOf(entry.getCountMissing()),
                    String.valueOf(entry.getCountMismatched()), String.valueOf(entry.getCountAvailable())));

            if (data || stack.getItem().toString().contains("banner") || stack.getItem().toString().contains("pot"))
            {
                CovOut.log("проход " + this.pass + ": " + DataItems.label(stack) + " — всего " + entry.getCountTotal()
                        + ", не хватает " + entry.getCountMissing() + ", не так " + entry.getCountMismatched()
                        + ", есть " + entry.getCountAvailable() + (data ? "" : " (без данных)"));
            }
        }

        CovOut.log("проход " + this.pass + ": строк " + entries.size() + ", с данными блока " + withData);
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            CovOut.log("дамп E готов: " + this.out.path() + ", строк " + this.out.doneCount());
            this.out.close();
        }
    }
}
