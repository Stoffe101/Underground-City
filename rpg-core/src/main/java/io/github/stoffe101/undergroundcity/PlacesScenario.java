package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * Property intelligence (D-157) through a real client ({@code -PucPlaytest=places}, a new furnished archipelago world): the survey of every city's buildings (that each city has easy, middling
 * and hard targets), the card under the crosshair at the street doors of buildings of different tiers, casing with the Inspect key as a beginner, with Casing and Routines and as an expert, how
 * often the client asks while the player looks round a street, the Places screen, tracking a place with the status display's arrow, and the book after a save, quit and reopen.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the clock, the travel to Valewood, standing at each door, the Thief skills and XP of a developed thief and of an expert, and a lockpick.
 */
final class PlacesScenario {
    private PlacesScenario() {}

    private static final class Facts {
        final List<DoorWalk.Spot> doors = new ArrayList<>();
        final List<Integer> tiers = new ArrayList<>();
        final List<Long> keys = new ArrayList<>();
        volatile int asksBefore;
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "PLACES";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        survey();
        // the travel leaves the player in spectator high over the city; standing at the first door puts them on the street in survival (run 2 fell sixty blocks when switched here)
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 5);
        chooseDoors();
        Playtest.queue("stop if no doors", () -> { if (F.doors.size() < 2) { Playtest.log("PLACES stopping: no doors to look at"); ThiefScenario.summary(); Playtest.finish(); } });
        glance(0, "2-glance-first");
        glance(1, "3-glance-second");
        caseAt(1, 0, "4-casing-beginner");
        Playtest.queue("a developed thief (scripted)", () -> {
            scripted("Casing and Know Their Routines were given, and a lockpick");
            server(() -> {
                var citizens = CitizenState.get(mc().getSingleplayerServer());
                citizens.unlockSkill(me().getUUID(), "thief.casing");
                citizens.unlockSkill(me().getUUID(), "thief.routines");
                me().getInventory().add(new ItemStack(UndergroundCity.item("lockpick")));
                return null;
            });
        });
        caseAt(1, 2, "5-casing-developed");
        Playtest.queue("an expert (scripted)", () -> {
            scripted("Camera Sense and the Thief XP of level " + Casing.EXPERT_LEVEL + " were given");
            server(() -> {
                var citizens = CitizenState.get(mc().getSingleplayerServer());
                citizens.unlockSkill(me().getUUID(), "thief.camera_sense");
                int need = 80 * (Casing.EXPERT_LEVEL - 1) * (Casing.EXPERT_LEVEL - 1);
                CitizenState.Citizen c = citizens.find(me().getUUID()).orElseThrow();
                if (c.thiefXp() < need) citizens.addThiefXp(me().getUUID(), need - c.thiefXp());
                return null;
            });
        });
        caseAt(1, 3, "6-casing-expert");
        lookAround();
        places();
        reopen();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the survey

    private static void survey() {
        Playtest.queue("survey every city's buildings", () -> Playtest.command("uc_places survey"));
        until("the survey has finished (a slice per tick)", () -> server(() -> !PropertyIntel.surveying()), 24000);
        Playtest.queue("what the survey found", () -> {
            String json = server(() -> {
                try { return Files.readString(level().getServer().getWorldPath(LevelResource.ROOT).resolve("underground_city").resolve("property-survey.json"), StandardCharsets.UTF_8); }
                catch (Exception error) { return ""; }
            });
            if (json.isEmpty()) { Playtest.fail("the survey wrote nothing"); return; }
            var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            Playtest.log("PLACES survey: " + root.get("buildings") + " buildings in " + root.get("chunks") + " chunks, " + root.get("work_ms") + " ms of work, worst slice " + root.get("worst_slice_ms") + " ms");
            List<String> missing = new ArrayList<>();
            for (var city : root.getAsJsonObject("cities").entrySet()) {
                var c = city.getValue().getAsJsonObject();
                Map<Integer, Integer> tiers = new TreeMap<>();
                for (int t = 0; t <= 6; t++) tiers.put(t, c.has("tier:" + t) ? c.get("tier:" + t).getAsInt() : 0);
                int easy = tiers.get(1) + tiers.get(2), middle = tiers.get(3) + tiers.get(4), hard = tiers.get(5) + tiers.get(6);
                Playtest.log(String.format(Locale.ROOT, "PLACES survey %s: %d buildings, %d targets; tiers %s; easy %d, middling %d, hard %d", city.getKey(),
                    c.has("buildings") ? c.get("buildings").getAsInt() : 0, c.has("targets") ? c.get("targets").getAsInt() : 0, tiers, easy, middle, hard));
                if (easy == 0 || middle == 0 || hard == 0) missing.add(city.getKey() + " (easy " + easy + ", middling " + middle + ", hard " + hard + ")");
            }
            if (!missing.isEmpty()) Playtest.fail("a city lacks a range of targets: " + missing);
            else pass("2 the survey of every city: each has easy (I-II), middling (III-IV) and hard (V-VI) targets");
        });
    }

    // ------------------------------------------------------------------------------------------------ doors

    /** Three buildings near the player with usable street doors, of as many different tiers as there are. */
    private static void chooseDoors() {
        Playtest.queue("choose doors of different tiers near the middle of Valewood", () -> {
            server(() -> {
                ServerLevel level = level();
                BlockPos here = me().blockPosition();
                Map<Integer, DoorWalk.Spot> byTier = new TreeMap<>();
                Map<Integer, Long> keys = new TreeMap<>();
                for (int r = 0; r <= 6 && byTier.size() < 3; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    var b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                    if (b == null || !b.locked() || Thievery.tier(b) <= 0 || byTier.containsKey(Thievery.tier(b)) || BuildingGuard.protectedAt(level, new BlockPos(cx * 16 + 8, b.streetY(), cz * 16 + 8)).isEmpty()) continue;
                    if (CityHomes.heldUnits(level.getServer(), b) > 0) continue;
                    DoorWalk.Spot spot = ThiefScenario.streetDoor(level, b);
                    if (spot == null) continue;
                    byTier.put(Thievery.tier(b), spot);
                    keys.put(Thievery.tier(b), b.key());
                }
                for (var e : byTier.entrySet()) { F.doors.add(e.getValue()); F.tiers.add(e.getKey()); F.keys.add(keys.get(e.getKey())); }
                return null;
            });
            Playtest.log("PLACES doors chosen: tiers " + F.tiers + " at " + F.doors.stream().map(d -> d.door().toShortString()).toList());
            if (F.doors.size() < 2) Playtest.fail("fewer than two buildings of different tiers with a usable street door near the middle of Valewood");
        });
    }

    /** Stands at door {@code i}, looks at it and waits for the card under the crosshair. */
    private static void glance(int i, String shotName) {
        Playtest.queue("stand at door " + (i + 1) + " (scripted)", () -> {
            DoorWalk.Spot spot = F.doors.get(i);
            scripted("the player was put on the street at door " + (i + 1) + " (" + spot.door().toShortString() + ", tier " + F.tiers.get(i) + ")");
            ThiefScenario.standAt(spot);
        }, () -> mc().player.position().distanceTo(F.doors.get(i).stand()) < 0.8, 200);
        Playtest.wait("settle", 10);
        Playtest.queue("look at the door", () -> JourneyScenario.look(Vec3.atCenterOf(F.doors.get(i).door().above())), () -> ClientPlaces.doorInSight(mc()) != null, 60);
        until("the card under the crosshair", () -> { JourneyScenario.look(Vec3.atCenterOf(F.doors.get(i).door().above())); return ClientPlaces.current() != null; }, 200);
        Playtest.wait("draw", 6);
        shot(shotName);
        Playtest.queue("the card says the tier", () -> {
            PropertyIntel.Peek p = ClientPlaces.current();
            Playtest.log(String.format(Locale.ROOT, "PLACES glance at door %d: %s %s, %s, tier %d (%s), access %d, layer %d", i + 1, p.status(), p.label(), p.address(), p.tier(), Thievery.tierWord(p.tier()), p.access(), p.layer()));
            boolean target = p.status() == PropertyIntel.Status.TARGET || p.status() == PropertyIntel.Status.OPEN;
            if (!target || p.tier() != F.tiers.get(i)) Playtest.fail("the card at door " + (i + 1) + " says " + p.status() + " tier " + p.tier() + ", the building is tier " + F.tiers.get(i));
            else pass((i + 3) + " the card under the crosshair at a tier-" + p.tier() + " " + p.label() + " says \"" + Thievery.tierWord(p.tier()) + " " + PropertyIntel.roman(p.tier()) + " / VI\" and \"" + PropertyIntel.accessWords(p.access()) + "\"");
        });
    }

    /** Presses the Inspect key at door {@code i} and checks the casing layer. */
    private static void caseAt(int i, int layer, String shotName) {
        Playtest.queue("look at door " + (i + 1), () -> JourneyScenario.look(Vec3.atCenterOf(F.doors.get(i).door().above())), () -> ClientPlaces.doorInSight(mc()) != null, 60);
        Playtest.queue("press the Inspect key", () -> { Playtest.focusWindow(); Playtest.tap("i"); });
        until("the casing card", () -> mc().screen instanceof CasingScreen, 200);
        Playtest.wait("draw", 10);
        shot(shotName);
        Playtest.queue("the casing's layer", () -> {
            Casing.Report r = mc().screen instanceof CasingScreen s ? s.report() : null;
            if (r == null) { Playtest.fail("no casing card"); return; }
            Playtest.log("PLACES casing (layer " + r.skill() + "): " + String.join(" | ", Casing.lines(r)));
            boolean ok = r.skill() == layer && r.tier() == F.tiers.get(i) && (layer == 0 ? r.risk().isEmpty() : !r.risk().isEmpty()) && (layer < 3 ? r.riskPercent() < 0 : r.riskPercent() >= 0 && r.searchable() >= 0);
            if (!ok) Playtest.fail("the casing at layer " + layer + " is wrong: " + Casing.lines(r));
            else pass(switch (layer) {
                case 0 -> "6 the Inspect key cases a door as a beginner: what it is, its tier and its lock, and no risk";
                case 2 -> "7 with Casing and Routines the card adds the alarm, cameras, people in, when it empties, the safe and the risk now (" + r.risk() + ": " + r.reasons() + ")";
                default -> "8 an expert reads the risk as a number (about " + r.riskPercent() + "%), the police's time (" + r.policeSeconds() + " s) and " + r.searchable() + " places worth searching";
            });
        });
        JourneyScenario.closeScreen("close the card");
    }

    // ------------------------------------------------------------------------------------------------ looking round

    /** Turns slowly round the street for ten seconds: the client asks only about doors it rests on, a few times at most. */
    private static void lookAround() {
        final int[] t = {0};
        Playtest.queue("look round the street for ten seconds", () -> { t[0] = 0; F.asksBefore = ClientPlaces.asks(); PropertyIntel.peeks = 0; }, () -> {
            var p = mc().player;
            p.setYRot(p.getYRot() + 3.6f);
            p.yHeadRot = p.getYRot();
            p.setXRot(5f);
            return ++t[0] >= 200;
        }, 220);
        Playtest.queue("how often the client asked", () -> {
            int asked = ClientPlaces.asks() - F.asksBefore;
            Playtest.log("PLACES looking round for 10 s: the client asked " + asked + " times, the server answered " + PropertyIntel.peeks);
            if (asked > 20) Playtest.fail("the client asked " + asked + " times in ten seconds of looking round");
            else pass("9 ten seconds of looking round a street: the client asked " + asked + " times (only about doors it rested on)");
        });
    }

    // ------------------------------------------------------------------------------------------------ the book

    private static void places() {
        Playtest.queue("press the Places key", () -> { Playtest.focusWindow(); Playtest.tap("j"); });
        until("the Places screen with the book", () -> mc().screen instanceof PlacesScreen && ClientPlaces.rows().size() >= 2, 200);
        Playtest.wait("draw", 10);
        shot("7-places");
        Playtest.queue("the book", () -> {
            List<PropertyIntel.Row> rows = ClientPlaces.rows();
            Playtest.log("PLACES book: " + rows.size() + " places: " + rows.stream().map(r -> r.entry().address() + " tier " + r.entry().tier() + " layer " + r.entry().layer()).toList());
            boolean cased = rows.stream().anyMatch(r -> r.entry().key() == F.keys.get(1) && r.entry().layer() == 3);
            boolean seen = rows.stream().anyMatch(r -> r.entry().key() == F.keys.get(0));
            if (!cased || !seen) Playtest.fail("the book does not hold the places glanced at and cased");
            else pass("10 the Places screen lists the places glanced at and cased, with what was learned");
        });
        JourneyScenario.click("Track", true);
        until("tracking", () -> ClientPlaces.tracked() != null && mc().screen == null, 100);
        Playtest.queue("step back from the door (scripted)", () -> {
            scripted("the player was moved 30 blocks along the street to see the arrow");
            server(() -> { var p = me(); p.teleportTo(p.getX() + 30, p.getY() + 1, p.getZ()); return null; });
        });
        until("the ground", Scenarios::groundBelowPlayer, 400);
        Playtest.wait("draw", 20);
        shot("8-tracking");
        Playtest.queue("the arrow", () -> {
            var o = ClientPlaces.objectiveOr(null);
            if (o == null || o.target() == null) Playtest.fail("no tracking card");
            else pass("11 Track points the status display's next-step card at the place (" + o.targetName() + ")");
        });
    }

    private static void reopen() {
        final int[] before = {0};
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            before[0] = server(() -> PropertyIntel.book(mc().getSingleplayerServer()).of(mc().player.getUUID()).size());
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            Playtest.log("PLACES leaving world " + folder + " with " + before[0] + " places");
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("press the Places key again", () -> { Playtest.focusWindow(); Playtest.tap("j"); });
        until("the Places screen", () -> mc().screen instanceof PlacesScreen && !ClientPlaces.rows().isEmpty(), 200);
        Playtest.queue("the book after the reopen", () -> {
            int after = ClientPlaces.rows().size();
            Playtest.log("PLACES after the reopen: " + after + " places (before " + before[0] + ")");
            if (after != before[0]) Playtest.fail("the book changed across the reopen: " + before[0] + " before, " + after + " after");
            else pass("12 after a save, quit and reopen the book holds the same " + after + " places");
        });
        JourneyScenario.closeScreen("close");
    }
}
