package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The practical ways round a home's guard (D-090): pistons, hoppers, containers of other mods, item frames, armor stands, chest vehicles, placing
 * entities, fluids, fire, mob griefing, trampling, hostile spawns and explosions. These tests use the game's own paths where they can
 * ({@code ServerPlayerGameMode.useItemOn}, {@code Player.attack}, {@code Player.interactOn}, a real piston's {@code triggerEvent}) so a Forge event
 * that does not fire where we expect fails them, and each one pairs the refusal with the control that proves the action works for someone entitled
 * (or outside every home), so a test cannot pass merely because nothing happens.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidenceSecurityGameTests {
    private static final String OVERWORLD = "minecraft:overworld";

    /** A Crew home with a founder, a default member, a builder who may not use shared storage, and an outsider. Interior x/z 0..4, y 0..3 from {@link #base}. */
    static final class Home implements AutoCloseable {
        final CrewGameTests.Rig rig;
        final CrewLedger ledger;
        final ServerLevel level;
        final ServerPlayer founder, member, builder, outsider;
        final BlockPos base;
        final Residence home;

        Home(GameTestHelper helper) throws Exception {
            rig = new CrewGameTests.Rig(helper);
            ledger = rig.ledger;
            level = helper.getLevel();
            founder = player(helper, "uc_sf" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff), "farmer");
            member = player(helper, "uc_sm" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff), "smith");
            builder = player(helper, "uc_sb" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff), "gunsmith");
            outsider = player(helper, "uc_so" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff), "technician");
            base = helper.absolutePos(new BlockPos(0, 2, 0));
            for (ServerPlayer p : List.of(founder, member, builder, outsider)) {
                p.moveTo(base.getX() + 2.5, base.getY() + 1, base.getZ() + 2.5);
                p.setGameMode(GameType.SURVIVAL);
            }
            home = ledger.registerResidence("test:sec", "Secure flat", "flat", OVERWORLD, "test", base, base.offset(4, 3, 4), 10, -1);
            String f = founder.getGameProfile().getName();
            ledger.create(founder.getUUID(), f, "Secure Crew");
            for (ServerPlayer p : List.of(member, builder)) ledger.accept(ledger.invite(founder.getUUID(), p.getUUID(), p.getGameProfile().getName(), f).id(), p.getUUID(), p.getGameProfile().getName());
            // the builder is a manager who may build and enter but not use shared storage
            ledger.setRole(founder.getUUID(), f, builder.getUUID(), Role.MANAGER);
            ledger.setPermission(founder.getUUID(), f, Role.MANAGER, Perm.STORAGE, false);
            rig.wallet.balances.put(founder.getUUID(), 100d);
            CrewService.lease(founder, id(), "test:sec", true, false);
            check(home.owned(), "the home must be leased for these tests");
            // the walls and floor of the shell that the tests click against
            for (int x = -1; x <= 5; x++) for (int z = -1; z <= 5; z++) level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int x = 0; x <= 4; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 4; z++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }

        BlockPos in(int x, int y, int z) { return base.offset(x, y, z); }

        @Override public void close() throws Exception { rig.close(); }
    }

    private static BlockHitResult click(BlockPos against, Direction face) { return new BlockHitResult(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, against, false); }

    /** The game's own path for "player uses the item in hand on a block": Forge's right-click event, then the item. Returns the place the item would go. */
    private static InteractionResult use(ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItemOn(player, player.serverLevel(), stack, InteractionHand.MAIN_HAND, click(against, face));
    }

    // ------------------------------------------------------------------------------------------------------------ pistons

    /**
     * Four powered pistons set up side by side and left to run the way the game runs them: placing a powered piston queues its extension, and the game
     * fires Forge's piston event as it carries that out. (A piston that is not powered does nothing, which would make every "refused" case pass without
     * testing the guard, so each refusal has a control row that does move.)
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "uc_guard_pistons")   // its own batch: the rig it holds open across ticks swaps global ledger and wallet state
    public static void pistonsCannotMoveBlocksAcrossAHomesBoundary(GameTestHelper helper) throws Exception {
        Home h = new Home(helper);
        ServerLevel level = h.level;
        for (int x = -12; x <= -1; x++) level.setBlockAndUpdate(h.in(x, 1, 2), Blocks.AIR.defaultBlockState());   // the rows this test uses, whatever the world put there
        for (int x = 5; x <= 6; x++) level.setBlockAndUpdate(h.in(x, 1, 3), Blocks.AIR.defaultBlockState());
        // A. outside, pushing toward the wall: the dirt and the wall block would both move (the wall block into the interior)
        level.setBlockAndUpdate(h.in(-2, 1, 2), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(h.in(-1, 1, 2), Blocks.STONE_BRICKS.defaultBlockState());   // the shell
        power(level, h.in(-3, 1, 2), Direction.EAST);
        // B. control: the same piston pushing a block away from the home
        level.setBlockAndUpdate(h.in(-10, 1, 2), Blocks.DIRT.defaultBlockState());
        power(level, h.in(-9, 1, 2), Direction.WEST);
        // C. inside the home, pushing a tenant's block within the home
        level.setBlockAndUpdate(h.in(1, 1, 0), Blocks.DIRT.defaultBlockState());
        power(level, h.in(0, 1, 0), Direction.EAST);
        // D. inside the home, pushing a row whose last block would enter the shell
        for (int x = 2; x <= 4; x++) level.setBlockAndUpdate(h.in(x, 1, 3), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(h.in(5, 1, 3), Blocks.STONE_BRICKS.defaultBlockState());   // the city's wall here
        BlockState wallBefore = level.getBlockState(h.in(5, 1, 3));
        power(level, h.in(1, 1, 3), Direction.EAST);
        helper.runAfterDelay(20, () -> {
            try {
                check(level.getBlockState(h.in(-3, 1, 2)).is(Blocks.PISTON) && !level.getBlockState(h.in(-3, 1, 2)).getValue(PistonBaseBlock.EXTENDED), "A: an outside piston extended against a home");
                check(level.getBlockState(h.in(-2, 1, 2)).is(Blocks.DIRT) && level.getBlockState(h.in(-1, 1, 2)).is(Blocks.STONE_BRICKS) && level.getBlockState(h.in(0, 1, 2)).isAir(),
                    "A: an outside piston moved blocks of a home's wall: " + level.getBlockState(h.in(-1, 1, 2)) + " / " + level.getBlockState(h.in(0, 1, 2)));
                check(level.getBlockState(h.in(-9, 1, 2)).getValue(PistonBaseBlock.EXTENDED) && level.getBlockState(h.in(-11, 1, 2)).is(Blocks.DIRT),
                    "B (control): a piston away from the home did not push its block, so the refusals prove nothing: " + level.getBlockState(h.in(-9, 1, 2)) + " / " + level.getBlockState(h.in(-11, 1, 2)));
                check(level.getBlockState(h.in(0, 1, 0)).getValue(PistonBaseBlock.EXTENDED) && level.getBlockState(h.in(2, 1, 0)).is(Blocks.DIRT),
                    "C: a piston inside the home could not move its tenant's own block: " + level.getBlockState(h.in(2, 1, 0)));
                check(!level.getBlockState(h.in(1, 1, 3)).getValue(PistonBaseBlock.EXTENDED) && level.getBlockState(h.in(4, 1, 3)).is(Blocks.DIRT) && level.getBlockState(h.in(5, 1, 3)) == wallBefore,
                    "D: a piston inside pushed a row of blocks out into the city's wall: " + level.getBlockState(h.in(5, 1, 3)));
                helper.succeed();
            } catch (Throwable failure) {
                helper.fail(String.valueOf(failure.getMessage())); // an AssertionError thrown from a delayed callback would otherwise take the whole test server down
            } finally {
                try { h.close(); } catch (Exception ignored) { /* scratch rig */ }
            }
        });
    }

    /** A redstone block behind the piston, then the piston: the game queues the extension itself. */
    private static void power(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlockAndUpdate(pos.relative(facing.getOpposite()), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, facing));
    }

    // ------------------------------------------------------------------------------------------------------------ hoppers and containers

    @GameTest(template = "empty")
    public static void aHopperCannotBePlacedToEmptyStorageItsPlacerMayNotOpen(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            BlockPos chest = h.in(2, 1, 2), below = h.in(2, 0, 2), floor = h.in(2, -1, 2);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            // the builder may build here but not use shared storage: the hopper under the chest is refused
            use(h.builder, new ItemStack(Items.HOPPER), floor, Direction.UP);
            check(level.getBlockState(below).isAir(), "a builder without the storage right placed a hopper under a shared chest");
            // a member who may use shared storage can
            use(h.member, new ItemStack(Items.HOPPER), floor, Direction.UP);
            check(level.getBlockState(below).is(Blocks.HOPPER), "a member who may use storage could not place a hopper under a shared chest");
            level.setBlockAndUpdate(below, Blocks.AIR.defaultBlockState());
            // a private chest: only its claimant may put a hopper beside it, not even someone with the storage right
            h.ledger.claimContainer(h.builder.getUUID(), "test:sec", chest);
            use(h.member, new ItemStack(Items.HOPPER), floor, Direction.UP);
            check(level.getBlockState(below).isAir(), "a member put a hopper under somebody else's private chest");
            use(h.builder, new ItemStack(Items.HOPPER), floor, Direction.UP);
            check(level.getBlockState(below).is(Blocks.HOPPER), "the claimant could not put a hopper under their own private chest");
            level.setBlockAndUpdate(below, Blocks.AIR.defaultBlockState());
            h.ledger.releaseClaim(h.builder.getUUID(), "test:sec", chest);
            // the other way round: a chest placed beside an existing hopper by someone without the storage right
            level.setBlockAndUpdate(h.in(0, 0, 0), Blocks.HOPPER.defaultBlockState());
            BlockPos beside = h.in(1, 0, 0);
            use(h.builder, new ItemStack(Items.CHEST), h.in(1, -1, 0), Direction.UP);
            check(!level.getBlockState(beside).is(Blocks.CHEST), "a builder without the storage right put a chest beside a hopper");
            use(h.member, new ItemStack(Items.CHEST), h.in(1, -1, 0), Direction.UP);
            check(level.getBlockState(beside).is(Blocks.CHEST), "a member who may use storage could not put a chest beside a hopper");
            // a hopper that is not beside any container is harmless and anyone who may build can place it
            use(h.builder, new ItemStack(Items.HOPPER), h.in(4, -1, 4), Direction.UP);
            check(level.getBlockState(h.in(4, 0, 4)).is(Blocks.HOPPER), "a builder could not place a hopper that touches no container");
        }
        helper.succeed();
    }

    /** The control for the test above: a hopper under a chest really does empty it, so keeping hoppers out of the way is what keeps the contents safe. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void controlAHopperUnderAChestDrainsItOutsideEveryHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(1, 3, 1)), hopper = chest.below();
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState());
        ((net.minecraft.world.Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        helper.runAfterDelay(60, () -> {
            HopperBlockEntity entity = (HopperBlockEntity) level.getBlockEntity(hopper);
            if (entity != null && entity.countItem(Items.DIAMOND) > 0) helper.succeed();
            else helper.fail("a hopper under a chest did not drain it in 60 ticks: the hopper tests prove nothing");
        });
    }

    @GameTest(template = "empty")
    public static void breakingAContainerNeedsTheStorageRightAsWellAsTheBuildRight(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            BlockPos chest = h.in(1, 0, 1);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            check(brokenBy(level, h.builder, chest).isCanceled(), "a builder without the storage right broke a shared chest and got its contents");
            check(!brokenBy(level, h.member, chest).isCanceled(), "a member could not break the shared chest of their own home");
            level.setBlockAndUpdate(h.in(3, 0, 3), Blocks.DIRT.defaultBlockState());
            check(!brokenBy(level, h.builder, h.in(3, 0, 3)).isCanceled(), "a builder could not break an ordinary block");
            // a private chest: only the claimant, even with the same rights as everyone
            h.ledger.claimContainer(h.member.getUUID(), "test:sec", chest);
            check(brokenBy(level, h.founder, chest).isCanceled() && !brokenBy(level, h.member, chest).isCanceled(), "a private chest could be broken by someone else");
        }
        helper.succeed();
    }

    private static BlockEvent.BreakEvent brokenBy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    @GameTest(template = "empty")
    public static void containersAreRecognisedByTheirItemHandlerNotOnlyByTheVanillaInterface(GameTestHelper helper) throws Exception {
        check(!ResidenceGuard.isContainer(null), "no block entity is no container");
        // every vanilla storage
        try (Home h = new Home(helper)) {
            for (Block block : List.of(Blocks.CHEST, Blocks.BARREL, Blocks.TRAPPED_CHEST, Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.FURNACE, Blocks.CHISELED_BOOKSHELF)) {
                h.level.setBlockAndUpdate(h.in(1, 0, 1), block.defaultBlockState());
                check(ResidenceGuard.isContainer(h.level.getBlockEntity(h.in(1, 0, 1))), block + " is not recognised as a container");
            }
            // things that are not storage stay plain interactions
            for (Block block : List.of(Blocks.OAK_SIGN, Blocks.BEACON, Blocks.BELL)) {
                h.level.setBlockAndUpdate(h.in(1, 0, 1), block.defaultBlockState());
                check(!ResidenceGuard.isContainer(h.level.getBlockEntity(h.in(1, 0, 1))), block + " was taken for a container");
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ item frames, armor stands, chest vehicles

    @GameTest(template = "empty")
    public static void framesStandsPaintingsAndChestVehiclesInAHomeAreProtected(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            // a frame with something in it, hanging on the north wall inside the home
            BlockPos at = h.in(2, 1, 0);
            level.setBlockAndUpdate(h.in(2, 1, -1), Blocks.STONE_BRICKS.defaultBlockState());
            ItemFrame frame = new ItemFrame(level, at, Direction.SOUTH);
            frame.setItem(new ItemStack(Items.EMERALD, 3), false);
            level.addFreshEntity(frame);
            ArmorStand stand = EntityType.ARMOR_STAND.create(level);
            stand.moveTo(h.in(3, 0, 3).getX() + 0.5, h.in(3, 0, 3).getY(), h.in(3, 0, 3).getZ() + 0.5);
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            level.addFreshEntity(stand);
            MinecartChest cart = EntityType.CHEST_MINECART.create(level);
            cart.moveTo(h.in(1, 0, 3).getX() + 0.5, h.in(1, 0, 3).getY(), h.in(1, 0, 3).getZ() + 0.5);
            cart.setItem(0, new ItemStack(Items.GOLD_INGOT, 9));
            level.addFreshEntity(cart);

            // the outsider can neither take from, turn, undress nor break any of them
            check(h.outsider.interactOn(frame, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "an outsider turned or emptied a frame in a home");
            check(h.outsider.interactOn(cart, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "an outsider opened a chest vehicle in a home");
            check(ForgeHooks.onInteractEntityAt(h.outsider, stand, Vec3.ZERO, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "an outsider dressed or undressed an armor stand in a home");
            h.outsider.attack(frame); h.outsider.attack(stand); h.outsider.attack(cart);
            check(frame.isAlive() && !frame.getItem().isEmpty() && stand.isAlive() && !stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() && cart.isAlive() && cart.getItem(0).getCount() == 9,
                "an outsider broke or emptied something in a home");
            // the builder (may build, may not use storage): cannot reach what is in them, even by breaking them
            check(h.builder.interactOn(frame, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "a builder without the storage right emptied a frame");
            h.builder.attack(frame); h.builder.attack(cart);
            check(frame.isAlive() && !frame.getItem().isEmpty() && cart.isAlive() && cart.getItem(0).getCount() == 9, "a builder without the storage right broke a frame or a chest vehicle that holds things");
            // a member entitled to the household's things can use them and break them
            check(h.member.interactOn(frame, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "a member could not use a frame in their own home");
            check(ForgeHooks.onInteractEntityAt(h.member, stand, Vec3.ZERO, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "a member could not use an armor stand in their own home");
            h.member.attack(frame);                        // the first blow knocks the framed item out
            check(frame.getItem().isEmpty(), "a member could not take the item out of a frame in their own home");
            h.member.attack(frame);                        // the second takes the frame down
            check(!frame.isAlive(), "a member could not take down a frame in their own home");

            // placing: an outsider cannot hang a frame or stand an armor stand inside; a member can
            BlockPos wall = h.in(4, 1, 2);
            level.setBlockAndUpdate(h.in(5, 1, 2), Blocks.STONE_BRICKS.defaultBlockState());
            use(h.outsider, new ItemStack(Items.ITEM_FRAME), h.in(5, 1, 2), Direction.WEST);
            check(level.getEntitiesOfClass(ItemFrame.class, new net.minecraft.world.phys.AABB(wall).inflate(0.4)).isEmpty(), "an outsider hung a frame inside a home");
            use(h.member, new ItemStack(Items.ITEM_FRAME), h.in(5, 1, 2), Direction.WEST);
            check(!level.getEntitiesOfClass(ItemFrame.class, new net.minecraft.world.phys.AABB(wall).inflate(0.4)).isEmpty(), "a member could not hang a frame in their own home");
            int stands = level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(h.base).inflate(6)).size();
            use(h.outsider, new ItemStack(Items.ARMOR_STAND), h.in(1, -1, 1), Direction.UP);
            check(level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(h.base).inflate(6)).size() == stands, "an outsider stood an armor stand in a home");

            // arrows and explosions: neither the outsider's arrow nor a mob's does what its owner could not
            Arrow arrow = EntityType.ARROW.create(level);
            arrow.setOwner(h.outsider);
            ProjectileImpactEvent impact = new ProjectileImpactEvent(arrow, new EntityHitResult(stand));
            MinecraftForge.EVENT_BUS.post(impact);
            check(impact.getImpactResult() == ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE, "an outsider's arrow could hurt an armor stand in a home");
            Arrow ownerless = EntityType.ARROW.create(level);
            ProjectileImpactEvent mobShot = new ProjectileImpactEvent(ownerless, new EntityHitResult(stand));
            MinecraftForge.EVENT_BUS.post(mobShot);
            check(mobShot.getImpactResult() == ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE, "a skeleton's arrow could hurt an armor stand in a home");
            Arrow membersArrow = EntityType.ARROW.create(level);
            membersArrow.setOwner(h.member);
            ProjectileImpactEvent ok = new ProjectileImpactEvent(membersArrow, new EntityHitResult(stand));
            MinecraftForge.EVENT_BUS.post(ok);
            check(ok.getImpactResult() == ProjectileImpactEvent.ImpactResult.DEFAULT, "a member's arrow was stopped inside their own home");
            Explosion explosion = new Explosion(level, null, h.in(2, 1, 2).getX(), h.in(2, 1, 2).getY(), h.in(2, 1, 2).getZ(), 4f, false, Explosion.BlockInteraction.DESTROY);
            List<net.minecraft.world.entity.Entity> caught = new ArrayList<>(List.of(stand, cart));
            MinecraftForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level, explosion, caught));
            check(caught.isEmpty(), "an explosion was allowed to take an armor stand or a chest vehicle out of a home: " + caught);
        }
        helper.succeed();
    }

    /** The NPC properties meant for burglary are not registered homes: everything that is refused in a home is allowed there. */
    @GameTest(template = "empty")
    public static void anUnregisteredHouseStaysOpenForBurglary(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            BlockPos house = h.base.offset(-14, 0, 2);   // far from the registered home and its shell
            for (int dx = 0; dx <= 4; dx++) for (int dy = 0; dy <= 2; dy++) level.setBlockAndUpdate(house.offset(dx, dy, 0), Blocks.AIR.defaultBlockState());   // the cells this test uses, whatever the world put there
            level.setBlockAndUpdate(house.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(house.below(2), Blocks.STONE.defaultBlockState());
            check(h.ledger.residenceAt(OVERWORLD, house) == null, "the NPC house must not be a registered home");
            ItemFrame frame = new ItemFrame(level, house.above(), Direction.SOUTH);
            frame.setItem(new ItemStack(Items.EMERALD), false);
            level.addFreshEntity(frame);
            MinecartChest cart = EntityType.CHEST_MINECART.create(level);
            cart.moveTo(house.getX() + 3.5, house.getY(), house.getZ() + 0.5);
            level.addFreshEntity(cart);
            check(h.outsider.interactOn(frame, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "a burglar was stopped from using a frame in an NPC house");
            check(h.outsider.interactOn(cart, InteractionHand.MAIN_HAND) != InteractionResult.FAIL, "a burglar was stopped from opening a chest vehicle in an NPC house");
            use(h.outsider, new ItemStack(Items.HOPPER), house.below(), Direction.UP);
            check(level.getBlockState(house).is(Blocks.HOPPER), "a burglar was stopped from placing a hopper outside every home");
            level.setBlockAndUpdate(house.east(), Blocks.CHEST.defaultBlockState());
            check(!brokenBy(level, h.outsider, house.east()).isCanceled(), "a burglar was stopped from breaking into a chest of an NPC house");
            h.outsider.attack(frame);
            check(frame.getItem().isEmpty(), "a burglar was stopped from taking an item out of a frame in an NPC house");
            h.outsider.attack(frame);
            check(!frame.isAlive(), "a burglar was stopped from breaking a frame in an NPC house");
            // and mobs still griefing there is the world's business, not a leased home's
            Zombie zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(house.getX() + 0.5, house.getY(), house.getZ() + 0.5);
            EntityMobGriefingEvent griefing = new EntityMobGriefingEvent(zombie);
            MinecraftForge.EVENT_BUS.post(griefing);
            check(griefing.getResult() == Event.Result.DEFAULT, "mob griefing was changed outside every home");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ fluids, fire, mobs

    @GameTest(template = "empty")
    public static void bucketsFireAndMobsCannotChangeAHomeFromOutsideItsRights(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            BlockPos wood = h.in(2, 0, 2);
            level.setBlockAndUpdate(wood, Blocks.OAK_PLANKS.defaultBlockState());
            // fluids: a bucket is used with the right-click on a block, then the bucket event. An outsider is refused at both; a member is not (the control).
            // (The bucket itself is placed by Item.use, which needs a camera ray a scripted player does not have, so the events are posted as the game posts them.)
            check(usedWith(level, h.outsider, new ItemStack(Items.WATER_BUCKET), h.in(1, -1, 1), Direction.UP).isCanceled(), "an outsider could pour water into a home");
            check(usedWith(level, h.outsider, new ItemStack(Items.LAVA_BUCKET), wood, Direction.UP).isCanceled(), "an outsider could pour lava into a home");
            check(!usedWith(level, h.member, new ItemStack(Items.WATER_BUCKET), h.in(1, -1, 1), Direction.UP).isCanceled(), "control: a member could not pour water in their own home");
            FillBucketEvent byOutsider = new FillBucketEvent(h.outsider, new ItemStack(Items.WATER_BUCKET), level, click(h.in(1, 0, 1), Direction.UP));
            MinecraftForge.EVENT_BUS.post(byOutsider);
            check(byOutsider.isCanceled(), "the bucket event let an outsider empty a bucket in a home");
            FillBucketEvent byMember = new FillBucketEvent(h.member, new ItemStack(Items.WATER_BUCKET), level, click(h.in(1, 0, 1), Direction.UP));
            MinecraftForge.EVENT_BUS.post(byMember);
            check(!byMember.isCanceled(), "control: the bucket event stopped a member in their own home");
            FillBucketEvent outsideAll = new FillBucketEvent(h.outsider, new ItemStack(Items.WATER_BUCKET), level, click(h.base.offset(-12, 0, 0), Direction.UP));
            MinecraftForge.EVENT_BUS.post(outsideAll);
            check(!outsideAll.isCanceled(), "control: a bucket was stopped outside every home");
            // fire: flint and steel
            use(h.outsider, new ItemStack(Items.FLINT_AND_STEEL), wood, Direction.UP);
            check(level.getBlockState(h.in(2, 1, 2)).isAir(), "an outsider lit a fire in a home");
            use(h.member, new ItemStack(Items.FLINT_AND_STEEL), wood, Direction.UP);
            check(level.getBlockState(h.in(2, 1, 2)).is(Blocks.FIRE), "control: a member could not light a fire in their own home");
            level.setBlockAndUpdate(h.in(2, 1, 2), Blocks.AIR.defaultBlockState());

            // mobs inside the home change nothing
            Zombie zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(wood.getX() + 0.5, wood.getY(), wood.getZ() + 0.5);
            EntityMobGriefingEvent griefing = new EntityMobGriefingEvent(zombie);
            MinecraftForge.EVENT_BUS.post(griefing);
            check(griefing.getResult() == Event.Result.DENY, "a mob could grief inside a home");
            LivingDestroyBlockEvent destroy = new LivingDestroyBlockEvent(zombie, wood, level.getBlockState(wood));
            MinecraftForge.EVENT_BUS.post(destroy);
            check(destroy.isCanceled(), "a mob destroyed a block of a home");
            LivingDestroyBlockEvent outside = new LivingDestroyBlockEvent(zombie, h.base.offset(-9, 0, 0), Blocks.STONE.defaultBlockState());
            MinecraftForge.EVENT_BUS.post(outside);
            check(!outside.isCanceled(), "control: a mob's block destruction was stopped outside every home");
            // farmland
            BlockState farmland = Blocks.FARMLAND.defaultBlockState();
            level.setBlockAndUpdate(h.in(3, 0, 3), farmland);
            BlockEvent.FarmlandTrampleEvent byMob = new BlockEvent.FarmlandTrampleEvent(level, h.in(3, 0, 3), farmland, 2f, zombie);
            MinecraftForge.EVENT_BUS.post(byMob);
            check(byMob.isCanceled(), "a mob trampled a tenant's farmland");
            BlockEvent.FarmlandTrampleEvent trampledByOutsider = new BlockEvent.FarmlandTrampleEvent(level, h.in(3, 0, 3), farmland, 2f, h.outsider);
            MinecraftForge.EVENT_BUS.post(trampledByOutsider);
            check(trampledByOutsider.isCanceled(), "an outsider trampled a tenant's farmland");
            BlockEvent.FarmlandTrampleEvent trampledByMember = new BlockEvent.FarmlandTrampleEvent(level, h.in(3, 0, 3), farmland, 2f, h.member);
            MinecraftForge.EVENT_BUS.post(trampledByMember);
            check(!trampledByMember.isCanceled(), "control: a member was not allowed to walk on their own farmland as vanilla does");
            // hostile spawns: the guard's own rule, tested as the base game would run it (the simulation world refuses every ordinary hostile spawn before the guard is asked, D-094)
            SimulationWorld.withProfile(SimulationWorld.Profile.CLASSIC, () -> {
            Zombie spawner = EntityType.ZOMBIE.create(level);
            spawner.moveTo(wood.getX() + 0.5, wood.getY(), wood.getZ() + 0.5);
            MobSpawnEvent.PositionCheck natural = new MobSpawnEvent.PositionCheck(spawner, level, MobSpawnType.NATURAL, null);
            MinecraftForge.EVENT_BUS.post(natural);
            check(natural.getResult() == Event.Result.DENY, "a monster could spawn on its own inside a home");
            MobSpawnEvent.PositionCheck viaSpawner = new MobSpawnEvent.PositionCheck(spawner, level, MobSpawnType.SPAWNER, null);
            MinecraftForge.EVENT_BUS.post(viaSpawner);
            check(viaSpawner.getResult() == Event.Result.DEFAULT, "a monster spawned on purpose (spawner, egg, command) was stopped");
            Pig pig = EntityType.PIG.create(level);
            pig.moveTo(wood.getX() + 0.5, wood.getY(), wood.getZ() + 0.5);
            MobSpawnEvent.PositionCheck animal = new MobSpawnEvent.PositionCheck(pig, level, MobSpawnType.NATURAL, null);
            MinecraftForge.EVENT_BUS.post(animal);
            check(animal.getResult() == Event.Result.DEFAULT, "an animal was kept from spawning inside a home");
            Zombie wild = EntityType.ZOMBIE.create(level);
            wild.moveTo(h.base.getX() - 20, h.base.getY(), h.base.getZ());
            MobSpawnEvent.PositionCheck field = new MobSpawnEvent.PositionCheck(wild, level, MobSpawnType.NATURAL, null);
            MinecraftForge.EVENT_BUS.post(field);
            check(field.getResult() == Event.Result.DEFAULT, "control: monsters were kept from spawning outside every home");
            });
        }
        helper.succeed();
    }

    /**
     * Somebody who may walk in and use the household's things but not build (a guest, or a member the founder took the build right from) clicks on blocks they
     * may use. What they hold in their hand must not turn that click into building: a frame, a stand, a minecart, fire, a bucket or a spawn egg all put
     * something into the home. (An outsider is refused earlier, at the clicked block, so only this player shows whether the item itself is checked.)
     */
    @GameTest(template = "empty")
    public static void aMemberWhoMayEnterButNotBuildCannotPlaceEntitiesFluidsOrFire(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            h.ledger.setPermission(h.founder.getUUID(), h.founder.getGameProfile().getName(), Role.MEMBER, Perm.BUILD, false);
            check(!h.ledger.crewOf(h.member.getUUID()).can(h.member.getUUID(), Perm.BUILD) && h.ledger.crewOf(h.member.getUUID()).can(h.member.getUUID(), Perm.ACCESS_HOME), "setup: the member may enter but not build");
            BlockPos wood = h.in(2, 0, 2);
            level.setBlockAndUpdate(wood, Blocks.OAK_PLANKS.defaultBlockState());
            check(!used(level, h.member, wood).isCanceled(), "control: clicking a block with an empty hand is a plain use the member may do");
            for (ItemStack item : List.of(new ItemStack(Items.ITEM_FRAME), new ItemStack(Items.ARMOR_STAND), new ItemStack(Items.OAK_BOAT), new ItemStack(Items.MINECART),
                    new ItemStack(Items.FLINT_AND_STEEL), new ItemStack(Items.FIRE_CHARGE), new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.LAVA_BUCKET), new ItemStack(Items.ZOMBIE_SPAWN_EGG), new ItemStack(Items.END_CRYSTAL))) {
                check(usedWith(level, h.member, item.copy(), wood, Direction.UP).isCanceled(), "a member who may not build was allowed to use " + item.getItem() + " on a block of the home");
            }
            // and an item that places nothing is fine
            check(!usedWith(level, h.member, new ItemStack(Items.APPLE), wood, Direction.UP).isCanceled(), "control: a plain item was stopped");
            // the real path as well: flint and steel does not light the planks
            use(h.member, new ItemStack(Items.FLINT_AND_STEEL), wood, Direction.UP);
            check(level.getBlockState(h.in(2, 1, 2)).isAir(), "a member who may not build lit a fire in the home");
            // give the right back: the same click works
            h.ledger.setPermission(h.founder.getUUID(), h.founder.getGameProfile().getName(), Role.MEMBER, Perm.BUILD, true);
            use(h.member, new ItemStack(Items.FLINT_AND_STEEL), wood, Direction.UP);
            check(level.getBlockState(h.in(2, 1, 2)).is(Blocks.FIRE), "control: with the build right the same click lit a fire");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void normalCraftingStorageAndBuildingStillWorkForTheEntitled(GameTestHelper helper) throws Exception {
        try (Home h = new Home(helper)) {
            ServerLevel level = h.level;
            // a member builds and furnishes
            use(h.member, new ItemStack(Items.OAK_PLANKS), h.in(1, -1, 1), Direction.UP);
            check(level.getBlockState(h.in(1, 0, 1)).is(Blocks.OAK_PLANKS), "a member could not place a block in their home");
            check(!brokenBy(level, h.member, h.in(1, 0, 1)).isCanceled(), "a member could not break a block in their home");
            use(h.member, new ItemStack(Items.CHEST), h.in(3, -1, 3), Direction.UP);
            check(level.getBlockState(h.in(3, 0, 3)).is(Blocks.CHEST), "a member could not place a chest");
            use(h.member, new ItemStack(Items.CRAFTING_TABLE), h.in(2, -1, 3), Direction.UP);
            use(h.member, new ItemStack(Items.FURNACE), h.in(4, -1, 3), Direction.UP);
            check(level.getBlockState(h.in(2, 0, 3)).is(Blocks.CRAFTING_TABLE) && level.getBlockState(h.in(4, 0, 3)).is(Blocks.FURNACE), "a member could not furnish the home");
            // the real events for using them
            check(!used(level, h.member, h.in(3, 0, 3)).isCanceled(), "a member could not open their chest");
            check(!used(level, h.member, h.in(4, 0, 3)).isCanceled(), "a member could not use their furnace");
            check(!used(level, h.member, h.in(2, 0, 3)).isCanceled(), "a member could not use their crafting table");
            check(used(level, h.outsider, h.in(3, 0, 3)).isCanceled() && used(level, h.outsider, h.in(4, 0, 3)).isCanceled(), "an outsider used a chest or furnace in a home");
            // a hopper beside a furnace the member set up is theirs to make (they may use storage)
            use(h.member, new ItemStack(Items.HOPPER), h.in(4, -1, 2), Direction.UP);
            check(level.getBlockState(h.in(4, 0, 2)).is(Blocks.HOPPER), "a member could not build a hopper feeding their own furnace");
            // the builder can still do the building work that needs no storage
            use(h.builder, new ItemStack(Items.STONE_BRICKS), h.in(0, -1, 0), Direction.UP);
            check(level.getBlockState(h.in(0, 0, 0)).is(Blocks.STONE_BRICKS), "a builder could not place a block");
            check(h.builder.getUUID() != null && !used(level, h.builder, h.in(2, 0, 3)).isCanceled(), "a builder could not use a crafting table");
        }
        helper.succeed();
    }

    private static net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock usedWith(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var event = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, against, click(against, face));
        MinecraftForge.EVENT_BUS.post(event);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return event;
    }

    private static net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock used(ServerLevel level, ServerPlayer player, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var event = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, click(pos, Direction.UP));
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }
}
