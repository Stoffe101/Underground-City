package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * Alarm panels (D-128), headless on the real server loop with fake players and a building the test makes itself: the panel's light follows the server (dark, armed, entry, jammed); a break-in of an
 * armed building with a panel gives an entry delay instead of an instant alarm, and the delay running out rings it; only a Technician with Bypass Tooling and a kit can work the panel, the kit is spent
 * when the trace starts, the right answer jams it (and a jammed building's alarm calls nobody), a wrong, early, late or distant answer rings it and raises heat; a thief who leaves is never rung for.
 * It does not show the panel on a real wall, the trace screen in a real client, or what any of it feels like.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlarmPanelGameTests {
    private static final String TYPE = "underground_city:test_alarm_building";
    private static final CityBuildings.Response ALARMED = new CityBuildings.Response(1.0, 60, 1, 120);
    private static final CityBuildings.Response NEVER = new CityBuildings.Response(0.0, 60, 1, 120);

    private static final class Scene implements AutoCloseable {
        final ServerLevel level;
        final BlockPos door, panel;
        final ServerPlayer thief;
        final CityBuildings.Building building;
        final Map<String, BuildingSpots.Plan> plans;

        Scene(GameTestHelper helper, String name, String career, CityBuildings.Response response, String lockProfile) {
            level = helper.getLevel();
            door = helper.absolutePos(new BlockPos(2, 2, 2));
            panel = helper.absolutePos(new BlockPos(3, 3, 3));
            thief = player(helper, name, career);
            thief.setPos(panel.getX() + 0.5, panel.getY() - 1, panel.getZ() + 1.5);
            int cx = panel.getX() >> 4, cz = panel.getZ() >> 4, streetY = panel.getY() - 2;
            var security = new CityBuildings.SecurityClass("test_alarmed", lockProfile, true, "", 0, "test premises", response);
            building = new CityBuildings.Building("valewood", cx, cz, TYPE, "test_alarm_building", 2, 0, streetY, security);
            plans = BuildingSpots.INSTANCE.plansForTest();
            Map<String, BuildingSpots.Plan> next = new HashMap<>(plans);
            next.put(TYPE, new BuildingSpots.Plan(TYPE, "shop", "valewood", List.of(new BuildingSpots.Spot("alarm", panel.getX() - cx * 16, 2, panel.getZ() - cz * 16, "north", "test panel"))));
            BuildingSpots.INSTANCE.replaceForTest(next);
            PoliceResponse.resetForTest();
            AlarmPanels.resetForTest();
            CityBuildings.INSTANCE.overrideForTest((x, z) -> Optional.of(building));
            PoliceResponse.spots = (lvl, chunk, random, players) -> Optional.empty();   // nobody needs to arrive: the tests read the dispatch itself
            level.setBlock(panel, UndergroundCity.ALARM_PANEL.get().defaultBlockState().setValue(AlarmPanelBlock.FACING, Direction.NORTH), 3);
        }

        void kits(int count) { thief.getInventory().setItem(0, new ItemStack(UndergroundCity.item("bypass_kit"), count)); }
        int kits() { return thief.getInventory().countItem(UndergroundCity.item("bypass_kit")); }
        void learn(String... skills) { for (String skill : skills) CitizenState.get(thief.server).unlockSkill(thief.getUUID(), skill); }
        AlarmPanelBlock.Light light() { AlarmPanels.refresh(level, building); return level.getBlockState(panel).getValue(AlarmPanelBlock.LIGHT); }
        BuildingBreachEvent opened(boolean quiet) {
            return new BuildingBreachEvent(thief.getUUID(), door, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, LockService.clock(level.getServer()), quiet, thief);
        }
        int heat() { return WorkOrderLedger.get(level.getServer()).heat(thief.getUUID(), "valewood"); }
        long tick() { return level.getServer().getTickCount(); }

        @Override public void close() {
            level.setBlock(panel, Blocks.AIR.defaultBlockState(), 3);
            AlarmPanels.resetForTest();
            PoliceResponse.resetForTest();
            CityBuildings.INSTANCE.overrideForTest(null);
            BuildingSpots.INSTANCE.replaceForTest(plans);
        }
    }

    /** Starts a trace and answers it as a client would: right (or {@code wrong}), after the signal, inside the window. */
    private static void answer(Scene s, boolean right) {
        AlarmPanels.Session session = AlarmPanels.sessionOf(s.thief.getUUID());
        check(session != null, "no trace was started");
        byte[] answer = session.sequence().clone();
        if (!right) answer[0] = (byte) ((answer[0] + 1) & 3);
        AlarmPanels.answer(s.thief, s.panel, answer, session.showUntil() + 5);
    }

    @GameTest(template = "empty")
    public static void thePanelsLightIsTheServersAnswerDarkArmedEntryJammed(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_light0", "technician", NEVER, "commercial")) {
            check(s.light() == AlarmPanelBlock.Light.OFF, "an unwired building's panel must be dark");
        }
        try (Scene s = new Scene(helper, "uc_ap_light", "technician", ALARMED, "commercial")) {
            check(s.light() == AlarmPanelBlock.Light.ARMED, "an armed building's panel must show red");
            PoliceResponse.noticed(s.thief, s.opened(false));
            check(AlarmPanels.inEntry(s.building.key()), "the break-in did not start the entry delay");
            var shown = s.level.getBlockState(s.panel).getValue(AlarmPanelBlock.LIGHT);
            check(shown == AlarmPanelBlock.Light.ENTRY || shown == AlarmPanelBlock.Light.ARMED, "the entry delay shows " + shown);
            s.learn("technician.fundamentals", "technician.municipal_repairs", AlarmPanels.SKILL);
            s.kits(1);
            AlarmPanels.use(s.thief, s.panel);
            answer(s, true);
            check(s.light() == AlarmPanelBlock.Light.JAMMED, "a jammed panel must show blue");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anArmedPanelGivesTheEntryDelayAndItsEndRingsTheAlarm(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_entry", "farmer", ALARMED, "commercial")) {
            PoliceResponse.noticed(s.thief, s.opened(false));
            check(PoliceResponse.dispatches() == 0, "an armed building with a panel rang at once instead of counting down");
            check(AlarmPanels.inEntry(s.building.key()), "no entry delay");
            AlarmPanels.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0, "the delay rang before it ran out");
            AlarmPanels.expireEntryForTest(s.building.key(), s.tick());
            AlarmPanels.pass(s.level.getServer());
            check(!AlarmPanels.inEntry(s.building.key()), "the delay did not end");
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).why.equals("alarm"), "the end of the delay did not ring the alarm: " + PoliceResponse.dispatches());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withoutTheSkillOrAKitThePanelOnlyTellsWhatItIs(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_gate", "technician", ALARMED, "commercial")) {
            s.kits(2);
            AlarmPanels.use(s.thief, s.panel);
            check(!AlarmPanels.hasSession(s.thief.getUUID()) && s.kits() == 2, "an unskilled player started a trace or lost a kit");
            s.learn("technician.fundamentals", "technician.municipal_repairs", AlarmPanels.SKILL);
            s.kits(0);
            AlarmPanels.use(s.thief, s.panel);
            check(!AlarmPanels.hasSession(s.thief.getUUID()), "a trace started without a kit");
            s.kits(2);
            AlarmPanels.use(s.thief, s.panel);
            check(AlarmPanels.hasSession(s.thief.getUUID()) && s.kits() == 1, "the kit must be spent the moment the trace starts: " + s.kits());
            AlarmPanels.use(s.thief, s.panel);
            check(s.kits() == 1, "a second click during a trace spent another kit");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theRightAnswerJamsThePanelAndAJammedBuildingCallsNobody(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_jam", "technician", ALARMED, "commercial")) {
            s.learn("technician.fundamentals", "technician.municipal_repairs", AlarmPanels.SKILL);
            s.kits(1);
            PoliceResponse.noticed(s.thief, s.opened(false));
            AlarmPanels.use(s.thief, s.panel);
            var session = AlarmPanels.sessionOf(s.thief.getUUID());
            check(session.sequence().length == AlarmPanels.BASE_LENGTH, "a commercial panel's signal is " + session.sequence().length + " long");
            int heat = s.heat();
            answer(s, true);
            check(AlarmPanels.jammed(s.level, s.building) && !AlarmPanels.inEntry(s.building.key()), "the right answer did not jam the panel and end the delay");
            AlarmPanels.expireEntryForTest(s.building.key(), s.tick());
            AlarmPanels.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0 && s.heat() == heat, "a jammed panel still rang or raised heat");
            PoliceResponse.noticed(s.thief, s.opened(false));
            check(PoliceResponse.dispatches() == 0 && !AlarmPanels.inEntry(s.building.key()), "a second entry into a jammed building rang or counted down");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWrongEarlyLateOrDistantAnswerRingsTheAlarmAndRaisesHeat(GameTestHelper helper) {
        for (String how : new String[]{"wrong", "early", "late", "distant"}) {
            try (Scene s = new Scene(helper, "uc_ap_" + how, "technician", ALARMED, "commercial")) {
                s.learn("technician.fundamentals", "technician.municipal_repairs", AlarmPanels.SKILL);
                s.kits(1);
                PoliceResponse.noticed(s.thief, s.opened(false));
                int heat = s.heat();
                AlarmPanels.use(s.thief, s.panel);
                var session = AlarmPanels.sessionOf(s.thief.getUUID());
                switch (how) {
                    case "wrong" -> answer(s, false);
                    case "early" -> AlarmPanels.answer(s.thief, s.panel, session.sequence(), session.showUntil() - 1);
                    case "late" -> AlarmPanels.answer(s.thief, s.panel, session.sequence(), session.deadline() + 1);
                    default -> { s.thief.setPos(s.panel.getX() + 20, s.panel.getY(), s.panel.getZ()); AlarmPanels.answer(s.thief, s.panel, session.sequence(), session.showUntil() + 5); }
                }
                check(!AlarmPanels.jammed(s.level, s.building), how + ": the panel was jammed");
                check(PoliceResponse.dispatches() == 1, how + ": the alarm did not ring (" + PoliceResponse.dispatches() + ")");
                check(s.heat() == heat + 1, how + ": heat " + heat + " -> " + s.heat());
                check(!AlarmPanels.inEntry(s.building.key()), how + ": the delay kept counting after the alarm rang");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aHighSecurityPanelIsLongerAndTheSkillsSlowItDownAndGiveMoreTime(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_skill", "technician", ALARMED, "high_security")) {
            s.learn("technician.fundamentals", "technician.municipal_repairs", AlarmPanels.SKILL);
            var citizen = CitizenState.get(s.level.getServer()).find(s.thief.getUUID()).orElseThrow();
            var plain = AlarmPanels.trace(citizen, s.building);
            check(plain.length() == AlarmPanels.BASE_LENGTH + 1, "a high-security panel's signal is " + plain.length() + " long");
            s.learn(AlarmPanels.ANALYSIS, "technician.automation_install", AlarmPanels.SYSTEMS);
            citizen = CitizenState.get(s.level.getServer()).find(s.thief.getUUID()).orElseThrow();
            var skilled = AlarmPanels.trace(citizen, s.building);
            check(skilled.perSymbol() > plain.perSymbol() && skilled.inputTicks() > plain.inputTicks(), "Signal Analysis and Security Systems did not ease the trace: " + plain + " " + skilled);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aThiefWhoLeavesIsNeverRungFor(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_leave", "farmer", ALARMED, "commercial")) {
            PoliceResponse.noticed(s.thief, s.opened(false));
            check(AlarmPanels.inEntry(s.building.key()), "no entry delay");
            new AlarmPanels().loggedOut(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(s.thief));
            AlarmPanels.expireEntryForTest(s.building.key(), s.tick());
            AlarmPanels.pass(s.level.getServer());
            check(!AlarmPanels.inEntry(s.building.key()) && PoliceResponse.dispatches() == 0, "a thief who left was rung for");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBuildingWithoutAPanelInTheWorldRingsAtOnceAsBefore(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_ap_none", "farmer", ALARMED, "commercial")) {
            s.level.setBlock(s.panel, Blocks.AIR.defaultBlockState(), 3);
            PoliceResponse.noticed(s.thief, s.opened(false));
            check(!AlarmPanels.inEntry(s.building.key()) && PoliceResponse.dispatches() == 1, "a building whose panel is not in the world did not ring at once");
        }
        helper.succeed();
    }
}
