package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps each player's screen informed about the work order they have accepted: what it is called, what is still missing and where it is
 * delivered. The server computes the progress from the player's real inventory and the order's accepted terms (the same matching the
 * delivery uses), and sends a small message only when something changed, so the client never needs to know the datapack or the rules.
 */
public final class OrderTracker {
    static final int MAX_LINES = 6;
    private static final Map<UUID, Tracked> SENT = new HashMap<>();

    /** One requirement: the item (for its name and icon), an optional label, how many the player carries and how many are needed. */
    public record Line(String item, String label, int held, int count) {}

    /** What the screen shows for an accepted order; {@code deliverIn} is the buyer's name and city, or empty for the open exchange. */
    public record Tracked(String title, int credits, int xp, String deliverIn, boolean underground, List<Line> lines) {
        public Tracked { lines = List.copyOf(lines); }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 7) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            Tracked now = compute(player);
            if (Objects.equals(now, SENT.get(player.getUUID()))) continue;
            if (now == null) SENT.remove(player.getUUID()); else SENT.put(player.getUUID(), now);
            TerminalNetwork.sendTracked(player, now);
        }
    }

    @SubscribeEvent public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) { SENT.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { SENT.remove(event.getEntity().getUUID()); }

    /** The accepted order of {@code player} with live progress, or null when they have none (or its terms can no longer be loaded). */
    static Tracked compute(ServerPlayer player) {
        if (CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return null;
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        if (ledger.faulted()) return null;
        CompoundTag record = ledger.player(player.getUUID());
        if (!record.hasUUID("order")) return null;
        WorkOrder order;
        try { order = WorkOrder.load(record.getCompound("job")); }
        catch (IllegalArgumentException removedItem) { return null; }
        List<Line> lines = new ArrayList<>();
        for (WorkOrder.Requirement requirement : order.requirements()) {
            if (lines.size() >= MAX_LINES) break;
            int held = 0;
            for (ItemStack stack : player.getInventory().items) if (!stack.isEmpty() && order.matches(requirement, stack)) held += stack.getCount();
            lines.add(new Line(requirement.item().toString(), requirement.label(), held, requirement.count()));
        }
        BuyerCatalog.Buyer buyer = order.terms().buyer().isEmpty() ? null : BuyerCatalog.INSTANCE.find(order.terms().buyer());
        String deliverIn = buyer == null ? "" : buyer.title() + ", " + TerminalScreenText.capitalize(buyer.city());
        return new Tracked(order.title(), order.credits(), order.xp(), deliverIn, order.underground(), lines);
    }
}
