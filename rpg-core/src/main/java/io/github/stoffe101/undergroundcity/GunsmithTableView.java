package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client only: what Core changes in TaCZ's Gun Smith Table screen (D-168). The screen lists every table recipe of a tab by its result's name, so a gun with
 * TaCZ's own iron recipe and Core's two kit recipes showed three entries called "Glock 17" (gunsmith run 5). Core's kit recipes name their results
 * ("Registered Glock 17", "Unmarked Glock 17", the name dropped again from the gun the table hands over), and this takes TaCZ's iron recipe for a kit gun off
 * the list, which the server refuses outside creative mode. Everything is reached by reflection on the pinned TaCZ version; a changed screen leaves the list
 * as TaCZ made it (one log line).
 */
public final class GunsmithTableView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean broken;

    private GunsmithTableView() {}

    /** Called at the end of the screen's {@code classifyRecipes}. */
    @SuppressWarnings("unchecked")
    public static void pruneIronRecipes(Object screen) {
        Minecraft mc = Minecraft.getInstance();
        if (broken || mc.level == null || mc.player == null || mc.player.isCreative()) return;
        try {
            RecipeManager manager = mc.level.getRecipeManager();
            Set<String> kitGuns = new HashSet<>();
            for (Recipe<?> recipe : manager.getRecipes())
                if (recipe.getId().getNamespace().equals(UndergroundCity.MOD_ID) && recipe.getId().getPath().startsWith("tacz/")) {
                    String gun = gunId(recipe);
                    if (gun != null) kitGuns.add(gun);
                }
            if (kitGuns.isEmpty()) return;
            Map<ResourceLocation, List<ResourceLocation>> recipes = (Map<ResourceLocation, List<ResourceLocation>>) field(screen, "recipes").get(screen);
            int removed = 0;
            for (List<ResourceLocation> ids : recipes.values())
                removed += ids.removeIf(id -> id.getNamespace().equals("tacz") && manager.byKey(id).map(r -> kitGuns.contains(gunId(r))).orElse(false)) ? 1 : 0;
            if (removed == 0) return;
            // the selected recipe may have been one of those taken off: select the first left in its tab, as the screen does on opening
            Field selected = field(screen, "selectedRecipe");
            Object current = selected.get(screen);
            if (current instanceof Recipe<?> r && r.getId().getNamespace().equals("tacz") && kitGuns.contains(gunId(r))) {
                List<ResourceLocation> list = (List<ResourceLocation>) field(screen, "selectedRecipeList").get(screen);
                Optional<? extends Recipe<?>> first = list == null || list.isEmpty() ? Optional.empty() : manager.byKey(list.get(0));
                selected.set(screen, first.orElse(null));
                screen.getClass().getMethod("updateIngredientCount").invoke(screen);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LOGGER.warn("[UC] could not tidy TaCZ's Gun Smith Table list (a version it was not written for?): it shows TaCZ's own recipes too", error);
        }
    }

    private static String gunId(Recipe<?> recipe) {
        try {
            Object output = recipe.getClass().getMethod("getOutput").invoke(recipe);
            if (!(output instanceof ItemStack stack) || stack.getTag() == null) return null;
            String id = stack.getTag().getString("GunId");
            return id.isEmpty() ? null : id;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
