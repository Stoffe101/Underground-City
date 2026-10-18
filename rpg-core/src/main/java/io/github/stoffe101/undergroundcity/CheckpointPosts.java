package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Gives every checkpoint zone a place and a person: {@code /uc_checkpoints place} builds the {@code checkpoint_post} district (a booth
 * and one guard) beside the road at each zone, an operator step like Founders Row, reversible one post at a time. The zone still decides
 * who is searched (it is a seeded roll over the law and the traveller's heat, see {@link CheckpointService}); the post only makes the actor
 * visible, and the guard it holds is the one who speaks when a search happens. Nothing here is a hard-coded coordinate: the position of each
 * zone is data, and the post is placed relative to the road plan.
 */
public final class CheckpointPosts {
    static final String DISTRICT = "checkpoint_post";
    /** How far beyond the road's reach (its deck, parapet and clearing band) the post's nearest edge stands. */
    static final int GAP = 3;

    public record Placement(CheckpointCatalog.Checkpoint post, BlockPos origin, double distanceFromRoad) {}

    /** A lot standing beside a road: its north-west corner (y is not decided here) and its centre. */
    record Lot(BlockPos origin, double cx, double cz) {}

    /**
     * The lot of {@code lotWidth} x {@code lotDepth} blocks beside the road at arc position {@code s}, clear of everything the road touches: the lot's
     * nearest corner stands {@link #GAP} blocks beyond the road's reach. {@code side} is +1 for the right-hand side of the direction of travel as a
     * walker sees it (x is east, z is south, so that is {@code (-tz, tx)}) and -1 for the left. Empty where the road has no direction (a zero-length stretch).
     */
    static Optional<Lot> lotBeside(CorridorPlan plan, double s, int lotWidth, int lotDepth, int side) {
        double[] at = plan.pointAt(s);
        double[] behind = plan.pointAt(Math.max(0, s - 3)), ahead = plan.pointAt(Math.min(plan.length(), s + 3));
        double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1];
        double length = Math.hypot(tx, tz);
        if (length < 1e-6) return Optional.empty();
        tx /= length; tz /= length;
        double nx = -tz * side, nz = tx * side;
        // The lot's centre must be far enough out that its nearest corner clears the road: half the diagonal plus the road's reach plus a gap.
        double half = Math.hypot(lotWidth, lotDepth) / 2.0;
        double out = plan.reach() + GAP + half;
        double cx = at[0] + nx * out, cz = at[1] + nz * out;
        return Optional.of(new Lot(new BlockPos((int) Math.floor(cx - lotWidth / 2.0), 0, (int) Math.floor(cz - lotDepth / 2.0)), cx, cz));
    }

    /**
     * Where the post for a zone goes: beside the road, on the right-hand side of its direction of travel, clear of everything the road touches.
     * Returns empty when no road runs through the zone.
     */
    static Optional<Placement> placement(CheckpointCatalog.Checkpoint post, List<CorridorPlan> plans, int lotWidth, int lotDepth) {
        CorridorPlan best = null;
        CorridorPlan.Hit bestHit = null;
        for (CorridorPlan plan : plans) {
            CorridorPlan.Hit hit = plan.project(post.x(), post.z());
            if (hit != null && (bestHit == null || hit.distance() < bestHit.distance())) { best = plan; bestHit = hit; }
        }
        if (best == null || bestHit.distance() > post.radius()) return Optional.empty();
        CorridorPlan road = best;
        return lotBeside(road, bestHit.s(), lotWidth, lotDepth, 1).map(lot -> new Placement(post, lot.origin(), road.project(lot.cx(), lot.cz()).distance()));
    }

    /** Builds a post at every zone that has none within its lot. Returns what was done, one line per zone. */
    static List<String> placeAll(ServerLevel level) {
        List<String> report = new ArrayList<>();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(DISTRICT);
        if (district == null) return List.of("The datapack has no " + DISTRICT + " district.");
        List<CorridorPlan> plans = DevCommands.plans(level.getServer());
        if (plans.isEmpty()) return List.of("There are no generated roads in this world (the world-prototype pack is missing).");
        int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
        for (CheckpointCatalog.Checkpoint post : CheckpointCatalog.INSTANCE.all()) {
            if (!post.dimension().equals(level.dimension().location().toString())) continue;
            Optional<Placement> placement = placement(post, plans, width, depth);
            if (placement.isEmpty()) { report.add(post.title() + ": no road runs through this zone."); continue; }
            BlockPos flat = placement.get().origin();
            try {
                // Load the lot's chunks and stand the post on the ground at its middle.
                for (int cx = flat.getX() >> 4; cx <= (flat.getX() + width) >> 4; cx++) for (int cz = flat.getZ() >> 4; cz <= (flat.getZ() + depth) >> 4; cz++) level.getChunk(cx, cz);
                BlockPos ground = DevCommands.surface(level, flat.getX() + width / 2, flat.getZ() + depth / 2);
                DistrictBuilder.build(level, district, new BlockPos(flat.getX(), ground.getY(), flat.getZ()));
                report.add(post.title() + ": post built at " + flat.getX() + ", " + ground.getY() + ", " + flat.getZ() + ".");
            } catch (IllegalStateException error) {
                report.add(post.title() + ": " + error.getMessage());
            }
        }
        return report;
    }

    /** The guard standing at a checkpoint zone, if a post was built there: the nearest district guard within the zone. */
    static Optional<Villager> guardAt(ServerLevel level, CheckpointCatalog.Checkpoint post) {
        AABB box = new AABB(post.x() - post.radius(), level.getMinBuildHeight(), post.z() - post.radius(), post.x() + post.radius(), level.getMaxBuildHeight(), post.z() + post.radius());
        return level.getEntitiesOfClass(Villager.class, box, villager -> CityNpcs.definition(villager).map(npc -> npc.role().equals("guard")).orElse(false))
            .stream().min(Comparator.comparingDouble(villager -> villager.distanceToSqr(post.x(), villager.getY(), post.z())));
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_checkpoints").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("place").executes(ctx -> {
            List<String> report = placeAll(ctx.getSource().getServer().overworld());
            report.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), true));
            return report.size();
        }));
        root.then(Commands.literal("remove").then(Commands.literal("confirm").executes(ctx -> {
            try {
                DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(DISTRICT);
                if (district == null) throw new IllegalStateException("The datapack has no " + DISTRICT + " district.");
                DistrictBuilder.undoNear(ctx.getSource().getLevel(), district, BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal("The nearest checkpoint post was removed and the ground restored."), true);
                return 1;
            } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
        })));
        root.then(Commands.literal("list").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("Posts built: " + DistrictBuilder.count(ctx.getSource().getLevel(), DISTRICT) + " of " + CheckpointCatalog.INSTANCE.all().size() + " zones."), false);
            return 1;
        }));
        event.getDispatcher().register(root);
    }
}
