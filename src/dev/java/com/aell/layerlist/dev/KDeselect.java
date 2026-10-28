package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Table;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27: «Выбрать» на подвкладке «Размещения» — переключатель. На выбранном в Litematica
 * размещении кнопка подвала называется «Снять выбор» и снимает выбор ({@code setSelectedSchematicPlacement(null)}:
 * ничего не выбрано, открытый список материалов Litematica закрыт); на другом — «Выбрать». Кнопка нажимается
 * кликом в её точку экрана; снимки до и после.
 */
@Environment(EnvType.CLIENT)
final class KDeselect
{
    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private @Nullable CompletableFuture<LoadedSchematic> loading;

    KDeselect(DumpSchematics k)
    {
        this.k = k;
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "размещения: два размещения образца", 15_000L, mc -> this.placeTwo()));
        s.add(step(p + "подвкладка «Размещения», строка выбранного", 3_000L, mc -> this.openTab()));
        s.add(step(p + "кнопка «Снять выбор» на выбранном", 3_000L, this.k.settleThen(300L, mc -> this.label("placements.deselect"))));
        s.add(step(p + "снимок: размещение выбрано", 5_000L, KTrash.cleanShot(this.k, p + "placements-selected")));
        s.add(step(p + "«Снять выбор» → ничего не выбрано", 3_000L, mc -> this.press("placements.deselect", false)));
        s.add(step(p + "кнопка снова «Выбрать»", 3_000L, this.k.settleThen(300L, mc -> this.label("placements.select"))));
        s.add(step(p + "снимок: выбор снят", 5_000L, KTrash.cleanShot(this.k, p + "placements-deselected")));
        s.add(step(p + "«Выбрать» → снова выбрано", 3_000L, mc -> this.press("placements.select", true)));
        s.add(step(p + "выделить другое размещение", 3_000L, mc -> this.otherRow()));
        s.add(step(p + "на другом размещении — «Выбрать»", 3_000L, this.k.settleThen(300L, mc -> this.label("placements.select"))));
        s.add(step(p + "размещения: убрать за собой", 3_000L, mc -> this.tidy()));
        return s;
    }

    void cleanup()
    {
        this.tidy();
    }

    private @Nullable Object pane()
    {
        return KProbe.fieldNamedType(this.k.gui(), "PlacementsPane");
    }

    @SuppressWarnings("unchecked")
    private @Nullable Table<SchematicPlacement> table()
    {
        return KProbe.fieldOfType(this.pane(), Table.class);
    }

    private boolean placeTwo()
    {
        SchematicEntry sample = this.k.generated(".litematic");

        if (sample == null)
        {
            this.k.fail("размещения: нет k-generated.litematic");
            return true;
        }

        if (this.loading == null)
        {
            this.loading = SchematicLibrary.get().load(sample);
        }

        if (! this.loading.isDone())
        {
            return false;
        }

        CompletableFuture<LoadedSchematic> done = this.loading;
        this.loading = null;

        if (done.isCompletedExceptionally())
        {
            this.k.fail("размещения: загрузка образца не удалась");
            return true;
        }

        this.made.add(PlacementOps.placeAt(done.join(), DumpSchematics.STAND.offset(-14, 0, 12)));
        this.made.add(PlacementOps.placeAt(done.join(), DumpSchematics.STAND.offset(-14, 0, 22)));
        PlacementOps.select(this.made.getFirst());
        this.k.check("выбрано первое размещение", PlacementOps.selected() == this.made.getFirst());
        return true;
    }

    private boolean openTab()
    {
        if (this.made.isEmpty())
        {
            return true;
        }

        this.k.setTab("PLACEMENTS");
        Table<SchematicPlacement> table = this.table();

        try
        {
            Object pane = this.pane();

            if (pane != null)
            {
                KProbe.call(pane, "reload");
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("reload(): " + e);
        }

        if (table == null)
        {
            this.k.fail("размещения: нет таблицы");
            return true;
        }

        table.setSelected(this.made.getFirst());
        this.k.check("в таблице выделено выбранное", table.selectedItem() == this.made.getFirst());
        return true;
    }

    private @Nullable Button choose()
    {
        Object pane = this.pane();
        Button button = KProbe.button(pane, "layerlist.schematics.placements.deselect");
        return button != null ? button : KProbe.button(pane, "layerlist.schematics.placements.select");
    }

    private boolean label(String key)
    {
        Button button = this.choose();
        String expected = Strings.plain("layerlist.schematics." + key);
        this.k.check("кнопка подвала «" + expected + "»", expected, button == null ? "—" : button.label());

        if (button != null)
        {
            GuiSchematics gui = this.k.gui();
            boolean inside = gui != null && button.visible() && button.x() >= 0 && button.x() + button.w() <= gui.width
                    && button.y() + button.h() <= gui.height;
            this.k.check("кнопка «" + expected + "» видна в подвале целиком", inside);
        }

        return true;
    }

    private boolean press(String key, boolean expectSelected)
    {
        GuiSchematics gui = this.k.gui();
        Button button = this.choose();
        String label = Strings.plain("layerlist.schematics." + key);

        if (gui == null || button == null || ! label.equals(button.label()))
        {
            this.k.fail("нет кнопки «" + label + "»");
            return true;
        }

        this.k.clickButton(gui, button, null);
        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (expectSelected)
        {
            this.k.check("«" + label + "» выбрало размещение", selected == this.made.getFirst());
        }
        else
        {
            this.k.check("«" + label + "»: в Litematica ничего не выбрано", selected == null);
            this.k.check("«" + label + "»: размещения на месте", PlacementOps.all().containsAll(this.made));
        }

        return true;
    }

    private boolean otherRow()
    {
        Table<SchematicPlacement> table = this.table();

        if (table == null || this.made.size() < 2)
        {
            return true;
        }

        // Подвал перестраивается в кадре — подпись проверяет следующий шаг
        table.setSelected(this.made.get(1));
        return true;
    }

    private boolean tidy()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        return true;
    }
}
