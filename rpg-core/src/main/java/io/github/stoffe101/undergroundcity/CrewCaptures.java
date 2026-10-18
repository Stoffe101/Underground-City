package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Fixture views of the Crew screen for the review captures (visual QA). They are made up; nothing here is read from a world. */
@OnlyIn(Dist.CLIENT)
final class CrewCaptures {
    private CrewCaptures() {}

    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-0000000000a1"), MATE = UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
        THIRD = UUID.fromString("00000000-0000-0000-0000-0000000000a3"), CREW = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final int MEMBER_BITS = CrewView.bits(EnumSet.of(Perm.ACCESS_HOME, Perm.BUILD, Perm.STORAGE, Perm.WORKSTATIONS));
    private static final int ALL_BITS = CrewView.bits(EnumSet.allOf(Perm.class));

    private static List<CrewView.MemberRow> members() {
        return List.of(new CrewView.MemberRow(SELF, "Imogen", "Founder", "farmer", 7, true, "online"),
            new CrewView.MemberRow(MATE, "Tobias", "Manager", "gunsmith", 5, true, "online"),
            new CrewView.MemberRow(THIRD, "Wren", "Member", "vintner", 3, false, "2 days ago"));
    }

    private static List<CrewView.LogRow> log() {
        return List.of(new CrewView.LogRow("4 min ago", "Tobias", "DEPOSIT", 80, ""), new CrewView.LogRow("22 min ago", "Imogen", "LEASE", 140, "Row House"),
            new CrewView.LogRow("1 h ago", "Imogen", "DEPOSIT", 120, ""), new CrewView.LogRow("1 h ago", "Wren", "JOINED", 0, ""),
            new CrewView.LogRow("2 h ago", "Imogen", "PERM", 0, "Member may: Use workstations"), new CrewView.LogRow("2 h ago", "Imogen", "CREATED", 0, ""));
    }

    private static CrewView crew(String role, int perms, String mode, String effective, long bank, double wallet, long owed, boolean consentSelf, boolean armed,
                                 List<CrewView.Candidate> candidates, List<CrewView.InviteRow> outgoing, List<CrewView.HomeRow> homes, List<CrewView.LeaseRow> nearby) {
        return new CrewView(true, "", role.equals("Founder") ? SELF : THIRD, role.equals("Founder") ? "Imogen" : "Wren", true, true, wallet, owed, false, true, CREW, "Night Owls", role, perms,
            mode, effective, consentSelf, 2, bank, CrewView.bits(EnumSet.allOf(Perm.class)), MEMBER_BITS | CrewView.bits(EnumSet.of(Perm.WITHDRAW)), false, armed,
            members(), List.of(), outgoing, candidates, log(), homes, nearby);
    }

    private static final List<CrewView.HomeRow> HOMES = List.of(
        new CrewView.HomeRow("row_flats:row_house", "Row House", "house", "Crew", "412 70 -88", 2, true),
        new CrewView.HomeRow("row_flats:flat_1", "Row Flat 1", "flat", "You", "398 70 -88", 0, false));
    private static final List<CrewView.LeaseRow> NEARBY = List.of(
        new CrewView.LeaseRow("row_flats:flat_2", "Row Flat 2", "flat", 120, false, 14));

    static Screen screen(String pose) {
        CrewScreen screen;
        switch (pose) {
            case "nocrew-invited" -> screen = new CrewScreen(new CrewView(true, "", SELF, "Imogen", true, true, 140, 0, true, false, new UUID(0, 0), "", "", 0, "BANK", "BANK", false, 0, 0, 0, 0, false, false,
                List.of(), List.of(new CrewView.InviteRow(UUID.randomUUID(), "Night Owls", "Tobias", "5 min ago"), new CrewView.InviteRow(UUID.randomUUID(), "Tin Roof Collective", "Marguerite", "2 days ago")),
                List.of(), List.of(), List.of(), List.of(), List.of()), "");
            case "nocrew" -> screen = new CrewScreen(new CrewView(true, "", SELF, "Imogen", true, true, 140, 0, true, false, new UUID(0, 0), "", "", 0, "BANK", "BANK", false, 0, 0, 0, 0, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), "");
            case "unregistered" -> screen = new CrewScreen(new CrewView(true, "", SELF, "Imogen", false, false, 0, 0, true, false, new UUID(0, 0), "", "", 0, "BANK", "BANK", false, 0, 0, 0, 0, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), "");
            case "unavailable" -> screen = new CrewScreen(CrewView.unavailableForCapture("Crew storage could not be read. Ask the host to restore data/underground_city_crews.dat from a backup."), "");
            case "members" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false,
                    List.of(new CrewView.Candidate("Marguerite", "technician"), new CrewView.Candidate("Doyle", "smith")),
                    List.of(new CrewView.InviteRow(UUID.randomUUID(), "Night Owls", "Pip", "just now")), HOMES, NEARBY), "Invitation sent to Pip.");
                screen.select(MATE);
            }
            case "members-member" -> screen = new CrewScreen(crew("Member", MEMBER_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), HOMES, List.of()), "");
            case "money" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.MONEY);
                screen.presetAmount("50");
            }
            case "money-consent" -> {
                screen = new CrewScreen(crew("Member", MEMBER_BITS, "SHARED", "BANK", 320, 140, 85, false, false, List.of(), List.of(), HOMES, List.of()), "!The bank only holds 320 credits.");
                screen.showPage(CrewScreen.Page.MONEY);
            }
            case "home" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.HOME);
            }
            case "rules-permissions" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.RULES);
            }
            case "rules-mode" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "SHARED", "BANK", 320, 140, 0, true, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.RULES);
                screen.showRulesTab(1);
            }
            case "rules-disband" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, true, List.of(), List.of(), HOMES, NEARBY), "Type the Crew's name to confirm.");
                screen.showPage(CrewScreen.Page.RULES);
                screen.showRulesTab(2);
                screen.presetConfirm("Night Owls");
            }
            default -> throw new IllegalArgumentException("unknown Crew capture " + pose);
        }
        return screen;
    }
}
