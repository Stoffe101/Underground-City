package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * Server-authoritative skill spending. A skill unlock touches no wallet or inventory, so it needs no
 * journal; the citizen record is saved immediately so a crash cannot hand out a contract reward whose
 * skill vanished. The client chooses only a node id; prerequisites, level and points are re-checked here.
 */
public final class SkillService {
    private SkillService() {}

    public static int pointsEarned(CitizenState.Citizen citizen) { return SkillRules.pointsEarned(citizen.careerLevel()); }

    public static int pointsSpent(CitizenState.Citizen citizen) {
        return SkillRules.pointsSpent(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    public static int pointsAvailable(CitizenState.Citizen citizen) { return pointsEarned(citizen) - pointsSpent(citizen); }

    /** Null when the node may be unlocked now, otherwise why not. */
    public static String blocker(CitizenState.Citizen citizen, SkillNode node) {
        if (!node.career().equals(citizen.career)) return "Belongs to another profession";
        return SkillRules.blocker(node, SkillCatalog.INSTANCE.index(), citizen.careerLevel(), citizen.skills(), pointsAvailable(citizen));
    }

    public static boolean has(CitizenState.Citizen citizen, String skillId) { return citizen.skills().contains(skillId); }

    public static boolean has(ServerPlayer player, String skillId) {
        return CitizenState.get(player.server).find(player.getUUID()).map(citizen -> has(citizen, skillId)).orElse(false);
    }

    /** Sum of unlocked inspection modifiers (negative or zero). */
    public static double inspectionModifier(CitizenState.Citizen citizen) {
        return SkillRules.inspectionModifier(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    public static double exchangeDiscount(CitizenState.Citizen citizen) {
        return SkillRules.exchangeDiscount(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    /** What delivering this order pays this citizen: the order's credits plus the career's contract-rate skills for its legality. */
    public static int payout(CitizenState.Citizen citizen, WorkOrder order) {
        return SkillRules.payout(order.credits(), order.underground(), SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    /** Percent more Sable Leaf bales per harvest (0 for a character without the grafting skills). */
    public static int yieldPercent(CitizenState.Citizen citizen) {
        return SkillRules.yieldPercent(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    public static int yieldPercent(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).map(SkillService::yieldPercent).orElse(0);
    }

    /** Percent off a home upgrade for this citizen. */
    public static int upgradeDiscountPercent(CitizenState.Citizen citizen) {
        return SkillRules.upgradeDiscountPercent(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills());
    }

    public static int upgradeDiscountPercent(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).map(SkillService::upgradeDiscountPercent).orElse(0);
    }

    public static boolean licensed(CitizenState.Citizen citizen, String category) {
        return SkillRules.hasLicence(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills(), category);
    }

    public static Set<String> unlocked(CitizenState.Citizen citizen) { return citizen.skills(); }

    /** Terminal action: spends points on one node, then refreshes the terminal. */
    public static void unlock(ServerPlayer player, BlockPos pos, String nodeId) {
        if (!TerminalNetwork.requestSlot(player)) return;
        if (!TerminalNetwork.authorizedForStarterShop(player, pos)) {
            player.sendSystemMessage(Component.literal("Register and use a nearby placed Operations Terminal."));
            return;
        }
        try {
            learn(player, nodeId);
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal(error.getMessage() == null ? "Skill unlock failed" : error.getMessage()));
        }
        TerminalNetwork.open(player, pos);
    }

    /** The unlock itself, without the terminal packet: throws IllegalStateException with the reason when refused. */
    static void learn(ServerPlayer player, String nodeId) {
        CitizenState state = CitizenState.get(player.server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElseThrow(() -> new IllegalStateException("Register as a citizen first"));
        SkillNode node = SkillCatalog.INSTANCE.find(nodeId);
        if (node == null) throw new IllegalStateException("Unknown skill");
        String blocker = blocker(citizen, node);
        if (blocker != null) throw new IllegalStateException(blocker);
        state.unlockSkill(player.getUUID(), node.id());
        player.server.overworld().getDataStorage().save();
        player.sendSystemMessage(Component.literal("Learned " + node.title() + ". " + pointsAvailable(citizen) + " skill points left."));
        Notice.send(player, Notice.Kind.SKILL, "Skill learned", node.title() + ". " + pointsAvailable(citizen) + (pointsAvailable(citizen) == 1 ? " point left." : " points left."));
    }
}
