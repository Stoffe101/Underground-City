package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * The bonded store job (D-173), the game's first raid on a secured facility. A broker in Blackwater wants a shipment that Ironhaven's customs seized back; it sits in the back room of
 * the city's bonded store, a real warehouse of the furnished city, behind that building's own locks, cameras and alarm, with the police beat outside. The job is an ordinary underground
 * work order carrying {@code raid}: taking it names the store (the warehouse nearest Ironhaven's centre) and gives the player somewhere to track. Preparation is the player's own:
 * casing the store, a Technician's back channel to loop its cameras and quiet its alarm line, picks or a crowbar. Only the person who took the job finds the shipment, once, in a search
 * inside that building; the store's watchman reports the loss, which heats Ironhaven for that person. The shipment is marked stolen, so a checkpoint on the way to Blackwater may seize
 * it, and the police can arrest. Delivering it settles through the work-order journal like every order. Nothing here touches a home or anybody's progression, and no violence is needed.
 */
public final class Raids {
    static final String BONDED_STORE = "bonded_store";
    static final int MIN_THIEF_LEVEL = 3;
    static final String SHIPMENT = "seized_shipment";
    /** How much heat the store's watchman's report puts on the thief in Ironhaven. */
    static final int REPORT_HEAT = 2;

    record Target(long key, BlockPos door, String city, int tier) {}

    private Raids() {}

    /** Why this player may not take a raid order now, or null. */
    static String refusal(ServerPlayer player, WorkOrder.Terms terms) {
        if (terms.raid().isEmpty()) return null;
        if (!terms.raid().equals(BONDED_STORE)) return "This job is no longer offered.";
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null || citizen.thiefLevel() < MIN_THIEF_LEVEL) return "Kessler only hires a thief who has done this before (Thief level " + MIN_THIEF_LEVEL + ").";
        return null;
    }

    /** The bonded store of this world: the warehouse nearest Ironhaven's centre that is not let to anybody and whose plan can be read now, or empty (a world without the furnished cities has none). */
    static Optional<Target> store(ServerLevel level) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals("ironhaven")).findFirst().orElse(null);
        if (region == null) return Optional.empty();
        Target best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
            int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz, distance = dx * dx + dz * dz;
            if (distance >= bestDistance || !CityBuildings.INSTANCE.askable(level, cx, cz)) continue;
            Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
            if (b.isEmpty() || !b.get().type().contains("warehouse") || !b.get().city().equals("ironhaven")) continue;
            // a warehouse let as trade premises (D-169) is a tenant's, not customs': the guard leaves it to them, so nobody could case or break into it (raid run 1 drew one)
            if (CityHomes.anyLet(level.getSeed(), b.get())) continue;
            BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.get().type());
            BuildingSpots.Spot door = null;
            if (plan != null) for (BuildingSpots.Spot spot : plan.spots()) if (spot.kind().equals("door")) { door = spot; break; }
            BlockPos at = door == null ? new BlockPos(cx * 16 + 8, b.get().streetY(), cz * 16 + 8) : new BlockPos(cx * 16 + door.x(), b.get().streetY() + door.y(), cz * 16 + door.z());
            best = new Target(b.get().key(), at, "ironhaven", Thievery.tier(b.get()));
            bestDistance = distance;
        }
        return Optional.ofNullable(best);
    }

    /** The job was taken: name the store for this player (kept in the journal with the order's nonce). */
    static void opened(ServerPlayer player, WorkOrderLedger orders, WorkOrder order, UUID nonce) {
        if (order.terms().raid().isEmpty()) return;
        Optional<Target> store = store(player.serverLevel());
        CompoundTag raid = new CompoundTag();
        raid.putUUID("nonce", nonce);
        store.ifPresent(t -> { raid.putLong("key", t.key()); raid.putLong("door", t.door().asLong()); });
        orders.putRaid(player.getUUID(), raid);
        if (store.isPresent()) {
            BlockPos d = store.get().door();
            player.sendSystemMessage(Component.literal("The bonded store is the " + Thievery.tierWord(store.get().tier()).toLowerCase(java.util.Locale.ROOT) + " warehouse at " + d.getX() + ", " + d.getZ()
                + " in Ironhaven. The shipment is in its back room. Its yard trades at night until three: go after that, and case it first; a Technician can loop its cameras. Then carry it to Kessler in Blackwater."));
            Notice.send(player, Notice.Kind.ORDER, "The bonded store", "Track it on the order. Case it, get in, find the shipment, carry it to Blackwater.");
        } else {
            player.sendSystemMessage(Component.literal("Kessler's man could not point out the bonded store from here: go to Ironhaven and ask the terminal again (it needs the furnished city)."));
        }
    }

    /** The player's open raid, when their accepted order is one: the store's key and door, and whether the shipment is already taken. */
    static Optional<CompoundTag> open(ServerPlayer player, WorkOrderLedger orders) {
        CompoundTag record = orders.player(player.getUUID());
        if (!record.hasUUID("order")) return Optional.empty();
        CompoundTag raid = orders.raid(player.getUUID());
        if (!raid.hasUUID("nonce") || !raid.getUUID("nonce").equals(record.getUUID("order"))) return Optional.empty();
        if (!raid.contains("key")) {   // the store could not be named when the job was taken: name it now
            Optional<Target> store = store(player.serverLevel());
            if (store.isEmpty()) return Optional.of(raid);
            raid.putLong("key", store.get().key()); raid.putLong("door", store.get().door().asLong());
            orders.putRaid(player.getUUID(), raid);
        }
        return Optional.of(raid);
    }

    /** A search finished inside a city building: the job holder's first search inside the bonded store turns up the shipment. Called by the burglary search on the server thread. */
    static void searched(ServerPlayer player, ServerLevel level, CityBuildings.Building building) { searchedForTest(player, level, building, WorkOrderLedger.get(player.server)); }

    /** The search with a given journal (the GameTests use a scratch one). */
    static void searchedForTest(ServerPlayer player, ServerLevel level, CityBuildings.Building building, WorkOrderLedger orders) {
        if (orders.faulted()) return;
        Optional<CompoundTag> raid = open(player, orders);
        if (raid.isEmpty() || raid.get().getBoolean("taken") || !raid.get().contains("key") || raid.get().getLong("key") != building.key()) return;
        ItemStack shipment = new ItemStack(UndergroundCity.item(SHIPMENT));
        StolenGoods.mark(shipment, building.city(), player.getUUID());
        if (!player.getInventory().add(shipment.copy())) { player.sendSystemMessage(Component.literal("The shipment is here, but your hands are full. Make room and search again.")); return; }
        CompoundTag next = raid.get().copy();
        next.putBoolean("taken", true);
        orders.putRaid(player.getUUID(), next);
        orders.addHeat(player.getUUID(), "ironhaven", REPORT_HEAT);
        player.sendSystemMessage(Component.literal("The seized shipment: customs tape, a consignment number, heavier than it looks. The store's watchman will report it missing.").withStyle(ChatFormatting.GOLD));
        Notice.send(player, Notice.Kind.LOOT, "The seized shipment", "Carry it to Kessler in Blackwater. It is stolen: a checkpoint can take it, and Ironhaven is hotter for you now.");
        Navigation.retrack(player, "order");
    }
}
