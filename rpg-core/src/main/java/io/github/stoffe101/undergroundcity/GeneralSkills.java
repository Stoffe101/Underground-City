package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * The general skills a player earns by playing (D-174), beside the Thief track (D-143). Two of the six stored skills now rise from what a person
 * actually does and change something they can see; the other three (driving, fitness, shooting) stay stored and unearned until a system can
 * consume them honestly.
 *
 * <ul>
 *   <li><b>Trading</b> rises with legal sales that settle: a delivered legal work order (credits / 8, at most 50 XP) and goods sold to a shop
 *   (credits / 8, at most 30 XP). It lowers regional exchange prices: 2% from level 3, 4% from level 6, 5% from level 10, always inside the
 *   10% cap the career skills share ({@link SkillNode#MAX_TOTAL_DISCOUNT}), so the economic model's bounds hold.</li>
 *   <li><b>Social</b> rises with work done for others: a civic contribution (40 XP), a Crew contract part (30 XP) and a legal buyer's trust
 *   growing (25 XP). From level 3 a legal buyer counts one step more trust than the record holds when deciding whether to offer a trusted
 *   order: the city's good name for a person who helps. Never for an underground buyer, never past the buyer's maximum, and it never changes
 *   the recorded trust.</li>
 * </ul>
 *
 * Awards come after the work-order journal's single settlement (or the shop sale's), so a delivery is never counted twice: a retried
 * delivery is refused by the journal before it gets here, and a crash between the settlement and the award loses the award rather than
 * doubling it.
 */
public final class GeneralSkills {
    static final String TRADING = "trading", SOCIAL = "social";
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

    /** What a general skill gives at a level, in one line for a notice or {@code /uc skills}. */
    static String gives(String skill, int level) {
        if (skill.equals(TRADING)) {
            double d = tradingDiscount(level);
            return d > 0 ? "Exchange prices " + Math.round(d * 100) + "% lower for you (with career skills, at most 10%)." : "At level 3 the exchanges sell to you 2% cheaper.";
        }
        if (skill.equals(SOCIAL)) {
            return level >= SOCIAL_TRUST_LEVEL ? "Legal buyers offer you their trusted work one step of trust sooner." : "At level 3 legal buyers trust you one step sooner.";
        }
        return "Nothing in the game uses this skill yet.";
    }
}
