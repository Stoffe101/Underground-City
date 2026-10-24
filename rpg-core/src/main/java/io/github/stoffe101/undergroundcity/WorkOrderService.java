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
        if (!TerminalNetwork.authorizedForExchange(player, pos)) {
            player.sendSystemMessage(Component.literal(ShopCounters.INSTANCE.all().isEmpty() ? "Register and use a nearby placed Operations Terminal."
                : "Register and use a nearby placed Operations Terminal, or a shop counter while its shopkeeper is there."));
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
            var exchange = Exchanges.at(player.level(), pos);
            if (exchange.map(Exchanges.Exchange::shopOnly).orElse(false)) {
                // a wayside desk and a shop counter are shops, not offices
                throw new IllegalStateException(exchange.get().counter() ? "This is a shop counter. Orders are taken at an Operations Terminal." : "Orders are posted in the cities. This desk only sells supplies.");
            }
            switch (action) {
                case 0 -> accept(player, ledger, citizen, id);
                case 1 -> complete(player, ledger, nonce, pos);
                case 2 -> {
                    ledger.cancel(player.getUUID(), nonce);
                    player.sendSystemMessage(Component.literal("Order cancelled. Employer demand returned."));
                }
                case 3 -> CrewContracts.fillOrder(player, ledger, id);   // a supplier fills a Crew contract part (D-170)
                default -> throw new IllegalStateException("Unknown work-order action");
            }
        });
    }

    /** Career, level and rival-city visit gates for accepting a catalog order. */
    static void accept(ServerPlayer player, WorkOrderLedger ledger, CitizenState.Citizen citizen, String id) {
        WorkOrder order = WorkOrderCatalog.INSTANCE.find(id);
        if (order == null || !order.openTo(citizen.career)) {
            throw new IllegalStateException("This order belongs to another profession");
        }
        if (citizen.careerLevel() < order.level()) throw new IllegalStateException("Career level " + order.level() + " required");
        if (!order.offeredAt(citizen.careerLevel())) throw new IllegalStateException("This is an apprentice order (career level " + order.maxLevel() + " and below)");
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
        String crewRefusal = CrewContracts.refusal(player.server, ledger, player.getUUID(), terms.crewPart());
        if (crewRefusal != null) throw new IllegalStateException(crewRefusal);
        String civicRefusal = CivicProjects.refusal(ledger, terms);
        if (civicRefusal != null) throw new IllegalStateException(civicRefusal);
        String raidRefusal = Raids.refusal(player, terms);
        if (raidRefusal != null) throw new IllegalStateException(raidRefusal);
        if (!terms.requiresFacility().isEmpty() && !Premises.holds(player.server, player.getUUID(), terms.requiresFacility())) {
            throw new IllegalStateException("A bulk order: it needs trade premises you work in (a commercial workshop or a warehouse, rented or bought on the Home page).");
        }
        if (terms.minTrust() > 0) {
            int trust = GeneralSkills.effectiveTrust(citizen, ledger, player.getUUID(), terms.buyer());   // a legal buyer counts one step more for Social 3 (D-174)
            BuyerCatalog.Buyer buyer = BuyerCatalog.INSTANCE.find(terms.buyer());
            if (trust < terms.minTrust()) {
                throw new IllegalStateException((buyer == null ? terms.buyer() : buyer.title()) + " does not trust you with this yet (" + trust + "/" + terms.minTrust() + "). Finish smaller contracts for them first.");
            }
        }
        UUID nonce = ledger.accept(player.getUUID(), order);
        Raids.opened(player, ledger, order, nonce);   // a raid's job names its target (D-173)
        CitizenState.get(player.server).markJourney(player.getUUID(), "accepted");
        net.minecraft.network.chat.MutableComponent message = Component.literal("Accepted: " + order.title() + ". Deliver ");
        boolean first = true;
        for (WorkOrder.Requirement requirement : order.requirements()) {
            if (!first) message.append(", ");
            first = false;
            message.append(requirement.count() + " ");
            // The player's own language names the item; a labelled requirement (a specific gun) keeps its label.
            if (requirement.label().isEmpty() && requirement.registryItem() != null) message.append(Component.translatable(requirement.registryItem().getDescriptionId()));
            else message.append(requirement.display());
        }
        player.sendSystemMessage(message.append(deliveryNote(order) + "."));
        Notice.send(player, Notice.Kind.ORDER, "Order accepted", order.title() + deliveryNote(order));
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
        if (!order.openTo(citizen.career)) throw new IllegalStateException("Profession changed");
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
        double gradeWeight = 0;   // the price weight of every unit taken (Standard 1.0, Fine 1.25, Reserve 1.6; D-163)
        int units = 0;
        for (WorkOrder.Requirement requirement : order.requirements()) {
            int needed = requirement.count();
            for (int slot = 0; slot < after.items.size() && needed > 0; slot++) {
                ItemStack stack = after.items.get(slot);
                if (order.matches(requirement, stack)) {
                    int take = Math.min(needed, stack.getCount());
                    gradeWeight += take * Workshops.GRADE_PRICE[Workshops.grade(stack)]; units += take;
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
        final int skilled = SkillService.payout(citizen, order);   // the order's snapshot plus the career's contract-rate skills
        final int credits = gradedPayout(skilled, gradeWeight, units);   // and the grade of what was delivered, fixed here and journaled below
        double target = QShopEconomy.balance(player) + credits;
        if (target > 1_000_000_000) throw new IllegalStateException("Wallet limit reached");
        int xpTarget = ProgressionRules.totalXp(citizen.careerXp(), order.xp());
        final int levelBefore = citizen.careerLevel();
        java.util.Map<String, Integer> trustAfter = java.util.Map.of(), heatAfter = java.util.Map.of();
        final int trustBefore = buyer == null ? 0 : ledger.trust(player.getUUID(), buyer.id());
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
        // a Crew contract part (D-170): refused before anything is taken when the Crew already has it, else recorded against the Crew by this settlement
        String crewRefusal = CrewContracts.refusal(player.server, ledger, player.getUUID(), terms.crewPart());
        if (crewRefusal != null) throw new IllegalStateException(crewRefusal + " Cancel the order to free it.");
        CompoundTag crewRecord = CrewContracts.deliveryRecord(player.server, player.getUUID(), terms.crewPart());
        // a civic contribution (D-171): refused once the project is finished, else counted by this settlement
        String civicRefusal = CivicProjects.refusal(ledger, terms);
        if (civicRefusal != null) throw new IllegalStateException(civicRefusal + " Cancel the order to free it.");
        CivicProjects.deliveryRecord(terms, crewRecord);
        ledger.prepare(player.getUUID(), nonce, after.save(new ListTag()), target, xpTarget, trustAfter, heatAfter, crewRecord);
        recover(player, ledger);
        if (crewRecord.hasUUID("crew")) CrewContracts.afterSettlement(player.server, ledger, crewRecord.getUUID("crew"), CrewContracts.split(terms.crewPart())[0]);
        if (!terms.civic().isEmpty()) CivicProjects.afterSettlement(player.server, ledger, terms.civic());
        CrewService.sweepIncome(player, credits, nonce); // fully shared finances only; never changes the settlement above
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "deliveries");
        String extra = (skilled > order.credits() ? " + " + (skilled - order.credits()) + " for your skills" : "") + (credits > skilled ? " + " + (credits - skilled) + " for the grade" : "");
        player.sendSystemMessage(Component.literal("Delivered " + order.title() + ": +" + credits
            + " credits" + (extra.isEmpty() ? "" : " (" + order.credits() + extra + ")") + ", +" + order.xp() + " career XP. Level " + citizen.careerLevel() + "."));
        Notice.send(player, Notice.Kind.DELIVERED, "Delivered", order.title() + ": +" + credits + " credits, +" + order.xp() + " XP");
        if (citizen.careerLevel() > levelBefore) Notice.send(player, Notice.Kind.SKILL, "Level " + citizen.careerLevel(), TerminalScreenText.capitalize(citizen.career) + " level up. A skill point is waiting.");
        if (!terms.rewards().isEmpty()) {
            player.sendSystemMessage(Component.literal("Also received: " + terms.rewards().stream().map(r -> r.count() + " x "
                + net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(r.item()).getDescription().getString()).collect(java.util.stream.Collectors.joining(", ")) + "."));
        }
        if (buyer != null && terms.trustGain() > 0) Notice.send(player, Notice.Kind.TRUST, "Trust grows", buyer.title() + " trusts you a little more.");
        if (witnessed) Notice.send(player, Notice.Kind.HEAT, "Seen at the hand-off", "The " + TerminalScreenText.capitalize(buyer.city()) + " guard will search you more closely for a while.");
        if (buyer != null && terms.trustGain() > 0) player.sendSystemMessage(Component.literal(buyer.title() + " trusts you a little more (" + ledger.trust(player.getUUID(), buyer.id()) + "/" + BuyerCatalog.MAX_TRUST + ")."));
        if (witnessed) player.sendSystemMessage(Component.literal("Someone noticed the hand-off. The " + TerminalScreenText.capitalize(buyer.city()) + " guard will search you more closely for a while."));
        GeneralSkills.delivered(player, order, credits, buyer != null && ledger.trust(player.getUUID(), buyer.id()) > trustBefore);   // Trading and Social (D-174), after the settlement
    }

    /** The credits for a delivery whose {@code units} weighed {@code gradeWeight} in all: Standard goods pay the order's credits, Fine and Reserve goods pay more in proportion (D-163). */
    static int gradedPayout(int credits, double gradeWeight, int units) {
        if (units <= 0 || gradeWeight <= units) return credits;
        return (int) Math.round(credits * Math.min(Workshops.GRADE_PRICE[2], gradeWeight / units));
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
        // A seizure from a carried backpack: the pack's slots live in the mod's own storage, so the prepared record carries the changes and they are replayed
        // here, after the inventory target is in place, and idempotently (a slot that already holds the replacement is left alone).
        if (pending.contains("packs", Tag.TAG_LIST)) CarriedStorage.apply(player, pending.getList("packs", Tag.TAG_COMPOUND));
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
        if (pending.hasUUID("crew_tx")) {
            // A purchase the Crew bank helped pay: the inventory and wallet targets are applied and saved, so the held credits are now spent. This comes
            // BEFORE the journal settles, which makes "settled" imply "committed": the hold of a delivered purchase can never be mistaken for an undecided one.
            CrewService.checkpoint("purchase_after_apply");
            CrewService.commitPurchase(player, pending.getUUID("crew_tx"));
            CrewService.checkpoint("purchase_after_commit");
        }
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
