package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Client only, for the scenarios (development runs, Mojang's names): where the vanilla recipe book shows a recipe whose result is an item, so a scenario can click it with the real pointer
 * (D-175: Core's station recipes are in every citizen's recipe book). Reads the book's current page; {@link #nextPage} turns it with the book's own arrow.
 */
final class RecipeBookProbe {
    private RecipeBookProbe() {}

    /** The GUI position of the button on the book's current page whose recipes make {@code result}, or null. */
    static int[] buttonFor(CraftingScreen screen, Item result) {
        try {
            Object page = field(RecipeBookComponent.class, screen.getRecipeBookComponent(), "recipeBookPage");
            List<?> buttons = (List<?>) field(page.getClass(), page, "buttons");
            for (Object button : buttons) {
                AbstractWidget w = (AbstractWidget) button;
                if (!w.visible) continue;
                Object collection = button.getClass().getMethod("getCollection").invoke(button);
                if (collection == null) continue;
                List<?> recipes = (List<?>) collection.getClass().getMethod("getRecipes").invoke(collection);
                for (Object r : recipes)
                    if (r instanceof Recipe<?> recipe && recipe.getResultItem(net.minecraft.client.Minecraft.getInstance().level.registryAccess()).is(result))
                        return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Playtest.log("RECIPEBOOK could not read the book: " + e);
        }
        return null;
    }

    /** Clicks the book's forward arrow when it shows; false when there is no further page. */
    static boolean nextPage(CraftingScreen screen) {
        try {
            Object page = field(RecipeBookComponent.class, screen.getRecipeBookComponent(), "recipeBookPage");
            AbstractWidget forward = (AbstractWidget) field(page.getClass(), page, "forwardButton");
            if (forward == null || !forward.visible) return false;
            Playtest.clickGui(forward.getX() + forward.getWidth() / 2, forward.getY() + forward.getHeight() / 2);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Object field(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
}
