package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The start in the city chosen (D-187) through a real client ({@code -PucPlaytest=citystart -PucPtCity=valewood}, a new furnished archipelago world): a new player arrives at the landing,
 * registers on the real first-login screen with real clicks (the city named, a Farmer), and the server moves them once to that city's welcome. The checks read where the player really is
 * (the server's own position of the player, the blocks round them), not the citizen record: inside the chosen city, on solid dry ground under the sky, not in a wall; their own respawn
 * point there; the status display's next step; a death and a real click on Respawn brings them back to their city; after a save, a quit and a reopen they are where they were and nothing
 * moves them again.
 *
 * <p>Scripted, each logged: the time of day (stopped at noon, so the pictures are readable) and the death ({@code /kill}, to test where a citizen respawns).
 */
final class CityStartScenario {
    private CityStartScenario() {}

    private static final List<String> CITIES = List.of("ironhaven", "valewood", "blackwater");   // the order of the registration screen's cards
    private static final class Facts {
        volatile String city;
        volatile BlockPos arrived, landing;
        volatile long placedAt;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "CITYSTART";
        Playtest.keepScreens = true;
        F.city = System.getProperty("uc.playtest.city", "valewood").toLowerCase(Locale.ROOT);
        int card = CITIES.indexOf(F.city);
        if (card < 0) throw new IllegalArgumentException("uc.playtest.city must be one of " + CITIES);
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 2400);
        Playtest.queue("the landing", () -> {
            F.landing = server(() -> me().blockPosition());
            Playtest.log("CITYSTART arrived at the landing " + F.landing.toShortString() + " in " + server(() -> CityRegions.INSTANCE.cityAt(level(), F.landing).orElse("no city")));
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 6000");
            scripted("the time of day was set to noon and the daylight cycle stopped (for readable pictures)");
        });
        until("the first-login registration screen opens by itself", () -> mc().screen instanceof RegistrationScreen, 1200);
        Playtest.wait("let the screen render", 20);
        clickAt("the " + F.city + " card", () -> { Playtest.focusWindow(); return ((RegistrationScreen) mc().screen).cardCentre(card); });
        until("the profession step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 1, 100);
        Playtest.wait("settle", 6);
        clickAt("the Farmer card", () -> ((RegistrationScreen) mc().screen).cardCentre(0));
        until("the confirm step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 2, 100);
        clickAt("CONFIRM", () -> ((RegistrationScreen) mc().screen).confirmCentre());
        until("the screen closes", () -> !(mc().screen instanceof RegistrationScreen), 300);
        Playtest.queue("the citizen", () -> {
            var c = server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).orElse(null));
            if (c == null || !c.city.equals(F.city)) { Playtest.fail("the citizen record is " + (c == null ? "missing" : c.city)); return; }
            pass("1 registered on the real screen as a citizen of " + F.city);
        });
        ThiefScenario.stopIfFailed("registration");
        until("the server has moved the citizen", () -> server(() -> CityArrival.placed(mc().getSingleplayerServer(), me().getUUID()).isPresent()), 2400);
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle on the ground", 40);
        Playtest.queue("where the player really is", () -> {
            var placed = server(() -> CityArrival.placed(mc().getSingleplayerServer(), me().getUUID()).orElse(null));
            BlockPos at = server(() -> me().blockPosition());
            F.arrived = at;
            F.placedAt = placed == null ? -1 : placed.gameTime();
            String region = server(() -> CityRegions.INSTANCE.cityAt(level(), at).orElse(""));
            boolean urban = server(() -> CityRegions.INSTANCE.region(F.city).map(r -> r.distanceSquared(at) <= (double) r.cityRadius() * r.cityRadius()).orElse(false));
            boolean safe = server(() -> CityArrival.standable(level(), at));
            boolean dry = server(() -> !me().isInWater() && !me().isInLava()), clear = server(() -> !me().isInWall()), ground = server(() -> me().onGround());
            BlockPos respawn = server(() -> me().getRespawnPosition());
            double moved = Math.sqrt(at.distSqr(F.landing));
            Playtest.log(String.format(Locale.ROOT, "CITYSTART placed %s (%s); at %s, %.0f blocks from the landing; civic city %s, inside the city's circle %s; standable %s, dry %s, not in a wall %s, on the ground %s; own respawn %s",
                placed, placed == null ? "" : placed.how(), at.toShortString(), moved, region, urban, safe, dry, clear, ground, respawn == null ? "none" : respawn.toShortString()));
            if (placed == null || placed.how().startsWith("failed") || placed.how().startsWith("no ")) { Playtest.fail("the server did not place the citizen: " + placed); return; }
            if (!F.city.equals(region)) { Playtest.fail("the player is in '" + region + "', not in " + F.city); return; }
            if (!F.city.equals("ironhaven") && !urban) { Playtest.fail("the player is not in " + F.city + "'s streets"); return; }
            if (!safe || !dry || !clear) { Playtest.fail("the arrival is not safe ground: standable " + safe + ", dry " + dry + ", not in a wall " + clear); return; }
            // the citizen's own respawn is where they were put (for Ironhaven, who registers at the Row, the Row's gate)
            double limit = placed.how().equals("already there") ? 48 : 3;
            if (respawn == null || respawn.distSqr(at) > limit * limit) { Playtest.fail("the citizen's own respawn point is not the arrival: " + respawn); return; }
            pass(String.format(Locale.ROOT, "2 the server moved the citizen %.0f blocks from the landing into %s (%s), onto dry open ground, and made it their own respawn point", moved, F.city, placed.how()));
        });
        ThiefScenario.stopIfFailed("the arrival");
        shot("1-arrived-" + F.city);
        Playtest.queue("the next step on the status display", () -> {
            var objective = server(() -> Journey.next(me()));
            String title = objective == null ? "(none)" : objective.title();
            Playtest.log("CITYSTART next step: " + title + " -> " + (objective == null || objective.target() == null ? "no place" : objective.targetName() + " at " + objective.target().toShortString()));
            String expected = F.city.equals("ironhaven") ? "Meet your employer" : Journey.FIND_WORK;
            if (!expected.equals(title)) Playtest.fail("the next step is '" + title + "', not '" + expected + "'");
            else if (!F.city.equals("ironhaven") && objective.target() != null && objective.target().distSqr(F.arrived) > 40 * 40) Playtest.fail("the step points " + Math.round(Math.sqrt(objective.target().distSqr(F.arrived))) + " blocks away from where the citizen arrived");
            else pass("3 the status display's next step is '" + title + "'" + (objective.target() == null ? "" : ", pointing at " + objective.targetName()));
        });
        Playtest.wait("the display draws", 30);
        shot("2-next-step");
        // a death: back to the city, not the landing
        Playtest.queue("die", () -> { scripted("the player was killed with /kill to see where a citizen respawns"); Playtest.deathExpected = true; Playtest.command("kill @s"); });
        until("the death screen", () -> mc().screen instanceof DeathScreen, 400);
        Playtest.wait("the respawn button wakes (the death screen holds it a moment)", 40);
        JourneyScenario.click("Respawn", true);
        until("respawned", () -> mc().player != null && mc().player.isAlive() && !(mc().screen instanceof DeathScreen), 400);
        Playtest.queue("deaths count again", () -> Playtest.deathExpected = false);
        until("the ground", Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("where the citizen respawned", () -> {
            BlockPos at = server(() -> me().blockPosition());
            String region = server(() -> CityRegions.INSTANCE.cityAt(level(), at).orElse(""));
            double fromArrival = Math.sqrt(at.distSqr(F.arrived));
            Playtest.log(String.format(Locale.ROOT, "CITYSTART respawned at %s in %s, %.1f blocks from the arrival", at.toShortString(), region, fromArrival));
            BlockPos own = server(() -> me().getRespawnPosition());
            double fromOwn = own == null ? 999 : Math.sqrt(at.distSqr(own));
            if (!F.city.equals(region) || fromOwn > 6) Playtest.fail("the citizen respawned at " + at.toShortString() + " (" + region + "), not at their own respawn point " + own + " in " + F.city);
            else pass("4 a death and a real click on Respawn bring the citizen back to " + F.city + ", " + Math.round(fromArrival) + " blocks from where they arrived");
        });
        // a reconnect never moves anybody
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle after the reopen", 100);
        Playtest.queue("after the reopen", () -> {
            BlockPos at = server(() -> me().blockPosition());
            var placed = server(() -> CityArrival.placed(mc().getSingleplayerServer(), me().getUUID()).orElse(null));
            boolean pending = server(() -> CityArrival.pending(mc().getSingleplayerServer(), me().getUUID()));
            boolean screen = mc().screen instanceof RegistrationScreen;
            Playtest.log("CITYSTART after the reopen: at " + at.toShortString() + ", placed " + placed + ", pending " + pending + ", registration screen " + screen);
            if (placed == null || placed.gameTime() != F.placedAt || pending || screen || Math.sqrt(at.distSqr(F.arrived)) > 6) Playtest.fail("the reopen moved the citizen, repeated the move or asked to register again");
            else pass("5 saved, left and reopened: the citizen is still in " + F.city + " where they were, the move was not repeated and nobody asked them to register again");
        });
        shot("3-after-reopen");
        Playtest.queue("summary", ThiefScenario::summary);
    }
}
