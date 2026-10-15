package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
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
        int heat = ledger.heat(player.getUUID(), post.jurisdiction());
        double chance = LawMath.inspectionChance(law, post.risk(), heat, SkillService.inspectionModifier(citizen));
        UUID id = player.getUUID();
        boolean searched = LawMath.roll(chance, LawMath.mix(id.getMostSignificantBits(), id.getLeastSignificantBits(), LawMath.hash(post.id()), roll));
        if (!searched) return new Result(Outcome.WAVED_THROUGH, 0, 0, "waved through");
        return inspect(player, citizen, post, law, ledger);
    }

    /** What the traveller carries that this city's law forbids them (category to worst status). Pure inspection, no changes. */
    public static Map<String, String> violations(CitizenState.Citizen citizen, CityLaws.Law law, Iterable<ItemStack> stacks) {
        Map<String, String> found = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            String category = ContrabandCatalog.INSTANCE.classify(stack);
            if (category == null) continue;
            String status = law.status(category);
            if (status.equals(CityLaws.LEGAL)) continue;
            if (status.equals(CityLaws.PERMIT) && SkillService.licensed(citizen, category)) continue;
            found.merge(category, status, (a, b) -> a.equals(CityLaws.PROHIBITED) || b.equals(CityLaws.PROHIBITED) ? CityLaws.PROHIBITED : a);
        }
        return found;
    }

    /** Performs the search itself (the dice already said yes). */
    public static Result inspect(ServerPlayer player, CitizenState.Citizen citizen, CheckpointCatalog.Checkpoint post,
                                 CityLaws.Law law, WorkOrderLedger ledger) {
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        List<ItemStack> carried = new ArrayList<>(after.items);
        carried.addAll(after.offhand);
        Map<String, String> violations = violations(citizen, law, carried);
        if (violations.isEmpty()) {
            player.sendSystemMessage(Component.literal("The " + TerminalScreenText.capitalize(post.jurisdiction()) + " guard searches your bags and finds nothing illegal."));
            return new Result(Outcome.CLEARED, 0, 0, "searched, clean");
        }
        int seized = 0;
        for (List<ItemStack> slots : List.of(after.items, after.offhand)) {
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                String category = ContrabandCatalog.INSTANCE.classify(stack);
                if (category != null && violations.containsKey(category)) { slots.set(slot, ItemStack.EMPTY); seized++; }
            }
        }
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
        ledger.prepareSeizure(player.getUUID(), after.save(new ListTag()), balanceAfter, citizen.careerXp(), post.jurisdiction(), heatAfter);
        WorkOrderService.recover(player, ledger);
        List<String> titles = new ArrayList<>();
        violations.forEach((category, status) -> {
            var entry = ContrabandCatalog.INSTANCE.category(category);
            titles.add((entry == null ? category : entry.title()) + " (" + status + ")");
        });
        player.sendSystemMessage(Component.literal("The " + TerminalScreenText.capitalize(post.jurisdiction()) + " guard searches you and confiscates "
            + seized + " stack" + (seized == 1 ? "" : "s") + " of contraband: " + String.join(", ", titles) + ". Fine: " + paid + " credits."));
        player.sendSystemMessage(Component.literal("Heat in " + TerminalScreenText.capitalize(post.jurisdiction()) + " is now " + heatAfter + "; searches here become more likely until it cools."));
        return new Result(Outcome.SEIZED, seized, paid, "seized " + seized);
    }
}
