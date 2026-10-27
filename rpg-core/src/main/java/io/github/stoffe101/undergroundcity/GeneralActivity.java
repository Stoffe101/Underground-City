package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fitness, Driving and Shooting (tenth pass, D-200): earned by doing them on purpose and used where a person feels it ({@link GeneralSkills} has the numbers and the words).
 *
 * <ul>
 *   <li><b>Fitness</b> rises with ground covered on foot, <b>Driving</b> with ground covered at the controls of a vehicle (not a living mount, not a minecart). Each player is
 *   sampled once a second and a window of ten seconds counts the straight line from its first sample to its last, so walking in circles or back and forth earns next to
 *   nothing; a window counts only when the player has turned their view in the last minute (a water stream or a moving platform does not move the mouse), holds no teleport
 *   (a step of more than {@link #TELEPORT_STEP} blocks in a second) and stays in one way of moving throughout; each skill earns at most its cap in an hour of the server's time.</li>
 *   <li><b>Shooting</b> rises with hits on a target block ({@code minecraft:target}) from ten blocks or more, by a TaCZ bullet or an arrow the player shot: the nearer the
 *   centre of the face, the more; the same block pays once in two seconds; at most its cap in an hour. A shot at a person or a creature never pays.</li>
 * </ul>
 *
 * The caps and the window state are kept in memory: a restart starts a new hour (written down in {@code docs/technical/general-skills.md}).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class GeneralActivity {
    static final int SAMPLE_TICKS = 20, WINDOW_SAMPLES = 10, LOOK_TICKS = 1200, HOUR_TICKS = 72_000;
    static final double LOOK_DEGREES = 5, TELEPORT_STEP = 40;
    static final double FOOT_BLOCKS_PER_XP = 25, FOOT_WINDOW_MAX = 70, DRIVE_BLOCKS_PER_XP = 50, DRIVE_WINDOW_MAX = 400;
    static final int FITNESS_CAP = 600, DRIVING_CAP = 600, SHOOTING_CAP = 300;
    static final double SHOT_MIN_DISTANCE = 10;
    static final int TARGET_COOLDOWN_TICKS = 40;
    static final UUID FITNESS_SPEED = UUID.fromString("3c0f1b9e-6a51-4d3e-9b51-2f8e0a6c1d10");

    enum Mode { NONE, FOOT, DRIVE }

    /** One player's samples: the window's first position, the last sample, how they move, when they last turned their view, and what each skill has earned this hour. */
    static final class Track {
        Vec3 start, last;
        Mode mode = Mode.NONE;
        int samples;
        boolean broken;
        float yaw, pitch;
        double turned;
        long lookedAt = Long.MIN_VALUE / 2;
        final Map<String, Integer> earned = new HashMap<>();
        final Map<String, Double> carry = new HashMap<>();
        long hourStart;
        final Map<Long, Long> targets = new HashMap<>();
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private GeneralActivity() {}

    static Track track(UUID player) { return TRACKS.computeIfAbsent(player, k -> new Track()); }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        if (now % SAMPLE_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sample(player, now);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { TRACKS.remove(event.getEntity().getUUID()); }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) { if (event.getEntity() instanceof ServerPlayer p) applyFitness(p); }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) { if (event.getEntity() instanceof ServerPlayer p) applyFitness(p); }

    /** How a player is moving now: on foot, at the controls of a vehicle, or neither (flying, gliding, a passenger, a mount, a minecart, a spectator). */
    static Mode mode(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.isCreative() || player.getAbilities().flying || player.isFallFlying()) return Mode.NONE;
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return Mode.FOOT;
        if (vehicle instanceof LivingEntity || vehicle instanceof AbstractMinecart) return Mode.NONE;
        return vehicle.getControllingPassenger() == player || vehicle.getFirstPassenger() == player ? Mode.DRIVE : Mode.NONE;
    }

    /** One sample: the view turned since the last, a teleport, and at the window's end its straight line paid out. */
    static void sample(ServerPlayer player, long now) {
        if (CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return;
        Track t = track(player.getUUID());
        Vec3 at = player.position();
        Mode mode = mode(player);
        t.turned += Math.abs(Mth.wrapDegrees(player.getYRot() - t.yaw)) + Math.abs(player.getXRot() - t.pitch);
        t.yaw = player.getYRot();
        t.pitch = player.getXRot();
        if (t.turned >= LOOK_DEGREES) { t.lookedAt = now; t.turned = 0; }
        if (t.start == null || t.mode != mode) { begin(t, at, mode); return; }
        if (at.distanceTo(t.last) > TELEPORT_STEP) t.broken = true;
        t.last = at;
        if (++t.samples < WINDOW_SAMPLES) return;
        double net = Math.hypot(at.x - t.start.x, at.z - t.start.z);
        if (!t.broken && now - t.lookedAt <= LOOK_TICKS && mode != Mode.NONE) {
            if (mode == Mode.FOOT) earn(player, t, GeneralSkills.FITNESS, Math.min(net, FOOT_WINDOW_MAX) / FOOT_BLOCKS_PER_XP, FITNESS_CAP, now);
            else earn(player, t, GeneralSkills.DRIVING, Math.min(net, DRIVE_WINDOW_MAX) / DRIVE_BLOCKS_PER_XP, DRIVING_CAP, now);
        }
        begin(t, at, mode);
        applyFitness(player);
    }

    private static void begin(Track t, Vec3 at, Mode mode) {
        t.start = at;
        t.last = at;
        t.mode = mode;
        t.samples = 0;
        t.broken = false;
    }

    /** Pays the whole XP of {@code amount} (the fraction is carried to the next window) within the skill's hourly cap; returns what was paid. */
    static int earn(ServerPlayer player, Track t, String skill, double amount, int cap, long now) {
        if (now - t.hourStart >= HOUR_TICKS) { t.hourStart = now; t.earned.clear(); }
        double total = amount + t.carry.getOrDefault(skill, 0.0);
        int whole = (int) Math.floor(total);
        int room = Math.max(0, cap - t.earned.getOrDefault(skill, 0));
        int paid = Math.min(whole, room);
        t.carry.put(skill, paid < whole ? 0.0 : total - whole);
        if (paid <= 0) return 0;
        t.earned.merge(skill, paid, Integer::sum);
        GeneralSkills.awardQuietly(player, skill, paid);
        return paid;
    }

    // ------------------------------------------------------------------------------------------------ Fitness: a little quicker on foot

    /** Keeps the walking-speed modifier at what the player's Fitness level gives (none below level 3). */
    static void applyFitness(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double want = CitizenState.get(player.server).find(player.getUUID()).map(c -> GeneralSkills.fitnessSpeed(c.generalLevel(GeneralSkills.FITNESS))).orElse(0.0);
        AttributeModifier have = speed.getModifier(FITNESS_SPEED);
        if (have != null && have.getAmount() == want) return;
        if (have != null) speed.removeModifier(FITNESS_SPEED);
        if (want > 0) speed.addTransientModifier(new AttributeModifier(FITNESS_SPEED, "Underground City fitness", want, AttributeModifier.Operation.MULTIPLY_BASE));
    }

    // ------------------------------------------------------------------------------------------------ Shooting

    /** A shot that hit a target block: Shooting XP by how near the centre it landed, from ten blocks or more, once per block in two seconds, within the hourly cap. Returns what was paid. */
    static int targetHit(ServerPlayer shooter, BlockPos target, int score, double distance) {
        if (distance < SHOT_MIN_DISTANCE || CitizenState.get(shooter.server).find(shooter.getUUID()).isEmpty()) return 0;
        Track t = track(shooter.getUUID());
        long now = shooter.server.getTickCount();
        Long last = t.targets.get(target.asLong());
        if (last != null && now - last < TARGET_COOLDOWN_TICKS) return 0;
        t.targets.put(target.asLong(), now);
        if (t.targets.size() > 64) t.targets.entrySet().removeIf(e -> now - e.getValue() >= TARGET_COOLDOWN_TICKS);
        return earn(shooter, t, GeneralSkills.SHOOTING, GeneralSkills.shotXp(score), SHOOTING_CAP, now);
    }

    /** An arrow a player shot landing on a target block (TaCZ's bullets come through {@link RangeService}). */
    @SubscribeEvent
    public static void impact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile instanceof AbstractArrow) || projectile.level().isClientSide() || !(projectile.getOwner() instanceof ServerPlayer shooter)) return;
        HitResult hit = event.getRayTraceResult();
        if (!(hit instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK || !projectile.level().getBlockState(block.getBlockPos()).is(Blocks.TARGET)) return;
        targetHit(shooter, block.getBlockPos(), RangeService.score(block.getLocation(), block.getBlockPos(), block.getDirection()), Math.sqrt(shooter.distanceToSqr(Vec3.atCenterOf(block.getBlockPos()))));
    }

    /** A steadier hand: an arrow or a TaCZ bullet a player of Shooting level 3 or more fires leaves closer to where they look (its speed unchanged). */
    @SubscribeEvent
    public static void fired(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || event.getLevel().isClientSide() || !(event.getEntity() instanceof Projectile projectile) || !(projectile.getOwner() instanceof ServerPlayer shooter)) return;
        if (!(projectile instanceof AbstractArrow) && !projectile.getClass().getName().equals("com.tacz.guns.entity.EntityKineticBullet")) return;
        double k = CitizenState.get(shooter.server).find(shooter.getUUID()).map(c -> GeneralSkills.steadiness(c.generalLevel(GeneralSkills.SHOOTING))).orElse(0.0);
        if (k > 0) projectile.setDeltaMovement(steady(projectile.getDeltaMovement(), shooter.getLookAngle(), k));
    }

    /** {@code motion} turned a fraction {@code k} of the way toward {@code look}, its speed kept. */
    static Vec3 steady(Vec3 motion, Vec3 look, double k) {
        double speed = motion.length();
        if (speed < 1e-6 || look.lengthSqr() < 1e-6) return motion;
        Vec3 blended = motion.normalize().scale(1 - k).add(look.normalize().scale(k));
        return blended.lengthSqr() < 1e-9 ? motion : blended.normalize().scale(speed);
    }

    /** Test seam. */
    static void clearForTest() { TRACKS.clear(); }
}
