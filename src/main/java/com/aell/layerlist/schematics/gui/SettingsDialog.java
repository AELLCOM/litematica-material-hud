package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.PreviewLimits;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Dropdown;
import com.aell.layerlist.ui.kit.FormRow;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toggle;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigString;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Окно настроек фичи (шестерёнка в шапке): опции {@link SchematicsConfig} — их нет в экране
 * настроек мода, потому что строк и выбора из списка его модель не умеет. Значения применяются
 * сразу, в файл ({@code layerlist.json}, через malilib) — при закрытии окна.
 */
@Environment(EnvType.CLIENT)
final class SettingsDialog
{
    private static final int WIDTH = 340;
    private static final int CONTROL_W = Ui.CTRL_W;

    private SettingsDialog() {}

    static void open(GuiSchematics screen)
    {
        Modal modal = new Modal(screen.layers, Tr.t("settings.title"), Strings.t("close"));
        modal.width(WIDTH);
        modal.onCancel(SettingsDialog::save);

        toggle(modal, SchematicsConfig.MAIN_MENU_BUTTON);
        toggle(modal, SchematicsConfig.LITEMATICA_SKIN);
        toggle(modal, SchematicsConfig.LITEMATICA_OWN_SCREENS);
        toggle(modal, SchematicsConfig.THUMBNAILS);

        // «Простое / Составное» — здесь, а не кнопками на вкладке «Выделение»; режим Litematica меняется сразу
        Toggle compound = new Toggle(SchematicsConfig.SELECTION_COMPOUND::getBooleanValue, value ->
        {
            SchematicsConfig.SELECTION_COMPOUND.setBooleanValue(value);
            screen.syncSelectionMode();
        });
        FormRow compoundRow = FormRow.inline(name(SchematicsConfig.SELECTION_COMPOUND), tooltip(SchematicsConfig.SELECTION_COMPOUND),
                compound, Math.max(compound.preferredWidth(Kit.font()), CONTROL_W));
        modal.row(compoundRow, compoundRow.height());

        // «Отображение схемы» — кнопкой над превью «Библиотеки» (PreviewPane), не здесь
        toggle(modal, SchematicsConfig.LIVE_GHOSTS);
        toggle(modal, SchematicsConfig.TOP_HIDE_CLOUDS);

        text(modal, SchematicsConfig.EXPORT_DIR, Tr.f("settings.export_dir.hint", SchematicsConfig.DEFAULT_EXPORT_FOLDER),
                false);
        Dropdown<SchematicsConfig.SheetsPalette> palette = new Dropdown<>(screen.layers,
                List.of(SchematicsConfig.SheetsPalette.values()), SchematicsConfig.sheetsPalette(),
                SchematicsConfig.SheetsPalette::getDisplayName, value ->
                {
                    SchematicsConfig.SHEETS_PALETTE.setOptionListValue(value);
                    save();
                });
        FormRow paletteRow = FormRow.inline(name(SchematicsConfig.SHEETS_PALETTE), tooltip(SchematicsConfig.SHEETS_PALETTE),
                palette, CONTROL_W);
        modal.row(paletteRow, paletteRow.height());

        text(modal, SchematicsConfig.SHEETS_WEB_APP_URL, Tr.t("settings.optional"), false);
        text(modal, SchematicsConfig.SHEETS_TOKEN, Tr.t("settings.optional"), true);

        modal.button(new Button(Tr.t("settings.defaults"), Button.Style.SECONDARY, () ->
        {
            for (IConfigBase option : SchematicsConfig.OPTIONS)
            {
                if (option instanceof IConfigResettable resettable)
                {
                    resettable.resetToDefault();
                }
            }

            modal.dismiss();
            save();
            open(screen);
        }).tooltip(Tr.t("settings.defaults.tip")));

        Button done = new Button(Tr.t("settings.done"), Button.Style.PRIMARY, () ->
        {
            modal.dismiss();
            save();
        });
        modal.button(done);
        modal.defaultButton(done);
        modal.open();
    }

    /** Выбор «Отображения схемы»: «Качественно» — после подтверждения (может просесть FPS и уйти много памяти). */
    private static void chooseQuality(GuiSchematics screen, Dropdown<SchematicsConfig.PreviewQuality> quality,
            SchematicsConfig.PreviewQuality value)
    {
        if (value == SchematicsConfig.PreviewQuality.QUALITY && SchematicsConfig.previewQuality() != value)
        {
            Modal.confirm(screen.layers, Tr.t("settings.quality.warn.title"), Tr.t("settings.quality.warn.text"),
                    Tr.t("settings.quality.warn.yes"), false, () ->
                    {
                        applyQuality(value);
                        quality.setValue(value);
                    }, Tr.t("cancel"));
            return;
        }

        applyQuality(value);
    }

    static void applyQuality(SchematicsConfig.PreviewQuality value)
    {
        SchematicsConfig.PREVIEW_QUALITY.setOptionListValue(value);
        // Пределы «Качественно» — от памяти на момент выбора; превью пересоберётся само (сменился бюджет)
        PreviewLimits.reset();
        save();
    }

    private static void toggle(Modal modal, ConfigBoolean option)
    {
        Toggle toggle = new Toggle(option::getBooleanValue, option::setBooleanValue);
        FormRow row = FormRow.inline(name(option), tooltip(option), toggle,
                Math.max(toggle.preferredWidth(Kit.font()), CONTROL_W));
        modal.row(row, row.height());
    }

    private static void text(Modal modal, ConfigString option, String hint, boolean secret)
    {
        TextInput input = new TextInput();
        input.setText(option.getStringValue());

        // Enter в поле — только закончить ввод; окно закрывается кнопкой «Готово»
        input.hint(hint).maxLength(512).masked(secret).onChange(option::setValueFromString)
                .onSubmit(value -> input.setFocused(false));
        FormRow row = FormRow.stacked(name(option), tooltip(option), input);
        modal.row(row, row.height());
    }

    private static String name(IConfigBase option)
    {
        return Strings.plain("layerlist.config." + option.getName() + ".name");
    }

    private static @Nullable String tooltip(IConfigBase option)
    {
        String text = Strings.raw("layerlist.config." + option.getName() + ".tooltip", "");
        return text.isEmpty() ? null : text;
    }

    private static void save()
    {
        new LayerListConfigs().save();
    }
}
