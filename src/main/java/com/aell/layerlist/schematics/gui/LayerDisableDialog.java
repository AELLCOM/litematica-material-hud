package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.BuildSpot;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.FormRow;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Slider;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * «Отключить на слое…» строки таблицы «Материалы» (1.0.12): номер слоя полем ввода и ползунком, оба — от 1
 * (нижний слой схемы) до числа слоёв схемы, друг за другом. Под ними — сколько клеток блока строки на этом
 * слое и, если схема размещена, его мировой Y. Нечего отключать (блока на слое нет или он там уже
 * отключён) — кнопка «Отключить» неактивна.
 *
 * <p>Слой, с которого диалог открывается (1.0.14): тот, на котором игрок строит, — Litematica «один слой» по высоте,
 * иначе слой последнего поставленного в эту схему блока; неизвестен — нижний. Тот же слой отключается и в один
 * щелчок — пункт «Отключить на текущем слое».
 */
@Environment(EnvType.CLIENT)
final class LayerDisableDialog
{
    private static final int FIELD_W = 44;

    private LayerDisableDialog() {}

    /**
     * Открыть диалог.
     *
     * @param onDisable номер слоя (0 — нижний), выбранный игроком; зовётся после закрытия окна
     */
    static void open(Layers layers, LitematicaSchematic schematic, MaterialLine line, IntConsumer onDisable)
    {
        int count = BlockReplacements.layerCount(schematic);

        if (count <= 0)
        {
            return;
        }

        Modal modal = new Modal(layers, Tr.f("materials.layer.title", line.name()), Tr.t("cancel"));
        modal.width(300);
        modal.text(Tr.f("materials.layer.text", count));

        Info info = new Info(schematic, line);
        Picker picker = new Picker(count, startLayer(schematic, count), info::show);
        FormRow field = FormRow.stacked(Tr.t("materials.layer.field"), Tr.f("materials.layer.field.tip", count), picker);
        modal.row(field, field.height());
        modal.row(info, Ui.TIP_LINE + Ui.PAD_MICRO);

        Button disable = new Button(Tr.t("materials.layer.disable"), Button.Style.PRIMARY, () ->
        {
            int layer = picker.layer();

            if (info.countFor(layer) > 0L)
            {
                modal.dismiss();
                onDisable.accept(layer - 1);
            }
        });
        info.button = disable;
        modal.button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel));
        modal.button(disable);
        modal.defaultButton(disable);
        info.show(picker.layer());
        modal.open();
    }

    /** Слой, с которого открывается окно: текущий, если он известен, иначе нижний. */
    private static int startLayer(LitematicaSchematic schematic, int count)
    {
        int here = currentLayer(schematic);
        return here >= 1 && here <= count ? here : 1;
    }

    /**
     * Слой, на котором игрок строит, в номерах схемы (1 — нижний); неизвестен — 0. Сначала Litematica «один слой» по
     * высоте (его и показывает список HUD), иначе — слой последнего поставленного блока, если он лёг в размещение
     * этой схемы ({@link BuildSpot}).
     */
    static int currentLayer(LitematicaSchematic schematic)
    {
        try
        {
            int count = BlockReplacements.layerCount(schematic);
            int base = BlockReplacements.layerBase(schematic);
            LayerRange range = DataManager.getRenderLayerRange();
            Integer origin = originY(schematic);

            if (origin != null && range.getLayerMode() == LayerMode.SINGLE_LAYER && range.getAxis() == Direction.Axis.Y)
            {
                int layer = range.getLayerSingle() - origin - base + 1;

                if (layer >= 1 && layer <= count)
                {
                    return layer;
                }
            }

            BlockPos spot = BuildSpot.last();

            if (spot != null)
            {
                for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic))
                {
                    if (inside(placement, spot))
                    {
                        int layer = spot.getY() - placement.getOrigin().getY() - base + 1;

                        if (layer >= 1 && layer <= count)
                        {
                            return layer;
                        }
                    }
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Нет размещения или другая Litematica — текущего слоя нет
        }

        return 0;
    }

    /** Клетка внутри одного из включённых регионов размещения. */
    private static boolean inside(SchematicPlacement placement, BlockPos pos)
    {
        for (fi.dy.masa.litematica.selection.Box box : placement.getSubRegionBoxes(
                fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
        {
            BlockPos a = box.getPos1();
            BlockPos b = box.getPos2();

            if (a != null && b != null
                    && pos.getX() >= Math.min(a.getX(), b.getX()) && pos.getX() <= Math.max(a.getX(), b.getX())
                    && pos.getY() >= Math.min(a.getY(), b.getY()) && pos.getY() <= Math.max(a.getY(), b.getY())
                    && pos.getZ() >= Math.min(a.getZ(), b.getZ()) && pos.getZ() <= Math.max(a.getZ(), b.getZ()))
            {
                return true;
            }
        }

        return false;
    }

    /** Y начала первого размещения схемы; не размещена — {@code null}. */
    private static @Nullable Integer originY(LitematicaSchematic schematic)
    {
        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic))
            {
                return placement.getOrigin().getY();
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }

        return null;
    }

    /** Поле номера слоя и ползунок в одну строку, синхронно. */
    private static final class Picker extends Widget
    {
        private final TextInput field;
        private final Slider slider;
        private final IntConsumer onChange;
        private boolean syncing;

        private Picker(int count, int start, IntConsumer onChange)
        {
            this.onChange = onChange;
            this.field = new TextInput().maxLength(String.valueOf(count).length()).centered(true)
                    .filter(c -> c >= '0' && c <= '9');
            this.slider = new Slider(1, count, start, this::slid);
            this.field.setText(String.valueOf(start));
            this.field.onChange(this::typed).onSubmit(text -> this.field.setFocused(false));
        }

        int layer()
        {
            return this.slider.value();
        }

        private void slid(int value)
        {
            if (! this.syncing)
            {
                this.syncing = true;
                this.field.setText(String.valueOf(value));
                this.syncing = false;
            }

            this.onChange.accept(value);
        }

        /** Ввод в поле: число за пределами — к ближнему краю на ползунке, поле не трогаем, пока игрок печатает. */
        private void typed(String text)
        {
            if (this.syncing || text.isEmpty())
            {
                return;
            }

            int value;

            try
            {
                value = Integer.parseInt(text);
            }
            catch (NumberFormatException e)
            {
                return;
            }

            value = Math.max(this.slider.min(), Math.min(this.slider.max(), value));
            this.syncing = true;
            this.slider.setRange(this.slider.min(), this.slider.max(), value);
            this.syncing = false;
            this.onChange.accept(value);
        }

        @Override
        public void setBounds(int x, int y, int w, int h)
        {
            super.setBounds(x, y, w, h);
            this.field.setBounds(x, y, FIELD_W, h);
            this.slider.setBounds(x + FIELD_W + Ui.PAD_REL, y, Math.max(0, w - FIELD_W - Ui.PAD_REL), h);
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            // Поле ушло из фокуса с числом за пределами или пустым — показать то, что выбрано на самом деле
            if (! this.field.focused() && ! this.field.text().equals(String.valueOf(this.slider.value())))
            {
                this.syncing = true;
                this.field.setText(String.valueOf(this.slider.value()));
                this.syncing = false;
            }

            this.field.render(ctx, mouseX, mouseY);
            this.slider.render(ctx, mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            if (this.field.mouseClicked(click, doubleClick))
            {
                return true;
            }

            this.field.setFocused(false);
            return this.slider.mouseClicked(click, doubleClick);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
        {
            return this.field.mouseDragged(click, dx, dy) || this.slider.mouseDragged(click, dx, dy);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent click)
        {
            boolean field = this.field.mouseReleased(click);
            return this.slider.mouseReleased(click) || field;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount)
        {
            // Колесо над полем — тоже по слою: поле узкое, промахнуться легко
            if (this.field.contains(mouseX, mouseY))
            {
                int next = Math.max(this.slider.min(), Math.min(this.slider.max(), this.slider.value() + (amount > 0 ? 1 : -1)));
                this.slider.setRange(this.slider.min(), this.slider.max(), next);
                this.slid(next);
                return true;
            }

            return this.slider.mouseScrolled(mouseX, mouseY, amount);
        }

        @Override
        public boolean keyPressed(KeyEvent key)
        {
            return this.field.keyPressed(key);
        }

        @Override
        public boolean charTyped(CharacterEvent event)
        {
            return this.field.charTyped(event);
        }

        @Override
        public @Nullable TextInput textInput()
        {
            return this.field;
        }
    }

    /** Строка под ползунком: сколько блоков строки на слое и мировой Y; заодно держит кнопку «Отключить». */
    private static final class Info extends Widget
    {
        private final LitematicaSchematic schematic;
        private final MaterialLine line;
        private final Map<Integer, Long> counts = new HashMap<>();
        private final @Nullable Integer originY;
        private final int base;
        private @Nullable Button button;
        private String text = "";
        private int color = Ui.C_TEXT_2;

        private Info(LitematicaSchematic schematic, MaterialLine line)
        {
            this.schematic = schematic;
            this.line = line;
            this.originY = originY(schematic);
            this.base = BlockReplacements.layerBase(schematic);
        }

        long countFor(int layer)
        {
            return this.counts.computeIfAbsent(layer,
                    l -> BlockReplacements.countOnLayer(this.schematic, this.line.stack().getItem(), l - 1));
        }

        void show(int layer)
        {
            long count = this.countFor(layer);
            String where = this.originY == null ? "" : Tr.f("materials.layer.world_y", this.originY + this.base + layer - 1);

            if (count > 0L)
            {
                this.text = Tr.f("materials.layer.count", layer, Tr.num(count)) + where;
                this.color = Ui.C_TEXT_2;
            }
            else
            {
                this.text = Tr.f("materials.layer.none", layer) + where;
                this.color = Ui.C_WARN;
            }

            if (this.button != null)
            {
                this.button.setEnabled(count > 0L);
            }
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            Font font = Kit.font();
            Paint.midText(ctx, font, Paint.fit(font, this.text, this.w), this.x, this.y, this.h, this.color, false);
        }
    }
}
