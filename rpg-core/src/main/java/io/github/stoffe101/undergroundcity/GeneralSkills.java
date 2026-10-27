package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * The general skills a player earns by playing (D-174, D-200), beside the Thief track (D-143). All five rise from what a person actually does and
 * change something they can feel; how Fitness, Driving and Shooting are earned (and kept from being earned by standing still) is {@link GeneralActivity}.
 *
 * <ul>
 *   <li><b>Trading</b> rises with legal sales that settle: a delivered legal work order (credits / 8, at most 50 XP) and goods sold to a shop
 *   (credits / 8, at most 30 XP). It lowers regional exchange prices: 2% from level 3, 4% from level 6, 5% from level 10, always inside the
 *   10% cap the career skills share ({@link SkillNode#MAX_TOTAL_DISCOUNT}), so the economic model's bounds hold.</li>
 *   <li><b>Social</b> rises with work done for others: a civic contribution (40 XP), a Crew contract part (30 XP) and a legal buyer's trust
 *   growing (25 XP). From level 3 a legal buyer counts one step more trust than the record holds when deciding whether to offer a trusted
 *   order: the city's good name for a person who helps. Never for an underground buyer, never past the buyer's maximum, and it never changes
 *   the recorded trust.</li>
 *   <li><b>Fitness</b> rises with ground covered on foot; from level 3 the player walks and runs 2% quicker, 4% from level 6, 6% from level 10.</li>
 *   <li><b>Driving</b> rises with ground covered at the controls of a vehicle; the vehicle dealer's prices are 3% lower from level 3, 6% from level 6 and 8% from
 *   level 10, inside the same 10% cap.</li>
 *   <li><b>Shooting</b> rises with hits on a target block from ten blocks or more; from level 3 an arrow or a TaCZ bullet leaves a fifth of the way closer to where the
 *   shooter looks (more than a third from level 6, half from level 10): a steadier hand, never more damage.</li>
 * </ul>
 *
 * Awards come after the work-order journal's single settlement (or the shop sale's), so a delivery is never counted twice: a retried
 * delivery is refused by the journal before it gets here, and a crash between the settlement and the award loses the award rather than
 * doubling it.
 */
public final class GeneralSkills {
    static final String TRADING = "trading", SOCIAL = "social", FITNESS = "fitness", DRIVING = "driving", SHOOTING = "shooting";
    static final int TRADING_DIVISOR = 8, ORDER_TRADING_CAP = 50, SHOP_TRADING_CAP = 30;
    static final int CIVIC_SOCIAL_XP = 40, CREW_PART_SOCIAL_XP = 30, TRUST_SOCIAL_XP = 25;
    static final int SOCIAL_TRUST_LEVEL = 3;

    private GeneralSkills() {}

    /** The exchange discount a Trading level gives (0, 0.02, 0.04 or 0.05). */
    static double tradingDiscount(int level) { return level >= 10 ? 0.05 : level >= 6 ? 0.04 : level >= 3 ? 0.02 : 0; }

    /** The citizen's exchange discount: the career skills' and Trading's together, never above the shared cap. */
    static double exchangeDiscount(CitizenState.Citizen citizen) {
        return Math.min(SkillNode.MAX_TOTAL_DISCOUNT, SkillService.exchangeDiscount(citizen) + tradingDiscount(citizen.generalLevel(TRADING)));
    }

    /** The same for one line: a vehicle dealer's line (D-101) also takes Driving's discount, a city's line its good name's (D-204), all of it never above the shared cap. */
    static double exchangeDiscount(CitizenState.Citizen citizen, RegionalStock stock) {
        double driving = vehicle(stock) ? drivingDiscount(citizen.generalLevel(DRIVING)) : 0;
        double standing = stock == null ? 0 : CityStanding.discount(citizen, stock.city());   // a city's good name (D-204)
        return Math.min(SkillNode.MAX_TOTAL_DISCOUNT, SkillService.exchangeDiscount(citizen) + tradingDiscount(citizen.generalLevel(TRADING)) + driving + standing);
    }

    /** True for a vehicle dealer's line (the lines generated from the vehicle tiers, {@code regional_stock/vehicles/}). */
    static boolean vehicle(RegionalStock stock) { return stock != null && stock.id().contains(":vehicles/"); }

    /** Fitness: how much quicker on foot (0, 0.02, 0.04 or 0.06 of the base walking speed). */
    static double fitnessSpeed(int level) { return level >= 10 ? 0.06 : level >= 6 ? 0.04 : level >= 3 ? 0.02 : 0; }

    /** Driving: the vehicle dealer's discount (0, 0.03, 0.06 or 0.08). */
    static double drivingDiscount(int level) { return level >= 10 ? 0.08 : level >= 6 ? 0.06 : level >= 3 ? 0.03 : 0; }

    /** Shooting: how far a shot is turned toward where the shooter looks (0, 0.2, 0.35 or 0.5). */
    static double steadiness(int level) { return level >= 10 ? 0.5 : level >= 6 ? 0.35 : level >= 3 ? 0.2 : 0; }

    /** Shooting XP for a hit on a target scored 0 to 10: 2 for hitting it, up to 5 more for the centre. */
    static int shotXp(int score) { return 2 + Math.max(0, Math.min(10, score)) / 2; }

    /** The trust a buyer counts when offering an order: the recorded trust, one step more for a legal buyer and a person of Social level 3 or more. */
    static int effectiveTrust(int recorded, int socialLevel, boolean undergroundBuyer) {
        if (undergroundBuyer || socialLevel < SOCIAL_TRUST_LEVEL || recorded >= BuyerCatalog.MAX_TRUST) return recorded;
        return recorded + 1;
    }

    static int effectiveTrust(CitizenState.Citizen citizen, WorkOrderLedger ledger, java.util.UUID player, String buyerId) {
        BuyerCatalog.Buyer buyer = BuyerCatalog.INSTANCE.find(buyerId);
        int recorded = ledger.trust(player, buyerId);
        return citizen == null ? recorded : effectiveTrust(recorded, citizen.generalLevel(SOCIAL), buyer == null || buyer.underground());
    }

    /** A delivery settled: Trading for a legal sale, Social for a civic contribution, a Crew part and a legal buyer's trust. */
    static void delivered(ServerPlayer player, WorkOrder order, int credits, boolean trustGrew) {
        WorkOrder.Terms terms = order.terms();
        if (!order.underground()) award(player, TRADING, Math.min(ORDER_TRADING_CAP, credits / TRADING_DIVISOR), "A legal sale settled.");
        int social = 0;
        if (!terms.civic().isEmpty()) social += CIVIC_SOCIAL_XP;
        if (!terms.crewPart().isEmpty()) social += CREW_PART_SOCIAL_XP;
        if (trustGrew && !order.underground()) social += TRUST_SOCIAL_XP;
        award(player, SOCIAL, social, !terms.civic().isEmpty() ? "You did something for the city." : !terms.crewPart().isEmpty() ? "You did your part for the Crew." : "A buyer thinks well of you.");
    }

    /** Goods sold to a shop's shelf. */
    static void suppliedShop(ServerPlayer player, long credits) {
        award(player, TRADING, (int) Math.min(SHOP_TRADING_CAP, credits / TRADING_DIVISOR), "Goods sold to a shop.");
    }

    /** Adds general XP earned a little at a time (a walk, a drive, a shot) and says something only when a level is reached. Returns the XP given. */
    static int awardQuietly(ServerPlayer player, String skill, int amount) {
        if (amount <= 0 || player == null) return 0;
        CitizenState state = CitizenState.get(player.server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElse(null);
        if (citizen == null) return 0;
        int before = citizen.generalLevel(skill);
        state.addGeneralXp(player.getUUID(), skill, amount);
        int after = citizen.generalLevel(skill);
        if (after > before) Notice.send(player, Notice.Kind.SKILL, title(skill) + " level " + after, gives(skill, after));
        if (after > before && skill.equals(FITNESS)) GeneralActivity.applyFitness(player);
        return amount;
    }

    static String title(String skill) { return skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1); }

    /** Adds general XP and tells the player, with what a new level gives. Returns the XP given. */
    static int award(ServerPlayer player, String skill, int amount, String why) {
        if (amount <= 0 || player == null) return 0;
        CitizenState state = CitizenState.get(player.server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElse(null);
        if (citizen == null) return 0;
        int before = citizen.generalLevel(skill);
        state.addGeneralXp(player.getUUID(), skill, amount);
        int after = citizen.generalLevel(skill);
        String name = skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1);
        if (after > before) Notice.send(player, Notice.Kind.SKILL, name + " level " + after, gives(skill, after));
        else Notice.send(player, Notice.Kind.SKILL, "+" + amount + " " + name + " XP", why);
        return amount;
    }

    /** The general skills the terminal's General tab lists, in its order; the Thief track has its own tab and a summary row. */
    static final java.util.List<String> SHOWN = java.util.List.of(TRADING, SOCIAL, DRIVING, FITNESS, SHOOTING);

    /** One general skill for the terminal: its level and XP, what it gives now, the next change and how it is earned; {@code used} is false while nothing consumes it. */
    static TerminalData.General row(CitizenState.Citizen citizen, String skill) {
        int xp = citizen.generalXp.getOrDefault(skill, 0), level = citizen.generalLevel(skill);
        int from = ProgressionRules.xpAtLevel(level), to = level >= 30 ? from : ProgressionRules.xpAtLevel(level + 1);
        boolean used = SHOWN.contains(skill);
        String title = title(skill);
        boolean active = switch (skill) {
            case TRADING -> tradingDiscount(level) > 0;
            case SOCIAL -> level >= SOCIAL_TRUST_LEVEL;
            case FITNESS -> fitnessSpeed(level) > 0;
            case DRIVING -> drivingDiscount(level) > 0;
            case SHOOTING -> steadiness(level) > 0;
            default -> false;
        };
        return new TerminalData.General(skill, title, level, xp, from, to, active ? gives(skill, level) : "", used ? next(skill, level) : "", earned(skill), used);
    }

    /** The next level at which a skill changes something, and what it then gives ("" at the top). */
    static String next(String skill, int level) {
        int[] steps = skill.equals(SOCIAL) ? new int[] {SOCIAL_TRUST_LEVEL} : SHOWN.contains(skill) ? new int[] {3, 6, 10} : new int[0];
        for (int step : steps) if (level < step) return "Level " + step + ": " + gives(skill, step);
        return "";
    }

    /** How a general skill's XP is earned, in one line. */
    static String earned(String skill) {
        return switch (skill) {
            case TRADING -> "Legal deliveries (an eighth of the pay, up to " + ORDER_TRADING_CAP + " XP) and goods sold to a shop (up to " + SHOP_TRADING_CAP + ").";
            case SOCIAL -> "Civic contributions (" + CIVIC_SOCIAL_XP + " XP), Crew contract parts (" + CREW_PART_SOCIAL_XP + ") and a legal buyer's trust growing (" + TRUST_SOCIAL_XP + ").";
            case FITNESS -> "Ground covered on foot, a straight line at a time (walking in circles counts for little); up to " + GeneralActivity.FITNESS_CAP + " XP an hour.";
            case DRIVING -> "Ground covered at the controls of a vehicle or a boat (not a horse or a minecart); up to " + GeneralActivity.DRIVING_CAP + " XP an hour.";
            case SHOOTING -> "Hits on a target block from ten blocks or more (2 XP, up to 7 for the centre), with a gun or a bow; up to " + GeneralActivity.SHOOTING_CAP + " XP an hour.";
            default -> "Nothing earns or uses this skill yet.";
        };
    }

    /** What a general skill gives at a level, in one line for a notice or {@code /uc skills}. */
    static String gives(String skill, int level) {
        if (skill.equals(TRADING)) {
            double d = tradingDiscount(level);
            return d > 0 ? "Exchange prices " + Math.round(d * 100) + "% lower for you (with career skills, at most 10%)." : "At level 3 the exchanges sell to you 2% cheaper.";
        }
        if (skill.equals(SOCIAL)) {
            return level >= SOCIAL_TRUST_LEVEL ? "Legal buyers offer you their trusted work one step of trust sooner." : "At level 3 legal buyers trust you one step sooner.";
        }
        if (skill.equals(FITNESS)) {
            double f = fitnessSpeed(level);
            return f > 0 ? Math.round(f * 100) + "% quicker on foot." : "At level 3 you walk and run 2% quicker.";
        }
        if (skill.equals(DRIVING)) {
            double d = drivingDiscount(level);
            return d > 0 ? "Vehicle dealers sell to you " + Math.round(d * 100) + "% cheaper (with career skills and Trading, at most 10%)." : "At level 3 the vehicle dealers sell to you 3% cheaper.";
        }
        if (skill.equals(SHOOTING)) {
            double k = steadiness(level);
            return k > 0 ? "A steadier hand: your shots leave " + Math.round(k * 100) + "% closer to where you aim." : "At level 3 your shots leave closer to where you aim.";
        }
        return "Nothing in the game uses this skill yet.";
    }
}
