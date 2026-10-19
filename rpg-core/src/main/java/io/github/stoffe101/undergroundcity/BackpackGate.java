package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.ResidenceGuard.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The server's own answer to "may this player's backpack reach into that container?" (D-092).
 *
 * <p>Sophisticated Backpacks' Deposit and Restock upgrades act on a container the player points the pack at. The mod's inventory-interaction packet carries a
 * block position chosen by the client and its handler (version 3.26.12) checks neither the distance nor anybody's rights, and it does not go through Forge's
 * right-click event that {@link ResidenceGuard} listens to. A modified client could therefore deposit into or restock from any loaded container, including a private
 * chest in somebody's home. {@code InventoryInteractionHelperMixin} asks here before the mod touches the container; the rule is the guard's own container rule plus
 * a reach limit. Like {@code BlueprintGate}, it is a gate on a third-party packet path, and it does nothing when the mod is absent (the mixins are pseudo).
 */
public final class BackpackGate {
    /** Farther than the game itself lets a player reach (a click is accepted within 6 blocks; the margin covers a pointed-at container at the edge). */
    static final double REACH_SQUARED = 8.0 * 8.0;

    private BackpackGate() {}

    /** May this player's backpack interact with the container at {@code pos}? Called on the logical server. */
    public static boolean allowsContainerInteraction(BlockPos pos, Level level, Player player) {
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) return true;   // nothing to protect on a client
        return allowsContainerInteraction(pos, serverLevel, sp);
    }

    static boolean allowsContainerInteraction(BlockPos pos, ServerLevel level, ServerPlayer player) {
        if (player.level() != level || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH_SQUARED) return false;
        if (player.isCreative()) return true;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return true;
        String dimension = level.dimension().location().toString();
        Residence home = ledger.residenceAt(dimension, pos);
        if (home == null) return true;   // outside every home: open, like every other container in the world (burglary is a game rule, D-079)
        return ResidenceGuard.allowed(ledger, home, player.getUUID(), dimension, pos, Act.CONTAINER);
    }

    /** A placed backpack never feeds the players standing near it (see {@code FeedingUpgradeWrapperMixin}); only the one a player wears does. */
    public static boolean placedBackpacksFeedBystanders() { return false; }
}
