package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Posted on the Forge event bus after a citizen pays lockpicks to force a household safe. Future
 * witness, evidence and police systems listen here; nothing consumes it yet.
 */
public final class PropertyForcedEntryEvent extends Event {
    private final UUID player;
    private final BlockPos pos;
    private final String policy;
    private final int vigilance;
    private final long activeTick;

    public PropertyForcedEntryEvent(UUID player, BlockPos pos, String policy, int vigilance, long activeTick) {
        this.player = player; this.pos = pos; this.policy = policy; this.vigilance = vigilance; this.activeTick = activeTick;
    }
    public UUID player() { return player; }
    public BlockPos pos() { return pos; }
    public String policy() { return policy; }
    public int vigilance() { return vigilance; }
    public long activeTick() { return activeTick; }
}
