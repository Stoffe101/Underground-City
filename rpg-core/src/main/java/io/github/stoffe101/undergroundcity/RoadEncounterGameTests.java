package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Roadside encounters (D-139): the shipped catalog, how often and which, what each choice settles (once), the courier's letter, and one encounter set and taken back beside a synthetic road. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoadEncounterGameTests {
    private RoadEncounterGameTests() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static void refused(Runnable action, String why) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new net.minecraft.gametest.framework.GameTestAssertException("not refused: " + why);
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", "smith");
        return player;
    }

    private record Journal(WorkOrderLedger ledger, Path path) {
        void close() throws java.io.IOException { Files.deleteIfExists(path); }
    }

    private static Journal journal(GameTestHelper helper) {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        return new Journal(new WorkOrderLedger(path), path);
    }

    private static RoadEncounters.Active standing(String encounter, String destination) {
        RoadEncounters.Active active = new RoadEncounters.Active();
        active.id = UUID.randomUUID(); active.encounter = encounter; active.road = "ironhaven_blackwater"; active.anchor = new BlockPos(100, 70, 100); active.destination = destination;
        return active;
    }

    private static RoadEncounters.Choice choice(RoadEncounters.Encounter encounter, String id) {
        return encounter.choices().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @GameTest(template = "empty")
    public static void theShippedEncountersCoverLegitUndergroundAndEnvironmentalAndAreSound(GameTestHelper helper) {
        List<RoadEncounters.Encounter> all = RoadEncounters.CATALOG.all();
        check(all.size() >= 4, "four encounters ship: " + all.stream().map(RoadEncounters.Encounter::id).toList());
        Set<String> kinds = all.stream().map(RoadEncounters.Encounter::kind).collect(Collectors.toSet());
        check(kinds.equals(Set.of("legit", "underground", "environmental")), "every kind is represented: " + kinds);
        var loot = helper.getLevel().getServer().getLootData();
        for (RoadEncounters.Encounter e : all) {
            for (RoadEncounters.Choice c : e.choices()) {
                if (!c.needItem().isEmpty()) check(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(new net.minecraft.resources.ResourceLocation(c.needItem())), e.id() + ": needs an unknown item " + c.needItem());
                if (!c.giveLoot().isEmpty()) check(loot.getLootTable(new net.minecraft.resources.ResourceLocation(c.giveLoot())) != net.minecraft.world.level.storage.loot.LootTable.EMPTY, e.id() + ": no loot table " + c.giveLoot());
                check(c.pay() <= 60 && c.cost() <= 60, e.id() + ": an encounter pays or costs no more than a small order");
            }
            for (RoadEncounters.Prop p : e.props()) {
                check(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(new net.minecraft.resources.ResourceLocation(p.block())), e.id() + ": unknown prop block " + p.block());
                if (!p.loot().isEmpty()) check(loot.getLootTable(new net.minecraft.resources.ResourceLocation(p.loot())) != net.minecraft.world.level.storage.loot.LootTable.EMPTY, e.id() + ": no loot table " + p.loot());
            }
        }
        RoadEncounters.Encounter hot = RoadEncounters.CATALOG.find("hot_goods");
        check(hot != null && hot.kind().equals("underground") && !hot.onRoad("ironhaven_valewood") && hot.onRoad("valewood_blackwater"), "goods off a lorry are offered only on the roads to Blackwater");
        check(new ItemStack(Items.HONEY_BOTTLE).is(RoadEncounters.DRINKS) && new ItemStack(Items.POTION).is(RoadEncounters.DRINKS), "a honey bottle or a bottle of water is something to drink");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void encountersComeOnlyAfterRoadTimeAndNeverTheSameTwiceRunning(GameTestHelper helper) {
        check(!RoadEncounters.due(RoadEncounters.QUIET_SECONDS - 10, 0.0), "not before the quiet time is up, whatever the roll");
        check(RoadEncounters.due(RoadEncounters.QUIET_SECONDS, 0.0) && !RoadEncounters.due(RoadEncounters.QUIET_SECONDS + 500, 0.99), "after it, only on the roll");
        // a crossing of 13 minutes of travel at the check rate
        RandomSource random = RandomSource.create(42);
        int[] seen = new int[6];
        for (int trip = 0; trip < 2000; trip++) {
            int seconds = 0, met = 0;
            for (int check = 0; check < 13 * 60 / 10; check++) {
                seconds += 10;
                if (RoadEncounters.due(seconds, random.nextDouble())) { met++; seconds = 0; }
            }
            seen[Math.min(5, met)]++;
        }
        check(seen[3] + seen[4] + seen[5] == 0 && seen[0] <= 10, "a crossing meets one or two, never three, and almost never none: " + java.util.Arrays.toString(seen));
        check(seen[2] > seen[1], "usually two: " + java.util.Arrays.toString(seen));
        List<RoadEncounters.Encounter> all = RoadEncounters.CATALOG.all();
        Map<String, Integer> picked = new HashMap<>();
        String last = "stranded_driver";
        for (int i = 0; i < 400; i++) {
            RoadEncounters.Encounter e = RoadEncounters.pick(all, "ironhaven_valewood", last, random);
            check(e != null && !e.id().equals(last), "never the same twice running: " + last);
            check(!e.id().equals("hot_goods"), "no lorry goods on the guarded highway");
            picked.merge(e.id(), 1, Integer::sum);
            last = e.id();
        }
        check(picked.keySet().containsAll(Set.of("stranded_driver", "courier_letter", "spilled_load")), "every fitting encounter comes up: " + picked);
        check(RoadEncounters.letterFee(0) == 20 && RoadEncounters.letterFee(400) == 36 && RoadEncounters.letterFee(5000) == 75, "a letter's fee grows with the way still to go, within bounds");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDriverIsHelpedOnceAndPaysThroughTheLedger(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Journal journal = journal(helper);
        try {
            FakePlayer helperPlayer = player(helper, "uc_enc_driver");
            QShopEconomy.applyBalance(helperPlayer, 5);
            RoadEncounters.Encounter driver = RoadEncounters.CATALOG.find("stranded_driver");
            RoadEncounters.Active car = standing("stranded_driver", "valewood");
            int xp = CitizenState.get(helperPlayer.server).find(helperPlayer.getUUID()).orElseThrow().careerXp();
            refused(() -> RoadEncounters.settle(helperPlayer, journal.ledger(), driver, car, choice(driver, "fix")), "a repair without the part");
            refused(() -> RoadEncounters.settle(helperPlayer, journal.ledger(), driver, car, choice(driver, "drink")), "a drink without one");
            check(QShopEconomy.balance(helperPlayer) == 5 && car.resolved < 0, "a refusal changes nothing");
            ItemStack marked = new ItemStack(Items.HONEY_BOTTLE);
            StolenGoods.mark(marked, "ironhaven");
            helperPlayer.getInventory().add(marked);
            refused(() -> RoadEncounters.settle(helperPlayer, journal.ledger(), driver, car, choice(driver, "drink")), "a stolen drink is not handed over");
            helperPlayer.getInventory().clearContent();
            helperPlayer.getInventory().add(new ItemStack(Items.HONEY_BOTTLE, 2));
            String reply = RoadEncounters.settle(helperPlayer, journal.ledger(), driver, car, choice(driver, "drink"));
            check(!reply.isEmpty() && QShopEconomy.balance(helperPlayer) == 17, "the drink pays 12: " + QShopEconomy.balance(helperPlayer));
            check(helperPlayer.getInventory().countItem(Items.HONEY_BOTTLE) == 1, "one bottle was handed over");
            check(CitizenState.get(helperPlayer.server).find(helperPlayer.getUUID()).orElseThrow().careerXp() == xp + 20, "and 20 career XP");
            check(car.resolved >= 0 && journal.ledger().hasEncounter(helperPlayer.getUUID(), car.id) && !journal.ledger().player(helperPlayer.getUUID()).contains("pending"), "settled and recorded");
            helperPlayer.getInventory().add(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(UndergroundCity.MOD_ID, "machined_parts"))));
            refused(() -> RoadEncounters.settle(helperPlayer, journal.ledger(), driver, car, choice(driver, "fix")), "a settled driver pays nobody twice");
            check(QShopEconomy.balance(helperPlayer) == 17, "nothing more was paid");
            // the same encounter replayed into the ledger (a second click that got through) is refused there too
            RoadEncounters.Active again = standing("stranded_driver", "valewood");
            again.id = car.id;
            refused(() -> RoadEncounters.settle(helperPlayer, journal.ledger(), driver, again, choice(driver, "fix")), "the ledger refuses an encounter it settled");
        } finally {
            journal.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void goodsOffALorryCostCreditsAndComeMarkedFromAnotherCity(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Journal journal = journal(helper);
        try {
            FakePlayer buyer = player(helper, "uc_enc_hot");
            RoadEncounters.Encounter hot = RoadEncounters.CATALOG.find("hot_goods");
            RoadEncounters.Active van = standing("hot_goods", "blackwater");
            QShopEconomy.applyBalance(buyer, 20);
            refused(() -> RoadEncounters.settle(buyer, journal.ledger(), hot, van, choice(hot, "buy")), "not enough credits");
            QShopEconomy.applyBalance(buyer, 50);
            RoadEncounters.settle(buyer, journal.ledger(), hot, van, choice(hot, "buy"));
            check(QShopEconomy.balance(buyer) == 15, "the lot cost 35: " + QShopEconomy.balance(buyer));
            int goods = 0;
            for (int i = 0; i < buyer.getInventory().getContainerSize(); i++) {
                ItemStack stack = buyer.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                goods += stack.getCount();
                check(StolenGoods.is(stack) && !StolenGoods.city(stack).equals("blackwater") && !StolenGoods.city(stack).isEmpty(), "every piece is marked stolen in another city: " + stack.getTag());
                check(FenceCatalog.INSTANCE.worth(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) > 0, "a fence buys it: " + stack);
            }
            check(goods >= 1, "the lot holds something");
            check(RoadEncounters.settle(buyer, journal.ledger(), hot, standing("hot_goods", "blackwater"), choice(hot, "ask")).contains("cannery"), "asking only talks, and points at the cannery");
        } finally {
            journal.close();
        }
        helper.succeed();
    }

    /** The courier's letter: given once, paid once at a terminal in the city it is addressed to, nothing anywhere else, and a copy pays nothing. */
    @GameTest(template = "empty", batch = "uc_encounter_letters")
    public static void aLetterPaysOnceInTheCityItIsAddressedTo(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available() || !QShopEconomy.configured(helper.getLevel().getServer())) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        List<CityRegions.Region> real = CityRegions.INSTANCE.all();
        BlockPos here = helper.absolutePos(BlockPos.ZERO);
        String dimension = level.dimension().location().toString();
        CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("blackwater", dimension, here.getX() >> 4, here.getZ() >> 4, 48),
            new CityRegions.Region("ironhaven", dimension, (here.getX() >> 4) + 40, here.getZ() >> 4, 48)));
        Journal journal = journal(helper);
        try {
            FakePlayer courier = player(helper, "uc_enc_letter");
            QShopEconomy.applyBalance(courier, 0);
            RoadEncounters.Encounter letter = RoadEncounters.CATALOG.find("courier_letter");
            RoadEncounters.Active stop = standing("courier_letter", "blackwater");
            stop.anchor = here.offset(400, 0, 0);
            RoadEncounters.settle(courier, journal.ledger(), letter, stop, choice(letter, "carry"));
            ItemStack envelope = ItemStack.EMPTY;
            for (int i = 0; i < courier.getInventory().getContainerSize(); i++) if (courier.getInventory().getItem(i).is(UndergroundCity.SEALED_ENVELOPE.get())) envelope = courier.getInventory().getItem(i);
            var tag = envelope.getTagElement("uc_letter");
            check(tag != null && tag.getString("to").equals("blackwater") && tag.getInt("fee") >= 20 && tag.getInt("fee") <= 75, "a sealed letter addressed to Blackwater: " + tag);
            int fee = tag.getInt("fee");
            check(QShopEconomy.balance(courier) == 0, "taking the letter pays nothing yet");
            ItemStack copy = envelope.copy();
            check(RoadEncounters.deliver(courier, here.offset(40 * 16, 0, 0), journal.ledger()) == 0 && courier.getInventory().countItem(UndergroundCity.SEALED_ENVELOPE.get()) == 1, "Ironhaven does not take a letter for Blackwater");
            check(RoadEncounters.deliver(courier, here.offset(2, 0, 2), journal.ledger()) == fee, "Blackwater pays the fee");
            check(QShopEconomy.balance(courier) == fee && courier.getInventory().countItem(UndergroundCity.SEALED_ENVELOPE.get()) == 0, "paid and handed over");
            courier.getInventory().add(copy);
            check(RoadEncounters.deliver(courier, here.offset(2, 0, 2), journal.ledger()) == 0, "a copy of a delivered letter pays nothing");
            check(QShopEconomy.balance(courier) == fee && courier.getInventory().countItem(UndergroundCity.SEALED_ENVELOPE.get()) == 0, "and the copy is taken back");
        } finally {
            CityRegions.INSTANCE.replaceForTest(real);
            journal.close();
        }
        helper.succeed();
    }

    /** An encounter set up the road beside a synthetic road on a platform in the sky: off the deck, with its person and props; ended, it takes its props back but leaves an opened barrel. */
    @GameTest(template = "empty", batch = "uc_encounter_world", timeoutTicks = 800)
    public static void anEncounterStandsOffTheRoadAndIsTakenBackWhenItEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        int y = level.getMaxBuildHeight() - 100, ox = arena.getX(), oz = arena.getZ() + 1400;
        BlockPos lo = new BlockPos(ox - 100, y - 4, oz - 30), hi = new BlockPos(ox + 100, y + 6, oz + 30);
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        WaysideShelterGameTests.emptyTheWorldData();
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{ox - 400, ox + 400}, new double[]{oz, oz}, 7, List.of());
        plan.build(new CorridorPlan.Terrain() {
            @Override public int surface(int x, int z) { return y + 1; }
            @Override public int floor(int x, int z) { return y + 1; }
        });
        Runnable cleanup = () -> {
            for (BlockPos at : BlockPos.betweenClosed(lo, hi)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
            WaysideShelterGameTests.restoreTheWorldData();
        };
        UndergroundGameTests.whenEntitiesTick(helper, level, List.of(lo, hi, new BlockPos(ox, y, oz)), 40, () -> {
            RoadEncounters.State state = RoadEncounters.state(level.getServer());   // the world's own record: a person joins only for an encounter the world knows
            RoadEncounters.Active load, driver;
            try {
                for (BlockPos at : BlockPos.betweenClosed(new BlockPos(ox - 96, y, oz - 26), new BlockPos(ox + 96, y, oz + 26))) level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                FakePlayer traveller = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_enc_traveller"));
                // the road runs from x = ox - 400; the traveller is at x = ox - 60 heading east
                load = RoadEncounters.start(level, state, List.of(plan), plan, 340, 1, traveller, RoadEncounters.CATALOG.find("spilled_load"));
                check(load != null, "a spilled load is set up the road");
                check(load.anchor.getX() > ox - 60 && load.anchor.getX() < ox + 30, "up the road from the traveller: " + load.anchor);
                check(Math.abs(load.anchor.getZ() - oz) > plan.reach(), "off the deck: " + load.anchor);
                check(load.anchor.getY() == y + 1, "on the ground: " + load.anchor);
                check(load.people.isEmpty() && load.props.size() == RoadEncounters.CATALOG.find("spilled_load").props().size(), "nobody, and its props: " + load.props.size());
                driver = RoadEncounters.start(level, state, List.of(plan), plan, 420, 1, traveller, RoadEncounters.CATALOG.find("stranded_driver"));
                check(driver != null && driver.people.size() == 1 && driver.destination.isEmpty() == CityRegions.INSTANCE.all().isEmpty(), "a stranded driver with their car: " + (driver == null ? null : driver.people));
                check(state.active.containsKey(load.id) && state.active.containsKey(driver.id) && state.traveller(traveller.getUUID()).last.equals("stranded_driver"), "the world remembers both, and what the traveller met last");
                var reloaded = RoadEncounters.State.load(state.save(new net.minecraft.nbt.CompoundTag()));
                check(reloaded.active.keySet().equals(state.active.keySet()) && reloaded.active.get(driver.id).props.size() == driver.props.size(), "the record survives a save and load");
            } catch (RuntimeException error) {
                cleanup.run();
                throw error;
            }
            UndergroundGameTests.waitUntil(helper, () -> level.getEntity(driver.people.get(0)) != null, 0, () -> {
                try {
                    check(level.getEntity(driver.people.get(0)) instanceof Villager person && RoadEncounters.isEncounter(person) && person.isInvulnerable() && person.hasCustomName(), "a named, protected stranger");
                    // open one barrel of the load: its loot is rolled, so it stays when the load is taken back
                    BlockPos opened = null;
                    for (var prop : load.props) if (prop.getBoolean("loot")) { opened = BlockPos.of(prop.getLong("pos")); break; }
                    check(opened != null && level.getBlockEntity(opened) instanceof RandomizableContainerBlockEntity, "the load has barrels");
                    ((RandomizableContainerBlockEntity) level.getBlockEntity(opened)).unpackLootTable(null);
                    List<BlockPos> others = load.props.stream().map(p -> BlockPos.of(p.getLong("pos"))).toList();
                    BlockPos keep = opened;
                    load.ending = true; driver.ending = true;
                    RoadEncounters.tend(level, state);
                    check(!state.active.containsKey(load.id) && !state.active.containsKey(driver.id), "both ended: " + state.active.keySet());
                    check(level.getBlockState(keep).is(Blocks.BARREL), "the opened barrel stays");
                    for (BlockPos at : others) if (!at.equals(keep)) check(level.getBlockState(at).isAir(), "an untouched prop is taken back: " + at + " " + level.getBlockState(at));
                    check(level.getEntity(driver.people.get(0)) == null, "the driver has gone");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            });
        });
    }
}
