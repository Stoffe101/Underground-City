package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewNetwork.Act;
import io.github.stoffe101.undergroundcity.CrewNetwork.Op;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Crew screen: one panel with a navigation rail like the Operations Terminal. CREW (members, roles, invitations), MONEY (the Crew bank,
 * your wallet, the history), HOME (your homes and the ones to lease nearby, private containers) and RULES (what each role may do, how the
 * Crew handles money, renaming and disbanding). It draws a server snapshot ({@link CrewView}) and sends bounded requests; it decides
 * nothing, and every button that cannot work says why in its tooltip. A player without a Crew sees one plain page: found one, or answer
 * an invitation.
 */
@OnlyIn(Dist.CLIENT)
public final class CrewScreen extends UcScreen {
    public enum Page {
        CREW("Crew", UcUi.Icon.TRUST), MONEY("Money", UcUi.Icon.CREDITS), HOME("Home", UcUi.Icon.KEY), RULES("Rules", UcUi.Icon.GEAR);
        final String label;
        final UcUi.Icon icon;
        Page(String label, UcUi.Icon icon) { this.label = label; this.icon = icon; }
    }

    private static final int ROW = 24, MROW = 29;

    private CrewView view;
    private String note = "";
    private Page page = Page.CREW;
    private int rulesTab;
    /** Home page: 0 = the homes you hold, 1 = the homes on offer nearby. */
    private int homeTab, findPage;
    private String selectedHome = "", confirmLeave = "";
    private boolean forCrew, fromBank, choicesSet, choicesInCrew;
    private UUID selected;
    private boolean busy;
    private int busyTicks;
    private int railW, cx, cy, cw, ch;
    private EditBox nameBox, amountBox, renameBox, confirmBox;
    private String presetAmount = "", presetConfirm = "";
    private final Map<String, UcButton> byId = new LinkedHashMap<>();

    CrewScreen(CrewView view, String note) {
        super(Component.literal("Underground City | Crew"));
        this.view = view;
        this.note = note == null ? "" : note;
    }

    // ------------------------------------------------------------------------------------------------ chrome

    @Override protected int accent() { return view.inCrew() ? (view.founder() ? UcUi.AMBER : UcUi.LEGAL) : UcUi.DIM; }
    @Override protected String titleText() { return "Crew"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TRUST; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        if (!view.inCrew()) return;
        String name = UcUi.fit(font, view.crewName().toUpperCase(Locale.ROOT), Math.max(60, pw / 3));
        int w = UcUi.displayWidth(font, name) + 10;
        UcUi.chip(g, font, name, right - w, py + 9, view.founder() ? UcUi.AMBER : UcUi.LEGAL, null);
        String role = view.role().toUpperCase(Locale.ROOT);
        int rw = UcUi.displayWidth(font, role) + 10;
        UcUi.chip(g, font, role, right - w - 4 - rw, py + 9, UcUi.SOFT, null);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        boolean refusal = note.startsWith("!");
        String text = refusal ? note.substring(1) : note;
        int x = px + PAD;
        if (!text.isEmpty()) {
            UcUi.icon(g, refusal ? UcUi.Icon.WARNING : UcUi.Icon.CHECK, x, y + 4, refusal ? UcUi.UNDERGROUND_TEXT : UcUi.OK);
            UcUi.text(g, font, UcUi.fit(font, text, pw - 2 * PAD - 150), x + 20, y + 9, refusal ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT);
        } else {
            UcUi.icon(g, UcUi.Icon.INFO, x, y + 4, UcUi.DIM);
            UcUi.text(g, font, UcUi.fit(font, view.inCrew() ? "Your career, wallet and skills stay your own." : "A Crew is optional. Everything else works alone.", pw - 2 * PAD - 150), x + 20, y + 9, UcUi.DIM);
        }
        if (view.moneyReady()) {
            String credits = String.format(Locale.ROOT, "%.0f", view.wallet());
            int w = UcUi.displayWidth(font, credits);
            UcUi.displayRight(g, font, credits, px + pw - PAD, y + 8, UcUi.AMBER);
            UcUi.icon(g, UcUi.Icon.CREDITS, px + pw - PAD - w - 19, y + 4, UcUi.AMBER);
        }
    }

    // ------------------------------------------------------------------------------------------------ building

    @Override protected void init() {
        layoutPanel(440, 290, 680, 430);
        railW = pw >= 440 ? 86 : 34;
        cx = px + PAD + railW + 8;
        cw = px + pw - PAD - cx;
        cy = bodyY();
        ch = bodyBottom() - cy;
        String name = nameBox == null ? "" : nameBox.getValue(), amount = amountBox == null ? presetAmount : amountBox.getValue();
        String rename = renameBox == null ? "" : renameBox.getValue(), confirm = confirmBox == null ? presetConfirm : confirmBox.getValue();
        nameBox = box(name, "Crew name", 24);
        amountBox = box(amount, "Amount", 9);
        amountBox.setFilter(s -> s.matches("[0-9]{0,9}"));
        renameBox = box(rename, "New name", 24);
        confirmBox = box(confirm, "Type the Crew's name", 24);
        build();
    }

    private EditBox box(String value, String hint, int max) {
        EditBox box = new EditBox(font, 0, 0, 100, 16, Component.literal(hint));
        box.setMaxLength(max);
        box.setBordered(false);
        box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        box.setValue(value);
        box.setTextColor(UcUi.TEXT);
        // what you type decides which buttons work (Deposit needs an amount you have, Found Crew a name); without this they only caught up at the next refresh
        box.setResponder(text -> build());
        return box;
    }

    private UcButton btn(String id, int x, int y, int w, int h, String label, Runnable action) {
        UcButton button = add(new UcButton(x, y, w, h, label, b -> action.run()));
        button.active = !busy;
        byId.put(id, button);
        return button;
    }

    private void place(EditBox box, int x, int y, int w) {
        box.setX(x + 4); box.setY(y + 4); box.setWidth(w - 8);
        addRenderableWidget(box);
    }

    private void send(Act act) {
        if (minecraft == null || minecraft.getConnection() == null) { note = "!Not connected to a server."; build(); return; }
        busy = true; busyTicks = 0; note = "";
        CrewNetwork.send(act);
        build();
    }

    private void build() {
        if (nameBox == null) return; // not opened yet: init() builds
        clearWidgets();
        byId.clear();
        if (!view.storageOk() || !view.citizen()) return;
        Page[] pages = view.inCrew() ? Page.values() : new Page[]{Page.CREW, Page.HOME};
        if (!view.inCrew() && page != Page.CREW && page != Page.HOME) page = Page.CREW;
        // the default For/Pay choices follow the player into a Crew (a screen opened before the Crew was founded must not stay on "For: me")
        if (!choicesSet || choicesInCrew != view.inCrew()) { choicesSet = true; choicesInCrew = view.inCrew(); forCrew = view.inCrew() && view.can(Perm.MANAGE_PROPERTY); fromBank = forCrew && view.can(Perm.SPEND) && !view.effectiveMode().equals(Mode.SEPARATE.name()); }
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            UcButton button = add(new UcButton(px + PAD, cy + i * 30, railW, 26, p.label, b -> { page = p; build(); }).kind(UcButton.Kind.RAIL).icon(p.icon)
                .tooltip(railW < 50 ? p.label : ""));
            button.selected(p == page).accent(UcUi.LEGAL);
            byId.put("rail_" + p.name().toLowerCase(Locale.ROOT), button);
            if (p == Page.CREW) button.badge(view.inCrew() ? view.members().size() : view.incoming().size());
            if (p == Page.MONEY && view.owed() > 0) button.badge(1);
            if (p == Page.HOME && view.homes().stream().anyMatch(h -> h.tenancy().arrears() > 0)) button.badge(1);
        }
        if (!view.inCrew() && page == Page.CREW) { buildNoCrew(); return; }
        switch (page) {
            case CREW -> buildCrew();
            case MONEY -> buildMoney();
            case HOME -> buildHome();
            case RULES -> buildRules();
        }
    }

    // ---- no Crew

    private void buildNoCrew() {
        int x = cx, w = cw, y = cy + 56;
        int half = Math.min(w, 300);
        place(nameBox, x, y + 20, half - 110);
        UcButton found = btn("create", x + half - 104, y + 20, 104, 20, "Found Crew", () -> send(Act.of(Op.CREATE).text(nameBox.getValue()))).kind(UcButton.Kind.PRIMARY);
        found.active = found.active && nameBox.getValue().trim().length() >= CrewRules.NAME_MIN;
        found.tooltip("Founds a Crew with you as its founder.", "Names have 3 to 24 letters, digits and ' - _ .");
        int iy = y + 84;
        int i = 0;
        for (CrewView.InviteRow invite : view.incoming()) {
            int row = iy + i * 26;
            btn("accept_" + i, cx + cw - 136, row, 64, 20, "Accept", () -> send(Act.of(Op.ACCEPT).target(invite.id()))).kind(UcButton.Kind.PRIMARY);
            btn("decline_" + i, cx + cw - 68, row, 64, 20, "Decline", () -> send(Act.of(Op.DECLINE).target(invite.id())));
            i++;
        }
    }

    private void renderNoCrew(GuiGraphics g) {
        int x = cx, w = cw, y = cy + 56;
        UcUi.display(g, font, "Start or join a Crew", x, cy + 4, UcUi.TEXT);
        UcUi.wrapped(g, font, "A Crew is a small group that can share a home and, if you all want it, a bank. You keep your own career, wallet, skills and homes. "
            + "You never need one.", x, cy + 18, w, UcUi.SOFT, 3);
        UcUi.well(g, nameBox.getX() - 4, nameBox.getY() - 4, nameBox.getWidth() + 8, 20);
        UcUi.text(g, font, "Found a Crew", x, y + 6, UcUi.DIM);
        UcUi.text(g, font, view.incoming().isEmpty() ? "No invitations yet. A friend invites you from their Crew screen (press K)." : "Invitations", x, y + 70, UcUi.DIM);
        int i = 0;
        for (CrewView.InviteRow invite : view.incoming()) {
            int row = y + 84 + i * 26;
            g.fill(x, row - 2, x + w, row + 22, i % 2 == 0 ? UcUi.ROW : 0xff111c27);
            UcUi.icon(g, UcUi.Icon.TRUST, x + 4, row + 2, UcUi.LEGAL);
            UcUi.text(g, font, UcUi.fit(font, invite.crew() + "  from " + invite.who(), w - 160), x + 26, row + 6, UcUi.TEXT);
            UcUi.text(g, font, invite.ago(), x + 26, row + 15, UcUi.FAINT);
            i++;
        }
    }

    // ---- CREW

    private int listW() { return Math.min(cw - 150, cw * 62 / 100); }

    private void buildCrew() {
        int lw = listW();
        CrewView.MemberRow chosen = member(selected);
        boolean founder = view.founder();
        int by = cy + 18 + view.members().size() * MROW + 6;
        if (chosen != null && !chosen.id().equals(view.self())) {
            int bx = cx;
            boolean target = !chosen.role().equals("Founder");
            if (founder && target) {
                boolean manager = chosen.role().equals("Manager");
                btn("role", bx, by, 112, 18, manager ? "Make member" : "Make manager", () -> send(Act.of(Op.SET_ROLE).target(chosen.id()).flag(!manager)))
                    .tooltip(manager ? "Managers lose the manager rights." : "Managers can invite, remove members, withdraw, spend and lease (the rules page can change that).");
                bx += 116;
                btn("transfer", bx, by, 124, 18, "Hand over lead", () -> send(Act.of(Op.TRANSFER).target(chosen.id()))).kind(UcButton.Kind.DANGER)
                    .tooltip("Makes " + chosen.name() + " the founder. You become a manager.");
                bx += 128;
            }
            boolean outranks = founder ? target : view.can(Perm.MANAGE_MEMBERS) && view.role().equals("Manager") && chosen.role().equals("Member");
            if (outranks) {
                btn("kick", bx, by, 70, 18, "Remove", () -> send(Act.of(Op.KICK).target(chosen.id()))).kind(UcButton.Kind.DANGER)
                    .tooltip(chosen.name() + " leaves the Crew. Their wallet, career and personal homes stay theirs.");
            }
        }
        int ly = cy + ch - 22;
        if (!founder) {
            btn("leave", cx, ly, 96, 18, "Leave Crew", () -> send(Act.of(Op.LEAVE))).kind(UcButton.Kind.DANGER)
                .tooltip("You keep your wallet, career and personal homes.");
        }
        if (view.canClaimLead()) {
            btn("claim_lead", cx + 100, ly, 150, 18, "Take over leadership", () -> send(Act.of(Op.CLAIM_LEAD))).kind(UcButton.Kind.PRIMARY)
                .tooltip("The founder has been away for 30 days.");
        }
        // invitations
        int ix = cx + lw + 10, iw = cw - lw - 10;
        if (view.can(Perm.INVITE)) {
            int y = cy + 18;
            for (int i = 0; i < view.candidates().size() && y + 20 < cy + ch - 70; i++) {
                CrewView.Candidate candidate = view.candidates().get(i);
                btn("invite_" + i, ix + iw - 54, y, 54, 18, "Invite", () -> send(Act.of(Op.INVITE).text(candidate.name()))).kind(UcButton.Kind.PRIMARY)
                    .tooltip("Sends " + candidate.name() + " an invitation. They answer on their own screen (K).");
                y += ROW;
            }
            int oy = cy + ch - 62;
            for (int i = 0; i < view.outgoing().size() && i < 2; i++) {
                int index = i;
                btn("withdraw_" + i, ix + iw - 54, oy + 12 + i * 20, 54, 16, "Cancel", () -> send(Act.of(Op.WITHDRAW_INVITE).target(view.outgoing().get(index).id())));
            }
        }
    }

    private CrewView.MemberRow member(UUID id) {
        if (id == null) return null;
        for (CrewView.MemberRow row : view.members()) if (row.id().equals(id)) return row;
        return null;
    }

    private void renderCrew(GuiGraphics g, int mx, int my) {
        int lw = listW();
        UcUi.display(g, font, "Members " + view.members().size() + "/" + CrewRules.MAX_MEMBERS, cx, cy + 4, UcUi.SOFT);
        int y = cy + 18;
        for (CrewView.MemberRow m : view.members()) {
            boolean sel = m.id().equals(selected), hover = mx >= cx && mx < cx + lw && my >= y && my < y + MROW - 2;
            g.fill(cx, y, cx + lw, y + MROW - 2, sel ? 0xff1f3a52 : hover ? UcUi.ROW_HOVER : UcUi.ROW);
            if (sel) g.fill(cx, y, cx + 3, y + MROW - 2, UcUi.LEGAL);
            UcUi.icon(g, UcUi.careerIcon(m.career()), cx + 6, y + 5, UcUi.careerColor(m.career()));
            int tx = cx + 28;
            String you = m.id().equals(view.self()) ? "  (you)" : "";
            String role = m.role().toUpperCase(Locale.ROOT);
            int rw = UcUi.displayWidth(font, role) + 8;
            UcUi.text(g, font, UcUi.fit(font, m.name() + you, lw - 28 - rw - 14), tx, y + 4, UcUi.TEXT);
            String detail = (m.career().isEmpty() ? "no trade yet" : capitalize(m.career()) + " L" + m.level());
            UcUi.text(g, font, UcUi.fit(font, detail, lw - 28 - rw - 14), tx, y + 15, UcUi.DIM);
            UcUi.chip(g, font, role, cx + lw - rw - 6, y + 3, roleColor(m.role()), null);
            boolean online = m.seen().equals("online");
            UcUi.textRight(g, font, m.seen(), cx + lw - 8, y + 17, online ? UcUi.OK : UcUi.FAINT);
            y += MROW;
        }
        CrewView.MemberRow chosen = member(selected);
        if (chosen == null) {
            UcUi.text(g, font, UcUi.fit(font, "Select a member to change a role or remove them.", lw), cx, y + 6, UcUi.FAINT);
        }
        // invitations column
        int ix = cx + lw + 10, iw = cw - lw - 10;
        if (view.can(Perm.INVITE)) {
            UcUi.display(g, font, "Invite a friend", ix, cy + 4, UcUi.SOFT);
            int iy = cy + 18;
            if (view.candidates().isEmpty()) {
                UcUi.wrapped(g, font, "Nobody is online who can be invited. They must be registered citizens without a Crew.", ix, iy + 2, iw, UcUi.FAINT, 4);
            }
            for (int i = 0; i < view.candidates().size() && iy + 20 < cy + ch - 70; i++) {
                CrewView.Candidate candidate = view.candidates().get(i);
                g.fill(ix, iy, ix + iw, iy + ROW - 2, UcUi.ROW);
                UcUi.icon(g, UcUi.careerIcon(candidate.career()), ix + 4, iy + 3, UcUi.careerColor(candidate.career()));
                UcUi.text(g, font, UcUi.fit(font, candidate.name(), iw - 24 - 62), ix + 24, iy + 3, UcUi.TEXT);
                UcUi.text(g, font, capitalize(candidate.career()), ix + 24, iy + 12, UcUi.DIM);
                iy += ROW;
            }
            int oy = cy + ch - 62;
            UcUi.text(g, font, view.outgoing().isEmpty() ? "No open invitations." : "Open invitations", ix, oy, UcUi.DIM);
            for (int i = 0; i < view.outgoing().size() && i < 2; i++) {
                CrewView.InviteRow invite = view.outgoing().get(i);
                UcUi.text(g, font, UcUi.fit(font, invite.who() + "  " + invite.ago(), iw - 62), ix, oy + 15 + i * 20, UcUi.SOFT);
            }
        } else {
            UcUi.display(g, font, "Invitations", ix, cy + 4, UcUi.SOFT);
            UcUi.wrapped(g, font, "Your role cannot invite. The founder can allow it on the Rules page.", ix, cy + 20, iw, UcUi.FAINT, 4);
        }
    }

    // ---- MONEY

    private long amount() {
        try { return amountBox.getValue().isEmpty() ? 0 : Long.parseLong(amountBox.getValue()); } catch (NumberFormatException e) { return 0; }
    }

    /** The height of the one- or two-line note under the cards that says how money moves in this Crew. */
    private static final int NOTE_H = 24, BANNER_H = 58;

    /** What the Money page says about how credits move. It always says what Q Shop's own shop screens do, because that is the limit people trip over. */
    private String moneyNote() {
        boolean shared = view.mode().equals(Mode.SHARED.name());
        if (shared && !view.effectiveMode().equals(Mode.SHARED.name())) return "Not in force yet: it needs every member's agreement. Until then this Crew works as a Crew bank.";
        return switch (view.effectiveMode()) {
            case "SEPARATE" -> "Everyone pays for their own purchases. Q Shop's own shops always use your wallet.";
            case "SHARED" -> view.can(Perm.SPEND)
                ? "Work-order pay goes in; at exchanges the bank pays first and you pay the rest. Q Shop's own shops never use the bank."
                : "You may not spend Crew money, so you pay for purchases yourself. Q Shop's own shops never use the bank.";
            default -> "Credits move only when someone deposits or withdraws. Q Shop's own shops always use your wallet.";
        };
    }

    private void buildMoney() {
        int y = cy + 56 + (view.owed() > 0 ? 28 : 0) + (needsConsent() ? BANNER_H + 4 : 0) + NOTE_H;
        int cardW = (cw - 8) / 2;
        place(amountBox, cx, y, 92);
        int[] quick = {10, 50, 100};
        for (int i = 0; i < quick.length; i++) {
            int q = quick[i];
            btn("quick_" + q, cx + 98 + i * 34, y, 30, 20, String.valueOf(q), () -> { amountBox.setValue(String.valueOf(q)); build(); }).kind(UcButton.Kind.CHIP).accent(UcUi.AMBER);
        }
        btn("quick_all", cx + 98 + 3 * 34, y, 40, 20, "All", () -> {
            amountBox.setValue(String.valueOf((long) Math.floor(view.wallet()))); build();
        }).kind(UcButton.Kind.CHIP).accent(UcUi.AMBER).tooltip("All the credits in your wallet.");
        boolean bank = !view.effectiveMode().equals(Mode.SEPARATE.name());
        UcButton deposit = btn("deposit", cx, y + 26, 120, 20, "Deposit", () -> send(Act.of(Op.DEPOSIT).amount(amount()))).kind(UcButton.Kind.PRIMARY);
        deposit.active = deposit.active && bank && view.moneyReady() && amount() >= 1 && amount() <= view.wallet();
        deposit.tooltip(!view.moneyReady() ? "Q Shop 1.8.4 with the credits currency is required." : !bank ? "This Crew keeps money separate." : amount() > view.wallet() ? "You only have " + (long) view.wallet() + " credits." : "Moves credits from your wallet into the Crew bank.");
        UcButton withdraw = btn("withdraw", cx + 126, y + 26, 120, 20, "Withdraw", () -> send(Act.of(Op.WITHDRAW).amount(amount())));
        withdraw.active = withdraw.active && bank && view.moneyReady() && view.can(Perm.WITHDRAW) && amount() >= 1 && amount() <= view.bank();
        withdraw.tooltip(!view.can(Perm.WITHDRAW) ? "Your role may not withdraw. The founder can allow it on the Rules page." : amount() > view.bank() ? "The bank holds " + view.bank() + " credits." : "Moves credits from the Crew bank into your wallet.");
        if (view.owed() > 0) {
            btn("claim_owed", cx + cw - 130, cy + 56, 130, 22, "Claim " + view.owed(), () -> send(Act.of(Op.CLAIM_OWED))).kind(UcButton.Kind.PRIMARY)
                .tooltip("Credits owed to you after a payout or a disband.");
        }
        if (needsConsent()) {
            int cyy = cy + 56 + (view.owed() > 0 ? 28 : 0);   // the banner sits above the note
            btn("consent_yes", cx + cw - 190, cyy + 38, 90, 18, "I agree", () -> send(Act.of(Op.CONSENT).flag(true))).kind(UcButton.Kind.PRIMARY);
            btn("consent_no", cx + cw - 96, cyy + 38, 96, 18, "Not for me", () -> send(Act.of(Op.CONSENT).flag(false)));
        }
        if (cardW < 0) return;
    }

    private boolean needsConsent() { return view.mode().equals(Mode.SHARED.name()) && !view.consentSelf(); }

    private void renderMoney(GuiGraphics g, int mx, int my) {
        int cardW = (cw - 8) / 2;
        boolean sharedInForce = view.effectiveMode().equals(Mode.SHARED.name());
        card(g, cx, cy, cardW, 50, "CREW BANK", view.bank() + "", UcUi.AMBER, modeShort(view.effectiveMode()) + (view.mode().equals(view.effectiveMode()) ? "" : " (not shared yet)"));
        card(g, cx + cardW + 8, cy, cardW, 50, "YOUR WALLET", view.moneyReady() ? String.format(Locale.ROOT, "%.0f", view.wallet()) : "OFFLINE", UcUi.OK,
            sharedInForce ? (view.can(Perm.SPEND) ? "pays what the bank does not" : "the bank is closed to you") : "stays yours");
        int y = cy + 56;
        if (view.owed() > 0) {
            g.fill(cx, y, cx + cw, y + 22, 0xff2a2410);
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + 4, y + 3, UcUi.AMBER);
            UcUi.text(g, font, UcUi.fit(font, view.owed() + " credits are owed to you.", cw - 24 - 134), cx + 24, y + 7, UcUi.AMBER);
            y += 28;
        }
        if (needsConsent()) {
            g.fill(cx, y, cx + cw, y + BANNER_H, 0xff261a12);
            UcUi.icon(g, UcUi.Icon.WARNING, cx + 4, y + 4, UcUi.WARN);
            UcUi.wrapped(g, font, "Proposed: work-order pay goes into the bank, and exchange purchases are paid from it first. Q Shop's own shops never use it.",
                cx + 24, y + 4, cw - 30, UcUi.SOFT, 3);
            y += BANNER_H + 4;
        }
        UcUi.wrapped(g, font, moneyNote(), cx, y, cw, UcUi.DIM, 2);
        y += NOTE_H;
        UcUi.well(g, amountBox.getX() - 4, amountBox.getY() - 4, amountBox.getWidth() + 8, 20);
        int hy = y + 54;
        UcUi.display(g, font, "History", cx, hy, UcUi.SOFT);
        int lines = Math.max(0, (cy + ch - (hy + 12)) / 11);
        int i = 0;
        for (CrewView.LogRow row : view.log()) {
            if (i >= lines) break;
            int ly = hy + 12 + i * 11;
            UcUi.text(g, font, UcUi.fit(font, row.ago(), 56), cx, ly, UcUi.FAINT);
            String what = logText(row);
            int color = row.kind().equals("DEPOSIT") ? UcUi.OK : row.kind().equals("WITHDRAW") || row.kind().equals("LEASE") || row.kind().equals("PURCHASE") ? UcUi.AMBER : UcUi.SOFT;
            UcUi.text(g, font, UcUi.fit(font, what, cw - 60), cx + 58, ly, color);
            i++;
        }
        if (view.log().isEmpty()) UcUi.text(g, font, "Nothing has happened yet.", cx, hy + 12, UcUi.FAINT);
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, String title, String value, int color, String sub) {
        UcUi.card(g, x, y, w, h, 0);
        UcUi.text(g, font, title, x + 8, y + 6, UcUi.DIM);
        UcUi.display(g, font, value, x + 8, y + 18, color);
        UcUi.text(g, font, UcUi.fit(font, sub, w - 16), x + 8, y + 36, UcUi.FAINT);
    }

    private static String logText(CrewView.LogRow row) {
        String who = row.who().isEmpty() ? "Someone" : row.who();
        return switch (row.kind()) {
            case "CREATED" -> who + " founded the Crew";
            case "JOINED" -> who + " joined";
            case "LEFT" -> who + " left";
            case "KICKED" -> who + " removed " + row.note();
            case "ROLE", "TRANSFER", "PERM", "RENAMED", "MODE" -> who + ": " + row.note();
            case "CONSENT" -> who + " " + row.note();
            case "DEPOSIT" -> who + " deposited " + row.amount();
            case "WITHDRAW" -> who + " withdrew " + row.amount();
            case "PURCHASE" -> who + " bought " + row.note() + ", bank paid " + row.amount();
            case "LEASE" -> who + " leased " + row.note() + " (" + row.amount() + ")";
            case "PAYOUT" -> who + " paid out " + row.amount() + ", " + row.note();
            default -> who + " " + row.kind().toLowerCase(Locale.ROOT);
        };
    }

    // ---- HOME

    private static final int HROW = 30;

    private int homeListW() { return Math.min(150, cw * 36 / 100); }

    private CrewView.HomeRow home(String id) {
        for (CrewView.HomeRow h : view.homes()) if (h.id().equals(id)) return h;
        return null;
    }

    private CrewView.HomeRow chosenHome() {
        CrewView.HomeRow home = home(selectedHome);
        if (home == null && !view.homes().isEmpty()) { home = view.homes().get(0); selectedHome = home.id(); }
        return home;
    }

    private static String hours(int minutes) { return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min"; }

    /** Credits a payment from this source could use right now (the wallet, or the Crew bank when the bank is the chosen source). */
    private long funds(boolean bank) { return bank ? view.bank() : (long) Math.floor(view.wallet()); }

    private void buildHome() {
        String[] tabs = {"Your homes (" + view.homes().size() + ")", "Find a home (" + view.nearby().size() + ")"};
        int tx = cx;
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            int w = UcUi.displayWidth(font, tabs[i]) + 22;
            btn("home_tab_" + i, tx, cy, w, 16, tabs[i], () -> { homeTab = index; build(); }).kind(UcButton.Kind.CHIP).accent(UcUi.LEGAL).selected(homeTab == i);
            tx += w + 4;
        }
        tabsRight = tx;
        if (homeTab == 0) buildMine(); else buildFind();
    }

    /** Where the Home page's tabs end (the page arrows of "Find a home" go to their right when there is room). */
    private int tabsRight;
    /** The page arrows and the "1 / 4" before them take this much of the tab row's right end. */
    private static final int PAGER_W = 96;

    /** True when the page arrows sit in the tab row; otherwise they take a row of their own above the first offer (they used to be drawn under the first offer's buttons). */
    private boolean pagerInTabRow() { return tabsRight <= cx + cw - PAGER_W; }

    private int pagerY() { return pagerInTabRow() ? cy : cy + (findHasChoices() ? 42 : 22); }

    private void buildMine() {
        CrewView.HomeRow home = chosenHome();
        if (home == null) return;
        CrewView.Tenancy t = home.tenancy();
        int lw = homeListW(), dx = cx + lw + 8, dw = cw - lw - 8;
        boolean crewPays = t.crewHome() && fromBank && view.can(Perm.SPEND) && !view.effectiveMode().equals(Mode.SEPARATE.name());
        boolean bankSource = t.crewHome() && crewPays;
        int by = cy + ch - 20;
        // bottom row: leave and the two private-container buttons; the row above it: pay buttons; above that, where a Crew home is paid from
        int third = (dw - 8) / 3;
        boolean leaving = confirmLeave.equals(home.id());
        String leaveLabel = t.rented() ? "Move out" : t.legacy() || t.tenure().equals("OWN") ? "Sell back" : "Leave";
        UcButton leave = btn("leave_home", dx, by, third, 18, leaving ? "Really?" : leaveLabel, () -> {
            if (!leaving) { confirmLeave = home.id(); build(); }
            else { confirmLeave = ""; send(Act.of(t.rented() ? Op.MOVE_OUT : Op.SELL_BACK).text(home.id())); }
        }).kind(UcButton.Kind.DANGER);
        leave.active = leave.active && t.manage();
        leave.tooltip(t.rented() ? "You give the home back. " + t.refund() + " credits come back (the deposit and any hours paid ahead, less what is owed). Containers must be empty: nothing stored is thrown away."
            : "You sell the home back to the city for " + t.refund() + " credits (" + view.rules().sellBackPercent() + "% of the price, less what is owed). Containers must be empty.");
        UcButton claim = btn("claim_container", dx + third + 4, by, third, 18, "Make private", () -> send(Act.of(Op.CLAIM_CONTAINER)));
        claim.active = claim.active && home.here();
        claim.tooltip(home.here() ? "Make the chest or barrel you are looking at private. Only you can open it, and nobody else can break it. You may keep " + t.claimsMax() + " in this home." : "Stand inside one of your homes, looking at a chest or barrel.");
        UcButton release = btn("release_container", dx + 2 * (third + 4), by, dw - 2 * (third + 4), 18, "Share again", () -> send(Act.of(Op.RELEASE_CONTAINER)));
        release.active = release.active && home.here();
        release.tooltip("Look at your private container to make it shared again.");
        if (t.billed()) {
            int py = by - 22;
            UcButton p1 = btn("pay_1", dx, py, third, 18, "Pay 1 h", () -> send(Act.of(Op.PAY).text(home.id()).amount(1).flag2(bankSource)));
            UcButton p3 = btn("pay_3", dx + third + 4, py, third, 18, "Pay 3 h", () -> send(Act.of(Op.PAY).text(home.id()).amount(3).flag2(bankSource)));
            for (UcButton b : List.of(p1, p3)) {
                long cost = (long) t.charge() * (b == p1 ? 1 : 3);
                b.active = b.active && view.moneyReady() && funds(bankSource) >= cost && (t.arrears() + t.prepaid() + (b == p1 ? 1 : 3) <= t.arrears() + view.rules().prepayMax() + 3);
                b.tooltip(cost + " credits from " + (bankSource ? "the Crew bank (" + view.bank() + ")" : "your wallet (" + (long) view.wallet() + ")") + ". Unpaid hours are settled first; the rest is paid ahead (up to " + view.rules().prepayMax() + " h).");
            }
            UcButton auto = btn("autopay", dx + 2 * (third + 4), py, dw - 2 * (third + 4), 18, t.autopay() ? "Auto: on" : "Auto: off", () -> send(Act.of(Op.AUTOPAY).text(home.id()).flag(!t.autopay())));
            auto.selected(t.autopay());
            auto.active = auto.active && t.manage();
            auto.tooltip(t.crewHome() ? "Each hour is paid from the Crew bank when it falls due. Needs the right to spend Crew money." : "Each hour is paid from your wallet when it falls due. If you cannot pay, it simply becomes unpaid; nothing is taken.");
            if (sourceChoice(t)) {
                btn("source", dx, py - 20, dw, 16, fromBank ? "Pay from: Crew bank" : "Pay from: my wallet", () -> { fromBank = !fromBank; build(); })
                    .kind(UcButton.Kind.CHIP).accent(UcUi.AMBER).tooltip("Where Pay takes the credits from. Anyone in the Crew can pay a Crew home from their own wallet.");
            }
        }
        // upgrades: only an owner can improve a home
        if (t.tenure().equals("OWN") && !view.upgrades().isEmpty()) {
            int uy = upgradeTop(t);
            for (int i = 0; i < view.upgrades().size(); i++) {
                CrewView.UpgradeRow up = view.upgrades().get(i);
                boolean fitted = java.util.Arrays.asList(t.upgrades().split(",")).contains(up.id());
                int index = i;
                UcButton buy = btn("upgrade_" + i, dx + dw - UPGRADE_BTN - 2, uy + i * UPGRADE_H, UPGRADE_BTN, UPGRADE_H - 2, fitted ? "Fitted" : "Fit", () -> send(Act.of(Op.UPGRADE).text(home.id()).amount(index).flag2(bankSource)));
                buy.active = buy.active && !fitted && t.manage() && view.moneyReady() && funds(bankSource) >= up.cost() && !t.standing().equals("LAPSED");
                buy.tooltip(fitted ? "Already fitted." : up.cost() + " credits from " + (bankSource ? "the Crew bank" : "your wallet") + ". " + up.summary());
            }
        }
    }

    /** Whether the home's page offers the choice of paying from the Crew bank or from the wallet. */
    private boolean sourceChoice(CrewView.Tenancy t) {
        return t.billed() && t.crewHome() && view.inCrew() && view.can(Perm.SPEND) && !view.effectiveMode().equals(Mode.SEPARATE.name());
    }

    /** The top of the footer (leave row, pay row and, for a Crew home, the pay-from chip) in the detail column. */
    private int footTop(CrewView.Tenancy t) {
        int top = cy + ch - 20;
        if (t.billed()) top -= 22;
        if (sourceChoice(t)) top -= 20;
        return top;
    }

    /** Where the upgrade rows start in the detail column. */
    private int upgradeTop(CrewView.Tenancy t) { return footTop(t) - 4 - view.upgrades().size() * UPGRADE_H; }

    private static final int UPGRADE_H = 17, UPGRADE_BTN = 42;

    private boolean findHasChoices() { return view.inCrew() && view.can(Perm.MANAGE_PROPERTY); }

    /** Where the first offer card starts: below the tabs, and below the for/pay chips when there are any. */
    private int findTop() { return cy + (findHasChoices() ? 42 : 24) + (!pagerInTabRow() && view.nearby().size() > 1 ? 18 : 0); }

    private void buildFind() {
        // who it is for and where the money comes from
        int y = cy + 22;
        if (findHasChoices()) {
            btn("for_toggle", cx, y, 120, 16, forCrew ? "For: the Crew" : "For: me", () -> { forCrew = !forCrew; if (!forCrew) fromBank = false; build(); })
                .kind(UcButton.Kind.CHIP).accent(UcUi.LEGAL).tooltip("A Crew home belongs to the Crew; a personal home stays yours when you leave or the Crew ends.");
            if (forCrew && view.can(Perm.SPEND) && !view.effectiveMode().equals(Mode.SEPARATE.name())) {
                btn("src_toggle", cx + 124, y, 140, 16, fromBank ? "Pay: Crew bank" : "Pay: my wallet", () -> { fromBank = !fromBank; build(); })
                    .kind(UcButton.Kind.CHIP).accent(UcUi.AMBER).tooltip("The Crew bank pays for Crew homes; you can also pay from your own wallet.");
            }
        }
        int per = offersPerPage();
        int pages = Math.max(1, (view.nearby().size() + per - 1) / per);
        findPage = Math.min(findPage, pages - 1);
        if (pages > 1) {
            btn("find_prev", cx + cw - 48, pagerY(), 22, 16, "<", () -> { findPage = Math.max(0, findPage - 1); build(); }).kind(UcButton.Kind.CHIP).active = findPage > 0;
            btn("find_next", cx + cw - 22, pagerY(), 22, 16, ">", () -> { findPage = Math.min(pages - 1, findPage + 1); build(); }).kind(UcButton.Kind.CHIP).active = findPage < pages - 1;
        }
        int top = findTop();
        for (int i = findPage * per; i < Math.min(view.nearby().size(), (findPage + 1) * per); i++) {
            CrewView.LeaseRow lease = view.nearby().get(i);
            CrewView.Offer o = lease.offer();
            int row = top + (i - findPage * per) * OFFER_H;
            boolean bank = forCrew && fromBank;
            if (o.canRent()) {
                long total = o.welcome() ? 0 : o.deposit() + o.rent();
                UcButton rent = btn("rent_" + i, cx + cw - 128, row + 4, 60, 18, "Rent", () -> send(Act.of(Op.RENT).text(lease.id()).flag(forCrew).flag2(bank)));
                rent.active = rent.active && (total == 0 || view.moneyReady() && funds(bank) >= total) && (!forCrew || view.can(Perm.MANAGE_PROPERTY));
                rent.tooltip(o.welcome() ? "The city's welcome: the first " + view.rules().welcomeCycles() + " hours are free, no deposit."
                    : total + " credits now: " + o.deposit() + " deposit (returned when you move out) and the first hour, " + o.rent() + ". Then " + o.rent() + " an hour of play.");
            }
            if (o.canBuy()) {
                UcButton buy = btn("buy_" + i, cx + cw - 64, row + 4, 60, 18, "Buy", () -> send(Act.of(Op.LEASE).text(lease.id()).flag(forCrew).flag2(bank)));
                buy.active = buy.active && view.moneyReady() && funds(bank) >= lease.price() && (!forCrew || view.can(Perm.MANAGE_PROPERTY)) && (!bank || view.can(Perm.SPEND));
                buy.tooltip(lease.price() + " credits, once" + (o.upkeep() > 0 ? ", then " + o.upkeep() + " an hour of play in upkeep" : "") + ". Owning lets you fit upgrades and sell the home back for " + view.rules().sellBackPercent() + "% of the price.");
            }
        }
    }

    private static final int OFFER_H = 64;

    private int offersPerPage() { return Math.max(1, (ch - (findTop() - cy) - 4) / OFFER_H); }

    private void renderHome(GuiGraphics g) {
        if (homeTab == 0) renderMine(g); else renderFind(g);
    }

    private static int standingColor(String standing) {
        return switch (standing) { case "LAPSED" -> UcUi.UNDERGROUND; case "NOTICE" -> UcUi.WARN; case "OVERDUE" -> UcUi.AMBER; default -> UcUi.OK; };
    }

    private static String standingWords(String standing) {
        return switch (standing) { case "LAPSED" -> "LAPSED"; case "NOTICE" -> "FINAL NOTICE"; case "OVERDUE" -> "OVERDUE"; default -> "PAID UP"; };
    }

    /** One entry of the home's detail, with its icon, on up to {@code maxLines} lines; nothing is drawn that would not end above {@code limit}. Returns the y of the next entry. */
    private int note(GuiGraphics g, UcUi.Icon icon, int iconColor, String text, int x, int y, int w, int color, int limit, int maxLines) {
        if (y + 9 > limit) return y;
        int lines = Math.max(1, Math.min(maxLines, (limit - y + 2) / 12));
        if (icon != null) UcUi.icon(g, icon, x, y - 1, iconColor);
        int end = UcUi.wrapped(g, font, text, x + (icon != null ? 18 : 0), y, w - (icon != null ? 18 : 0), color, lines);
        return Math.max(end, y + 10) + 2;
    }

    private void renderMine(GuiGraphics g) {
        int lw = homeListW(), dx = cx + lw + 8, dw = cw - lw - 8;
        UcUi.display(g, font, "Your homes", cx, cy + 22, UcUi.SOFT);
        if (view.homes().isEmpty()) {
            UcUi.wrapped(g, font, "You hold no home yet. Open Find a home beside one: a motel room costs almost nothing, and the first hours are the city's.", cx, cy + 38, cw, UcUi.FAINT, 4);
            return;
        }
        int y = cy + 22;
        for (CrewView.HomeRow h : view.homes()) {
            boolean sel = h.id().equals(selectedHome);
            g.fill(cx, y + 10, cx + lw, y + 10 + HROW - 4, sel ? 0xff1f3a52 : UcUi.ROW);
            if (sel) g.fill(cx, y + 10, cx + 3, y + 10 + HROW - 4, UcUi.LEGAL);
            UcUi.icon(g, UcUi.Icon.KEY, cx + 6, y + 15, h.here() ? UcUi.OK : UcUi.DIM);
            UcUi.text(g, font, UcUi.fit(font, h.title(), lw - 30), cx + 24, y + 14, UcUi.TEXT);
            CrewView.Tenancy t = h.tenancy();
            String line = h.owner().equals("Crew") ? "Crew" : "You";
            line += t.rented() ? "  rented" : t.tenure().equals("OWN") ? "  owned" : "";
            UcUi.text(g, font, UcUi.fit(font, line, lw - 30), cx + 24, y + 24, t.billed() && !t.standing().equals("CURRENT") ? standingColor(t.standing()) : UcUi.DIM);
            y += HROW;
        }
        CrewView.HomeRow home = chosenHome();
        CrewView.Tenancy t = home.tenancy();
        boolean upgradable = t.tenure().equals("OWN") && !view.upgrades().isEmpty();
        // the detail runs from the top; the upgrades (if any) and the buttons are fixed to the bottom, so what is drawn above them stops where they begin
        int limit = (upgradable ? upgradeTop(t) : footTop(t)) - 4;
        int ty = cy + 22;
        int chipRight = cx + cw;
        String tenure = t.rented() ? "RENTED" : "OWNED";
        int w1 = UcUi.displayWidth(font, tenure) + 10;
        int chipsLeft = chipRight - w1;
        UcUi.chip(g, font, tenure, chipsLeft, ty - 2, t.rented() ? UcUi.LEGAL : UcUi.AMBER, null);
        if (t.billed()) {
            String standing = standingWords(t.standing());
            int w2 = UcUi.displayWidth(font, standing) + 10;
            chipsLeft -= 4 + w2;
            UcUi.chip(g, font, standing, chipsLeft, ty - 2, standingColor(t.standing()), null);
        }
        UcUi.display(g, font, UcUi.fit(font, home.title(), Math.max(40, chipsLeft - dx - 6)), dx, ty, UcUi.TEXT);
        ty += 14;
        String where = (t.category().isEmpty() ? capitalize(home.kind()) : t.category()) + (t.area().isEmpty() ? "" : ", " + t.area());
        ty = note(g, null, 0, where + "  |  " + home.where(), dx, ty, dw, UcUi.DIM, limit, 1);
        if (t.billed()) {
            ty = note(g, UcUi.Icon.CREDITS, UcUi.AMBER, (t.rented() ? "Rent " : "Upkeep ") + t.charge() + " credits an hour of play", dx, ty, dw, UcUi.TEXT, limit, 1);
            if (t.arrears() > 0) {
                int color = standingColor(t.standing());
                ty = note(g, UcUi.Icon.WARNING, color, "Unpaid: " + t.arrears() + " h = " + t.owed() + " credits. " + (t.standing().equals("LAPSED") ? "Your things are safe; pay to restore the home."
                    : t.standing().equals("NOTICE") ? "Pay within the hour of play or the home lapses." : "Grace period: nothing is lost."), dx, ty, dw, color, limit, 2);
            } else {
                ty = note(g, UcUi.Icon.CLOCK, UcUi.OK, (t.prepaid() > 0 ? "Paid ahead " + t.prepaid() + " h. Next due in " : "Next payment in ") + hours(t.minutes()) + ".", dx, ty, dw, UcUi.SOFT, limit, 1);
            }
            if (t.rented() && t.deposit() > 0) ty = note(g, null, 0, "Deposit " + t.deposit() + " credits, back when you move out.", dx, ty, dw, UcUi.DIM, limit, 1);
        } else {
            ty = note(g, UcUi.Icon.CHECK, UcUi.OK, t.legacy() ? "Bought before rent existed: no charge, ever." : "No rent or upkeep.", dx, ty, dw, UcUi.SOFT, limit, 1);
        }
        ty = note(g, null, 0, "Containers " + (t.maxContainers() > 0 ? t.containers() + " of " + t.maxContainers() : "no limit") + "  |  private " + t.claimsMine() + " of " + t.claimsMax() + " each", dx, ty, dw, UcUi.SOFT, limit, 1);
        ty = note(g, null, 0, "Has: " + (t.facilities().isEmpty() ? "nothing listed" : t.facilities()), dx, ty, dw, UcUi.DIM, limit, 2);
        ty = note(g, null, 0, "Lives here: " + t.residents(), dx, ty, dw, UcUi.DIM, limit, 1);
        if (upgradable) {
            int uy = upgradeTop(t);
            for (int i = 0; i < view.upgrades().size(); i++) {
                CrewView.UpgradeRow up = view.upgrades().get(i);
                boolean fitted = java.util.Arrays.asList(t.upgrades().split(",")).contains(up.id());
                int row = uy + i * UPGRADE_H;
                g.fill(dx, row, dx + dw, row + UPGRADE_H - 2, UcUi.ROW);
                UcUi.icon(g, fitted ? UcUi.Icon.CHECK : UcUi.Icon.GEAR, dx + 2, row, fitted ? UcUi.OK : UcUi.DIM);
                String cost = up.cost() + " cr";
                int costRight = dx + dw - UPGRADE_BTN - 6;
                UcUi.text(g, font, UcUi.fit(font, up.title(), costRight - font.width(cost) - 6 - (dx + 19)), dx + 19, row + 3, fitted ? UcUi.OK : UcUi.TEXT);
                UcUi.textRight(g, font, cost, costRight, row + 3, fitted ? UcUi.DIM : UcUi.AMBER);
            }
        } else if (t.rented()) {
            ty = note(g, null, 0, "Upgrades are for homes you own. Buying also ends the rent.", dx, ty + 2, dw, UcUi.FAINT, limit, 2);
        }
        if (!t.tenure().equals("OWN") && !t.receipts().isEmpty() && limit - ty >= 12) {
            UcUi.text(g, font, fitSegments("Last: " + t.receipts().get(0), dw), dx, limit - 8, UcUi.FAINT);
        }
    }

    /** A receipt's leading segments (they are separated by two spaces) that fit the width: a receipt is cut between its parts, never in the middle of a word. */
    private String fitSegments(String text, int width) {
        StringBuilder out = new StringBuilder();
        for (String part : text.split("  ")) {
            String next = out.length() == 0 ? part : out + "  " + part;
            if (font.width(next) > width) break;
            out = new StringBuilder(next);
        }
        return out.length() == 0 ? UcUi.fit(font, text, width) : out.toString();
    }

    private void renderFind(GuiGraphics g) {
        int per = offersPerPage();
        if (view.nearby().isEmpty()) {
            UcUi.wrapped(g, font, "No home on offer within 64 blocks. Each home is held by one person or Crew at a time: stand beside the Row Motel or the Row Flats.", cx, findTop() + 4, cw, UcUi.FAINT, 3);
            return;
        }
        int top = findTop();
        int textW = cw - 140;
        for (int i = findPage * per; i < Math.min(view.nearby().size(), (findPage + 1) * per); i++) {
            CrewView.LeaseRow lease = view.nearby().get(i);
            CrewView.Offer o = lease.offer();
            int row = top + (i - findPage * per) * OFFER_H;
            UcUi.card(g, cx, row, cw, OFFER_H - 4, 0);
            int titleW = Math.min(font.width(lease.title()), textW - 80);
            UcUi.text(g, font, UcUi.fit(font, lease.title(), titleW), cx + 8, row + 5, UcUi.TEXT);
            if (!o.category().isEmpty()) {
                String category = UcUi.fit(font, o.category().toUpperCase(Locale.ROOT), Math.max(40, textW - titleW - 22));
                UcUi.chip(g, font, category, cx + 8 + titleW + 6, row + 3, UcUi.LEGAL, null);
            }
            // one line for renting and one for buying: neither is cut short
            int ty = row + 16;
            if (o.canRent()) {
                String rent = o.welcome() ? "Rent " + o.rent() + "/h. The first " + view.rules().welcomeCycles() + " hours are free." : "Rent " + o.rent() + "/h, deposit " + o.deposit();
                UcUi.icon(g, UcUi.Icon.CREDITS, cx + 6, ty - 2, UcUi.AMBER);
                UcUi.text(g, font, UcUi.fit(font, rent, textW - 20), cx + 24, ty, UcUi.AMBER);
                ty += 11;
            }
            if (o.canBuy()) {
                String buy = "Buy " + lease.price() + (lease.starter() ? " (starter price)" : "") + (o.upkeep() > 0 ? ", upkeep " + o.upkeep() + "/h" : ", no upkeep");
                if (!o.canRent()) UcUi.icon(g, UcUi.Icon.CREDITS, cx + 6, ty - 2, UcUi.AMBER);
                UcUi.text(g, font, UcUi.fit(font, buy, textW - 20), cx + 24, ty, UcUi.AMBER);
                ty += 11;
            }
            String extra = o.summary().isEmpty() ? capitalize(lease.kind()) + ", " + lease.distance() + " m away" : o.summary();
            int lines = Math.max(1, (row + OFFER_H - 5 - (ty + 1)) / 10);
            UcUi.wrapped(g, font, extra, cx + 8, ty + 1, textW, UcUi.DIM, Math.min(2, lines));
            if (o.factor() != 100) UcUi.text(g, font, "prices x" + String.format(Locale.ROOT, "%.2f", o.factor() / 100.0), cx + cw - 128, row + 28, UcUi.FAINT);
        }
        int pages = Math.max(1, (view.nearby().size() + per - 1) / per);
        if (pages > 1) UcUi.textRight(g, font, (findPage + 1) + " / " + pages, cx + cw - 54, pagerY() + 4, UcUi.FAINT);
    }

    // ---- RULES

    private static final String[] MODE_TEXT = {
        "Everybody keeps their own money. There is no standing bank; whoever makes a Crew purchase pays it.",
        "Everybody keeps their own money, and the Crew has a shared bank. Members deposit; the rules decide who withdraws and spends. Recommended.",
        "Like the bank, and while every member agrees, the pay of work orders goes into it and exchange purchases are paid from it first, by members who may spend Crew money. Q Shop's own shop screens never use the bank."};

    /** The height of each money-mode card, from the lines its text needs at this width; the first two give up room if the page is short. Used by the build and the draw alike. */
    private int[] modeCardHeights() {
        int textW = cw - 98;
        int[] heights = new int[MODE_TEXT.length];
        int total = 0;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.max(36, 20 + font.split(Component.literal(MODE_TEXT[i]), textW).size() * 10 + 4);
            total += heights[i] + 4;
        }
        int shrink = Math.max(0, total - (ch - 24 - 8));
        for (int i = 0; i < 2; i++) heights[i] -= Math.min(shrink / 2, heights[i] - 36);
        return heights;
    }

    private void buildRules() {
        String[] tabs = {"Permissions", "Money mode", "Crew"};
        int tx = cx;
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            int w = UcUi.displayWidth(font, tabs[i]) + 22;
            UcButton chip = btn("rules_tab_" + i, tx, cy, w, 16, tabs[i], () -> { rulesTab = index; build(); }).kind(UcButton.Kind.CHIP).accent(UcUi.LEGAL);
            chip.selected(rulesTab == i);
            tx += w + 4;
        }
        int top = cy + 24;
        if (rulesTab == 0) {
            Perm[] perms = Perm.values();
            int rowH = Math.max(15, Math.min(20, (ch - 24 - 14) / perms.length));
            for (int i = 0; i < perms.length; i++) {
                Perm perm = perms[i];
                int y = top + 14 + i * rowH;
                for (int c = 0; c < 2; c++) {
                    boolean manager = c == 0;
                    boolean on = ((manager ? view.managerPerms() : view.memberPerms()) & (1 << perm.ordinal())) != 0;
                    UcButton toggle = btn("perm_" + (manager ? "m_" : "x_") + i, cx + cw - 110 + c * 56, y, 50, rowH - 2, on ? "Yes" : "No", () -> send(Act.of(Op.PERMISSION).amount(perm.ordinal()).flag2(manager).flag(!on)))
                        .kind(UcButton.Kind.CHIP).accent(on ? UcUi.OK : UcUi.UNDERGROUND);
                    toggle.selected(on);
                    toggle.active = toggle.active && view.founder();
                    toggle.tooltip(view.founder() ? perm.hint : "Only the founder changes these.");
                }
            }
        } else if (rulesTab == 1) {
            Mode[] modes = Mode.values();
            int[] heights = modeCardHeights();
            int y = top;
            for (int i = 0; i < modes.length; i++) {
                Mode mode = modes[i];
                UcButton choose = btn("mode_" + mode.name().toLowerCase(Locale.ROOT), cx + cw - 82, y + heights[i] - 24, 76, 18, view.mode().equals(mode.name()) ? "Chosen" : "Choose", () -> send(Act.of(Op.MODE).amount(mode.ordinal())));
                choose.kind(UcButton.Kind.PRIMARY);
                choose.active = choose.active && view.founder() && !view.mode().equals(mode.name());
                choose.tooltip(view.founder() ? "Changing the mode never moves a credit." : "Only the founder chooses.");
                y += heights[i] + 4;
            }
            if (view.bank() > 0 && view.founder()) {
                btn("pay_out", cx, top + java.util.Arrays.stream(modeCardHeights()).sum() + 3 * 4 + 2, 170, 18, "Pay out the bank (" + view.bank() + ")", () -> send(Act.of(Op.PAY_OUT))).kind(UcButton.Kind.DANGER)
                    .tooltip("Splits the bank equally between the members; any remainder goes to the founder. Each member then claims their share.");
            }
        } else {
            int y = top;
            place(renameBox, cx, y + 14, Math.min(cw - 110, 220));
            UcButton rename = btn("rename", cx + Math.min(cw - 110, 220) + 6, y + 14, 90, 20, "Rename", () -> send(Act.of(Op.RENAME).text(renameBox.getValue())));
            rename.active = rename.active && view.founder() && renameBox.getValue().trim().length() >= CrewRules.NAME_MIN;
            int dy = y + 70;
            if (!view.disbandArmed()) {
                UcButton start = btn("disband_ask", cx, dy + 40, 150, 20, "Disband the Crew...", () -> send(Act.of(Op.DISBAND_ASK))).kind(UcButton.Kind.DANGER);
                start.active = start.active && view.founder();
                start.tooltip(view.founder() ? "Asks you to type the Crew's name before anything happens." : "Only the founder can disband the Crew.");
            } else {
                place(confirmBox, cx, dy + 40, Math.min(cw - 140, 220));
                UcButton confirm = btn("disband", cx + Math.min(cw - 140, 220) + 6, dy + 40, 124, 20, "Disband for good", () -> send(Act.of(Op.DISBAND).text(confirmBox.getValue()))).kind(UcButton.Kind.DANGER);
                confirm.active = confirm.active && confirmBox.getValue().equals(view.crewName());
            }
        }
    }

    private void renderRules(GuiGraphics g) {
        int top = cy + 24;
        if (rulesTab == 0) {
            UcUi.text(g, font, "What each role may do", cx, top, UcUi.DIM);
            UcUi.display(g, font, "Manager", cx + cw - 110, top, UcUi.SOFT);
            UcUi.display(g, font, "Member", cx + cw - 54, top, UcUi.SOFT);
            Perm[] perms = Perm.values();
            int rowH = Math.max(15, Math.min(20, (ch - 24 - 14) / perms.length));
            for (int i = 0; i < perms.length; i++) {
                int y = top + 14 + i * rowH;
                g.fill(cx, y, cx + cw - 116, y + rowH - 2, i % 2 == 0 ? UcUi.ROW : 0xff111c27);
                UcUi.text(g, font, UcUi.fit(font, perms[i].title, cw - 124), cx + 6, y + (rowH - 10) / 2, UcUi.TEXT);
            }
        } else if (rulesTab == 1) {
            Mode[] modes = Mode.values();
            int[] heights = modeCardHeights();
            int textW = cw - 98;
            int y = top;
            for (int i = 0; i < modes.length; i++) {
                int h = heights[i];
                boolean chosen = view.mode().equals(modes[i].name());
                UcUi.card(g, cx, y, cw, h, chosen ? 2 : 0);
                UcUi.display(g, font, modes[i].title, cx + 8, y + 6, chosen ? UcUi.TEXT : UcUi.SOFT);
                if (modes[i] == Mode.SHARED && chosen) {
                    boolean active = view.effectiveMode().equals(Mode.SHARED.name());
                    String state = active ? "in force" : "waiting: " + view.consentCount() + " of " + view.members().size() + " agree";
                    UcUi.text(g, font, state, cx + cw - 90 - font.width(state), y + 7, active ? UcUi.OK : UcUi.WARN);
                }
                UcUi.wrapped(g, font, MODE_TEXT[i], cx + 8, y + 18, textW, UcUi.DIM, Math.max(2, (h - 20) / 10));
                y += h + 4;
            }
        } else {
            int y = top;
            UcUi.text(g, font, "Rename the Crew", cx, y, UcUi.DIM);
            UcUi.well(g, renameBox.getX() - 4, renameBox.getY() - 4, renameBox.getWidth() + 8, 20);
            int dy = y + 70;
            UcUi.display(g, font, "Disband", cx, dy, UcUi.UNDERGROUND_TEXT);
            UcUi.wrapped(g, font, "The bank is split equally between the members, every Crew home becomes yours personally, and everyone keeps their own wallet, career and homes.",
                cx, dy + 12, cw, UcUi.FAINT, 3);
            if (view.disbandArmed()) UcUi.well(g, confirmBox.getX() - 4, confirmBox.getY() - 4, confirmBox.getWidth() + 8, 20);
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing and input

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (!view.storageOk()) {
            UcUi.icon(g, UcUi.Icon.WARNING, px + PAD, cy + 4, UcUi.UNDERGROUND);
            UcUi.wrapped(g, font, view.problem(), px + PAD + 24, cy + 6, pw - 2 * PAD - 24, UcUi.UNDERGROUND_TEXT, 5);
            return;
        }
        if (!view.citizen()) {
            UcUi.wrapped(g, font, "Register as a citizen first: choose your city and trade at the registrar in Founders Row or with /uc start. Then come back.", px + PAD, cy + 6, pw - 2 * PAD, UcUi.SOFT, 4);
            return;
        }
        UcUi.well(g, px + PAD - 2, cy - 2, railW + 4, ch + 4);
        if (!view.inCrew() && page == Page.CREW) { renderNoCrew(g); return; }
        switch (page) {
            case CREW -> renderCrew(g, mouseX, mouseY);
            case MONEY -> renderMoney(g, mouseX, mouseY);
            case HOME -> renderHome(g);
            case RULES -> renderRules(g);
        }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (page == Page.HOME && homeTab == 0) {
            int y = cy + 22;
            for (CrewView.HomeRow home : view.homes()) {
                if (mouseX >= cx && mouseX < cx + homeListW() && mouseY >= y && mouseY < y + HROW - 2) { selectedHome = home.id(); confirmLeave = ""; build(); return true; }
                y += HROW;
            }
        }
        if (view.inCrew() && page == Page.CREW) {
            int y = cy + 18, lw = listW();
            for (CrewView.MemberRow m : view.members()) {
                if (mouseX >= cx && mouseX < cx + lw && mouseY >= y && mouseY < y + MROW - 2) { selected = m.id(); build(); return true; }
                y += MROW;
            }
        }
        return false;
    }

    @Override public void tick() {
        super.tick();
        if (busy && ++busyTicks > 60) { busy = false; build(); } // the server never answered (a dropped request): let the player try again
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        boolean typing = (nameBox != null && nameBox.isFocused()) || (amountBox != null && amountBox.isFocused()) || (renameBox != null && renameBox.isFocused()) || (confirmBox != null && confirmBox.isFocused());
        if (!typing && ClientCrew.KEY.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    /** The server answered: take the new numbers and keep the page, selection and typing. */
    void update(CrewView next, String message) {
        CrewView old = view;
        view = next;
        if (message != null && !message.isEmpty()) note = message;
        busy = false;
        if (selected != null && member(selected) == null) selected = null;
        if (!selectedHome.isEmpty() && home(selectedHome) == null) { selectedHome = ""; confirmLeave = ""; }
        if (old.homes().size() != next.homes().size()) confirmLeave = "";
        if (!old.inCrew() && next.inCrew()) page = Page.CREW;
        if (old.inCrew() && !next.inCrew()) { page = Page.CREW; rulesTab = 0; }
        if (old.disbandArmed() && !next.disbandArmed()) confirmBox.setValue("");
        build();
    }

    // ------------------------------------------------------------------------------------------------ helpers and QA hooks

    private static String capitalize(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static int roleColor(String role) {
        return switch (role) { case "Founder" -> UcUi.AMBER; case "Manager" -> UcUi.LEGAL; default -> UcUi.DIM; };
    }

    private static String modeShort(String name) {
        return switch (name) { case "SEPARATE" -> "no bank: money is separate"; case "SHARED" -> "shared finances"; default -> "Crew bank"; };
    }

    CrewView snapshot() { return view; }
    Page currentPage() { return page; }
    public void showPage(Page next) { page = next; build(); }
    public void showRulesTab(int tab) { rulesTab = tab; build(); }
    public void select(UUID member) { selected = member; build(); }
    /** QA hooks for the Home page: its tab (0 your homes, 1 find a home) and which held home is shown. */
    public void showHomeTab(int tab) { homeTab = tab; build(); }
    public void selectHome(String id) { selectedHome = id; build(); }
    /** QA hooks: who a home found on the Find tab would be for, and where its money would come from. */
    public boolean forCrewChosen() { return forCrew; }
    public boolean fromBankChosen() { return fromBank; }
    public EditBox nameField() { return nameBox; }
    public EditBox amountField() { return amountBox; }
    public EditBox confirmField() { return confirmBox; }
    public EditBox renameField() { return renameBox; }
    /** QA hooks: text for the amount and confirmation boxes that is used when the screen opens (or put in them straight away if it is open). */
    public void presetAmount(String text) { presetAmount = text; if (amountBox != null) { amountBox.setValue(text); build(); } }
    public void presetConfirm(String text) { presetConfirm = text; if (confirmBox != null) { confirmBox.setValue(text); build(); } }
    public void rebuild() { build(); }
    boolean busy() { return busy; }

    /** QA hook: the button with this id, or null; ids are listed in the build methods ("create", "accept_0", "invite_0", "deposit", "withdraw" and so on). */
    public UcButton button(String id) { return byId.get(id); }

    /** QA hook: the centre of a button in GUI coordinates, or null; used by the scripted player to click where a person would. */
    public int[] centreOf(String id) {
        UcButton b = byId.get(id);
        return b == null || !b.visible ? null : new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
    }

    /** QA hook: presses a button exactly as a click on it would (it must be active and visible). */
    public boolean press(String id) {
        UcButton b = byId.get(id);
        if (b == null || !b.visible || !b.active) return false;
        return b.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0);
    }

    public List<String> buttonIds() { return List.copyOf(byId.keySet()); }
}
