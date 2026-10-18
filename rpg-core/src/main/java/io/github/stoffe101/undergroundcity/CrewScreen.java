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
        if (!view.inCrew()) { buildNoCrew(); return; }
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            UcButton button = add(new UcButton(px + PAD, cy + i * 30, railW, 26, p.label, b -> { page = p; build(); }).kind(UcButton.Kind.RAIL).icon(p.icon)
                .tooltip(railW < 50 ? p.label : ""));
            button.selected(p == page).accent(UcUi.LEGAL);
            byId.put("rail_" + p.name().toLowerCase(Locale.ROOT), button);
            if (p == Page.CREW) button.badge(view.members().size());
            if (p == Page.MONEY && view.owed() > 0) button.badge(1);
        }
        switch (page) {
            case CREW -> buildCrew();
            case MONEY -> buildMoney();
            case HOME -> buildHome();
            case RULES -> buildRules();
        }
    }

    // ---- no Crew

    private void buildNoCrew() {
        int x = px + PAD, w = pw - 2 * PAD, y = cy + 56;
        int half = Math.min(w, 300);
        place(nameBox, x, y + 20, half - 110);
        UcButton found = btn("create", x + half - 104, y + 20, 104, 20, "Found Crew", () -> send(Act.of(Op.CREATE).text(nameBox.getValue()))).kind(UcButton.Kind.PRIMARY);
        found.active = found.active && nameBox.getValue().trim().length() >= CrewRules.NAME_MIN;
        found.tooltip("Founds a Crew with you as its founder.", "Names have 3 to 24 letters, digits and ' - _ .");
        int iy = y + 84;
        int i = 0;
        for (CrewView.InviteRow invite : view.incoming()) {
            int row = iy + i * 26;
            btn("accept_" + i, px + pw - PAD - 136, row, 64, 20, "Accept", () -> send(Act.of(Op.ACCEPT).target(invite.id()))).kind(UcButton.Kind.PRIMARY);
            btn("decline_" + i, px + pw - PAD - 68, row, 64, 20, "Decline", () -> send(Act.of(Op.DECLINE).target(invite.id())));
            i++;
        }
    }

    private void renderNoCrew(GuiGraphics g) {
        int x = px + PAD, w = pw - 2 * PAD, y = cy + 56;
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

    private void buildMoney() {
        int y = cy + 56 + (view.owed() > 0 ? 28 : 0) + (needsConsent() ? 52 : 0);
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
            int cyy = cy + 56 + (view.owed() > 0 ? 28 : 0);
            btn("consent_yes", cx + cw - 190, cyy + 28, 90, 18, "I agree", () -> send(Act.of(Op.CONSENT).flag(true))).kind(UcButton.Kind.PRIMARY);
            btn("consent_no", cx + cw - 96, cyy + 28, 96, 18, "Not for me", () -> send(Act.of(Op.CONSENT).flag(false)));
        }
        if (cardW < 0) return;
    }

    private boolean needsConsent() { return view.mode().equals(Mode.SHARED.name()) && !view.consentSelf(); }

    private void renderMoney(GuiGraphics g, int mx, int my) {
        int cardW = (cw - 8) / 2;
        card(g, cx, cy, cardW, 50, "CREW BANK", view.bank() + "", UcUi.AMBER, modeShort(view.effectiveMode()) + (view.mode().equals(view.effectiveMode()) ? "" : " (not shared yet)"));
        card(g, cx + cardW + 8, cy, cardW, 50, "YOUR WALLET", view.moneyReady() ? String.format(Locale.ROOT, "%.0f", view.wallet()) : "OFFLINE", UcUi.OK, "stays yours");
        int y = cy + 56;
        if (view.owed() > 0) {
            g.fill(cx, y, cx + cw, y + 22, 0xff2a2410);
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + 4, y + 3, UcUi.AMBER);
            UcUi.text(g, font, UcUi.fit(font, view.owed() + " credits are owed to you.", cw - 24 - 134), cx + 24, y + 7, UcUi.AMBER);
            y += 28;
        }
        if (needsConsent()) {
            g.fill(cx, y, cx + cw, y + 48, 0xff261a12);
            UcUi.icon(g, UcUi.Icon.WARNING, cx + 4, y + 4, UcUi.WARN);
            UcUi.wrapped(g, font, "The founder proposes shared finances: work-order pay goes into the bank and exchange purchases come out of it, once everyone agrees.",
                cx + 24, y + 4, cw - 30, UcUi.SOFT, 2);
            y += 52;
        }
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
            int color = row.kind().equals("DEPOSIT") ? UcUi.OK : row.kind().equals("WITHDRAW") || row.kind().equals("LEASE") ? UcUi.AMBER : UcUi.SOFT;
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
            case "LEASE" -> who + " leased " + row.note() + " (" + row.amount() + ")";
            case "PAYOUT" -> who + " paid out " + row.amount() + ", " + row.note();
            default -> who + " " + row.kind().toLowerCase(Locale.ROOT);
        };
    }

    // ---- HOME

    private void buildHome() {
        int y = cy + 18;
        boolean canLeaseCrew = view.can(Perm.MANAGE_PROPERTY);
        for (CrewView.HomeRow home : view.homes()) y += ROW;
        boolean here = view.homes().stream().anyMatch(CrewView.HomeRow::here);
        UcButton claim = btn("claim_container", cx, y + 2, 150, 18, "Make container private", () -> send(Act.of(Op.CLAIM_CONTAINER)));
        claim.active = claim.active && here;
        claim.tooltip(here ? "Look at a chest or barrel in your home first. Only you can open it, and nobody else can break it." : "Stand inside one of your homes.");
        UcButton release = btn("release_container", cx + 156, y + 2, 140, 18, "Share it again", () -> send(Act.of(Op.RELEASE_CONTAINER)));
        release.active = release.active && here;
        release.tooltip("Look at your private container to make it shared again.");
        int ny = y + 46;
        for (int i = 0; i < view.nearby().size(); i++) {
            CrewView.LeaseRow lease = view.nearby().get(i);
            int row = ny + i * ROW;
            UcButton bank = btn("lease_bank_" + i, cx + cw - 232, row, 76, 18, "Crew bank", () -> send(Act.of(Op.LEASE).text(lease.id()).flag(true).flag2(true)));
            bank.active = bank.active && view.inCrew() && canLeaseCrew && view.can(Perm.SPEND) && view.moneyReady() && !view.effectiveMode().equals(Mode.SEPARATE.name()) && view.bank() >= lease.price();
            bank.tooltip(!canLeaseCrew ? "Your role may not lease for the Crew." : view.bank() < lease.price() ? "The bank holds " + view.bank() + " credits." : "Pay " + lease.price() + " credits from the Crew bank. The home is the Crew's.");
            UcButton crewMine = btn("lease_crew_" + i, cx + cw - 152, row, 76, 18, "Crew (mine)", () -> send(Act.of(Op.LEASE).text(lease.id()).flag(true).flag2(false)));
            crewMine.active = crewMine.active && canLeaseCrew && view.moneyReady() && view.wallet() >= lease.price();
            crewMine.tooltip("Pay " + lease.price() + " credits from your own wallet. The home is the Crew's.");
            UcButton mine = btn("lease_me_" + i, cx + cw - 72, row, 72, 18, "For me", () -> send(Act.of(Op.LEASE).text(lease.id()).flag(false).flag2(false)));
            mine.active = mine.active && view.moneyReady() && view.wallet() >= lease.price();
            mine.tooltip("Pay " + lease.price() + " credits from your wallet. The home is yours alone; a Crew does not change that.");
        }
    }

    private void renderHome(GuiGraphics g) {
        UcUi.display(g, font, "Your homes", cx, cy + 4, UcUi.SOFT);
        int y = cy + 18;
        if (view.homes().isEmpty()) {
            UcUi.text(g, font, "You and your Crew hold no home yet.", cx, y + 4, UcUi.FAINT);
            y += ROW;
        }
        for (CrewView.HomeRow home : view.homes()) {
            g.fill(cx, y, cx + cw, y + ROW - 2, UcUi.ROW);
            UcUi.icon(g, UcUi.Icon.KEY, cx + 4, y + 3, home.here() ? UcUi.OK : UcUi.DIM);
            UcUi.text(g, font, UcUi.fit(font, home.title(), cw - 190), cx + 24, y + 3, UcUi.TEXT);
            UcUi.text(g, font, capitalize(home.kind()) + "  at " + home.where() + (home.mine() > 0 ? "  |  " + home.mine() + " private" : ""), cx + 24, y + 12, UcUi.DIM);
            String owner = home.owner().toUpperCase(Locale.ROOT);
            int w = UcUi.displayWidth(font, owner) + 8;
            UcUi.chip(g, font, owner, cx + cw - w - 6, y + 5, home.owner().equals("Crew") ? UcUi.AMBER : UcUi.LEGAL, null);
            y += ROW;
        }
        int ny = y + 46;
        UcUi.display(g, font, "Homes for lease nearby", cx, ny - 17, UcUi.SOFT);
        if (view.nearby().isEmpty()) {
            UcUi.wrapped(g, font, "No unleased home within 64 blocks. Homes are finite: each is leased once, for credits.", cx, ny, cw, UcUi.FAINT, 3);
        }
        for (int i = 0; i < view.nearby().size(); i++) {
            CrewView.LeaseRow lease = view.nearby().get(i);
            int row = ny + i * ROW;
            g.fill(cx, row - 2, cx + cw, row + ROW - 4, i % 2 == 0 ? UcUi.ROW : 0xff111c27);
            UcUi.text(g, font, UcUi.fit(font, lease.title(), cw - 250), cx + 4, row + 1, UcUi.TEXT);
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + 4, row + 8, UcUi.AMBER);
            UcUi.text(g, font, lease.price() + " credits" + (lease.starter() ? "  starter price" : "") + "  " + lease.distance() + " m", cx + 22, row + 12, lease.starter() ? UcUi.OK : UcUi.AMBER);
        }
    }

    // ---- RULES

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
            int cardH = Math.max(40, (ch - 24 - 16) / 3 - 4);
            for (int i = 0; i < modes.length; i++) {
                Mode mode = modes[i];
                int y = top + i * (cardH + 4);
                UcButton choose = btn("mode_" + mode.name().toLowerCase(Locale.ROOT), cx + cw - 82, y + cardH - 24, 76, 18, view.mode().equals(mode.name()) ? "Chosen" : "Choose", () -> send(Act.of(Op.MODE).amount(mode.ordinal())));
                choose.kind(UcButton.Kind.PRIMARY);
                choose.active = choose.active && view.founder() && !view.mode().equals(mode.name());
                choose.tooltip(view.founder() ? "Changing the mode never moves a credit." : "Only the founder chooses.");
            }
            if (view.bank() > 0 && view.founder()) {
                btn("pay_out", cx, top + 3 * (cardH + 4) + 2, 170, 18, "Pay out the bank (" + view.bank() + ")", () -> send(Act.of(Op.PAY_OUT))).kind(UcButton.Kind.DANGER)
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
            String[] text = {
                "Everybody keeps their own money. There is no standing bank; whoever makes a Crew purchase pays it.",
                "Everybody keeps their own money, and the Crew has a shared bank. Members deposit; the rules decide who withdraws and spends. Recommended.",
                "Like the bank, and while every member agrees, the pay of work orders goes into it and purchases at the regional exchange come out of it. Q Shop's own shop screens still use your own wallet."};
            int cardH = Math.max(40, (ch - 24 - 16) / 3 - 4);
            for (int i = 0; i < modes.length; i++) {
                int y = top + i * (cardH + 4);
                boolean chosen = view.mode().equals(modes[i].name());
                UcUi.card(g, cx, y, cw, cardH, chosen ? 2 : 0);
                UcUi.display(g, font, modes[i].title, cx + 8, y + 6, chosen ? UcUi.TEXT : UcUi.SOFT);
                if (modes[i] == Mode.SHARED && chosen) {
                    boolean active = view.effectiveMode().equals(Mode.SHARED.name());
                    String state = active ? "in force" : "waiting: " + view.consentCount() + " of " + view.members().size() + " agree";
                    UcUi.text(g, font, state, cx + cw - 90 - font.width(state), y + 7, active ? UcUi.OK : UcUi.WARN);
                }
                UcUi.wrapped(g, font, text[i], cx + 8, y + 18, cw - 98, UcUi.DIM, Math.max(2, (cardH - 22) / 10));
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
        if (!view.inCrew()) { renderNoCrew(g); return; }
        UcUi.well(g, px + PAD - 2, cy - 2, railW + 4, ch + 4);
        switch (page) {
            case CREW -> renderCrew(g, mouseX, mouseY);
            case MONEY -> renderMoney(g, mouseX, mouseY);
            case HOME -> renderHome(g);
            case RULES -> renderRules(g);
        }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
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
