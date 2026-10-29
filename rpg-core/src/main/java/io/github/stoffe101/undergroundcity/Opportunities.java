package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What is worth doing now (twelfth pass): the Guide page's Opportunities tab, built from the server's state when a terminal opens. The owner asked for an optional signpost that
 * shows a beginner achievable work and an experienced player the advanced work, and says why something is not open yet. Every line comes from something real: the orders on
 * this terminal (what is ready to deliver, what pays best now, what a skill would open, what a buyer in another city wants), a shop's shortage contract, the Technician's and the
 * Smith's open calls in this city, unspent skill points, whether the player has a home, and the city's good name. At most {@link TerminalData#MAX_OPPORTUNITIES}, most useful first.
 */
final class Opportunities {
    private Opportunities() {}

    static List<TerminalData.Opportunity> of(ServerPlayer player, CitizenState.Citizen citizen, WorkOrderLedger ledger, String region, List<TerminalData.Row> rows) {
        List<TerminalData.Opportunity> out = new ArrayList<>();
        try {
            build(player, citizen, ledger, region, rows, out);
        } catch (RuntimeException error) {
            // a signpost is never worth a terminal that will not open
            com.mojang.logging.LogUtils.getLogger().warn("Opportunities could not be listed: {}", error.toString());
        }
        return out.stream().limit(TerminalData.MAX_OPPORTUNITIES).toList();
    }

    private static boolean holdsAll(TerminalData.Row row) { return !row.reqs().isEmpty() && row.reqs().stream().allMatch(r -> r.held() >= r.count()); }

    private static String city(String id) { return id == null || id.isEmpty() ? "" : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1); }

    private static void build(ServerPlayer player, CitizenState.Citizen citizen, WorkOrderLedger ledger, String region, List<TerminalData.Row> rows, List<TerminalData.Opportunity> out) {
        String here = region.isEmpty() ? citizen.city : region;
        // 1. an accepted order whose goods the player carries: deliver it
        for (TerminalData.Row row : rows)
            if (row.accepted() && holdsAll(row)) out.add(new TerminalData.Opportunity("deliver", "Deliver " + row.title(), "You carry everything it asks for: " + row.credits() + " credits and " + row.xp() + " XP when you hand it in.", "CONTRACTS", row.extra().underground(), false));
        boolean busy = rows.stream().anyMatch(TerminalData.Row::accepted);
        // 2. a shop's shortage contract for this trade (D-207): time-bound, paid above the shop's price
        rows.stream().filter(r -> ShortageContracts.isContract(r.id()) && r.available() > 0 && !r.accepted()).findFirst()
            .ifPresent(r -> out.add(new TerminalData.Opportunity("shortage", r.title(), r.credits() + " credits, above what the shop usually pays. One taker, until the shelf is filled.", "CONTRACTS", false, false)));
        // 3. open work the player can take now, legal first, the best paying
        List<TerminalData.Row> open = rows.stream().filter(r -> r.available() > 0 && !r.accepted() && r.extra().skillOk() && r.level() <= citizen.careerLevel()
            && !ShortageContracts.isContract(r.id()) && (r.extra().career().isEmpty() || r.extra().career().equals(citizen.career) || r.extra().career().equals(WorkOrder.ANY_CAREER))
            && r.extra().trust() >= r.extra().minTrust()).toList();
        open.stream().filter(r -> !r.extra().underground() && holdsAll(r)).findFirst()
            .ifPresent(r -> out.add(new TerminalData.Opportunity("ready", "Ready now: " + r.title(), "You already carry what it needs: " + r.credits() + " credits, " + r.xp() + " XP.", "CONTRACTS", false, false)));
        open.stream().filter(r -> !r.extra().underground()).sorted(Comparator.comparingInt(TerminalData.Row::credits).reversed()).limit(busy ? 1 : 2)
            .filter(r -> out.stream().noneMatch(o -> o.title().endsWith(r.title())))
            .forEach(r -> out.add(new TerminalData.Opportunity("work", r.title(), r.credits() + " credits, " + r.xp() + " XP" + (r.extra().buyerCity().isEmpty() ? " at any terminal." : " from " + r.extra().buyer() + " in " + city(r.extra().buyerCity()) + ".")
                + (r.reqs().isEmpty() ? "" : " Needs " + r.reqs().get(0).count() + " x " + label(r.reqs().get(0)) + (r.reqs().size() > 1 ? " and more" : "") + "."), "CONTRACTS", false, false)));
        // 4. the Technician's and the Smith's calls in this city (D-158, D-182)
        if ("technician".equals(citizen.career)) {
            TechService.Systems s = TechService.systems(player.server);
            long faults = s.faults.keySet().stream().filter(k -> s.panels.containsKey(k) && here.equals(s.panels.get(k).city())).count();
            long jobs = s.jobs.values().stream().filter(j -> s.panels.containsKey(j.key()) && here.equals(s.panels.get(j.key()).city()) && !"quiet".equals(j.kind())).count();
            if (faults + jobs > 0) out.add(new TerminalData.Opportunity("service", (faults + jobs) + " service call" + (faults + jobs == 1 ? "" : "s") + " in " + city(here),
                "Panels by street doors: " + faults + " repair" + (faults == 1 ? "" : "s") + " (" + TechService.repairFee(3) + " credits at a tier III building), " + jobs + " install or upgrade. Your computer lists them.", "", false, false));
        }
        if ("smith".equals(citizen.career)) {
            MachineService.Works w = MachineService.works(player.server);
            long down = w.breakdowns.keySet().stream().filter(k -> w.machines.containsKey(k) && here.equals(w.machines.get(k).city())).count();
            if (down > 0) out.add(new TerminalData.Opportunity("service", down + " machine" + (down == 1 ? "" : "s") + " down in " + city(here),
                MachineService.fee(3) + " credits a drive at a tier III works, fixed with parts of your own making.", "", false, false));
        }
        // 5. skill points to spend
        int points = SkillService.pointsAvailable(citizen);
        if (points > 0) out.add(new TerminalData.Opportunity("skill", points + " skill point" + (points == 1 ? "" : "s") + " to spend", "A point opens better orders, pays more for each one or lets you make better goods.", "SKILLS", false, false));
        // 6. a home
        if (!HudState.of(player).hasHome()) out.add(new TerminalData.Opportunity("home", "No home yet", "Press K for the homes to rent: a motel room is cheapest, and a home keeps your things and gives you a bed.", "", false, false));
        // 7. underground work this character could take (shown only once its trade's underground skill is held)
        open.stream().filter(r -> r.extra().underground()).max(Comparator.comparingInt(TerminalData.Row::credits))
            .ifPresent(r -> out.add(new TerminalData.Opportunity("underground", r.title(), "Off the books: a search takes the goods and fines you. " + r.credits() + " credits from " + r.extra().buyer() + (r.extra().buyerCity().isEmpty() ? "" : " in " + city(r.extra().buyerCity()))
                + "; checkpoints on the way search, and a hand-off can be seen.", "CONTRACTS", true, false)));
        // 8. the next order a skill would open, if it pays more than what is open now
        int bestOpen = open.stream().mapToInt(TerminalData.Row::credits).max().orElse(0);
        rows.stream().filter(r -> !r.extra().skillOk() && !r.extra().underground() && r.credits() > bestOpen && !r.extra().skill().isEmpty())
            .min(Comparator.comparingInt(TerminalData.Row::level))
            .ifPresent(r -> out.add(new TerminalData.Opportunity("locked", "Next: " + r.title(), r.credits() + " credits once you have " + r.extra().skill() + (r.level() > citizen.careerLevel() ? " and level " + r.level() : "") + ".", "SKILLS", false, true)));
        // 9. a buyer in another city
        if (!region.isEmpty()) open.stream().filter(r -> !r.extra().underground() && !r.extra().buyerCity().isEmpty() && !r.extra().buyerCity().equals(region))
            .max(Comparator.comparingInt(TerminalData.Row::credits))
            .filter(r -> out.stream().noneMatch(o -> o.title().equals(r.title())))
            .ifPresent(r -> out.add(new TerminalData.Opportunity("travel", "Travel to " + city(r.extra().buyerCity()), r.extra().buyer() + " there pays " + r.credits() + " credits for " + r.title() + ".", "CONTRACTS", false, false)));
        // 10. the city's good name (D-204)
        if (!region.isEmpty()) {
            int standing = CityStanding.of(citizen, region);
            if (standing < 50) out.add(new TerminalData.Opportunity("standing", city(region) + " thinks of you: " + standing + " of 100",
                "Deliveries here raise it; at 50 the city's sellers take 2% off. An arrest here lowers it.", "", false, false));
        }
    }

    private static String label(TerminalData.Req req) {
        if (!req.label().isEmpty()) return req.label();
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(req.item()));
        return item == null ? req.item() : item.getDescription().getString();
    }
}
