package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Posted on the Forge event bus when a lock of a city building is dealt with: a door picked open, or a pick snapped in it ({@code alarm} says the snapped lock rang a bell). Witnesses, alarms,
 * police and evidence systems listen here: {@link LockService}'s own heat rule, and {@link PoliceResponse}, which decides whether anybody noticed and, if so, sends officers (D-122).
 */
public final class BuildingBreachEvent extends Event {
    public enum Outcome { OPENED, SNAPPED }

    private final UUID player;
    private final BlockPos door;
    private final String city;
    private final String securityClass;
    private final Outcome outcome;
    private final long activeTick;
    private final boolean alarm;
    private boolean quiet;
    private final net.minecraft.server.level.ServerPlayer actor;

    public BuildingBreachEvent(UUID player, BlockPos door, String city, String securityClass, Outcome outcome, long activeTick) {
        this(player, door, city, securityClass, outcome, activeTick, false);
    }

    public BuildingBreachEvent(UUID player, BlockPos door, String city, String securityClass, Outcome outcome, long activeTick, boolean alarm) {
        this(player, door, city, securityClass, outcome, activeTick, alarm, null);
    }

    /** {@code actor} is the player's live object when the poster has it (tests that act through a fake player have no other way to be found); null otherwise. */
    public BuildingBreachEvent(UUID player, BlockPos door, String city, String securityClass, Outcome outcome, long activeTick, boolean alarm, net.minecraft.server.level.ServerPlayer actor) {
        this.player = player; this.door = door; this.city = city; this.securityClass = securityClass; this.outcome = outcome; this.activeTick = activeTick; this.alarm = alarm; this.actor = actor;
    }
    public UUID player() { return player; }
    public BlockPos door() { return door; }
    public String city() { return city; }
    public String securityClass() { return securityClass; }
    public Outcome outcome() { return outcome; }
    public long activeTick() { return activeTick; }
    /** True when a snapped pick rang the lock's own alarm bell (an opened door never carries this: a building's armed alarm is decided by {@link PoliceResponse}). */
    public boolean alarm() { return alarm; }
    public net.minecraft.server.level.ServerPlayer actor() { return actor; }

    /** Marks a door picked cleanly (the pick never came under much strain): a quiet entry does not trip a building's armed alarm, except where the alarm is too good to be bypassed ({@link PoliceResponse#QUIET_BYPASS_MAX_CHANCE}). */
    public BuildingBreachEvent quiet(boolean value) { quiet = value; return this; }
    public boolean quiet() { return quiet; }
}
