package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trade premises (D-169) and Crew contracts (D-170) on the dedicated test server: a station in premises with the trade facility runs one more batch a quarter faster for whoever
 * may work there, bulk orders open only to somebody who works in such premises, and a Crew contract pays each part to the member who brought it, records parts once, owes the
 * bonus in equal shares to those who delivered and pays each share once through its own settlement, across a restart too. The wallet is Q Shop's own.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BusinessGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static void refused(Runnable action, String containing, String what) {
        try { action.run(); } catch (IllegalStateException expected) {
            check(containing.isEmpty() || expected.getMessage().contains(containing), what + ": refused, but saying \"" + expected.getMessage() + "\"");
            return;
        }
        throw new net.minecraft.gametest.framework.GameTestAssertException(what + ": not refused");
    }

    static Terms tradeTerms(String id) {
        return new Terms(id, "Test workshop", "commercial_workshop", 8, "Test", Set.of(Tenure.RENT, Tenure.OWN), 135, 270, 10500, 27, 16, 4,
            Set.of("storage", "crafting", "furnace", "workshop", "trade"), "test");
    }

    /** A scratch work-order journal with a city circle round the test; put back on close. */
    static final class Orders implements AutoCloseable {
        final Path path;
        WorkOrderLedger ledger;
        final List<CityRegions.Region> regions = CityRegions.INSTANCE.all();
        final BlockPos pos;

        Orders(GameTestHelper helper, String city) {
            path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
            ledger = new WorkOrderLedger(path);
            pos = helper.absolutePos(new BlockPos(0, 2, 0));
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city, helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
        }

        void restart() { ledger = new WorkOrderLedger(path); }

        @Override public void close() throws Exception {
            CityRegions.INSTANCE.replaceForTest(regions);
            Files.deleteIfExists(path);
        }
    }

    private static ServerPlayer citizen(GameTestHelper helper, String name, String career, int level) {
        FakePlayer player = CrewGameTests.player(helper, name, career);
        if (level > 1) CitizenState.get(player.server).addCareerXp(player.getUUID(), (int) (125.0 * Math.pow(level - 1, 2)));
        QShopEconomy.applyBalance(player, 0);
        return player;
    }

    private static CitizenState.Citizen of(ServerPlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(); }

    private static void give(ServerPlayer player, String item, int count) { player.getInventory().add(new ItemStack(UndergroundCity.item(item), count)); }

    /** Takes and delivers a catalog order the way the terminal does. */
    private static void deliver(ServerPlayer player, Orders o, String orderId) {
        WorkOrderService.accept(player, o.ledger, of(player), orderId);
        WorkOrderService.complete(player, o.ledger, o.ledger.player(player.getUUID()).getUUID("order"), o.pos);
    }

    // ------------------------------------------------------------------------------------------------ trade premises (D-169)

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tradePremisesRunOneMoreBatchAQuarterFasterOnlyForWhoMayWorkThere(GameTestHelper helper) throws Exception {
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of("t:shop", tradeTerms("t:shop"), "t:flat", PropertyGameTests.flatTerms("t:flat")))) {
            Residence shop = f.home("t:shop", 0), flat = f.home("t:flat", 1);
            ServerPlayer smith = f.person(helper, "uc_tp_smith", "smith", 0), stranger = f.person(helper, "uc_tp_other", "smith", 0);
            for (Residence r : List.of(shop, flat)) { r.ownerType = OwnerType.PLAYER; r.owner = smith.getUUID(); r.tenure = Tenure.OWN; }
            BlockPos inShop = f.in(0, 2, 1, 2), inFlat = f.in(1, 2, 1, 2);
            check(Premises.tradeAt(helper.getLevel(), inShop, smith.getUUID()) != null, "the holder's station in trade premises gets the bonus");
            check(Premises.tradeAt(helper.getLevel(), inFlat, smith.getUUID()) == null, "a flat is not trade premises");
            check(Premises.tradeAt(helper.getLevel(), inShop, stranger.getUUID()) == null, "somebody who may not work there gets no bonus");
            // the station itself: one more batch, a quarter faster
            helper.getLevel().setBlock(inShop, UndergroundCity.WORKSHOP_BLOCKS.get("machinist_bench").get().defaultBlockState(), 3);
            WorkshopBlock.Entity e = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(inShop);
            Workshops.Station st = e.station();
            Workshops.Recipe fasteners = st.recipe("fasteners");
            int base = st.tier(e.tier).batch();
            for (var input : fasteners.inputs().entrySet()) smith.getInventory().add(new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(input.getKey()), 64));
            check(WorkshopBlock.refusal(smith, e, st, fasteners, base + 1) == null, "the holder may run " + (base + 1) + " batches in trade premises: " + WorkshopBlock.refusal(smith, e, st, fasteners, base + 1));
            check(WorkshopBlock.refusal(stranger, e, st, fasteners, base + 1) != null, "a stranger at the same station runs the tier's own batch");
            check(Premises.ticks(fasteners, st.tier(1), true) < Workshops.ticksFor(fasteners, st.tier(1)), "trade premises work faster");
            check(Premises.holds(f.rig.server, smith.getUUID(), Premises.TRADE) && !Premises.holds(f.rig.server, stranger.getUUID(), Premises.TRADE), "who holds trade premises");
            // a lapsed tenancy withdraws the bonus with the other comforts
            shop.tenure = Tenure.RENT; shop.charge = 135; shop.arrears = 99;
            check(Premises.tradeAt(helper.getLevel(), inShop, smith.getUUID()) == null, "a lapsed workshop gives no bonus");
            helper.getLevel().setBlock(inShop, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBulkOrderIsTakenOnlyBySomebodyWhoWorksInTradePremises(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of("t:shop2", tradeTerms("t:shop2")));
             Orders o = new Orders(helper, "ironhaven")) {
            ServerPlayer farmer = citizen(helper, "uc_bulk_farmer", "farmer", 5);
            CitizenState.get(farmer.server).unlockSkill(farmer.getUUID(), "farmer.fundamentals");
            WorkOrder bulk = WorkOrderCatalog.INSTANCE.find("underground_city:bulk_canteen_flour");
            check(bulk != null && bulk.terms().requiresFacility().equals(Premises.TRADE), "the bulk order loaded with its facility");
            refused(() -> WorkOrderService.accept(farmer, o.ledger, of(farmer), bulk.id()), "trade premises", "a bulk order without premises");
            Residence shop = f.home("t:shop2", 0);
            shop.ownerType = OwnerType.PLAYER; shop.owner = farmer.getUUID(); shop.tenure = Tenure.OWN;
            WorkOrderService.accept(farmer, o.ledger, of(farmer), bulk.id());
            check(o.ledger.player(farmer.getUUID()).hasUUID("order"), "with premises the bulk order is taken");
            give(farmer, "flour", 64);
            WorkOrderService.complete(farmer, o.ledger, o.ledger.player(farmer.getUUID()).getUUID("order"), o.pos);
            check(QShopEconomy.balance(farmer) >= bulk.credits(), "the bulk order paid: " + QShopEconomy.balance(farmer));
            // the facility survives the accepted order's snapshot
            check(Premises.TRADE.equals(WorkOrder.load(bulk.save()).terms().requiresFacility()), "the facility is kept in the snapshot");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ Crew contracts (D-170)

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aCrewContractPaysEachPartToItsDelivererAndSharesTheBonusAmongThemOnce(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper); Orders o = new Orders(helper, "ironhaven")) {
            CrewContracts.Contract canteen = CrewContracts.CATALOG.find("underground_city:depot_canteen");
            check(canteen != null && CrewContracts.CATALOG.available(canteen), "the canteen contract loaded with all its parts");
            ServerPlayer farmer = citizen(helper, "uc_cc_farmer", "farmer", 3), smith = citizen(helper, "uc_cc_smith", "smith", 3), loner = citizen(helper, "uc_cc_loner", "farmer", 3);
            // not in a Crew: refused, with the reason
            refused(() -> WorkOrderService.accept(loner, o.ledger, of(loner), "underground_city:crew_canteen_flour"), "Crew", "a Crew part outside a Crew");
            CrewLedger.Crew crew = rig.ledger.create(farmer.getUUID(), "uc_cc_farmer", "Canteen Crew");
            rig.ledger.accept(rig.ledger.invite(farmer.getUUID(), smith.getUUID(), "uc_cc_smith", "uc_cc_farmer").id(), smith.getUUID(), "uc_cc_smith");
            // the farmer's part: their own credits and XP, recorded against the Crew
            give(farmer, "flour", 24);
            int farmerXp = of(farmer).careerXp();
            deliver(farmer, o, "underground_city:crew_canteen_flour");
            check(QShopEconomy.balance(farmer) == WorkOrderCatalog.INSTANCE.find("underground_city:crew_canteen_flour").credits() && of(farmer).careerXp() > farmerXp, "the farmer was paid for their part: " + QShopEconomy.balance(farmer));
            check(farmer.getUUID().toString().equals(o.ledger.crewPartBy(crew.id, canteen.id(), "flour")), "the part is the farmer's");
            // nobody brings a part twice: the second farmer of the Crew is refused at the board, before anything is taken
            rig.ledger.accept(rig.ledger.invite(farmer.getUUID(), loner.getUUID(), "uc_cc_loner", "uc_cc_farmer").id(), loner.getUUID(), "uc_cc_loner");
            give(loner, "flour", 24);
            refused(() -> WorkOrderService.accept(loner, o.ledger, of(loner), "underground_city:crew_canteen_flour"), "already", "a part the Crew has");
            check(loner.getInventory().countItem(UndergroundCity.item("flour")) == 24, "nothing was taken from the refused member");
            // the smith's part
            give(smith, "fasteners", 24); give(smith, "steel_bracket", 6);
            deliver(smith, o, "underground_city:crew_canteen_fittings");
            int fittings = WorkOrderCatalog.INSTANCE.find("underground_city:crew_canteen_fittings").credits();
            check(QShopEconomy.balance(smith) == fittings, "the smith was paid for their part: " + QShopEconomy.balance(smith));
            // no Vintner: the farmer has a supplier fill the cider part from their own wallet; that completes the contract
            QShopEconomy.applyBalance(farmer, 300);
            check(CrewContracts.fill(farmer, o.ledger, canteen.id(), "cider") == 180 && QShopEconomy.balance(farmer) == 120, "the supplier's price left the farmer's wallet: " + QShopEconomy.balance(farmer));
            check(o.ledger.crewCompletions(crew.id, canteen.id()) == 1 && o.ledger.crewPartBy(crew.id, canteen.id(), "flour") == null, "the contract completed and the next cycle has no parts");
            long share = canteen.bonus() / 2;
            check(o.ledger.crewBonusOwed(farmer.getUUID()) + o.ledger.crewBonusOwed(smith.getUUID()) == canteen.bonus() && o.ledger.crewBonusOwed(smith.getUUID()) >= share
                && o.ledger.crewBonusOwed(loner.getUUID()) == 0, "the bonus is owed in equal shares to the two who delivered, nothing to the member who did not");
            // each share reaches its wallet once
            long farmerShare = o.ledger.crewBonusOwed(farmer.getUUID()), smithShare = o.ledger.crewBonusOwed(smith.getUUID());
            check(CrewContracts.payOwed(farmer, o.ledger) == farmerShare && QShopEconomy.balance(farmer) == 120 + farmerShare, "the farmer's share was paid");
            check(CrewContracts.payOwed(farmer, o.ledger) == 0 && QShopEconomy.balance(farmer) == 120 + farmerShare, "and only once");
            // the smith was away: the share waits in the journal across a restart and is paid when they are next here
            o.restart();
            check(o.ledger.crewBonusOwed(smith.getUUID()) == smithShare && o.ledger.crewCompletions(crew.id, canteen.id()) == 1, "the owed share and the completion survive a restart");
            check(CrewContracts.payOwed(smith, o.ledger) == smithShare && QShopEconomy.balance(smith) == fittings + smithShare, "the smith's share was paid after the restart");
            // a finished contract opens again only after its cooldown of active play
            refused(() -> WorkOrderService.accept(farmer, o.ledger, of(farmer), "underground_city:crew_canteen_flour"), "recently", "a contract finished a moment ago");
            o.ledger.advance(canteen.cooldownTicks() + 1);
            WorkOrderService.accept(farmer, o.ledger, of(farmer), "underground_city:crew_canteen_flour");
            check(o.ledger.player(farmer.getUUID()).hasUUID("order"), "after the cooldown the contract is open again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aCrewOfOneMayFillPartsButSuppliersAloneEarnNoBonusAndARestartReplaysASettlementOnce(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper); Orders o = new Orders(helper, "valewood")) {
            CrewContracts.Contract fair = CrewContracts.CATALOG.find("underground_city:harvest_fair");
            check(fair != null && CrewContracts.CATALOG.available(fair), "the fair contract loaded");
            ServerPlayer solo = citizen(helper, "uc_cc_solo", "vintner", 3);
            CrewLedger.Crew crew = rig.ledger.create(solo.getUUID(), "uc_cc_solo", "Just Me");
            QShopEconomy.applyBalance(solo, 1000);
            // every part filled by suppliers: the contract completes, but nobody delivered a part, so nobody is owed a bonus
            for (CrewContracts.Part part : fair.parts()) CrewContracts.fill(solo, o.ledger, fair.id(), part.id());
            long spent = fair.parts().stream().mapToLong(CrewContracts.Part::fillPrice).sum();
            check(QShopEconomy.balance(solo) == 1000 - spent && o.ledger.crewCompletions(crew.id, fair.id()) == 1 && o.ledger.crewBonusOwed(solo.getUUID()) == 0,
                "buying every part pays no bonus: wallet " + QShopEconomy.balance(solo) + ", owed " + o.ledger.crewBonusOwed(solo.getUUID()));
            // a crash after a bonus settlement is prepared, before it settles: the restart replays it once, never twice
            o.ledger.advance(fair.cooldownTicks() + 1);
            QShopEconomy.applyBalance(solo, 1000);
            give(solo, "berry_wine", 6);
            deliver(solo, o, "underground_city:crew_fair_wine");
            CrewContracts.fill(solo, o.ledger, fair.id(), "produce");
            CrewContracts.fill(solo, o.ledger, fair.id(), "lighting");
            long owed = o.ledger.crewBonusOwed(solo.getUUID());
            check(owed == fair.bonus(), "the one who delivered a part is owed the whole bonus: " + owed);
            double before = QShopEconomy.balance(solo);
            o.ledger.prepareCrewBonus(solo.getUUID(), solo.getInventory().save(new net.minecraft.nbt.ListTag()), before + owed, of(solo).careerXp(), owed);
            o.restart();   // the prepared record is on disk; the wallet has not moved
            check(o.ledger.player(solo.getUUID()).contains("pending"), "the prepared settlement survived the restart");
            WorkOrderService.recover(solo, o.ledger);
            check(QShopEconomy.balance(solo) == before + owed && o.ledger.crewBonusOwed(solo.getUUID()) == 0, "recovery paid the share once: " + QShopEconomy.balance(solo));
            WorkOrderService.recover(solo, o.ledger);
            check(CrewContracts.payOwed(solo, o.ledger) == 0 && QShopEconomy.balance(solo) == before + owed, "a second recovery and a pay-out pay nothing more");
        }
        helper.succeed();
    }
    // ------------------------------------------------------------------------------------------------ civic projects (D-171)

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void twoPeopleFinishACivicProjectTogetherItsStallsStandOnceAndTheCouncilsOrdersOpen(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Orders o = new Orders(helper, "valewood")) {
            CivicProjects.Project market = CivicProjects.CATALOG.find("underground_city:valewood_market");
            check(market != null && market.goal() == 5, "the market project loaded");
            ServerPlayer smith = citizen(helper, "uc_civ_smith", "smith", 3), gunsmith = citizen(helper, "uc_civ_gun", "gunsmith", 3);
            // the council's follow-up order is closed while the market is not finished
            ServerPlayer farmer = citizen(helper, "uc_civ_farmer", "farmer", 3);
            refused(() -> WorkOrderService.accept(farmer, o.ledger, of(farmer), "underground_city:council_stall_produce"), "opens once", "an order a civic project has not opened yet");
            // anybody can help: a Gunsmith brings lanterns (the order open to every career), a Smith brings frames; the count is shared
            for (int i = 0; i < 2; i++) {
                gunsmith.getInventory().add(new ItemStack(net.minecraft.world.item.Items.LANTERN, 4));
                deliver(gunsmith, o, "underground_city:civic_market_lanterns");
                o.ledger.advance(12000);   // the council's demand for lanterns refills
            }
            check(o.ledger.civic(market.id()).getInt("count") == 2 && o.ledger.trust(gunsmith.getUUID(), "valewood_council") == 2, "the Gunsmith's two contributions counted and raised their standing");
            for (int i = 0; i < 3; i++) {
                give(smith, "steel_bracket", 6); give(smith, "fasteners", 16);
                deliver(smith, o, "underground_city:civic_market_frames");
                o.ledger.advance(12000);
            }
            check(CivicProjects.finished(o.ledger, market.id()), "five contributions finished the project");
            // no more contributions are taken once it is finished, before anything changes hands
            gunsmith.getInventory().add(new ItemStack(net.minecraft.world.item.Items.LANTERN, 4));
            refused(() -> WorkOrderService.accept(gunsmith, o.ledger, of(gunsmith), "underground_city:civic_market_lanterns"), "done", "a contribution to a finished project");
            // the change in the world: built once (the test world has no city plan, so it is built at a spot the test chooses), never over anything
            BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.getLevel().setBlock(at.east(1).above(1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);   // something already there is never replaced
            CivicProjects.buildAtForTest(helper.getLevel(), o.ledger, market, at);
            check(helper.getLevel().getBlockState(at).is(net.minecraft.world.level.block.Blocks.DARK_OAK_FENCE) && helper.getLevel().getBlockState(at.above(2)).getBlock() instanceof net.minecraft.world.level.block.SlabBlock,
                "a stall stands where it was built");
            check(helper.getLevel().getBlockState(at.east(1).above(1)).is(net.minecraft.world.level.block.Blocks.STONE), "a block that was already there was left alone");
            check(CivicProjects.builtAt(o.ledger, market.id()).orElseThrow().equals(at), "where it stands is kept in the journal");
            o.restart();
            check(o.ledger.civic(market.id()).getBoolean("built") && CivicProjects.finished(o.ledger, market.id()), "finished and built survive a restart, so it is never built twice");
            // the council's better orders open, for whoever has its trust
            CitizenState.get(farmer.server).unlockSkill(farmer.getUUID(), "farmer.fundamentals");
            refused(() -> WorkOrderService.accept(farmer, o.ledger, of(farmer), "underground_city:council_stall_produce"), "trust", "the council's order without its trust");
            check(CivicProjects.refusal(o.ledger, WorkOrderCatalog.INSTANCE.find("underground_city:council_stall_produce").terms()) == null, "the project no longer holds the council's order back");
            check(CivicProjects.describe(o.ledger).get(0).contains("5/5"), "the board reads five of five: " + CivicProjects.describe(o.ledger));
            for (BlockPos p : BlockPos.betweenClosed(at, at.offset(7, 3, 2))) helper.getLevel().setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }
    // ------------------------------------------------------------------------------------------------ the bonded store job (D-173)

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theBondedStoreJobNamesItsStoreOnlyItsHolderFindsTheShipmentOnceAndItSettlesInBlackwater(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Orders o = new Orders(helper, "ironhaven")) {
            BlockPos centre = o.pos;
            int scx = (centre.getX() >> 4) + 1, scz = centre.getZ() >> 4;
            CityBuildings.Building store = new CityBuildings.Building("ironhaven", scx, scz, "underground_city:ih_warehouse", "", 2, 0, centre.getY(), null);
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> cx == scx && cz == scz ? java.util.Optional.of(store) : java.util.Optional.empty());
            try {
                ServerPlayer thief = citizen(helper, "uc_raid_thief", "farmer", 2), other = citizen(helper, "uc_raid_other", "smith", 2);
                WorkOrder job = WorkOrderCatalog.INSTANCE.find("underground_city:raid_bonded_store");
                check(job != null && job.underground() && job.terms().raid().equals(Raids.BONDED_STORE), "the job loaded");
                refused(() -> WorkOrderService.accept(thief, o.ledger, of(thief), job.id()), "Thief level", "a thief below level 3");
                CitizenState.get(thief.server).addThiefXp(thief.getUUID(), ProgressionRules.thiefXpAtLevel(Raids.MIN_THIEF_LEVEL));
                WorkOrderService.accept(thief, o.ledger, of(thief), job.id());
                var raid = Raids.open(thief, o.ledger).orElseThrow();
                check(raid.getLong("key") == store.key() && !raid.getBoolean("taken"), "taking the job named the store: " + raid);
                // the tracked destination is the store's door until the shipment is taken
                var d = Navigation.orderDestination(thief, job.id(), o.ledger).orElseThrow();
                check(d.city().equals("ironhaven") && d.title().contains("bonded store"), "the order is tracked to the store: " + d);
                // somebody else searching the store finds no shipment; the holder finds it once
                Raids.searchedForTest(other, helper.getLevel(), store, o.ledger);
                check(other.getInventory().countItem(UndergroundCity.item(Raids.SHIPMENT)) == 0, "nobody but the job holder finds the shipment");
                int heat = o.ledger.heat(thief.getUUID(), "ironhaven");
                Raids.searchedForTest(thief, helper.getLevel(), store, o.ledger);
                check(thief.getInventory().countItem(UndergroundCity.item(Raids.SHIPMENT)) == 1 && Raids.open(thief, o.ledger).orElseThrow().getBoolean("taken"), "the holder found the shipment");
                ItemStack shipment = thief.getInventory().items.stream().filter(st -> st.is(UndergroundCity.item(Raids.SHIPMENT))).findFirst().orElseThrow();
                check(StolenGoods.is(shipment) && thief.getUUID().equals(StolenGoods.thief(shipment)), "the shipment is marked stolen by its thief");
                check(o.ledger.heat(thief.getUUID(), "ironhaven") == Math.min(WorkOrderLedger.MAX_HEAT, heat + Raids.REPORT_HEAT), "the watchman's report heated Ironhaven");
                Raids.searchedForTest(thief, helper.getLevel(), store, o.ledger);
                check(thief.getInventory().countItem(UndergroundCity.item(Raids.SHIPMENT)) == 1, "the shipment is found once");
                String dimension = helper.getLevel().dimension().location().toString();
                CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("ironhaven", dimension, centre.getX() >> 4, centre.getZ() >> 4, 64),
                    new CityRegions.Region("blackwater", dimension, (centre.getX() >> 4) + 200, centre.getZ() >> 4, 64)));
                check(Navigation.orderDestination(thief, job.id(), o.ledger).orElseThrow().city().equals("blackwater"), "once taken, the order is tracked to Blackwater");
                // delivered at a terminal in Blackwater
                CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("blackwater", dimension, centre.getX() >> 4, centre.getZ() >> 4, 64)));
                double before = QShopEconomy.balance(thief);
                WorkOrderService.complete(thief, o.ledger, o.ledger.player(thief.getUUID()).getUUID("order"), centre);
                check(QShopEconomy.balance(thief) == before + job.credits() && thief.getInventory().countItem(UndergroundCity.item(Raids.SHIPMENT)) == 0, "Kessler paid for the shipment");
                check(Raids.open(thief, o.ledger).isEmpty(), "the job is over");
            } finally {
                CityBuildings.INSTANCE.overrideForTest(null);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recordedLostCitiesPlansSurviveASaveAndLoadUnchanged(GameTestHelper helper) {
        Map<Long, LostCitiesLink.ChunkSample> samples = Map.of(
            net.minecraft.world.level.ChunkPos.asLong(3, -7), new LostCitiesLink.ChunkSample(true, 0, "underground_city:ih_flats", "", 4, 1, 2, "ironhaven"),
            net.minecraft.world.level.ChunkPos.asLong(-12, 40), new LostCitiesLink.ChunkSample(true, 1, "", "", 0, 0, 0, "valewood"),
            net.minecraft.world.level.ChunkPos.asLong(90, 90), new LostCitiesLink.ChunkSample(false, 0, "", "", 0, 0, 0, ""));
        Map<Long, LostCitiesLink.ChunkSample> back = LostCitiesLink.storeRoundTripForTest(samples);
        check(back.equals(samples), "the record came back changed: " + back);
        helper.succeed();
    }
}
