package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.SchematicsState;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Список ников, который правят поле {@link RosterField} и выбор игроков онлайн {@link PlayerPicker}: «Кто
 * собирает» окна выгрузки ({@link #COLLECTORS}) или состав Party.
 */
@Environment(EnvType.CLIENT)
public interface NickList
{
    /** Ники по порядку. */
    List<String> list();

    void set(List<String> nicks);

    /** Больше стольких ников в список не идёт. */
    int max();

    /** «Кто собирает» окна выгрузки — ростер из {@link SchematicsState}. */
    NickList COLLECTORS = new NickList()
    {
        @Override
        public List<String> list()
        {
            return SchematicsState.get().roster();
        }

        @Override
        public void set(List<String> nicks)
        {
            SchematicsState.get().setRoster(nicks);
        }

        @Override
        public int max()
        {
            return SchematicsState.ROSTER_MAX;
        }
    };
}
