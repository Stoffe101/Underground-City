package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The twelfth pass's police acceptance cases through one real client ({@code -PucPlaytest=policecases}, a new furnished archipelago world; the city is {@code -PucPtCity}, Valewood by
 * default), with the police trace on so every case reports the path it took:
 *
 * <ol>
 * <li>a witnessed blow, a real dispatch, officers who walk in and reach a citizen who stands still, an arrest for assault at the city's fine (cases 1, 2, 3);</li>
 * <li>a quiet burglary of an empty house whose alarm is not armed, worked only when nobody is in sight: nobody reports it (case 7), and the thief carries marked goods;</li>
 * <li>a second offence while carrying them: an arrest that takes the marked goods and leaves the rest, at the fine raised by the first conviction (case 5);</li>
 * <li>a third offence, and the thief sprints away from the first officer who sees them, with the real keys: a chase of a moving player that ends in an escape or an arrest (cases 4, 6);</li>
 * <li>a save, a quit and a reopen while wanted: the warrant is still there, nobody was punished while away, and the player is told (case 9);</li>
 * <li>walking up to an officer on the beat while wanted: recognised and arrested on the warrant.</li>
 * </ol>
 *
 * Case 8 (a second, innocent player beside the offender) needs two clients and is a GameTest and the duo session's; case 10 (the owner's buildings) is this scenario run privately with
 * them. Scripted, each logged: the clock, the travel to the city, the credits (operator command), standing beside a person to strike, the house's choice, the lockpicks, the steps between
 * rooms of the search, the bread put in the pockets.
 */
final class PoliceCasesScenario {
    private PoliceCasesScenario() {}

    static final String CITY = System.getProperty("uc.playtest.city", "valewood");

    private static final class Facts {
        volatile double walletBefore;
        volatile long arrestsBefore, escapesBefore, warrantArrestsBefore, traceMark, warningsBefore, assaultsBefore, raisedBefore;
        volatile int priorsBefore, stolenBefore, breadBefore;
        volatile boolean wantedAfterBurglary, seen, warrantBeforeQuit;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "CASES";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "0 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        Playtest.queue("300 credits with the operator command, and the police trace on", () -> {
            Playtest.command("uc admin credits " + mc().player.getGameProfile().getName() + " add 300");
            Playtest.command("uc_police trace on");
            scripted("300 credits were added with /uc admin credits (so fines can be paid) and the police trace switched on with /uc_police trace on (diagnostics only)");
        });
        Playtest.wait("the commands", 10);
        ThiefScenario.travel("to the middle of " + CITY, () -> ThiefScenario.centre(CITY), 4);
        Playtest.queue("down to the street", () -> {
            BlockPos at = server(() -> CityArrival.safeStand(level(), BlockPos.containing(me().getX(), 72, me().getZ()), 16, 12, 40).orElse(null));
            if (at == null) { Playtest.fail("no street ground near the middle of " + CITY); return; }
            scripted("the player was set down on the street at " + at.toShortString() + " in survival mode");
            mc().getSingleplayerServer().execute(() -> {
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                me().setDeltaMovement(Vec3.ZERO);
            });
        });
        ThiefScenario.stopIfFailed("the street");
        Playtest.wait("people come out", 300);

        Playtest.queue("bread in the pockets (lawful goods an arrest must leave)", () -> {
            mc().getSingleplayerServer().execute(() -> me().getInventory().add(new ItemStack(Items.BREAD, 4)));
            scripted("four bread were put in the pockets: lawful goods an arrest must not take");
        });
        Playtest.wait("settle", 10);
        before("A");
        strikeUntilReported("A");
        awaitArrest("A", "1-2-3 a witnessed blow");
        ThiefScenario.stopIfFailed("the first arrest");
        // Blackwater's clubs keep their staff until three (D-185): its shops and clubs are empty by five
        final boolean late = CITY.equals("blackwater");
        Playtest.queue("the clock: " + (late ? "05:00" : "03:00") + " (the streets empty)", () -> { Playtest.command(late ? "time set 23000" : "time set 21000"); scripted("the time was set to " + (late ? "05:00" : "03:00")); });
        Playtest.wait("the street empties", 400);
        quietBurglary();
        // a burglary somebody noticed brings the police to the door: stand there and be arrested with the marked goods (case 5); one nobody noticed leaves the goods for the next arrest
        awaitArrest("B", "5 an arrest after a noticed burglary");
        Playtest.queue("the clock: 13:00 again", () -> { Playtest.command("time set 7000"); scripted("the time was set back to 13:00"); });
        Playtest.wait("people come out again", 400);
        before("B2");
        strikeUntilReported("B2", () -> ThiefScenario.stolen()[0] > 0);
        awaitArrest("B2", "5 a second offence carrying marked goods");
        chase();
        reopen();
        recognised();
        Playtest.queue("summary", () -> {
            Playtest.log("CASES " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status));
            ThiefScenario.summary();
        });
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The honest bread carried: bread taken in a burglary is marked stolen and is the arrest's to take (police cases run 9 found three stolen loaves counted as honest ones). */
    private static int bread() {
        return server(() -> {
            int n = 0;
            for (ItemStack stack : me().getInventory().items) if (stack.is(Items.BREAD) && !StolenGoods.is(stack)) n += stack.getCount();
            return n;
        });
    }

    private static void markTrace() { F.traceMark = server(PoliceTrace::count); }

    /** Every police trace line about this player since the mark, into the log: the path a case really took. */
    private static void logTrace(String label) {
        String name = mc().player.getGameProfile().getName();
        for (String line : server(() -> PoliceTrace.since(F.traceMark))) if (line.startsWith(name + " ")) Playtest.log("CASES trace " + label + ": " + line);
    }

    private static WorkOrderLedger.Warrant warrant() { return server(() -> WorkOrderLedger.get(level().getServer()).warrant(me().getUUID(), CITY)); }

    /** The dispatch out for this player, or null. Server thread. */
    private static PoliceResponse.Dispatch dispatch() {
        for (int i = 0; i < PoliceResponse.dispatches(); i++) { var d = PoliceResponse.dispatchAt(i); if (d.burglar.equals(me().getUUID())) return d; }
        return null;
    }

    /** Waits, standing still, until an officer has the player in sight or the response is over. */
    private static void untilSeen(int seconds) {
        until("an officer arrives and sees the player", () -> server(() -> { var d = dispatch(); return d == null || d.arrived && d.lastSeen != null && "pursuit".equals(d.state); }), 20 * seconds);
    }

    // ------------------------------------------------------------------------------------------------ cases 1, 2, 3 and 5: a witnessed blow, the arrest

    /** The state before a case: wallet, priors, marked goods and bread carried, the counters. */
    private static void before(String label) {
        Playtest.queue(label + ": before", () -> {
            markTrace();
            F.arrestsBefore = server(() -> PoliceResponse.arrests);
            F.walletBefore = server(() -> QShopEconomy.balance(me()));
            F.priorsBefore = server(() -> WorkOrderLedger.get(level().getServer()).priors(me().getUUID(), CITY));
            F.stolenBefore = ThiefScenario.stolen()[0];
            F.breadBefore = bread();
            F.warningsBefore = server(() -> CrimeWatch.warnings);
            F.assaultsBefore = server(() -> CrimeWatch.assaults);
            F.raisedBefore = server(() -> PoliceResponse.raised);
            Playtest.log(String.format(Locale.ROOT, "CASES %s before: wallet %.0f, priors %d, carrying %d marked unit(s) and %d bread; %s", label, F.walletBefore, F.priorsBefore, F.stolenBefore, F.breadBefore, server(PoliceResponse::status)));
        });
    }

    private static void strikeUntilReported(String label) { strikeUntilReported(label, () -> true); }

    /**
     * A blow a passer-by can see, tried up to three times until the server has counted it (run 1's third blow was aimed on the client while the person walked off behind a wall,
     * and the server never had a blow to judge). Skipped altogether when {@code when} says no.
     */
    private static void strikeUntilReported(String label, java.util.function.BooleanSupplier when) {
        final boolean[] go = {false};
        Playtest.queue(label + ": strike?", () -> { go[0] = when.getAsBoolean(); if (!go[0]) Playtest.log("CASES " + label + ": skipped"); });
        for (int attempt = 1; attempt <= 3; attempt++) {
            final int n = attempt;
            PoliceScenario.strike(label + ": the blow, try " + n, true, 30, () -> go[0] && server(() -> CrimeWatch.assaults) <= F.assaultsBefore);
        }
        Playtest.queue(label + ": the blow counted?", () -> {
            if (!go[0]) return;
            long counted = server(() -> CrimeWatch.assaults) - F.assaultsBefore;
            Playtest.log("CASES " + label + ": blows the server counted " + counted);
            if (counted <= 0) Playtest.fail(label + ": three blows never reached the server");
        });
        shot(label + "-1-blow");
    }

    /** Stands still until officers arrive and arrest (or the response ends). Checks the fine (raised by priors) and that marked goods carried are taken and the bread stays. */
    private static void awaitArrest(String label, String step) {
        final int[] t = {0};
        Playtest.queue(label + ": stand still until the police arrive and arrest (or nothing is out)", () -> t[0] = 0, () -> {
            t[0]++;
            if (t[0] % 400 == 0) Playtest.log("CASES " + label + " waiting " + t[0] / 20 + " s: " + server(PoliceResponse::status));
            return server(() -> PoliceResponse.arrests) > F.arrestsBefore || (!server(() -> PoliceResponse.wanted(me().getUUID())) && t[0] > 100);
        }, 20 * 300);
        Playtest.wait("draw", 10);
        shot(label + "-2-arrest");
        Playtest.queue(label + ": the arrest", () -> {
            logTrace(label);
            boolean arrested = server(() -> PoliceResponse.arrests) > F.arrestsBefore;
            double wallet = server(() -> QShopEconomy.balance(me()));
            String offence = lastArrestOffence();
            int base = offence == null ? 0 : CityLaws.INSTANCE.offence(CITY, offence).fine();
            int expected = (int) Math.min(F.walletBefore, LawMath.escalatedFine(base, F.priorsBefore));
            int stolenAfter = ThiefScenario.stolen()[0], breadAfter = bread();
            int seized = seizedAtLastArrest();
            Playtest.log(String.format(Locale.ROOT, "CASES %s arrest %s for %s: wallet %.0f -> %.0f (fine %d, priors %d, expected %d); marked goods %d -> %d; bread %d -> %d; priors now %d",
                label, arrested, offence, F.walletBefore, wallet, base, F.priorsBefore, expected, F.stolenBefore, stolenAfter, F.breadBefore, breadAfter,
                server(() -> WorkOrderLedger.get(level().getServer()).priors(me().getUUID(), CITY))));
            if (!arrested && server(() -> CrimeWatch.warnings) > F.warningsBefore && F.priorsBefore == 0) {
                pass("3 a first minor offence in front of an officer on the beat, with a clean record: a warning, no arrest (" + label + ")");
                return;
            }
            if (!arrested) {
                // nothing raised since the baseline (no report, no alarm, no patrol): nothing was out for the player, even with marked goods from a burglary nobody saw (police cases run 9)
                if (server(() -> PoliceResponse.raised) <= F.raisedBefore && !label.equals("A")) { Playtest.log("CASES " + label + ": nothing was out for the player"); return; }
                Playtest.fail(label + ": the citizen who stood still was not arrested: " + server(PoliceResponse::status));
                return;
            }
            if (Math.abs(F.walletBefore - wallet - expected) > 0.5) { Playtest.fail(label + ": the fine was " + (F.walletBefore - wallet) + ", not " + expected); return; }
            if ((F.stolenBefore > 0 || seized > 0) && stolenAfter != 0) { Playtest.fail(label + ": the marked goods were not all taken (" + F.stolenBefore + " -> " + stolenAfter + ")"); return; }
            if (breadAfter != F.breadBefore) { Playtest.fail(label + ": lawful goods were taken (bread " + F.breadBefore + " -> " + breadAfter + ")"); return; }
            pass(step + ": arrested for " + offence + " by officers who walked in, fined " + expected + (F.priorsBefore > 0 ? " (" + base + " raised by " + F.priorsBefore + " prior conviction(s))" : "")
                + (seized > 0 ? "; " + seized + " stack(s) of marked goods taken, none left" : "") + ", " + breadAfter + " bread kept");
        });
    }

    /** The stacks the latest arrest since the mark took, from its trace line, or 0. */
    private static int seizedAtLastArrest() {
        return server(() -> {
            int found = 0;
            for (String line : PoliceTrace.since(F.traceMark)) {
                int at = line.indexOf(" stack(s) seized");
                if (line.contains(" arrest: ") && at > 0) {
                    int from = line.lastIndexOf(' ', at - 1);
                    try { found = Integer.parseInt(line.substring(from + 1, at)); } catch (NumberFormatException ignored) { }
                }
            }
            return found;
        });
    }

    /** The offence of the player's latest arrest, from its trace line. */
    private static String lastArrestOffence() {
        return server(() -> {
            for (String line : PoliceTrace.since(F.traceMark)) {
                int at = line.indexOf(" arrest: ");
                if (at < 0) continue;
                int f = line.indexOf(" for ", at);
                if (f < 0) continue;
                String rest = line.substring(f + 5);
                int end = rest.indexOf(' ');
                String id = (end < 0 ? rest : rest.substring(0, end)).replace(":", "");
                return id;
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ case 7: a burglary nobody sees

    private static void quietBurglary() {
        // whatever the city's middle has: Valewood has houses, Ironhaven's middle flat blocks, storefronts, warehouses and workshops (police cases run 6), Blackwater's towers, clubs
        // and storefronts; the chooser keeps only a lock plain picks can try (a club's needs a Pro Pick Set)
        Playtest.queue("what to burgle in " + CITY, () -> {
            ThiefScenario.targets = java.util.Set.copyOf(CityBuildings.INSTANCE.classes().keySet());
            Playtest.log("CASES burglary targets in " + CITY + ": " + ThiefScenario.targets);
        });
        ThiefScenario.chooseHouse();
        ThiefScenario.stopIfFailed("finding a house");
        ThiefScenario.prepareAtDoor();
        before("B");   // a noticed burglary's arrest may come while the thief is still inside: the baseline is taken before the pick
        ThiefScenario.waitForClearStreet(CITY);
        // a careful thief also waits for the passers-by to go: nobody within 24 blocks who can see the door
        final int[] t = {0};
        Playtest.queue("nobody in the street who can see the door", () -> t[0] = 0, () -> {
            if (t[0]++ % 20 != 0) return false;
            long watching = server(() -> {
                ServerLevel level = level();
                return level.getEntitiesOfClass(TownspersonEntity.class, new AABB(me().blockPosition()).inflate(24), p -> p.isAlive() && p.hasLineOfSight(me())).stream().count();
            });
            if (watching > 0 && t[0] % 400 == 1) Playtest.log("CASES " + watching + " person(s) in sight of the door; the thief waits");
            return watching == 0 || t[0] > 20 * 180;
        }, 20 * 200);
        Playtest.queue("the street before the pick", () -> { markTrace(); Playtest.log("CASES before the pick: " + server(CrimeWatch::status)); });
        ThiefScenario.pickTheDoor();
        ThiefScenario.stopIfFailed("the door");
        ThiefScenario.search();
        Playtest.wait("anything heard", 60);
        Playtest.queue("7: was it noticed?", () -> {
            logTrace("burglary");
            boolean wanted = server(() -> PoliceResponse.wanted(me().getUUID()));
            WorkOrderLedger.Warrant w = warrant();
            F.wantedAfterBurglary = wanted;
            Playtest.log("CASES after the burglary: wanted " + wanted + ", warrant " + (w == null ? "none" : w.offence()) + "; " + server(CrimeWatch::status) + "; " + server(PoliceResponse::status));
            if (!wanted && w == null) pass("7 a burglary nobody saw stayed undetected: no report, no dispatch, no warrant, with " + ThiefScenario.stolen()[0] + " marked unit(s) carried away");
            else Playtest.log("CASES the burglary was noticed (case 7 not shown in this run; see the trace above)");
        });
        // whatever came of it, out to the street; a response already out is met standing still at the door (awaitArrest)
        ThiefScenario.walkOrTeleport("out of the house to the street", () -> ThiefScenario.door().stand(), 1.5, 900);
    }

    // ------------------------------------------------------------------------------------------------ cases 4 and 6: a chase

    private static void chase() {
        Playtest.queue("C: before", () -> {
            markTrace();
            F.escapesBefore = server(() -> PoliceResponse.escapes);
            F.arrestsBefore = server(() -> PoliceResponse.arrests);
        });
        before("C");
        strikeUntilReported("C");
        untilSeen(300);
        Playtest.queue("C: seen?", () -> {
            F.seen = server(() -> { var d = dispatch(); return d != null && d.arrived && d.lastSeen != null; });
            Playtest.log("CASES chase: seen " + F.seen + "; " + server(PoliceResponse::status));
        });
        shot("C-1-seen");
        // run: away from the nearest officer, 180 blocks, sprinting with the real keys and no teleport if the walker sticks
        final PathWalker[] walker = new PathWalker[1];
        final Vec3[] goal = new Vec3[1];
        final int[] ticks = {0};
        Playtest.queue("C: sprint away from the officer who sees them", () -> {
            ticks[0] = 0;
            goal[0] = server(() -> {
                ServerLevel level = level();
                Vec3 me = me().position();
                TownspersonEntity nearest = null;
                for (TownspersonEntity o : level.getEntitiesOfClass(TownspersonEntity.class, new AABB(me().blockPosition()).inflate(64), TownspersonEntity::police))
                    if (nearest == null || o.distanceToSqr(me) < nearest.distanceToSqr(me)) nearest = o;
                Vec3 dir = nearest == null ? new Vec3(1, 0, 0) : me.subtract(nearest.position()).multiply(1, 0, 1);
                if (dir.lengthSqr() < 1e-4) dir = new Vec3(1, 0, 0);
                Vec3 far = me.add(dir.normalize().scale(180));
                return Vec3.atBottomCenterOf(CityArrival.safeStand(level, BlockPos.containing(far), 16, 10, 12).orElse(BlockPos.containing(far)));
            });
            Playtest.log("CASES running to " + BlockPos.containing(goal[0]).toShortString());
            walker[0] = new PathWalker("the run", () -> goal[0], 3.0).sprinting();
        }, () -> {
            walker[0].tick();
            ticks[0]++;
            boolean over = !server(() -> PoliceResponse.wanted(me().getUUID()));
            if (over || ticks[0] > 20 * 120) { walker[0].stop(); return true; }
            return walker[0].done();
        }, 20 * 125);
        Playtest.queue("C: the run", () -> Playtest.log("CASES run: " + (walker[0] == null ? "none" : walker[0].summary()) + "; at " + mc().player.blockPosition().toShortString()));
        until("C: the response ends (an escape or an arrest)", () -> !server(() -> PoliceResponse.wanted(me().getUUID())), 20 * 180);
        Playtest.wait("draw", 10);
        shot("C-2-after");
        Playtest.queue("C: how it ended", () -> {
            logTrace("chase");
            long escapes = server(() -> PoliceResponse.escapes) - F.escapesBefore, arrests = server(() -> PoliceResponse.arrests) - F.arrestsBefore;
            WorkOrderLedger.Warrant w = warrant();
            Playtest.log("CASES chase over: escapes " + escapes + ", arrests " + arrests + ", warrant " + (w == null ? "none" : w.offence() + " until " + w.expires()) + "; " + server(PoliceResponse::status));
            if (!F.seen) { Playtest.fail("no officer saw the player before the run"); return; }
            if (escapes > 0) {
                pass("4 a real chase of a sprinting player through the streets, and 6 an escape" + (w != null ? "; the warrant stands" : ""));
                if (w == null) Playtest.fail("an escape from a witnessed assault left no warrant");
            } else if (arrests > 0) pass("4 a real chase: the officers caught a moving player (no escape this run)");
            else Playtest.fail("the chase ended in neither an escape nor an arrest");
        });
    }

    // ------------------------------------------------------------------------------------------------ case 9: a reopen while wanted

    private static void reopen() {
        Playtest.queue("9: before the quit", () -> {
            F.warrantBeforeQuit = warrant() != null;
            Playtest.log("CASES before the quit: warrant " + F.warrantBeforeQuit + "; " + server(PoliceResponse::status));
        });
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            ClientNotice.lastTitle = "";
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle after the reopen", 60);
        shot("9-reopened");
        Playtest.queue("9: after the reopen", () -> {
            WorkOrderLedger.Warrant w = warrant();
            boolean dispatched = server(() -> PoliceResponse.wanted(me().getUUID()));
            String notice = ClientNotice.lastTitle;
            Playtest.log("CASES after the reopen: warrant " + (w == null ? "none" : w.offence()) + ", a response out " + dispatched + ", last notice '" + notice + "'; " + server(PoliceResponse::status));
            if (!F.warrantBeforeQuit) { Playtest.log("CASES no warrant before the quit (the chase ended in an arrest): the reopen shows only that nothing is pending"); if (!dispatched) pass("9 a reopen with nothing outstanding: no response, nothing pending"); return; }
            if (w == null) Playtest.fail("the warrant was lost by a save and a reopen");
            else if (dispatched) Playtest.fail("a response was waiting for the player after the reopen");
            else pass("9 saved, quit and reopened while wanted: the warrant for " + w.offence() + " stands, nobody was sent while the player was away" + (notice.contains("wanted") ? ", and the player is told \"" + notice + "\"" : " (the notice was not seen: '" + notice + "')"));
        });
    }

    // ------------------------------------------------------------------------------------------------ recognised on the beat

    private static void recognised() {
        Playtest.queue("R: before", () -> { markTrace(); F.warrantArrestsBefore = server(() -> PoliceResponse.arrestsOnWarrant); F.arrestsBefore = server(() -> PoliceResponse.arrests); });
        final PathWalker[] walker = new PathWalker[1];
        final UUID[] officer = new UUID[1];
        final int[] t = {0};
        Playtest.queue("R: walk up to an officer on the beat (waits for a patrol to be out)", () -> { t[0] = 0; walker[0] = null; officer[0] = null; }, () -> {
            t[0]++;
            if (warrant() == null) return true;
            if (server(() -> PoliceResponse.wanted(me().getUUID()))) { if (walker[0] != null) walker[0].stop(); return true; }
            if (officer[0] == null && t[0] % 40 == 0) {
                officer[0] = server(() -> {
                    PolicePatrols.Patrol patrol = PolicePatrols.patrolOf(CITY);
                    if (patrol == null) return null;
                    for (UUID id : patrol.officers) if (level().getEntity(id) instanceof TownspersonEntity o && o.isAlive()) return id;
                    return null;
                });
                if (officer[0] != null) {
                    Playtest.log("CASES an officer is on the beat; walking up to them");
                    walker[0] = new PathWalker("to the officer", () -> server(() -> level().getEntity(officer[0]) instanceof TownspersonEntity o ? o.position() : null), 8.0);
                }
            }
            if (walker[0] != null) { walker[0].tick(); if (walker[0].done()) { officer[0] = null; walker[0] = null; } }
            return t[0] > 20 * 420;
        }, 20 * 430);
        final int[] k = {0};
        Playtest.queue("R: stand still until arrested (or the response ends)", () -> k[0] = 0, () -> {
            k[0]++;
            return server(() -> PoliceResponse.arrests) > F.arrestsBefore || (!server(() -> PoliceResponse.wanted(me().getUUID())) && k[0] > 60);
        }, 20 * 180);
        Playtest.wait("draw", 10);
        shot("R-recognised");
        Playtest.queue("R: recognised?", () -> {
            logTrace("recognised");
            long onWarrant = server(() -> PoliceResponse.arrestsOnWarrant) - F.warrantArrestsBefore, arrests = server(() -> PoliceResponse.arrests) - F.arrestsBefore;
            Playtest.log("CASES recognised: arrests " + arrests + " (" + onWarrant + " of them on a warrant), warrant now " + (warrant() == null ? "none" : "still") + "; " + server(PoliceResponse::status));
            if (arrests > 0) pass("an officer on the beat recognised the wanted player and arrested them on the warrant");
            else Playtest.log("CASES no officer on the beat came close enough to recognise the player in this run");
        });
    }
}
