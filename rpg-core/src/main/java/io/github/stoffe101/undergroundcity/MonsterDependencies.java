package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which hostile-creature drops does a recipe chain need? (D-095.) The simulation world has no zombies, spiders, endermen or blazes (D-094), so anything that is made from
 * their drops has to have another way in. This reads the recipes that are actually loaded (the base game's, Forge's, every mod's, Core's and TaCZ's gun table, which keeps its
 * ingredients in a recipe type of its own), follows each item back through the cheapest recipe to the "roots" listed in
 * {@code data/underground_city/progression/monster_resources.json}, and reports which roots an item needs. Nothing here changes a recipe.
 *
 * <p>Items that are not the result of any recipe are taken to come from the world (ores, plants, livestock, loot), and so are items that are only ever made from each other (a gem and
 * its block); a root is a root whether or not a recipe also makes it, because the question is what the player must obtain from outside the chain.
 */
final class MonsterDependencies {
    private static final String CATALOGUE = "/data/underground_city/progression/monster_resources.json";
    private static final String TACZ_RECIPE = "com.tacz.guns.crafting.GunSmithTableRecipe";

    private MonsterDependencies() {}

    /** Craftable items that no chain of recipes could resolve from the world (they are taken to come from the world); for the diagnosis. */
    static Set<String> lastUnresolved = new TreeSet<>();

    static JsonObject catalogue() {
        try (Reader reader = new InputStreamReader(MonsterDependencies.class.getResourceAsStream(CATALOGUE), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("monster-resource catalogue missing from Core: " + CATALOGUE, e);
        }
    }

    /** Roots that have a route (obtainable without hunting) and roots that have none. */
    static Set<String> routed(JsonObject catalogue) {
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry : catalogue.getAsJsonObject("roots").entrySet()) {
            if (entry.getValue().getAsJsonObject().has("route") && !entry.getValue().getAsJsonObject().get("route").isJsonNull()) found.add(entry.getKey());
        }
        return found;
    }

    static Set<String> allRoots(JsonObject catalogue) { return new TreeSet<>(catalogue.getAsJsonObject("roots").keySet()); }

    /** The ingredients of a recipe as lists of acceptable items; empty when the recipe says nothing (a smithing recipe, a special recipe). */
    static List<List<Item>> ingredientsOf(Recipe<?> recipe) {
        List<Ingredient> ingredients = new ArrayList<>();
        try {
            if (recipe.getClass().getName().equals(TACZ_RECIPE)) {
                for (Object input : (List<?>) ModReflect.callOn(recipe, "getInputs")) ingredients.add((Ingredient) ModReflect.callOn(input, "getIngredient"));
            } else {
                ingredients.addAll(recipe.getIngredients());
                if (ingredients.stream().allMatch(Ingredient::isEmpty)) ingredients.addAll(ingredientFields(recipe));   // smithing and other recipes keep theirs in fields
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the ingredients of " + recipe.getId(), e);
        }
        List<List<Item>> result = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            List<Item> options = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) if (!stack.isEmpty() && !options.contains(stack.getItem())) options.add(stack.getItem());
            if (!options.isEmpty()) result.add(options);
        }
        return result;
    }

    /** The ingredient fields of a recipe whose {@code getIngredients()} is empty (a smithing recipe has a template, a base and an addition). */
    private static List<Ingredient> ingredientFields(Recipe<?> recipe) {
        List<Ingredient> found = new ArrayList<>();
        for (Class<?> type = recipe.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !Ingredient.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    found.add((Ingredient) field.get(recipe));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read " + field, e);
                }
            }
        }
        return found;
    }

    /** Strongly connected groups of more than one item (or an item that is an ingredient of itself) in the "made from" graph; roots are terminal. */
    private static List<Set<String>> cycles(Map<String, List<List<List<String>>>> recipesFor, Set<String> roots) {
        Map<String, List<String>> edges = new HashMap<>();
        for (Map.Entry<String, List<List<List<String>>>> entry : recipesFor.entrySet()) {
            if (roots.contains(entry.getKey())) continue;
            Set<String> targets = new TreeSet<>();
            for (List<List<String>> ingredients : entry.getValue()) for (List<String> options : ingredients) targets.addAll(options);
            edges.put(entry.getKey(), new ArrayList<>(targets));
        }
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        List<String> stack = new ArrayList<>();
        List<Set<String>> found = new ArrayList<>();
        int[] counter = {0};
        for (String start : edges.keySet()) {
            if (index.containsKey(start)) continue;
            List<Object[]> work = new ArrayList<>();   // {node, next edge index}
            work.add(new Object[] {start, 0});
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.add(start);
            onStack.add(start);
            while (!work.isEmpty()) {
                Object[] frame = work.get(work.size() - 1);
                String node = (String) frame[0];
                List<String> out = edges.getOrDefault(node, List.of());
                int next = (Integer) frame[1];
                if (next < out.size()) {
                    frame[1] = next + 1;
                    String target = out.get(next);
                    if (!edges.containsKey(target)) continue;   // a natural item or a root: no further edges
                    if (!index.containsKey(target)) {
                        index.put(target, counter[0]);
                        low.put(target, counter[0]++);
                        stack.add(target);
                        onStack.add(target);
                        work.add(new Object[] {target, 0});
                    } else if (onStack.contains(target)) {
                        low.put(node, Math.min(low.get(node), index.get(target)));
                    }
                } else {
                    work.remove(work.size() - 1);
                    if (!work.isEmpty()) {
                        String parent = (String) work.get(work.size() - 1)[0];
                        low.put(parent, Math.min(low.get(parent), low.get(node)));
                    }
                    if (low.get(node).equals(index.get(node))) {
                        Set<String> component = new TreeSet<>();
                        String member;
                        do {
                            member = stack.remove(stack.size() - 1);
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(node));
                        if (component.size() > 1 || edges.get(node).contains(node)) found.add(component);
                    }
                }
            }
        }
        return found;
    }

    static String id(Item item) { return String.valueOf(ForgeRegistries.ITEMS.getKey(item)); }

    /** The result of a recipe as a registry name, or null when it makes nothing the audit can name. */
    static String resultOf(Recipe<?> recipe, ServerLevel level) {
        ItemStack out;
        try {
            out = recipe.getResultItem(level.registryAccess());
        } catch (Exception e) {
            return null;
        }
        return out == null || out.isEmpty() || out.getItem() == Items.AIR ? null : id(out.getItem());
    }

    /**
     * For every item, the smallest set of roots that some recipe chain needs. An item that is not craftable has no roots (it comes from the world) unless it is itself a root.
     * Returned as registry name to sorted root names.
     */
    static Map<String, Set<String>> rootsNeeded(ServerLevel level, Set<String> roots) {
        Map<String, List<List<List<String>>>> recipesFor = new HashMap<>();   // result -> recipes -> ingredients -> acceptable items
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            String result = resultOf(recipe, level);
            if (result == null) continue;
            List<List<String>> ingredients = new ArrayList<>();
            for (List<Item> options : ingredientsOf(recipe)) {
                List<String> names = new ArrayList<>();
                for (Item item : options) names.add(id(item));
                ingredients.add(names);
            }
            recipesFor.computeIfAbsent(result, k -> new ArrayList<>()).add(ingredients);
        }
        Map<String, Set<String>> best = new HashMap<>();
        for (Item item : ForgeRegistries.ITEMS) {
            String name = id(item);
            if (roots.contains(name)) best.put(name, new TreeSet<>(Set.of(name)));
            else if (!recipesFor.containsKey(name)) best.put(name, new TreeSet<>());
        }
        // items that are made from each other (a gem and its block, cobblestone and its slabs) come from the world: break every cycle by taking its members as natural
        lastUnresolved = new TreeSet<>();
        for (Set<String> cycle : cycles(recipesFor, roots)) {
            for (String member : cycle) {
                best.put(member, new TreeSet<>());
                lastUnresolved.add(member);
            }
        }
        boolean changed = true;
        for (int pass = 0; changed && pass < 200; pass++) {
            changed = false;
            for (Map.Entry<String, List<List<List<String>>>> entry : recipesFor.entrySet()) {
                String name = entry.getKey();
                if (roots.contains(name)) continue;
                for (List<List<String>> ingredients : entry.getValue()) {
                    Set<String> needed = new TreeSet<>();
                    boolean known = true;
                    for (List<String> options : ingredients) {
                        Set<String> cheapest = null;
                        for (String option : options) {
                            Set<String> sub = best.get(option);
                            if (sub == null) continue;
                            if (cheapest == null || sub.size() < cheapest.size() || (sub.size() == cheapest.size() && sub.toString().compareTo(cheapest.toString()) < 0)) cheapest = sub;
                        }
                        if (cheapest == null) { known = false; break; }
                        needed.addAll(cheapest);
                    }
                    if (!known) continue;
                    Set<String> current = best.get(name);
                    if (current == null || better(needed, current)) {
                        best.put(name, needed);
                        changed = true;
                    }
                }
            }
        }
        for (Map.Entry<String, List<List<List<String>>>> entry : recipesFor.entrySet()) best.putIfAbsent(entry.getKey(), new TreeSet<>());   // nothing should be left; if it is, it is natural
        return best;
    }

    private static boolean better(Set<String> candidate, Set<String> current) {
        return candidate.size() < current.size() || (candidate.size() == current.size() && !candidate.equals(current) && candidate.toString().compareTo(current.toString()) < 0);
    }

    /** For a diagnosis: every recipe of one item, each ingredient with its options and what the audit worked out for them. */
    static String explain(ServerLevel level, Map<String, Set<String>> needs, String name) {
        StringBuilder text = new StringBuilder(name + " -> " + needs.get(name));
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (!name.equals(resultOf(recipe, level))) continue;
            text.append("\n  recipe ").append(recipe.getId()).append(" (").append(recipe.getClass().getSimpleName()).append(")");
            for (List<Item> options : ingredientsOf(recipe)) {
                List<String> shown = new ArrayList<>();
                for (Item item : options.subList(0, Math.min(options.size(), 4))) shown.add(id(item) + "=" + needs.get(id(item)) + (lastUnresolved.contains(id(item)) ? "(unresolved)" : ""));
                text.append("\n    one of ").append(options.size()).append(": ").append(shown);
            }
        }
        return text.toString();
    }

    /** Roots a result needs that have no route yet, for the namespaces the catalogue treats as approved gameplay. */
    static Map<String, Set<String>> unrouted(Map<String, Set<String>> needs, JsonObject catalogue) {
        Set<String> routed = routed(catalogue);
        Set<String> namespaces = new HashSet<>();
        for (JsonElement e : catalogue.getAsJsonObject("approved_scopes").getAsJsonArray("namespaces")) namespaces.add(e.getAsString());
        Map<String, Set<String>> found = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(needs.keySet());
        Collections.sort(names);
        for (String name : names) {
            if (!namespaces.contains(ResourceLocation.parse(name).getNamespace()) || excluded(catalogue, name)) continue;
            Set<String> missing = new TreeSet<>(needs.get(name));
            missing.removeAll(routed);
            if (!missing.isEmpty()) found.put(name, missing);
        }
        return found;
    }

    /** Is this result listed in the catalogue's exclusions (an exact item, or a pattern for a family such as the prismarine furniture)? */
    static boolean excluded(JsonObject catalogue, String name) {
        for (JsonElement e : catalogue.getAsJsonArray("exclusions")) {
            JsonObject exclusion = e.getAsJsonObject();
            if (exclusion.has("item") && exclusion.get("item").getAsString().equals(name)) return true;
            if (exclusion.has("pattern") && name.matches(exclusion.get("pattern").getAsString())) return true;
        }
        return false;
    }
}
