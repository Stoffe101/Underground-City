package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Thief track and the burglary it feeds (D-143 to D-145), headless: the tree and its points (never the career's), the save, the XP rules that cannot be farmed, what a search can find by a
 * building's tier and when a spot refills, the fences' rates with the Thief skills, spool pins and a careful hand, the tools and skills a lock asks for, casing, and raking. None of it shows a
 * person breaking into a real house; the real-client burglary scenario does that.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThiefGameTests {
    private ThiefGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer player(GameTestHelper helper, String name, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    private static CitizenState.Citizen citizen(FakePlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(); }

    private static CityBuildings.Building building(BlockPos door, String lock, int tier, CityBuildings.Response response) {
        var security = new CityBuildings.SecurityClass("test_tier" + tier, lock, true, "", 0, "test house", response, tier, "");
        return new CityBuildings.Building("valewood", door.getX() >> 4, door.getZ() >> 4, "test", "test_building", 2, 0, door.getY() - 1, security);
    }

    // ------------------------------------------------------------------------------------------------ the tree and its points

    @GameTest(template = "empty")
    public static void theThiefTreeLoadsAndIsWhollyAffordableAtTheTopLevel(GameTestHelper helper) {
        List<SkillNode> tree = SkillCatalog.INSTANCE.tree(SkillNode.THIEF);
        check(tree.size() == 16, "the Thief tree has " + tree.size() + " skills");
        int cost = SkillRules.pointsSpent(tree, tree.stream().map(SkillNode::id).collect(java.util.stream.Collectors.toSet()));
        check(cost <= SkillRules.thiefPointsEarned(ProgressionRules.THIEF_MAX_LEVEL), "the whole Thief tree costs " + cost + ", more than the top level earns");
        for (SkillNode node : tree) check(!node.effectText().isBlank() && node.thief(), node.id() + " has no effect line or is not a Thief skill");
        check(SkillCatalog.INSTANCE.find("thief.casing") != null && SkillCatalog.INSTANCE.find("farmer.fundamentals") != null, "the catalogue no longer finds skills of both kinds");
        // a Thief skill may not carry a career's effect: the tree is open to every career
        SkillNode bad = new SkillNode("thief.bad", "thief", "entry", "Bad", "Pays like a trade.", 1, 2, List.of(), 0, List.of(), 0, "", 6, 0, 0, 0, "+6% on contracts");
        boolean refused = false;
        try { SkillRules.validateThiefTree(List.of(bad)); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "a Thief skill with a career payout was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void thiefSkillsSpendThiefPointsAndNeverCareerPoints(GameTestHelper helper) {
        FakePlayer player = player(helper, "uc_thief_points", "smith");
        CitizenState state = CitizenState.get(player.server);
        state.addCareerXp(player.getUUID(), ProgressionRules.xpAtLevel(5));          // career level 5: 4 career points
        state.addThiefXp(player.getUUID(), ProgressionRules.thiefXpAtLevel(3));        // Thief level 3: 2 Thief points
        CitizenState.Citizen c = citizen(player);
        check(c.careerLevel() == 5 && c.thiefLevel() == 3, "levels " + c.careerLevel() + " and " + c.thiefLevel());
        check(SkillService.pointsAvailable(c) == 4 && SkillService.thiefPointsAvailable(c) == 2, "points " + SkillService.pointsAvailable(c) + " and " + SkillService.thiefPointsAvailable(c));
        SkillService.learn(player, "thief.casing");
        SkillService.learn(player, "thief.steady_hands");
        check(SkillService.pointsAvailable(c) == 4, "learning Thief skills spent career points: " + SkillService.pointsAvailable(c));
        check(SkillService.thiefPointsAvailable(c) == 0, "the Thief points were not spent: " + SkillService.thiefPointsAvailable(c));
        String blocker = SkillService.blocker(c, SkillCatalog.INSTANCE.find("thief.raking"));
        check(blocker != null && blocker.contains("Thief level 4"), "Raking at Thief level 3 should wait for level 4: " + blocker);
        SkillService.learn(player, "smith.fundamentals");                               // the career's tree is untouched by the Thief's
        check(SkillService.pointsAvailable(c) == 3 && SkillService.thiefPointsAvailable(c) == 0, "career and Thief points mixed");
        boolean refused = false;
        try { SkillService.learn(player, "thief.soft_steps"); } catch (IllegalStateException expected) { refused = expected.getMessage().contains("Thief point"); }
        check(refused, "a Thief skill was learned with no Thief points left (or the reason did not name Thief points)");
        // every career can follow it
        for (String career : ProgressionRules.CAREERS) {
            FakePlayer other = player(helper, "uc_thief_" + career, career);
            CitizenState.get(other.server).addThiefXp(other.getUUID(), ProgressionRules.thiefXpAtLevel(2));
            check(SkillService.blocker(citizen(other), SkillCatalog.INSTANCE.find("thief.casing")) == null, "a " + career + " cannot learn Casing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSaveBeforeTheThiefTrackLoadsAsANewThiefAndThiefProgressSurvivesASave(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        CompoundTag record = new CompoundTag();
        record.putUUID("player", id); record.putString("city", "ironhaven"); record.putString("career", "gunsmith");
        CompoundTag career = new CompoundTag(); career.putInt("gunsmith", 2000); record.put("career_xp", career);
        ListTag skills = new ListTag(); skills.add(net.minecraft.nbt.StringTag.valueOf("gunsmith.fundamentals")); record.put("skills", skills);
        CompoundTag root = new CompoundTag(); ListTag list = new ListTag(); list.add(record); root.put("citizens", list);
        CitizenState old = CitizenState.load(root);
        CitizenState.Citizen c = old.find(id).orElseThrow();
        check(c.thiefXp() == 0 && c.thiefLevel() == 1 && c.skills().contains("gunsmith.fundamentals") && c.careerXp() == 2000, "an older record did not load as it was, with a new Thief track");
        old.addThiefXp(id, 1000);
        old.unlockSkill(id, "thief.casing");
        CitizenState again = CitizenState.load(old.save(new CompoundTag()));
        CitizenState.Citizen d = again.find(id).orElseThrow();
        check(d.thiefXp() == 1000 && d.thiefLevel() == 4 && d.skills().contains("thief.casing") && d.skills().contains("gunsmith.fundamentals") && d.careerXp() == 2000,
            "Thief progress did not survive a save: " + d.thiefXp() + " " + d.skills());
        check(ProgressionRules.thiefLevelForXp(ProgressionRules.thiefXpAtLevel(25) + 10_000) == 25 && ProgressionRules.thiefLevelForXp(89) == 1 && ProgressionRules.thiefLevelForXp(90) == 2,
            "the Thief curve is not 90 x (level - 1)^2 capped at 25");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ XP that cannot be farmed

    @GameTest(template = "empty")
    public static void gettingIntoABuildingPaysOncePerThiefByItsTier(GameTestHelper helper) {
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building house = building(door, "deadbolt", 3, null);
        FakePlayer a = player(helper, "uc_thief_entry_a", "farmer"), b = player(helper, "uc_thief_entry_b", "vintner");
        check(Thievery.entered(a, house) == 3 * Thievery.ENTRY_XP_PER_TIER, "the first entry of a tier-3 house did not pay " + 3 * Thievery.ENTRY_XP_PER_TIER);
        check(Thievery.entered(a, house) == 0, "the same thief was paid twice for the same house");
        check(Thievery.entered(b, house) == 3 * Thievery.ENTRY_XP_PER_TIER, "another thief's first time was not paid");
        check(Thievery.crackedSafe(a, door.above(), 4) == 4 * Thievery.SAFE_XP_PER_TIER && Thievery.crackedSafe(a, door.above(), 4) == 0, "a safe paid twice or not at all");
        check(Thievery.escaped(a, 77L, 2) > 0 && Thievery.escaped(a, 77L, 2) == 0, "one escape paid twice");
        check(citizen(a).thiefXp() == 3 * Thievery.ENTRY_XP_PER_TIER + 4 * Thievery.SAFE_XP_PER_TIER + 2 * Thievery.ESCAPE_XP_PER_TIER, "the XP total is " + citizen(a).thiefXp());
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ searching

    private static BurglarySearch.Kind bedside() {
        BurglarySearch.Kind kind = BurglarySearch.LOADER.kinds().get("bedside");
        if (kind == null) throw new AssertionError("the bedside kind of furniture is not loaded");
        return kind;
    }

    @GameTest(template = "empty")
    public static void aSearchFindsOnlyWhatTheBuildingsTierAllows(GameTestHelper helper) {
        BurglarySearch.Kind kind = bedside();
        RandomSource random = RandomSource.create(42);
        int emptyLow = 0, emptyHigh = 0, valuableLow = 0, necklaces = 0;
        Set<String> tierTwoUp = Set.of("underground_city:wristwatch", "underground_city:smartphone", "underground_city:pocket_watch", "underground_city:gold_ring", "underground_city:tablet",
            "underground_city:jewellery_box", "underground_city:pearl_necklace", "underground_city:designer_handbag", "minecraft:clock", "minecraft:emerald");
        for (int i = 0; i < 2000; i++) {
            List<ItemStack> low = BurglarySearch.roll(kind, 1, "house", random);
            if (low.isEmpty()) emptyLow++;
            for (ItemStack s : low) if (tierTwoUp.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) valuableLow++;
            List<ItemStack> high = BurglarySearch.roll(kind, 5, "house", random);
            if (high.isEmpty()) emptyHigh++;
            for (ItemStack s : high) if (s.is(UndergroundCity.item("pearl_necklace"))) necklaces++;
        }
        check(valuableLow == 0, "a shabby night stand gave " + valuableLow + " finds of a better home");
        check(emptyLow > emptyHigh * 2, "a shabby drawer is not emptier than a wealthy one: " + emptyLow + " against " + emptyHigh);
        check(necklaces > 20, "a wealthy bedroom almost never held a necklace: " + necklaces);
        // the use of a building limits what is there: rare vinyl is a home's or a club's, never an office's bookshelf
        BurglarySearch.Kind books = BurglarySearch.LOADER.kinds().get("books");
        for (int i = 0; i < 500; i++) for (ItemStack s : BurglarySearch.roll(books, 4, "office", random)) check(!s.is(UndergroundCity.item("rare_vinyl")), "an office bookshelf held a record");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSearchedSpotStaysEmptyUntilItRefillsInActiveTime(GameTestHelper helper) {
        BurglarySearch.Records records = new BurglarySearch.Records();
        long refill = BurglarySearch.LOADER.refillTicks();
        long pos = helper.absolutePos(new BlockPos(3, 2, 3)).asLong();
        check(records.ready(pos, 1000, refill), "an unsearched spot is not ready");
        records.mark(pos, 1000);
        check(!records.ready(pos, 1000 + refill - 1, refill), "a spot refilled before its time");
        check(records.ready(pos, 1000 + refill, refill), "a spot did not refill after its time");
        BurglarySearch.Records loaded = BurglarySearch.Records.load(records.save(new CompoundTag()));
        check(!loaded.ready(pos, 1000 + refill - 1, refill), "a searched spot forgot it was searched across a save");
        check(refill >= 72_000L, "a spot refills in less than an hour of active play: " + refill);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aTwoBlockPieceOfFurnitureIsSearchedOnceByItsLowerHalf(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var upper = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        var lower = upper.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        var stairs = net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
        check(BurglarySearch.anchor(upper, pos).equals(pos.below()), "the upper half of a two-block piece is a second searchable spot");
        check(BurglarySearch.anchor(lower, pos).equals(pos), "the lower half moved");
        check(BurglarySearch.anchor(stairs, pos).equals(pos), "an upside-down block (half=top) was taken for the top of a two-block piece");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ fences

    @GameTest(template = "empty")
    public static void fenceRatesFollowTheThiefSkillsWithinTheirCaps(GameTestHelper helper) {
        FakePlayer player = player(helper, "uc_thief_fence", "technician");
        CitizenState.Citizen c = citizen(player);
        FenceCatalog.Fence back = new FenceCatalog.Fence("blackwater", "Test", "", 50, 32, 1, 600, 70, 10, 21, 3, Set.of("underground_city:tablet"));
        int plain = FenceService.rate(back, "ironhaven", c, "underground_city:tablet");
        CitizenState.get(player.server).unlockSkill(player.getUUID(), "thief.haggler");
        int haggled = FenceService.rate(back, "ironhaven", c, "underground_city:tablet");
        CitizenState.get(player.server).unlockSkill(player.getUUID(), "thief.specialists");
        int special = FenceService.rate(back, "ironhaven", c, "underground_city:tablet"), other = FenceService.rate(back, "ironhaven", c, "minecraft:gold_ingot");
        check(plain == 50 && haggled == 58, "Haggler did not add 8 points: " + plain + " -> " + haggled);
        boolean fenceOnly = FenceCatalog.INSTANCE.fenceOnly("underground_city:tablet");
        check(special == Math.min(fenceOnly ? FenceCatalog.MAX_FENCE_ONLY_PERCENT : FenceCatalog.MAX_RATE_PERCENT, 73), "the specialty premium is wrong: " + special);
        check(other == Math.min(FenceCatalog.MAX_RATE_PERCENT, 58) && other <= FenceCatalog.MAX_RATE_PERCENT, "goods with an honest price passed the 60 percent cap: " + other);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyGoodsYouTookYourselfEarnThiefXpAtAFence(GameTestHelper helper) {
        FakePlayer thief = player(helper, "uc_thief_own", "farmer");
        UUID partner = UUID.randomUUID();
        ItemStack mine = new ItemStack(Items.GOLD_INGOT, 3), theirs = new ItemStack(Items.GOLD_INGOT, 2);
        StolenGoods.mark(mine, "valewood", thief.getUUID());
        StolenGoods.mark(theirs, "valewood", partner);
        thief.getInventory().add(mine); thief.getInventory().add(theirs);
        FenceService.Row row = new FenceService.Row("minecraft:gold_ingot", "valewood", 5, 17);
        long own = FenceService.ownCredits(thief, Map.of("minecraft:gold_ingot|valewood", 5), List.of(row));
        check(own == 3L * 17, "own goods are worth " + own + ", expected " + 3 * 17);
        check(StolenGoods.thief(mine) == null || StolenGoods.thief(thief.getInventory().items.get(0)) != null, "the thief's name is not on the stolen goods");
        ItemStack again = new ItemStack(Items.GOLD_INGOT);
        StolenGoods.mark(again, "valewood", thief.getUUID());
        StolenGoods.mark(again, "blackwater", partner);
        check(thief.getUUID().equals(StolenGoods.thief(again)) && StolenGoods.city(again).equals("valewood"), "a second mark replaced the first thief or city");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ locks

    private static LockpickSession.Profile profile(String id) { return LockProfiles.INSTANCE.find(id); }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void spoolPinsTurnBackOnceAndACarefulHandStillOpensTheLock(GameTestHelper helper) {
        for (String id : new String[] {"secured", "high_security", "wall_safe"}) {
            check(profile(id).spools() > 0, id + " has no spool pins");
            int opened = 0, turned = 0;
            for (int seed = 0; seed < 30; seed++) {
                LockpickSession session = new LockpickSession(profile(id), 5000L + seed);
                if (LockpickSessionGameTests.solve(session, false) == LockpickSession.State.OPENED) opened++;
                if (session.spoolEvents() > 0) turned++;
            }
            check(opened >= 21, id + ": a careful hand opened only " + opened + " of 30");
            check(turned >= 20, id + ": spool pins turned back in only " + turned + " of 30 locks");
        }
        // a spool pin is never the first pin, and turns back only once
        LockpickSession session = new LockpickSession(profile("secured"), 11L);
        check(!session.spoolAhead(), "an untrained hand was told about a spool pin");
        LockpickSession trained = new LockpickSession(profile("secured"), 11L, LockpickSession.Hands.of(false, true));
        check(!trained.spoolAhead(), "the first pin of a lock was a spool pin");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void steadyHandsAndFeelForThePinsMakeTheSameLockKinder(GameTestHelper helper) {
        LockpickSession.Profile deadbolt = profile("deadbolt");
        int strainPlain = 0, strainSteady = 0, misreadPlain = 0, misreadFeel = 0;
        for (int seed = 0; seed < 20; seed++) {
            LockpickSession plain = new LockpickSession(deadbolt, 900L + seed), steady = new LockpickSession(deadbolt, 900L + seed, LockpickSession.Hands.of(true, false));
            for (int t = 0; t < 15; t++) { plain.tick(0.02, true); steady.tick(0.02, true); }   // pressing far off the spot strains the pick
            strainPlain += plain.strainPercent(); strainSteady += steady.strainPercent();
            LockpickSession noisy = new LockpickSession(deadbolt, 300L + seed), exact = new LockpickSession(deadbolt, 300L + seed, LockpickSession.Hands.of(false, true));
            double spot = exact.sweetSpot();
            for (double p = 0; p <= 1.0; p += 0.01) {
                noisy.tick(p, false); exact.tick(p, false);
                double d = Math.abs(p - spot);
                int truth = d <= deadbolt.tolerance() ? LockpickSession.CLOSE : d <= deadbolt.tolerance() * 2.6 ? LockpickSession.CLOSE : d <= 0.28 ? LockpickSession.NEAR : LockpickSession.FAR;
                if (noisy.feel() != truth) misreadPlain++;
                if (exact.feel() != truth) misreadFeel++;
            }
        }
        check(strainSteady < strainPlain, "Steady Hands did not slow the strain: " + strainSteady + " against " + strainPlain);
        check(misreadFeel == 0 && misreadPlain > 0, "Feel for the Pins did not make the reading exact: " + misreadFeel + " misreadings against " + misreadPlain);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLockAsksForItsToolAndItsSkillBeforeItIsEvenTried(GameTestHelper helper) {
        FakePlayer player = player(helper, "uc_thief_tools", "gunsmith");
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        LockService.Target target = new LockService.Target(LockService.Kind.DOOR, door, "high_security", "valewood", "test", "Door", 1);
        check(LockService.requirement(player, profile("high_security"), target) != null, "a high-security lock was open to lockpicks");
        player.getInventory().add(new ItemStack(UndergroundCity.item("pro_pick_set")));
        String noSkill = LockService.requirement(player, profile("high_security"), target);
        check(noSkill != null && noSkill.contains("Professional Tools"), "a Pro Pick Set without the skill should be refused, naming the skill: " + noSkill);
        CitizenState.get(player.server).unlockSkill(player.getUUID(), "thief.pro_tools");
        check(LockService.requirement(player, profile("high_security"), target) == null, "a thief with a Pro Pick Set and the skill was refused");
        check(LockService.requirement(player, profile("deadbolt"), target) == null, "a deadbolt asked for anything");
        LockService.Target safe = new LockService.Target(LockService.Kind.SAFE, door, "wall_safe", "valewood", "safe", "Wall safe", 1);
        String wall = LockService.requirement(player, profile("wall_safe"), safe);
        check(wall != null && wall.contains("Safecracking"), "a wall safe was open without Safecracking: " + wall);
        CitizenState.get(player.server).unlockSkill(player.getUUID(), "thief.safecracking");
        check(LockService.requirement(player, profile("wall_safe"), safe) == null, "a safecracker was refused a wall safe");
        check(UndergroundCity.item("pro_pick_set").getMaxDamage() == UndergroundCity.DURABLE_USES, "the Pro Pick Set does not wear");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aRakeOpensACheapLockAtOnceOrJoltsThePick(GameTestHelper helper) {
        check(profile("latch").rakeChance() > profile("deadbolt").rakeChance() && profile("deadbolt").rakeChance() > 0 && profile("commercial").rakeChance() == 0,
            "rake chances: a latch rakes best, a deadbolt sometimes, a commercial lock never");
        LockpickSession lock = new LockpickSession(profile("latch"), 3L);
        lock.rakedOpen();
        check(lock.state() == LockpickSession.State.OPENED, "a raked lock is not open");
        LockpickSession jolted = new LockpickSession(profile("deadbolt"), 3L);
        jolted.jolt(LockService.RAKE_FAIL_JOLT);
        check(jolted.active() && jolted.strainPercent() >= 25, "a failed rake did not jolt the pick: " + jolted.strainPercent());
        jolted.jolt(LockService.RAKE_FAIL_JOLT); jolted.jolt(LockService.RAKE_FAIL_JOLT); jolted.jolt(LockService.RAKE_FAIL_JOLT);
        check(jolted.state() == LockpickSession.State.BROKEN, "a pick jolted past its limit did not snap");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ cameras

    @GameTest(template = "empty")
    public static void aCameraSeesOnlyInItsConeAndInSight(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos camera = helper.absolutePos(new BlockPos(1, 4, 1));
        net.minecraft.core.Direction south = net.minecraft.core.Direction.SOUTH;
        net.minecraft.world.phys.Vec3 ahead = net.minecraft.world.phys.Vec3.atCenterOf(camera.south(6).below(2));
        net.minecraft.world.phys.Vec3 behind = net.minecraft.world.phys.Vec3.atCenterOf(camera.north(4).below(2));
        net.minecraft.world.phys.Vec3 aside = net.minecraft.world.phys.Vec3.atCenterOf(camera.east(7).south(1).below(2));
        net.minecraft.world.phys.Vec3 far = net.minecraft.world.phys.Vec3.atCenterOf(camera.south(20).below(2));
        for (int x = -1; x <= 1; x++) for (int y = -3; y <= 1; y++) level.setBlockAndUpdate(camera.offset(x, y, 3), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        check(SecurityDevices.sees(level, camera, south, ahead), "a camera did not see somebody straight ahead of it");
        check(!SecurityDevices.sees(level, camera, south, behind), "a camera saw behind itself");
        check(!SecurityDevices.sees(level, camera, south, aside), "a camera saw far out to its side");
        check(!SecurityDevices.sees(level, camera, south, far), "a camera saw past its range");
        for (int x = -1; x <= 1; x++) for (int y = -3; y <= 1; y++) level.setBlockAndUpdate(camera.offset(x, y, 3), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        check(!SecurityDevices.sees(level, camera, south, ahead), "a camera saw through a wall");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ casing and tiers

    @GameTest(template = "empty")
    public static void casingReadsMoreOfAPlaceTheMoreTheThiefKnows(GameTestHelper helper) {
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building house = building(door, "deadbolt", 3, new CityBuildings.Response(1.0, 60, 1, 60));
        CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(house));
        try {
            FakePlayer novice = player(helper, "uc_thief_case_a", "vintner");
            Casing.Report a = Casing.read(novice, helper.getLevel(), house, door);
            check(a.tier() == 3 && a.lock().contains("deadbolt") && a.alarm() == -1 && a.cameras() == -1 && a.inside() == -1 && a.empty().isEmpty() && a.safe() == -1, "a beginner read more than the tier and the lock: " + a);
            check(!a.canTry() && a.lockNote().contains("no lockpick"), "a beginner without a pick was not told: " + a.lockNote());
            FakePlayer expert = player(helper, "uc_thief_case_b", "vintner");
            CitizenState.get(expert.server).unlockSkill(expert.getUUID(), "thief.casing");
            CitizenState.get(expert.server).unlockSkill(expert.getUUID(), "thief.routines");
            expert.getInventory().add(new ItemStack(UndergroundCity.item("lockpick")));
            Casing.Report b = Casing.read(expert, helper.getLevel(), house, door);
            check(b.alarm() == 2 && b.cameras() >= 0 && b.inside() == 0 && b.empty().equals("Empty now") && b.safe() == 0 && b.recent() == 0 && b.canTry(), "an expert's casing is wrong: " + b);
            check(Casing.lines(b).size() >= 7, "the casing report's words are short: " + Casing.lines(b));
        } finally {
            CityBuildings.INSTANCE.overrideForTest(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theShippedBuildingClassesClimbFromShabbyToExceptional(GameTestHelper helper) {
        Map<String, Integer> expect = Map.of("cottage", 1, "flat_block", 2, "workshop", 3, "flat_tower", 4, "office", 4, "vault", 6, "public", 0);
        expect.forEach((id, tier) -> {
            CityBuildings.SecurityClass c = CityBuildings.INSTANCE.securityClass(id);
            check(c != null && c.tier() == tier, id + " is tier " + (c == null ? "missing" : c.tier()) + ", expected " + tier);
        });
        check(CityBuildings.INSTANCE.securityClass("vault").safeProfile().equals("vault_safe") && CityBuildings.INSTANCE.securityClass("office").safeProfile().equals("wall_safe")
            && CityBuildings.INSTANCE.securityClass("cottage").safeProfile().equals("safe"), "the safes do not follow the tiers");
        check(profile("high_security").requiresTool().equals("pro_pick_set") && profile("vault_safe").requiresSkill().equals("thief.safecracking"), "the top locks ask for nothing");
        check(Thievery.tierWord(1).equals("Shabby") && Thievery.tierWord(6).equals("Exceptional"), "the tier words changed");
        helper.succeed();
    }
}
