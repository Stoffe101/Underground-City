package io.github.stoffe101.undergroundcity;

import java.util.List;
import java.util.Set;

/**
 * One capability in a career tree. Nodes are verbs, not stat padding: they unlock contracts
 * (through {@code requires_skill} in a work order), act as licences in the city laws, cut
 * checkpoint search odds, or discount regional exchange goods. No node excludes another, so
 * a character can follow the legal branch, the underground branch, or both.
 * Pure data: no Minecraft types, so {@link SkillRules} can be tested without loading Forge.
 */
public record SkillNode(String id, String career, String branch, String title, String description,
                        int cost, int minLevel, List<String> requires, double inspectionModifier,
                        List<String> licences, double exchangeDiscount) {
    public static final Set<String> BRANCHES = Set.of("core", "legal", "underground", "utility");
    /** Display order of the branches in the terminal: legal on the left, underground on the right. */
    public static final List<String> BRANCH_ORDER = List.of("legal", "core", "underground", "utility");
    public static final int MAX_NODES_PER_TREE = 16;
    public static final double MAX_INSPECTION_REDUCTION = 0.25, MAX_TOTAL_DISCOUNT = 0.10;

    public SkillNode {
        requires = List.copyOf(requires);
        licences = List.copyOf(licences);
    }
}
