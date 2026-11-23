package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.place.PlacementGroups;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Окна групп размещений (заявка #5): «В группу…» — в какую положить (кнопки существующих групп или имя новой) и
 * «Переименовать группу…». Имя другой существующей группы при переименовании сливает группы в одну.
 */
@Environment(EnvType.CLIENT)
final class PlacementGroupFlow
{
    private static final int WIDTH = 300;
    /** Сколько существующих групп показать кнопками; остальные — вводом имени. */
    private static final int MAX_BUTTONS = 6;

    private PlacementGroupFlow() {}

    static void assign(GuiSchematics screen, List<SchematicPlacement> placements)
    {
        assign(screen, placements, () -> {});
    }

    static void assign(GuiSchematics screen, List<SchematicPlacement> placements, Runnable done)
    {
        if (placements.isEmpty())
        {
            return;
        }

        PlacementGroups.Group current = placements.size() == 1 ? PlacementGroups.get().of(placements.get(0)) : null;
        TextInput name = new TextInput().maxLength(PlacementGroups.MAX_NAME).hint(Tr.t("groups.assign.hint"));
        Modal modal = new Modal(screen.layers, Tr.t("groups.assign.title"), Tr.t("cancel"));
        Runnable apply = () -> attempt(screen, modal, placements, name.text(), done);
        name.onSubmit(value -> apply.run());
        modal.width(WIDTH).text(placements.size() == 1 ? Tr.f("groups.assign.text_one", placements.get(0).getName())
                : Tr.f("groups.assign.text", placements.size()));

        List<PlacementGroups.Group> groups = PlacementGroups.get().groups();
        int shown = 0;

        for (PlacementGroups.Group group : groups)
        {
            if (group == current || shown == MAX_BUTTONS)
            {
                continue;
            }

            ++shown;
            String target = group.name();
            modal.row(new Button(Tr.f("groups.assign.existing", target), Button.Style.SECONDARY,
                    () -> attempt(screen, modal, placements, target, done)), Ui.CTRL_H);
        }

        if (shown > 0)
        {
            modal.text(Tr.t("groups.assign.or_new"));
        }

        Button ok = new Button(Tr.t("groups.assign.ok"), Button.Style.PRIMARY, apply).accent(Theme.GREEN);
        modal.row(name, Ui.CTRL_H)
                .button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel))
                .button(ok)
                .defaultButton(ok)
                .open();
        name.setFocused(true);
    }

    private static void attempt(GuiSchematics screen, Modal modal, List<SchematicPlacement> placements, String typed, Runnable done)
    {
        String clean = PlacementGroups.clean(typed);

        if (clean.isEmpty())
        {
            screen.toast(Tr.t("groups.empty_name"), Ui.C_WARN);
            return;
        }

        modal.dismiss();
        PlacementGroups.Group group = PlacementGroups.get().add(clean, placements);
        done.run();
        screen.toast(Tr.f("groups.assign.done", placements.size(), group.name()), Theme.GREEN);
    }

    static void rename(GuiSchematics screen, PlacementGroups.Group group)
    {
        TextInput name = new TextInput().maxLength(PlacementGroups.MAX_NAME).hint(Tr.t("groups.assign.hint"));
        name.setText(group.name());
        Modal modal = new Modal(screen.layers, Tr.t("groups.rename.title"), Tr.t("cancel"));
        Runnable apply = () ->
        {
            String clean = PlacementGroups.clean(name.text());

            if (clean.isEmpty())
            {
                screen.toast(Tr.t("groups.empty_name"), Ui.C_WARN);
                return;
            }

            modal.dismiss();
            String old = group.name();
            PlacementGroups.get().rename(group, clean);

            if (! old.equals(clean))
            {
                screen.toast(Tr.f("groups.rename.done", old, clean), Theme.GREEN);
            }
        };
        name.onSubmit(value -> apply.run());

        Button ok = new Button(Tr.t("groups.rename.ok"), Button.Style.PRIMARY, apply).accent(Theme.GREEN);
        modal.width(WIDTH).text(Tr.t("groups.rename.text"))
                .row(name, Ui.CTRL_H)
                .button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel))
                .button(ok)
                .defaultButton(ok)
                .open();
        name.setFocused(true);
        name.selectAll();
    }
}
