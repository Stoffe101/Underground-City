package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.TreeMap;

/**
 * Reads, from the running game, what the vehicle and boat mods actually offer (D-101): which item classes they register, which of them are whole vehicles, and
 * what the crafting recipes of those items ask for. Nothing is asserted about the mods except that the audit runs; the printed lines are the evidence the
 * vehicle price tiers in {@code content/gameplay/vehicle-tiers.json} are built from. Passes trivially when none of the mods is loaded.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VehicleAuditGameTests {
    private static final String[] NAMESPACES = {"mts", "mtsofficialpack", "oamp", "modernlife"};

    private VehicleAuditGameTests() {}

    /** The simple class name of the object an Immersive Vehicles builder item wraps (ItemVehicle, ItemPart, ...), or the item's own class name. */
    private static String wrappedKind(Item item) {
        try {
            for (Class<?> type = item.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().getName().contains("AItemBase")) {
                        field.setAccessible(true);
                        Object wrapped = field.get(item);
                        if (wrapped != null) return wrapped.getClass().getSimpleName();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* fall through */ }
        return item.getClass().getSimpleName();
    }

    @GameTest(template = "empty")
    public static void theVehicleModsAreAudited(GameTestHelper helper) {
        for (String namespace : NAMESPACES) {
            if (!ModList.get().isLoaded(namespace)) continue;
            Map<String, java.util.List<String>> byClass = new TreeMap<>();
            Map<String, java.util.List<String>> byKind = new TreeMap<>();
            for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
                ResourceLocation id = entry.getKey().location();
                if (!id.getNamespace().equals(namespace)) continue;
                byClass.computeIfAbsent(entry.getValue().getClass().getName(), k -> new java.util.ArrayList<>()).add(id.getPath());
                // Immersive Vehicles wraps every item of its packs in one builder class; the wrapped object says whether it is a whole vehicle, a part, a decor, a pole or a plain item
                byKind.computeIfAbsent(wrappedKind(entry.getValue()), k -> new java.util.ArrayList<>()).add(id.getPath());
            }
            if (namespace.equals("mts")) {
                try {
                    java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("uc-audit");
                    java.nio.file.Files.createDirectories(dir);
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    byKind.forEach((kind, ids) -> ids.stream().sorted().forEach(id -> lines.add(kind + "\t" + id)));
                    java.nio.file.Files.write(dir.resolve("mts-items-by-kind.tsv"), lines);
                } catch (java.io.IOException ignored) { /* an audit file only */ }
                byKind.forEach((kind, ids) -> System.out.println("[UC-VEHICLE-AUDIT]   kind " + kind + " x" + ids.size() + ": " + ids.stream().sorted().limit(60).toList()));
            }
            System.out.println("[UC-VEHICLE-AUDIT] " + namespace + ": " + byClass.values().stream().mapToInt(java.util.List::size).sum() + " items in " + byClass.size() + " classes");
            byClass.forEach((className, ids) -> System.out.println("[UC-VEHICLE-AUDIT]   " + className + " x" + ids.size() + ": " + ids.stream().sorted().limit(40).toList()));
            int recipes = 0;
            for (Recipe<?> recipe : helper.getLevel().getRecipeManager().getRecipes()) {
                ResourceLocation id = recipe.getId();
                if (!id.getNamespace().equals(namespace)) continue;
                if (recipes++ < 40) {
                    System.out.println("[UC-VEHICLE-AUDIT]   recipe " + id + " (" + recipe.getType() + ") -> " + recipe.getResultItem(helper.getLevel().registryAccess()).getItem().builtInRegistryHolder().key().location()
                        + " from " + recipe.getIngredients().stream().map(i -> i.isEmpty() ? "-" : i.getItems().length == 0 ? "?" : ForgeRegistries.ITEMS.getKey(i.getItems()[0].getItem()).toString()).toList());
                }
            }
            System.out.println("[UC-VEHICLE-AUDIT] " + namespace + ": " + recipes + " recipes in the recipe manager");
        }
        helper.succeed();
    }
}
