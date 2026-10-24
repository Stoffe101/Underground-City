package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The visible security devices inside a city building (D-144, D-146): where its cameras hang, and what they see. A camera is any block the game knows as one: the CCTV cameras the furnished buildings
 * carry (Modern Life's, by block id at run time), SecurityCraft's when its set is loaded, and Core's own. A building's cameras never move (its walls cannot be broken), so the scan of its volume is
 * made once and kept; it reads only a loaded chunk, and costs one palette look per section that holds no camera at all.
 *
 * <p><b>What a camera does.</b> It watches a cone in front of it ({@link #RANGE} blocks, {@link #HALF_ANGLE_DEG} degrees either side of the way it faces) and sees anybody in it it has a clear line to.
 * A burglar it sees inside somebody else's building rings the building's alarm through Core's own dispatch, as an alarm does, unless the building's alarm panel is jammed or its cameras are looped.
 * A thief with <b>Camera Sense</b> sees each camera's field as faint particles and gets a second's warning before a camera holds them; a thief with <b>Wire Work</b> (or a Technician's Bypass Tooling)
 * can loop a building's cameras for ten minutes with a Bypass Kit. Nothing a camera does reaches anybody who is not inside a building that is not theirs. A camera never loads a chunk, never moves the
 * view, and is checked twice a second only for players standing in a building that has one.
 */
public final class SecurityDevices {
    /** Block ids that are cameras. */
    static final Set<String> CAMERA_IDS = Set.of("modernlife:cctv_camera", "securitycraft:security_camera", "underground_city:security_camera");
    private static final int CACHE = 512;
    private static final Map<Long, List<BlockPos>> CAMERAS = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<BlockPos>> eldest) { return size() > CACHE; }
    });

    /** Registered on the Forge bus for its clock and its clicks. */
    SecurityDevices() {}

    static boolean isCamera(BlockState state) { return CAMERA_IDS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }

    /** The cameras of a building (empty when its chunk is not loaded; asked again next time). */
    static List<BlockPos> cameras(ServerLevel level, CityBuildings.Building building) {
        List<BlockPos> known = CAMERAS.get(building.key());
        if (known != null) return known;
        LevelChunk chunk = level.getChunkSource().getChunkNow(building.chunkX(), building.chunkZ());
        if (chunk == null) return List.of();
        List<BlockPos> found = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(SecurityDevices::isCamera)) continue;
            int baseY = level.getSectionYFromSectionIndex(s) << 4;
            if (baseY + 15 < building.minY() || baseY >= building.maxY()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (isCamera(section.getBlockState(x, y, z))) found.add(new BlockPos(building.chunkX() * 16 + x, baseY + y, building.chunkZ() * 16 + z));
            }
        }
        List<BlockPos> result = List.copyOf(found);
        CAMERAS.put(building.key(), result);
        return result;
    }

    /** Test seam and data reload: forget what was scanned. */
    static void forget() { CAMERAS.clear(); LOOPED.clear(); WARNED.clear(); SEEN_AT.clear(); }

    // ------------------------------------------------------------------------------------------------ what a camera sees

    static final double RANGE = 12, HALF_ANGLE_DEG = 50;
    /** How long a camera loop lasts (active ticks), how long a Camera Sense warning holds before the camera calls, and how long before the same camera may call again. */
    static final long LOOP_TICKS = 10 * 1200L, WARNING_TICKS = 20, CALL_AGAIN_TICKS = 2400;
    private static final Map<Long, Long> LOOPED = new java.util.HashMap<>();
    private static final Map<java.util.UUID, Long> WARNED = new java.util.HashMap<>();
    private static final Map<Long, Long> SEEN_AT = new java.util.HashMap<>();
    static long sightings, calls;

    /** The way a camera looks: the facing its block state names (a CCTV camera's lens points that way), else south. */
    static net.minecraft.core.Direction facing(BlockState state) {
        for (var property : state.getProperties()) {
            if (property.getName().equals("facing") && state.getValue(property) instanceof net.minecraft.core.Direction d && d.getAxis().isHorizontal()) return d;
        }
        return net.minecraft.core.Direction.SOUTH;
    }

    /** True when a camera at {@code camera} facing {@code facing} sees {@code eye}: in range, inside its cone, with nothing solid between. Pure but for the line of sight. */
    static boolean sees(ServerLevel level, BlockPos camera, net.minecraft.core.Direction facing, net.minecraft.world.phys.Vec3 eye) {
        net.minecraft.world.phys.Vec3 lens = net.minecraft.world.phys.Vec3.atCenterOf(camera).add(facing.getStepX() * 0.5, -0.2, facing.getStepZ() * 0.5);
        net.minecraft.world.phys.Vec3 to = eye.subtract(lens);
        double distance = to.length();
        if (distance > RANGE || distance < 0.5) return false;
        double cos = (to.x * facing.getStepX() + to.z * facing.getStepZ()) / Math.max(1e-6, Math.hypot(to.x, to.z));
        if (cos < Math.cos(Math.toRadians(HALF_ANGLE_DEG))) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(lens, eye, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, null));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) < 0.25;
    }

    static boolean looped(ServerLevel level, CityBuildings.Building b) {
        Long until = LOOPED.get(b.key());
        return until != null && until > LockService.clock(level.getServer());
    }

    /** Twice a second: every player inside somebody else's building that has cameras is checked against them. */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 3) return;
        ServerLevel level = event.getServer().overworld();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            try { watch(level, player); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(SecurityDevices.class).warn("A camera check failed: {}", error.toString()); }
        }
    }

    static void watch(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        java.util.Optional<CityBuildings.Building> found = BurglarySearch.foreignBuilding(player, level, player.blockPosition());
        if (found.isEmpty()) return;
        CityBuildings.Building b = found.get();
        if (!CityBuildings.INSTANCE.known(b.chunkX(), b.chunkZ()) || BuildingGuard.openToPublic(level, b, null)) return;   // a customer in an open shop, or at a fence's at their hours, is not a burglar
        if (CityHomes.holdsKey(player, b)) return;   // nor a tenant crossing their own building's lobby, or anybody their home lets in (a housemate is never a burglar for coming home)
        List<BlockPos> cameras = cameras(level, b);
        if (cameras.isEmpty()) return;
        boolean sense = Thievery.has(player, "thief.camera_sense");
        long now = LockService.clock(level.getServer());
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        for (BlockPos camera : cameras) {
            if (camera.distSqr(player.blockPosition()) > (RANGE + 4) * (RANGE + 4) || !level.isLoaded(camera)) continue;
            net.minecraft.core.Direction facing = facing(level.getBlockState(camera));
            if (sense && level.getServer().getTickCount() % 20 == 3) showField(level, player, camera, facing);
            if (looped(level, b) || AlarmPanels.jammed(level, b) || TechService.securityDown(level, b) || !sees(level, camera, facing, eye)) continue;
            sightings++;
            if (sense) {
                // Camera Sense: a second's warning before the camera holds the thief; stepping out of its field in that second is clean
                Long warned = WARNED.get(player.getUUID());
                if (warned == null || now - warned > WARNING_TICKS * 4) {
                    WARNED.put(player.getUUID(), now);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("A camera is turning toward you.").withStyle(net.minecraft.ChatFormatting.GOLD), true);
                    continue;
                }
                if (now - warned < WARNING_TICKS) continue;
            }
            long key = camera.asLong() ^ player.getUUID().getLeastSignificantBits();
            Long last = SEEN_AT.get(key);
            if (last != null && now - last < CALL_AGAIN_TICKS) continue;
            SEEN_AT.put(key, now);
            calls++;
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("A camera has you. The building's alarm is calling the police.").withStyle(net.minecraft.ChatFormatting.RED));
            PoliceResponse.raise(player, level, b, camera, true, false, false);
            return;
        }
    }

    /** Camera Sense: a few faint particles along the edges and the middle of a camera's field, for this player only. */
    private static void showField(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos camera, net.minecraft.core.Direction facing) {
        double base = Math.atan2(facing.getStepZ(), facing.getStepX());
        for (double offset : new double[] {-Math.toRadians(HALF_ANGLE_DEG), 0, Math.toRadians(HALF_ANGLE_DEG)}) {
            for (double r = 1.5; r <= RANGE; r += 2.5) {
                double x = camera.getX() + 0.5 + Math.cos(base + offset) * r, z = camera.getZ() + 0.5 + Math.sin(base + offset) * r;
                level.sendParticles(player, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, true, x, camera.getY() - 0.5, z, 1, 0, 0, 0, 0);
            }
        }
    }

    /** A right click on a camera: a thief with Wire Work (or a Technician with Bypass Tooling) and a Bypass Kit loops every camera of the building for ten minutes. */
    @net.minecraftforge.eventbus.api.SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public void use(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!isCamera(level.getBlockState(event.getPos()))) return;
        java.util.Optional<CityBuildings.Building> found = BurglarySearch.foreignBuilding(player, level, event.getPos());
        if (found.isEmpty()) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        CityBuildings.Building b = found.get();
        if (looped(level, b)) { player.displayClientMessage(net.minecraft.network.chat.Component.literal("This building's cameras are already looped."), true); return; }
        boolean able = Thievery.has(player, AlarmPanels.WIRE_WORK) || SkillService.has(player, AlarmPanels.SKILL);
        net.minecraft.world.item.Item kit = UndergroundCity.item("bypass_kit");
        if (!able) { player.displayClientMessage(net.minecraft.network.chat.Component.literal("A camera. Looping its feed takes a Bypass Kit and Wire Work (or a Technician's Bypass Tooling)."), true); return; }
        if (player.getInventory().countItem(kit) < 1) { player.displayClientMessage(net.minecraft.network.chat.Component.literal("You need a Bypass Kit to loop the cameras."), true); return; }
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) if (stack.is(kit)) { stack.shrink(1); break; }
        LOOPED.put(b.key(), LockService.clock(level.getServer()) + LOOP_TICKS);
        level.playSound(null, event.getPos(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.get(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 0.6f);
        Notice.send(player, Notice.Kind.LOOT, "Cameras looped", "Every camera in this building shows an empty room for ten minutes.");
        Thievery.bypassed(player, b.key(), "cameras");
    }

    /** When a camera last warned this thief (Camera Sense), or null: a scripted player's check that the warning came. Server thread only, like the map. */
    static Long warnedAt(java.util.UUID player) { return WARNED.get(player); }

    static String status() { return "camera sightings " + sightings + ", calls " + calls + ", looped buildings " + LOOPED.size(); }
}
