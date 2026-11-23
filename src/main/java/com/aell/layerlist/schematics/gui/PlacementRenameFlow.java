package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.compat.Syncmatica;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Переименование размещения в подвкладке «Размещения» (меню ПКМ строки; заявка #6 на GitHub, 15.11.2026: «в
 * Litematica размещение можно переименовать, а в вашем обзоре нет»). Меняется только имя размещения — как полем
 * «Rename» в окне размещения Litematica ({@link SchematicPlacement#setName}): файл схемы, имя схемы в файле и
 * остальные размещения того же файла не трогаются. Litematica сохраняет имя со своими размещениями мира. Пустое имя
 * не принимается; общее размещение Syncmatica не переименовать — его имя у всех игроков сервера одно.
 */
@Environment(EnvType.CLIENT)
final class PlacementRenameFlow
{
    private static final int WIDTH = 300;
    static final int MAX_LENGTH = 128;

    private PlacementRenameFlow() {}

    /** Можно ли переименовать: не общее размещение Syncmatica. */
    static boolean allowed(SchematicPlacement placement)
    {
        return ! Syncmatica.shared(placement);
    }

    static void open(GuiSchematics screen, SchematicPlacement placement, Runnable done)
    {
        if (! allowed(placement))
        {
            screen.toast(Tr.t("placements.rename.shared"), Ui.C_WARN);
            return;
        }

        TextInput name = new TextInput().maxLength(MAX_LENGTH).hint(Tr.t("placements.rename.hint"));
        name.setText(placement.getName());

        Modal modal = new Modal(screen.layers, Tr.t("placements.rename.title"), Tr.t("cancel"));
        Runnable apply = () -> attempt(screen, modal, placement, name.text(), done);
        name.onSubmit(value -> apply.run());

        Button ok = new Button(Tr.t("placements.rename.ok"), Button.Style.PRIMARY, apply).accent(Theme.GREEN);
        modal.width(WIDTH).text(Tr.t("placements.rename.text"))
                .row(name, Ui.CTRL_H)
                .button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel))
                .button(ok)
                .defaultButton(ok)
                .open();
        name.setFocused(true);
        name.selectAll();
    }

    private static void attempt(GuiSchematics screen, Modal modal, SchematicPlacement placement, String typed, Runnable done)
    {
        String value = typed.strip();

        if (value.isEmpty())
        {
            screen.toast(Tr.t("placements.rename.empty"), Ui.C_WARN);
            return;
        }

        modal.dismiss();
        String old = placement.getName();

        if (value.equals(old))
        {
            return;
        }

        if (! rename(screen, placement, value))
        {
            return;
        }

        done.run();
        screen.toast(Tr.f("placements.rename.done", old, value), Theme.GREEN, Toasts.Action.once(Tr.t("remove.undo"), () ->
        {
            if (rename(screen, placement, old))
            {
                done.run();
                screen.toast(Tr.f("placements.rename.undone", old), Theme.GREEN);
            }
        }));
    }

    private static boolean rename(GuiSchematics screen, SchematicPlacement placement, String name)
    {
        try
        {
            placement.setName(name);
            return true;
        }
        catch (RuntimeException e)
        {
            screen.toast(Tr.f("placements.rename.failed", Tr.reason(e)), Ui.C_DANGER);
            return false;
        }
    }
}
