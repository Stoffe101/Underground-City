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
 * near, flees if it is struck, never opens a door (so it can never walk into a building that is locked) and is never saved: when nobody is near it is removed and the roster, which is
 * arithmetic, decides afresh who is out in the street the next time somebody comes. Who it is (name, face, trade) comes from the roster; this class only carries the identity, so that
 * speaking to the same person twice is speaking to the same person.
 */
public final class TownspersonEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(TownspersonEntity.class, EntityDataSerializers.INT);
    private long person, homeKey;
    private String city = "", role = "resident";
    /** An errand: where to go and how fast, and the game time at which the body leaves (set by {@link PoliceResponse} for an officer and by {@link HouseholdService} for a commuter; none of it is saved). */
    private BlockPos errand;
    private double errandSpeed = 1.0;
    private long leaveAt = Long.MAX_VALUE;

    public TownspersonEntity(EntityType<? extends TownspersonEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation ground) { ground.setCanOpenDoors(false); ground.setCanPassDoors(false); ground.setCanFloat(true); }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.24).add(Attributes.FOLLOW_RANGE, 16.0);
    }

    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(SKIN, 0); }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.35) { @Override public boolean canUse() { return !police() && super.canUse(); } });
        goalSelector.addGoal(2, new ErrandGoal());
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.55, 0.002f) { @Override public boolean canUse() { return !police() && errand == null && super.canUse(); } });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** Gives this body the identity of a person and a leash round {@code anchor}: guards stay near their post, everybody else wanders a little farther. */
    void become(PopulationPlan.Person who, BlockPos anchor) {
        person = who.id();
        city = who.city();
        role = who.role();
        entityData.set(SKIN, who.skin());
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

    /** True once an officer has been told to leave. */
    boolean leaving() { return leaveAt != Long.MAX_VALUE; }
    BlockPos errandPlace() { return errand; }

    /** Sends an officer (or a commuter) to a place at a speed (a multiple of their walking speed); null stands them still. */
    void errand(BlockPos where, double speed) { errand = where; errandSpeed = speed; if (where == null) getNavigation().stop(); }

    /** An officer (or a commuter) leaves (is removed) this many ticks from now; nobody is left standing in a street for good. */
    void leaveIn(long ticks) { leaveAt = level().getGameTime() + ticks; }

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && leaving() && level().getGameTime() >= leaveAt) discard();
    }

    /** Walks to the errand's place while there is one; stands and watches when there is none. */
    private final class ErrandGoal extends Goal {
        private int repath;
        ErrandGoal() { setFlags(java.util.EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return errand != null; }
        @Override public boolean canContinueToUse() { return errand != null; }
        @Override public void tick() {
            if (errand == null) return;
            if (--repath > 0 && !getNavigation().isDone()) return;
            repath = 10;
            if (distanceToSqr(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5) > 2.5 * 2.5) getNavigation().moveTo(errand.getX() + 0.5, errand.getY(), errand.getZ() + 0.5, errandSpeed);
        }
    }

    public long person() { return person; }
    public int skin() { return entityData.get(SKIN); }
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
