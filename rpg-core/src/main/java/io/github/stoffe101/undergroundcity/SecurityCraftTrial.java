package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The SecurityCraft trial (D-140): SecurityCraft's devices installed in a city building by Core and answering to Core. An opt-in set ({@code -PucPack=...,securitycraft}) that
 * no default launch includes, and nothing here does anything when SecurityCraft is not loaded.
 *
 * <p>Who decides: Core. Every device is placed by Core and owned by one system owner ({@link #SYSTEM_NAME}), so no player owns, re-codes or removes one; SecurityCraft supplies the
 * physical device, its screens and its signals. A keypad door's code is issued by Core and kept by Core (SecurityCraft stores only a salted hash). A correct code typed by a
 * <b>keyholder</b> Core named is an ordinary entry; the same code typed by anybody else is an entry into a building that is not theirs, a quiet break-in that Core's police,
 * witnesses, alarm panels, heat and stash all answer exactly as they answer a picked lock. A lockpick still works on a keypad door (it is a door), through Core's lock. The laser
 * across the hall is SecurityCraft's detection; Core reads it, decides whether whoever crossed it belongs there, and if not rings the building's alarm (Core's dispatch) and
 * the SecurityCraft siren with it. A camera shows its feed only to the keyholders, through SecurityCraft's own allowlist that Core writes.
 *
 * <p>What is kept out: every SecurityCraft recipe is removed while the trial runs (devices come only from Core), and a player cannot place a SecurityCraft block, use a
 * SecurityCraft item other than the camera monitor, the manual and keycards, or bring a SecurityCraft entity (sentry, mine, I.M.S., boat) into the world. Lethal devices, offline
 * punishment and a second lock or ownership system are not part of the trial.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class SecurityCraftTrial {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOD = "securitycraft", KEY = "underground_city_securitycraft_trial";
    static final String SYSTEM_NAME = "UC_Security";
    static final UUID SYSTEM_UUID = UUID.nameUUIDFromBytes("underground_city:securitycraft_system".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    /** SecurityCraft items a player may still use: viewing an allowed camera, reading the manual, a Core-issued keycard. */
    static final Set<String> USABLE = Set.of("camera_monitor", "sc_manual", "keycard_lv1", "keycard_lv2", "keycard_lv3", "keycard_lv4", "keycard_lv5", "limited_use_keycard", "keycard_holder");
    /** How long the siren sounds after a trip, and the least time between two trips of one install. */
    static final int SIREN_TICKS = 600, TRIP_COOLDOWN = 200, CODE_DIGITS = 6;

    private SecurityCraftTrial() {}

    static boolean loaded() { return ModList.get() != null && ModList.get().isLoaded(MOD); }

    static boolean isSecurityCraft(Block block) { return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(MOD); }

    static boolean isKeypadDoor(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(MOD + ":keypad_door"); }

    // ------------------------------------------------------------------------------------------ the record
    /** One installed building: its keypad door, its code, its devices and its keyholders. */
    static final class Install {
        long building; String city = ""; BlockPos door; String code = "";
        final List<BlockPos> lasers = new ArrayList<>(), alarms = new ArrayList<>(), cameras = new ArrayList<>(), lights = new ArrayList<>();
        final Map<UUID, String> keyholders = new LinkedHashMap<>();
        /** Wall blocks a laser was set into, by position: what stood there (for a later removal; not saved, the trial's world is disposable). */
        final Map<Long, BlockState> replaced = new LinkedHashMap<>();
        long lastTrip = -TRIP_COOLDOWN, sirenUntil;
        int trips, codeEntries, keyholderEntries;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("building", building); tag.putString("city", city); tag.putLong("door", door.asLong()); tag.putString("code", code);
            tag.putInt("trips", trips); tag.putInt("code_entries", codeEntries); tag.putInt("keyholder_entries", keyholderEntries);
            for (var list : Map.of("lasers", lasers, "alarms", alarms, "cameras", cameras, "lights", lights).entrySet()) tag.putLongArray(list.getKey(), list.getValue().stream().mapToLong(BlockPos::asLong).toArray());
            CompoundTag keys = new CompoundTag();
            keyholders.forEach((id, name) -> keys.putString(id.toString(), name));
            tag.put("keyholders", keys);
            return tag;
        }

        static Install load(CompoundTag tag) {
            Install i = new Install();
            i.building = tag.getLong("building"); i.city = tag.getString("city"); i.door = BlockPos.of(tag.getLong("door")); i.code = tag.getString("code");
            i.trips = tag.getInt("trips"); i.codeEntries = tag.getInt("code_entries"); i.keyholderEntries = tag.getInt("keyholder_entries");
            for (long p : tag.getLongArray("lasers")) i.lasers.add(BlockPos.of(p));
            for (long p : tag.getLongArray("alarms")) i.alarms.add(BlockPos.of(p));
            for (long p : tag.getLongArray("cameras")) i.cameras.add(BlockPos.of(p));
            for (long p : tag.getLongArray("lights")) i.lights.add(BlockPos.of(p));
            CompoundTag keys = tag.getCompound("keyholders");
            for (String k : keys.getAllKeys()) i.keyholders.put(UUID.fromString(k), keys.getString(k));
            return i;
        }
    }

    static final class State extends SavedData {
        final Map<Long, Install> installs = new LinkedHashMap<>();

        static State load(CompoundTag tag) {
            State state = new State();
            for (Tag t : tag.getList("installs", Tag.TAG_COMPOUND)) { Install i = Install.load((CompoundTag) t); state.installs.put(i.door.asLong(), i); }
            return state;
        }

        @Override public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Install i : installs.values()) list.add(i.save());
            tag.put("installs", list);
            return tag;
        }
    }

    static State state(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY); }

    static FakePlayer system(ServerLevel level) { return FakePlayerFactory.get(level, new GameProfile(SYSTEM_UUID, SYSTEM_NAME)); }

    // ------------------------------------------------------------------------------------------ SecurityCraft, by reflection (no compile-time dependency)
    private static Block block(String id) {
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(MOD, id));
        if (block == net.minecraft.world.level.block.Blocks.AIR) throw new IllegalStateException("SecurityCraft has no block " + id);
        return block;
    }

    private static ItemStack stack(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(MOD, id))); }

    private static Method method(Object target, String name, Class<?>... types) throws NoSuchMethodException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) { try { return i.getMethod(name, types); } catch (NoSuchMethodException ignored) { } }
            try { return c.getMethod(name, types); } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    /** Places a SecurityCraft block as the system owner would by hand: the state, then the block's own placement (which names the placer its owner and links lasers). */
    static void placeAsSystem(ServerLevel level, BlockPos pos, BlockState state, String itemId) {
        level.setBlock(pos, state, 3);
        state.getBlock().setPlacedBy(level, pos, level.getBlockState(pos), system(level), stack(itemId));
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) setOwner(entity);
    }

    static void setOwner(BlockEntity entity) {
        try { method(entity, "setOwner", String.class, String.class).invoke(entity, SYSTEM_UUID.toString(), SYSTEM_NAME); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("SecurityCraft owner API changed: " + error); }
    }

    static String ownerUuid(BlockEntity entity) {
        try {
            Object owner = method(entity, "getOwner").invoke(entity);
            return String.valueOf(method(owner, "getUUID").invoke(owner));
        } catch (ReflectiveOperationException error) { return ""; }
    }

    /** Sets a keypad door's code on both halves (SecurityCraft salts and hashes it on its own thread; Core keeps the code itself). */
    static void setCode(ServerLevel level, BlockPos lower, String code) {
        try {
            Class<?> utils = Class.forName("net.geforcemods.securitycraft.util.PasscodeUtils", true, block("keypad_door").getClass().getClassLoader());
            String hashed = (String) utils.getMethod("hashPasscodeWithoutSalt", String.class).invoke(null, code);
            for (BlockPos half : List.of(lower, lower.above())) {
                BlockEntity entity = level.getBlockEntity(half);
                if (entity != null) method(entity, "hashAndSetPasscode", String.class).invoke(entity, hashed);
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("SecurityCraft passcode API changed: " + error); }
    }

    static void insertModule(ServerLevel level, BlockPos pos, ItemStack module) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return;
        try { method(entity, "insertModule", ItemStack.class, boolean.class).invoke(entity, module, false); }
        catch (ReflectiveOperationException error) { LOGGER.warn("[UC] SecurityCraft module could not be fitted at {}: {}", pos.toShortString(), error.toString()); }
    }

    /** An allowlist module naming the keyholders (SecurityCraft's allowlists hold player names, not UUIDs). */
    static ItemStack allowlist(Install install) {
        ItemStack module = stack("whitelist_module");
        CompoundTag tag = module.getOrCreateTag();
        int n = 0;
        for (String name : install.keyholders.values()) if (n < 50) tag.putString("Player" + (++n), name);
        return module;
    }

    /** Rewrites the names on the allowlist module fitted to a device (SecurityCraft reads the module's stack each time it checks). */
    static void rewriteAllowlist(ServerLevel level, BlockPos pos, Install install) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return;
        try {
            @SuppressWarnings("unchecked") List<ItemStack> modules = (List<ItemStack>) method(entity, "getInventory").invoke(entity);
            for (ItemStack module : modules) if (BuiltInRegistries.ITEM.getKey(module.getItem()).getPath().equals("whitelist_module")) module.setTag(allowlist(install).getTag());
            entity.setChanged();
        } catch (ReflectiveOperationException error) { LOGGER.warn("[UC] SecurityCraft allowlist could not be rewritten at {}: {}", pos.toShortString(), error.toString()); }
    }

    static void sound(ServerLevel level, BlockPos alarm, boolean on) {
        BlockEntity entity = level.getBlockEntity(alarm);
        if (entity == null) return;
        try {
            method(entity, "setPowered", boolean.class).invoke(entity, on);
            BlockState state = level.getBlockState(alarm);
            if (state.hasProperty(BlockStateProperties.LIT)) level.setBlock(alarm, state.setValue(BlockStateProperties.LIT, on), 3);
        } catch (ReflectiveOperationException error) { LOGGER.warn("[UC] SecurityCraft alarm could not be sounded: {}", error.toString()); }
    }

    // ------------------------------------------------------------------------------------------ installing
    /** A fresh six-digit code. */
    static String newCode(net.minecraft.util.RandomSource random) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_DIGITS; i++) code.append(random.nextInt(10));
        return code.toString();
    }

    /**
     * Installs the trial's devices at the exterior door {@code lower} of a city building: the door becomes a keypad door with a new code, a laser pair crosses the hall two steps
     * inside (where the walls leave room), a siren and a camera hang over the inside of the door and a motion light beside them. Returns the record. Throws with the reason when
     * the door or the building does not fit.
     */
    static Install install(ServerLevel level, BlockPos lower) {
        if (!loaded()) throw new IllegalStateException("SecurityCraft is not installed (it is the opt-in securitycraft set).");
        BlockState old = level.getBlockState(lower);
        if (!(old.getBlock() instanceof DoorBlock)) throw new IllegalStateException("That is not a door.");
        if (old.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) { lower = lower.below(); old = level.getBlockState(lower); }
        CityBuildings.Building building = CityBuildings.INSTANCE.at(level, lower).orElseThrow(() -> new IllegalStateException("That door is not a city building's."));
        if (!BuildingGuard.exterior(level, lower, old)) throw new IllegalStateException("That is not a street door.");
        Direction facing = old.getValue(DoorBlock.FACING);
        Direction inside = BuildingGuard.exposed(level, lower.relative(facing).above()) ? facing.getOpposite() : facing;
        // the door: the old one out (no drops), the keypad door in, owned by the system, with Core's code
        level.setBlock(lower.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlock(lower, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        BlockState door = block("keypad_door").defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, old.getValue(DoorBlock.HINGE))
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.OPEN, false);
        placeAsSystem(level, lower, door, "keypad_door_item");
        if (!isKeypadDoor(level.getBlockState(lower.above()))) level.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        if (level.getBlockEntity(lower.above()) != null) setOwner(level.getBlockEntity(lower.above()));
        Install install = new Install();
        install.building = building.key(); install.city = building.city(); install.door = lower.immutable(); install.code = newCode(level.random);
        setCode(level, lower, install.code);
        // the laser across the hall at ankle height, one to four steps in: two laser blocks set into the hall's side walls (a tenement's entrance is a corridor a door wide),
        // or standing at the ends of a wider hall's open floor; the field between them is what a person walks through
        Direction across = inside.getClockWise();
        for (int k = 1; k <= 4 && install.lasers.isEmpty(); k++) {
            BlockPos row = lower.relative(inside, k);
            if (!level.getBlockState(row).isAir() || !level.getBlockState(row.above()).isAir()) continue;
            int left = reach(level, row, across.getOpposite()), right = reach(level, row, across);
            BlockPos a = row.relative(across.getOpposite(), left + 1), b = row.relative(across, right + 1);   // the first solid blocks either side: the walls
            if (left + right + 1 > 5 || !wall(level, a) || !wall(level, b)) continue;
            install.replaced.put(a.asLong(), level.getBlockState(a)); install.replaced.put(b.asLong(), level.getBlockState(b));
            for (BlockPos end : List.of(a, b)) placeAsSystem(level, end, block("laser_block").defaultBlockState(), "laser_block");
            // the field is drawn when the second laser is placed by its owner; the redstone module makes a crossing a signal that Core reads
            insertModule(level, a, stack("redstone_module"));
            install.lasers.add(a); install.lasers.add(b);
        }
        // the siren, the camera and the light on the walls just inside the door, at head height and above: the nearest open cells with a wall to hang from
        List<BlockPos> used = new ArrayList<>();
        for (String device : List.of("alarm", "security_camera", "motion_activated_light")) {
            List<BlockPos> into = device.equals("alarm") ? install.alarms : device.equals("security_camera") ? install.cameras : install.lights;
            search:
            for (int up = 2; up <= 3; up++) for (int k = 1; k <= 3; k++) for (int a = 0; a <= 2; a++) for (int sign : a == 0 ? new int[]{1} : new int[]{1, -1}) {
                BlockPos at = lower.relative(inside, k).relative(across, a * sign).above(up);
                if (used.contains(at) || !level.getBlockState(at).isAir()) continue;
                for (Direction from : new Direction[]{inside.getOpposite(), across, across.getOpposite(), inside}) {
                    if (!wall(level, at.relative(from))) continue;
                    if (tryHang(level, at, from.getOpposite(), device, device, into)) { used.add(at); break search; }
                }
            }
        }
        for (BlockPos camera : install.cameras) insertModule(level, camera, allowlist(install));
        State state = state(level.getServer());
        state.installs.put(install.door.asLong(), install);
        state.setDirty();
        LOGGER.info("[UC] SecurityCraft trial installed at {} in {} (building {}): lasers {}, alarms {}, cameras {}, lights {}", install.door.toShortString(), install.city, install.building,
            install.lasers.size(), install.alarms.size(), install.cameras.size(), install.lights.size());
        return install;
    }

    /** How many air blocks (ankle and head height) lie from {@code from} toward {@code direction}, up to 3: where the laser block will stand. */
    private static int reach(ServerLevel level, BlockPos from, Direction direction) {
        int n = 0;
        while (n < 3 && level.getBlockState(from.relative(direction, n + 1)).isAir() && level.getBlockState(from.relative(direction, n + 1).above()).isAir()) n++;
        return n;
    }

    /** A solid, full block of a building's wall (not glass, a door or furniture): a laser can be set into it, a device hung on it. */
    private static boolean wall(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.isCollisionShapeFullBlock(level, pos) && state.isSolidRender(level, pos) && !(state.getBlock() instanceof DoorBlock) && level.getBlockEntity(pos) == null;
    }

    /** Hangs a wall device at {@code at} (air, against the wall behind it) facing into the room; keeps the position when it stuck. */
    private static boolean tryHang(ServerLevel level, BlockPos at, Direction into, String blockId, String itemId, List<BlockPos> out) {
        if (!level.getBlockState(at).isAir() || level.getBlockState(at.relative(into.getOpposite())).isAir()) return false;
        BlockState state = block(blockId).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, into);
        else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, into);
        if (!state.canSurvive(level, at)) return false;
        placeAsSystem(level, at, state, itemId);
        if (level.getBlockState(at).getBlock() != state.getBlock()) return false;
        out.add(at.immutable());
        return true;
    }

    static Install at(MinecraftServer server, BlockPos lower) { return state(server).installs.get(lower.asLong()); }

    // ------------------------------------------------------------------------------------------ Core's authority
    /** A keypad door opened (SecurityCraft emits the vanilla game event with the player as its cause). Core decides what the entry was. */
    @SubscribeEvent
    public static void opened(VanillaGameEvent event) {
        if (!loaded() || event.getVanillaEvent() != GameEvent.BLOCK_OPEN || !(event.getLevel() instanceof ServerLevel level) || !(event.getCause() instanceof ServerPlayer player)) return;
        BlockPos pos = BlockPos.containing(event.getEventPosition());
        BlockState state = level.getBlockState(pos);
        if (!isKeypadDoor(state)) return;
        BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        Install install = at(level.getServer(), lower);
        if (install == null) return;
        // a door opened from the inside is somebody leaving (Core opened it for them); only the street side asks for the code
        if (!BuildingGuard.outsideSide(level, lower, level.getBlockState(lower), player.position())) return;
        entered(level, player, install, lower);
    }

    /** What an entry by code is: a keyholder's own way in, or a break-in by somebody who learnt the code. Visible for the GameTests. */
    static void entered(ServerLevel level, ServerPlayer player, Install install, BlockPos lower) {
        long now = LockService.clock(level.getServer());
        State state = state(level.getServer());
        if (install.keyholders.containsKey(player.getUUID()) || player.isCreative()) {
            install.keyholderEntries++;
            PropertyLocks.get(level.getServer()).exitOpen(lower, now + BuildingGuard.EXIT_TICKS);
            state.setDirty();
            LOGGER.info("[UC] keyholder {} entered by code at {}", player.getGameProfile().getName(), lower.toShortString());
            return;
        }
        install.codeEntries++;
        state.setDirty();
        PropertyLocks.get(level.getServer()).breach(lower, now + LockService.BREACH_TICKS);
        try { WorkOrderLedger.get(level.getServer()).addHeat(player.getUUID(), install.city, LockService.HEAT_OPENED); }
        catch (RuntimeException error) { LOGGER.warn("Heat from a code entry could not be recorded: {}", error.toString()); }
        CityBuildings.INSTANCE.at(level, lower).ifPresent(building -> BuildingStash.furnish(level, building, lower, now));
        // the code got them in without a sound: a quiet entry, which the police, witnesses and the alarm panel weigh as they weigh a clean pick
        MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(player.getUUID(), lower, install.city, "keypad", BuildingBreachEvent.Outcome.OPENED, now, false, player).quiet(true));
        Notice.send(player, Notice.Kind.HEAT, "In by the code", "Nobody gave you this code. Whoever owns the place will know the door was opened.");
        LOGGER.info("[UC] {} entered by a code that is not theirs at {}", player.getGameProfile().getName(), lower.toShortString());
    }

    /** Every few ticks: a laser that SecurityCraft powered (somebody crossed it) or anybody standing in its field; Core decides whether they belong. Then the siren's time. */
    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        // every tick: a software-rendered client can move a block and more between two of its position updates, and a check every few ticks let a walker through (run 3)
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !loaded()) return;
        State state = state(level.getServer());
        if (state.installs.isEmpty()) return;
        long now = level.getGameTime();
        for (Install install : state.installs.values()) {
            if (install.sirenUntil > 0 && now >= install.sirenUntil) { install.sirenUntil = 0; for (BlockPos alarm : install.alarms) if (loadedNow(level, alarm)) sound(level, alarm, false); }
            if (install.lasers.size() < 2 || now - install.lastTrip < TRIP_COOLDOWN || !loadedNow(level, install.lasers.get(0)) || !loadedNow(level, install.lasers.get(1))) continue;
            BlockPos a = install.lasers.get(0), b = install.lasers.get(1);
            boolean powered = poweredLaser(level.getBlockState(a)) || poweredLaser(level.getBlockState(b));
            // the beam's own row of blocks: with the walker's own width a crossing is inside it for some seven ticks at a walk (run 5 found a wider box reaching into the doorway)
            AABB field = new AABB(a).minmax(new AABB(b));
            List<ServerPlayer> inside = level.getEntitiesOfClass(ServerPlayer.class, field, p -> !p.isSpectator() && !p.isCreative());
            if (!powered && inside.isEmpty()) continue;
            ServerPlayer intruder = inside.stream().filter(p -> !install.keyholders.containsKey(p.getUUID())).findFirst().orElse(null);
            install.lastTrip = now;
            state.setDirty();
            if (intruder == null) continue;   // a keyholder walking their own hall
            tripped(level, install, intruder, powered);
        }
    }

    private static boolean poweredLaser(BlockState state) { return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED); }

    private static boolean loadedNow(ServerLevel level, BlockPos pos) { return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null; }

    /** Somebody who does not belong crossed the laser: Core rings the building's alarm (its dispatch) and the SecurityCraft siren with it. Visible for the GameTests. */
    static void tripped(ServerLevel level, Install install, ServerPlayer intruder, boolean bySignal) {
        install.trips++;
        install.sirenUntil = level.getGameTime() + SIREN_TICKS;
        state(level.getServer()).setDirty();
        for (BlockPos alarm : install.alarms) sound(level, alarm, true);
        CityBuildings.INSTANCE.at(level, install.door).ifPresent(building -> PoliceResponse.raise(intruder, level, building, install.door, true, false, false));
        LOGGER.info("[UC] laser at {} crossed by {} ({}); alarm raised", install.lasers.get(0).toShortString(), intruder.getGameProfile().getName(), bySignal ? "SecurityCraft signal" : "seen in the field");
    }

    // ------------------------------------------------------------------------------------------ keeping SecurityCraft's own authority out
    /** No player places a SecurityCraft block: devices come from Core. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void place(BlockEvent.EntityPlaceEvent event) {
        if (!loaded() || !(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer || player.isCreative()) return;
        if (!isSecurityCraft(event.getPlacedBlock().getBlock())) return;
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal("Security devices are fitted by the city, not by hand.").withStyle(ChatFormatting.GRAY));
    }

    /** No player uses a SecurityCraft item other than the camera monitor, the manual and keycards (no sentry, mine, taser, codebreaker, owner changer, key panel or reinforcer). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void useItem(PlayerInteractEvent.RightClickItem event) {
        if (forbidden(event.getEntity(), event.getItemStack())) { event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL); refuse(event.getEntity()); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (forbidden(event.getEntity(), event.getItemStack())) { event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL); refuse(event.getEntity()); }
    }

    static boolean forbidden(Player player, ItemStack stack) {
        if (!loaded() || player.level().isClientSide() || player.isCreative() || player instanceof FakePlayer || stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals(MOD) && !USABLE.contains(id.getPath());
    }

    private static void refuse(Player player) {
        if (player instanceof ServerPlayer p) p.sendSystemMessage(Component.literal("That is not something you can use here.").withStyle(ChatFormatting.GRAY));
    }

    /** No SecurityCraft creature or projectile (sentry, its bullets, mines, the I.M.S., security boats) enters the world; the camera's own view entity is allowed. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !loaded()) return;
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        if (type.getNamespace().equals(MOD) && !type.getPath().contains("camera")) { event.setCanceled(true); LOGGER.info("[UC] SecurityCraft entity {} kept out of the world", type); }
    }

    /** Removes every SecurityCraft recipe (devices come only from Core while the trial runs) and tells the clients. Returns how many were removed. */
    static int removeRecipes(MinecraftServer server) {
        var manager = server.getRecipeManager();
        List<Recipe<?>> keep = new ArrayList<>();
        int removed = 0;
        for (Recipe<?> recipe : manager.getRecipes()) {
            if (recipe.getId().getNamespace().equals(MOD)) removed++;
            else keep.add(recipe);
        }
        if (removed == 0) return 0;
        manager.replaceRecipes(keep);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(manager.getRecipes()));
        LOGGER.info("[UC] SecurityCraft trial: {} SecurityCraft recipes removed; devices come only from Core", removed);
        return removed;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) { if (loaded()) removeRecipes(event.getServer()); }

    /** A datapack reload brings the recipes back; take them out again. */
    @SubscribeEvent
    public static void reloaded(TagsUpdatedEvent event) {
        if (loaded() && event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD && net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null) {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            server.execute(() -> removeRecipes(server));
        }
    }

    /** One line for logs and the scripted run. */
    static String status(MinecraftServer server) {
        StringBuilder out = new StringBuilder("SecurityCraft trial: " + state(server).installs.size() + " install(s)");
        for (Install i : state(server).installs.values()) out.append("; door ").append(i.door.toShortString()).append(" code entries ").append(i.codeEntries)
            .append(", keyholder entries ").append(i.keyholderEntries).append(", laser trips ").append(i.trips).append(", keyholders ").append(i.keyholders.values());
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------ operators
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_security").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("install").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            HitResult hit = player.pick(6.0, 0, false);
            if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) { ctx.getSource().sendFailure(Component.literal("Look at a city building's street door.")); return 0; }
            try {
                Install install = install(player.serverLevel(), block.getBlockPos());
                ctx.getSource().sendSuccess(() -> Component.literal("Installed: keypad door code " + install.code + ", " + install.lasers.size() / 2 + " laser pair, " + install.alarms.size()
                    + " siren, " + install.cameras.size() + " camera, " + install.lights.size() + " light."), true);
                return 1;
            } catch (IllegalStateException refused) {
                ctx.getSource().sendFailure(Component.literal(refused.getMessage()));
                return 0;
            }
        }));
        root.then(Commands.literal("keyholder").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
            ServerPlayer holder = EntityArgument.getPlayer(ctx, "player");
            ServerLevel level = ctx.getSource().getLevel();
            Install nearest = null;
            for (Install i : state(ctx.getSource().getServer()).installs.values()) if (nearest == null || i.door.distSqr(holder.blockPosition()) < nearest.door.distSqr(holder.blockPosition())) nearest = i;
            if (nearest == null) { ctx.getSource().sendFailure(Component.literal("Nothing is installed.")); return 0; }
            nearest.keyholders.put(holder.getUUID(), holder.getGameProfile().getName());
            for (BlockPos camera : nearest.cameras) if (loadedNow(level, camera)) rewriteAllowlist(level, camera, nearest);
            state(ctx.getSource().getServer()).setDirty();
            Install chosen = nearest;
            ctx.getSource().sendSuccess(() -> Component.literal(holder.getGameProfile().getName() + " now holds the key to the door at " + chosen.door.toShortString() + " (code " + chosen.code + ")."), true);
            return 1;
        })));
        root.then(Commands.literal("status").executes(ctx -> {
            State state = state(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal("SecurityCraft " + (loaded() ? "is loaded" : "is not loaded") + "; " + state.installs.size() + " install(s)."), false);
            for (Install i : state.installs.values()) ctx.getSource().sendSuccess(() -> Component.literal(" door " + i.door.toShortString() + " in " + i.city + ": code " + i.code
                + ", keyholders " + i.keyholders.values() + ", code entries " + i.codeEntries + ", keyholder entries " + i.keyholderEntries + ", laser trips " + i.trips), false);
            return state.installs.size();
        }));
        event.getDispatcher().register(root);
    }
}
