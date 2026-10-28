package com.aell.ui;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class Model
{
    private Model() {}

    /** Рисунок строки-карточки ({@link Option#card}). Координаты — пиксели GUI. */
    public interface Card
    {
        /** Высота при ширине {@code width}. */
        int height(int width);

        void draw(GuiContext ctx, net.minecraft.client.gui.Font font, int x, int y, int w, int h, int mouseX, int mouseY,
                boolean focused);

        /** Щелчок левой кнопкой в карточке; истина — обработан. */
        boolean click(double mouseX, double mouseY, int x, int y, int w, int h);
    }

    public enum Kind
    {
        TOGGLE,
        INT,
        DOUBLE,
        KEYBIND,
        ACTION,
        /** Выбор из нескольких вариантов: кнопка перебирает их по кругу. */
        CHOICE
    }

    public record Reason(String text, boolean foreign)
    {
        public int color()
        {
            return this.foreign ? Ui.C_FOREIGN : Ui.C_WARN;
        }
    }

    public interface Preview
    {
        int width();

        void draw(GuiContext ctx, int x, int rowTop, Option option);
    }

    public static final class Option
    {
        public final String key;
        public final Kind kind;

        public final @Nullable Values.Bool bool;
        public final @Nullable Values.Int number;
        public final @Nullable Values.Dbl decimal;
        public final @Nullable Values.Key hotkey;
        public final @Nullable IKeybind keybind;
        /** Варианты выбора ({@link Kind#CHOICE}). */
        public @Nullable Values.Choice choice;
        public @Nullable Runnable action;

        public Num.Scale scale = Num.Scale.LINEAR;

        public String format = "%.2f";

        public IntSupplier swatch = () -> 0;

        public Supplier<@Nullable Reason> gate = () -> null;

        public Supplier<List<String>> foreignTip = List::of;

        public @Nullable Preview preview;

        public boolean child;

        /** Кнопка строки-действия: 0 — обычная (рамка акцента), 1 — главная (приглушённая заливка), 2 — опасная. */
        public int actionStyle;
        /** Своя надпись на кнопке строки-действия (ключ перевода); {@code null} — «Открыть». */
        public @Nullable String actionLabel;
        /** Тон главной кнопки-действия ({@link #primary()}); 0 — синий акцент. */
        public int actionColor;
        /** Подписи состояний тумблера вместо «Вкл/Выкл» (ключи перевода); {@code null} — обычные. */
        public @Nullable String onLabel;
        public @Nullable String offLabel;
        /** Строка-карточка: свой рисунок во всю ширину и своя высота (главное действие вкладки). */
        public @Nullable Card card;
        /** Жёлтый треугольник «!» перед названием: опция тяжёлая или рискованная. */
        public boolean warning;
        /**
         * Включение тумблера — через подтверждение: экран отдаёт сюда «включить», мод спрашивает игрока и зовёт его, если
         * тот согласен; выключение — сразу. {@code null} — без вопроса.
         */
        public java.util.function.@Nullable Consumer<Runnable> confirmEnable;

        private Option(String key, Kind kind, @Nullable Values.Bool bool, @Nullable Values.Int number,
                @Nullable Values.Dbl decimal, @Nullable Values.Key hotkey, @Nullable IKeybind keybind)
        {
            this.key = key;
            this.kind = kind;
            this.bool = bool;
            this.number = number;
            this.decimal = decimal;
            this.hotkey = hotkey;
            this.keybind = keybind;
        }

        /** Выбор из нескольких вариантов: щелчок — следующий, с Shift — предыдущий. */
        public static Option choice(String key, Values.Choice value)
        {
            Option option = new Option(key, Kind.CHOICE, null, null, null, null, null);
            option.choice = value;

            return option;
        }

        public static Option toggle(String key, Values.Bool value)
        {
            return new Option(key, Kind.TOGGLE, value, null, null, null, null);
        }

        public static Option integer(String key, Values.Int value, Num.Scale scale)
        {
            Option option = new Option(key, Kind.INT, null, value, null, null, null);
            option.scale = scale;

            return option;
        }

        public static Option decimal(String key, Values.Dbl value, String format)
        {
            Option option = new Option(key, Kind.DOUBLE, null, null, value, null, null);
            option.format = format;

            return option;
        }

        public static Option keybind(String key, Values.Key value, IKeybind bind)
        {
            return new Option(key, Kind.KEYBIND, null, null, null, value, bind);
        }

        public static Option action(String key, Runnable action)
        {
            Option option = new Option(key, Kind.ACTION, null, null, null, null, null);
            option.action = action;

            return option;
        }

        /**
         * Карточка вместо строки: рисует себя сама во всю ширину раздела, высота — её; Enter с клавиатуры —
         * {@code action}. Ищется по имени {@code key}, как обычная строка.
         */
        public static Option card(String key, Card card, Runnable action)
        {
            Option option = action(key, action);
            option.card = card;

            return option;
        }

        /** Знак предупреждения у названия (см. {@link #warning}). */
        public Option warning()
        {
            this.warning = true;
            return this;
        }

        /** Спросить перед включением (см. {@link #confirmEnable}). */
        public Option confirmEnable(java.util.function.Consumer<Runnable> ask)
        {
            this.confirmEnable = ask;
            return this;
        }

        public Option swatch(IntSupplier color)
        {
            this.swatch = color;

            return this;
        }

        public Option gate(Supplier<@Nullable Reason> reason)
        {
            this.gate = reason;

            return this;
        }

        public Option gateWhen(BooleanSupplier blocked, Supplier<String> text, boolean foreign)
        {
            this.gate = () -> blocked.getAsBoolean() ? new Reason(text.get(), foreign) : null;

            return this;
        }

        public Option foreignTip(Supplier<List<String>> lines)
        {
            this.foreignTip = lines;

            return this;
        }

        public Option preview(Preview preview)
        {
            this.preview = preview;

            return this;
        }

        /** Главное действие раздела: кнопка с приглушённой акцентной заливкой. */
        public Option primary()
        {
            this.actionStyle = 1;
            return this;
        }

        /** Опасное действие (сброс, удаление): красная кнопка. */
        public Option danger()
        {
            this.actionStyle = 2;
            return this;
        }

        /** Своя надпись на кнопке вместо «Открыть». */
        /** Главная кнопка своим тоном (например, фиолетовым — «про другой мод»). */
        public Option accent(int color)
        {
            this.actionStyle = 1;
            this.actionColor = color;

            return this;
        }

        /** Тумблер с подписями состояний: «Качество» / «Оптимизированно» вместо «Вкл» / «Выкл». */
        public Option states(String onKey, String offKey)
        {
            this.onLabel = onKey;
            this.offLabel = offKey;

            return this;
        }

        public Option buttonLabel(String key)
        {
            this.actionLabel = key;
            return this;
        }

        public Option child()
        {
            this.child = true;

            return this;
        }

        public boolean isDefault()
        {
            return switch (this.kind)
            {
                case TOGGLE -> this.bool == null || this.bool.isDefault();
                case INT -> this.number == null || this.number.isDefault();
                case DOUBLE -> this.decimal == null || this.decimal.isDefault();
                case KEYBIND -> this.hotkey == null || this.hotkey.isDefault();
                case ACTION -> true;
                case CHOICE -> this.choice == null || this.choice.isDefault();
            };
        }

        public void resetToDefault()
        {
            switch (this.kind)
            {
                case TOGGLE -> { if (this.bool != null) { this.bool.reset(); } }
                case INT -> { if (this.number != null) { this.number.reset(); } }
                case DOUBLE -> { if (this.decimal != null) { this.decimal.reset(); } }
                case KEYBIND -> { if (this.hotkey != null) { this.hotkey.reset(); } }
                case ACTION -> { }
                case CHOICE -> { if (this.choice != null) { this.choice.reset(); } }
            }
        }

        public String defaultText()
        {
            return switch (this.kind)
            {
                case TOGGLE -> this.bool == null ? ""
                        : this.onLabel != null && this.offLabel != null
                                ? Strings.plain(this.bool.defaultValue() ? this.onLabel : this.offLabel)
                                : Strings.onOff(this.bool.defaultValue());
                case INT -> this.number == null ? "" : String.valueOf(this.number.defaultValue());
                case DOUBLE -> this.decimal == null ? ""
                        : Num.format(this.decimal.defaultValue(), this.format);
                case KEYBIND ->
                {
                    if (this.hotkey == null)
                    {
                        yield "";
                    }

                    String value = this.hotkey.defaultDisplay();
                    yield value.isEmpty() ? Strings.notSet() : value;
                }
                case ACTION -> "";
                case CHOICE -> this.choice == null ? "" : this.choice.label(this.choice.defaultIndex());
            };
        }
    }

    public static final class Section
    {
        public final String key;
        public final List<Option> options = new ArrayList<>();

        public @Nullable String noteKey;

        public Supplier<@Nullable String> warning = () -> null;

        /** Цвет заголовка (черта, текст, линия); 0 — синий акцент кита. */
        public int accent;

        public Section(String key)
        {
            this.key = key;
        }

        public Section note(String noteKey)
        {
            this.noteKey = noteKey;

            return this;
        }

        public Section warning(Supplier<@Nullable String> warning)
        {
            this.warning = warning;

            return this;
        }

        /** Свой цвет заголовка — по смыслу раздела (зоны — циан, инструмент — зелёный…). */
        public Section accent(int color)
        {
            this.accent = color;

            return this;
        }

        public Section add(Option... options)
        {
            this.options.addAll(List.of(options));

            return this;
        }
    }

    public static final class Tab
    {
        public final String key;
        public final List<Section> sections = new ArrayList<>();

        public @Nullable net.minecraft.resources.Identifier icon;

        /**
         * Вкладка-ссылка: клик открывает этот экран поверх настроек, а текущая вкладка не меняется
         * (возврат — по стеку {@link Nav}). Секций у такой вкладки нет; стрелки ←/→ её пропускают.
         */
        public @Nullable Supplier<Screen> opens;

        public Tab(String key)
        {
            this.key = key;
        }

        public Tab icon(@Nullable net.minecraft.resources.Identifier icon)
        {
            this.icon = icon;

            return this;
        }

        public Tab opens(Supplier<Screen> screen)
        {
            this.opens = screen;

            return this;
        }

        public Tab add(Section... sections)
        {
            this.sections.addAll(List.of(sections));

            return this;
        }

        public List<Option> allOptions()
        {
            List<Option> all = new ArrayList<>();

            for (Section section : this.sections)
            {
                all.addAll(section.options);
            }

            return all;
        }
    }

    /**
     * Кнопка в шапке настроек. {@code accent} — свой цвет кнопки (0 — без него): приглушённая заливка, рамка
     * и светлый текст этого тона, для главного действия мода рядом с поиском.
     */
    public record HeaderButton(String labelKey, boolean foreign, Runnable action, int accent)
    {
        public HeaderButton(String labelKey, boolean foreign, Runnable action)
        {
            this(labelKey, foreign, action, 0);
        }
    }

    public record Status(String text, int color, @Nullable String tabKey, @Nullable String optionKey) {}
}
