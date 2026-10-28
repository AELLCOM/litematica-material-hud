package com.aell.layerlist.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Книга рецептов экранов инвентаря, верстака и печей: открыта ли она — чтобы панель,
 * вставшая слева от окна, не легла на книгу ({@code hud.HudDock}).
 */
@Mixin(AbstractRecipeBookScreen.class)
public interface IMixinAbstractRecipeBookScreen
{
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> layerlist_getRecipeBook();
}
