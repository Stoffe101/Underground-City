package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trades' workshops (D-163), the goods they make sold to shops (D-164), the Technician's fault rebalance (D-165) and back-channel work (D-166), headless: the stations load from data, a batch
 * takes what goes in and gives what comes out after its time, at the grade its tier and skills earn; a tier is fitted from real parts and kept when the station is carried away; a contract pays more
 * for Fine and Reserve goods and takes nothing named; a shop buys a supplier's goods once per sale onto a shelf its customers empty; a quietened alarm line does not call. None of it shows a screen
 * in a real client.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkshopGameTests {
    private WorkshopGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer player(GameTestHelper helper, String name, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    private static int count(FakePlayer player, net.minecraft.world.item.Item item) { return player.getInventory().countItem(item); }

    @GameTest(template = "empty")
    public static void everyStationLoadsWithTiersRecipesAndRealItems(GameTestHelper helper) {
        Map<String, Workshops.Station> all = Workshops.INSTANCE.all();
        for (String id : UndergroundCity.WORKSHOPS) {
            Workshops.Station st = all.get(id);
            check(st != null, "station " + id + " did not load");
            check(st.tiers().size() == 3, id + " has " + st.tiers().size() + " tiers, not three");
            check(UndergroundCity.WORKSHOP_BLOCKS.containsKey(id), id + " has no block");
            for (Workshops.Tier t : st.tiers()) for (var part : t.parts().keySet())
                check(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(part), id + " tier " + t.tier() + " needs an unknown part " + part);
            for (Workshops.Recipe r : st.recipes()) {
                if (!r.mod().isEmpty()) continue;
                check(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(r.output()), id + "/" + r.id() + " makes an unknown item");
                for (var in : r.inputs().keySet()) check(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(in), id + "/" + r.id() + " needs an unknown item " + in);
            }
        }
        check(all.values().stream().map(Workshops.Station::career).distinct().count() == 4, "the stations should serve the Farmer, the Vintner, the Smith and the Gunsmith");
        helper.succeed();
    }

    /**
     * Two players at one station (D-180): a batch is its maker's. Somebody else can neither take it nor start over it; the maker takes it and is paid its career XP; a member of the maker's
     * Crew may take it for them (the XP stays the maker's); a station saved before the field existed still hands over to anybody, as before.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBatchIsItsMakersAndOnlyTheirCrewTakesItForThem(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(pos, UndergroundCity.WORKSHOP_BLOCKS.get("machinist_bench").get().defaultBlockState(), 3);
        WorkshopBlock.Entity e = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(pos);
        FakePlayer maker = player(helper, "uc_ws_maker", "smith"), stranger = player(helper, "uc_ws_stranger", "smith");
        maker.getInventory().add(new ItemStack(Items.IRON_NUGGET, 12));
        stranger.getInventory().add(new ItemStack(Items.IRON_NUGGET, 12));
        check(WorkshopBlock.start(maker, helper.getLevel(), e, "fasteners", 1).startsWith("Started"), "the maker's batch did not start");
        e.since -= e.duration;
        e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
        check(!e.busy() && maker.getUUID().equals(e.readyBy), "the finished batch does not name its maker");
        check(WorkshopBlock.collect(stranger, helper.getLevel(), e).contains("somebody else's"), "a stranger took the maker's batch");
        check(WorkshopBlock.start(stranger, helper.getLevel(), e, "fasteners", 1).contains("Somebody else's batch"), "a stranger started over the maker's waiting batch");
        check(count(stranger, Items.IRON_NUGGET) == 12, "the refused stranger lost their nuggets");
        int xp = CitizenState.get(maker.server).find(maker.getUUID()).orElseThrow().careerXp();
        check(WorkshopBlock.collect(maker, helper.getLevel(), e).startsWith("Took 8"), "the maker could not take their own batch");
        check(CitizenState.get(maker.server).find(maker.getUUID()).orElseThrow().careerXp() > xp, "the maker was not paid the batch's XP");
        // a Crew mate takes it for the maker; the XP is the maker's
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            rig.ledger.create(maker.getUUID(), "uc_ws_maker", "Bench Crew");
            rig.ledger.accept(rig.ledger.invite(maker.getUUID(), stranger.getUUID(), "uc_ws_stranger", "uc_ws_maker").id(), stranger.getUUID(), "uc_ws_stranger");
            maker.getInventory().add(new ItemStack(Items.IRON_NUGGET, 6));
            check(WorkshopBlock.start(maker, helper.getLevel(), e, "fasteners", 1).startsWith("Started"), "the maker's second batch did not start");
            e.since -= e.duration;
            e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
            int makerXp = CitizenState.get(maker.server).find(maker.getUUID()).orElseThrow().careerXp(), mateXp = CitizenState.get(maker.server).find(stranger.getUUID()).orElseThrow().careerXp();
            check(WorkshopBlock.start(stranger, helper.getLevel(), e, "fasteners", 1).contains("Crew mate"), "a Crew mate started over the maker's waiting batch");
            check(WorkshopBlock.collect(stranger, helper.getLevel(), e).startsWith("Took 8"), "a Crew mate could not take the maker's batch");
            check(CitizenState.get(maker.server).find(maker.getUUID()).orElseThrow().careerXp() > makerXp && CitizenState.get(maker.server).find(stranger.getUUID()).orElseThrow().careerXp() == mateXp,
                "the XP of a batch a Crew mate took went to the mate, not the maker");
        } catch (Exception error) { throw new AssertionError("the Crew rig failed: " + error); }
        // a pile of two recipes is paid as the two: the last recipe's XP was once paid for every batch in the pile (the review of D-180)
        FakePlayer mixer = player(helper, "uc_ws_mixer", "smith");
        mixer.getInventory().add(new ItemStack(Items.IRON_NUGGET, 6));
        mixer.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
        check(WorkshopBlock.start(mixer, helper.getLevel(), e, "fasteners", 1).startsWith("Started"), "the mixer's fasteners did not start");
        e.since -= e.duration; e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
        check(WorkshopBlock.start(mixer, helper.getLevel(), e, "barrel_hoops", 1).startsWith("Started"), "the mixer could not start hoops over their own waiting fasteners");
        check(!WorkshopBlock.mayBreak(stranger, e) && WorkshopBlock.mayBreak(mixer, e), "a stranger may break a station with somebody else's batch in it, or its maker may not");
        e.since -= e.duration; e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
        int mixerXp = CitizenState.get(mixer.server).find(mixer.getUUID()).orElseThrow().careerXp();
        check(WorkshopBlock.collect(mixer, helper.getLevel(), e).startsWith("Took"), "the mixer could not take their pile");
        int expected = e.station().recipe("fasteners").xp() + e.station().recipe("barrel_hoops").xp();
        check(CitizenState.get(mixer.server).find(mixer.getUUID()).orElseThrow().careerXp() - mixerXp == expected, "a mixed pile was not paid each recipe's XP once");
        // a batch nobody comes back for is anybody's after two hours of play
        mixer.getInventory().add(new ItemStack(Items.IRON_NUGGET, 6));
        check(WorkshopBlock.start(mixer, helper.getLevel(), e, "fasteners", 1).startsWith("Started"), "the mixer's last batch did not start");
        e.since -= e.duration; e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
        check(!WorkshopBlock.mayTake(stranger, e), "a stranger may take a fresh batch");
        e.readySince -= WorkshopBlock.ABANDONED_TICKS;
        check(WorkshopBlock.mayTake(stranger, e) && WorkshopBlock.collect(stranger, helper.getLevel(), e).startsWith("Took 8"), "a batch left two hours is still held for its maker");
        // an old station: nobody named, anybody takes it
        e.ready.add(new ItemStack(UndergroundCity.item("fasteners"), 8));
        e.readyBy = null;
        check(WorkshopBlock.collect(stranger, helper.getLevel(), e).startsWith("Took 8"), "a station saved before D-180 refused its goods");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBatchTakesItsInputsAndGivesGradedGoodsAfterItsTime(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(pos, UndergroundCity.WORKSHOP_BLOCKS.get("fermentation_vat").get().defaultBlockState(), 3);
        WorkshopBlock.Entity e = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(pos);
        check(e != null && e.station() != null, "the vat has no station");
        FakePlayer vintner = player(helper, "uc_ws_vintner", "vintner");
        vintner.getInventory().add(new ItemStack(Items.SWEET_BERRIES, 16));
        vintner.getInventory().add(new ItemStack(Items.SUGAR, 4));
        vintner.getInventory().add(new ItemStack(Items.GLASS_BOTTLE, 6));
        // tier 1 runs one batch at a time
        check(WorkshopBlock.start(vintner, helper.getLevel(), e, "berry_wine", 2).startsWith("This tier runs 1"), "a tier 1 vat took two batches");
        String said = WorkshopBlock.start(vintner, helper.getLevel(), e, "berry_wine", 1);
        check(said.startsWith("Started"), "the batch did not start: " + said);
        check(count(vintner, Items.SWEET_BERRIES) == 8 && count(vintner, Items.SUGAR) == 2 && count(vintner, Items.GLASS_BOTTLE) == 3, "the batch did not take exactly its inputs");
        check(WorkshopBlock.start(vintner, helper.getLevel(), e, "berry_wine", 1).contains("still at work"), "a busy vat took a second batch");
        check(WorkshopBlock.collect(vintner, helper.getLevel(), e).startsWith("Still at work"), "goods were taken before their time");
        // the batch is done once its time has passed on the active clock; the station's own clock finishes it before the player comes back (workshop run 3 paid no XP then)
        e.since -= e.duration;
        int xpBefore = CitizenState.get(vintner.server).find(vintner.getUUID()).orElseThrow().careerXp();
        e.check(helper.getLevel(), helper.getLevel().getBlockState(pos));
        check(!e.busy() && !e.ready.isEmpty(), "the station's clock did not finish the batch");
        String took = WorkshopBlock.collect(vintner, helper.getLevel(), e);
        int xpGain = CitizenState.get(vintner.server).find(vintner.getUUID()).orElseThrow().careerXp() - xpBefore;
        check(xpGain == e.station().recipe("berry_wine").xp(), "taking a batch the clock had finished paid " + xpGain + " XP, not the recipe's");
        net.minecraft.world.item.Item wine = UndergroundCity.item("berry_wine");
        check(took.startsWith("Took 3"), "the finished batch did not hand over three bottles: " + took);
        check(count(vintner, wine) == 3, "the player does not hold the wine");
        ItemStack held = vintner.getInventory().items.stream().filter(s -> s.is(wine)).findFirst().orElseThrow();
        check(Workshops.grade(held) == 0, "a tier 1 vat without Cellar Craft made better than Standard");
        // a grade from tier 3 and the skill: Reserve at most
        Workshops.Recipe r = e.station().recipe("berry_wine");
        check(Workshops.gradeFor(r, 3, s -> s.equals("vintner.cellar_craft"), 0) == 2, "a tier 3 vat with Cellar Craft did not make Reserve");
        check(Workshops.gradeFor(r, 2, s -> false, 0) == 0, "a tier 2 vat raised the grade");
        // ageing keeps the grade of what went in, never below Fine
        Workshops.Recipe aged = Workshops.INSTANCE.station("oak_cask").recipe("aged_wine");
        check(Workshops.gradeFor(aged, 1, s -> false, 0) == 1 && Workshops.gradeFor(aged, 1, s -> false, 1) == 2, "ageing did not lift and keep the grade");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aTierIsFittedFromRealPartsAndKeptWhenTheStationIsBroken(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(pos, UndergroundCity.WORKSHOP_BLOCKS.get("machinist_bench").get().defaultBlockState(), 3);
        WorkshopBlock.Entity e = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(pos);
        FakePlayer smith = player(helper, "uc_ws_smith", "smith");
        check(WorkshopBlock.upgrade(smith, helper.getLevel(), e).startsWith("Fitting"), "a tier was fitted without parts");
        smith.getInventory().add(new ItemStack(UndergroundCity.item("gear_set"), 1));
        smith.getInventory().add(new ItemStack(UndergroundCity.item("steel_bracket"), 2));
        smith.getInventory().add(new ItemStack(UndergroundCity.item("springs"), 2));
        String said = WorkshopBlock.upgrade(smith, helper.getLevel(), e);
        check(said.startsWith("Fitted") && e.tier == 2, "the drill press was not fitted: " + said);
        check(count(smith, UndergroundCity.item("gear_set")) == 0 && count(smith, UndergroundCity.item("springs")) == 0, "the parts were not used");
        check(helper.getLevel().getBlockState(pos).getValue(WorkshopBlock.TIER) == 2, "the block does not show tier 2");
        // tier 3 needs Precision Machining
        smith.getInventory().add(new ItemStack(UndergroundCity.item("gear_set"), 2));
        smith.getInventory().add(new ItemStack(UndergroundCity.item("machined_parts"), 2));
        smith.getInventory().add(new ItemStack(UndergroundCity.item("springs"), 2));
        check(WorkshopBlock.upgrade(smith, helper.getLevel(), e).contains("needs the skill"), "the lathe was fitted without Precision Machining");
        // a station carried away keeps its tier
        helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2));
        ItemStack bench = drops.stream().map(ItemEntity::getItem).filter(s -> s.is(UndergroundCity.WORKSHOP_ITEMS.get("machinist_bench").get())).findFirst().orElse(ItemStack.EMPTY);
        check(!bench.isEmpty() && bench.getTag() != null && bench.getTag().getInt("uc_tier") == 2, "the broken bench did not drop itself at tier 2");
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSkillGatesItsRecipeAndAnUndergroundRecipeIsMarked(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(pos, UndergroundCity.WORKSHOP_BLOCKS.get("pot_still").get().defaultBlockState(), 3);
        WorkshopBlock.Entity e = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(pos);
        FakePlayer vintner = player(helper, "uc_ws_still", "vintner");
        vintner.getInventory().add(new ItemStack(Items.SUGAR, 8));
        vintner.getInventory().add(new ItemStack(Items.APPLE, 4));
        vintner.getInventory().add(new ItemStack(Items.GLASS_BOTTLE, 4));
        check(WorkshopBlock.start(vintner, helper.getLevel(), e, "moonshine", 1).contains("Home Distilling"), "moonshine was run without Home Distilling");
        check(count(vintner, Items.SUGAR) == 8, "a refused batch took its inputs");
        check(e.station().recipe("moonshine").legality().equals("underground") && e.station().recipe("grain_spirit").legality().equals("legal"), "the still's recipes are marked wrongly");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aContractPaysForTheGradeAndTakesNothingNamed(GameTestHelper helper) {
        check(WorkOrderService.gradedPayout(100, 4, 4) == 100, "Standard goods paid more than the order");
        check(WorkOrderService.gradedPayout(100, 4 * 1.25, 4) == 125, "Fine goods did not pay a quarter more");
        check(WorkOrderService.gradedPayout(100, 2 * 1.6 + 2, 4) == 130, "half Reserve did not pay in proportion");
        WorkOrder order = WorkOrder.fromJson(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "test_wine"),
            com.google.gson.JsonParser.parseString("{\"schema_version\":1,\"title\":\"t\",\"career\":\"vintner\",\"level\":1,\"item\":\"underground_city:berry_wine\",\"count\":2,\"credits\":10,\"xp\":1,\"capacity\":1,\"refill_ticks\":1200}").getAsJsonObject());
        WorkOrder.Requirement req = order.requirements().get(0);
        net.minecraft.world.item.Item wine = UndergroundCity.item("berry_wine");
        check(order.matches(req, new ItemStack(wine)), "plain wine was refused");
        check(order.matches(req, Workshops.graded(new ItemStack(wine), 2)), "Reserve wine was refused");
        ItemStack named = Workshops.graded(new ItemStack(wine), 1);
        named.setHoverName(net.minecraft.network.chat.Component.literal("Mine"));
        check(!order.matches(req, named), "a named bottle would be taken");
        ItemStack stolen = new ItemStack(wine);
        stolen.getOrCreateTag().putString(StolenGoods.KEY, "valewood");
        check(!order.matches(req, stolen), "stolen goods would be taken");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShopBuysASuppliersGoodsOnceOntoAShelfItsCustomersEmpty(GameTestHelper helper) throws Exception {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        WorkOrderLedger ledger = new WorkOrderLedger(path);
        try {
            RegionalStock pub = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":shops/pub_wine_ironhaven");
            check(pub != null, "the pub's wine line did not load");
            RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(pub.id());
            check(terms != null && terms.pays() < pub.unitPrice() && terms.career().equals("vintner"), "the pub's supply terms are wrong");
            check(RegionalCatalog.INSTANCE.supply(UndergroundCity.MOD_ID + ":shops/pub_stew_ironhaven") == null, "the pub buys stew from suppliers");
            int before = ledger.regionalUnits(pub);
            UUID player = UUID.randomUUID(), nonce = UUID.randomUUID();
            ledger.prepareSupply(player, nonce, pub, 3, 42, new ListTag(), 42, 0);
            check(ledger.regionalUnits(pub) == before + 3, "the shelf did not take the three bottles");
            ledger.settle(player);
            boolean refused = false;
            try { ledger.prepareSupply(player, nonce, pub, 3, 42, new ListTag(), 84, 0); } catch (IllegalStateException expected) { refused = true; }
            check(refused && ledger.regionalUnits(pub) == before + 3, "the same sale was made twice");
            refused = false;
            try { ledger.prepareSupply(player, UUID.randomUUID(), pub, pub.capacity(), 1, new ListTag(), 1, 0); } catch (IllegalStateException expected) { refused = true; }
            check(refused, "a shop took more than its shelf holds");
            check(ledger.drawDown(pub, 2) == 2 && ledger.regionalUnits(pub) == before + 1, "a customer did not buy off the shelf");
            check(ledger.drawDown(pub, 999) == before + 1 && ledger.regionalUnits(pub) == 0 && ledger.drawDown(pub, 1) == 0, "the shelf went below empty");
            // the generated line's own price check: a shop never buys back for more than it sells
            for (RegionalStock line : RegionalCatalog.INSTANCE.all()) {
                RegionalCatalog.Supply s = RegionalCatalog.INSTANCE.supply(line.id());
                if (s != null) check(s.pays() < line.unitPrice(), line.id() + " buys back for more than it sells");
            }
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEmptyBackRoomBarrelHoldsTheBusinesssStockAndAFullOneIsLeftAlone(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        // the furnished buildings' plans come with the opt-in furnished package: two plans stand in for a bakery and a house (tests put back what they took)
        java.util.Map<String, BuildingSpots.Plan> plans = BuildingSpots.INSTANCE.plansForTest();
        java.util.Map<String, BuildingSpots.Plan> test = new java.util.HashMap<>(plans);
        test.put("underground_city:vw_bakery", new BuildingSpots.Plan("underground_city:vw_bakery", "shop", "valewood", List.of()));
        test.put("underground_city:vw_house_a", new BuildingSpots.Plan("underground_city:vw_house_a", "house", "valewood", List.of()));
        BuildingSpots.INSTANCE.replaceForTest(test);
        try {
        var security = new CityBuildings.SecurityClass("storefront", "deadbolt", true, "", 0, "bakery", new CityBuildings.Response(0.0, 60, 1, 60), 3, "");
        CityBuildings.Building bakery = new CityBuildings.Building("valewood", at.getX() >> 4, at.getZ() >> 4, "underground_city:vw_bakery", "vw_bakery", 2, 0, at.getY() - 1, security);
        var barrel = new net.minecraft.world.SimpleContainer(27);
        int stacks = BurglarySearch.stock(helper.getLevel(), bakery, at, barrel, 0);
        check(stacks > 0, "an empty back-room barrel in a bakery held nothing");
        boolean bakers = false;
        for (int i = 0; i < barrel.getContainerSize(); i++) {
            ItemStack s = barrel.getItem(i);
            if (s.isEmpty()) continue;
            String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
            check(!id.contains("berry_wine") && !id.contains("fasteners"), "a bakery's stock held another trade's goods: " + id);
            bakers |= id.equals("underground_city:flour") || id.equals("minecraft:wheat") || id.equals("minecraft:sugar") || id.equals("minecraft:bread") || id.endsWith("_crate") || id.endsWith("cash_box");
        }
        check(bakers, "the bakery's stock held none of a bakery's goods");
        var full = new net.minecraft.world.SimpleContainer(27);
        full.setItem(0, new ItemStack(Items.STICK));
        check(BurglarySearch.stock(helper.getLevel(), bakery, at, full, 0) == 0 && full.getItem(1).isEmpty(), "a barrel that held something was filled too");
        CityBuildings.Building house = new CityBuildings.Building("valewood", at.getX() >> 4, at.getZ() >> 4, "underground_city:vw_house_a", "vw_house_a", 2, 0, at.getY() - 1, security);
        check(BurglarySearch.stock(helper.getLevel(), house, at, new net.minecraft.world.SimpleContainer(27), 0) == 0, "a house's barrel was filled with a business's stock");
        } finally {
            BuildingSpots.INSTANCE.replaceForTest(plans);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aQuietenedLineDoesNotCallAndBackChannelWorkIsHidden(GameTestHelper helper) {
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        var security = new CityBuildings.SecurityClass("flat_block", "deadbolt", true, "", 0, "test flat", new CityBuildings.Response(1.0, 60, 1, 60), 3, "");
        int cx = 300_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000), cz = 300_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000);
        CityBuildings.Building b = new CityBuildings.Building("valewood", cx, cz, "test", "test_building", 2, 0, door.getY() - 1, security);
        TechService.Systems s = TechService.systems(helper.getLevel().getServer());
        long now = LockService.clock(helper.getLevel().getServer());
        TechService.Panel panel = new TechService.Panel(b.key(), null, door, "valewood", 3, "residential", "test flat", "9 Quiet Lane");
        try {
            check(PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "an alarm with a certain chance was not armed");
            s.quieted.put(b.key(), now + TechService.QUIET_TICKS);
            check(TechService.quieted(helper.getLevel(), b) && !PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "a quietened line still called");
            s.quieted.put(b.key(), now - 1);
            check(PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "a quiet line did not come back");
            SecurityDevices.loop(helper.getLevel(), b, 1200);
            check(SecurityDevices.looped(helper.getLevel(), b), "the cameras were not looped");
            // back-channel work is on the board only for a Technician with Bypass Tooling
            s.panels.put(b.key(), panel);
            TechService.Job job = TechService.newJob(s, "quiet", panel, TechService.quietFee(3), TechService.quietXp(3), now);
            TechWork.TESTING = true;
            FakePlayer honest = player(helper, "uc_ws_honest", "technician");
            check(TechWork.board(honest).stream().noneMatch(c -> c.id().equals(job.id())), "back-channel work was shown to a Technician without Bypass Tooling");
            FakePlayer quiet = player(helper, "uc_ws_quiet", "technician");
            CitizenState.get(quiet.server).unlockSkill(quiet.getUUID(), "technician.fundamentals");
            CitizenState.get(quiet.server).unlockSkill(quiet.getUUID(), "technician.bypass_tooling");
            var line = TechWork.board(quiet).stream().filter(x -> x.id().equals(job.id())).findFirst();
            check(line.isPresent() && line.get().blocked().equals("Bring a Bypass Kit"), "back-channel work was not shown, or not asked for a Bypass Kit");
            check(TechWork.startQuiet(quiet, helper.getLevel(), panel, job).contains("Bypass Kit"), "quiet work started without a Bypass Kit");
        } finally {
            TechWork.TESTING = false;
            s.quieted.remove(b.key());
            s.panels.remove(b.key());
            s.jobs.values().removeIf(j -> j.key() == b.key());
        }
        helper.succeed();
    }
}
