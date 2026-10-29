package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lawful roadside inspections (D-053). The guard does not know what you carry. A checkpoint is a zone on an
 * inter-city road; when a registered traveller enters it, the ledger issues a numbered roll (at most one per
 * checkpoint per ten active minutes) and a deterministic dice roll decides whether this traveller is searched.
 * A search confiscates only items the city's law calls contraband for THIS traveller (a licence skill satisfies
 * a "permit" status, never a "prohibited" one), charges the city's fine up to the wallet, and raises that
 * city's heat. Inventory, wallet and heat change through the same fsynced prepared-settlement journal as every
 * other transaction. Creative and spectator players are never searched.
 */
public final class CheckpointService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, String> INSIDE = new HashMap<>();

    public enum Outcome { NOT_CHECKED, WAVED_THROUGH, CLEARED, SEIZED }

    /** Test seam: the process "dies" right after the prepared record is on disk, before anything is applied (the crash the journal exists for). */
    static volatile boolean crashAfterPrepare;

    public record Result(Outcome outcome, int stacksSeized, int fine, String summary) {}

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer || player.isSpectator() || player.isCreative()) continue;
            CheckpointCatalog.Checkpoint post = CheckpointCatalog.INSTANCE.at(
                player.level().dimension().location().toString(), player.getX(), player.getZ());
            if (post == null) { INSIDE.remove(player.getUUID()); continue; }
            if (post.id().equals(INSIDE.put(player.getUUID(), post.id()))) continue;
            try {
                approach(player, post);
            } catch (RuntimeException error) {
                LOGGER.error("Checkpoint inspection failed for {} at {}", player.getUUID(), post.id(), error);
            }
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { INSIDE.remove(event.getEntity().getUUID()); }

    /** A traveller reached a checkpoint zone. Tells them where they are, then maybe searches them. */
    public static Result approach(ServerPlayer player, CheckpointCatalog.Checkpoint post) {
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        CityLaws.Law law = CityLaws.INSTANCE.find(post.jurisdiction());
        if (citizen == null || law == null || ledger.faulted() || ledger.player(player.getUUID()).contains("pending")) {
            return new Result(Outcome.NOT_CHECKED, 0, 0, "not checked");
        }
        long roll = ledger.claimInspectionRoll(player.getUUID(), post.id());
        if (roll < 0) return new Result(Outcome.NOT_CHECKED, 0, 0, "recently searched here");
        player.sendSystemMessage(Component.literal("Checkpoint: " + post.title() + " (" + TerminalScreenText.capitalize(post.jurisdiction()) + " guard)."));
        guardTurns(player, post);
        int heat = ledger.heat(player.getUUID(), post.jurisdiction());
        double chance = LawMath.inspectionChance(law, post.risk(), heat, SkillService.inspectionModifier(citizen));
        // a person wanted in this city (twelfth pass): the guard has their description, and a search of them is an arrest for the warrant
        WorkOrderLedger.Warrant warrant = ledger.warrant(player.getUUID(), post.jurisdiction());
        if (warrant != null) chance = Math.min(law.maxChance(), chance + WANTED_SEARCH_BONUS);
        UUID id = player.getUUID();
        boolean searched = LawMath.roll(chance, LawMath.mix(id.getMostSignificantBits(), id.getLeastSignificantBits(), LawMath.hash(post.id()), roll));
        if (!searched) {
            if (warrant != null) PoliceTrace.note(player, post.jurisdiction(), "checkpoint", "wanted person waved through at " + post.id() + " (search chance " + Math.round(chance * 100) + "%)");
            return new Result(Outcome.WAVED_THROUGH, 0, 0, "waved through");
        }
        if (warrant != null) return arrestWanted(player, citizen, post, law, ledger, warrant);
        return inspect(player, citizen, post, law, ledger, player.getVehicle(), new Why(chance, heat));
    }

    /** How much more likely a checkpoint is to search a person wanted in its city (capped by the city's maximum). */
    static final double WANTED_SEARCH_BONUS = 0.4;

    /** A checkpoint's guard recognises a wanted person: the arrest of the warrant, through the same prepared record as any arrest. */
    static Result arrestWanted(ServerPlayer player, CitizenState.Citizen citizen, CheckpointCatalog.Checkpoint post, CityLaws.Law law, WorkOrderLedger ledger, WorkOrderLedger.Warrant warrant) {
        CityLaws.Offence offence = CityLaws.INSTANCE.offence(post.jurisdiction(), warrant.offence());
        player.sendSystemMessage(Component.literal(actor(player, post) + " checks your face against a description: you are wanted for " + offence.charge() + ".").withStyle(net.minecraft.ChatFormatting.GOLD));
        int priors = ledger.priors(player.getUUID(), post.jurisdiction());
        Result result = arrest(player, citizen, "checkpoint:" + post.id() + ":warrant:" + warrant.offence(), post.jurisdiction(), law, ledger,
            LawMath.escalatedFine(warrant.fine(), priors), LawMath.escalatedHeat(warrant.heat(), priors));
        PoliceTrace.note(player, post.jurisdiction(), "arrest", "at checkpoint " + post.id() + " on a warrant for " + warrant.offence() + ": fined " + result.fine() + ", " + result.stacksSeized() + " stack(s) seized");
        PoliceResponse.arrestsOnWarrant++;
        Notice.send(player, Notice.Kind.HEAT, "Arrested at the checkpoint", result.stacksSeized() + (result.stacksSeized() == 1 ? " stack" : " stacks") + " taken, " + result.fine() + " credits fined. The warrant is served.");
        return result;
    }

    /** The post's guard, when the zone has a built post: they turn to the traveller and challenge them, so the search has a face. */
    private static void guardTurns(ServerPlayer player, CheckpointCatalog.Checkpoint post) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        CheckpointPosts.guardAt(level, post).ifPresent(guard -> {
            guard.getLookControl().setLookAt(player);
            player.sendSystemMessage(Component.literal(guard.getCustomName().getString() + ": Stop at the post. Routine inspection.").withStyle(net.minecraft.ChatFormatting.GOLD));
        });
    }

    /** Who searches: the post's own guard where one stands, otherwise the city's guard in general. */
    private static String actor(ServerPlayer player, CheckpointCatalog.Checkpoint post) {
        if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
            var guard = CheckpointPosts.guardAt(level, post);
            if (guard.isPresent() && guard.get().hasCustomName()) return "The " + TerminalScreenText.capitalize(post.jurisdiction()) + " guard at the post, " + guard.get().getCustomName().getString() + ",";
        }
        return "The " + TerminalScreenText.capitalize(post.jurisdiction()) + " guard";
    }

    /** The category a stack counts under: marked stolen goods, else the contraband catalogue's answer (null for an ordinary item). */
    static String categoryOf(ItemStack stack) {
        return StolenGoods.is(stack) ? StolenGoods.CATEGORY : ContrabandCatalog.INSTANCE.classify(stack);
    }

    static final String LICENSED_FIREARMS = "firearms_licensed";

    /**
     * The category a stack counts under for the person carrying it: as {@link #categoryOf}, except that a licensed firearm this person built and registered at a Gun Smith Table
     * is carried under its registration (D-175) and counts as nothing, in every city. The first-hour audit found the legal sidearm order delivered in Valewood, which asks a permit
     * for licensed firearms, while the Arms Dealer Licence comes four levels later: the Gunsmith's own legal gun was seized on the way to the legal buyer. A gun registered to
     * somebody else, an unmarked gun and a restricted weapon count as before.
     */
    static String categoryFor(ServerPlayer holder, ItemStack stack) {
        String category = categoryOf(stack);
        if (holder != null && LICENSED_FIREARMS.equals(category) && GunsmithBuilds.registeredTo(holder.server, stack, holder.getUUID())) return null;
        return category;
    }

    /** What the traveller carries that this city's law forbids them (category to worst status). Pure inspection, no changes. */
    public static Map<String, String> violations(CitizenState.Citizen citizen, CityLaws.Law law, Iterable<ItemStack> stacks) {
        return violations(citizen, law, stacks, null);
    }

    /** As above, for a known holder, whose own registered builds are carried under their registration. */
    static Map<String, String> violations(CitizenState.Citizen citizen, CityLaws.Law law, Iterable<ItemStack> stacks, ServerPlayer holder) {
        Map<String, String> found = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            String category = categoryFor(holder, stack);
            if (category == null) continue;
            // marked goods are prohibited in every city, whatever its law says about the item itself (D-122)
            String status = category.equals(StolenGoods.CATEGORY) ? CityLaws.PROHIBITED : law.status(category);
            if (status.equals(CityLaws.LEGAL)) continue;
            if (status.equals(CityLaws.PERMIT) && SkillService.licensed(citizen, category)) continue;
            found.merge(category, status, (a, b) -> a.equals(CityLaws.PROHIBITED) || b.equals(CityLaws.PROHIBITED) ? CityLaws.PROHIBITED : a);
        }
        return found;
    }

    /** Why a traveller was searched: the odds the post gave, the heat that raised them. Kept in the record of the search. */
    public record Why(double chance, int heat) {
        static final Why UNKNOWN = new Why(0, 0);
    }

    /** Performs the search itself (the dice already said yes). */
    public static Result inspect(ServerPlayer player, CitizenState.Citizen citizen, CheckpointCatalog.Checkpoint post,
                                 CityLaws.Law law, WorkOrderLedger ledger) {
        return inspect(player, citizen, post, law, ledger, player.getVehicle(), Why.UNKNOWN);
    }

    static Result inspect(ServerPlayer player, CitizenState.Citizen citizen, CheckpointCatalog.Checkpoint post,
                          CityLaws.Law law, WorkOrderLedger ledger, net.minecraft.world.entity.Entity ridden) {
        return inspect(player, citizen, post, law, ledger, ridden, Why.UNKNOWN);
    }

    /**
     * {@code ridden} is the vehicle brought into the zone (normally the one the traveller rides); tests name one directly. The search covers what the traveller
     * carries (pockets, hands, armour slots and the packs, shulker boxes and bundles in them: see {@link CarriedStorage}) and the hold of the vehicle brought into
     * the zone. It never reads storage that is not carried: a chest nearby, a house, somebody else's pack.
     */
    static Result inspect(ServerPlayer player, CitizenState.Citizen citizen, CheckpointCatalog.Checkpoint post,
                          CityLaws.Law law, WorkOrderLedger ledger, net.minecraft.world.entity.Entity ridden, Why why) {
        // An open container (a backpack screen) holds its own view of the pack and could write it back over the seizure, so the search closes it first.
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        List<CarriedStorage.Found> carried = CarriedStorage.scan(after);
        // A traveller who rides a vehicle with a hold (a boat, a cart, a car with a crate in its rack) brings its cargo into the zone with them, so the
        // guard searches that too. A vehicle left outside the zone is never searched: nothing is known about what is not here (D-051).
        VehicleCargo.Hold hold = VehicleCargo.of(ridden);
        String vehicleName = ridden == null ? "" : ridden.getName().getString();
        List<CarriedStorage.Found> cargo = new ArrayList<>();
        if (hold != null) {
            for (int slot = 0; slot < hold.size(); slot++) {
                ItemStack stack = hold.get(slot);
                if (stack.isEmpty()) continue;
                int index = slot;
                cargo.add(CarriedStorage.inHold(stack, "the " + vehicleName, () -> hold.take(index)));
                cargo.addAll(CarriedStorage.scanHold(stack));
            }
        }
        List<CarriedStorage.Found> everything = new ArrayList<>(carried);
        everything.addAll(cargo);
        Map<String, String> violations = violations(citizen, law, everything.stream().map(f -> f.stack).toList(), player);
        if (violations.isEmpty()) {
            player.sendSystemMessage(Component.literal(actor(player, post) + " searches your bags" + (hold != null ? " and your " + vehicleName : "") + " and finds nothing illegal."));
            Notice.send(player, Notice.Kind.SEARCH, "Searched, nothing found", post.title() + ". You may go.");
            ledger.recordSearch(player.getUUID(), searchRecord(post, why, "clean", 0, 0, Map.of(), List.of()));
            return new Result(Outcome.CLEARED, 0, 0, "searched, clean");
        }
        // What is taken, and from where: a stack counts once, in the place it was found.
        List<CarriedStorage.Found> takenFromPerson = new ArrayList<>(), takenFromVehicle = new ArrayList<>();
        Map<String, Integer> whereCounts = new java.util.LinkedHashMap<>();
        for (CarriedStorage.Found found : everything) {
            String category = categoryFor(player, found.stack);
            if (category == null || !violations.containsKey(category)) continue;
            (cargo.contains(found) ? takenFromVehicle : takenFromPerson).add(found);
            whereCounts.merge(found.where, 1, Integer::sum);
        }
        for (CarriedStorage.Found found : takenFromPerson) found.take();   // out of the inventory copy, or recorded as a change to a carried pack
        int seized = takenFromPerson.size() + takenFromVehicle.size();
        int fine = violations.values().stream().mapToInt(law::fine).sum();
        boolean economy = QShopEconomy.available() && QShopEconomy.configured(player.server);
        Double balanceAfter = null;
        int paid = 0;
        if (economy) {
            double balance = QShopEconomy.balance(player);
            paid = (int) Math.min(balance, fine);
            balanceAfter = balance - paid;
        }
        int heatAfter = ledger.heatAfter(player.getUUID(), post.jurisdiction(), 2);
        List<String> titles = new ArrayList<>();
        violations.forEach((category, status) -> {
            var entry = ContrabandCatalog.INSTANCE.category(category);
            titles.add((entry == null ? (category.equals(StolenGoods.CATEGORY) ? "Stolen goods" : category) : entry.title()) + " (" + status + ")");
        });
        CompoundTag record = searchRecord(post, why, "seized", seized, paid, whereCounts, titles);
        // One prepared record carries the inventory, the changes to carried packs, the fine, the heat and the record of the search: all or none, replayed after a crash.
        ledger.prepareSeizure(player.getUUID(), after.save(new ListTag()), balanceAfter, citizen.careerXp(), post.jurisdiction(), heatAfter, CarriedStorage.journal(takenFromPerson), record);
        if (crashAfterPrepare) throw new CrewService.SimulatedCrash("checkpoint_after_prepare");
        EconomyTelemetry.record(player, "search", "city", post.jurisdiction(), "fine", paid, "stacks", seized, "post", post.id());
        // The vehicle's hold is not part of the journaled inventory; its stacks leave it now, after the decision is on disk (a crash here costs the traveller the fine
        // and the heat but not the goods, which is the safe side).
        for (CarriedStorage.Found found : takenFromVehicle) found.take();
        if (hold != null && !takenFromVehicle.isEmpty()) hold.changed();
        WorkOrderService.recover(player, ledger);
        String places = whereCounts.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").collect(java.util.stream.Collectors.joining(", "));
        player.sendSystemMessage(Component.literal(actor(player, post) + " searches you" + (hold != null ? " and your " + vehicleName : "") + " and confiscates "
            + seized + " stack" + (seized == 1 ? "" : "s") + " of contraband: " + String.join(", ", titles) + ". Found in " + places + ". Fine: " + paid + " credits."));
        if (why.chance() > 0) player.sendSystemMessage(Component.literal("This was a routine inspection: the post searches about " + Math.round(why.chance() * 100) + " in 100 travellers"
            + (why.heat() > 0 ? " with your record in " + TerminalScreenText.capitalize(post.jurisdiction()) + " (heat " + why.heat() + ")" : "") + "."));
        player.sendSystemMessage(Component.literal("Heat in " + TerminalScreenText.capitalize(post.jurisdiction()) + " is now " + heatAfter + "; searches here become more likely until it cools."));
        Notice.send(player, Notice.Kind.HEAT, "Contraband seized", seized + (seized == 1 ? " stack" : " stacks") + " taken from " + places + ", " + paid + " credits fined. Heat " + heatAfter + " in " + TerminalScreenText.capitalize(post.jurisdiction()) + ".");
        return new Result(Outcome.SEIZED, seized, paid, "seized " + seized);
    }

    /**
     * An arrest after a break-in somebody noticed (D-122), settled through the same prepared record as a checkpoint search, so a crash cannot leave a fine without the seizure or the other way round.
     * What the officers take is what the person carries (pockets, hands, armour and the packs in them, never storage that is not carried) that is marked as stolen goods or that this city's law forbids
     * this person; the fine is {@code fine} credits, never more than the wallet holds; the heat is {@code heat} more in {@code city}. {@code postId} names the building in the record of the search.
     */
    static Result arrest(ServerPlayer player, CitizenState.Citizen citizen, String postId, String city, CityLaws.Law law, WorkOrderLedger ledger, int fine, int heat) {
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        List<CarriedStorage.Found> carried = CarriedStorage.scan(after);
        Map<String, String> violations = violations(citizen, law, carried.stream().map(f -> f.stack).toList(), player);
        List<CarriedStorage.Found> taken = new ArrayList<>();
        Map<String, Integer> whereCounts = new LinkedHashMap<>();
        for (CarriedStorage.Found found : carried) {
            String category = categoryFor(player, found.stack);
            if (category == null || !violations.containsKey(category)) continue;
            taken.add(found);
            whereCounts.merge(found.where, 1, Integer::sum);
        }
        for (CarriedStorage.Found found : taken) found.take();
        Double balanceAfter = null;
        int paid = 0;
        if (QShopEconomy.available() && QShopEconomy.configured(player.server)) {
            double balance = QShopEconomy.balance(player);
            paid = (int) Math.min(balance, Math.max(0, fine));
            balanceAfter = balance - paid;
        }
        int heatAfter = ledger.heatAfter(player.getUUID(), city, heat);
        List<String> titles = new ArrayList<>();
        violations.forEach((category, status) -> {
            var entry = ContrabandCatalog.INSTANCE.category(category);
            titles.add((entry == null ? (category.equals(StolenGoods.CATEGORY) ? "Stolen goods" : category) : entry.title()) + " (" + status + ")");
        });
        CompoundTag record = searchRecord(postId, city, Why.UNKNOWN, "arrested", taken.size(), paid, whereCounts, titles);
        // an arrest is a conviction: its settlement serves the person's warrant in the city and records the conviction, in the same step as the fine (twelfth pass)
        ledger.prepareSeizure(player.getUUID(), after.save(new ListTag()), balanceAfter, citizen.careerXp(), city, heatAfter, CarriedStorage.journal(taken), record, true);
        if (crashAfterPrepare) throw new CrewService.SimulatedCrash("checkpoint_after_prepare");
        WorkOrderService.recover(player, ledger);
        EconomyTelemetry.record(player, "arrest", "city", city, "fine", paid, "asked", fine, "stacks", taken.size(), "post", postId);
        return new Result(Outcome.SEIZED, taken.size(), paid, heatAfter + " heat");
    }

    /** The entry a person's search history keeps: where, why, what was found and where it was, what it cost. */
    private static CompoundTag searchRecord(CheckpointCatalog.Checkpoint post, Why why, String outcome, int stacks, int fine, Map<String, Integer> where, List<String> categories) {
        return searchRecord(post.id(), post.jurisdiction(), why, outcome, stacks, fine, where, categories);
    }

    private static CompoundTag searchRecord(String postId, String city, Why why, String outcome, int stacks, int fine, Map<String, Integer> where, List<String> categories) {
        CompoundTag tag = new CompoundTag();
        tag.putString("post", postId); tag.putString("city", city); tag.putString("outcome", outcome);
        tag.putInt("permille", (int) Math.round(why.chance() * 1000)); tag.putInt("heat", why.heat());
        tag.putInt("stacks", stacks); tag.putInt("fine", fine);
        ListTag places = new ListTag(), cats = new ListTag();
        where.forEach((k, v) -> places.add(net.minecraft.nbt.StringTag.valueOf(k + " x" + v)));
        categories.forEach(c -> cats.add(net.minecraft.nbt.StringTag.valueOf(c)));
        tag.put("where", places); tag.put("categories", cats);
        return tag;
    }
}
