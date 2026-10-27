package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The firing range's score. A TaCZ bullet that lands on a {@code minecraft:target} block inside the range district is scored by how near the centre of the
 * block's face it hit (10 on the middle, 0 at the edge), the shooter is told on a notice, and the citizen's best score and hit count are kept with their
 * journey. The server alone decides: the listener reads the hit from TaCZ's own server event, checks that a registered range stands there and that the block
 * is a target, and pays nothing: a range is practice, never income. Any target block, in the range or not, gives Shooting XP (D-200, {@link GeneralActivity#targetHit}).
 *
 * <p>TaCZ is optional, so the event is reached by reflection ({@code AmmoHitBlockEvent}); without TaCZ the range is simply scenery.
 */
public final class RangeService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Integer> LAST_NOTICE = new HashMap<>();
    private static final double HALF_FACE = 0.5;

    private RangeService() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        if (!ModList.get().isLoaded("tacz")) return;
        try {
            Class<?> type = Class.forName("com.tacz.guns.api.event.server.AmmoHitBlockEvent");
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) type, RangeService::hit);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("[UC] the firing range is scenery only: TaCZ's bullet-hit event could not be reached ({})", error.toString());
        }
    }

    /** 0 to 10 from the hit point's distance to the centre of the face that was hit (the corner of a face is about 0.7 away). */
    static int score(Vec3 hit, BlockPos block, Direction face) {
        Vec3 centre = Vec3.atCenterOf(block);
        double dx = hit.x - centre.x, dy = hit.y - centre.y, dz = hit.z - centre.z;
        double along = switch (face.getAxis()) {
            case X -> Math.hypot(dy, dz);
            case Y -> Math.hypot(dx, dz);
            case Z -> Math.hypot(dx, dy);
        };
        double fraction = Math.max(0.0, 1.0 - along / HALF_FACE);
        return (int) Math.round(10.0 * fraction);
    }

    private static void hit(Event event) {
        try {
            Level level = (Level) event.getClass().getMethod("getLevel").invoke(event);
            if (!(level instanceof ServerLevel serverLevel)) return;
            BlockHitResult result = (BlockHitResult) event.getClass().getMethod("getHitResult").invoke(event);
            Object ammo = event.getClass().getMethod("getAmmo").invoke(event);
            if (!(ammo instanceof Projectile bullet) || !(bullet.getOwner() instanceof ServerPlayer shooter)) return;
            if (!serverLevel.getBlockState(result.getBlockPos()).is(Blocks.TARGET)) return;
            int points = score(result.getLocation(), result.getBlockPos(), result.getDirection());
            double distance = Math.sqrt(shooter.distanceToSqr(Vec3.atCenterOf(result.getBlockPos())));
            GeneralActivity.targetHit(shooter, result.getBlockPos(), points, distance);   // Shooting XP for any target block (D-200)
            if (insideRange(serverLevel, result.getBlockPos())) record(shooter, points, (int) Math.round(distance));
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("[UC] a bullet hit could not be scored: {}", error.toString());
        }
    }

    /** True when {@code pos} lies inside a built firing range lot. */
    static boolean insideRange(ServerLevel level, BlockPos pos) {
        BlockPos origin = DistrictBuilder.originOf(level, "firing_range");
        DistrictCatalog.District range = DistrictCatalog.INSTANCE.find("firing_range");
        if (origin == null || range == null) return false;
        BlockPos lo = origin.offset(range.min()), hi = origin.offset(range.max());
        return pos.getX() >= lo.getX() && pos.getX() <= hi.getX() && pos.getY() >= lo.getY() && pos.getY() <= hi.getY() && pos.getZ() >= lo.getZ() && pos.getZ() <= hi.getZ();
    }

    /** Keeps the score for a registered citizen and tells the shooter (one notice every few ticks at most, so a burst of fire is not a wall of notices). */
    static void record(ServerPlayer shooter, int points, int distance) {
        CitizenState state = CitizenState.get(shooter.server);
        boolean registered = state.find(shooter.getUUID()).isPresent();
        boolean best = false;
        if (registered) {
            state.bumpJourney(shooter.getUUID(), "range_hits");
            best = state.raiseJourney(shooter.getUUID(), "range_best", points);
        }
        int now = shooter.server.getTickCount();
        Integer last = LAST_NOTICE.get(shooter.getUUID());
        if (last != null && now - last < 6) return;
        LAST_NOTICE.put(shooter.getUUID(), now);
        String title = points >= 10 ? "Bull's-eye" : points >= 8 ? "Good shot" : points >= 5 ? "On the target" : "Near the edge";
        Notice.send(shooter, Notice.Kind.DELIVERED, title + ": " + points + " of 10", distance + " blocks" + (best && points > 0 ? ". A new best." : ""));
    }
}
