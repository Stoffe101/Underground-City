package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Property intelligence (D-157), headless: the neighbourhood grade (its distribution, and that a graded tier carries its tier's lock, stash and alarm), what a glance at a street door tells
 * (a target, a home, a listing, whether the player could try it), that a glance and a casing are remembered and how the book is bounded and saved, and that the risk estimate rises with what
 * would notice a break-in. None of it shows the card under a real crosshair; the property scenario through a real client does that.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PropertyIntelGameTests {
    private PropertyIntelGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", "farmer");
        return player;
    }

    private static CityBuildings.Building building(BlockPos door, String classId, String lock, int tier, CityBuildings.Response response) {
        var security = new CityBuildings.SecurityClass(classId, lock, true, "", 0, "test house", response, tier, "");
        return new CityBuildings.Building("valewood", door.getX() >> 4, door.getZ() >> 4, "test", "test_building", 2, 0, door.getY() - 1, security);
    }

    /** A wooden door standing in the open at {@code lower}, facing north. */
    private static void door(GameTestHelper helper, BlockPos lower) {
        var level = helper.getLevel();
        for (int y = 0; y < 6; y++) for (int dz = -2; dz <= 2; dz++) level.setBlockAndUpdate(lower.offset(0, y, dz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(lower.below(), Blocks.STONE.defaultBlockState());
        var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    // ------------------------------------------------------------------------------------------------ the grade

    @GameTest(template = "empty")
    public static void theNeighbourhoodGradeLeansUpAtTheCentreAndDownAtTheEdge(GameTestHelper helper) {
        int centreUp = 0, edgeDown = 0, middleSame = 0, steps = 100;
        for (int i = 0; i < steps; i++) {
            double noise = i / (double) steps;
            int c = CityBuildings.gradeOffset(0.0, noise), m = CityBuildings.gradeOffset(0.5, noise), e = CityBuildings.gradeOffset(1.0, noise);
            check(Math.abs(c) <= 2 && Math.abs(m) <= 2 && Math.abs(e) <= 2, "a grade moved a tier by more than two");
            if (c > 0) centreUp++;
            if (e < 0) edgeDown++;
            if (m == 0) middleSame++;
            check(CityBuildings.gradeOffset(0.6, noise) < 2 && CityBuildings.gradeOffset(0.4, noise) > -2, "a building half way out moved two tiers");
        }
        check(centreUp >= 65 && edgeDown >= 65 && middleSame >= 85, "the grade's distribution is off: up at the centre " + centreUp + "%, down at the edge " + edgeDown + "%, same half way " + middleSame + "%");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGradedBuildingMeetsTheSecurityOfItsTier(GameTestHelper helper) {
        CityBuildings.SecurityClass flats = CityBuildings.INSTANCE.securityClass("flat_block");
        CityBuildings.SecurityClass vault = CityBuildings.INSTANCE.securityClass("vault");
        check(flats != null && vault != null && CityBuildings.INSTANCE.grade("flat_block").isPresent(), "the shipped classes have no grade");
        var region = CityRegions.INSTANCE.region("valewood").orElseThrow();
        Set<Integer> seen = new HashSet<>();
        for (long seed = 1; seed <= 400; seed++) {
            for (int[] at : new int[][] {{region.centerChunkX(), region.centerChunkZ()}, {region.centerChunkX() + region.cityRadius() / 16, region.centerChunkZ()}}) {
                CityBuildings.SecurityClass g = CityBuildings.INSTANCE.graded(flats, "valewood", at[0], at[1], seed);
                seen.add(g.tier());
                check(g.id().equals("flat_block") && g.tier() >= 1 && g.tier() <= 3, "an apartment block left its grade: tier " + g.tier());
                if (g.tier() == 1) check(g.lockProfile().equals("latch") && g.response() == null && g.safeProfile().equals("safe"), "a shabby apartment block has the wrong security: " + g);
                if (g.tier() == 3) check(g.lockProfile().equals("deadbolt") && g.stashPolicy().endsWith("apartment_affluent") && g.response() != null, "a comfortable apartment block has the wrong security: " + g);
                check(CityBuildings.INSTANCE.graded(vault, "valewood", at[0], at[1], seed).tier() == 6, "the vault was graded");
            }
        }
        check(seen.containsAll(List.of(1, 2, 3)), "the grade never gave apartment blocks all of their tiers: " + seen);
        // every shipped grade stays inside what its category has security for
        for (var c : CityBuildings.INSTANCE.classes().values()) {
            var grade = CityBuildings.INSTANCE.grade(c.id());
            if (grade.isEmpty() || c.tier() <= 0) continue;
            check(grade.get().min() <= c.tier() && c.tier() <= grade.get().max(), c.id() + "'s grade leaves out its own tier");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the glance

    @GameTest(template = "empty")
    public static void aGlanceAtAStreetDoorSaysWhatThePlaceIsAndIsRemembered(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
        door(helper, lower);
        CityBuildings.Building house = building(lower, "flat_block", "deadbolt", 3, null);
        CityBuildings.Building fortress = building(lower, "flat_tower", "high_security", 5, null);
        CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(house));
        BuildingGuard.roofReach = 6;   // a test world is crowded: other tests build above this one
        try {
            FakePlayer thief = player(helper, "uc_glance_a");
            thief.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 1.5);
            Optional<PropertyIntel.Peek> first = PropertyIntel.peek(thief, helper.getLevel(), lower.above());
            check(first.isPresent(), "a glance at a street door told nothing");
            PropertyIntel.Peek p = first.get();
            check(p.status() == PropertyIntel.Status.TARGET && p.tier() == 3 && p.door().equals(lower) && p.access() == 3 && p.layer() == -1 && !p.address().isEmpty(),
                "the glance is wrong: " + p);
            PropertyIntel.Entry remembered = PropertyIntel.book(thief.server).get(thief.getUUID(), house.key());
            check(remembered != null && remembered.layer() == -1 && remembered.tier() == 3, "a glance was not remembered: " + remembered);
            thief.getInventory().add(new ItemStack(UndergroundCity.item("lockpick")));
            check(PropertyIntel.peek(thief, helper.getLevel(), lower).orElseThrow().access() == 0, "with a lockpick the door still reads as out of reach");
            Casing.caseDoor(thief, helper.getLevel(), house, lower);
            check(PropertyIntel.book(thief.server).get(thief.getUUID(), house.key()).layer() == 0, "a beginner's casing was not remembered as one");
            check(PropertyIntel.peek(thief, helper.getLevel(), lower).orElseThrow().layer() == 0, "the glance does not know the place was cased");
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(fortress));
            check(PropertyIntel.peek(thief, helper.getLevel(), lower).orElseThrow().access() == 1, "a high-security door did not ask for a Pro Pick Set");
            // too far: no answer
            thief.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 12.5);
            check(PropertyIntel.peek(thief, helper.getLevel(), lower).isEmpty(), "a glance reached twelve blocks");
            // a block that is no door: nothing
            check(PropertyIntel.peek(thief, helper.getLevel(), lower.below()).isEmpty(), "a glance at a wall told something");
        } finally {
            CityBuildings.INSTANCE.overrideForTest(null);
            BuildingGuard.roofReach = BuildingGuard.ROOF_REACH;
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aHomeIsNeverReadAsATarget(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
        door(helper, lower);
        CityBuildings.Building house = building(lower, "flat_block", "deadbolt", 3, null);
        CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(house));
        CrewLedger ledger = CrewService.ledger(helper.getLevel().getServer());
        String id = "test:glance_home_" + Integer.toHexString(UUID.randomUUID().hashCode());
        try {
            check(ledger != null && !ledger.faulted(), "no Crew ledger to register a home with");
            CrewLedger.Residence home = ledger.registerResidence(id, "7 Test Row", "house", "minecraft:overworld", "test", lower.offset(-1, 0, -1), lower.offset(1, 3, 1), 10, -1);
            FakePlayer visitor = player(helper, "uc_glance_v");
            FakePlayer owner = player(helper, "uc_glance_o");
            visitor.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 1.5);
            owner.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 1.5);
            check(PropertyIntel.peek(visitor, helper.getLevel(), lower).orElseThrow().status() == PropertyIntel.Status.TO_LET, "a vacant listing did not read as to let");
            home.ownerType = CrewLedger.OwnerType.PLAYER;
            home.owner = owner.getUUID();
            check(PropertyIntel.peek(owner, helper.getLevel(), lower).orElseThrow().status() == PropertyIntel.Status.YOURS, "a player's own home did not read as theirs");
            PropertyIntel.Peek other = PropertyIntel.peek(visitor, helper.getLevel(), lower).orElseThrow();
            check(other.status() == PropertyIntel.Status.PLAYER_HOME && other.tier() == 0, "somebody else's home read as " + other.status() + " tier " + other.tier());
            check(PropertyIntel.book(visitor.server).get(visitor.getUUID(), house.key()) == null, "a player's home went into a thief's book");
            check(Casing.inspect(visitor, helper.getLevel(), lower) != null, "the Inspect key cased a player's home");
        } finally {
            CityBuildings.INSTANCE.overrideForTest(null);
            CrewLedger.Residence left = ledger == null ? null : ledger.residence(id);
            if (left != null) { left.ownerType = CrewLedger.OwnerType.NONE; left.owner = null; ledger.unregisterResidence(id); }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the risk

    @GameTest(template = "empty")
    public static void theRiskNowRisesWithWhatWouldNoticeABreakIn(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
        door(helper, lower);
        CityBuildings.Building quiet = building(lower, "flat_block", "latch", 1, null);
        CityBuildings.Building wired = building(lower, "flat_block", "secured", 4, new CityBuildings.Response(1.0, 60, 2, 120));
        FakePlayer thief = player(helper, "uc_risk_a");
        thief.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 1.5);
        PropertyIntel.Risk calm = PropertyIntel.risk(thief, helper.getLevel(), quiet, lower, 0, 0, false);
        PropertyIntel.Risk home = PropertyIntel.risk(thief, helper.getLevel(), quiet, lower, 2, 0, false);
        PropertyIntel.Risk watchful = PropertyIntel.risk(thief, helper.getLevel(), quiet, lower, 2, 0, true);
        PropertyIntel.Risk cameras = PropertyIntel.risk(thief, helper.getLevel(), quiet, lower, 0, 2, false);
        PropertyIntel.Risk alarm = PropertyIntel.risk(thief, helper.getLevel(), wired, lower, 0, 0, false);
        check(calm.word().equals("Low") && calm.chance() < PropertyIntel.LOW, "an empty shabby house with no alarm is not low risk: " + calm);
        check(home.chance() > calm.chance() && watchful.chance() > home.chance(), "people at home, and a watchful household, did not raise the risk: " + calm + " " + home + " " + watchful);
        check(cameras.chance() > calm.chance() && cameras.reasons().stream().anyMatch(r -> r.contains("camera")), "cameras did not raise the risk: " + cameras);
        check(alarm.word().equals("Very high") && alarm.reasons().get(0).contains("alarm"), "a certain alarm is not very high risk: " + alarm);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the book

    @GameTest(template = "empty")
    public static void theBookKeepsMarkedPlacesAndForgetsTheOldestAndSurvivesASave(GameTestHelper helper) {
        PropertyIntel.Book book = new PropertyIntel.Book();
        UUID who = UUID.randomUUID();
        CityBuildings.Building first = new CityBuildings.Building("valewood", 1000, 1000, "test", "t", 2, 0, 70, CityBuildings.INSTANCE.securityClass("cottage"));
        book.seen(who, first, new BlockPos(16000, 70, 16000), "1 Test Lane", "residential", "cottage", 1);
        check(book.mark(who, first.key(), true), "a remembered place could not be marked");
        for (int i = 0; i < PropertyIntel.MAX_PLACES + 20; i++) {
            CityBuildings.Building b = new CityBuildings.Building("ironhaven", i, -i, "test", "t", 2, 0, 70, CityBuildings.INSTANCE.securityClass("workshop"));
            book.seen(who, b, new BlockPos(i * 16, 70, -i * 16), (i + 2) + " Test Lane", "industrial", "workshop", 10 + i);
        }
        List<PropertyIntel.Entry> kept = book.of(who);
        check(kept.size() == PropertyIntel.MAX_PLACES, "the book keeps " + kept.size() + " places, not " + PropertyIntel.MAX_PLACES);
        check(book.get(who, first.key()) != null && book.get(who, first.key()).marked(), "a marked place was forgotten to make room");
        CompoundTag saved = book.save(new CompoundTag());
        PropertyIntel.Book loaded = PropertyIntel.Book.load(saved);
        check(loaded.of(who).size() == kept.size() && loaded.get(who, first.key()).marked() && loaded.get(who, first.key()).address().equals("1 Test Lane"), "the book did not survive a save");
        check(loaded.forget(who, first.key()) && loaded.get(who, first.key()) == null, "a place could not be forgotten");
        check(loaded.of(UUID.randomUUID()).isEmpty(), "somebody else's book is not empty");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theTierWordsAndNumeralsAgree(GameTestHelper helper) {
        Map<Integer, String> numerals = Map.of(1, "I", 2, "II", 3, "III", 4, "IV", 5, "V", 6, "VI");
        numerals.forEach((tier, numeral) -> check(PropertyIntel.roman(tier).equals(numeral), "tier " + tier + " reads " + PropertyIntel.roman(tier)));
        check(PropertyIntel.riskWord(0.05).equals("Low") && PropertyIntel.riskWord(0.2).equals("Moderate") && PropertyIntel.riskWord(0.4).equals("High") && PropertyIntel.riskWord(0.9).equals("Very high"),
            "the risk words moved");
        check(PropertyIntel.categoryWord("residential").equals("Residential") && PropertyIntel.categoryWord("special").equals("High-value"), "the category words moved");
        helper.succeed();
    }
}
