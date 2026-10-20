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
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * One of a city's ambient people (D-108): a body for a {@link PopulationPlan.Person}. It is deliberately small. It walks a short leash around where it was put, looks at players who come
 * near, flees if it is struck, never opens a door (so it can never walk into a building that is locked) and is never saved: when nobody is near it is removed and the roster, which is
 * arithmetic, decides afresh who is out in the street the next time somebody comes. Who it is (name, face, trade) comes from the roster; this class only carries the identity, so that
 * speaking to the same person twice is speaking to the same person.
 */
public final class TownspersonEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(TownspersonEntity.class, EntityDataSerializers.INT);
    private long person;
    private String city = "", role = "resident";

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
        goalSelector.addGoal(1, new PanicGoal(this, 1.35));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.55, 0.002f));
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
