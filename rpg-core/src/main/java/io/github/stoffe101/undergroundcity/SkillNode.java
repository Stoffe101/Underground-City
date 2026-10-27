package io.github.stoffe101.undergroundcity;

import java.util.List;
import java.util.Set;

/**
 * One capability in a career tree. Nodes are verbs, not stat padding: they unlock contracts
 * (through {@code requires_skill} in a work order), act as licences in the city laws, cut
 * checkpoint search odds, discount regional exchange goods, raise what a delivered contract pays, raise what the
 * cannabis plant yields, or cheapen a home's upgrades. Every effect is consumed by a real system and is capped
 * for a whole tree ({@link #MAX_LEGAL_PAYOUT} and the like), so no build trivialises the economy. A node may
 * need several others ({@code requires} is all-of); a {@code crossover} node joins a legal and an underground
 * line. No node excludes another, so a character can follow the legal branch, the underground branch, or both.
 * Pure data: no Minecraft types, so {@link SkillRules} can be tested without loading Forge.
 */
public record SkillNode(String id, String career, String branch, String title, String description,
                        int cost, int minLevel, List<String> requires, double inspectionModifier,
                        List<String> licences, double exchangeDiscount, String shortTitle,
                        int legalPayoutPercent, int undergroundPayoutPercent, int yieldPercent, int upgradeDiscountPercent, String effectText) {
    public static final Set<String> BRANCHES = Set.of("core", "legal", "underground", "utility", "crossover");
    /** The Thief tree's columns (D-143): getting in, staying unseen, and turning what was taken into credits. */
    public static final Set<String> THIEF_BRANCHES = Set.of("core", "entry", "stealth", "network");
    /** The tree of the specialization every career can follow, with its own XP, level and points. */
    public static final String THIEF = "thief";
    /** Longest effect line of a specialization skill (its detail panel shows one line). */
    public static final int MAX_EFFECT_TEXT = 40;   // one line of the detail panel beside its button at the narrowest GUI (1280x720, scale 2)
    /** Display order of the branches in the terminal: legal on the left, underground on the right, everything else between. */
    public static final List<String> BRANCH_ORDER = List.of("legal", "core", "utility", "crossover", "underground");
    public static final int MAX_NODES_PER_TREE = 16;
    public static final double MAX_INSPECTION_REDUCTION = 0.25, MAX_TOTAL_DISCOUNT = 0.10;
    /** Whole-tree ceilings of the percentage effects. A contract bonus is a rate, never a multiplier of the order's size (D-098). */
    public static final int MAX_LEGAL_PAYOUT = 12, MAX_UNDERGROUND_PAYOUT = 12, MAX_YIELD = 40, MAX_UPGRADE_DISCOUNT = 20;

    /** Longest label that is safe on a terminal button; longer full titles must supply a {@code short_title}. */
    public static final int MAX_SHORT_TITLE = 20;

    public SkillNode {
        requires = List.copyOf(requires);
        licences = List.copyOf(licences);
        if (shortTitle == null || shortTitle.isBlank()) shortTitle = title; // the button falls back to an ellipsis if even this is too wide
        if (effectText == null) effectText = "";
    }

    /** A node without a written effect line (every career skill: their effects are worded from the numbers). */
    public SkillNode(String id, String career, String branch, String title, String description, int cost, int minLevel, List<String> requires,
                     double inspectionModifier, List<String> licences, double exchangeDiscount, String shortTitle,
                     int legalPayoutPercent, int undergroundPayoutPercent, int yieldPercent, int upgradeDiscountPercent) {
        this(id, career, branch, title, description, cost, minLevel, requires, inspectionModifier, licences, exchangeDiscount, shortTitle,
            legalPayoutPercent, undergroundPayoutPercent, yieldPercent, upgradeDiscountPercent, "");
    }

    /** True for a skill of the Thief tree, which every career can follow (D-143). */
    public boolean thief() { return THIEF.equals(career); }

    /** The node as it was before the percentage effects existed; keeps the older call sites and the tests short. */
    public SkillNode(String id, String career, String branch, String title, String description, int cost, int minLevel, List<String> requires,
                     double inspectionModifier, List<String> licences, double exchangeDiscount, String shortTitle) {
        this(id, career, branch, title, description, cost, minLevel, requires, inspectionModifier, licences, exchangeDiscount, shortTitle, 0, 0, 0, 0);
    }

    /** The branch is drawn in the middle column unless it is one of the two lines (getting in and fencing, on the Thief tree). */
    public int column() { return column(branch); }
    public static int column(String branch) { return branch.equals("legal") || branch.equals("entry") ? 0 : branch.equals("underground") || branch.equals("network") ? 2 : 1; }

    /** What the node does, in the words the terminal shows under its description. Empty for a pure foundation or a pure contract gate. */
    public String effectSummary() {
        List<String> parts = new java.util.ArrayList<>();
        if (legalPayoutPercent > 0) parts.add("+" + legalPayoutPercent + "% on legal contracts");
        if (undergroundPayoutPercent > 0) parts.add("+" + undergroundPayoutPercent + "% on underground contracts");
        if (yieldPercent > 0) parts.add("+" + yieldPercent + "% Cannabis yield");
        if (upgradeDiscountPercent > 0) parts.add("-" + upgradeDiscountPercent + "% home upgrades");
        if (exchangeDiscount > 0) parts.add(Math.round(exchangeDiscount * 100) + "% off exchange goods");
        if (inspectionModifier < 0) parts.add(Math.round(inspectionModifier * 100) + "% search chance");
        if (!licences.isEmpty()) parts.add("licence: " + String.join(", ", licences).replace('_', ' '));
        if (!effectText.isEmpty()) parts.add(effectText);
        return String.join("; ", parts);
    }
}
