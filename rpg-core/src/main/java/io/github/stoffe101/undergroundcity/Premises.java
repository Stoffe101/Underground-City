package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Trade premises (D-169): a commercial workshop or a warehouse has the {@code trade} facility. A workshop station standing in such premises runs one more batch at a time and works a
 * quarter faster, for whoever may use the premises' workstations (the holder, or a Crew member with that permission, while the home is in good standing); and bulk orders that ask for
 * the facility ({@code requires_facility}) are taken only by somebody who holds such premises. The premises cost what every home costs: rent or upkeep on the household's active
 * clock, never while everybody is away, and a lapse withdraws the bonus with the other comforts and takes nothing. Nothing here pays a credit on its own.
 */
public final class Premises {
    public static final String TRADE = "trade";
    public static final int EXTRA_BATCH = 1;
    public static final double SPEED = 1.25;

    private Premises() {}

    /** The premises a station at {@code pos} stands in, when they are trade premises in good standing that {@code player} may work in; else null. */
    static CrewLedger.Residence tradeAt(ServerLevel level, BlockPos pos, UUID player) {
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return null;
        CrewLedger.Residence home = ledger.residenceAt(level.dimension().location().toString(), pos);
        if (home == null || !PropertyService.facilities(home).contains(TRADE)) return null;
        return ResidenceGuard.rights(ledger, home, player).workstations() ? home : null;
    }

    /** True when {@code player} may work in trade premises with {@code facility} somewhere (for an order that asks for the facility). */
    static boolean holds(MinecraftServer server, UUID player, String facility) {
        CrewLedger ledger = CrewService.ledger(server);
        if (ledger == null) return false;
        for (CrewLedger.Residence home : ledger.allResidences())
            if (home.ownerType != CrewLedger.OwnerType.NONE && PropertyService.facilities(home).contains(facility) && ResidenceGuard.rights(ledger, home, player).workstations()) return true;
        return false;
    }

    /** The batches a station of this tier runs at a time here. */
    static int batch(Workshops.Tier tier, boolean trade) { return tier.batch() + (trade ? EXTRA_BATCH : 0); }

    /** How long a batch takes here. */
    static long ticks(Workshops.Recipe recipe, Workshops.Tier tier, boolean trade) {
        return trade ? Math.max(0, Math.round(recipe.seconds() * 20 / (tier.speed() * SPEED))) : Workshops.ticksFor(recipe, tier);
    }
}
