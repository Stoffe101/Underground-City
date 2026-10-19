package io.github.stoffe101.undergroundcity;

import net.minecraftforge.fml.ModList;

/**
 * The opt-in survival set in one place (D-085, backpack replaced by Sophisticated Backpacks in D-092): Sophisticated Backpacks with its required
 * Sophisticated Core, Thirst Was Taken 2, Farmer's Delight and Comforts. Tests and the journey ask here whether the set is loaded, so a change of
 * provider is one edit. Traveler's Backpack is the retired provider: it must never be loaded together with the new one.
 */
final class SurvivalSet {
    private SurvivalSet() {}

    static final String BACKPACK = "sophisticatedbackpacks:backpack";
    static final String BACKPACK_MOD = "sophisticatedbackpacks", BACKPACK_CORE = "sophisticatedcore", LEGACY_BACKPACK_MOD = "travelersbackpack";

    /** The whole set, with the backpack provider's required library. */
    static boolean loaded() {
        ModList mods = ModList.get();
        return mods.isLoaded(BACKPACK_MOD) && mods.isLoaded(BACKPACK_CORE) && mods.isLoaded("thirstwastaken2") && mods.isLoaded("farmersdelight") && mods.isLoaded("comforts");
    }

    /** Only the backpack provider and its library (the backpack tests need nothing else). */
    static boolean backpacksLoaded() { return ModList.get().isLoaded(BACKPACK_MOD) && ModList.get().isLoaded(BACKPACK_CORE); }
}
