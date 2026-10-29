package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** The Guide page's opportunities (twelfth pass): built from the terminal's rows and the server's state, most useful first, crime marked as crime and locked work marked as locked. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OpportunitiesGameTests {
    private OpportunitiesGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static TerminalData.Row row(String id, String title, int level, int credits, boolean accepted, int held, boolean underground, boolean skillOk, String buyerCity) {
        return new TerminalData.Row("underground_city:" + id, title, level, credits, 100, 2, accepted, 0, List.of(new TerminalData.Req("minecraft:carrot", 4, held)),
            new TerminalData.Extra(underground, skillOk ? "" : "Cultivar Broker", skillOk, buyerCity.isEmpty() ? "" : "A buyer", buyerCity, 0, 0, List.of(), "farmer"));
    }

    @GameTest(template = "empty")
    public static void theListLeadsWithWorkThatIsReadyAndMarksCrimeAndLockedWork(GameTestHelper helper) {
        FakePlayer player = CrewGameTests.player(helper, "uc_opp_farmer", "farmer");
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        List<TerminalData.Row> rows = List.of(
            row("accepted_order", "Harvest for the canteen", 1, 60, true, 4, false, true, ""),
            row("open_order", "Council stall produce", 1, 240, false, 0, false, true, ""),
            row("far_order", "Blackwater market crates", 1, 180, false, 0, false, true, "blackwater"),
            row("locked_order", "Broker heirloom seed stock", 5, 400, false, 0, false, false, ""),
            row("quiet_order", "Quiet bales", 1, 237, false, 0, true, true, "blackwater"));
        List<TerminalData.Opportunity> list = Opportunities.of(player, citizen, ledger, "valewood", rows);
        check(!list.isEmpty() && list.size() <= TerminalData.MAX_OPPORTUNITIES, "the list is empty or too long: " + list.size());
        check(list.get(0).kind().equals("deliver") && list.get(0).title().contains("Harvest for the canteen"), "the accepted order the player can deliver is not first: " + list.get(0));
        check(list.stream().anyMatch(o -> o.kind().equals("work") && o.title().equals("Council stall produce")), "the best open order is missing: " + list);
        check(list.stream().filter(o -> o.kind().equals("underground")).allMatch(TerminalData.Opportunity::underground) && list.stream().anyMatch(o -> o.kind().equals("underground")), "work off the books is not marked: " + list);
        check(list.stream().anyMatch(o -> o.kind().equals("locked") && o.locked() && o.detail().contains("Cultivar Broker")), "the order a skill would open is not shown as locked with its skill: " + list);
        check(list.stream().anyMatch(o -> o.kind().equals("home")), "a citizen with no home is not told how to find one");
        check(list.stream().noneMatch(o -> o.kind().equals("service")), "a Farmer was offered a Technician's or Smith's call");
        check(list.stream().noneMatch(o -> !o.underground() && o.title().contains("Quiet bales")), "crime was shown as ordinary work");
        // a beginner with nothing accepted sees two open orders and no delivery
        List<TerminalData.Opportunity> fresh = Opportunities.of(player, citizen, ledger, "valewood", rows.subList(1, rows.size()));
        check(fresh.stream().noneMatch(o -> o.kind().equals("deliver")), "a delivery was offered with nothing accepted");
        check(fresh.stream().filter(o -> o.kind().equals("work")).count() == 2, "a citizen with nothing accepted should see two open orders: " + fresh);
        helper.succeed();
    }
}
