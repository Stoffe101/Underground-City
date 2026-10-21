package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.Tags;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * The regenerating quarry (D-123). The data cases need nothing but the datapack; the behaviour case builds the real district in the sky (the only place a test is sure not to meet a block entity or a
 * neighbour), mines veins and drives the pass with a clock of its own, then with the real active clock, to show: a mined vein grows back after its interval of active time and not a tick before; nothing
 * grows back while the clock stands still (offline time never counts); a player standing in the cell holds it; a block somebody built there is never overwritten; a clock that went backwards pays nothing
 * early; the saved state survives a round trip; undoing the district leaves nothing behind. It does not show a person mining it, nor how the pit looks.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResourceSitesGameTests {
    private static ResourceSiteCatalog.Site quarry() {
        var site = ResourceSiteCatalog.INSTANCE.find("ironhaven_quarry");
        check(site != null, "the quarry site did not load from data");
        return site;
    }

    @GameTest(template = "empty")
    public static void theQuarryDataIsBoundedAndEveryVeinIsABaseMetalOreInsideItsDistrict(GameTestHelper helper) {
        var site = quarry();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(site.district());
        check(district != null, "the site's district is missing: " + site.district());
        check(site.intervalMinutes() >= ResourceSiteCatalog.MIN_INTERVAL_MINUTES && site.veins().size() <= ResourceSiteCatalog.MAX_VEINS, "the site is outside its bounds");
        check(site.oresPerHourCeiling() <= 60.0, "the site could out-supply the shelves: " + site.oresPerHourCeiling() + " ores an active hour");
        var allowed = java.util.Set.of(Blocks.IRON_ORE, Blocks.COAL_ORE, Blocks.COPPER_ORE);
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (var vein : site.veins()) {
            check(vein.block().defaultBlockState().is(Tags.Blocks.ORES) && allowed.contains(vein.block()), "a vein is not a base-metal ore: " + vein.block());
            BlockPos at = vein.at();
            check(at.getX() >= district.min().getX() && at.getX() <= district.max().getX() && at.getY() >= district.min().getY() && at.getY() <= district.max().getY()
                && at.getZ() >= district.min().getZ() && at.getZ() <= district.max().getZ(), "a vein lies outside its district: " + at);
            check(seen.add(at), "two veins at " + at);
            check(district.ops().stream().anyMatch(op -> op.kind().equals("block") && op.a().equals(at) && op.text().equals(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(vein.block()).toString())),
                "the district does not place the ore of the vein at " + at);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_district", timeoutTicks = 400)
    public static void aMinedVeinGrowsBackInActiveTimeOnlyAndNeverOverwritesABuilder(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var site = quarry();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(site.district());
        BlockPos arena = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos origin = new BlockPos(arena.getX() - 12, level.getMaxBuildHeight() - 40, arena.getZ() - 8);
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        DistrictBuilder.forgetForTest(level, district.id());
        ResourceSites.resetForTest();
        Runnable cleanup = () -> {
            if (DistrictBuilder.isBuilt(level, district.id())) DistrictBuilder.undo(level, district);
            DistrictBuilder.forgetForTest(level, district.id());
            ResourceSites.state(level).depleted.clear();
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
        };
        helper.runAfterDelay(40, () -> {
            try {
                DistrictBuilder.build(level, district, origin);
                long interval = site.intervalTicks();
                // the pit stands as its data says: every vein is its ore, and a stone neighbour is not
                for (var vein : site.veins()) check(level.getBlockState(origin.offset(vein.at())).is(vein.block()), "a vein is not in the built pit: " + vein.at());
                var first = site.veins().get(0);
                BlockPos cell = origin.offset(first.at());
                long now = 1_000_000L;
                var full = ResourceSites.reconcile(level, site, origin, now);
                check(full.standing() == site.veins().size() && full.depleted() == 0 && full.waiting() == 0, "a fresh pit reported depletion: " + full);

                // mined: noticed at once, grown back at exactly the interval and not before
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                var noticed = ResourceSites.reconcile(level, site, origin, now);
                check(noticed.depleted() == 1 && noticed.waiting() == 1, "a mined vein was not noticed: " + noticed);
                ResourceSites.reconcile(level, site, origin, now + interval - 1);
                check(level.getBlockState(cell).isAir(), "a vein grew back one tick early");
                var back = ResourceSites.reconcile(level, site, origin, now + interval);
                check(level.getBlockState(cell).is(first.block()) && back.regrown() == 1 && back.depleted() == 0, "a vein did not grow back at its interval: " + back);

                // a clock that stands still pays nothing, however many passes are made (offline time never counts)
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                ResourceSites.reconcile(level, site, origin, now + interval);
                for (int pass = 0; pass < 50; pass++) ResourceSites.reconcile(level, site, origin, now + interval);
                check(level.getBlockState(cell).isAir(), "a vein grew back although no active time passed");

                // the real tick handler reads the real active clock: it moves only when the ledger is advanced, which the world does only while somebody plays
                long real = LockService.clock(level.getServer());
                ResourceSites.pass(level.getServer());
                for (int pass = 0; pass < 20; pass++) ResourceSites.pass(level.getServer());
                check(level.getBlockState(cell).isAir(), "the real pass grew a vein back without active time");
                WorkOrderLedger.get(level.getServer()).advance(interval);
                ResourceSites.pass(level.getServer());
                check(level.getBlockState(cell).is(first.block()) && LockService.clock(level.getServer()) == real + interval, "the real pass did not grow the vein back after an active interval");

                // a player standing in the cell holds it; leaving releases it
                TownspersonEntity miner = UndergroundCity.TOWNSPERSON.get().create(level);
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                long t0 = now + 10 * interval;
                ResourceSites.reconcile(level, site, origin, t0);
                miner.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
                level.addFreshEntity(miner);
                var held = ResourceSites.reconcile(level, site, origin, t0 + interval);
                check(level.getBlockState(cell).isAir() && held.regrown() == 0, "a vein grew into somebody's body");
                miner.moveTo(cell.getX() + 50.5, cell.getY(), cell.getZ() + 50.5, 0, 0);
                ResourceSites.reconcile(level, site, origin, t0 + interval + 1);
                miner.discard();
                check(level.getBlockState(cell).is(first.block()), "a vein stayed held after the one in it left");

                // a builder's block is never overwritten, and the wait starts afresh when the cell is air again
                level.setBlock(cell, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                long t1 = t0 + 5 * interval;
                ResourceSites.reconcile(level, site, origin, t1);
                ResourceSites.reconcile(level, site, origin, t1 + 3 * interval);
                check(level.getBlockState(cell).is(Blocks.OAK_PLANKS), "a block somebody placed in a vein cell was overwritten");
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                ResourceSites.reconcile(level, site, origin, t1 + 3 * interval + 1);
                ResourceSites.reconcile(level, site, origin, t1 + 3 * interval + interval - 1);
                check(level.getBlockState(cell).isAir(), "a cell that was a builder's grew back on the old clock");
                ResourceSites.reconcile(level, site, origin, t1 + 3 * interval + 1 + interval);
                check(level.getBlockState(cell).is(first.block()), "a cell did not grow back a full interval after it was freed");

                // a clock that went backwards (a restored backup) restarts the wait and never pays early
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                long t2 = t1 + 100 * interval;
                ResourceSites.reconcile(level, site, origin, t2);
                ResourceSites.reconcile(level, site, origin, t2 - 5 * interval);
                ResourceSites.reconcile(level, site, origin, t2 - 5 * interval + interval - 1);
                check(level.getBlockState(cell).isAir(), "a clock that went backwards paid out early");

                // the saved state survives a round trip
                Map<Integer, Long> before = Map.copyOf(ResourceSites.state(level).depleted.get(site.id()));
                CompoundTag saved = ResourceSites.state(level).save(new CompoundTag());
                var restored = ResourceSites.State.load(saved);
                check(restored.depleted.get(site.id()).equals(before) && !before.isEmpty(), "the depletion record did not survive being saved: " + before + " vs " + restored.depleted);

                // undoing the district leaves the world as it was; the pass then has nothing to do and does not fail
                DistrictBuilder.undo(level, district);
                ResourceSites.pass(level.getServer());
                check(level.getBlockState(cell).isAir(), "undoing the district did not remove the pit");
                helper.succeed();
            } catch (AssertionError error) {
                helper.fail(String.valueOf(error.getMessage()));   // a throwing lambda would end the server loop; helper.fail records the failure
            } finally {
                cleanup.run();
            }
        });
    }
}
