package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * One of a city's ambient people (D-108), or, for the minutes after a noticed break-in, a police officer sent by {@link PoliceResponse} (D-122; role "police"): a body for a {@link PopulationPlan.Person}. It is deliberately small. It walks a short leash around where it was put, looks at players who come
 * near, flees if it is struck, never opens a door (so it can never walk into a building that is locked; an officer does, D-189) and is never saved: when nobody is near it is removed and the roster, which is
 * arithmetic, decides afresh who is out in the street the next time somebody comes. Who it is (name, face, trade) comes from the roster; this class only carries the identity, so that
 * speaking to the same person twice is speaking to the same person.
 */
public final class TownspersonEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(TownspersonEntity.class, EntityDataSerializers.INT);
    /** A person's build (0 to 9): drawn a little shorter or taller than the model (D-162), so a crowd is not one height. */
    private static final EntityDataAccessor<Integer> BUILD = SynchedEntityData.defineId(TownspersonEntity.class, EntityDataSerializers.INT);
    private long person, homeKey;
    private String city = "", role = "resident";
    /** An errand: where to go and how fast, and the game time at which the body leaves (set by {@link PoliceResponse} for an officer and by {@link HouseholdService} for a commuter; none of it is saved). */
    private BlockPos errand;
    private double errandSpeed = 1.0;
    private long leaveAt = Long.MAX_VALUE;
    /** Standing still on purpose (talking, looking in a shop window, browsing): no wandering off. */
    private boolean holding;

    public TownspersonEntity(EntityType<? extends TownspersonEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation ground) { ground.setCanOpenDoors(false); ground.setCanPassDoors(false); ground.setCanFloat(true); }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.24).add(Attributes.FOLLOW_RANGE, 16.0);
    }

    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(SKIN, 0); entityData.define(BUILD, 5); }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.35) { @Override public boolean canUse() { return !police() && super.canUse(); } });
        goalSelector.addGoal(2, new StepGoal());
        goalSelector.addGoal(2, new ErrandGoal());
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.55, 0.002f) { @Override public boolean canUse() { return !police() && errand == null && !holding && super.canUse(); } });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** Gives this body the identity of a person and a leash round {@code anchor}: guards stay near their post, everybody else wanders a little farther. */
    void become(PopulationPlan.Person who, BlockPos anchor) {
        person = who.id();
        city = who.city();
        role = who.role();
        entityData.set(SKIN, who.skin());
        entityData.set(BUILD, (int) Math.floorMod(PopulationPlan.mix(who.id() ^ 0xB01DL), 10));
        setCustomName(Component.literal(who.name()));
        setCustomNameVisible(false);   // the name shows when the crosshair rests on them, as for any named creature
        restrictTo(anchor, role.equals("guard") ? 3 : 14);
    }

    /** Makes this body a police officer of {@code city}: a guard's outfit, a rank and a surname, quicker on their feet than a stroller and with no leash. */
    void becomeOfficer(String city, String name, int skin) {
        this.city = city; role = "police"; person = 0;
        entityData.set(SKIN, skin);
        String surname = name.contains(" ") ? name.substring(name.lastIndexOf(' ') + 1) : name;
        setCustomName(Component.literal("Officer " + surname));
        setCustomNameVisible(true);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.3);
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(48.0);
        clearRestriction();
        // an officer goes through doors (D-189): a thief who stayed inside was never caught. Their paths may cross a closed door of any kind, and they open it as they reach it
        if (getNavigation() instanceof GroundPathNavigation ground) { ground.setCanOpenDoors(true); ground.setCanPassDoors(true); }
        setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.DOOR_IRON_CLOSED, 0f);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.DOOR_WOOD_CLOSED, 0f);
    }

    /**
     * An officer, or a customer on the way to a player's counter, opens the closed doors on the next steps of their path as they reach them, whatever the door (a wooden one, an iron street door), and marks each as opened on purpose for a
     * few seconds so the building's door watch does not shut it in their face; the watch shuts it again afterwards. Nobody else's doors are touched.
     */
    private void openDoorsAhead() {
        var path = getNavigation().getPath();
        if (path == null || path.isDone()) return;
        for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2); i++) {
            BlockPos node = path.getNodePos(i);
            for (BlockPos cell : new BlockPos[]{node, node.above()}) {
                var state = level().getBlockState(cell);
                if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door) || state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) continue;
                if (distanceToSqr(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5) > 2.6 * 2.6) continue;
                BlockPos lower = state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? cell.below() : cell;
                var server = level().getServer();
                if (server != null) PropertyLocks.get(server).exitOpen(lower, LockService.clock(server) + 80);
                door.setOpen(this, level(), level().getBlockState(lower), lower, true);
                return;
            }
        }
    }

    /**
     * Makes this body a resident or a worker of a furnished building on the way out of it or home to it ({@link HouseholdService}): no leash, an errand to {@code to}, and gone a minute and a half from
     * now whatever happens (nobody is left standing in a street for good).
     */
    void becomeCommuter(PopulationPlan.Person who, BlockPos to, long building) {
        become(who, to);
        homeKey = building;
        clearRestriction();
        errand(to, 0.8);
        leaveIn(1800);
    }

    /** Makes this body a resident or a worker of a furnished building ({@link HouseholdService}): it stays within two blocks of where it was put and remembers which building is its own. */
    void becomeResident(PopulationPlan.Person who, BlockPos anchor, long building) {
        become(who, anchor);
        homeKey = building;
        restrictTo(anchor, 2);
    }

    /** The key of the building this body lives or works in ({@link CityBuildings.Building#key}), or 0 for a street person or an officer. */
    long homeKey() { return homeKey; }

    boolean police() { return role.equals("police"); }

    /** True while this street person walks to a player's counter and back out (D-208): they go through the doors on their way, as an officer does. */
    private boolean passDoors;

    /** On for a customer of a player's business, off again once they are back on the street; an officer always passes doors. */
    void passDoors(boolean on) {
        if (passDoors == on || police()) return;
        passDoors = on;
        if (getNavigation() instanceof GroundPathNavigation ground) { ground.setCanOpenDoors(on); ground.setCanPassDoors(on); }
        setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.DOOR_WOOD_CLOSED, on ? 0f : -1f);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.DOOR_IRON_CLOSED, on ? 0f : -1f);
    }

    /** True once an officer has been told to leave. */
    boolean leaving() { return leaveAt != Long.MAX_VALUE; }
    BlockPos errandPlace() { return errand; }

    /** Sends an officer (or a commuter) to a place at a speed (a multiple of their walking speed); null stands them still. */
    void errand(BlockPos where, double speed) {
        if (where != null) stepping = null;
        boolean changed = where == null || !where.equals(errand);
        errand = where; errandSpeed = speed;
        if (changed && !police()) { getNavigation().stop(); freshErrand = true; }
        if (where == null) getNavigation().stop();
    }

    /** A new errand is walked at once, not after the last path runs out. */
    private boolean freshErrand;

    /** Stands still on purpose, or walks on again. */
    void hold(boolean on) { holding = on; if (on) { getNavigation().stop(); stepping = null; } }

    /**
     * Steps straight to {@code to} without a path (through a doorway the navigation will not plan through, since these people never open doors themselves): the door is opened for them by
     * {@link PopulationService} or {@link HouseholdService}.
     */
    void stepThrough(BlockPos to) {
        errand = null;
        holding = false;
        getNavigation().stop();
        if (!to.equals(stepping)) stepUntil = tickCount + 100;
        stepping = to;
        getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.6);
    }

    /** Where {@link #stepThrough} is taking this body, until it is there (or five seconds have passed); null when it is not stepping. */
    private BlockPos stepping;
    private int stepUntil;

    /**
     * Keeps a step going: a move control's wanted position lasts one tick, and a step was given only on the population's pass (one tick in ten), so a body stepping through a doorway moved a
     * tenth of the time and never got through (business run 8: an open door, a clear doorway, and the body half a block short after ten seconds).
     */
    private final class StepGoal extends Goal {
        StepGoal() { setFlags(java.util.EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return stepping != null; }
        @Override public boolean canContinueToUse() { return stepping != null; }
        @Override public boolean requiresUpdateEveryTick() { return true; }   // a goal is otherwise ticked every other tick, and a move lasts one
        @Override public void tick() {
            if (stepping == null) return;
            double dx = getX() - (stepping.getX() + 0.5), dz = getZ() - (stepping.getZ() + 0.5);
            if (dx * dx + dz * dz < 0.2 * 0.2 || tickCount > stepUntil) { stepping = null; return; }
            getMoveControl().setWantedPosition(stepping.getX() + 0.5, stepping.getY(), stepping.getZ() + 0.5, 0.6);
        }
    }

    /** An officer (or a commuter) leaves (is removed) this many ticks from now; nobody is left standing in a street for good. */
    void leaveIn(long ticks) { leaveAt = level().getGameTime() + ticks; }

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && leaving() && level().getGameTime() >= leaveAt) discard();
        if (!level().isClientSide && (police() || passDoors) && errand != null && tickCount % 2 == 0) openDoorsAhead();
    }

    /** Walks to the errand's place while there is one; stands and watches when there is none. */
    private final class ErrandGoal extends Goal {
        private int repath;
        ErrandGoal() { setFlags(java.util.EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return errand != null; }
        @Override public boolean canContinueToUse() { return errand != null; }
        @Override public void tick() {
            if (errand == null) return;
            if (police()) {
                // an officer chases somebody who moves: a fresh path twice a second
                if (--repath > 0 && !getNavigation().isDone()) return;
                repath = 10;
                if (distanceToSqr(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5) > 2.5 * 2.5) getNavigation().moveTo(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5, errandSpeed);
                return;
            }
            // anybody else: a path when the errand is new, then at most every two seconds once the last one is used up, so a body that cannot get somewhere stops asking (D-162)
            if (freshErrand) { freshErrand = false; repath = 0; }
            if (--repath > 0 || !getNavigation().isDone()) return;
            repath = 40;
            if (distanceToSqr(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5) > 1.8 * 1.8) getNavigation().moveTo(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5, errandSpeed);
        }
    }

    public long person() { return person; }
    public int skin() { return entityData.get(SKIN); }
    public int build() { return entityData.get(BUILD); }
    public String city() { return city; }
    public String role() { return role; }

    /** A person is never lost to a stray arrow or a fall; /kill and the void still work. The people of a city are not loot. */
    @Override public boolean isInvulnerableTo(DamageSource source) { return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source); }

    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean removeWhenFarAway(double distanceSquared) { return true; }
    @Override public boolean canBeLeashed(Player player) { return false; }
    @Override public boolean canPickUpLoot() { return false; }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer speaker) PopulationService.talk(speaker, this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); tag.putLong("person", person); tag.putString("city", city); tag.putString("role", role); }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        person = tag.getLong("person"); city = tag.getString("city"); role = tag.getString("role").isEmpty() ? "resident" : tag.getString("role");
    }
}
