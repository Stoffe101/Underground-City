package io.github.stoffe101.undergroundcity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, side-independent skill-tree rules (D-049). One skill point per career level above the
 * first. Every tree must be fully affordable by the maximum level, so no combination of choices
 * (including taking only underground nodes first) permanently locks a character out of the
 * legal branch or the other way round.
 */
public final class SkillRules {
    public static final int MAX_LEVEL = 30;
    private SkillRules() {}

    public static int pointsEarned(int careerLevel) {
        if (careerLevel < 1) throw new IllegalArgumentException("Career level starts at 1");
        return Math.min(careerLevel, MAX_LEVEL) - 1;
    }

    public static int pointsSpent(Collection<SkillNode> tree, Set<String> unlocked) {
        int spent = 0;
        for (SkillNode node : tree) if (unlocked.contains(node.id())) spent += node.cost();
        return spent;
    }

    /** Human-readable reason this node cannot be unlocked now, or null when it can. */
    public static String blocker(SkillNode node, Map<String, SkillNode> byId, int careerLevel, Set<String> unlocked, int pointsAvailable) {
        if (unlocked.contains(node.id())) return "Already unlocked";
        if (careerLevel < node.minLevel()) return "Career level " + node.minLevel() + " required";
        for (String required : node.requires()) {
            if (!unlocked.contains(required)) {
                SkillNode prerequisite = byId.get(required);
                return "Requires " + (prerequisite == null ? required : prerequisite.title());
            }
        }
        if (pointsAvailable < node.cost()) {
            return "Needs " + node.cost() + " skill point" + (node.cost() == 1 ? "" : "s") + " (" + pointsAvailable + " available)";
        }
        return null;
    }

    /** Depth of a node from the roots of its tree (roots are 0). Used for layout only. */
    public static int depth(SkillNode node, Map<String, SkillNode> byId) {
        int deepest = 0;
        for (String required : node.requires()) {
            SkillNode parent = byId.get(required);
            if (parent != null) deepest = Math.max(deepest, depth(parent, byId) + 1);
        }
        return deepest;
    }

    public static List<SkillNode> displayOrder(Collection<SkillNode> tree) {
        Map<String, SkillNode> byId = new HashMap<>();
        for (SkillNode node : tree) byId.put(node.id(), node);
        List<SkillNode> sorted = new ArrayList<>(tree);
        sorted.sort(Comparator.<SkillNode>comparingInt(node -> SkillNode.BRANCH_ORDER.indexOf(node.branch()))
            .thenComparingInt(node -> depth(node, byId)).thenComparing(SkillNode::id));
        return sorted;
    }

    /** Structural validation of one career tree. Throws IllegalArgumentException with the first problem. */
    public static void validateTree(String career, List<SkillNode> nodes) {
        if (nodes.isEmpty() || nodes.size() > SkillNode.MAX_NODES_PER_TREE) throw new IllegalArgumentException(career + ": 1-" + SkillNode.MAX_NODES_PER_TREE + " nodes expected");
        Map<String, SkillNode> byId = new HashMap<>();
        int total = 0;
        boolean legal = false, underground = false, root = false;
        for (SkillNode node : nodes) {
            if (byId.put(node.id(), node) != null) throw new IllegalArgumentException("Duplicate skill " + node.id());
            if (!node.id().matches(career + "\\.[a-z][a-z_]{2,39}")) throw new IllegalArgumentException("Skill id must be " + career + ".<name>: " + node.id());
            if (!node.career().equals(career)) throw new IllegalArgumentException(node.id() + ": wrong career");
            if (!SkillNode.BRANCHES.contains(node.branch())) throw new IllegalArgumentException(node.id() + ": unknown branch " + node.branch());
            if (node.title().isBlank() || node.title().length() > 40 || node.description().isBlank() || node.description().length() > 170) {
                throw new IllegalArgumentException(node.id() + ": title (<=40) and description (<=170) required");
            }
            if (!node.shortTitle().equals(node.title()) && node.shortTitle().length() > SkillNode.MAX_SHORT_TITLE) {
                throw new IllegalArgumentException(node.id() + ": short_title must be at most " + SkillNode.MAX_SHORT_TITLE + " characters");
            }
            if (node.cost() < 1 || node.cost() > 4 || node.minLevel() < 1 || node.minLevel() > MAX_LEVEL) throw new IllegalArgumentException(node.id() + ": invalid cost or level");
            if (node.inspectionModifier() < -SkillNode.MAX_INSPECTION_REDUCTION || node.inspectionModifier() > 0) throw new IllegalArgumentException(node.id() + ": inspection modifier out of range");
            if (node.exchangeDiscount() < 0 || node.exchangeDiscount() > SkillNode.MAX_TOTAL_DISCOUNT) throw new IllegalArgumentException(node.id() + ": discount out of range");
            for (String licence : node.licences()) if (!licence.matches("[a-z_]{3,40}")) throw new IllegalArgumentException(node.id() + ": bad licence " + licence);
            if (node.legalPayoutPercent() < 0 || node.legalPayoutPercent() > 6 || node.undergroundPayoutPercent() < 0 || node.undergroundPayoutPercent() > 6
                || node.yieldPercent() < 0 || node.yieldPercent() > 30 || node.upgradeDiscountPercent() < 0 || node.upgradeDiscountPercent() > 15) {
                throw new IllegalArgumentException(node.id() + ": percentage effect out of range");
            }
            total += node.cost();
            legal |= node.branch().equals("legal");
            underground |= node.branch().equals("underground");
            root |= node.requires().isEmpty();
        }
        if (!root) throw new IllegalArgumentException(career + ": no root skill");
        if (!legal || !underground) throw new IllegalArgumentException(career + ": every career needs both a legal and an underground branch (D-049)");
        if (total > pointsEarned(MAX_LEVEL)) throw new IllegalArgumentException(career + ": the whole tree must be affordable at the maximum level, costs total " + total);
        // whole-tree ceilings: a build can never stack past what the economy was balanced for (D-098)
        if (sum(nodes, SkillNode::legalPayoutPercent) > SkillNode.MAX_LEGAL_PAYOUT || sum(nodes, SkillNode::undergroundPayoutPercent) > SkillNode.MAX_UNDERGROUND_PAYOUT
            || sum(nodes, SkillNode::yieldPercent) > SkillNode.MAX_YIELD || sum(nodes, SkillNode::upgradeDiscountPercent) > SkillNode.MAX_UPGRADE_DISCOUNT) {
            throw new IllegalArgumentException(career + ": the percentage effects of the whole tree exceed their ceilings");
        }
        for (SkillNode node : nodes) {
            for (String required : node.requires()) {
                SkillNode parent = byId.get(required);
                if (parent == null) throw new IllegalArgumentException(node.id() + ": unknown prerequisite " + required);
                if (parent.minLevel() > node.minLevel()) throw new IllegalArgumentException(node.id() + ": level requirement lower than its prerequisite");
            }
            Set<String> seen = new HashSet<>();
            if (reaches(node, node.id(), byId, seen)) throw new IllegalArgumentException(node.id() + ": prerequisite cycle");
            // The legal line, the foundation and the utility skills never need a crime to be reached: a character who only ever follows the law is not locked
            // out of any of them. A crossover joins the two lines, so it must need at least one skill of each.
            boolean undergroundAncestor = hasAncestorIn(node, "underground", byId);
            if (undergroundAncestor && (node.branch().equals("legal") || node.branch().equals("core"))) {
                throw new IllegalArgumentException(node.id() + ": a " + node.branch() + " skill cannot depend on an underground one (use branch crossover)");
            }
            if (node.branch().equals("crossover")) {
                boolean fromLegal = false, fromUnderground = false;
                for (String required : node.requires()) {
                    SkillNode parent = byId.get(required);
                    fromUnderground |= parent.branch().equals("underground") || hasAncestorIn(parent, "underground", byId);
                    fromLegal |= parent.branch().equals("legal");
                }
                if (node.requires().size() < 2 || !fromLegal || !fromUnderground) {
                    throw new IllegalArgumentException(node.id() + ": a crossover skill needs at least two prerequisites, one legal and one underground");
                }
            }
        }
    }

    private static int sum(Collection<SkillNode> nodes, java.util.function.ToIntFunction<SkillNode> field) {
        int total = 0;
        for (SkillNode node : nodes) total += field.applyAsInt(node);
        return total;
    }

    /** True if the node or anything it needs (transitively) is in the branch. */
    static boolean hasAncestorIn(SkillNode node, String branch, Map<String, SkillNode> byId) {
        for (String required : node.requires()) {
            SkillNode parent = byId.get(required);
            if (parent != null && (parent.branch().equals(branch) || hasAncestorIn(parent, branch, byId))) return true;
        }
        return false;
    }

    private static boolean reaches(SkillNode from, String target, Map<String, SkillNode> byId, Set<String> seen) {
        for (String required : from.requires()) {
            if (required.equals(target)) return true;
            SkillNode parent = byId.get(required);
            if (parent != null && seen.add(required) && reaches(parent, target, byId, seen)) return true;
        }
        return false;
    }

    /** Sum of the inspection modifiers of unlocked nodes, clamped to the design limit. */
    public static double inspectionModifier(Collection<SkillNode> tree, Set<String> unlocked) {
        double sum = 0;
        for (SkillNode node : tree) if (unlocked.contains(node.id())) sum += node.inspectionModifier();
        return Math.max(-0.35, Math.min(0, sum));
    }

    public static double exchangeDiscount(Collection<SkillNode> tree, Set<String> unlocked) {
        double sum = 0;
        for (SkillNode node : tree) if (unlocked.contains(node.id())) sum += node.exchangeDiscount();
        return Math.min(SkillNode.MAX_TOTAL_DISCOUNT, sum);
    }

    /** Sum of an unlocked node percentage, clamped to the ceiling for the whole tree. */
    public static int percent(Collection<SkillNode> tree, Set<String> unlocked, java.util.function.ToIntFunction<SkillNode> field, int ceiling) {
        int total = 0;
        for (SkillNode node : tree) if (unlocked.contains(node.id())) total += field.applyAsInt(node);
        return Math.min(ceiling, total);
    }

    public static int legalPayoutPercent(Collection<SkillNode> tree, Set<String> unlocked) { return percent(tree, unlocked, SkillNode::legalPayoutPercent, SkillNode.MAX_LEGAL_PAYOUT); }
    public static int undergroundPayoutPercent(Collection<SkillNode> tree, Set<String> unlocked) { return percent(tree, unlocked, SkillNode::undergroundPayoutPercent, SkillNode.MAX_UNDERGROUND_PAYOUT); }
    public static int yieldPercent(Collection<SkillNode> tree, Set<String> unlocked) { return percent(tree, unlocked, SkillNode::yieldPercent, SkillNode.MAX_YIELD); }
    public static int upgradeDiscountPercent(Collection<SkillNode> tree, Set<String> unlocked) { return percent(tree, unlocked, SkillNode::upgradeDiscountPercent, SkillNode.MAX_UPGRADE_DISCOUNT); }

    /** What a contract of the given legality pays after the career's contract-rate skills: whole credits, never rounded up. */
    public static int payout(int credits, boolean underground, Collection<SkillNode> tree, Set<String> unlocked) {
        int percent = underground ? undergroundPayoutPercent(tree, unlocked) : legalPayoutPercent(tree, unlocked);
        return credits + (int) ((long) credits * percent / 100);
    }

    public static boolean hasLicence(Collection<SkillNode> tree, Set<String> unlocked, String category) {
        for (SkillNode node : tree) if (unlocked.contains(node.id()) && node.licences().contains(category)) return true;
        return false;
    }
}
