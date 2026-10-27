package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Staff-only zones (D-159), read from a small shop built in the sky in a chunk of its own: a front room behind a street door that leads out to the open air, a back room behind an inside door.
 * The front room is the public floor, the inside door is a staff door, a barrel out front is on the floor and one in the back is not. Whether the shop is open (its shopkeeper in) and what the
 * door guard does with a staff door are a real client's to show (the organic burglary scenario).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StaffZoneGameTests {
    private StaffZoneGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void door(ServerLevel level, BlockPos lower, Direction facing) {
        BlockState state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
        level.setBlock(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    @GameTest(template = "empty")
    public static void theFrontRoomIsTheFloorAndTheBackRoomIsStaffOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        int cx = (arena.getX() + 3000) >> 4, cz = arena.getZ() >> 4, x0 = cx * 16, z0 = cz * 16, y = level.getMaxBuildHeight() - 140;
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        try {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                level.setBlock(new BlockPos(x0 + x, y, z0 + z), stone, 2);
                for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(x0 + x, y + dy, z0 + z), air, 2);
            }
            // walls round x 2..12, z 2..12, a roof over them, an inside wall at z = 8
            for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) {
                boolean wall = x == 2 || x == 12 || z == 2 || z == 12 || z == 8;
                for (int dy = 1; dy <= 3; dy++) if (wall) level.setBlock(new BlockPos(x0 + x, y + dy, z0 + z), stone, 2);
                level.setBlock(new BlockPos(x0 + x, y + 4, z0 + z), stone, 2);
            }
            BlockPos street = new BlockPos(x0 + 7, y + 1, z0 + 2), inside = new BlockPos(x0 + 7, y + 1, z0 + 8);
            door(level, street, Direction.NORTH);
            door(level, inside, Direction.SOUTH);
            BlockPos frontBarrel = new BlockPos(x0 + 4, y + 1, z0 + 4), backBarrel = new BlockPos(x0 + 4, y + 1, z0 + 10);
            level.setBlock(frontBarrel, Blocks.BARREL.defaultBlockState(), 2);
            level.setBlock(backBarrel, Blocks.BARREL.defaultBlockState(), 2);
            var security = new CityBuildings.SecurityClass("test_shop", "deadbolt", true, "", 0, "test shop", new CityBuildings.Response(0, 60, 1, 60), 2, "");
            CityBuildings.Building b = new CityBuildings.Building("valewood", cx, cz, "test", "test_shop", 1, 0, y + 1, security);
            StaffZones.resetForTest();

            check(BuildingGuard.exterior(level, street, level.getBlockState(street)), "the street door is not taken for a street door");
            check(!BuildingGuard.exterior(level, inside, level.getBlockState(inside)), "the inside door is taken for a street door");
            StaffZones.Zones z = StaffZones.of(level, b);
            BlockPos front = new BlockPos(x0 + 7, y + 1, z0 + 5), back = new BlockPos(x0 + 7, y + 1, z0 + 10);
            check(z.isPublic(front) && z.isPublic(new BlockPos(x0 + 11, y + 1, z0 + 7)), "the front room is not the public floor");
            check(!z.isPublic(back), "the back room is on the public floor");
            check(z.isStaffDoor(inside) && !z.isStaffDoor(street), "the doors are not told apart: staff " + z.staffDoors().size());
            check(StaffZones.onPublicFloor(level, b, frontBarrel) && !StaffZones.onPublicFloor(level, b, backBarrel), "the barrels are not told apart");
            check(StaffZones.inStaffArea(level, b, back) && !StaffZones.inStaffArea(level, b, front), "the staff area is not where a person stands in the back");
            // the pavement under an awning outside the street door, and the doorway itself, are not "in the back" (organic run 3 was told to leave at a bakery's counter)
            for (int x = 5; x <= 9; x++) level.setBlock(new BlockPos(x0 + x, y + 4, z0 + 1), stone, 2);
            StaffZones.resetForTest();
            BlockPos pavement = new BlockPos(x0 + 7, y + 1, z0 + 1);
            check(StaffZones.roofed(level, pavement) && !StaffZones.inStaffArea(level, b, pavement), "the pavement under an awning is taken for a staff area");
            check(!StaffZones.inStaffArea(level, b, street) && !StaffZones.inStaffArea(level, b, inside), "a doorway is taken for a staff area");
            z = StaffZones.of(level, b);
            check(StaffZones.onPublicSide(level, b, inside, Vec3.atBottomCenterOf(front)) && !StaffZones.onPublicSide(level, b, inside, Vec3.atBottomCenterOf(back)), "the sides of the staff door are not told apart");
            check(StaffZones.of(level, b) == z && StaffZones.computed == 1, "the zones were read again within the minute");

            // a redstone signal (a pressure plate or a button beside it) never holds a staff door open: it is shut again, the power left as it is (the fourth pass's open defect)
            for (BlockPos half : new BlockPos[] {inside, inside.above()}) level.setBlock(half, level.getBlockState(half).setValue(DoorBlock.OPEN, true).setValue(DoorBlock.POWERED, true), 2);
            check(StaffZones.shutPowered(level, b) == 1 && !level.getBlockState(inside).getValue(DoorBlock.OPEN) && level.getBlockState(inside).getValue(DoorBlock.POWERED),
                "a staff door held open by redstone was not shut");
            check(StaffZones.shutPowered(level, b) == 0, "a shut staff door was shut again");
            for (BlockPos half : new BlockPos[] {street, street.above()}) level.setBlock(half, level.getBlockState(half).setValue(DoorBlock.OPEN, true).setValue(DoorBlock.POWERED, true), 2);
            check(StaffZones.shutPowered(level, b) == 0 && level.getBlockState(street).getValue(DoorBlock.OPEN), "a street door was taken for a staff door");

            // with the inside door gone (an opening), the back room joins the floor
            level.setBlock(inside.above(), air, 2);
            level.setBlock(inside, air, 2);
            StaffZones.resetForTest();
            check(StaffZones.of(level, b).isPublic(back) && StaffZones.onPublicFloor(level, b, backBarrel), "an open doorway did not join the back room to the floor");

            // Modern Life's floor mat counts as "solid" for blocksMotion (it copies bamboo's properties) though it is a sixteenth of a block high: mats just inside the street door must
            // not cut the public floor off (the corner store's whole floor was staff only, unmarked run 2, eighth pass)
            var mat = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse("modernlife:floor_mat"));
            if (mat != null && mat != Blocks.AIR) {
                BlockPos inDoor = new BlockPos(x0 + 7, y + 1, z0 + 3);
                for (int x = 6; x <= 8; x++) level.setBlock(new BlockPos(x0 + x, y + 1, z0 + 3), mat.defaultBlockState(), 2);
                StaffZones.resetForTest();
                com.mojang.logging.LogUtils.getLogger().info("[UC-TEST] a Modern Life floor mat: blocksMotion {}, collision height {}", level.getBlockState(inDoor).blocksMotion(),
                    level.getBlockState(inDoor).getCollisionShape(level, inDoor).max(Direction.Axis.Y));
                check(StaffZones.clear(level, inDoor) && StaffZones.walkable(level, inDoor), "a floor mat is taken for a wall");
                z = StaffZones.of(level, b);
                check(z.isPublic(inDoor) && z.isPublic(front) && !StaffZones.inStaffArea(level, b, front), "floor mats inside the street door cut the public floor off");
            }
        } finally {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int dy = 0; dy <= 6; dy++) level.setBlock(new BlockPos(x0 + x, y + dy, z0 + z), air, 2);
            StaffZones.resetForTest();
        }
        helper.succeed();
    }
}
