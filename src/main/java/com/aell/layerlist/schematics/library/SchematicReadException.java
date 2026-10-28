package com.aell.layerlist.schematics.library;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Схема не прочиталась или не загрузилась. Хранит ключ перевода и аргументы, а текст для игрока
 * собирает только в {@link #getMessage()} — так исключение можно создавать в фоновых потоках и в
 * проверке вне игры, а перевод берётся на языке, выбранном в момент показа.
 *
 * <p>Провалы будущих {@link SchematicLibrary} приходят как {@link java.util.concurrent.CompletionException}
 * с этим исключением в {@code getCause()}; карточке достаточно показать {@code getMessage()}.
 */
@Environment(EnvType.CLIENT)
public final class SchematicReadException extends Exception
{
    private static final long serialVersionUID = 1L;

    private final String key;
    // Аргументы — только для текста на экране; сериализовать исключение некуда
    private final transient Object[] args;

    public SchematicReadException(String key, Object... args)
    {
        this(null, key, args);
    }

    /** @param cause исходная ошибка — только для лога, игроку показывается текст ключа */
    public SchematicReadException(@Nullable Throwable cause, String key, Object... args)
    {
        // Стек самого исключения бесполезен (это «ожидаемая» ошибка файла), стек причины — в cause
        super(key, cause, false, false);
        this.key = key;
        this.args = args.clone();
    }

    /** Ключ перевода {@code layerlist.schematics.error.*}. */
    public String key()
    {
        return this.key;
    }

    @Override
    public String getMessage()
    {
        return I18n.get(this.key, this.args);
    }

    /** Текст причины из чужого исключения: сообщение, иначе имя класса. */
    static String reason(Throwable error)
    {
        String message = error.getMessage();

        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
