package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public cultivation (D-160). Carrying the crop was always what the city laws looked at; now growing it where anybody can see is too. A cannabis plant in the open (the sky above it), inside the
 * limits of a city where the crop is prohibited, and not in anybody's registered home or garden, is <b>in public view</b>. An officer on the beat who can see it pulls it up: the plant is gone with
 * nothing dropped, and its grower is told and gains heat in that city.
 *
 * <ul>
 * <li>Who planted a plant is recorded when it is planted. A plant is seized only while its grower is online (nothing is taken from somebody who is away), and a plant nobody recorded (one planted
 * before this rule) is never seized.</li>
 * <li>Under a roof, in a greenhouse with a glass roof (glass hides nothing, but a plant under it is not in the open), at home, outside the cities or in Blackwater (a licensed trade) the rule does
 * not apply. Planting in public view says so at once.</li>
 * <li>The check runs on the plant's own random ticks, so only plants in loaded chunks are looked at, and only when an officer is within {@link #SIGHT} blocks.</li>
 * </ul>
 */
final class CannabisLaw {
    private CannabisLaw() {}

    static final String CATEGORY = "restricted_cultivar";
    /** How far an officer on the beat sees a plant, and the heat its grower gains in that city when one is seized. */
    static final double SIGHT = 20;
    static final int HEAT = 2;

    record Planting(UUID planter, long at) {}

    static Plantings plantings(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Plantings::load, Plantings::new, Plantings.DATA_KEY); }

    /** Who planted which plant (bounded: the oldest are forgotten first). Saved. */
    static final class Plantings extends SavedData {
        static final String DATA_KEY = "underground_city_plantings";
        static final int MAX = 4096;
        final Map<Long, Planting> byPos = new LinkedHashMap<>();
        long seized;

        void put(long pos, Planting p) {
            byPos.remove(pos);
            byPos.put(pos, p);
            while (byPos.size() > MAX) byPos.remove(byPos.keySet().iterator().next());
            setDirty();
        }

        static Plantings load(CompoundTag root) {
            Plantings s = new Plantings();
            for (Tag t : root.getList("plantings", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                s.byPos.put(c.getLong("pos"), new Planting(c.getUUID("planter"), c.getLong("at")));
            }
            s.seized = root.getLong("seized");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag();
            byPos.forEach((pos, p) -> { CompoundTag c = new CompoundTag(); c.putLong("pos", pos); c.putUUID("planter", p.planter()); c.putLong("at", p.at()); list.add(c); });
            root.put("plantings", list);
            root.putLong("seized", seized);
            return root;
        }
    }

    // ------------------------------------------------------------------------------------------------ the rule

    /** True when the crop is prohibited in {@code city} (Ironhaven and Valewood; Blackwater licenses it). */
    static boolean prohibited(String city) {
        CityLaws.Law law = CityLaws.INSTANCE.find(city);
        return law != null && CityLaws.PROHIBITED.equals(law.status(CATEGORY));
    }

    /** True when a plant at {@code pos} is in the open and not on anybody's registered home or garden (the city is the caller's to judge). */
    static boolean inTheOpen(ServerLevel level, BlockPos pos) {
        if (StaffZones.roofed(level, pos)) return false;   // anything solid above, glass too (the block heightmap, not the sky light, which passes through glass)
        CrewLedger ledger = CrewService.ledger(level.getServer());
        return ledger == null || ledger.residenceAt(level.dimension().location().toString(), pos) == null;
    }

    /** The city whose law a plant at {@code pos} breaks by being where it is, if any. */
    static Optional<String> inPublicView(ServerLevel level, BlockPos pos) {
        return CityRegions.INSTANCE.cityAt(level, pos).filter(CannabisLaw::prohibited).filter(city -> inTheOpen(level, pos));
    }

    /** A plant was planted: remember by whom, and tell them at once if it is in public view. */
    static void planted(ServerLevel level, BlockPos pos, ServerPlayer player) {
        plantings(level.getServer()).put(pos.asLong(), new Planting(player.getUUID(), LockService.clock(level.getServer())));
        inPublicView(level, pos).ifPresent(city -> player.sendSystemMessage(Component.literal("Growing cannabis in the open in " + TerminalScreenText.capitalize(city)
            + " is against the law. An officer on the beat who sees this plant will pull it up. Under a roof, or at home, it is your own business.").withStyle(ChatFormatting.GOLD)));
    }

    static void forget(ServerLevel level, BlockPos pos) {
        Plantings p = plantings(level.getServer());
        if (p.byPos.remove(pos.asLong()) != null) p.setDirty();
    }

    /** One look from the plant's random tick: seized when it is in public view, its grower is online and an officer can see it. Returns whether it was. */
    static boolean look(ServerLevel level, BlockPos pos) {
        Planting planting = plantings(level.getServer()).byPos.get(pos.asLong());
        if (planting == null) return false;
        ServerPlayer grower = level.getServer().getPlayerList().getPlayer(planting.planter());
        if (grower == null) return false;
        Optional<String> city = inPublicView(level, pos);
        if (city.isEmpty() || seenBy(level, pos) == null) return false;
        seize(level, pos, planting.planter(), city.get());
        return true;
    }

    /** The officer on the beat who can see the plant at {@code pos}, or null. */
    static TownspersonEntity seenBy(ServerLevel level, BlockPos pos) {
        Vec3 plant = Vec3.atCenterOf(pos);
        for (TownspersonEntity officer : level.getEntitiesOfClass(TownspersonEntity.class, new AABB(pos).inflate(SIGHT), TownspersonEntity::police)) {
            if (!officer.isAlive() || officer.distanceToSqr(plant) > SIGHT * SIGHT) continue;
            var hit = level.clip(new ClipContext(officer.getEyePosition(), plant, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, officer));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return officer;
        }
        return null;
    }

    /** Pulls the plant up (nothing dropped), and tells its grower and gives them heat in {@code city}. */
    static void seize(ServerLevel level, BlockPos pos, UUID planter, String city) {
        level.removeBlock(pos, false);
        Plantings p = plantings(level.getServer());
        p.byPos.remove(pos.asLong());
        p.seized++;
        p.setDirty();
        try { WorkOrderLedger.get(level.getServer()).addHeat(planter, city, HEAT); }
        catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(CannabisLaw.class).warn("Heat for a seized plant could not be recorded: {}", error.toString()); }
        ServerPlayer grower = level.getServer().getPlayerList().getPlayer(planter);
        if (grower != null) Notice.send(grower, Notice.Kind.HEAT, "Plant seized", "An officer pulled up your cannabis at " + pos.getX() + ", " + pos.getZ() + ": grown in the open in "
            + TerminalScreenText.capitalize(city) + ", where it is prohibited. Heat there rose.");
        org.slf4j.LoggerFactory.getLogger(CannabisLaw.class).info("[UC] a cannabis plant at {} in {} was seized", pos.toShortString(), city);
    }
}
