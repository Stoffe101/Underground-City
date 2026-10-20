package io.github.stoffe101.undergroundcity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The fixed vocabulary of Crews: roles, permissions, finance modes, limits and name rules. Pure data and arithmetic (no Minecraft
 * types), so the same rules are used by the ledger, the service, the guard and the screen, and can be exercised without a world.
 */
public final class CrewRules {
    private CrewRules() {}

    /** Lower ordinal outranks higher. The founder is the only one who can rename, change rules and money mode, promote and demote. */
    public enum Role {
        FOUNDER("Founder"), MANAGER("Manager"), MEMBER("Member");
        public final String title;
        Role(String title) { this.title = title; }
        public boolean outranks(Role other) { return ordinal() < other.ordinal(); }
    }

    /** What a role may do. The founder always holds all of them; the matrix for managers and members is the founder's to edit. */
    public enum Perm {
        INVITE("Invite players", "Send invitations to registered citizens who have no Crew."),
        MANAGE_MEMBERS("Remove members", "Remove a member who ranks below you."),
        ACCESS_HOME("Enter the Crew home", "Open the doors of Crew homes and move around inside them."),
        BUILD("Build and furnish", "Place and break blocks inside Crew homes."),
        STORAGE("Use shared storage", "Open chests, barrels and other containers inside Crew homes (except private ones)."),
        WORKSTATIONS("Use workstations", "Use crafting tables, furnaces, anvils, gun tables and other workstations inside Crew homes."),
        WITHDRAW("Withdraw from the bank", "Take credits out of the Crew bank into your own wallet."),
        SPEND("Spend Crew money", "Pay for Crew purchases from the bank."),
        MANAGE_PROPERTY("Rent and buy homes", "Rent or buy a home for the Crew, pay its rent, change how it is paid, fit upgrades and move out.");

        public final String title, hint;
        Perm(String title, String hint) { this.title = title; this.hint = hint; }
    }

    /**
     * How money works for this Crew. Changing it never moves a credit.
     * SEPARATE: everyone keeps their own wallet; there is no standing bank balance and Crew purchases are paid by whoever makes them.
     * BANK: personal wallets plus a separate Crew bank (the recommended default).
     * SHARED: like BANK, plus the Crew-wide agreement that eligible income is paid into the bank and Core purchases are paid from it.
     *         It only takes effect while every member has consented (see {@link CrewLedger.Crew#effectiveMode()}).
     */
    public enum Mode {
        SEPARATE("Separate money"), BANK("Personal money and a Crew bank"), SHARED("Fully shared finances");
        public final String title;
        Mode(String title) { this.title = title; }
    }

    public static final int MAX_MEMBERS = 8;
    public static final int MAX_OPEN_INVITES = 6;
    public static final int NAME_MIN = 3, NAME_MAX = 24;
    /** A single transfer is capped well below the wallet limit (1,000,000,000) so a typo cannot empty an account. */
    public static final long MAX_TRANSFER = 1_000_000L;
    public static final long MAX_BANK = 1_000_000_000L;
    public static final int LOG_MAX = 40;
    /** Settled transaction ids remembered so a replayed packet cannot repeat a transfer. */
    public static final int SEEN_MAX = 256;
    public static final long INVITE_TTL_MILLIS = 7L * 24 * 3600 * 1000;
    /** A founder who has not been seen for this long can be succeeded by a manager (never automatically). */
    public static final long FOUNDER_INACTIVE_MILLIS = 30L * 24 * 3600 * 1000;
    /** How long somebody who left a Crew home may still walk in to fetch what they stored in their private containers. */
    public static final long RETRIEVAL_MILLIS = 3L * 24 * 3600 * 1000;
    public static final int MAX_CLAIMS_PER_PLAYER = 4;
    /** Receipts kept for each home (rent, payments, upgrades, moves). */
    public static final int RECEIPTS_MAX = 12;

    public static EnumSet<Perm> defaultPerms(Role role) {
        return switch (role) {
            case FOUNDER, MANAGER -> EnumSet.allOf(Perm.class);
            case MEMBER -> EnumSet.of(Perm.ACCESS_HOME, Perm.BUILD, Perm.STORAGE, Perm.WORKSTATIONS);
        };
    }

    private static final Set<Character> NAME_PUNCTUATION = Set.of(' ', '\'', '-', '_', '.');

    /** The Crew name as it will be stored (single spaces, trimmed), or an {@link IllegalStateException} with the reason. */
    public static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new IllegalStateException("A Crew name has " + NAME_MIN + " to " + NAME_MAX + " characters.");
        }
        boolean letter = false;
        for (char c : name.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || NAME_PUNCTUATION.contains(c);
            if (!ok) throw new IllegalStateException("A Crew name may use letters, digits, spaces and ' - _ . only.");
            if (Character.isLetter(c)) letter = true;
        }
        if (!letter) throw new IllegalStateException("A Crew name needs at least one letter.");
        return name;
    }

    /** The key that makes names unique regardless of case and spacing. */
    public static String nameKey(String clean) { return clean.toLowerCase(Locale.ROOT).replace(' ', '_'); }

    /** A positive whole number of credits within the per-transfer cap, or an {@link IllegalStateException}. */
    public static long cleanAmount(long amount) {
        if (amount < 1) throw new IllegalStateException("Enter an amount of at least 1 credit.");
        if (amount > MAX_TRANSFER) throw new IllegalStateException("A single transfer is limited to " + MAX_TRANSFER + " credits.");
        return amount;
    }

    /** "3 days ago" style text from a duration in milliseconds. */
    public static String ago(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 2) return "just now";
        if (minutes < 120) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h ago";
        return (hours / 24) + " days ago";
    }
}
