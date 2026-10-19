package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;
import static io.github.stoffe101.undergroundcity.ModReflect.call;
import static io.github.stoffe101.undergroundcity.ModReflect.callOn;
import static io.github.stoffe101.undergroundcity.ModReflect.cls;

/**
 * Sophisticated Backpacks as Underground City runs it (D-092): the policy that sizes the packs and switches upgrades on or off, the pack as an item that survives a
 * save, a drop and a pickup, a placed pack, and a placed pack in somebody's home. Every test passes at once when the mods are not loaded (the hosted jobs without the
 * survival set), like the other optional-mod tests; the {@code survival} job runs them.
 *
 * <p>What this is not: it is not a person using the backpack screen. The GUI, the sorting buttons and the upgrade tabs are the mod's own and are exercised by the real-client
 * journey (docs/technical/testing.md). These tests drive the same public methods and packets' server paths the mod uses, with scripted players.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BackpackGameTests {
    private BackpackGameTests() {}

    private static final String MOD = "sophisticatedbackpacks";
    private static final String HELPER = "net.p3pp3rf1y.sophisticatedbackpacks.util.InventoryInteractionHelper";
    private static final String BLOCK_ENTITY = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity";

    private static boolean loaded() { return SurvivalSet.backpacksLoaded(); }

    private static Item item(String path) { return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(MOD, path)); }

    private static ItemStack stack(String path) { return new ItemStack(item(path)); }

    private static JsonObject policy() { return BackpackPolicy.load(); }

    // ------------------------------------------------------------------------------------------------------------ the policy

    @GameTest(template = "empty")
    public static void theRunningConfigurationIsTheUndergroundCityPolicy(GameTestHelper helper) {
        if (!loaded()) { helper.succeed(); return; }
        List<String> drift = BackpackPolicy.audit(policy());
        check(drift.isEmpty(), "the running Sophisticated Backpacks configuration differs from the policy: " + drift);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyItemOfTheModIsClassifiedAndTheTiersGrow(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        JsonObject policy = policy();
        List<String> enabled = BackpackPolicy.strings(policy.getAsJsonArray("enabled")), disabled = BackpackPolicy.strings(policy.getAsJsonArray("disabled"));
        int registered = 0;
        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            if (!key.getNamespace().equals(MOD)) continue;
            registered++;
            check(enabled.contains(key.toString()) ^ disabled.contains(key.toString()), key + " must be listed exactly once in the backpack policy");
        }
        check(registered == enabled.size() + disabled.size(), "the policy lists " + (enabled.size() + disabled.size()) + " items, the mod registers " + registered);
        // tiers: each holds more than the last, never more than the mod's own default, and never fewer upgrade slots
        int lastSlots = 0, lastUpgrades = 0;
        for (String tier : List.of("leatherBackpack", "copperBackpack", "ironBackpack", "goldBackpack", "diamondBackpack", "netheriteBackpack")) {
            String id = policy.getAsJsonObject("tiers").getAsJsonObject(tier).get("item").getAsString();
            ItemStack pack = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id)));
            int slots = BackpackSupport.slots(pack), upgrades = BackpackSupport.upgradeSlots(pack);
            check(slots > lastSlots && upgrades >= lastUpgrades, tier + " must hold more than the tier before it: " + slots + "/" + upgrades + " after " + lastSlots + "/" + lastUpgrades);
            lastSlots = slots;
            lastUpgrades = upgrades;
        }
        check(lastSlots <= 90 && lastUpgrades <= 4, "the best backpack is capped at 90 slots and 4 upgrades by policy: " + lastSlots + "/" + lastUpgrades);
        helper.succeed();
    }

    /** Disabled items have no recipe at all, enabled ones can be made: this is what keeps the upgrades that the policy withholds out of the economy. */
    @GameTest(template = "empty")
    public static void recipesFollowThePolicy(GameTestHelper helper) {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        List<Recipe<?>> recipes = new ArrayList<>(level.getRecipeManager().getRecipes());
        JsonObject policy = policy();
        for (String id : BackpackPolicy.strings(policy.getAsJsonArray("enabled"))) {
            Item target = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
            boolean craftable = recipes.stream().anyMatch(r -> r.getResultItem(level.registryAccess()).is(target));
            check(craftable, id + " is enabled by the policy but no recipe makes it");
        }
        for (String id : BackpackPolicy.strings(policy.getAsJsonArray("disabled"))) {
            Item target = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
            List<ResourceLocation> found = recipes.stream().filter(r -> r.getResultItem(level.registryAccess()).is(target)).map(Recipe::getId).toList();
            check(found.isEmpty(), id + " is disabled by the policy but recipes still make it: " + found);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the pack as an item

    @GameTest(template = "empty")
    public static void aWornBackpackKeepsItsItemsAndUpgradesThroughASaveAndALoad(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerPlayer p = player(helper, "uc_bp_save", "farmer");
        ItemStack pack = stack("iron_backpack");
        BackpackSupport.wear(p, pack);
        check(BackpackSupport.worn(p), "the pack should sit in the chest slot");
        IItemHandlerModifiable inventory = BackpackSupport.inventory(pack);
        check(inventory.getSlots() == BackpackSupport.slots(pack), "the pack's handler has " + inventory.getSlots() + " slots, the item reports " + BackpackSupport.slots(pack));
        check(BackpackSupport.insertAll(inventory, new ItemStack(Items.DIAMOND, 7)) && BackpackSupport.insertAll(inventory, new ItemStack(Items.BREAD, 12)), "the items did not fit");
        check(BackpackSupport.upgrades(pack).insertItem(0, stack("pickup_upgrade"), false).isEmpty(), "an upgrade slot refused a pickup upgrade");
        Optional<UUID> before = BackpackSupport.contentsUuid(pack);
        check(before.isPresent(), "a pack that holds things has a contents id");
        // the stack goes through NBT exactly as it does in a player file; the contents live in the world's save data under that id
        ItemStack loaded = ItemStack.of(pack.save(new CompoundTag()));
        check(BackpackSupport.contentsUuid(loaded).equals(before), "the contents id changed through a save");
        check(BackpackSupport.count(BackpackSupport.inventory(loaded), Items.DIAMOND) == 7 && BackpackSupport.count(BackpackSupport.inventory(loaded), Items.BREAD) == 12, "the items changed through a save");
        check(BackpackSupport.upgrades(loaded).getStackInSlot(0).is(item("pickup_upgrade")), "the upgrade did not survive a save");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void upgradeSlotsAndTheStackUpgradeLimitAreEnforced(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ItemStack leather = stack("backpack");
        IItemHandlerModifiable one = BackpackSupport.upgrades(leather);
        check(one.getSlots() == 1, "the leather pack has " + one.getSlots() + " upgrade slots; the policy gives it one");
        check(one.insertItem(0, stack("magnet_upgrade"), false).isEmpty(), "the leather pack's one slot refused a magnet upgrade");
        ItemStack iron = stack("iron_backpack");
        IItemHandlerModifiable two = BackpackSupport.upgrades(iron);
        check(two.getSlots() == 2, "the iron pack has " + two.getSlots() + " upgrade slots; the policy gives it two");
        check(two.insertItem(0, stack("stack_upgrade_starter_tier"), false).isEmpty(), "a first stack upgrade should fit");
        // the mod's own placement rule (the one its upgrade slot applies when a player moves an upgrade in): counts per pack from maxUpgradesPerStorage
        Object wrapper = BackpackSupport.wrapper(iron);
        ItemStack second = stack("stack_upgrade_starter_tier");
        check(!(Boolean) callOn(callOn(second.getItem(), "canAddUpgradeTo", wrapper, second, true, false), "isSuccessful"), "a second stack upgrade is accepted: the policy allows one per pack (maxUpgradesPerStorage)");
        ItemStack feeding = stack("feeding_upgrade");
        check((Boolean) callOn(callOn(feeding.getItem(), "canAddUpgradeTo", wrapper, feeding, true, false), "isSuccessful"), "a different upgrade should still be accepted for the second slot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deathDropsTheWornPackAndPickingItUpGivesTheSameContentsBack(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = player(helper, "uc_bp_dead", "smith"), finder = player(helper, "uc_bp_finder", "gunsmith");
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        victim.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        finder.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        ItemStack pack = stack("copper_backpack");
        BackpackSupport.wear(victim, pack);
        check(BackpackSupport.insertAll(BackpackSupport.inventory(pack), new ItemStack(Items.GOLD_INGOT, 9)), "the ingots did not fit");
        UUID contents = BackpackSupport.contentsUuid(pack).orElseThrow();
        victim.getInventory().dropAll();   // what death does with a player's items when keepInventory is off
        check(!BackpackSupport.worn(victim), "the pack should have left the player at death");
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4), e -> e.getItem().getItem() == pack.getItem());
        check(drops.size() == 1, "expected the pack as one dropped item, found " + drops.size());
        ItemEntity drop = drops.get(0);
        drop.setNoPickUpDelay();
        drop.playerTouch(finder);
        ItemStack found = finder.getInventory().items.stream().filter(s -> s.getItem() == pack.getItem()).findFirst().orElse(ItemStack.EMPTY);
        check(!found.isEmpty() && drop.isRemoved(), "the finder could not pick the dropped pack up");
        check(BackpackSupport.contentsUuid(found).orElseThrow().equals(contents) && BackpackSupport.count(BackpackSupport.inventory(found), Items.GOLD_INGOT) == 9, "the picked-up pack lost or changed its contents");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ placing and breaking

    private static BlockHitResult click(BlockPos against, Direction face) { return new BlockHitResult(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, against, false); }

    /** Sneak and right-click: the mod's own way to place a pack (a plain right-click on a block does nothing, and on a container it deposits or restocks). */
    private static InteractionResult use(ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        boolean sneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(true);
        try {
            return player.gameMode.useItemOn(player, player.serverLevel(), stack, InteractionHand.MAIN_HAND, click(against, face));
        } finally {
            player.setShiftKeyDown(sneaking);
        }
    }

    private static Object wrapperOf(ServerLevel level, BlockPos pos) throws Exception {
        BlockEntity entity = level.getBlockEntity(pos);
        check(entity != null && entity.getClass().getName().equals(BLOCK_ENTITY), "no backpack block entity at " + pos);
        return callOn(entity, "getBackpackWrapper");
    }

    @GameTest(template = "empty")
    public static void aPlacedPackKeepsItsContentsAndComesBackWhenBroken(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, "uc_bp_place", "farmer");
        BlockPos floor = helper.absolutePos(new BlockPos(3, 1, 3)), spot = floor.above();
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(spot, Blocks.AIR.defaultBlockState());
        p.moveTo(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 3.5);
        p.setGameMode(GameType.SURVIVAL);
        ItemStack pack = stack("gold_backpack");
        Item packItem = pack.getItem();   // placing uses the stack up, and an empty stack reports Items.AIR
        check(BackpackSupport.insertAll(BackpackSupport.inventory(pack), new ItemStack(Items.EMERALD, 5)), "the emeralds did not fit");
        UUID contents = BackpackSupport.contentsUuid(pack).orElseThrow();
        InteractionResult placed = use(p, pack, floor, Direction.UP);
        check(placed.consumesAction() && level.getBlockState(spot).getBlock().getClass().getName().endsWith("BackpackBlock"), "the pack was not placed: " + placed + " " + level.getBlockState(spot));
        Object wrapper = wrapperOf(level, spot);
        check(((Optional<?>) callOn(wrapper, "getContentsUuid")).orElse(null) != null && ((Optional<UUID>) callOn(wrapper, "getContentsUuid")).get().equals(contents), "the placed pack lost its contents id");
        check(BackpackSupport.count((IItemHandlerModifiable) callOn(wrapper, "getInventoryHandler"), Items.EMERALD) == 5, "the placed pack lost its items");
        // break it as the player would: the drop is the pack with the same contents
        check(p.gameMode.destroyBlock(spot), "the player could not break their own placed pack");
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(3), e -> e.getItem().getItem() == packItem);
        check(drops.size() == 1, "expected the pack as one dropped item, found " + drops.size() + " (tile drops " + level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS)
            + ", block now " + level.getBlockState(spot) + ", items around " + level.getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(8)).stream().map(e -> e.getItem().toString()).toList()
            + ", the player holds " + p.getInventory().items.stream().filter(s -> !s.isEmpty()).map(ItemStack::toString).toList() + ")");
        ItemStack back = drops.get(0).getItem();
        check(BackpackSupport.contentsUuid(back).orElseThrow().equals(contents) && BackpackSupport.count(BackpackSupport.inventory(back), Items.EMERALD) == 5, "the broken pack lost or changed its contents");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ a placed pack in somebody's home

    private static PlayerInteractEvent.RightClickBlock used(ServerPlayer player, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, click(pos, Direction.UP));
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    private static BlockEvent.BreakEvent brokenBy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    /** Places a pack the way a player does (the member, who may use storage) and returns where it stands. */
    private static BlockPos placeInHome(ResidenceSecurityGameTests.Home h, ServerPlayer by, ItemStack pack, int x, int z) {
        BlockPos spot = h.in(x, 0, z);
        h.level.setBlockAndUpdate(spot, Blocks.AIR.defaultBlockState());
        use(by, pack, h.in(x, -1, z), Direction.UP);
        check(h.level.getBlockState(spot).getBlock().getClass().getName().endsWith("BackpackBlock"), by.getGameProfile().getName() + " could not place a backpack in the home");
        return spot;
    }

    @GameTest(template = "empty")
    public static void aPlacedPackInACrewHomeFollowsTheStorageRules(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        try (ResidenceSecurityGameTests.Home h = new ResidenceSecurityGameTests.Home(helper)) {
            ServerLevel level = h.level;
            ItemStack pack = stack("iron_backpack");
            BackpackSupport.insertAll(BackpackSupport.inventory(pack), new ItemStack(Items.DIAMOND, 3));
            BlockPos spot = placeInHome(h, h.member, pack, 2, 2);
            check(ResidenceGuard.isContainer(level.getBlockEntity(spot)), "a placed backpack is not recognised as a container: the guard would treat it as decoration");
            // opening it
            check(!used(h.member, spot).isCanceled(), "a member could not open the Crew's pack in their own home");
            check(used(h.outsider, spot).isCanceled(), "an outsider opened a pack in a Crew home");
            check(used(h.builder, spot).isCanceled(), "a builder without the storage right opened the Crew's pack");
            // breaking it (which would drop everything in it)
            check(brokenBy(level, h.outsider, spot).isCanceled() && brokenBy(level, h.builder, spot).isCanceled(), "somebody without the rights broke the Crew's pack and so got its contents");
            check(!brokenBy(level, h.member, spot).isCanceled(), "a member could not break the pack in their own home");
            // a private pack: only its claimant, however much the others may do elsewhere in the home
            h.ledger.claimContainer(h.member.getUUID(), "test:sec", spot);
            check(used(h.founder, spot).isCanceled() && brokenBy(level, h.founder, spot).isCanceled(), "the founder opened or broke a member's private pack");
            check(!used(h.member, spot).isCanceled() && !brokenBy(level, h.member, spot).isCanceled(), "the claimant could not use their own private pack");
            h.ledger.releaseClaim(h.member.getUUID(), "test:sec", spot);
        }
        helper.succeed();
    }

    /** The mod lets a player pick a placed pack up with a sneaking, empty-handed right-click; in a Crew home that is taking the Crew's storage away with everything in it. */
    @GameTest(template = "empty")
    public static void aSneakingOutsiderCannotPickUpTheCrewsPlacedPackButAMemberCan(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        try (ResidenceSecurityGameTests.Home h = new ResidenceSecurityGameTests.Home(helper)) {
            ServerLevel level = h.level;
            ItemStack pack = stack("iron_backpack");
            Item packItem = pack.getItem();
            BackpackSupport.insertAll(BackpackSupport.inventory(pack), new ItemStack(Items.DIAMOND, 4));
            UUID contents = BackpackSupport.contentsUuid(pack).orElseThrow();
            BlockPos spot = placeInHome(h, h.member, pack, 2, 2);
            for (ServerPlayer denied : List.of(h.outsider, h.builder)) {
                use(denied, ItemStack.EMPTY, spot, Direction.UP);
                check(level.getBlockState(spot).getBlock().getClass().getName().endsWith("BackpackBlock"), denied.getGameProfile().getName() + " picked up the Crew's placed pack with a sneaking right-click");
                check(denied.getMainHandItem().isEmpty(), denied.getGameProfile().getName() + " ended up holding something");
            }
            // control: the same gesture by the member takes the pack into their hand, contents intact
            use(h.member, ItemStack.EMPTY, spot, Direction.UP);
            check(!level.getBlockState(spot).getBlock().getClass().getName().endsWith("BackpackBlock"), "the member could not pick up the pack in their own home (the control for the refusals is void): " + level.getBlockState(spot));
            ItemStack held = h.member.getMainHandItem();
            check(held.getItem() == packItem && BackpackSupport.contentsUuid(held).orElseThrow().equals(contents) && BackpackSupport.count(BackpackSupport.inventory(held), Items.DIAMOND) == 4, "the picked-up pack lost its contents: " + held);
        }
        helper.succeed();
    }

    /** Two players carry two packs: what one puts in the other cannot see, and a copy of a stack is noticed by the mod rather than being a second set of contents. */
    @GameTest(template = "empty")
    public static void twoPlayersPacksHoldTheirOwnContentsAndDuplicationIsSwitchedOff(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerPlayer a = player(helper, "uc_bp_a", "farmer"), b = player(helper, "uc_bp_b", "smith");
        ItemStack packA = stack("backpack"), packB = stack("backpack");
        BackpackSupport.wear(a, packA);
        BackpackSupport.wear(b, packB);
        check(BackpackSupport.insertAll(BackpackSupport.inventory(packA), new ItemStack(Items.DIAMOND, 5)), "A's diamonds did not fit");
        check(BackpackSupport.insertAll(BackpackSupport.inventory(packB), new ItemStack(Items.COAL, 9)), "B's coal did not fit");
        check(BackpackSupport.count(BackpackSupport.inventory(packA), Items.COAL) == 0 && BackpackSupport.count(BackpackSupport.inventory(packB), Items.DIAMOND) == 0, "one player's pack shows the other's items");
        check(!BackpackSupport.contentsUuid(packA).equals(BackpackSupport.contentsUuid(packB)), "two packs share one contents id");
        // the mod's own switches that keep a copied stack from becoming a second set of contents, and a pack from being opened by someone else
        List<String> drift = BackpackPolicy.audit(policy());
        check(drift.stream().noneMatch(line -> line.contains("tickDedupeLogicDisabled") || line.contains("allowOpeningOtherPlayerBackpacks")), "the duplication or other-player safeguards are off: " + drift);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hoppersCannotBeBuiltToEmptyAPackThatIsNotTheirBuildersToOpen(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        try (ResidenceSecurityGameTests.Home h = new ResidenceSecurityGameTests.Home(helper)) {
            ServerLevel level = h.level;
            BlockPos pack = placeInHome(h, h.member, stack("backpack"), 2, 2);
            BlockPos below = h.in(2, -1, 2), beside = h.in(3, 0, 2);
            level.setBlockAndUpdate(beside, Blocks.AIR.defaultBlockState());
            // a hopper beside the pack (it takes from every side of a pack): the builder, who has no storage right, cannot place one; the member can
            use(h.builder, new ItemStack(Items.HOPPER), h.in(3, -1, 2), Direction.UP);
            check(level.getBlockState(beside).isAir(), "a builder without the storage right placed a hopper beside the Crew's pack");
            use(h.member, new ItemStack(Items.HOPPER), h.in(3, -1, 2), Direction.UP);
            check(level.getBlockState(beside).is(Blocks.HOPPER), "a member who may use storage could not place a hopper beside the Crew's pack");
            level.setBlockAndUpdate(beside, Blocks.AIR.defaultBlockState());
            // and the other way round: a pack placed beside an existing hopper by somebody without the storage right
            level.setBlockAndUpdate(pack, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(h.in(1, 0, 2), Blocks.HOPPER.defaultBlockState());
            use(h.builder, stack("backpack"), h.in(2, -1, 2), Direction.UP);
            check(!level.getBlockState(pack).getBlock().getClass().getName().endsWith("BackpackBlock"), "a builder without the storage right placed a pack beside a hopper");
            check(below != null, "unreachable");
        }
        helper.succeed();
    }

    /**
     * Deposit and Restock act on whatever container the pack is pointed at, through a packet the client fills in (see {@link BackpackGate}). This calls the mod's own server
     * method with a pack that has a Deposit upgrade, the way the packet handler does, at a Crew's chest.
     */
    @GameTest(template = "empty")
    public static void theInventoryInteractionPacketCannotReachIntoAChestTheWearerMayNotOpen(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        try (ResidenceSecurityGameTests.Home h = new ResidenceSecurityGameTests.Home(helper)) {
            ServerLevel level = h.level;
            BlockPos chest = h.in(2, 0, 2);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            Class<?> helperClass = cls(BackpackSupport.ITEM, HELPER);
            // the pack of somebody without the storage right: 16 cobblestone and a Deposit upgrade
            ItemStack pack = stack("iron_backpack");
            BackpackSupport.insertAll(BackpackSupport.inventory(pack), new ItemStack(Items.COBBLESTONE, 16));
            check(BackpackSupport.upgrades(pack).insertItem(0, stack("deposit_upgrade"), false).isEmpty(), "the deposit upgrade did not fit");
            for (ServerPlayer denied : List.of(h.outsider, h.builder)) {
                boolean done = (Boolean) call(helperClass, "tryInventoryInteraction", chest, level, pack, Direction.UP, denied);
                check(!done, denied.getGameProfile().getName() + " could deposit into the Crew's chest through the backpack packet path");
                check(containerCount(level, chest, Items.COBBLESTONE) == 0, "the chest received items from somebody without the storage right");
            }
            // control: the member, who may use the storage, deposits through exactly the same call (so the refusals above come from the gate, not from the call being a no-op)
            boolean done = (Boolean) call(helperClass, "tryInventoryInteraction", chest, level, pack, Direction.UP, h.member);
            check(done && containerCount(level, chest, Items.COBBLESTONE) > 0, "the member's deposit did nothing (done=" + done + "): the control for the refusals is void");
            // far away is refused for everybody: the packet carries any position
            BlockPos far = chest.offset(40, 0, 0);
            net.minecraft.world.level.block.state.BlockState before = level.getBlockState(far);   // the neighbouring tests' blocks are not ours to leave changed
            level.setBlockAndUpdate(far, Blocks.CHEST.defaultBlockState());
            try {
                check(!(Boolean) call(helperClass, "tryInventoryInteraction", far, level, pack, Direction.UP, h.member), "a pack deposited into a chest 40 blocks away");
            } finally {
                level.setBlockAndUpdate(far, before);
            }
        }
        helper.succeed();
    }

    private static int containerCount(ServerLevel level, BlockPos pos, Item item) {
        net.minecraft.world.Container container = (net.minecraft.world.Container) level.getBlockEntity(pos);
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        return total;
    }

    /** A placed pack with the Feeding upgrade fed everyone within three blocks. In a Crew home that is an outsider eating the Crew's stored bread. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "uc_backpack_feeding")
    public static void aPlacedPackDoesNotFeedBystandersButAWornOneFeedsItsWearer(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ResidenceSecurityGameTests.Home h = new ResidenceSecurityGameTests.Home(helper);
        ServerLevel level = h.level;
        ItemStack placed = stack("gold_backpack");
        BackpackSupport.insertAll(BackpackSupport.inventory(placed), new ItemStack(Items.BREAD, 16));
        check(BackpackSupport.upgrades(placed).insertItem(0, stack("feeding_upgrade"), false).isEmpty(), "the feeding upgrade did not fit");
        check(BackpackSupport.upgrades(placed).insertItem(1, stack("magnet_upgrade"), false).isEmpty(), "the magnet upgrade did not fit");
        BlockPos spot = placeInHome(h, h.member, placed, 2, 2);
        BlockEntity entity = level.getBlockEntity(spot);
        // a bystander the world can find: scripted fake players are not in the level's entity lists, which the mod searches, so this one is a fake player that is also added to the level
        ServerPlayer bystander = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "uc_bystander"));
        level.addNewPlayer(bystander);
        bystander.moveTo(spot.getX() + 1.5, spot.getY(), spot.getZ() + 0.5);
        bystander.getFoodData().setFoodLevel(4);
        // the control that the placed pack's upgrades do run: its magnet takes a stone dropped beside it
        ItemEntity stone = new ItemEntity(level, spot.getX() + 0.5, spot.getY() + 0.2, spot.getZ() + 1.5, new ItemStack(Items.COBBLESTONE, 3));
        level.addFreshEntity(stone);
        // a worn pack feeds its wearer: the same upgrade, the same bread
        ServerPlayer wearer = h.founder;
        ItemStack worn = stack("gold_backpack");
        BackpackSupport.insertAll(BackpackSupport.inventory(worn), new ItemStack(Items.BREAD, 16));
        BackpackSupport.upgrades(worn).insertItem(0, stack("feeding_upgrade"), false);
        BackpackSupport.wear(wearer, worn);
        wearer.getFoodData().setFoodLevel(4);
        Class<?> blockEntityClass = cls(BackpackSupport.ITEM, BLOCK_ENTITY);
        helper.onEachTick(() -> {
            try {
                call(blockEntityClass, "serverTick", level, spot, entity);
                callOn(worn.getItem(), "onArmorTick", worn, level, wearer);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        helper.runAfterDelay(150, () -> {
            try {
                check(stone.isRemoved() || stone.getItem().getCount() < 3, "the placed pack's magnet did not take the stone: placed packs do not run their upgrades in this test, so it proves nothing about feeding");
                check(bystander.getFoodData().getFoodLevel() == 4, "the Crew's placed pack fed a bystander: food level " + bystander.getFoodData().getFoodLevel());
                check(wearer.getFoodData().getFoodLevel() > 4, "a worn pack's Feeding upgrade did not feed its wearer (food level " + wearer.getFoodData().getFoodLevel() + "): the control for the refusal is void");
                helper.succeed();
            } catch (Exception | AssertionError e) {
                helper.fail(String.valueOf(e.getMessage()));
            } finally {
                level.removePlayerImmediately(bystander, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                try { h.close(); } catch (Exception ignored) { /* the first failure is the one that matters */ }
            }
        });
    }
}
