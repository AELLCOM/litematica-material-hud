package com.aell.layerlist.dev;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** 26.2: проверка горячей клавиши библиотеки идёт событиями SDL — в 26.2 их нет, шагов нет. */
@Environment(EnvType.CLIENT)
final class KHotkey
{
    KHotkey(DumpSchematics k)
    {
    }

    List<DumpSchematics.Step> steps(String p)
    {
        return List.of();
    }

    void cleanup()
    {
    }
}
