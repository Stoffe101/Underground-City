package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.UUID;

/** All actions execute on the server thread, with bounded shared employer demand. */
public final class WorkOrderService {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Authorized, rate-limited, journaled terminal operation; always refreshes the terminal. */
    interface Operation { void run(WorkOrderLedger ledger, CitizenState.Citizen citizen); }

    static void guarded(ServerPlayer player, BlockPos pos, Operation operation) {
        if (!TerminalNetwork.requestSlot(player)) return;
        if (!TerminalNetwork.authorizedForStarterShop(player, pos)) {
            player.sendSystemMessage(Component.literal("Register and use a nearby placed Operations Terminal."));
            return;
        }
        try {
            if (!QShopEconomy.available()) throw new IllegalStateException("Install Q Shop 1.8.4 to enable paid work orders");
            if (!QShopEconomy.configured(player.server)) throw new IllegalStateException("Q Shop credits configuration is missing");
            WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
            if (ledger.player(player.getUUID()).contains("pending")) recover(player);
            CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
            operation.run(ledger, citizen);
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal(error.getMessage() == null ? "Work-order transaction failed" : error.getMessage()));
            if (WorkOrderLedger.get(player.server).faulted()
                    || WorkOrderLedger.get(player.server).player(player.getUUID()).contains("pending")) {
                LOGGER.error("Work-order recovery required for {}", player.getUUID(), error);
                player.connection.disconnect(Component.literal("Work-order save interrupted. Reconnect for recovery; restart server if storage failed."));
                return;
            }
        }
        TerminalNetwork.open(player, pos);
    }

    public static void action(ServerPlayer player, BlockPos pos, int action, String id, UUID nonce) {
        guarded(player, pos, (ledger, citizen) -> {
            switch (action) {
                case 0 -> accept(player, ledger, citizen, id);
                case 1 -> complete(player, ledger, nonce, pos);
                case 2 -> {
                    ledger.cancel(player.getUUID(), nonce);
                    player.sendSystemMessage(Component.literal("Order cancelled. Employer demand returned."));
                }
                default -> throw new IllegalStateException("Unknown work-order action");
            }
        });
    }

    /** Career, level and rival-city visit gates for accepting a catalog order. */
    static void accept(ServerPlayer player, WorkOrderLedger ledger, CitizenState.Citizen citizen, String id) {
        WorkOrder order = WorkOrderCatalog.INSTANCE.find(id);
        if (order == null || !order.career().equals(citizen.career)) {
            throw new IllegalStateException("This order belongs to another profession");
        }
        if (citizen.careerLevel() < order.level()) throw new IllegalStateException("Career level " + order.level() + " required");
        int visits = ledger.foreignVisits(player.getUUID(), citizen.city);
        if (visits < order.foreignVisits()) {
            int more = order.foreignVisits() - visits;
            throw new IllegalStateException("Use a terminal inside " + more + " more rival " + (more == 1 ? "city" : "cities") + " first");
        }
        WorkOrder.Terms terms = order.terms();
        if (!terms.requiresSkill().isEmpty() && !SkillService.has(citizen, terms.requiresSkill())) {
            SkillNode needed = SkillCatalog.INSTANCE.find(terms.requiresSkill());
            throw new IllegalStateException("Requires the skill " + (needed == null ? terms.requiresSkill() : needed.title()));
        }
        if (terms.minTrust() > 0) {
            int trust = ledger.trust(player.getUUID(), terms.buyer());
            BuyerCatalog.Buyer buyer = BuyerCatalog.INSTANCE.find(terms.buyer());
            if (trust < terms.minTrust()) {
                throw new IllegalStateException((buyer == null ? terms.buyer() : buyer.title()) + " does not trust you with this yet (" + trust + "/" + terms.minTrust() + "). Finish smaller contracts for them first.");
            }
        }
        ledger.accept(player.getUUID(), order);
        player.sendSystemMessage(Component.literal("Accepted: " + order.title() + ". Deliver " + describe(order) + deliveryNote(order) + "."));
    }

    private static String describe(WorkOrder order) {
        return order.requirements().stream().map(r -> r.count() + " " + r.display()).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String deliveryNote(WorkOrder order) {
        BuyerCatalog.Buyer buyer = order.terms().buyer().isEmpty() ? null : BuyerCatalog.INSTANCE.find(order.terms().buyer());
        return buyer == null ? "" : " to " + buyer.title() + " in " + TerminalScreenText.capitalize(buyer.city());
    }

    static void complete(ServerPlayer player, UUID nonce) { complete(player, WorkOrderLedger.get(player.server), nonce, null); }

    static void complete(ServerPlayer player, WorkOrderLedger ledger, UUID nonce) { complete(player, ledger, nonce, null); }

    /**
     * Delivers the accepted order. {@code terminal} is the placed terminal the request came from; an order with a
     * buyer can only be delivered at a terminal inside the buyer's city, so the goods really have to travel (and
     * pass any checkpoint on the way).
     */
    static void complete(ServerPlayer player, WorkOrderLedger ledger, UUID nonce, BlockPos terminal) {
        WorkOrder order = ledger.checkActive(player.getUUID(), nonce);
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
        if (!order.career().equals(citizen.career)) throw new IllegalStateException("Profession changed");
        WorkOrder.Terms terms = order.terms();
        BuyerCatalog.Buyer buyer = null;
        if (!terms.buyer().isEmpty()) {
            buyer = BuyerCatalog.INSTANCE.find(terms.buyer());
            if (buyer == null) throw new IllegalStateException("This buyer no longer exists; cancel the order");
            String here = terminal == null ? "" : CityRegions.INSTANCE.cityAt(player.level(), terminal).orElse("");
            if (!here.equals(buyer.city())) {
                throw new IllegalStateException("Deliver to " + buyer.title() + " in " + TerminalScreenText.capitalize(buyer.city()) + ": use a terminal inside that city.");
            }
        }
        // Plan removal on a COPY; incomplete deliveries leave the live inventory untouched.
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (WorkOrder.Requirement requirement : order.requirements()) {
            int needed = requirement.count();
            for (int slot = 0; slot < after.items.size() && needed > 0; slot++) {
                ItemStack stack = after.items.get(slot);
                if (order.matches(requirement, stack)) {
                    int take = Math.min(needed, stack.getCount());
                    stack.shrink(take); needed -= take;
                }
            }
            if (needed > 0) missing.add(needed + " plain " + requirement.item());
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Missing " + String.join(", ", missing) + ". Named/enchanted goods are protected.");
        // Reward items are planned on the same copy, so a full inventory refuses the delivery before anything changes.
        for (WorkOrder.Reward reward : terms.rewards()) {
            ItemStack stack = reward.stack();
            after.add(stack);
            if (!stack.isEmpty()) throw new IllegalStateException("Not enough inventory space for the reward items. Free some slots and deliver again.");
        }
        double target = QShopEconomy.balance(player) + order.credits();
        if (target > 1_000_000_000) throw new IllegalStateException("Wallet limit reached");
        int xpTarget = ProgressionRules.totalXp(citizen.careerXp(), order.xp());
        java.util.Map<String, Integer> trustAfter = java.util.Map.of(), heatAfter = java.util.Map.of();
        boolean witnessed = false;
        if (buyer != null) {
            if (terms.trustGain() > 0) trustAfter = java.util.Map.of(buyer.id(), Math.min(BuyerCatalog.MAX_TRUST, ledger.trust(player.getUUID(), buyer.id()) + terms.trustGain()));
            CityLaws.Law law = CityLaws.INSTANCE.find(buyer.city());
            if (order.underground() && law != null) {
                // One roll per accepted order (the nonce is consumed by this settlement), so a retry cannot re-roll it.
                double chance = LawMath.witnessChance(law, ledger.heat(player.getUUID(), buyer.city()), SkillService.inspectionModifier(citizen));
                witnessed = LawMath.roll(chance, LawMath.mix(nonce.getMostSignificantBits(), nonce.getLeastSignificantBits(), 0x5717E55L));
                if (witnessed) heatAfter = java.util.Map.of(buyer.city(), ledger.heatAfter(player.getUUID(), buyer.city(), 1));
            }
        }
        ledger.prepare(player.getUUID(), nonce, after.save(new ListTag()), target, xpTarget, trustAfter, heatAfter);
        recover(player, ledger);
        player.sendSystemMessage(Component.literal("Delivered " + order.title() + ": +" + order.credits()
            + " credits, +" + order.xp() + " career XP. Level " + citizen.careerLevel() + "."));
        if (!terms.rewards().isEmpty()) {
            player.sendSystemMessage(Component.literal("Also received: " + terms.rewards().stream().map(r -> r.count() + " x "
                + net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(r.item()).getDescription().getString()).collect(java.util.stream.Collectors.joining(", ")) + "."));
        }
        if (buyer != null && terms.trustGain() > 0) player.sendSystemMessage(Component.literal(buyer.title() + " trusts you a little more (" + ledger.trust(player.getUUID(), buyer.id()) + "/" + BuyerCatalog.MAX_TRUST + ")."));
        if (witnessed) player.sendSystemMessage(Component.literal("Someone noticed the hand-off. The " + TerminalScreenText.capitalize(buyer.city()) + " guard will search you more closely for a while."));
    }

    /** Prepared absolute targets are replayed BEFORE any post-login interaction. */
    public static void recover(ServerPlayer player) { recover(player, WorkOrderLedger.get(player.server)); }

    static void recover(ServerPlayer player, WorkOrderLedger ledger) {
        CompoundTag record = ledger.player(player.getUUID());
        CitizenState citizens = CitizenState.get(player.server);
        if (citizens.find(player.getUUID()).isEmpty()) {
            if (record.contains("pending")) throw new IllegalStateException("Missing citizen for pending order");
            return;
        }
        citizens.restoreCareerXp(player.getUUID(), record.getInt("earned_xp_target"));
        if (!record.contains("pending")) return;
        CompoundTag pending = record.getCompound("pending");
        if (pending.contains("balance")) QShopEconomy.applyBalance(player, pending.getDouble("balance"));
        player.getInventory().load(pending.getList("inventory", Tag.TAG_COMPOUND));
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        citizens.restoreCareerXp(player.getUUID(), pending.getInt("xp"));
        player.server.getPlayerList().saveAll();
        // Vanilla logs player-save I/O failures instead of throwing them. Verify
        // persisted inventory/capabilities before retiring the recovery record.
        if (!(player instanceof FakePlayer)) {
            try {
                CompoundTag expected = player.saveWithoutId(new CompoundTag());
                java.io.File file = player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                    .resolve(player.getUUID() + ".dat").toFile();
                CompoundTag saved = net.minecraft.nbt.NbtIo.readCompressed(file);
                if (!saved.getList("Inventory", Tag.TAG_COMPOUND).equals(expected.getList("Inventory", Tag.TAG_COMPOUND))
                    || !saved.getCompound("ForgeCaps").equals(expected.getCompound("ForgeCaps"))) {
                    throw new IllegalStateException("Player inventory/wallet save verification failed");
                }
                try (var channel = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)) {
                    channel.force(true);
                }
                WorkOrderLedger.forceDirectory(file.toPath().getParent());
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Player data was not durably saved", error);
            }
        }
        player.server.overworld().getDataStorage().save();
        ledger.settle(player.getUUID());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.getServer().getPlayerList().getPlayers().stream().noneMatch(p -> !(p instanceof FakePlayer))) return;
        WorkOrderLedger ledger = WorkOrderLedger.get(event.getServer());
        if (!ledger.faulted()) {
            ledger.advance(1);
            if (ledger.clock() % 1200 == 0) ledger.persist();
        }
    }

    @SubscribeEvent
    public void stop(ServerStoppingEvent event) {
        WorkOrderLedger ledger = WorkOrderLedger.get(event.getServer());
        if (!ledger.faulted()) ledger.persist();
    }
}
