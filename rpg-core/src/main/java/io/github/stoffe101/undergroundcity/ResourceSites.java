package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes mined veins of a resource site grow back, in <b>active play time</b> only (D-123). A world made with the scarcity package has no natural ore, so the base metals come from the cities' shelves
 * (D-120) and from one place that renews: a built district (the old quarry beside the landing) whose vein cells are listed in {@code resource_sites/<id>.json}.
 *
 * <p>Rules, all of them tested:
 * <ul>
 *   <li>A vein that is not its ore and is air is <b>depleted</b>; the moment it is first seen so, the active clock ({@link LockService#clock}, which advances only while somebody is online) is
 *       written beside it. Seen "first" means at most five seconds late, and a vein broken while nobody was looking (an explosion, a piston) is noticed when its chunk next has somebody near.</li>
 *   <li>After {@code interval_minutes} of <b>active</b> time it grows back, unless somebody (or something alive) stands in the cell. Offline time never counts: the clock does not move, so nothing renews while nobody
 *       plays and nobody is owed anything for being away.</li>
 *   <li>A cell that holds anything but air or its ore (a block somebody placed) is never overwritten and is forgotten until it is air again. Nothing is ever removed or replaced destructively.</li>
 *   <li>Nothing is placed in a chunk that is not loaded; the pass looks only at sites whose district was built in this world, and costs a handful of block reads per site every five seconds.</li>
 *   <li>A site exists only where its district was built: in a world that never built it, nothing here does anything, and an existing world is never given one.</li>
 * </ul>
 */
public final class ResourceSites {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Server ticks between two passes (five seconds). */
    static final int CYCLE = 100;
    static final String KEY = "underground_city_resource_sites";

    /** Per site (by id): vein index to the active tick at which it was first seen depleted. */
    static final class State extends SavedData {
        final Map<String, Map<Integer, Long>> depleted = new HashMap<>();

        static State load(CompoundTag root) {
            State state = new State();
            ListTag sites = root.getList("sites", Tag.TAG_COMPOUND);
            for (int i = 0; i < sites.size(); i++) {
                CompoundTag site = sites.getCompound(i);
                Map<Integer, Long> veins = new HashMap<>();
                ListTag list = site.getList("depleted", Tag.TAG_COMPOUND);
                for (int j = 0; j < list.size() && j < ResourceSiteCatalog.MAX_VEINS; j++) veins.put(list.getCompound(j).getInt("i"), list.getCompound(j).getLong("t"));
                state.depleted.put(site.getString("id"), veins);
            }
            return state;
        }

        @Override public CompoundTag save(CompoundTag root) {
            ListTag sites = new ListTag();
            depleted.forEach((id, veins) -> {
                if (veins.isEmpty()) return;
                CompoundTag site = new CompoundTag();
                site.putString("id", id);
                ListTag list = new ListTag();
                veins.forEach((index, time) -> { CompoundTag v = new CompoundTag(); v.putInt("i", index); v.putLong("t", time); list.add(v); });
                site.put("depleted", list);
                sites.add(site);
            });
            root.put("sites", sites);
            return root;
        }
    }

    // measurements for /uc_site status
    static long passes, regrown, noticed, blockedByPlayer, blockedByBuilder;

    static State state(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY);
    }

    public record Report(int veins, int standing, int depleted, int regrown, int waiting) {}

    /**
     * One pass over one site at {@code origin}: notices depleted veins, grows back those whose time is up. Pure of the tick handler so the tests can drive it with their own clock and origin.
     * Returns what it saw.
     */
    static Report reconcile(ServerLevel level, ResourceSiteCatalog.Site site, BlockPos origin, long now) {
        State state = state(level);
        Map<Integer, Long> depleted = state.depleted.computeIfAbsent(site.id(), id -> new HashMap<>());
        int standing = 0, waiting = 0, grew = 0;
        for (int i = 0; i < site.veins().size(); i++) {
            var vein = site.veins().get(i);
            BlockPos pos = origin.offset(vein.at());
            if (!level.isLoaded(pos)) { if (depleted.containsKey(i)) waiting++; continue; }
            BlockState here = level.getBlockState(pos);
            if (here.is(vein.block())) {
                standing++;
                if (depleted.remove(i) != null) state.setDirty();
                continue;
            }
            if (!here.isAir()) {
                // somebody built here: left alone, and not owed to regrow until it is air again
                blockedByBuilder++;
                if (depleted.remove(i) != null) state.setDirty();
                continue;
            }
            Long since = depleted.get(i);
            if (since == null) { depleted.put(i, now); state.setDirty(); noticed++; waiting++; continue; }
            if (since > now) { depleted.put(i, now); state.setDirty(); waiting++; continue; }   // a clock that went backwards (a restored backup) restarts the wait; it never pays out early
            if (now - since < site.intervalTicks()) { waiting++; continue; }
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(pos).inflate(0.5), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty()) { blockedByPlayer++; waiting++; continue; }
            level.setBlock(pos, vein.block().defaultBlockState(), Block.UPDATE_ALL);
            depleted.remove(i);
            state.setDirty();
            standing++; grew++; regrown++;
        }
        return new Report(site.veins().size(), standing, site.veins().size() - standing, grew, waiting);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 0) return;
        MinecraftServer server = event.getServer();
        if (server.getPlayerList().getPlayerCount() == 0) return;   // nothing renews while nobody is here, and the clock would not move anyway
        try { pass(server); }
        catch (RuntimeException error) { LOGGER.error("Resource site pass failed", error); }
    }

    static void pass(MinecraftServer server) {
        var sites = ResourceSiteCatalog.INSTANCE.all();
        if (sites.isEmpty()) return;
        ServerLevel level = server.overworld();
        long now = LockService.clock(server);
        passes++;
        for (var site : sites) {
            BlockPos origin = DistrictBuilder.originOf(level, site.district());
            if (origin == null) continue;
            reconcile(level, site, origin, now);
        }
    }

    static String status(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        ServerLevel level = server.overworld();
        for (var site : ResourceSiteCatalog.INSTANCE.all()) {
            BlockPos origin = DistrictBuilder.originOf(level, site.district());
            Map<Integer, Long> depleted = state(level).depleted.getOrDefault(site.id(), Map.of());
            out.append(String.format(Locale.ROOT, "%s: %s, %d veins every %d active minutes (at most %.0f ores an active hour), %d waiting; ", site.id(),
                origin == null ? "not built in this world" : "built at " + origin.toShortString(), site.veins().size(), site.intervalMinutes(), site.oresPerHourCeiling(), depleted.size()));
        }
        return out + String.format(Locale.ROOT, "passes %d, noticed %d, regrown %d, held by a player %d, held by a builder %d", passes, noticed, regrown, blockedByPlayer, blockedByBuilder);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_site").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status(context.getSource().getServer())), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    /** Test seam. */
    static void resetForTest() { passes = regrown = noticed = blockedByPlayer = blockedByBuilder = 0; }
}
