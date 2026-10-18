package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Keeps a leased home to its tenants: who may break or place blocks inside, open its doors, its containers and its workstations. Server
 * side only (a client's own prediction is never trusted). A unit nobody holds is a showroom: anyone may walk in and look, nobody may build
 * in it or open anything. The one-block shell around every home (its walls, floor and ceiling) belongs to the city, so a tenant cannot
 * open a neighbour's wall. Creative-mode players are never held back (an operator in survival is). Not guarded yet: pistons, fluids,
 * fire, mobs and entities such as item frames (see docs/design/crews.md).
 */
public final class ResidenceGuard {
    public enum Act { BREAK, PLACE, DOOR, CONTAINER, WORKSTATION, OTHER, EXPLODE }

    /** Blocks that count as a workstation inside a home. Data: {@code data/underground_city/tags/blocks/residence_workstations.json}. */
    public static final TagKey<Block> WORKSTATIONS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_workstations"));

    public record Rights(boolean access, boolean build, boolean storage, boolean workstations) {
        static final Rights NONE = new Rights(false, false, false, false), ALL = new Rights(true, true, true, true);
    }

    // ------------------------------------------------------------------------------------------------------------ decisions

    /** What a player may do in a home right now. */
    public static Rights rights(CrewLedger ledger, Residence r, UUID player) {
        if (!r.owned() || r.reservedBy != null) return Rights.NONE;
        if (r.ownerType == OwnerType.PLAYER) return r.owner.equals(player) ? Rights.ALL : guest(ledger, r, player);
        Crew crew = ledger.crew(r.owner);
        if (crew == null) return Rights.NONE;
        if (crew.roleOf(player) == null) return guest(ledger, r, player);
        boolean access = crew.can(player, Perm.ACCESS_HOME);
        return new Rights(access, access && crew.can(player, Perm.BUILD), access && crew.can(player, Perm.STORAGE), access && crew.can(player, Perm.WORKSTATIONS));
    }

    private static Rights guest(CrewLedger ledger, Residence r, UUID player) {
        Long until = r.guests.get(player);
        return until != null && until > ledger.now() ? new Rights(true, false, false, false) : Rights.NONE;
    }

    /** Is this player still somebody whose private containers the home honours (a tenant, a member, or a guest in their retrieval window)? */
    private static boolean entitled(CrewLedger ledger, Residence r, UUID player) {
        return rights(ledger, r, player).access();
    }

    public static boolean allowed(CrewLedger ledger, Residence r, UUID player, String dimension, BlockPos pos, Act act) {
        if (act == Act.EXPLODE) return false;
        if (!r.owned() || r.reservedBy != null) return act == Act.DOOR || act == Act.OTHER; // a showroom: look, don't touch
        boolean interior = r.insideInterior(dimension, pos);
        if (!interior && (act == Act.BREAK || act == Act.PLACE)) return false; // the shell is the city's
        Rights rights = rights(ledger, r, player);
        UUID claimant = r.claims.get(pos.asLong());
        boolean claimed = claimant != null && interior && entitled(ledger, r, claimant);
        return switch (act) {
            case DOOR, OTHER -> rights.access();
            case PLACE -> rights.build();
            case BREAK -> claimed ? claimant.equals(player) && rights.build() : rights.build();
            case CONTAINER -> claimed ? claimant.equals(player) && rights.access() : rights.storage();
            case WORKSTATION -> rights.workstations();
            case EXPLODE -> false;
        };
    }

    /** What the player is touching, from the block (workstations first: a furnace is also a container). */
    public static Act classify(BlockState state, net.minecraft.world.level.block.entity.BlockEntity entity) {
        if (state.is(WORKSTATIONS)) return Act.WORKSTATION;
        if (entity instanceof PropertyCacheBlockEntity) return Act.OTHER; // a Household Safe has its own lock, lockpick and evidence rules
        if (entity instanceof Container) return Act.CONTAINER;
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) return Act.DOOR;
        return Act.OTHER;
    }

    /** The words for a refusal. */
    public static String refusal(CrewLedger ledger, Residence r, Act act) {
        if (!r.owned() || r.reservedBy != null) return "This home is for lease. You can look around; open the Crew screen (K) beside it to lease it.";
        String owner = r.ownerType == OwnerType.PLAYER ? "a private home" : "a Crew home";
        Crew crew = r.ownerType == OwnerType.CREW ? ledger.crew(r.owner) : null;
        if (crew != null) owner = "the home of " + crew.name;
        return switch (act) {
            case BREAK, PLACE -> "You cannot build here: this is " + owner + ".";
            case CONTAINER -> "That container is not yours to open (" + owner + ").";
            case WORKSTATION -> "You may not use that workstation (" + owner + ").";
            default -> "This is " + owner + ". You are not on its door list.";
        };
    }

    // ------------------------------------------------------------------------------------------------------------ events

    private static boolean bypass(ServerPlayer player) { return player.isCreative(); }

    private static void tell(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }

    @SubscribeEvent
    public void breakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || bypass(player)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        Residence r = ledger.residenceAt(dim, event.getPos());
        if (r == null) return;
        if (!allowed(ledger, r, player.getUUID(), dim, event.getPos(), Act.BREAK)) {
            event.setCanceled(true);
            tell(player, refusal(ledger, r, Act.BREAK));
        }
    }

    @SubscribeEvent
    public void placeBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || bypass(player)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        Residence r = ledger.residenceAt(dim, event.getPos());
        if (r == null) return;
        if (!allowed(ledger, r, player.getUUID(), dim, event.getPos(), Act.PLACE)) {
            event.setCanceled(true);
            tell(player, refusal(ledger, r, Act.PLACE));
        }
    }

    @SubscribeEvent
    public void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || bypass(player)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        BlockPos pos = event.getPos();
        Residence r = ledger.residenceAt(dim, pos);
        if (r == null) return;
        BlockState state = level.getBlockState(pos);
        Act act = classify(state, level.getBlockEntity(pos));
        // An ender chest is the player's own; sitting on a bed, reading a sign and the like are plain interactions.
        if (!allowed(ledger, r, player.getUUID(), dim, pos, act)) {
            event.setUseBlock(Event.Result.DENY);
            event.setUseItem(Event.Result.DENY);
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            tell(player, refusal(ledger, r, act));
        }
    }

    @SubscribeEvent
    public void explode(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        event.getAffectedBlocks().removeIf(pos -> ledger.residenceAt(dim, pos) != null);
    }
}
