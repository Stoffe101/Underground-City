package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The on-screen status (D-109), the civilian counter's data and the guided journey's new steps, proved without a client: where the cards stand at every window size the game offers, what the
 * server tells a client, and what each guided step and goal means. How it looks on a screen is a different question: the settings screen is captured at the four supported viewports.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HudGameTests {
    /** The GUI sizes the supported windows give: 1280x720 at scale 3 and 2, 1920x1080 at 3 and 2, and 3440x1440 at 3 and 2. */
    private static final int[][] SCREENS = {{427, 240}, {640, 360}, {640, 360}, {960, 540}, {1147, 480}, {1720, 720}, {320, 180}, {250, 140}};

    private static HudLayout.Card[] stack(int status, int order, int objective) {
        return new HudLayout.Card[] {new HudLayout.Card(status, 2), new HudLayout.Card(order, 1), new HudLayout.Card(objective, 3)};
    }

    @GameTest(template = "empty")
    public static void theStackStaysOnScreenAndOffTheCornersOthersUse(GameTestHelper helper) {
        for (HudLayout.Anchor anchor : HudLayout.Anchor.values()) {
            for (int[] screen : SCREENS) {
                for (float wanted : new float[] {0.6f, 1.0f, 1.5f}) {
                    HudLayout.Plan plan = HudLayout.plan(anchor, screen[0], screen[1], stack(66, 82, 58), wanted);
                    String where = anchor + " on " + screen[0] + "x" + screen[1] + " at " + wanted;
                    if (!plan.any()) continue;
                    check(plan.scale() >= HudLayout.MIN_SCALE - 1e-4 && plan.scale() <= wanted + 1e-4, "The scale stays between the minimum and the wanted size: " + plan.scale() + " " + where);
                    int bottom = Integer.MIN_VALUE, previousBottom = Integer.MIN_VALUE;
                    for (int i = 0; i < 3; i++) {
                        if (!plan.shown()[i]) continue;
                        int h = Math.round(new int[] {66, 82, 58}[i] * plan.scale());
                        check(plan.x()[i] >= HudLayout.MARGIN - 1 && plan.x()[i] + plan.width() <= screen[0] - HudLayout.MARGIN + 1, "A card stays inside the screen horizontally: " + where);
                        check(plan.y()[i] >= HudLayout.MARGIN - 1 && plan.y()[i] + h <= screen[1] - HudLayout.MARGIN + 1, "A card stays inside the screen vertically: " + where + " y " + plan.y()[i] + " h " + h);
                        check(plan.y()[i] >= previousBottom, "Cards do not overlap each other: " + where);
                        previousBottom = plan.y()[i] + h;
                        bottom = previousBottom;
                        if (anchor.top()) check(plan.y()[i] >= HudLayout.MARGIN + HudLayout.reserved(anchor) - 1, "A top card stays below what the game draws there: " + where);
                        if (anchor.bottom()) check(plan.y()[i] + h <= screen[1] - HudLayout.MARGIN - HudLayout.reserved(anchor) + 1, "A bottom card stays above what the game draws there: " + where);
                        if (anchor.right()) check(plan.x()[i] + plan.width() == screen[0] - HudLayout.MARGIN, "A right-hand card is flush with the right margin: " + where);
                        if (!anchor.right()) check(plan.x()[i] == HudLayout.MARGIN, "A left-hand card is flush with the left margin: " + where);
                    }
                    // the next step is the last card dropped, and only when it is not needed: whenever anything is shown and the next step exists, it is shown
                    check(plan.shown()[2], "The next step outlasts the other cards: " + where);
                }
            }
        }
        // a roomy window shows everything at the wanted size
        HudLayout.Plan roomy = HudLayout.plan(HudLayout.Anchor.TOP_RIGHT, 1720, 720, stack(66, 82, 58), 1.0f);
        check(roomy.shown()[0] && roomy.shown()[1] && roomy.shown()[2] && Math.abs(roomy.scale() - 1.0f) < 1e-4, "A 3440x1440 window at GUI scale 2 shows all three cards at full size");
        // a small one drops the accepted order first, then the status, and keeps the next step
        HudLayout.Plan small = HudLayout.plan(HudLayout.Anchor.TOP_RIGHT, 427, 190, stack(66, 82, 58), 1.0f);
        check(small.shown()[2] && !small.shown()[1], "A small window drops the accepted order before the next step: " + java.util.Arrays.toString(small.shown()));
        // no stack at all when even the smallest card does not fit
        check(!HudLayout.plan(HudLayout.Anchor.TOP_RIGHT, 100, 60, stack(66, 82, 58), 1.0f).any(), "A window too small for any card shows none");
        check(HudLayout.Anchor.TOP_RIGHT.next().previous() == HudLayout.Anchor.TOP_RIGHT && HudLayout.Anchor.BOTTOM_RIGHT.next() == HudLayout.Anchor.TOP_LEFT, "The anchors cycle both ways");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theStatusSnapshotCrossesTheWireIntact(GameTestHelper helper) {
        HudState state = new HudState("valewood", 0, "ironhaven", "farmer", 3, 0.4f, 520, 2, 340.5, "The Row", 1200, "Row Flat 2", 24, 1, 38, "GRACE", 48, 3, "valewood");
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        state.write(buffer);
        check(HudState.read(buffer).equals(state), "A snapshot reads back as it was written");
        check(HudState.EMPTY.equals(HudState.read(emptyBuffer())), "The empty snapshot reads back as itself");
        check(!HudState.EMPTY.registered() && !HudState.EMPTY.hasCrew() && !HudState.EMPTY.hasHome(), "Nobody is registered, in a Crew or at home in the empty snapshot");
        helper.succeed();
    }

    private static FriendlyByteBuf emptyBuffer() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        HudState.EMPTY.write(buffer);
        return buffer;
    }

    @GameTest(template = "empty")
    public static void theServerSaysWhereThePlayerIsAndWhoTheyAre(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var regions = CityRegions.INSTANCE.all();
        check(!regions.isEmpty(), "City regions are loaded");
        CityRegions.Region region = regions.get(0);
        UUID id = UUID.randomUUID();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(id, "uc_hud_" + id.toString().substring(0, 6)));
        // not a citizen: a place and nothing else
        player.setPos(region.centerX(), 80, region.centerZ());
        HudState visitor = HudState.compute(player);
        check(visitor.placeKind() == 0 && visitor.place().equals(region.id()) && !visitor.registered(), "Inside a city's circle the place is that city: " + visitor);
        player.setPos(region.centerX() + region.radius() + 120, 80, region.centerZ());
        HudState near = HudState.compute(player);
        check(near.placeKind() == 1 && !near.place().isEmpty(), "A little beyond the circle is its outskirts: " + near);
        player.setPos(region.centerX() + region.radius() + 5000, 80, region.centerZ() + 5000);
        HudState open = HudState.compute(player);
        check(open.placeKind() == 2 && open.place().isEmpty(), "Far from every city is the open road: " + open);
        // a citizen: the trade, the level and the home city
        CitizenState citizens = CitizenState.get(server);
        check(citizens.register(id, "valewood", "farmer"), "Registered");
        citizens.addCareerXp(id, ProgressionRules.xpAtLevel(3));
        player.setPos(region.centerX(), 80, region.centerZ());
        HudState citizen = HudState.compute(player);
        check(citizen.registered() && citizen.homeCity().equals("valewood") && citizen.career().equals("farmer") && citizen.level() >= 3, "A citizen's trade and level are in the snapshot: " + citizen);
        check(citizen.xpFraction() >= 0f && citizen.xpFraction() <= 1f && !citizen.hasHome() && !citizen.hasCrew(), "No home and no Crew yet: " + citizen);
        // a fake player is never sent anything
        check(HudState.of(player).equals(HudState.EMPTY), "A fake player has no screen");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theGuidedJourneyHasAHomeStepAndAGoalAfterTheChecklist(GameTestHelper helper) {
        List<String> ids = Journey.STEPS.stream().map(Journey.Def::id).toList();
        check(ids.equals(List.of("registered", "employer", "accept", "deliver", "trade", "home", "skill", "travel", "second")), "The first hours in the order a citizen meets them: " + ids);
        var level = helper.getLevel();
        UUID id = UUID.randomUUID();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(id, "uc_goal_" + id.toString().substring(0, 6)));
        CitizenState citizens = CitizenState.get(level.getServer());
        citizens.register(id, "ironhaven", "smith");
        var low = Journey.goal(player, citizens.find(id).orElseThrow());
        check(low != null && low.title().startsWith("Reach level"), "A new citizen's goal is the next level: " + low);
        citizens.addCareerXp(id, ProgressionRules.xpAtLevel(3));
        var mid = Journey.goal(player, citizens.find(id).orElseThrow());
        check(mid != null && mid.title().contains("friend"), "At level 3 without a Crew the goal is the Crew: " + mid);
        citizens.addCareerXp(id, ProgressionRules.xpAtLevel(5));
        check(Journey.goal(player, citizens.find(id).orElseThrow()) == null, "By level 5 the goal ends by itself");
        // a home flag is a flag: set once, never undone
        check(citizens.find(id).orElseThrow().journey("home") == 0, "No home milestone yet");
        citizens.markJourney(id, "home");
        check(Journey.done(citizens.find(id).orElseThrow(), WorkOrderLedger.get(level.getServer()), id, "home"), "The home step is done once a home was rented or bought");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyARealPurchaseCountsAsBuyingSomething(GameTestHelper helper) {
        if (!ModList.get().isLoaded("qshop")) { helper.succeed(); return; }
        var level = helper.getLevel();
        UUID id = UUID.randomUUID();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(id, "uc_buyer_" + id.toString().substring(0, 6)));
        CitizenState citizens = CitizenState.get(level.getServer());
        citizens.register(id, "ironhaven", "smith");
        var trade = com.qshop.api.CurrencyService.SOURCE_TRADE;
        MinecraftForge.EVENT_BUS.post(new com.qshop.api.CurrencyChangedEvent(player, "credits", 100, 120, trade, null)); // a sale pays: not a purchase
        check(citizens.find(id).orElseThrow().journey("bought") == 0, "A sale to the shop is not buying something");
        MinecraftForge.EVENT_BUS.post(new com.qshop.api.CurrencyChangedEvent(player, "credits", 120, 100, com.qshop.api.CurrencyService.SOURCE_COMMAND, null)); // not a trade
        check(citizens.find(id).orElseThrow().journey("bought") == 0, "A balance change that is not a trade is not buying something");
        MinecraftForge.EVENT_BUS.post(new com.qshop.api.CurrencyChangedEvent(player, "credits", 120, 100, trade, null));
        check(citizens.find(id).orElseThrow().journey("bought") > 0, "A purchase from a shop counts");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theCivilianCounterNamesWhatIsLeftAndWhenItComesBack(GameTestHelper helper) {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4);
        var unlimited = new CivilianShopData.Entry(0, false, "Rations (x4)", stack, 20, -1, -1, "NEVER");
        var daily = new CivilianShopData.Entry(1, false, "Basic seeds (x4)", stack, 8, 32, 3, "DAILY");
        var global = new CivilianShopData.Entry(2, false, "Workbench", stack, 18, 2, 16, "WEEKLY");
        var spent = new CivilianShopData.Entry(3, true, "Deliver wheat (x8)", stack, 12, 0, 8, "DAILY");
        check(unlimited.left() == -1 && unlimited.limitText().isEmpty(), "An unlimited entry says nothing about limits");
        check(daily.left() == 3 && daily.limitText().equals("3 left today"), "The player's own limit is the tighter: " + daily.limitText());
        check(global.left() == 2 && global.limitText().equals("2 left this week"), "The shop-wide limit is the tighter: " + global.limitText());
        check(spent.left() == 0 && spent.count() == 4, "A spent entry has none left and counts its lot: " + spent.left());
        var data = new CivilianShopData("uc_starter_supply", "Supply", "credits", 72, List.of(new CivilianShopData.Tab(0, "Goods", stack, List.of())));
        check(data.empty(), "A counter with no entries is empty");
        check(CivilianShopData.fmt(8).equals("8") && CivilianShopData.fmt(2.5).equals("2.50"), "Prices print whole when whole");
        helper.succeed();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
