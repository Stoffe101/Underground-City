package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Posted on the Forge event bus when a lock of a city building is dealt with: a door picked open, or a pick snapped in it. Witnesses, alarms, police and evidence systems listen here;
 * today the only listener is {@link LockService}'s own heat rule, so <b>no police or witness responds to a break-in yet</b> and the documents say so.
 */
public final class BuildingBreachEvent extends Event {
    public enum Outcome { OPENED, SNAPPED }

    private final UUID player;
    private final BlockPos door;
    private final String city;
    private final String securityClass;
    private final Outcome outcome;
    private final long activeTick;

    public BuildingBreachEvent(UUID player, BlockPos door, String city, String securityClass, Outcome outcome, long activeTick) {
        this.player = player; this.door = door; this.city = city; this.securityClass = securityClass; this.outcome = outcome; this.activeTick = activeTick;
    }
    public UUID player() { return player; }
    public BlockPos door() { return door; }
    public String city() { return city; }
    public String securityClass() { return securityClass; }
    public Outcome outcome() { return outcome; }
    public long activeTick() { return activeTick; }
}
