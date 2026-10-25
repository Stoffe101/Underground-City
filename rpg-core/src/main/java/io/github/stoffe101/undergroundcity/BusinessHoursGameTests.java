package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Business hours by kind of business (D-185): the shipped table is the owner's (shops 08 to 18, cafes 07 to 20, restaurants 11 to 23, pubs and bars 12 to 02, clubs 18 to 03, industrial
 * suppliers 07 to 19), a span crosses midnight, the people who work a building keep its kind's hours, a fence keeps its own, and the bonded store's warehouse is shut at 04:00 when the raid
 * is done (D-173).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BusinessHoursGameTests {
    private BusinessHoursGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static BuildingSpots.Plan plan(String type, String use) {
        return new BuildingSpots.Plan(type, use, "blackwater", List.of(new BuildingSpots.Spot("counter", 4, 1, 4, "south", "the bar"), new BuildingSpots.Spot("bed", 2, 5, 2, "north", "a bed")));
    }

    /** How many of 40 days the worker of {@code plan} is at the counter at {@code hour}. */
    private static int daysIn(BuildingSpots.Plan plan, int hour) {
        HouseholdPlan.Resident worker = new HouseholdPlan.Resident(77L, "Test Worker", 0, "worker", 0, 1);
        int in = 0;
        for (long day = 0; day < 40; day++) if (HouseholdPlan.where(worker, plan, 1234L, hour, day) != HouseholdPlan.AWAY) in++;
        return in;
    }

    @GameTest(template = "empty")
    public static void eachKindOfBusinessKeepsItsHoursAcrossMidnight(GameTestHelper helper) {
        BusinessHours.Rules rules = BusinessHours.RULES;
        int[][] expected = {{8, 18}, {7, 20}, {11, 23}, {12, 2}, {18, 3}, {7, 19}};
        String[] kinds = {"shop", "cafe", "restaurant", "pub", "club", "industrial"};
        for (int i = 0; i < kinds.length; i++) {
            BusinessHours.Span s = rules.kinds().get(kinds[i]);
            check(s != null && s.from() == expected[i][0] && s.to() == expected[i][1], kinds[i] + " keeps " + s + ", not " + expected[i][0] + " to " + expected[i][1]);
        }
        BusinessHours.Span club = rules.kinds().get("club");
        for (int h : new int[] {18, 21, 23, 0, 2}) check(club.open(h), "a club is shut at " + h + ":00");
        for (int h : new int[] {3, 4, 10, 13, 17}) check(!club.open(h), "a club is open at " + h + ":00");
        BusinessHours.Span pub = rules.kinds().get("pub");
        check(pub.open(12) && pub.open(23) && pub.open(1) && !pub.open(2) && !pub.open(11), "a pub's hours do not cross midnight to two");
        // the furnished buildings by type and use
        check(rules.kindOf("underground_city:bw_club", "club").equals("club") && rules.kindOf("underground_city:ih_pub", "pub").equals("pub"), "the club or the pub keeps the wrong hours");
        check(rules.kindOf("underground_city:vw_bakery", "shop").equals("cafe"), "the bakery is not a cafe");
        check(rules.kindOf("underground_city:bw_store", "shop").equals("shop") && rules.kindOf("underground_city:ih_warehouse", "warehouse").equals("industrial")
            && rules.kindOf("underground_city:ih_workshop", "workshop").equals("industrial"), "a shop or an industrial supplier keeps the wrong hours");
        check(rules.kindOf("underground_city:something_new", "flats").equals(rules.fallback()), "an unknown building does not keep the fallback hours");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theStaffKeepTheirBuildingsHoursAndTheBondedStoreIsShutForTheRaid(GameTestHelper helper) {
        BuildingSpots.Plan club = plan("underground_city:bw_club", "club"), shop = plan("underground_city:bw_store", "shop"), yard = plan("underground_city:ih_warehouse", "warehouse");
        check(daysIn(club, 1) >= 30 && daysIn(club, 22) >= 30, "a club's bartender is not in after midnight or in the evening");
        check(daysIn(club, 10) == 0 && daysIn(club, 4) == 0, "a club's bartender is in at ten in the morning or at four");
        check(daysIn(shop, 13) >= 30 && daysIn(shop, 20) == 0 && daysIn(shop, 1) == 0, "a shop's keeper does not keep eight to six");
        // the bonded store raid is done at 04:00 (D-173): the warehouse's staff are home and the fence's night trade (21:00 to 03:00) is over
        check(daysIn(yard, 4) == 0 && daysIn(yard, 7) >= 30 && daysIn(yard, 18) >= 30 && daysIn(yard, 19) == 0, "the warehouse does not keep seven to seven, or is staffed at 04:00");
        check(!HouseholdPlan.fenceHours(4) && HouseholdPlan.fenceHours(22) && HouseholdPlan.fenceHours(2), "a fence's hours changed");
        helper.succeed();
    }
}
