package com.aell.layerlist.dev;

import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Режим {@code zfverify}: Zone Focus поверх Material HUD отдаёт проверку нам (запрос 09.10.2026). Нужен
 * jar Zone Focus в {@code run/mods}; всё — рефлексией.
 * <ul>
 *   <li>Zone Focus видит API 5 и считает проверку нашей;</li>
 *   <li>в его настройках нет вкладки «Проверка», клавиша проверки на месте (подпись «Material HUD»);</li>
 *   <li>его Ctrl+P включает и выключает нашу проверку (Ctrl+B), своя проверка Zone Focus молчит.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class ZfVerifyTest implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "ZFVERIFY (проверка Zone Focus → Material HUD)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            Object zm = Class.forName("com.aell.zonefocus.zone.ZoneManager").getMethod("getInstance").invoke(null);
            zm.getClass().getMethod("clear").invoke(zm);
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {
        }

        if (! FabricLoader.getInstance().isModLoaded("zonefocus"))
        {
            CovOut.log("[zfverify] Zone Focus не загружен — положите jar в run/mods");
            return false;
        }

        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null || mc.player == null)
        {
            CovOut.log("[zfverify] нет схемы " + path);
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        this.placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(4, 0, 4), "zfverify", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        LayerVerifier.getInstance().stop();
        this.nextAt = Util.getMillis() + 4000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        this.nextAt = now + 2500L;

        try
        {
            switch (this.stage++)
            {
                case 0 ->
                {
                    boolean owns = (boolean) Class.forName("com.aell.zonefocus.compat.MaterialHudCompat")
                            .getMethod("ownsVerification").invoke(null);
                    this.check("Zone Focus отдаёт проверку Material HUD (API 5)", owns, "");

                    Method build = Class.forName("com.aell.zonefocus.config.ZoneFocusSettings").getDeclaredMethod("buildTabs");
                    build.setAccessible(true);
                    List<String> keys = new ArrayList<>();

                    for (Object tab : (List<?>) build.invoke(null))
                    {
                        keys.add((String) tab.getClass().getField("key").get(tab));
                    }

                    this.check("в настройках Zone Focus нет вкладки «Проверка»", ! keys.contains("zonefocus.gui.tab_verification"),
                            keys.toString());
                    this.check("остальные вкладки на месте", keys.contains("zonefocus.gui.tab_hotkeys") && keys.size() >= 3,
                            keys.toString());
                    this.zfKey(mc);
                }
                case 1 ->
                {
                    this.check("Ctrl+P Zone Focus включил проверку Material HUD", LayerVerifier.getInstance().isHighlighting(), "");
                    this.check("своя проверка Zone Focus не включилась", ! this.zfVerifying(), "");
                    this.zfKey(mc);
                }
                case 2 ->
                {
                    this.check("второй Ctrl+P выключил проверку Material HUD", ! LayerVerifier.getInstance().isHighlighting(), "");
                }
                default ->
                {
                    CovOut.log("[zfverify] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("рефлексия", false, e.toString());
            CovOut.log("[zfverify] итог: FAIL");
            return true;
        }

        return false;
    }

    /** Нажатие клавиши проверки Zone Focus — тем же обработчиком, что у его клавиш. */
    private void zfKey(Minecraft mc) throws ReflectiveOperationException
    {
        Object hotkey = Class.forName("com.aell.zonefocus.config.ZoneFocusConfigs").getField("VERIFY_ZONE").get(null);
        IKeybind keybind = (IKeybind) hotkey.getClass().getMethod("getKeybind").invoke(hotkey);
        Object client = Class.forName("com.aell.zonefocus.ZoneFocusClient").getConstructor().newInstance();
        client.getClass().getMethod("onKeyAction", KeyAction.class, IKeybind.class).invoke(client, KeyAction.PRESS, keybind);
    }

    private boolean zfVerifying() throws ReflectiveOperationException
    {
        Object verifier = Class.forName("com.aell.zonefocus.verify.ZoneVerifier").getMethod("getInstance").invoke(null);
        return (boolean) verifier.getClass().getMethod("isHighlighting").invoke(verifier);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[zfverify] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
        LayerVerifier.getInstance().stop();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
