package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Freight from the Northgate depot (D-156): the depot's dispatcher hands a registered player a sealed manifest for the depot office of another city, and any terminal or counter in that city pays
 * it on delivery, once, through the courier letter's own path ({@link RoadEncounters#deliver}: one ledger commit, one once-only record, a copy pays nothing). One manifest at a time; the next
 * after {@link #EVERY_SECONDS} of active play. The fee grows with the way to go, as a letter's does, and the destinations take turns, so the road to each city has a reason to be walked.
 */
final class Freight {
    private Freight() {}

    /** The building key of the dispatcher's counter at the depot ({@code shop_counters/roadside/northgate_dispatch.json}). */
    static final String DEPOT_SHOP = RoadsidePlaces.SHOP_PREFIX + "northgate_dispatch";
    static final String KIND = "freight", FROM = "Northgate Freight Depot";
    /** Active seconds between two manifests for one player, and the career XP a delivery pays. */
    static final int EVERY_SECONDS = 600, XP = 40;

    /** A manifest handed over, or why not. */
    record Issue(ItemStack manifest, String city, int fee, String refusal) {
        static Issue refused(String why) { return new Issue(ItemStack.EMPTY, "", 0, why); }
    }

    /** What the dispatcher answers to "Any freight going out?": a manifest put in the player's hands, or why there is none. */
    static String ask(ServerPlayer player, Villager dispatcher) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) return "Register first. I only send freight with citizens.";
        RoadEncounters.State state = RoadEncounters.state(player.server);
        Issue issue = issue(player, dispatcher.blockPosition(), LockService.clock(player.server), state);
        if (!issue.refusal().isEmpty()) return issue.refusal();
        if (!player.getInventory().add(issue.manifest())) return "Your hands are full. Make room and ask again.";
        record(state, player.getUUID(), LockService.clock(player.server));
        String city = TerminalScreenText.capitalize(issue.city());
        Notice.send(player, Notice.Kind.ORDER, "Freight manifest", "For " + city + ": " + issue.fee() + " credits at any terminal or counter there.");
        return "One for the " + city + " depot office. Hand it in at any terminal or counter in " + city + " and they pay you " + issue.fee() + " credits. Mind the bridges.";
    }

    /** The pure part, for the tests: whether {@code player} may have a manifest now, and the manifest. Does not change any state. */
    static Issue issue(ServerPlayer player, BlockPos depot, long now, RoadEncounters.State state) {
        String carried = carrying(player);
        if (carried != null) return Issue.refused("You still have the manifest for " + TerminalScreenText.capitalize(carried) + ". Deliver that one first.");
        Long next = state.freightNext.get(player.getUUID());
        if (next != null && now < next) return Issue.refused("Nothing going out for a while. Come back in about " + Math.max(1, (next - now + 1199) / 1200) + " minutes.");
        List<CityRegions.Region> cities = destinations(depot);
        if (cities.isEmpty()) return Issue.refused("No freight going anywhere today.");
        CityRegions.Region to = cities.get(Math.floorMod(state.freightCount.getOrDefault(player.getUUID(), 0), cities.size()));
        int fee = RoadEncounters.letterFee(distanceToEdge(to, depot));
        ItemStack stack = new ItemStack(UndergroundCity.SEALED_ENVELOPE.get());
        CompoundTag tag = stack.getOrCreateTagElement("uc_letter");
        tag.putUUID("id", UUID.randomUUID());
        tag.putString("to", to.id());
        tag.putString("from", FROM);
        tag.putString("kind", KIND);
        tag.putInt("fee", fee);
        tag.putInt("xp", XP);
        stack.setHoverName(Component.literal("Freight manifest for " + TerminalScreenText.capitalize(to.id())));
        return new Issue(stack, to.id(), fee, "");
    }

    /** Notes that {@code player} was handed a manifest now: the next is due {@link #EVERY_SECONDS} later, and goes to the next city in turn. */
    static void record(RoadEncounters.State state, UUID player, long now) {
        state.freightNext.put(player, now + EVERY_SECONDS * 20L);
        state.freightCount.merge(player, 1, Integer::sum);
        state.setDirty();
    }

    /** The city of a depot manifest the player carries, or null. */
    static String carrying(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            CompoundTag tag = player.getInventory().getItem(i).is(UndergroundCity.SEALED_ENVELOPE.get()) ? player.getInventory().getItem(i).getTagElement("uc_letter") : null;
            if (tag != null && KIND.equals(tag.getString("kind"))) return tag.getString("to");
        }
        return null;
    }

    /** Where the depot sends freight: the overworld's cities but the one nearest the depot (its own end of the road), the farthest first. */
    static List<CityRegions.Region> destinations(BlockPos depot) {
        List<CityRegions.Region> all = new ArrayList<>();
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) if (region.dimension().equals("minecraft:overworld")) all.add(region);
        all.sort(Comparator.comparingDouble(region -> distanceToEdge(region, depot)));
        if (all.size() > 1) all.remove(0);
        all.sort(Comparator.comparingDouble((CityRegions.Region region) -> distanceToEdge(region, depot)).reversed());
        return all;
    }

    private static double distanceToEdge(CityRegions.Region region, BlockPos from) {
        return Math.max(0, Math.hypot(region.centerChunkX() * 16 + 8 - from.getX(), region.centerChunkZ() * 16 + 8 - from.getZ()) - region.cityRadius());
    }
}
