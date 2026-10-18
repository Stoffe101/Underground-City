package io.github.stoffe101.undergroundcity;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Opt-in ({@code -Duc.bootSmoke=true}) check run by {@code scripts/run_prod_boot_check.py} on a production-style
 * dedicated server: real obfuscated mod jars in a plain {@code mods} folder, not the ForgeGradle development
 * environment. It prints one {@code [UC-BOOT]} line per fact and stops the server. It changes nothing and reads
 * only registries and Core's own datapack catalogs.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class BootSmokeHarness {
    private static final boolean ENABLED = Boolean.getBoolean("uc.bootSmoke");
    private static final List<String> MODS = List.of("underground_city", "qshop", "lostcities", "tacz", "taczblueprints", "modernlife", "mts", "mtsofficialpack", "oamp",
        "architectury", "vinery", "farm_and_charm", "kaleidoscope_tavern");

    private BootSmokeHarness() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!ENABLED) return;
        var server = event.getServer();
        for (String id : MODS) System.out.println("[UC-BOOT] mod " + id + "=" + ModList.get().isLoaded(id));
        System.out.println("[UC-BOOT] core work_orders=" + WorkOrderCatalog.INSTANCE.all().size() + " skills_farmer=" + SkillCatalog.INSTANCE.tree("farmer").size()
            + " districts=" + DistrictCatalog.INSTANCE.ids());
        for (String ns : List.of("tacz", "taczblueprints", "modernlife", "mts", "mtsofficialpack", "oamp", "vinery", "farm_and_charm", "kaleidoscope_tavern")) {
            if (ModList.get().isLoaded(ns)) System.out.println("[UC-BOOT] registry " + ns + " items=" + OptionalModProbes.items(ns) + " entities=" + OptionalModProbes.entities(ns));
        }
        // every item a loaded order, starter kit or contraband tag names must really exist in the installed mods (the Vintner's Vinery and Kaleidoscope goods)
        List<String> missing = new java.util.ArrayList<>();
        for (WorkOrder order : WorkOrderCatalog.INSTANCE.all()) {
            if (!order.terms().requiresMod().isEmpty() && !ModList.get().isLoaded(order.terms().requiresMod())) continue;
            for (WorkOrder.Requirement requirement : order.requirements()) {
                if (requirement.gunIds().isEmpty() && !net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(requirement.item())) missing.add(order.id() + " needs " + requirement.item());
            }
        }
        System.out.println("[UC-BOOT] order_items=" + (missing.isEmpty() ? "ok" : "MISSING " + missing));
        System.out.println("[UC-BOOT] vintner skills=" + SkillCatalog.INSTANCE.tree("vintner").size() + " orders="
            + WorkOrderCatalog.INSTANCE.all().stream().filter(o -> o.career().equals("vintner")).count()
            + " spirit_items=" + net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues().stream()
                .filter(i -> new net.minecraft.world.item.ItemStack(i).is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, new net.minecraft.resources.ResourceLocation("underground_city", "spirits")))).count());
        if (ModList.get().isLoaded("mts")) {
            // Immersive Vehicles registers every content-pack vehicle and part as an item named mts:<pack>.<name>.
            for (String pack : List.of("mtsofficialpack", "oamp")) {
                System.out.println("[UC-BOOT] mts pack " + pack + " items=" + OptionalModProbes.itemsWithPrefix("mts", pack + "."));
            }
        }
        if (ModList.get().isLoaded("modernlife")) {
            List<String> problems = OptionalModProbes.boatCargoRoundTrip(server.overworld());
            System.out.println("[UC-BOOT] boat_cargo=" + (problems.isEmpty() ? "ok" : "FAILED " + problems));
        }
        System.out.println("[UC-BOOT] done");
        server.halt(false);
    }
}
