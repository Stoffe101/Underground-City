package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * State of one secured terminal: how many recoverable ledgers it holds, when it refills and when it is locked out.
 * Everything is counted in the shared active-play clock (never wall time), so absent players neither lose nor gain.
 */
public final class SecureTerminalBlockEntity extends BlockEntity {
    /** One ledger returns every 45 active minutes, up to a small stock. */
    public static final long REFILL_TICKS = 45 * 1200L;
    public static final int CAPACITY = 2;
    /** A tripped alarm locks the terminal for three active minutes. */
    public static final long LOCKOUT_TICKS = 3 * 1200L;
    private int ledgers = 1;
    /** Active-clock time the refill interval counts from; -1 until the terminal is first looked at. */
    private long lastTick = -1;
    private long lockedUntil = -1;
    private int attempts;

    public SecureTerminalBlockEntity(BlockPos pos, BlockState state) { super(UndergroundCity.SECURE_TERMINAL_ENTITY.get(), pos, state); }

    /** Lazily applies elapsed active time: one ledger per interval, no hidden backlog while full. */
    public int ledgers(long now) {
        if (lastTick < 0) { lastTick = now; setChanged(); } // a new terminal starts its first interval when first used
        if (ledgers >= CAPACITY) { lastTick = now; return ledgers; }
        long intervals = Math.max(0, (now - lastTick) / REFILL_TICKS);
        if (intervals > 0) {
            ledgers = (int) Math.min(CAPACITY, ledgers + intervals);
            lastTick = ledgers >= CAPACITY ? now : lastTick + intervals * REFILL_TICKS;
            setChanged();
        }
        return ledgers;
    }

    public void take(long now) {
        if (ledgers(now) < 1) throw new IllegalStateException("The drive is empty");
        if (ledgers >= CAPACITY) lastTick = now; // the refill clock starts when the stock first drops below capacity
        ledgers--;
        setChanged();
    }

    public boolean lockedAt(long now) { return now < lockedUntil; }
    public void lock(long now) { lockedUntil = now + LOCKOUT_TICKS; setChanged(); }
    public int nextAttempt() { setChanged(); return ++attempts; }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("schema_version", 1); tag.putInt("ledgers", ledgers); tag.putLong("last", lastTick);
        tag.putLong("locked_until", lockedUntil); tag.putInt("attempts", attempts);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        ledgers = Math.max(0, Math.min(CAPACITY, tag.contains("ledgers") ? tag.getInt("ledgers") : 1));
        lastTick = tag.contains("last") ? tag.getLong("last") : -1; lockedUntil = tag.contains("locked_until") ? tag.getLong("locked_until") : -1;
        attempts = Math.max(0, tag.getInt("attempts"));
    }
}
