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
 * <p>Phase 1, founder (UC_Farmer): found the Crew, invite, deposit 120, lease the Row House from the Crew bank, let members withdraw.
 * Member (UC_Smith): accept, deposit 60, withdraw 10 once allowed, make the chest in front of them private.
 * Phase 2 (after the server was killed and restarted): both read their screens first, then the founder deposits 5 and the member withdraws 20.
 */
final class CrewJourneyActions {
    private static final int PHASE = Integer.getInteger("uc.journey.phase", 0);
    private static int ticks, step, askedAt;
    private static final List<String> LOG = new ArrayList<>();
    private static JsonObject observed;
    private CrewJourneyActions() {}

    private interface Step { boolean run(CrewScreen screen, CrewView view); }

    static void tick(Minecraft mc) {
        if (mc.player == null || ++ticks % 10 != 0) return;
        boolean founder = mc.getUser().getName().equals(CrewJourneyServer.FARMER);
        if (!(mc.screen instanceof CrewScreen screen)) {
            // the server opens the screen; if it was closed or never arrived, ask for it the way the K key does (not more than every 5 seconds)
            if (ticks - askedAt >= 100 && mc.getConnection() != null) { askedAt = ticks; CrewNetwork.ask(true); }
            return;
        }
        List<Step> steps = PHASE == 1 ? (founder ? founderOne() : memberOne()) : (founder ? founderTwo() : memberTwo());
        if (step >= steps.size()) return;
        if (screen.busy()) return; // a request is in flight: wait for the server's answer, like a person would
        CrewView view = screen.snapshot();
        if (steps.get(step).run(screen, view)) {
            step++;
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
                if (v.wallet() <= 80) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "120");
                press(s, "deposit");
                return false;
            },
            (s, v) -> v.bank() == 180,
            (s, v) -> {
                if (v.homes().stream().anyMatch(h -> h.title().equals("Row House") && h.owner().equals("Crew"))) return true;
                s.showPage(CrewScreen.Page.HOME);
                for (int i = 0; i < v.nearby().size(); i++) {
                    CrewView.LeaseRow lease = v.nearby().get(i);
                    if (lease.title().equals("Row House")) {
                        say("lease offer: " + lease.title() + " " + lease.price() + " credits, starter price " + lease.starter());
                        press(s, "lease_bank_" + i);
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
            (s, v) -> v.bank() == 30);
    }

    private static List<Step> memberOne() {
        return List.of(
            (s, v) -> {
                if (v.inCrew()) return true;
                if (!v.incoming().isEmpty()) press(s, "accept_0");
                return false;
            },
            (s, v) -> {
                if (v.wallet() <= 140) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "60");
                press(s, "deposit");
                return false;
            },
            (s, v) -> v.can(Perm.WITHDRAW),
            (s, v) -> {
                if (v.wallet() >= 150) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "10");
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
                return true;
            },
            (s, v) -> {
                if (v.wallet() <= 60) return true;
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
                return true;
            },
            (s, v) -> {
                if (v.wallet() >= 170) return true;
                s.showPage(CrewScreen.Page.MONEY);
                type(s.amountField(), "20");
                press(s, "withdraw");
                return false;
            });
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

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
        v.homes().forEach(h -> homes.add(h.title() + ":" + h.owner() + ":private=" + h.mine()));
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
