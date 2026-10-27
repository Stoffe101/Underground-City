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
        return List.of(new CrewView.LogRow("2 min ago", "Tobias", "PURCHASE", 32, "1 x Waterskin"), new CrewView.LogRow("4 min ago", "Tobias", "DEPOSIT", 80, ""),
            new CrewView.LogRow("22 min ago", "Imogen", "LEASE", 140, "Row House"),
            new CrewView.LogRow("1 h ago", "Imogen", "DEPOSIT", 120, ""), new CrewView.LogRow("1 h ago", "Wren", "JOINED", 0, ""),
            new CrewView.LogRow("2 h ago", "Imogen", "PERM", 0, "Member may: Use workstations"), new CrewView.LogRow("2 h ago", "Imogen", "CREATED", 0, ""));
    }

    private static CrewView crew(String role, int perms, String mode, String effective, long bank, double wallet, long owed, boolean consentSelf, boolean armed,
                                 List<CrewView.Candidate> candidates, List<CrewView.InviteRow> outgoing, List<CrewView.HomeRow> homes, List<CrewView.LeaseRow> nearby) {
        return new CrewView(true, "", role.equals("Founder") ? SELF : THIRD, role.equals("Founder") ? "Imogen" : "Wren", true, true, wallet, owed, false, true, CREW, "Night Owls", role, perms,
            mode, effective, consentSelf, 2, bank, CrewView.bits(EnumSet.allOf(Perm.class)), MEMBER_BITS | CrewView.bits(EnumSet.of(Perm.WITHDRAW)), false, armed,
            members(), List.of(), outgoing, candidates, log(), homes, nearby, UPGRADES, CrewView.RulesRow.DEFAULT);
    }

    // the numbers are the shipped ladder's (rent 40 for a flat, 100 for the house, 10 for a motel room), not a measurement of play
    private static CrewView.Tenancy tenancy(String tenure, String standing, String category, String area, int charge, int deposit, int minutes, int prepaid, int arrears, boolean autopay,
                                            boolean crewHome, int containers, int maxContainers, String facilities, String upgrades, String residents, int refund, List<String> receipts) {
        return new CrewView.Tenancy(tenure, standing, category, area, charge, deposit, minutes, prepaid, arrears, arrears * charge, autopay, true, crewHome, containers, maxContainers, 1,
            maxContainers <= 2 ? 1 : maxContainers <= 5 ? 2 : 3, facilities, upgrades, residents, false, refund, receipts);
    }

    private static final CrewView.Tenancy FLAT = tenancy("RENT", "CURRENT", "Small apartment", "Row Flats", 40, 80, 38, 3, 0, true, false, 3, 5, "bed, storage, crafting, furnace, terminal", "", "Imogen", 200,
        List.of("12 min ago  PAID 40  1 hour", "1 h ago  RENTED 120  Row Flat 1"));
    private static final CrewView.Tenancy FLAT_OVERDUE = tenancy("RENT", "NOTICE", "Small apartment", "Row Flats", 40, 80, 0, 0, 3, false, false, 3, 5, "bed, storage, crafting, furnace, terminal", "", "Imogen", 0,
        List.of("just now  OVERDUE 3 hours", "2 h ago  PAID 40  1 hour"));
    private static final CrewView.Tenancy HOUSE = tenancy("OWN", "CURRENT", "House with workshop", "Row Flats", 20, 0, 12, 2, 0, true, true, 6, 10, "bed, storage, crafting, furnace, terminal, workshop", "secure_storage",
        "Imogen, Tobias, Wren", 4200, List.of("20 min ago  UPGRADE 260  Locking storage racks", "1 day ago  BOUGHT 7000  Row House"));
    private static final CrewView.Tenancy MOTEL = tenancy("RENT", "CURRENT", "Motel room", "Row Motel", 10, 0, 44, 1, 0, false, false, 1, 2, "bed, storage, terminal", "", "Imogen", 10,
        List.of("16 min ago  WELCOME 2 free hours  Row Motel Room 1"));

    private static final List<CrewView.HomeRow> HOMES = List.of(
        new CrewView.HomeRow("row_flats:flat_1", "Row Flat 1", "flat", "You", "398 70 -88", 1, true, FLAT),
        new CrewView.HomeRow("row_flats:row_house", "Row House", "house", "Crew", "412 70 -88", 2, false, HOUSE));
    private static final List<CrewView.LeaseRow> NEARBY = List.of(
        new CrewView.LeaseRow("row_motel:motel_2", "Row Motel Room 2", "motel", 0, false, 9, new CrewView.Offer(true, false, 10, 0, 0, true, "Motel room", "A bed, a barrel and the home terminal. The cheapest roof in the city.", "bed, storage, terminal", 2, 1, 100)),
        new CrewView.LeaseRow("row_flats:flat_2", "Row Flat 2", "flat", 2300, false, 14, new CrewView.Offer(true, true, 46, 92, 9, false, "Small apartment", "A real home: crafting, a furnace and room for five containers.", "bed, storage, crafting, furnace, terminal", 5, 2, 115)),
        new CrewView.LeaseRow("row_flats:row_house_2", "Row House 2", "house", 8050, false, 21, new CrewView.Offer(true, true, 115, 230, 23, false, "House with workshop", "Ten containers, a workshop for heavy workstations and room for a Crew.", "bed, storage, crafting, furnace, terminal, workshop", 10, 3, 115)));
    private static final List<CrewView.UpgradeRow> UPGRADES = List.of(
        new CrewView.UpgradeRow("secure_storage", "Locking storage racks", 260, "Three more containers fit and each resident may keep one more private."),
        new CrewView.UpgradeRow("workshop_bench", "Workshop fit-out", 600, "Heavy workstations may be placed and used: anvil, smithing table, brewing stand, gun tables."),
        new CrewView.UpgradeRow("plumbing", "Running water", 320, "Water may be placed inside the home: a sink, a cistern, irrigated beds."));

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
            case "money-shared" -> {   // fully shared finances in force, for a member who may spend Crew money
                screen = new CrewScreen(crew("Member", MEMBER_BITS | CrewView.bits(EnumSet.of(Perm.SPEND)), "SHARED", "SHARED", 320, 140, 0, true, false, List.of(), List.of(), HOMES, List.of()), "");
                screen.showPage(CrewScreen.Page.MONEY);
            }
            case "money-shared-nospend" -> {   // the same Crew, for a member who may not
                screen = new CrewScreen(crew("Member", MEMBER_BITS, "SHARED", "SHARED", 320, 140, 0, true, false, List.of(), List.of(), HOMES, List.of()), "");
                screen.showPage(CrewScreen.Page.MONEY);
            }
            case "home" -> {   // a rented flat in good standing: pay ahead, automatic payment, move out
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.HOME);
                screen.selectHome("row_flats:flat_1");
            }
            case "home-notice" -> {   // the same flat after three unpaid hours: final notice, what is owed, nothing lost
                List<CrewView.HomeRow> homes = List.of(new CrewView.HomeRow("row_flats:flat_1", "Row Flat 1", "flat", "You", "398 70 -88", 1, true, FLAT_OVERDUE), HOMES.get(1));
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 320, 140, 0, false, false, List.of(), List.of(), homes, NEARBY), "!Row Flat 1: 3 hours unpaid, 120 credits. Nothing is lost.");
                screen.showPage(CrewScreen.Page.HOME);
                screen.selectHome("row_flats:flat_1");
            }
            case "home-owned" -> {   // a house the Crew owns: residents, storage, and the three upgrades with their prices
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 900, 1200, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.HOME);
                screen.selectHome("row_flats:row_house");
            }
            case "home-find" -> {   // homes for rent or sale nearby, with the city's price factor and the motel's welcome
                screen = new CrewScreen(crew("Founder", ALL_BITS, "BANK", "BANK", 900, 1200, 0, false, false, List.of(), List.of(), HOMES, NEARBY), "");
                screen.showPage(CrewScreen.Page.HOME);
                screen.showHomeTab(1);
            }
            case "home-find-many" -> {   // a new player with no Crew and seven homes in reach: the welcome rooms first (D-136), the page arrows in the tab row, never under a card's buttons
                List<CrewView.LeaseRow> many = new java.util.ArrayList<>(NEARBY);
                CrewView.Offer motel = NEARBY.get(0).offer();
                for (int i = 3; i <= 6; i++) many.add(new CrewView.LeaseRow("row_motel:motel_" + i, "Row Motel Room " + i, "motel", 0, false, 9 + i, motel));
                many.sort(CrewView.MOVE_IN_ORDER);
                screen = new CrewScreen(new CrewView(true, "", SELF, "Imogen", true, true, 4, 0, true, false, new UUID(0, 0), "", "", 0, "BANK", "BANK", false, 0, 0, 0, 0, false, false,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), many, UPGRADES, CrewView.RulesRow.DEFAULT), "");
                screen.showPage(CrewScreen.Page.HOME);
                screen.showHomeTab(1);
            }
            case "home-solo" -> {   // a player with no Crew at all: the Home page works for everybody
                screen = new CrewScreen(new CrewView(true, "", SELF, "Imogen", true, true, 64, 0, true, false, new UUID(0, 0), "", "", 0, "BANK", "BANK", false, 0, 0, 0, 0, false, false,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(new CrewView.HomeRow("row_motel:motel_1", "Row Motel Room 1", "motel", "You", "380 70 -88", 1, true, MOTEL)),
                    NEARBY.subList(0, 2), UPGRADES, CrewView.RulesRow.DEFAULT), "");
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
            case "rules-mode-active" -> {
                screen = new CrewScreen(crew("Founder", ALL_BITS, "SHARED", "SHARED", 320, 140, 0, true, false, List.of(), List.of(), HOMES, NEARBY), "");
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
