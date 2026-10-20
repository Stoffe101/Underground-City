package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps a leased home to its tenants: who may break or place blocks inside, open its doors, its containers and its workstations. Server
 * side only (a client's own prediction is never trusted). A unit nobody holds is a showroom: anyone may walk in and look, nobody may build
 * in it or open anything. The one-block shell around every home (its walls, floor and ceiling) belongs to the city, so a tenant cannot
 * open a neighbour's wall. Creative-mode players are never held back (an operator in survival is).
 *
 * <p>Beyond the player's own block actions (D-090) the guard also closes the practical ways round them: pistons moving blocks across a home's
 * boundary, hoppers and droppers placed to drain someone's storage, containers that are not vanilla (any block entity offering an item handler),
 * item frames, armor stands, paintings and container vehicles, placing entities, fluids and fire with an item, mob griefing, trampling,
 * hostile natural spawns and the entities caught in an explosion. Everything is keyed by the registered home: a house that is NOT registered
 * (the NPC properties meant for burglary) is never touched by any of it. Still not covered, by design or for want of an event: fire that
 * spreads or is lit by lightning or lava, a fluid dispenser, vehicles from other mods, and operators or creative players (see docs/design/crews.md).
 */
public final class ResidenceGuard {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public enum Act { BREAK, PLACE, DOOR, CONTAINER, WORKSTATION, OTHER, EXPLODE }

    /** Blocks that count as a workstation inside a home. Data: {@code data/underground_city/tags/blocks/residence_workstations.json}. */
    public static final TagKey<Block> WORKSTATIONS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_workstations"));

    /** The heavy workstations (anvils, smithing table, grindstone, brewing stand, enchanting table, gun tables): a home needs the workshop facility to hold or use them (D-099). */
    public static final TagKey<Block> HEAVY_WORKSTATIONS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_heavy_workstations"));
    /** Furnaces, blast furnaces and smokers: a home needs the furnace facility. Every other workstation of the tag above needs the crafting facility. */
    public static final TagKey<Block> FURNACES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_furnaces"));

    /** Blocks that move items between containers by themselves (hoppers, droppers, dispensers). Data: {@code tags/blocks/residence_transfer_blocks.json}. */
    public static final TagKey<Block> TRANSFER_BLOCKS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_transfer_blocks"));
    /** Entities that hold or display someone's things. Data: {@code tags/entity_types/residence_protected_entities.json}; anything that is a container is protected too. */
    public static final TagKey<EntityType<?>> PROTECTED_ENTITIES = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "residence_protected_entities"));

    public record Rights(boolean access, boolean build, boolean storage, boolean workstations) {
        static final Rights NONE = new Rights(false, false, false, false), ALL = new Rights(true, true, true, true);
    }

    // ------------------------------------------------------------------------------------------------------------ decisions

    /** What a player may do in a home right now. */
    public static Rights rights(CrewLedger ledger, Residence r, UUID player) { return rights(ledger, r, player, true); }

    /** The same, optionally as if the home had not lapsed: what the person's role would allow in a home in good standing. */
    static Rights rights(CrewLedger ledger, Residence r, UUID player, boolean honourLapse) {
        if (!r.owned() || r.reservedBy != null) return Rights.NONE;
        // A lapsed tenancy keeps the door and the containers (to take things out) and loses the comforts: building and workstations (D-099).
        boolean lapsed = honourLapse && r.billed() && r.standing(PropertyCatalog.INSTANCE.rules()) == PropertyRules.Standing.LAPSED;
        if (r.ownerType == OwnerType.PLAYER) return r.owner.equals(player) ? (lapsed ? new Rights(true, false, true, false) : Rights.ALL) : guest(ledger, r, player);
        Crew crew = ledger.crew(r.owner);
        if (crew == null) return Rights.NONE;
        if (crew.roleOf(player) == null) return guest(ledger, r, player);
        boolean access = crew.can(player, Perm.ACCESS_HOME);
        if (lapsed) return new Rights(access, false, access && crew.can(player, Perm.STORAGE), false);
        return new Rights(access, access && crew.can(player, Perm.BUILD), access && crew.can(player, Perm.STORAGE), access && crew.can(player, Perm.WORKSTATIONS));
    }

    /** The facility a block needs a home to have, or null for a block that needs none. */
    static String facilityNeeded(BlockState state) {
        if (state.is(HEAVY_WORKSTATIONS)) return "workshop";
        if (state.is(FURNACES)) return "furnace";
        if (state.is(WORKSTATIONS)) return "crafting";
        return null;
    }

    static boolean hasFacility(Residence r, String facility) { return PropertyService.facilities(r).contains(facility); }

    static String noFacility(Residence r, String facility) {
        return switch (facility) {
            case "workshop" -> "This home has no workshop space for heavy workstations (anvil, smithing table, grindstone, brewing stand, gun tables). Rent a workshop home, or fit the Workshop upgrade to one you own.";
            case "furnace" -> "This home has no furnace corner.";
            case "plumbing" -> "This home has no running water. Water may only be placed in a home with the Running water upgrade.";
            default -> "This home has no space for a crafting station.";
        };
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

    /**
     * A furnace, a smoker, a blast furnace and a brewing stand are workstations and also hold things. When the home's comforts are withdrawn (it has lapsed) or it never had the
     * facility (a home from an older save), the resident may still open such a block to take their things out: otherwise a move-out, which is refused while anything is stored,
     * could never happen. Building, anvils, crafting tables and every other workstation stay locked, and what a person's role forbids stays forbidden.
     */
    static boolean takeOutOnly(CrewLedger ledger, Residence r, UUID player, BlockState state) {
        boolean lapsedOut = rights(ledger, r, player, false).workstations() && !rights(ledger, r, player).workstations();
        String need = facilityNeeded(state);
        boolean withoutFacility = need != null && !hasFacility(r, need) && rights(ledger, r, player).workstations();
        return lapsedOut || withoutFacility;
    }

    /** What the player is touching, from the block (workstations first: a furnace is also a container). */
    public static Act classify(BlockState state, net.minecraft.world.level.block.entity.BlockEntity entity) {
        if (state.is(WORKSTATIONS)) return Act.WORKSTATION;
        if (entity instanceof PropertyCacheBlockEntity) return Act.OTHER; // a Household Safe has its own lock, lockpick and evidence rules
        if (isContainer(entity)) return Act.CONTAINER;
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) return Act.DOOR;
        return Act.OTHER;
    }

    /**
     * Does this block entity hold items other people could take? Every vanilla container, and any modded block that offers an item handler on any side
     * (backpacks placed in the world, machines, drawers), so a mod's storage is protected without the guard knowing the mod.
     */
    public static boolean isContainer(BlockEntity entity) {
        if (entity == null || entity instanceof PropertyCacheBlockEntity) return false;
        if (entity instanceof Container) return true;
        if (entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent()) return true;
        for (Direction side : Direction.values()) if (entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).isPresent()) return true;
        return false;
    }

    /** Is this entity somebody's display or storage (frames, stands, paintings, chest vehicles, anything tagged or any container)? */
    public static boolean protectedEntity(Entity entity) {
        return entity != null && (entity.getType().is(PROTECTED_ENTITIES) || entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof Container);
    }

    /** Items that put something into the world when used on a block: entities, fluids, fire. They need the right to build where the thing would appear. */
    static boolean placesSomething(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof HangingEntityItem || item instanceof ArmorStandItem || item instanceof BoatItem || item instanceof MinecartItem || item instanceof BucketItem
            || item instanceof FlintAndSteelItem || item instanceof FireChargeItem || item instanceof SpawnEggItem || item instanceof EndCrystalItem;
    }

    /**
     * Hoppers, droppers and dispensers move items on their own, so placing one beside somebody's storage would let a builder empty a container they may not
     * open. A transfer block may only be placed beside a container the placer may use (or that is theirs, if it is private), and a container may only be
     * placed beside a transfer block by someone who may use shared storage.
     */
    static boolean transferRuleAllows(CrewLedger ledger, Residence r, UUID player, Level level, BlockPos pos, BlockState placed) {
        if (!r.insideInterior(r.dimension, pos)) return true;
        Rights rights = rights(ledger, r, player);
        if (placed.is(TRANSFER_BLOCKS)) {
            for (Direction side : Direction.values()) {
                BlockPos neighbour = pos.relative(side);
                UUID claimant = r.claims.get(neighbour.asLong());
                if (claimant != null) { if (!claimant.equals(player)) return false; continue; }
                if (isContainer(level.getBlockEntity(neighbour)) && !rights.storage()) return false;
            }
        } else if (!rights.storage() && (isContainer(level.getBlockEntity(pos)) || placed.is(Tags.Blocks.CHESTS) || placed.is(Tags.Blocks.BARRELS))) {
            for (Direction side : Direction.values()) if (level.getBlockState(pos.relative(side)).is(TRANSFER_BLOCKS)) return false;
        }
        return true;
    }

    /** The words for a refusal. */
    public static String refusal(CrewLedger ledger, Residence r, Act act) {
        if (!r.owned() || r.reservedBy != null) return "This home is for lease. You can look around; open the Crew screen (K) beside it to lease it.";
        String owner = r.ownerType == OwnerType.PLAYER ? "a private home" : "a Crew home";
        Crew crew = r.ownerType == OwnerType.CREW ? ledger.crew(r.owner) : null;
        if (crew != null) owner = "the home of " + crew.name;
        return switch (act) {
            case BREAK, PLACE -> "You cannot build or change things here: this is " + owner + ".";
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
        } else if (isContainer(level.getBlockEntity(event.getPos())) && !allowed(ledger, r, player.getUUID(), dim, event.getPos(), Act.CONTAINER)) {
            // breaking a chest spills what is in it: whoever may not open it may not break it either
            event.setCanceled(true);
            tell(player, refusal(ledger, r, Act.CONTAINER));
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
        } else if (!transferRuleAllows(ledger, r, player.getUUID(), level, event.getPos(), event.getPlacedBlock())) {
            event.setCanceled(true);
            tell(player, "A hopper, dropper or dispenser beside somebody's storage could empty it. You need the right to use shared storage, or the container must be yours.");
        } else if (r.insideInterior(dim, event.getPos())) {
            String need = facilityNeeded(event.getPlacedBlock());
            if (need != null && !hasFacility(r, need)) {
                event.setCanceled(true);
                tell(player, noFacility(r, need));
            } else if (isContainerBlock(level, event.getPos(), event.getPlacedBlock()) && PropertyService.containerCount(level, r) > PropertyService.maxContainers(r)) {
                event.setCanceled(true);
                tell(player, "This home holds at most " + PropertyService.maxContainers(r) + " containers (chests, barrels, backpacks, machines with storage). Fit the storage upgrade or take one out.");
            }
        }
    }

    /** A container that was just placed: any block entity with storage, or one of the usual storage blocks before its block entity exists. */
    static boolean isContainerBlock(ServerLevel level, BlockPos pos, BlockState placed) {
        if (placed.is(WORKSTATIONS) || placed.is(TRANSFER_BLOCKS)) return false;   // a furnace or a hopper holds items but is not a place to keep things
        return isContainer(level.getBlockEntity(pos)) || placed.is(Tags.Blocks.CHESTS) || placed.is(Tags.Blocks.BARRELS);
    }

    /** Storage proper: a container that is not a workstation or a hopper. These are what a home's container limit counts. */
    public static boolean isStorage(BlockEntity entity) {
        return entity != null && isContainer(entity) && !entity.getBlockState().is(WORKSTATIONS) && !entity.getBlockState().is(TRANSFER_BLOCKS);
    }

    @SubscribeEvent
    public void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || bypass(player)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        BlockPos pos = event.getPos();
        // What the item in hand would put into the world (an entity, a fluid, fire) needs the right to build where it would appear.
        if (event.getFace() != null && placesSomething(event.getItemStack())) {
            BlockPos target = pos.relative(event.getFace());
            Residence home = ledger.residenceAt(dim, target);
            if (home != null && !allowed(ledger, home, player.getUUID(), dim, target, Act.PLACE)) {
                deny(event, player, refusal(ledger, home, Act.PLACE));
                return;
            }
            if (home != null && home.insideInterior(dim, target) && event.getItemStack().getItem() instanceof BucketItem bucket) {
                String why = fluidRefusal(home, bucket);
                if (why != null) { deny(event, player, why); return; }
            }
        }
        Residence r = ledger.residenceAt(dim, pos);
        if (r == null) return;
        BlockState state = level.getBlockState(pos);
        var touched = level.getBlockEntity(pos);
        Act act = classify(state, touched);
        if (act == Act.WORKSTATION && isContainer(touched) && r.insideInterior(dim, pos) && takeOutOnly(ledger, r, player.getUUID(), state)) act = Act.CONTAINER;
        // An ender chest is the player's own; sitting on a bed, reading a sign and the like are plain interactions.
        if (!allowed(ledger, r, player.getUUID(), dim, pos, act)) { deny(event, player, refusal(ledger, r, act)); return; }
        if (act == Act.WORKSTATION && r.insideInterior(dim, pos)) {
            String need = facilityNeeded(state);
            if (need != null && !hasFacility(r, need)) deny(event, player, noFacility(r, need));
        }
    }

    /** Lava never goes in a home; water only where the home has running water. Null when the bucket is fine (or empty). */
    static String fluidRefusal(Residence home, BucketItem bucket) {
        if (bucket.getFluid() == Fluids.EMPTY) return null;
        if (bucket.getFluid().isSame(Fluids.LAVA)) return "Lava is not allowed inside a home.";
        if (bucket.getFluid().isSame(Fluids.WATER) && !hasFacility(home, "plumbing")) return noFacility(home, "plumbing");
        return null;
    }

    private static void deny(PlayerInteractEvent.RightClickBlock event, ServerPlayer player, String words) {
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        tell(player, words);
    }

    // ---------------------------------------------------------------------------------------------------- entities, fluids, pistons, mobs

    /** The home an entity of a kind the guard protects is standing in, or null when it is outside every home or not protected. */
    private static Residence homeOf(CrewLedger ledger, Entity entity) {
        if (!protectedEntity(entity) || !(entity.level() instanceof ServerLevel level)) return null;
        return ledger.residenceAt(level.dimension().location().toString(), entity.blockPosition());
    }

    private boolean entityDenied(ServerPlayer player, Entity target, Act act) {
        if (bypass(player) || !(target.level() instanceof ServerLevel level)) return false;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return false;
        Residence r = homeOf(ledger, target);
        if (r == null || allowed(ledger, r, player.getUUID(), level.dimension().location().toString(), target.blockPosition(), act)) return false;
        tell(player, refusal(ledger, r, act));
        return true;
    }

    /** Taking an item out of a frame, dressing or undressing a stand, opening a chest vehicle: the same right as opening a container. */
    @SubscribeEvent
    public void useEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (entityDenied(player, event.getTarget(), Act.CONTAINER)) { event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true); }
    }

    @SubscribeEvent
    public void useEntityAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (entityDenied(player, event.getTarget(), Act.CONTAINER)) { event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true); }
    }

    /** Does breaking this entity spill something somebody keeps in it (a framed item, a stand's equipment, a vehicle's cargo)? */
    static boolean holdsThings(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) return !frame.getItem().isEmpty();
        if (entity instanceof ArmorStand stand) {
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) if (!stand.getItemBySlot(slot).isEmpty()) return true;
            return false;
        }
        return entity instanceof Container container && !container.isEmpty();
    }

    /** Breaking a frame, a painting, a stand or a chest vehicle needs the right to build, and the right to use storage as well when something is in it. */
    @SubscribeEvent
    public void hitEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEntity().level().isClientSide()) return;
        if (entityDenied(player, event.getTarget(), Act.BREAK) || (holdsThings(event.getTarget()) && entityDenied(player, event.getTarget(), Act.CONTAINER))) event.setCanceled(true);
    }

    /** An arrow, a trident or a snowball cannot do what its owner could not: and nothing at all hurts a home's frames and stands when the thrower is not a player. */
    @SubscribeEvent
    public void projectile(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity().level() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        Residence r = homeOf(ledger, hit.getEntity());
        if (r == null) return;
        if (event.getProjectile().getOwner() instanceof ServerPlayer owner) {
            String dim = level.dimension().location().toString();
            BlockPos at = hit.getEntity().blockPosition();
            if (bypass(owner) || (allowed(ledger, r, owner.getUUID(), dim, at, Act.BREAK) && (!holdsThings(hit.getEntity()) || allowed(ledger, r, owner.getUUID(), dim, at, Act.CONTAINER)))) return;
            tell(owner, refusal(ledger, r, Act.BREAK));
        }
        event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
    }

    /** A bucket empties or fills only where its holder may build (the item-in-hand check above covers the click; this covers anything that fires the bucket event on its own). */
    @SubscribeEvent
    public void bucket(FillBucketEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || bypass(player)) return;
        if (!(event.getTarget() instanceof BlockHitResult hit)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        for (BlockPos pos : List.of(hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()))) {
            Residence r = ledger.residenceAt(dim, pos);
            if (r != null && !allowed(ledger, r, player.getUUID(), dim, pos, Act.PLACE)) {
                event.setCanceled(true);
                tell(player, refusal(ledger, r, Act.PLACE));
                return;
            }
            if (r != null && r.insideInterior(dim, pos) && event.getEmptyBucket().getItem() instanceof BucketItem bucket) {
                String why = fluidRefusal(r, bucket);
                if (why != null) { event.setCanceled(true); tell(player, why); return; }
            }
        }
    }

    /**
     * A piston (or a sticky one's pull) may not move or destroy a block of a home from outside, nor push anything across a home's boundary. Only a piston
     * standing in a home's own interior may move things, and only things that stay in that interior: the walls belong to the city.
     */
    @SubscribeEvent
    public void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        String dim = level.dimension().location().toString();
        if (ledger == null || !ledger.residenceNear(dim, event.getPos(), 16)) return;
        Residence home = ledger.residenceAt(dim, event.getPos());
        boolean insideInterior = home != null && home.insideInterior(dim, event.getPos());
        List<BlockPos> touched = new ArrayList<>();
        touched.add(event.getFaceOffsetPos());
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure != null && structure.resolve()) {
            Direction push = event.getPistonMoveType().isExtend ? event.getDirection() : event.getDirection().getOpposite();
            for (BlockPos moved : structure.getToPush()) { touched.add(moved); touched.add(moved.relative(push)); }
            touched.addAll(structure.getToDestroy());
        }
        for (BlockPos pos : touched) {
            Residence r = ledger.residenceAt(dim, pos);
            if (r != null && !(insideInterior && r == home && r.insideInterior(dim, pos))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** Endermen, zombies at doors, ravagers and the like change nothing inside a home. */
    @SubscribeEvent
    public void mobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger != null && ledger.residenceAt(level.dimension().location().toString(), entity.blockPosition()) != null) event.setResult(Event.Result.DENY);
    }

    /** The wither and the dragon break blocks by another route. */
    @SubscribeEvent
    public void livingDestroys(LivingDestroyBlockEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger != null && ledger.residenceAt(level.dimension().location().toString(), event.getPos()) != null) event.setCanceled(true);
    }

    /** Something jumping on a tenant's farmland in their home does not ruin it (a player who may build there can still till it). */
    @SubscribeEvent
    public void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        Residence r = ledger.residenceAt(dim, event.getPos());
        if (r == null) return;
        if (event.getEntity() instanceof ServerPlayer player && (bypass(player) || allowed(ledger, r, player.getUUID(), dim, event.getPos(), Act.PLACE))) return;
        event.setCanceled(true);
    }

    /** A home is not a dungeon: hostile monsters do not spawn on their own inside it (spawners, eggs and commands still work). */
    @SubscribeEvent
    public void spawn(MobSpawnEvent.PositionCheck event) {
        if (event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        if (event.getSpawnType() != MobSpawnType.NATURAL && event.getSpawnType() != MobSpawnType.CHUNK_GENERATION) return;
        ServerLevel level = event.getLevel().getLevel();
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger != null && ledger.residenceAt(level.dimension().location().toString(), BlockPos.containing(event.getX(), event.getY(), event.getZ())) != null) event.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public void explode(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        String dim = level.dimension().location().toString();
        event.getAffectedBlocks().removeIf(pos -> ledger.residenceAt(dim, pos) != null);
        if (!event.getAffectedEntities().isEmpty()) {
            try {
                event.getAffectedEntities().removeIf(entity -> homeOf(ledger, entity) != null);
            } catch (UnsupportedOperationException fixedList) {
                LOG.debug("An explosion handed over a fixed entity list; the frames and stands in it were left as the game decided");
            }
        }
    }
}
