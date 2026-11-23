package com.aell.layerlist.ghost;

import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Курица в 3D у строки «Мобы в голограммах»: настоящая модель игры, как игрок в инвентаре, стоит вполоборота и покачивается.
 * Нужен мир (модели моба без него не собрать): в главном меню строка обходится без картинки.
 */
@Environment(EnvType.CLIENT)
public final class MobPreview implements Model.Preview
{
    private static final int WIDTH = 20;
    /**
     * Масштаб модели. Рамка курицы — 0,7 блока, но голова с гребнем выше рамки: вся модель около 0,94 блока, при 14 —
     * 13 px GUI, по 3–4 px до краёв строки в 20 px.
     */
    private static final int SIZE = 14;
    /**
     * Сдвиг вниз в блоках: игра ставит по центру строки рамку 0,7, а голова торчит над ней на 0,24 — без сдвига
     * курица прижата к верху и голову обрезает (снимок пользователя 14.11.2026).
     */
    private static final float DROP = 0.12F;
    /** Полный цикл покачивания, мс. */
    private static final long PERIOD = 6000L;
    /**
     * Смещение «взгляда» в px: курица стоит вполоборота и чуть покачивается, около 25° в обе стороны, без наклона
     * головы — взгляд на высоте центра строки.
     */
    private static final float TURN = 22.0F;
    private static final float SWAY = 14.0F;

    private @Nullable LivingEntity mob;
    private @Nullable Level level;
    private boolean failed;

    @Override
    public int width()
    {
        return WIDTH;
    }

    @Override
    public void draw(GuiContext ctx, int x, int rowTop, Model.Option option)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return;
        }

        if (this.mob == null || this.level != mc.level)
        {
            try
            {
                this.mob = EntityType.CHICKEN.create(mc.level, EntitySpawnReason.LOAD);

                // Модель спрашивает номер сущности (зерно для предметов в руке); в мир курица не попадает
                if (this.mob != null)
                {
                    this.mob.setId(-0x4D48);
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                this.mob = null;
            }

            this.level = mc.level;
        }

        if (this.mob == null)
        {
            return;
        }

        // «Курсор», за которым смотрит курица: левее центра (вполоборота) и плавно туда-сюда, на высоте центра строки —
        // голова не задирается и не опускается
        double turn = (Util.getMillis() % PERIOD) / (double) PERIOD * Math.PI * 2.0D;
        float cx = x + WIDTH / 2.0F;
        float cy = rowTop + Ui.ROW_H / 2.0F;
        float lookX = cx - TURN + (float) Math.sin(turn) * SWAY;
        float lookY = cy;

        try
        {
            InventoryScreen.renderEntityInInventoryFollowsMouse(ctx, x, rowTop, x + WIDTH, rowTop + Ui.ROW_H, SIZE,
                    DROP, lookX, lookY, this.mob);
        }
        catch (RuntimeException | LinkageError e)
        {
            // Рисунок — украшение: сбой модели не должен ронять экран настроек (в журнал — один раз)
            if (! this.failed)
            {
                this.failed = true;
                org.slf4j.LoggerFactory.getLogger("layerlist").warn("Material HUD: курица у опции не нарисована", e);
            }

            this.mob = null;
        }
    }
}
