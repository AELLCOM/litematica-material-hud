package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toggle;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskSaveSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.FileNameUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * «Сохранить схему» во «Выделении»: плитка с именем схемы и опциями сохранения Litematica (сущности, мир голограмм,
 * только видимые блоки, блоки поддержки). Сохраняет задачей Litematica в текущую папку «Библиотеки»; готово —
 * игрока переводит в «Библиотеку», где новая схема сверху в жёлтой рамке с «NEW».
 */
@Environment(EnvType.CLIENT)
final class SaveSchematicDialog
{
    private static final String EXT = ".litematic";

    // Выбор опций помнится до выхода из игры, как флажки в окне Litematica
    private static boolean ignoreEntities;
    private static boolean fromSchematicWorld;
    private static boolean visibleOnly;
    private static boolean supportBlocks;

    private SaveSchematicDialog() {}

    static void open(GuiSchematics screen, AreaSelection area)
    {
        String suggested = FileNameUtils.generateSafeFileName(area.getName());

        if (Configs.Generic.GENERATE_LOWERCASE_NAMES.getBooleanValue())
        {
            suggested = FileNameUtils.generateSimpleSafeFileName(suggested);
        }

        TextInput name = new TextInput().maxLength(128).hint(Tr.t("save.name.hint"));
        name.setText(suggested);

        Modal modal = new Modal(screen.layers, Tr.t("save.title"), Tr.t("cancel"));
        Runnable save = () -> attempt(screen, modal, area, name.text(), false);
        name.onSubmit(value -> save.run());

        Button cancel = new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel);
        Button ok = new Button(Tr.t("save.ok"), Button.Style.PRIMARY, save).accent(Theme.GREEN);

        modal.width(300)
                .text(Tr.f("save.text", screen.libraryFolderName()))
                .row(name, Ui.CTRL_H)
                .row(new Toggle(Tr.t("save.ignore_entities"), () -> ignoreEntities, v -> ignoreEntities = v), Ui.CTRL_H)
                .row(new Toggle(Tr.t("save.from_schematic_world"), () -> fromSchematicWorld, v -> fromSchematicWorld = v)
                        .tooltip(Tr.t("save.from_schematic_world.tip")), Ui.CTRL_H)
                .row(new Toggle(Tr.t("save.visible_only"), () -> visibleOnly, v -> visibleOnly = v)
                        .tooltip(Tr.t("save.visible_only.tip")), Ui.CTRL_H)
                .row(new Toggle(Tr.t("save.support_blocks"), () -> supportBlocks, v -> supportBlocks = v)
                        .tooltip(Tr.t("save.support_blocks.tip")), Ui.CTRL_H)
                .button(cancel)
                .button(ok)
                .defaultButton(ok)
                .open();
        name.setFocused(true);
    }

    /** Имя → безопасное имя файла, как у Litematica; занято — спросить, заменить ли. */
    private static void attempt(GuiSchematics screen, Modal modal, AreaSelection area, String typed, boolean overwrite)
    {
        String fileName = FileNameUtils.generateSimpleUnicodeSafeFileName(typed.trim());

        if (FileNameUtils.doesFileNameContainIllegalCharacters(fileName))
        {
            fileName = FileNameUtils.generateSafeFileName(fileName);
        }

        if (fileName.endsWith(EXT))
        {
            fileName = fileName.substring(0, fileName.length() - EXT.length());
        }

        if (fileName.isEmpty())
        {
            screen.toast(Tr.t("save.empty_name"), Ui.C_WARN);
            return;
        }

        Path dir = screen.libraryFolder();
        Path file = dir.resolve(fileName + EXT).toAbsolutePath().normalize();

        if (! Files.isDirectory(dir))
        {
            screen.toast(Tr.f("save.no_folder", dir.toString()), Ui.C_DANGER);
            return;
        }

        if (Files.exists(file) && ! overwrite)
        {
            String finalName = fileName;
            Modal.confirm(screen.layers, Tr.t("save.exists.title"), Tr.f("save.exists.text", file.getFileName().toString()),
                    Tr.t("save.exists.yes"), true, () -> attempt(screen, modal, area, finalName, true), Tr.t("cancel"));
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null)
        {
            screen.toast(Tr.t("place.need_world"), Ui.C_WARN);
            return;
        }

        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, mc.player.getName().getString());

        if (schematic == null)
        {
            screen.toast(Tr.t("save.failed"), Ui.C_DANGER);
            return;
        }

        // Имя схемы внутри файла — то, что ввёл игрок (Litematica взяла бы имя выделения)
        schematic.getMetadata().setName(typed.trim().isEmpty() ? fileName : typed.trim());
        LitematicaSchematic.SchematicSaveInfo info = new LitematicaSchematic.SchematicSaveInfo(visibleOnly, supportBlocks,
                ignoreEntities, fromSchematicWorld);
        TaskSaveSchematic task = new TaskSaveSchematic(dir, fileName, schematic, area, info, overwrite);
        task.setCompletionListener(new ICompletionListener()
        {
            @Override
            public void onTaskCompleted()
            {
                screen.showSaved(file);
            }

            @Override
            public void onTaskAborted()
            {
                screen.toast(Tr.t("save.failed"), Ui.C_DANGER);
            }
        });
        TaskScheduler.getServerInstanceIfExistsOrClient().scheduleTask(task, 10);
        modal.dismiss();
        screen.toast(Tr.t("save.started"), Ui.C_TEXT_2);
    }
}
