package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Shortage contracts (D-207) on a scratch work-order journal: a supplier line short for five minutes of active play posts one contract for its trade, which one player takes and delivers
 * once, at a terminal of the shop's city, before its deadline; the goods go onto the shelf and the line rests. Untaken contracts lapse, taken ones past their deadline cannot be delivered,
 * a city never has more than two, and a restart keeps them. The wallet is Q Shop's own.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShortageContractGameTests {
    private static final String PUB_WINE = UndergroundCity.MOD_ID + ":shops/pub_wine_ironhaven";

    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static void refused(Runnable action, String containing, String what) {
        try { action.run(); } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(containing), what + ": refused, but saying \"" + expected.getMessage() + "\"");
            return;
        }
        throw new net.minecraft.gametest.framework.GameTestAssertException(what + ": not refused");
    }

    private static ServerPlayer vintner(GameTestHelper helper, String name) {
        ServerPlayer player = CrewGameTests.player(helper, name, "vintner");
        QShopEconomy.applyBalance(player, 0);
        return player;
    }

    private static CitizenState.Citizen of(ServerPlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(); }

    /** Every supplier line full but the pub's wine, which is empty: only it can go short. */
    private static RegionalStock onlyThePubShort(WorkOrderLedger ledger) {
        for (RegionalStock line : RegionalCatalog.INSTANCE.all())
            if (RegionalCatalog.INSTANCE.supply(line.id()) != null) ledger.setRegionalUnitsForTest(line, line.capacity());
        RegionalStock pub = RegionalCatalog.INSTANCE.find(PUB_WINE);
        check(pub != null && RegionalCatalog.INSTANCE.supply(PUB_WINE) != null, "the pub's wine line did not load with its supply terms");
        ledger.setRegionalUnitsForTest(pub, 0);
        return pub;
    }

    /** Posts the pub's contract: short for the full five minutes, reviewed. */
    private static ShortageContracts.Contract post(GameTestHelper helper, WorkOrderLedger ledger) {
        ShortageContracts.review(helper.getLevel().getServer(), ledger);
        ledger.advance(ShortageContracts.SHORT_FOR);
        List<ShortageContracts.Contract> posted = ShortageContracts.review(helper.getLevel().getServer(), ledger);
        check(posted.size() == 1 && posted.get(0).line().equals(PUB_WINE), "the pub's short line posted one contract: " + posted);
        return posted.get(0);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aLineShortLongEnoughPostsOneContractThatOnePlayerDeliversOnceOntoTheShelf(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (BusinessGameTests.Orders o = new BusinessGameTests.Orders(helper, "ironhaven")) {
            RegionalStock pub = onlyThePubShort(o.ledger);
            RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(PUB_WINE);
            // short, but not for long enough yet: nothing
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty(), "a line that has just gone short posted a contract");
            o.ledger.advance(ShortageContracts.SHORT_FOR - 1);
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty(), "a line short for less than five minutes of play posted a contract");
            o.ledger.advance(1);
            List<ShortageContracts.Contract> posted = ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(posted.size() == 1, "five minutes short posted one contract: " + posted);
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty() && ShortageContracts.contracts(o.ledger).size() == 1, "a second review posted the line again");
            ShortageContracts.Contract c = posted.get(0);
            WorkOrder order = ShortageContracts.order(c);
            check(order != null && order.career().equals("vintner") && order.capacity() == 1, "the contract is a Vintner's order with one taker");
            check(c.quantity() == Math.min(pub.maxOrder(), pub.capacity() / 2), "it asks for what brings the shelf back to half, within an order: " + c.quantity());
            long unit = order.credits() / c.quantity();
            check(unit > terms.pays() && unit < pub.unitPrice(), "a unit pays more than the shop pays and less than it charges: " + unit);
            check(ShortageContracts.offered(o.ledger, "vintner").size() == 1 && ShortageContracts.offered(o.ledger, "smith").isEmpty(), "offered to the trade, not to another");
            // one taker
            ServerPlayer first = vintner(helper, "uc_sc_first"), second = vintner(helper, "uc_sc_second");
            WorkOrderService.accept(first, o.ledger, of(first), c.id());
            refused(() -> WorkOrderService.accept(second, o.ledger, of(second), c.id()), "Somebody else", "a contract somebody holds");
            // delivered at a terminal of another city: refused before anything is taken
            first.getInventory().add(new ItemStack(UndergroundCity.item("berry_wine"), c.quantity()));
            UUID nonce = o.ledger.player(first.getUUID()).getUUID("order");
            refused(() -> WorkOrderService.complete(first, o.ledger, nonce, o.pos.offset(4000, 0, 0)), "Ironhaven", "a delivery outside the shop's city");
            check(first.getInventory().countItem(UndergroundCity.item("berry_wine")) == c.quantity() && QShopEconomy.balance(first) == 0, "a refused delivery took something");
            // delivered in the city: paid once, the goods on the shelf, the contract filled
            WorkOrderService.complete(first, o.ledger, nonce, o.pos);
            check(QShopEconomy.balance(first) == order.credits(), "the contract paid " + QShopEconomy.balance(first) + ", not " + order.credits());
            check(first.getInventory().countItem(UndergroundCity.item("berry_wine")) == 0, "the bottles were not taken");
            check(o.ledger.regionalUnits(pub) == c.quantity(), "the bottles did not reach the pub's shelf: " + o.ledger.regionalUnits(pub));
            check(ShortageContracts.contract(o.ledger, c.id()).filled() && ShortageContracts.offered(o.ledger, "vintner").isEmpty(), "the contract is filled and offered no more");
            refused(() -> WorkOrderService.complete(first, o.ledger, nonce, o.pos), "", "the same delivery twice");
            check(QShopEconomy.balance(first) == order.credits(), "a second delivery paid again");
            refused(() -> WorkOrderService.accept(second, o.ledger, of(second), c.id()), "filled", "a filled contract taken");
            // the line rests: short again at once, no contract until its cooldown has passed
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(ShortageContracts.contracts(o.ledger).isEmpty(), "a filled contract was not closed");
            o.ledger.setRegionalUnitsForTest(pub, 0);
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            o.ledger.advance(ShortageContracts.SHORT_FOR);
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty(), "a resting line posted again");
            o.ledger.advance(ShortageContracts.COOLDOWN);
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).size() == 1, "after its rest the line posts again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void anUntakenContractLapsesATakenOneCannotBeDeliveredLateAndARestartKeepsThem(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (BusinessGameTests.Orders o = new BusinessGameTests.Orders(helper, "ironhaven")) {
            RegionalStock pub = onlyThePubShort(o.ledger);
            ShortageContracts.Contract c = post(helper, o.ledger);
            // a restart keeps the open contract
            o.restart();
            check(ShortageContracts.contract(o.ledger, c.id()) != null && ShortageContracts.offered(o.ledger, "vintner").size() == 1, "a restart lost the contract");
            // nobody takes it: it lapses at its deadline and the line rests
            o.ledger.advance(ShortageContracts.DEADLINE);
            check(ShortageContracts.offered(o.ledger, "vintner").isEmpty(), "a contract past its deadline is still offered");
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(ShortageContracts.contracts(o.ledger).isEmpty(), "an untaken contract did not lapse");
            o.ledger.advance(ShortageContracts.SHORT_FOR);
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty(), "a lapsed line posted again at once");
            o.ledger.advance(ShortageContracts.COOLDOWN);
            List<ShortageContracts.Contract> again = ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(again.size() == 1, "after its rest the lapsed line posts again");
            // taken but delivered too late: refused, the order can be cancelled, nothing is paid and the shelf is untouched
            ServerPlayer late = vintner(helper, "uc_sc_late");
            WorkOrderService.accept(late, o.ledger, of(late), again.get(0).id());
            o.ledger.advance(ShortageContracts.DEADLINE);
            late.getInventory().add(new ItemStack(UndergroundCity.item("berry_wine"), again.get(0).quantity()));
            UUID nonce = o.ledger.player(late.getUUID()).getUUID("order");
            int shelf = o.ledger.regionalUnits(pub);   // the line refills a bottle an hour of play on its own
            refused(() -> WorkOrderService.complete(late, o.ledger, nonce, o.pos), "ran out of time", "a delivery past the deadline");
            check(QShopEconomy.balance(late) == 0 && o.ledger.regionalUnits(pub) == shelf, "a late delivery paid or filled the shelf");
            check(late.getInventory().countItem(UndergroundCity.item("berry_wine")) == again.get(0).quantity(), "a late delivery took the bottles");
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(ShortageContracts.contract(o.ledger, again.get(0).id()) != null, "a taken contract's record went before its holder could be told");
            o.ledger.advance(ShortageContracts.HELD_GRACE);
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(ShortageContracts.contracts(o.ledger).isEmpty(), "a taken contract long past its deadline was kept");
            o.ledger.cancel(late.getUUID(), nonce);
            check(!o.ledger.player(late.getUUID()).hasUUID("order"), "the late order could not be cancelled");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aCityNeverHasMoreThanTwoShortageContracts(GameTestHelper helper) throws Exception {
        try (BusinessGameTests.Orders o = new BusinessGameTests.Orders(helper, "ironhaven")) {
            int lines = 0;
            for (RegionalStock line : RegionalCatalog.INSTANCE.all()) {
                if (RegionalCatalog.INSTANCE.supply(line.id()) == null) continue;
                o.ledger.setRegionalUnitsForTest(line, line.city().equals("ironhaven") ? 0 : line.capacity());
                if (line.city().equals("ironhaven") && Shortages.mark(line) >= 0) lines++;
            }
            check(lines > ShortageContracts.PER_CITY, "Ironhaven has only " + lines + " supplier lines that can go short; the test needs more");
            ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            o.ledger.advance(ShortageContracts.SHORT_FOR);
            List<ShortageContracts.Contract> posted = ShortageContracts.review(helper.getLevel().getServer(), o.ledger);
            check(posted.size() == ShortageContracts.PER_CITY && posted.stream().allMatch(c -> c.city().equals("ironhaven")), "Ironhaven posted " + posted.size() + " contracts");
            check(ShortageContracts.review(helper.getLevel().getServer(), o.ledger).isEmpty(), "a city full of contracts posted another");
            check(posted.stream().map(ShortageContracts.Contract::line).distinct().count() == posted.size(), "one line posted twice");
        }
        helper.succeed();
    }
}
