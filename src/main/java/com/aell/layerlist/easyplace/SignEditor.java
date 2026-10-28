package com.aell.layerlist.easyplace;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.List;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jetbrains.annotations.Nullable;

/**
 * Редактор табличек посреди стройки.
 *
 * Сервер после установки таблички зовёт {@code openTextEdit} и присылает
 * {@code ClientboundOpenSignEditorPacket} — в ванили это удобно, а при стройке по схеме
 * каждый поставленный указатель распахивает экран и выбивает игрока из ритма. Ответ на
 * пакет серверу при этом не обязателен быть из экрана: {@code ServerboundSignUpdatePacket}
 * принимается, пока сервер помнит игрока редактором таблички ({@code playerWhoMayEdit}
 * выставлен до отправки пакета).
 *
 * Поэтому свежепоставленная табличка схемы отвечается сразу текстом из схемы, без экрана.
 * «Свежепоставленная» отличается от ручного редактирования по тексту: у только что
 * поставленной таблички все строки пусты. Уже заполненная табличка — это игрок кликнул
 * «редактировать», и экран открывается как обычно; так схемную табличку можно поправить
 * руками, а заполнение из схемы не зацикливается.
 */
@Environment(EnvType.CLIENT)
public final class SignEditor
{
    private SignEditor() {}

    /** true — экран не открывать: табличка схемы заполнена без него. */
    public static boolean handleOpen(BlockPos pos, SignTextSlot slot)
    {
        if (! EasyPlaceModule.isActive())
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        WorldSchematic schematicWorld = schematicWorld();

        if (level == null || mc.player == null || mc.player.connection == null
                || schematicWorld == null)
        {
            return false;
        }

        if (! (schematicWorld.getBlockState(pos).getBlock() instanceof SignBlock)
                || ! (level.getBlockState(pos).getBlock() instanceof SignBlock))
        {
            return false;
        }

        // Непустая табличка — игрок открыл редактирование сам: экран как в ванили
        if (! blank(level.getBlockEntity(pos) instanceof SignBlockEntity sign
                ? sign.getText(slot)
                : null))
        {
            return false;
        }

        String[] lines = lines(schematicWorld, pos, slot);

        mc.player.connection.send(new ServerboundSignUpdatePacket(pos, List.of(lines), slot));
        EpDebug.say("табличка " + EpDebug.at(pos) + ": текст из схемы, редактор не открываем");

        return true;
    }

    private static boolean blank(@Nullable SignText text)
    {
        if (text == null)
        {
            return true;
        }

        for (int line = 0; line < 4; ++line)
        {
            if (! text.getMessages(false).get(line).getString().isEmpty())
            {
                return false;
            }
        }

        return true;
    }

    private static String[] lines(WorldSchematic schematicWorld, BlockPos pos, SignTextSlot slot)
    {
        String[] out = { "", "", "", "" };

        try
        {
            if (schematicWorld.getBlockEntity(pos) instanceof SignBlockEntity sign)
            {
                SignText text = sign.getText(slot);

                for (int line = 0; line < 4; ++line)
                {
                    out[line] = text.getMessages(false).get(line).getString();
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Текст не прочитался — табличка остаётся пустой, но экран всё равно не нужен
        }

        return out;
    }

    private static @Nullable WorldSchematic schematicWorld()
    {
        try
        {
            return SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
