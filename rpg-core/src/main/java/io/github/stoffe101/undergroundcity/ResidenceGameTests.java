package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import io.github.stoffe101.undergroundcity.ResidenceGuard.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;
import static io.github.stoffe101.undergroundcity.CrewGameTests.rejected;

/** Homes: leasing, ownership and who may do what inside them, including the real Forge events the guard listens to. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidenceGameTests {
    private static final String OVERWORLD = "minecraft:overworld";

    /** Stands the player beside the homes at {@code base}: leasing needs the tenant to be nearby. */
    private static void near(BlockPos base, ServerPlayer... players) {
        for (ServerPlayer p : players) p.moveTo(base.getX() + 2.5, base.getY() + 1, base.getZ() + 2.5);
    }

    private static Residence flat(CrewLedger l, BlockPos base, String id, int price, int starter) {
        return l.registerResidence(id, "Flat " + id, "flat", OVERWORLD, "test", base, base.offset(4, 3, 4), price, starter);
    }

    @GameTest(template = "empty")
    public static void leasingIsFiniteAtomicAndNotFree(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            ServerPlayer a = player(helper, "uc_la", "farmer"), b = player(helper, "uc_lb", "smith"), c = player(helper, "uc_lc", "gunsmith");
            for (ServerPlayer p : List.of(a, b, c)) rig.wallet.balances.put(p.getUUID(), 300d);
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            near(base, a, b, c);
            Residence one = flat(l, base, "test:one", 100, 40), two = flat(l, base.offset(20, 0, 0), "test:two", 100, 40), three = flat(l, base.offset(40, 0, 0), "test:three", 100, -1);
            rejected(() -> flat(l, base.offset(2, 0, 2), "test:overlap", 100, -1), "overlapping interiors");
            rejected(() -> flat(l, base.offset(0, 0, 100), "test:bad", 0, 0), "a free home");
            rejected(() -> flat(l, base.offset(100, 0, 0), "test:bad2", 50, 60), "a starter price above the price");
            // two shells may touch (a shared wall) but an interior may not enter a neighbour's shell
            flat(l, base.offset(6, 0, 0), "test:neighbour", 100, -1);                                   // interior x 6..10, shell x 5..11
            rejected(() -> flat(l, base.offset(11, 0, 0), "test:tooclose", 100, -1), "an interior inside another's shell"); // x 11 is the neighbour's wall
            flat(l, base.offset(12, 0, 0), "test:sharedwall", 100, -1);                                  // x 12..16: its wall x 11 is the neighbour's wall too
            // a personal lease from a wallet: starter price once, then the full price
            Crew crew = l.create(a.getUUID(), "uc_la", "Lease Crew");
            l.accept(l.invite(a.getUUID(), b.getUUID(), "uc_lb", "uc_la").id(), b.getUUID(), "uc_lb");
            CrewService.lease(a, id(), "test:one", false, false);
            check(one.ownerType == OwnerType.PLAYER && one.owner.equals(a.getUUID()), "a personal lease");
            check(rig.wallet.balances.get(a.getUUID()) == 260d, "the starter price was not charged: " + rig.wallet.balances.get(a.getUUID()));
            check(!l.starterAvailable(a.getUUID()), "the starter discount was not spent");
            rejected(() -> CrewService.lease(b, id(), "test:one", false, false), "leasing a home somebody holds");
            // joining a Crew does not touch a personal home, and the Crew cannot lease from the bank without funds
            check(one.ownerType == OwnerType.PLAYER && l.crewOf(a.getUUID()) == crew, "joining a Crew changed a personal home");
            rejected(() -> CrewService.lease(a, id(), "test:two", true, true), "an empty bank paying for a home");
            // a member without the right cannot lease for the Crew, even with their own money
            rejected(() -> CrewService.lease(b, id(), "test:two", true, false), "a member leasing for the Crew without permission");
            // the Crew pools money, then the founder pays the full price (the founder's starter discount is spent)
            CrewService.deposit(a, id(), 30);
            CrewService.deposit(b, id(), 30);
            rejected(() -> CrewService.lease(a, id(), "test:two", true, true), "a bank of 60 paying a price of 100");
            CrewService.deposit(a, id(), 30);
            CrewService.deposit(b, id(), 30);
            CrewService.lease(a, id(), "test:two", true, true);
            check(crew.bank == 20 && two.ownerType == OwnerType.CREW && two.owner.equals(crew.id), "the Crew lease: bank " + crew.bank);
            check(l.starterAvailable(b.getUUID()), "a member's starter discount was spent by somebody else's lease");
            // the third home is a first-come lease: whoever pays gets it, nobody else can
            CrewService.lease(c, id(), "test:three", false, false);
            check(three.owner.equals(c.getUUID()) && rig.wallet.balances.get(c.getUUID()) == 200d, "a lease without a starter price");
            rejected(() -> CrewService.lease(a, id(), "test:three", true, false), "leasing a home that is gone");
            // a stranger cannot reach in: homes are finite, there is no source of new ones
            check(l.allResidences().stream().filter(Residence::owned).count() == 3, "the number of homes changed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crewLeaseFromBankIsOneAtomicWrite(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            ServerPlayer a = player(helper, "uc_ba", "farmer"), b = player(helper, "uc_bb", "smith");
            rig.wallet.balances.put(a.getUUID(), 500d); rig.wallet.balances.put(b.getUUID(), 500d);
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            near(base, a, b);
            Residence house = flat(l, base, "test:house", 150, 90);
            Crew crew = l.create(a.getUUID(), "uc_ba", "Bank Crew");
            l.accept(l.invite(a.getUUID(), b.getUUID(), "uc_bb", "uc_ba").id(), b.getUUID(), "uc_bb");
            CrewService.deposit(a, id(), 50);
            CrewService.deposit(b, id(), 50);
            rejected(() -> CrewService.lease(b, id(), "test:house", true, true), "a plain member leasing from the bank");
            UUID tx = id();
            CrewService.lease(a, tx, "test:house", true, true);
            check(crew.bank == 10 && house.ownerType == OwnerType.CREW && house.owner.equals(crew.id), "starter lease from the bank: bank " + crew.bank);
            check(l.reloaded().residence("test:house").ownerType == OwnerType.CREW && l.reloaded().crew(crew.id).bank == 10, "the lease and the bank are not in the same durable write");
            rejected(() -> CrewService.lease(a, tx, "test:house", true, true), "a replayed lease");
            rejected(() -> CrewService.lease(a, id(), "test:house", true, true), "leasing a home twice");
            check(!l.starterAvailable(a.getUUID()) && l.starterAvailable(b.getUUID()), "the starter entitlement belongs to the person who used it");
            // far from the home nobody can lease it
            ServerPlayer far = player(helper, "uc_far", "technician");
            Residence second = flat(l, base.offset(30, 0, 0), "test:second", 100, -1);
            rig.wallet.balances.put(far.getUUID(), 500d);
            far.moveTo(base.getX() + 500, base.getY(), base.getZ());
            rejected(() -> CrewService.lease(far, id(), "test:second", false, false), "leasing from 500 blocks away");
            check(second.ownerType == OwnerType.NONE, "a refusal still changed ownership");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void walletLeaseCrashRecoversToOwnedOrFreeNeverBoth(GameTestHelper helper) throws Exception {
        for (String stage : List.of("after_begin", "after_wallet", "after_save")) {
            for (boolean lost : new boolean[]{false, true}) {
                if (stage.equals("after_save") && lost || stage.equals("after_begin") && lost) continue;
                try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
                    CrewLedger l = rig.ledger;
                    ServerPlayer a = player(helper, "uc_ca", "farmer"), rival = player(helper, "uc_cb", "smith");
                    rig.wallet.balances.put(a.getUUID(), 200d); rig.wallet.balances.put(rival.getUUID(), 200d);
                    BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
                    near(base, a, rival);
                    flat(l, base, "test:lonely", 100, 40);
                    CrewService.crashAt = stage;
                    try { CrewService.lease(a, id(), "test:lonely", false, false); throw new AssertionError("no crash"); } catch (CrewService.SimulatedCrash expected) { }
                    CrewService.crashAt = "";
                    if (lost) rig.wallet.balances.put(a.getUUID(), 200d);
                    // the unit is reserved while the lease is in flight, in memory and after a restart
                    rejected(() -> CrewService.lease(rival, id(), "test:lonely", false, false), "a second tenant taking a reserved home");
                    rig.restart();
                    rejected(() -> CrewService.lease(rival, id(), "test:lonely", false, false), "a second tenant taking a reserved home after a restart");
                    check(CrewService.recover(rig.server, rig.wallet) == 1, "recovery " + stage + " " + lost);
                    Residence r = rig.ledger.residence("test:lonely");
                    boolean settled = !lost && !stage.equals("after_begin");
                    String where = stage + (lost ? " (wallet lost)" : "");
                    check(settled == r.owned() && (!settled || r.owner.equals(a.getUUID())), where + ": ownership " + r.ownerType);
                    check(r.reservedBy == null, where + ": the reservation was not released");
                    check(rig.wallet.balances.get(a.getUUID()) == (settled ? 160d : 200d), where + ": wallet " + rig.wallet.balances.get(a.getUUID()));
                    check(settled != rig.ledger.starterAvailable(a.getUUID()), where + ": starter entitlement out of step with the lease");
                    if (!settled) { CrewService.lease(rival, id(), "test:lonely", false, false); check(rig.ledger.residence("test:lonely").owner.equals(rival.getUUID()), "a cancelled lease left the home unobtainable"); }
                }
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the guard

    @GameTest(template = "empty")
    public static void guardDecisionMatrix(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            ServerPlayer founder = player(helper, "uc_gf", "farmer"), member = player(helper, "uc_gm", "smith"), outsider = player(helper, "uc_go", "gunsmith"), exMember = player(helper, "uc_gx", "technician");
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            near(base, founder, member, outsider, exMember);
            Residence r = flat(l, base, "test:guard", 10, -1);
            BlockPos inside = base.offset(2, 1, 2), wall = base.offset(-1, 1, 2), chest = base.offset(1, 0, 1);
            // a showroom: look, don't touch
            for (Act act : Act.values()) {
                boolean expected = act == Act.DOOR || act == Act.OTHER;
                check(ResidenceGuard.allowed(l, r, outsider.getUUID(), OVERWORLD, inside, act) == expected, "showroom " + act);
            }
            Crew crew = l.create(founder.getUUID(), "uc_gf", "Guard Crew");
            for (ServerPlayer p : List.of(member, exMember)) l.accept(l.invite(founder.getUUID(), p.getUUID(), p.getGameProfile().getName(), "uc_gf").id(), p.getUUID(), p.getGameProfile().getName());
            rig.wallet.balances.put(founder.getUUID(), 100d);
            CrewService.lease(founder, id(), "test:guard", true, false);
            check(r.ownerType == OwnerType.CREW, "setup");
            // members with the default rights
            for (Act act : List.of(Act.DOOR, Act.OTHER, Act.PLACE, Act.BREAK, Act.CONTAINER, Act.WORKSTATION)) {
                check(ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, act), "member " + act);
                check(ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, inside, act), "founder " + act);
                check(!ResidenceGuard.allowed(l, r, outsider.getUUID(), OVERWORLD, inside, act), "outsider " + act);
            }
            check(!ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, Act.EXPLODE), "an explosion was allowed");
            // the shell (walls) is the city's: nobody builds in it, only members open its doors
            check(!ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, wall, Act.BREAK) && !ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, wall, Act.PLACE), "the founder could change the city's wall");
            check(ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, wall, Act.DOOR) && !ResidenceGuard.allowed(l, r, outsider.getUUID(), OVERWORLD, wall, Act.DOOR), "doors in the shell");
            check(r.insideShell(OVERWORLD, wall) && !r.insideInterior(OVERWORLD, wall) && l.residenceAt(OVERWORLD, wall) == r, "the shell lookup");
            check(l.residenceAt(OVERWORLD, base.offset(-2, 1, 2)) == null && l.residenceAt("minecraft:the_nether", inside) == null, "a position outside the shell was claimed");
            // the founder narrows what members may do
            l.setPermission(founder.getUUID(), "f", Role.MEMBER, Perm.WORKSTATIONS, false);
            check(!ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, Act.WORKSTATION) && ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, Act.CONTAINER), "per-permission rights");
            l.setPermission(founder.getUUID(), "f", Role.MEMBER, Perm.ACCESS_HOME, false);
            check(!ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, Act.CONTAINER) && !ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, inside, Act.DOOR), "storage without access to the home");
            l.setPermission(founder.getUUID(), "f", Role.MEMBER, Perm.ACCESS_HOME, true);
            l.setPermission(founder.getUUID(), "f", Role.MEMBER, Perm.WORKSTATIONS, true);

            // a private container: only the claimant opens or breaks it
            l.claimContainer(member.getUUID(), "test:guard", chest);
            check(ResidenceGuard.allowed(l, r, member.getUUID(), OVERWORLD, chest, Act.CONTAINER), "the claimant is locked out of their own container");
            check(!ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, chest, Act.CONTAINER) && !ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, chest, Act.BREAK), "even the founder opened or broke a private container");
            check(ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, base.offset(2, 0, 1), Act.CONTAINER), "an unclaimed container is shared");
            rejected(() -> l.claimContainer(founder.getUUID(), "test:guard", chest), "claiming a container somebody else holds");
            rejected(() -> l.releaseClaim(founder.getUUID(), "test:guard", chest), "releasing somebody else's claim");
            for (int i = 0; i < CrewRules.MAX_CLAIMS_PER_PLAYER - 1; i++) l.claimContainer(member.getUUID(), "test:guard", base.offset(3, 0, i));
            rejected(() -> l.claimContainer(member.getUUID(), "test:guard", base.offset(4, 0, 4)), "more private containers than allowed");
            // a departing member keeps a retrieval window for their private containers, then they become shared
            l.claimContainer(exMember.getUUID(), "test:guard", base.offset(0, 1, 4));
            l.leave(exMember.getUUID(), "uc_gx");
            check(ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, base.offset(0, 1, 4), Act.CONTAINER), "the leaver cannot fetch what they stored");
            check(ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, wall, Act.DOOR), "the leaver cannot get in to fetch it");
            check(!ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, inside, Act.CONTAINER) && !ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, inside, Act.PLACE), "the leaver can use the household's things");
            rig.now[0] += CrewRules.RETRIEVAL_MILLIS + 1;
            check(!ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, base.offset(0, 1, 4), Act.CONTAINER) && !ResidenceGuard.allowed(l, r, exMember.getUUID(), OVERWORLD, wall, Act.DOOR), "the retrieval window never closes");
            check(ResidenceGuard.allowed(l, r, founder.getUUID(), OVERWORLD, base.offset(0, 1, 4), Act.CONTAINER), "an abandoned private container did not become shared");
            // a personal home: the owner everything, everybody else nothing, even a Crew member
            Residence mine = flat(l, base.offset(20, 0, 0), "test:mine", 10, -1);
            rig.wallet.balances.put(member.getUUID(), 100d);
            near(base.offset(20, 0, 0), member);
            CrewService.lease(member, id(), "test:mine", false, false);
            for (Act act : List.of(Act.DOOR, Act.PLACE, Act.CONTAINER, Act.WORKSTATION)) {
                check(ResidenceGuard.allowed(l, mine, member.getUUID(), OVERWORLD, base.offset(22, 1, 2), act), "the owner of a personal home, " + act);
                check(!ResidenceGuard.allowed(l, mine, founder.getUUID(), OVERWORLD, base.offset(22, 1, 2), act), "a Crew mate in a personal home, " + act);
            }
            // a restart keeps claims, guests and owners
            rig.restart();
            Residence back = rig.ledger.residence("test:guard");
            check(back.ownerType == OwnerType.CREW && back.claims.containsKey(chest.asLong()) && back.guests.containsKey(exMember.getUUID()), "homes, claims or guests lost across a restart");
            check(rig.ledger.residenceAt(OVERWORLD, wall) == back, "the lookup index was not rebuilt after a restart");
        }
        helper.succeed();
    }

    private static BlockHitResult hit(BlockPos pos) { return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false); }

    @GameTest(template = "empty")
    public static void guardCancelsRealForgeEvents(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            ServerLevel level = helper.getLevel();
            ServerPlayer founder = player(helper, "uc_ef", "farmer"), member = player(helper, "uc_em", "smith"), outsider = player(helper, "uc_eo", "gunsmith");
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            near(base, founder, member, outsider);
            Residence r = flat(l, base, "test:events", 10, -1);
            BlockPos inside = base.offset(2, 1, 2), chest = base.offset(1, 0, 1), furnace = base.offset(3, 0, 1), door = base.offset(-1, 0, 2), wallStone = base.offset(-1, 1, 3), outside = base.offset(-6, 0, 2);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
            level.setBlockAndUpdate(door, Blocks.OAK_DOOR.defaultBlockState());
            level.setBlockAndUpdate(wallStone, Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(inside, Blocks.OAK_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(outside, Blocks.DIRT.defaultBlockState());
            l.create(founder.getUUID(), "uc_ef", "Event Crew");
            l.accept(l.invite(founder.getUUID(), member.getUUID(), "uc_em", "uc_ef").id(), member.getUUID(), "uc_em");
            rig.wallet.balances.put(founder.getUUID(), 100d);
            // an unleased unit is a showroom; leasing it makes it the Crew's
            check(brokenBy(level, outsider, inside).isCanceled(), "a showroom wall was breakable");
            CrewService.lease(founder, id(), "test:events", true, false);

            check(!brokenBy(level, member, inside).isCanceled() && !brokenBy(level, founder, inside).isCanceled(), "members cannot break blocks in their own home");
            check(brokenBy(level, outsider, inside).isCanceled(), "an outsider broke a block inside a Crew home");
            check(brokenBy(level, outsider, chest).isCanceled(), "an outsider broke a chest");
            check(brokenBy(level, founder, wallStone).isCanceled(), "the founder broke the city's wall");
            check(!brokenBy(level, outsider, outside).isCanceled(), "a block outside every home was protected");
            check(placedBy(level, outsider, inside.above()).isCanceled() && !placedBy(level, member, inside.above()).isCanceled(), "placement");
            check(placedBy(level, founder, wallStone.above()).isCanceled(), "building into the shell");

            check(usedBy(level, outsider, chest).isCanceled() && !usedBy(level, member, chest).isCanceled(), "opening a chest");
            check(usedBy(level, outsider, furnace).isCanceled() && !usedBy(level, member, furnace).isCanceled(), "using a furnace (a workstation)");
            check(usedBy(level, outsider, door).isCanceled() && !usedBy(level, member, door).isCanceled(), "opening the door");
            // the safes of the burglary system keep their own rules
            level.setBlockAndUpdate(base.offset(4, 0, 4), UndergroundCity.PROPERTY_CACHE.get().defaultBlockState());
            check(ResidenceGuard.classify(level.getBlockState(base.offset(4, 0, 4)), level.getBlockEntity(base.offset(4, 0, 4))) == Act.OTHER, "a Household Safe is a container of the Crew system");

            // creative players are never held back
            outsider.setGameMode(GameType.CREATIVE);
            check(!brokenBy(level, outsider, inside).isCanceled(), "a creative player was held back");
            outsider.setGameMode(GameType.SURVIVAL);

            // explosions spare homes
            Explosion explosion = new Explosion(level, null, inside.getX(), inside.getY(), inside.getZ(), 4f, false, Explosion.BlockInteraction.DESTROY);
            explosion.getToBlow().add(inside); explosion.getToBlow().add(wallStone); explosion.getToBlow().add(outside);
            MinecraftForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level, explosion, List.of()));
            check(explosion.getToBlow().equals(List.of(outside)), "an explosion was allowed to destroy a home: " + explosion.getToBlow());
        }
        helper.succeed();
    }

    private static BlockEvent.BreakEvent brokenBy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    private static BlockEvent.EntityPlaceEvent placedBy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = Blocks.STONE.defaultBlockState();
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos), state, player);
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    private static PlayerInteractEvent.RightClickBlock usedBy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit(pos));
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    /** An iron door of a home opens for those the home lets through, and for nobody else: the commercial workshop's iron street door lies inside its premises, where the building's guard
     *  stands aside, and its tenant could not get in (premises run 5, seventh pass). */
    @GameTest(template = "empty")
    public static void aHomesIronDoorOpensForItsTenantAndNobodyElse(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            ServerPlayer tenant = player(helper, "uc_it", "smith"), stranger = player(helper, "uc_is", "farmer");
            rig.wallet.balances.put(tenant.getUUID(), 500d);
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            near(base, tenant, stranger);
            flat(l, base, "test:ironworks", 100, -1);
            ServerLevel level = helper.getLevel();
            BlockPos door = base.offset(2, 1, -1);   // in the home's front wall (its shell)
            level.setBlockAndUpdate(door, Blocks.IRON_DOOR.defaultBlockState());
            level.setBlockAndUpdate(door.above(), Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            CrewService.lease(tenant, id(), "test:ironworks", false, false);
            check(usedBy(level, stranger, door).isCanceled() && !level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "a stranger opened the tenant's iron door");
            usedBy(level, tenant, door.above());
            check(level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "the tenant's click did not open their home's iron door");
            usedBy(level, tenant, door);
            check(!level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "the tenant's second click did not shut it");
        }
        helper.succeed();
    }

    /** The real Row Flats, built into the sky of the test world: their homes register, lease, and are guarded block by block; the lease is what keeps the district from being undone. */
    @GameTest(template = "empty", batch = "uc_district", timeoutTicks = 400)
    public static void rowFlatsAreRealHomesThatCanBeLeasedGuardedAndNotUndoneFromUnderTheirTenants(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find("row_flats");
        check(district != null && district.homeOps().size() == 3, "Row Flats must load with three homes");
        BlockPos arena = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos origin = new BlockPos(arena.getX() - 12, level.getMaxBuildHeight() - 40, arena.getZ() - 8);
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        for (int cx = (lo.getX() - 1) >> 4; cx <= (hi.getX() + 1) >> 4; cx++) for (int cz = (lo.getZ() - 1) >> 4; cz <= (hi.getZ() + 1) >> 4; cz++) level.getChunk(cx, cz);
        DistrictBuilder.forgetForTest(level, district.id());
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            var result = DistrictBuilder.build(level, district, origin);
            check(result.homes() == 3 && l.allResidences().size() == 3 && result.terminals() == 3, "Build summary " + result);
            ServerPlayer tenant = player(helper, "uc_tenant", "farmer"), mate = player(helper, "uc_mate", "gunsmith"), stranger = player(helper, "uc_visitor", "technician");
            for (ServerPlayer p : List.of(tenant, mate, stranger)) rig.wallet.balances.put(p.getUUID(), 500d);
            DistrictCatalog.Op flatOp = district.homeOps().get(0), houseOp = district.homeOps().get(2);
            Residence flat = l.residence(DistrictBuilder.homeId(district, flatOp.home())), house = l.residence(DistrictBuilder.homeId(district, houseOp.home()));
            check(flat.min.equals(origin.offset(flatOp.a())) && flat.max.equals(origin.offset(flatOp.b())) && flat.price == PropertyCatalog.INSTANCE.find(flat.id).orElseThrow().buyPrice() && flat.starterPrice < 0, "the registered region and price come from the data");
            // the furnishings the data promises are really there, inside the registered interior
            BlockPos flatChest = origin.offset(flatOp.b().getX(), 0, flatOp.a().getZ()), flatDoor = origin.offset((flatOp.a().getX() + flatOp.b().getX()) / 2, 0, 8);
            check(level.getBlockState(flatChest).is(Blocks.CHEST) && flat.insideInterior(OVERWORLD, flatChest), "the flat's chest");
            check(level.getBlockState(flatDoor).is(Blocks.OAK_DOOR) && flat.insideShell(OVERWORLD, flatDoor) && !flat.insideInterior(OVERWORLD, flatDoor), "the flat's door is in the shell");
            check(level.getBlockState(origin.offset(flatOp.a().getX() + 1, 0, 1)).is(UndergroundCity.TERMINAL.get()), "the home computer");
            for (BlockPos p : BlockPos.betweenClosed(house.min, house.max)) {
                if (level.getBlockState(p).is(Blocks.SMITHING_TABLE) || level.getBlockState(p).is(Blocks.ANVIL) || level.getBlockState(p).is(Blocks.GRINDSTONE) || level.getBlockState(p).is(Blocks.BREWING_STAND)
                    || level.getBlockState(p).is(Blocks.CRAFTING_TABLE) || level.getBlockState(p).is(Blocks.FURNACE)) {
                    check(ResidenceGuard.classify(level.getBlockState(p), level.getBlockEntity(p)) == Act.WORKSTATION, "workstation at " + p);
                }
            }

            // a showroom, then a personal tenancy of the flat (the deposit and the first hour), then the Crew renting the house from the bank
            check(brokenBy(level, stranger, flatChest).isCanceled() && usedBy(level, stranger, flatChest).isCanceled() && !usedBy(level, stranger, flatDoor).isCanceled(), "an unleased flat: look, don't touch");
            near(flat.min, tenant);
            var flatTerms = PropertyCatalog.INSTANCE.find(flat.id).orElseThrow();
            int flatCost = l.rentQuote(tenant.getUUID(), flat, flatTerms, PropertyCatalog.INSTANCE.rules()).total();
            PropertyService.rent(tenant, id(), flat.id, false, false);
            check(flat.ownerType == OwnerType.PLAYER && flat.tenure == PropertyRules.Tenure.RENT && rig.wallet.balances.get(tenant.getUUID()) == 500d - flatCost, "the deposit and the first hour for the flat: " + rig.wallet.balances.get(tenant.getUUID()));
            check(!usedBy(level, tenant, flatChest).isCanceled() && !usedBy(level, tenant, flatDoor).isCanceled(), "a tenant locked out of their own flat");
            check(usedBy(level, stranger, flatChest).isCanceled() && usedBy(level, stranger, flatDoor).isCanceled() && usedBy(level, mate, flatChest).isCanceled(), "a flat open to people who do not live there");
            check(brokenBy(level, tenant, flatDoor).isCanceled(), "a tenant broke their own front door out of the city's wall");
            BlockPos wall = origin.offset(flatOp.a().getX() - 1, 1, 4);
            check(brokenBy(level, tenant, wall).isCanceled(), "a tenant broke the wall they share with the next flat");

            // the Crew takes the house
            Crew crew = l.create(tenant.getUUID(), "uc_tenant", "Flat Crew");
            l.accept(l.invite(tenant.getUUID(), mate.getUUID(), "uc_mate", "uc_tenant").id(), mate.getUUID(), "uc_mate");
            near(house.min, tenant, mate, stranger);
            CrewService.deposit(tenant, id(), 200);
            CrewService.deposit(mate, id(), 200);
            var houseTerms = PropertyCatalog.INSTANCE.find(house.id).orElseThrow();
            int houseCost = l.rentQuote(tenant.getUUID(), house, houseTerms, PropertyCatalog.INSTANCE.rules()).total();
            PropertyService.rent(tenant, id(), house.id, true, true);
            check(house.ownerType == OwnerType.CREW && house.tenure == PropertyRules.Tenure.RENT && crew.bank == 400 - houseCost, "the Row House rented from the bank: bank " + crew.bank);
            BlockPos houseChest = origin.offset(houseOp.b().getX(), 0, houseOp.a().getZ());
            BlockPos houseSmith = origin.offset(houseOp.b().getX() - 3, 0, 1);
            check(level.getBlockState(houseSmith).is(Blocks.SMITHING_TABLE), "the house's smithing table");
            for (BlockPos pos : List.of(houseChest, houseSmith)) {
                check(!usedBy(level, tenant, pos).isCanceled() && !usedBy(level, mate, pos).isCanceled(), "the Crew cannot use its own " + level.getBlockState(pos).getBlock());
                check(usedBy(level, stranger, pos).isCanceled(), "a stranger used the Crew's " + level.getBlockState(pos).getBlock());
            }
            // the personal flat is still the tenant's alone, and the mate cannot enter it
            check(usedBy(level, mate, flatDoor).isCanceled() && !usedBy(level, tenant, flatDoor).isCanceled(), "joining a Crew changed who may enter a personal flat");
            // the founder takes the workstation permission away: the chest stays open, the table closes
            l.setPermission(tenant.getUUID(), "uc_tenant", Role.MEMBER, Perm.WORKSTATIONS, false);
            check(!usedBy(level, mate, houseChest).isCanceled() && usedBy(level, mate, houseSmith).isCanceled(), "workstation permission");
            // the district cannot be undone while its homes are leased
            rejected(() -> DistrictBuilder.undo(level, district), "undoing a district whose homes are leased");
            check(level.getBlockState(houseChest).is(Blocks.CHEST) && l.residence(house.id).owned(), "a refused undo changed the world");
        }
        // with nobody holding a home the district goes away cleanly, and its homes leave the registry
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            // the real ledger never knew these homes; undo asks the ledger of the world, which holds none of them
            DistrictBuilder.undo(level, district);
            check(!DistrictBuilder.isBuilt(level, district.id()), "undo left the district built");
            check(!level.getBlockState(origin.offset(district.homeOps().get(0).b().getX(), 0, 1)).is(Blocks.CHEST), "undo left the chest");
            // registering again after an undo works (a fresh world's ledger, a fresh build)
            var again = DistrictBuilder.build(level, district, origin);
            check(again.homes() == 3 && rig.ledger.allResidences().size() == 3, "a rebuilt district registers its homes again");
            DistrictBuilder.undo(level, district);
            check(rig.ledger.allResidences().isEmpty(), "undoing an unleased district left its homes registered");
        }
        DistrictBuilder.forgetForTest(level, district.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void districtsRegisterAndReleaseHomes(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewLedger l = rig.ledger;
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            flat(l, base, "test:keep", 10, -1);
            l.unregisterResidence("test:keep");
            check(l.residence("test:keep") == null && l.residenceAt(OVERWORLD, base.offset(1, 1, 1)) == null, "an unleased unit could not be removed");
            Residence held = flat(l, base, "test:held", 10, -1);
            ServerPlayer a = player(helper, "uc_ra", "farmer");
            near(base, a);
            rig.wallet.balances.put(a.getUUID(), 100d);
            CrewService.lease(a, id(), "test:held", false, false);
            rejected(() -> l.unregisterResidence("test:held"), "removing a home somebody holds");
            rejected(() -> flat(l, base.offset(8, 0, 0), "test:held", 10, -1), "moving a held home");
            check(flat(l, base, "test:held", 10, -1) == held && held.owner.equals(a.getUUID()), "registering the same home again disturbed its owner");
        }
        helper.succeed();
    }
}
