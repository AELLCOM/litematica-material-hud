package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.schematics.library.LoadedSchematic;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * Откуда берутся строки таблицы материалов: «Схема: &lt;имя&gt;» или «Список HUD». Подпись идёт и в
 * переключатель источника, и в выгрузки ({@code ExportRequest.source}).
 */
@Environment(EnvType.CLIENT)
public sealed interface MaterialSource permits MaterialSource.Schematic, MaterialSource.Hud
{
    /** Подпись источника на языке игры. */
    String label();

    /** Строки заново, с текущим наличием. Будущее завершается на клиентском потоке. */
    CompletableFuture<List<MaterialRow>> rows();

    /** Вся схема-файл. */
    record Schematic(LoadedSchematic schematic) implements MaterialSource
    {
        public Schematic
        {
            Objects.requireNonNull(schematic, "schematic");
        }

        @Override
        public String label()
        {
            return I18n.get("layerlist.schematics.materials.source.schematic", this.schematic.entry().name());
        }

        @Override
        public CompletableFuture<List<MaterialRow>> rows()
        {
            return SchematicMaterials.ofSchematic(this.schematic);
        }
    }

    /** Опубликованный список HUD: недостающее для размещений в мире. */
    record Hud() implements MaterialSource
    {
        @Override
        public String label()
        {
            return I18n.get("layerlist.schematics.materials.source.hud");
        }

        @Override
        public CompletableFuture<List<MaterialRow>> rows()
        {
            return CompletableFuture.supplyAsync(SchematicMaterials::ofHud, Minecraft.getInstance());
        }
    }
}
