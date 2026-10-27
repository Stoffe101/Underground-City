package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * Breaking things in a burglary and the city putting them back (D-190), on the test house of the building tests: who may break what, that a break drops nothing and takes what stood on it, and
 * that the record survives a save and is put back once the wait is over, never over a cell somebody filled. Through a real client: the wreck scenario.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BurglaryDamageGameTests {

    private static ServerPlayer thief(GameTestHelper helper, String name) {
        ServerPlayer p = player(helper, name, "farmer");
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    /** The room's furniture: a carpet, an inner wall of planks with a torch on it, a sign with text, a window in the outer wall, a chest. */
    private static void furnish(BuildingSecurityGameTests.House h) {
        h.level.setBlock(h.in(2, 0, 2), Blocks.RED_CARPET.defaultBlockState(), 2);
        h.level.setBlock(h.in(4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        h.level.setBlock(h.in(4, 1, 4), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        h.level.setBlock(h.in(4, 2, 4), Blocks.TORCH.defaultBlockState(), 2);
        h.level.setBlock(h.in(2, 0, 4), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4), 2);
        if (h.level.getBlockEntity(h.in(2, 0, 4)) instanceof SignBlockEntity sign) sign.setText(sign.getFrontText().setMessage(0, Component.literal("Home sweet home")), true);
        h.level.setBlock(h.in(0, 2, 3), Blocks.GLASS_PANE.defaultBlockState(), 2);
        h.level.setBlock(h.in(0, 1, 2), Blocks.OAK_PLANKS.defaultBlockState(), 2);   // a plank in the outer wall: part of the shell
        h.level.setBlock(h.in(5, 0, 1), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST), 2);
    }

    @GameTest(template = "empty", batch = "uc_wreck_rules", timeoutTicks = 200)
    public static void onlyAThiefWhoPickedThisBuildingMayBreakItsFurnitureWindowsAndInnerWalls(GameTestHelper helper) {
        try (BuildingSecurityGameTests.House h = new BuildingSecurityGameTests.House(helper)) {
            furnish(h);
            BurglaryDamage.resetForTest();
            ServerPlayer thief = thief(helper, "uc_wreck_a"), visitor = thief(helper, "uc_wreck_b");
            long now = LockService.clock(h.level.getServer());
            check(BurglaryDamage.judge(visitor, h.level, h.building, h.in(2, 0, 2), now) == BurglaryDamage.Verdict.NO_PERMIT, "somebody who picked nothing may break the furniture");
            BurglaryDamage.grantForTest(thief, h.building, now);
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(2, 0, 2), now) == BurglaryDamage.Verdict.ALLOWED, "the thief may not break a carpet");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(0, 2, 3), now) == BurglaryDamage.Verdict.ALLOWED, "the thief may not break a window");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(4, 1, 4), now) == BurglaryDamage.Verdict.ALLOWED, "the thief may not break an inner wall of planks");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(0, 1, 2), now) == BurglaryDamage.Verdict.SHELL, "a plank of the outer wall is not part of the shell");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(2, -1, 2), now) == BurglaryDamage.Verdict.SHELL || BurglaryDamage.judge(thief, h.level, h.building, h.in(2, -1, 2), now) == BurglaryDamage.Verdict.NOT_BREAKABLE,
                "the floor gives way to a thief");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(0, 1, 4), now) == BurglaryDamage.Verdict.NOT_BREAKABLE, "the stone of the walls gives way");
            // a plank of the outer wall under eaves: no sky straight over the air outside it, but a step further out (wreck run 3 found such walls not counted as shell)
            h.level.setBlock(h.in(0, 1, 1), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            h.level.setBlock(h.in(-1, 4, 1), Blocks.STONE.defaultBlockState(), 2);
            h.level.setBlock(h.in(-2, 4, 1), Blocks.STONE.defaultBlockState(), 2);
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(0, 1, 1), now) == BurglaryDamage.Verdict.SHELL, "a plank of the outer wall under the eaves is not part of the shell");
            h.level.setBlock(h.in(-1, 4, 1), Blocks.AIR.defaultBlockState(), 2);
            h.level.setBlock(h.in(-2, 4, 1), Blocks.AIR.defaultBlockState(), 2);
            check(BurglaryDamage.judge(thief, h.level, h.building, h.doorLower, now) == BurglaryDamage.Verdict.PROTECTED, "the door can be broken");
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(5, 0, 1), now) == BurglaryDamage.Verdict.PROTECTED, "a chest can be broken (its contents would spill)");
            // the permit is for this building alone, and for a while
            CityBuildings.Building other = new CityBuildings.Building("ironhaven", h.building.chunkX() + 1, h.building.chunkZ(), "building1", "lostcities:building1", 2, 0, h.building.streetY(), h.building.security());
            check(BurglaryDamage.judge(thief, h.level, other, h.in(2, 0, 2).east(16), now) == BurglaryDamage.Verdict.NO_PERMIT, "a lock picked in one building lets the thief break things in another");
            long later = now + BurglaryDamage.RULES.permitMinutes() * 60L * 20L + 1;
            check(BurglaryDamage.judge(thief, h.level, h.building, h.in(2, 0, 2), later) == BurglaryDamage.Verdict.NO_PERMIT, "the permit did not run out");
            BurglaryDamage.resetForTest();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_wreck_put_back", timeoutTicks = 300)
    public static void aBreakDropsNothingTakesWhatStoodOnItAndIsPutBackOnceTheWaitIsOver(GameTestHelper helper) {
        try (BuildingSecurityGameTests.House h = new BuildingSecurityGameTests.House(helper)) {
            furnish(h);
            BurglaryDamage.resetForTest();
            BurglaryDamage.store(h.level.getServer()).records.remove(h.building.key());
            ServerPlayer thief = thief(helper, "uc_wreck_c");
            // items left lying here by earlier tests in this crowded world are not this test's
            h.level.getEntitiesOfClass(ItemEntity.class, new AABB(h.in(0, 0, 0)).inflate(12)).forEach(ItemEntity::discard);
            long now = LockService.clock(h.level.getServer());
            BurglaryDamage.grantForTest(thief, h.building, now);
            BlockState carpet = h.level.getBlockState(h.in(2, 0, 2)), wall = h.level.getBlockState(h.in(4, 1, 4)), torch = h.level.getBlockState(h.in(4, 2, 4)), pane = h.level.getBlockState(h.in(0, 2, 3));
            int gone = BurglaryDamage.breakFor(thief, h.level, h.building, h.in(4, 1, 4), now);
            check(gone == 2 && h.level.getBlockState(h.in(4, 2, 4)).isAir(), "the torch standing on the broken wall did not go with it: " + gone);
            BurglaryDamage.breakFor(thief, h.level, h.building, h.in(2, 0, 2), now);
            BurglaryDamage.breakFor(thief, h.level, h.building, h.in(0, 2, 3), now);
            BurglaryDamage.breakFor(thief, h.level, h.building, h.in(2, 0, 4), now);
            check(h.level.getEntitiesOfClass(ItemEntity.class, new AABB(h.in(0, 0, 0)).inflate(12)).isEmpty(), "a break dropped an item (a copy made from nothing, once it is put back)");
            BurglaryDamage.Record record = BurglaryDamage.store(h.level.getServer()).records.get(h.building.key());
            check(record != null && record.cells.size() == 5, "the record does not hold the five cells broken: " + (record == null ? "none" : record.cells.size()));
            // the record survives a save and a load
            BurglaryDamage.Store loaded = BurglaryDamage.Store.load(BurglaryDamage.store(h.level.getServer()).save(new CompoundTag()));
            check(loaded.records.get(h.building.key()) != null && loaded.records.get(h.building.key()).cells.size() == 5, "the record did not survive a save");
            // somebody fills one of the cells meanwhile: it is left as it is
            h.level.setBlock(h.in(2, 0, 2), Blocks.STONE.defaultBlockState(), 2);
            // the thief picked the street door and left it standing open
            PropertyLocks.get(h.level.getServer()).breach(h.doorLower, now + 1_000_000);
            BlockState doorState = h.level.getBlockState(h.doorLower);
            ((net.minecraft.world.level.block.DoorBlock) doorState.getBlock()).setOpen(null, h.level, doorState, h.doorLower, true);
            long wait = BurglaryDamage.RULES.restoreMinutes() * 60L * 20L;
            BurglaryDamage.fixClockForTest(now + wait - 20);
            check(BurglaryDamage.restoreDue(h.level.getServer()) == 0 && h.level.getBlockState(h.in(4, 1, 4)).isAir(), "the building was put back before the wait was over");
            BurglaryDamage.fixClockForTest(now + wait);
            check(BurglaryDamage.restoreDue(h.level.getServer()) == 1, "the building was not put back once the wait was over");
            check(h.level.getBlockState(h.in(4, 1, 4)).equals(wall) && h.level.getBlockState(h.in(4, 2, 4)).equals(torch) && h.level.getBlockState(h.in(0, 2, 3)).getBlock() == pane.getBlock(),
                "the wall, the torch on it or the window was not put back as it was");
            check(h.level.getBlockState(h.in(2, 0, 2)).is(Blocks.STONE), "a cell somebody filled was overwritten");
            check(!h.level.getBlockState(h.doorLower).getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && !PropertyLocks.get(h.level.getServer()).breached(h.doorLower, now + wait),
                "the picked street door was left open, or unlocked, in the building put back");
            check(h.level.getBlockEntity(h.in(2, 0, 4)) instanceof SignBlockEntity sign && sign.getFrontText().getMessage(0, false).getString().equals("Home sweet home"), "the sign came back without its words");
            check(!BurglaryDamage.store(h.level.getServer()).records.containsKey(h.building.key()), "the record was kept after the building was put back (it would be put back again)");
            check(h.level.getEntitiesOfClass(ItemEntity.class, new AABB(h.in(0, 0, 0)).inflate(12)).isEmpty(), "putting back dropped an item");
            check(carpet.is(Blocks.RED_CARPET), "the fixture's carpet is not a carpet");
            BurglaryDamage.resetForTest();
        }
        helper.succeed();
    }
    /**
     * An owner's changes are never the city's to put back (tenth pass): a thief's break before the house became somebody's home is recorded; once it is a home, the thief may break
     * nothing more there, what the owner changes is never recorded, and when the wait is over the city puts back nothing inside the home (not the thief's old break, not the owner's
     * change) and forgets the record, so nothing is put back after the owner moves out either.
     */
    @GameTest(template = "empty", batch = "uc_wreck_owner", timeoutTicks = 300)
    public static void anOwnersChangesAreNeverPutBackAndTheirHomeIsNeverBroken(GameTestHelper helper) {
        try (BuildingSecurityGameTests.House h = new BuildingSecurityGameTests.House(helper)) {
            furnish(h);
            BurglaryDamage.resetForTest();
            BurglaryDamage.store(h.level.getServer()).records.remove(h.building.key());
            ServerPlayer thief = thief(helper, "uc_wreck_d"), owner = thief(helper, "uc_wreck_owner");
            long now = LockService.clock(h.level.getServer());
            BurglaryDamage.grantForTest(thief, h.building, now);
            BurglaryDamage.breakFor(thief, h.level, h.building, h.in(4, 1, 4), now);   // the inner wall (and the torch on it), while nobody lives here
            check(BurglaryDamage.store(h.level.getServer()).records.get(h.building.key()).cells.size() == 2, "the thief's break was not recorded");
            // the house becomes the owner's home
            CrewLedger ledger = CrewService.ledger(h.level.getServer());
            check(ledger != null && !ledger.faulted(), "no Crew ledger in the test world");
            String id = "test:wreck_owner";
            CrewLedger.Residence home = ledger.registerResidence(id, "Wreck test home", "flat", h.level.dimension().location().toString(), "test", h.in(1, 0, 1), h.in(5, 3, 5), 10, -1);
            home.ownerType = CrewLedger.OwnerType.PLAYER;
            home.owner = owner.getUUID();
            try {
                check(BurglaryDamage.judge(thief, h.level, h.building, h.in(2, 0, 2), now) == BurglaryDamage.Verdict.HOME, "a thief may break things in somebody's home");
                // the owner takes up their carpet and builds a shelf where the thief's wall was: neither is a burglary, neither is recorded
                h.level.setBlock(h.in(2, 0, 2), Blocks.AIR.defaultBlockState(), 2);
                h.level.setBlock(h.in(4, 1, 4), Blocks.BOOKSHELF.defaultBlockState(), 2);
                check(BurglaryDamage.store(h.level.getServer()).records.get(h.building.key()).cells.size() == 2, "the owner's changes were recorded as damage");
                long wait = BurglaryDamage.RULES.restoreMinutes() * 60L * 20L;
                BurglaryDamage.fixClockForTest(now + 2 * wait);
                BurglaryDamage.restoreDue(h.level.getServer());
                check(h.level.getBlockState(h.in(2, 0, 2)).isAir(), "the carpet the owner took up was put back");
                check(h.level.getBlockState(h.in(4, 1, 4)).is(Blocks.BOOKSHELF), "the owner's shelf was replaced by the wall the thief broke");
                check(h.level.getBlockState(h.in(4, 2, 4)).isAir(), "the torch was put back inside somebody's home");
                check(!BurglaryDamage.store(h.level.getServer()).records.containsKey(h.building.key()), "the record was kept: it would be put back once the owner moves out");
                check(h.level.getEntitiesOfClass(ItemEntity.class, new AABB(h.in(0, 0, 0)).inflate(12)).isEmpty(), "something dropped (a belonging copied)");
            } finally {
                home.ownerType = CrewLedger.OwnerType.NONE;
                home.owner = null;
                ledger.unregisterResidence(id);
                BurglaryDamage.fixClockForTest(null);
                BurglaryDamage.resetForTest();
            }
        }
        helper.succeed();
    }
}
