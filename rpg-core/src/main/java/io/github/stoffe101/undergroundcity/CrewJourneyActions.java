package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the two-client Crew journey: each real client presses the buttons of the real Crew screen (the screen's own click handler at the
 * button's centre, and key events for the text boxes), in the order a pair of friends would, and records what its own screen showed. Loaded only when
 * the journey is enabled. It decides nothing: every number it reports came from the server.
 *
 * <p>Phase 1, founder (UC_Farmer): found the Crew, invite, deposit what the rent needs on top of the member's share, rent the Row House for the Crew from the Crew bank on the Find a home
 * page (the deposit and the first hour at the home's own price), let members withdraw. Member (UC_Smith): accept, deposit 110, withdraw 10 once allowed, make the chest in front of them
 * private. Phase 2 (after the server was killed and restarted): both read their screens first, then the founder deposits 5 and the member withdraws 20.
 */
final class CrewJourneyActions {
    private static final int PHASE = Integer.getInteger("uc.journey.phase", 0);
    private static int ticks, step, askedAt;
    private static boolean allStepsDone;
    private static double walletAfterRestart;
    private static final List<String> LOG = new ArrayList<>();
    private static JsonObject observed;
    private CrewJourneyActions() {}

    private interface Step { boolean run(CrewScreen screen, CrewView view); }

    /** Has this client finished every Crew step of its script (so that the survival script, if the combined journey runs one, may use the screen)? */
    static boolean finished() { return allStepsDone; }

    static void tick(Minecraft mc) {
        if (mc.player == null || ++ticks % 10 != 0) return;
        // Once every Crew step is done this script leaves the screen alone for good. Asking for the Crew screen again would race with the survival half of the combined journey: the server opens
        // the exchange terminal in the same tick that hands over the goods, and a late request for the Crew screen replaces it (seen in the hosted combined journey: the client waited in stage 0).
        if (allStepsDone) {
            // phase 2's last part (seventh pass): the Crew contract on the terminal the server opened, chosen and delivered with the screen's own buttons
            if (PHASE == 2 && !SurvivalJourney.ON) contractTick(mc);
            return;
        }
        boolean founder = mc.getUser().getName().equals(CrewJourneyServer.FARMER);
        if (!(mc.screen instanceof CrewScreen screen)) {
            // the server opens the screen; if it was closed or never arrived, ask for it the way the K key does (not more than every 5 seconds)
            if (ticks - askedAt >= 100 && mc.getConnection() != null) { askedAt = ticks; CrewNetwork.ask(true); }
            return;
        }
        List<Step> steps = PHASE == 1 ? (founder ? founderOne() : memberOne()) : (founder ? founderTwo() : memberTwo());
        if (step >= steps.size()) { allStepsDone = true; return; }
        if (screen.busy()) return; // a request is in flight: wait for the server's answer, like a person would
        CrewView view = screen.snapshot();
        if (steps.get(step).run(screen, view)) {
            step++;
            if (step >= steps.size()) allStepsDone = true;
            say("step " + step + " of " + steps.size() + " done");
            record(mc, screen.snapshot(), "after_step_" + step);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ phase 1

    private static List<Step> founderOne() {
        return List.of(
            (s, v) -> {
                if (v.inCrew()) return true;
                type(s.nameField(), CrewJourneyServer.CREW);
                press(s, "create");
                return false;
            },
            (s, v) -> {
                if (v.members().size() == 2 || !v.outgoing().isEmpty()) return true;
                for (int i = 0; i < v.candidates().size(); i++) {
                    if (v.candidates().get(i).name().equals(CrewJourneyServer.SMITH)) { press(s, "invite_" + i); return false; }
                }
                return false; // the other player has not shown up as a candidate yet
            },
            (s, v) -> v.members().size() == 2,
            (s, v) -> {
                // the founder puts in what the rent needs on top of the member's share, read from the offer the server priced for this home
                CrewView.LeaseRow house = rowHouse(v);
                if (house == null) return false;
                long total = house.offer().deposit() + house.offer().rent(), amount = total + CrewJourneyServer.BANK_AFTER_RENT - CrewJourneyServer.MEMBER_DEPOSIT;
                if (v.wallet() <= CrewJourneyServer.START - amount) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), Long.toString(amount));
                press(s, "deposit");
                return false;
            },
            (s, v) -> {
                CrewView.LeaseRow house = rowHouse(v);
                return house != null && v.bank() == house.offer().deposit() + house.offer().rent() + CrewJourneyServer.BANK_AFTER_RENT;
            },
            (s, v) -> {
                if (v.homes().stream().anyMatch(h -> h.title().equals("Row House") && h.owner().equals("Crew"))) return true;
                s.showPage(CrewScreen.Page.HOME);
                s.showHomeTab(1);
                for (int i = 0; i < v.nearby().size(); i++) {
                    CrewView.LeaseRow lease = v.nearby().get(i);
                    if (lease.title().equals("Row House")) {
                        say("rental offer: " + lease.title() + " rent " + lease.offer().rent() + " an hour, deposit " + lease.offer().deposit() + ", for the Crew " + s.forCrewChosen() + ", from the bank " + s.fromBankChosen());
                        if (!s.forCrewChosen() || !s.fromBankChosen()) { say("the Find a home defaults are not the Crew's bank"); return false; }
                        press(s, "rent_" + i);
                        return false;
                    }
                }
                return false;
            },
            (s, v) -> {
                if ((v.memberPerms() & (1 << Perm.WITHDRAW.ordinal())) != 0) return true;
                s.showPage(CrewScreen.Page.RULES);
                s.showRulesTab(0);
                press(s, "perm_x_" + Perm.WITHDRAW.ordinal());
                return false;
            },
            (s, v) -> v.bank() == CrewJourneyServer.BANK_AFTER_WITHDRAWAL);
    }

    /** The Row House among the homes on offer (or null before the Crew exists and the offers have arrived). */
    private static CrewView.LeaseRow rowHouse(CrewView v) {
        for (CrewView.LeaseRow lease : v.nearby()) if (lease.title().equals("Row House")) return lease;
        return null;
    }

    private static List<Step> memberOne() {
        return List.of(
            (s, v) -> {
                if (v.inCrew()) return true;
                if (!v.incoming().isEmpty()) press(s, "accept_0");
                return false;
            },
            (s, v) -> {
                if (v.wallet() <= CrewJourneyServer.START - CrewJourneyServer.MEMBER_DEPOSIT) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), Integer.toString(CrewJourneyServer.MEMBER_DEPOSIT));
                press(s, "deposit");
                return false;
            },
            (s, v) -> v.can(Perm.WITHDRAW),
            (s, v) -> {
                if (v.wallet() >= CrewJourneyServer.START - CrewJourneyServer.MEMBER_DEPOSIT + CrewJourneyServer.FIRST_WITHDRAWAL) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), Integer.toString(CrewJourneyServer.FIRST_WITHDRAWAL));
                press(s, "withdraw");
                return false;
            },
            (s, v) -> {
                if (v.homes().stream().anyMatch(h -> h.mine() >= 1)) return true;
                s.showPage(CrewScreen.Page.HOME);
                if (v.homes().stream().anyMatch(CrewView.HomeRow::here)) press(s, "claim_container");
                return false;
            });
    }

    // ------------------------------------------------------------------------------------------------------------ phase 2

    private static List<Step> founderTwo() {
        return List.of(
            (s, v) -> {
                if (!v.inCrew()) return false;
                record(Minecraft.getInstance(), v, "after_restart");
                walletAfterRestart = v.wallet();
                return true;
            },
            (s, v) -> {
                if (v.wallet() <= walletAfterRestart - 5) return true;
                if (!everyoneOnline(v)) return false;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "5");
                press(s, "deposit");
                return false;
            });
    }

    private static List<Step> memberTwo() {
        return List.of(
            (s, v) -> {
                if (!v.inCrew()) return false;
                record(Minecraft.getInstance(), v, "after_restart");
                walletAfterRestart = v.wallet();
                return true;
            },
            (s, v) -> {
                if (v.wallet() >= walletAfterRestart + 20) return true;
                if (!everyoneOnline(v)) return false;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "20");
                press(s, "withdraw");
                return false;
            });
    }

    // ------------------------------------------------------------------------------------------------------------ the Crew contract (phase 2, seventh pass)

    private static int contractStep, contractPhase, contractWait;
    private static double balanceBeforeFill;
    private static boolean contractDone;

    /**
     * The farmer delivers the canteen's flour and then has a supplier fill the cider part (nobody in the Crew makes cider; the part is on the founder's terminal because it is the Crew's); the
     * smith delivers the fittings. Each delivery is: choose the part on the Work page, press Accept, wait for the server to accept it, press Deliver, wait for the server to settle it. Presses go
     * through the screen's own click handler at the button's centre, as in phase 1; nothing is decided here.
     */
    private static void contractTick(Minecraft mc) {
        if (contractDone || !(mc.screen instanceof TerminalScreen t)) return;
        boolean founder = mc.getUser().getName().equals(CrewJourneyServer.FARMER);
        if (++contractWait < 3) return;   // let the screen take the server's last answer in
        contractWait = 0;
        String part = founder ? (contractStep == 0 ? CrewJourneyServer.FLOUR : CrewJourneyServer.CIDER) : CrewJourneyServer.FITTINGS;
        boolean fill = founder && contractStep == 1;
        TerminalData data = t.data();
        if (fill) {
            if (contractPhase == 0) {
                if (!t.selectJobById(part)) { say("the cider part is not on the founder's terminal yet"); return; }
                balanceBeforeFill = data.balance();
                if (t.clickSupplier()) { say("pressed Supplier on the cider part"); contractPhase = 1; }
                else say("Supplier is not available on the cider part now; trying again");
            } else if (data.balance() <= balanceBeforeFill - 1) {
                say("the supplier filled the cider part: wallet " + (long) balanceBeforeFill + " -> " + (long) data.balance());
                contractDone = true;
            }
            return;
        }
        switch (contractPhase) {
            case 0 -> {
                if (!t.selectJobById(part)) { say(part + " is not on the terminal"); return; }
                if (t.primaryState().equals("Accept order") && t.clickPrimary()) { say("pressed Accept on " + part); contractPhase = 1; }
                else say("Accept is not available on " + part + " (" + t.primaryState() + "); trying again");
            }
            case 1 -> {
                if (data.active() == null) return;   // the server has not accepted it yet
                t.selectJobById(part);
                if (t.primaryState().equals("Deliver and collect") && t.clickPrimary()) { say("pressed Deliver on " + part); contractPhase = 2; }
            }
            case 2 -> {
                if (data.active() != null) return;   // not settled yet
                say("delivered " + part);
                contractPhase = 0;
                if (founder) contractStep++; else contractDone = true;
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    /**
     * The server checks what recovery left once both players are in; a deposit or a withdrawal before that changes the very numbers it is checking. When the second client's first
     * connection attempt fails and is retried (a slow login on a hosted runner), the first client used to act alone and the check saw a bank 5 credits off.
     */
    private static boolean everyoneOnline(CrewView v) {
        return v.members().stream().allMatch(CrewView.MemberRow::online);
    }

    private static void press(CrewScreen screen, String id) {
        if (!screen.press(id)) say("button " + id + " is not available now (inactive or absent); trying again");
        else say("pressed " + id);
    }

    /** Types the text with key events into the box a click would focus, replacing what is there. */
    private static void type(EditBox box, String text) {
        if (box.getValue().equals(text)) return;
        box.setValue("");
        box.setFocused(true);
        for (char c : text.toCharArray()) box.charTyped(c, 0);
    }

    private static void say(String line) {
        String text = "[UC-JOURNEY] crew client phase " + PHASE + ": " + line;
        if (LOG.isEmpty() || !LOG.get(LOG.size() - 1).equals(text)) { LOG.add(text); System.out.println(text); }
    }

    /** What this player's screen shows, written to the game directory for the runner to compare with the server's report. */
    private static void record(Minecraft mc, CrewView v, String when) {
        if (observed == null) observed = new JsonObject();
        JsonObject o = new JsonObject();
        o.addProperty("player", mc.getUser().getName());
        o.addProperty("in_crew", v.inCrew());
        o.addProperty("crew", v.crewName());
        o.addProperty("role", v.role());
        o.addProperty("bank", v.bank());
        o.addProperty("wallet", v.wallet());
        o.addProperty("mode", v.effectiveMode());
        JsonArray members = new JsonArray();
        v.members().forEach(m -> members.add(m.name() + ":" + m.role() + ":" + m.career()));
        o.add("members", members);
        JsonArray homes = new JsonArray();
        v.homes().forEach(h -> homes.add(h.title() + ":" + h.owner() + ":private=" + h.mine() + ":" + h.tenancy().tenure() + ":" + h.tenancy().standing() + ":charge=" + h.tenancy().charge() + ":deposit=" + h.tenancy().deposit()));
        o.add("homes", homes);
        JsonArray history = new JsonArray();
        v.log().stream().limit(12).forEach(l -> history.add(l.kind() + (l.amount() > 0 ? " " + l.amount() : "")));
        o.add("history_newest_first", history);
        o.addProperty("member_may_withdraw", (v.memberPerms() & (1 << Perm.WITHDRAW.ordinal())) != 0);
        observed.add(when, o);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("journey-crew-client-phase" + PHASE + ".json"), observed + "\n");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
