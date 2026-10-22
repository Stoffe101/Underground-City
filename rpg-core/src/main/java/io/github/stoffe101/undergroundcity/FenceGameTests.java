package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * The fence (D-125): what a fence pays and for what, the network's budget, the heat of selling a city's own goods back to it, the hours, the crash-safe sale record, and the safe that only gives.
 * The sale runs through {@link FenceService#settle} with an isolated journal, the same call the network handler makes once it has found the fence and the seller in reach.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FenceGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError("A sale that should be refused went through");
    }

    private static final FenceCatalog.Fence YARD = new FenceCatalog.Fence("ironhaven", "The Yard", "", 45, 30, 1, 240, 40, 10, 21, 3);

    private static FakePlayer player(GameTestHelper helper, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_f_" + career));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    private static CitizenState.Citizen citizen(FakePlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(); }

    private static ItemStack stolen(net.minecraft.world.item.Item item, int count, String city) {
        ItemStack stack = new ItemStack(item, count);
        StolenGoods.mark(stack, city);
        return stack;
    }

    private record Journal(WorkOrderLedger ledger, Path path) {
        void close() throws java.io.IOException { Files.deleteIfExists(path); }
    }

    private static Journal journal(GameTestHelper helper) {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        return new Journal(new WorkOrderLedger(path), path);
    }

    private static Map<String, FenceCatalog.Fence> savedFences;
    private static Map<String, Integer> savedValues;
    private static void install() {
        savedFences = FenceCatalog.INSTANCE.all(); savedValues = FenceCatalog.INSTANCE.values();
        FenceCatalog.INSTANCE.setForTest(Map.of("ironhaven", YARD), Map.of("minecraft:gold_ingot", 38, "minecraft:emerald", 30, "minecraft:clock", 150));
    }
    private static void restore() { FenceCatalog.INSTANCE.setForTest(savedFences, savedValues); }

    @GameTest(template = "empty")
    public static void theShippedFencesAreSaneAndAgreeWithTheHouseholds(GameTestHelper helper) {
        check(!FenceCatalog.INSTANCE.all().isEmpty(), "no fence network loaded");
        check(FenceCatalog.INSTANCE.forCity("valewood").isEmpty(), "Valewood has no fence by design: stolen goods must travel");
        for (FenceCatalog.Fence fence : FenceCatalog.INSTANCE.all().values()) {
            check(fence.ratePercent() <= FenceCatalog.MAX_RATE_PERCENT && fence.sameCityRatePercent() < fence.ratePercent(), fence.city() + ": selling a city's own goods back must pay less");
            for (int hour = 0; hour < 24; hour++) check(fence.about(hour) == HouseholdPlan.fenceHours(hour), fence.city() + ": the fence deals at " + hour + " but the household plan disagrees");
        }
        // nothing a person can come by for free, and nothing worthless, has a worth
        for (String free : new String[]{"minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_log", "minecraft:paper", "minecraft:bread", "minecraft:stick"}) {
            check(FenceCatalog.INSTANCE.worth(free) == 0, free + " must not be bought by a fence");
        }
        // a fence's worth is never above the honest price of a line the cities sell, so a fence never pays more than buying costs
        for (RegionalStock stock : RegionalCatalog.INSTANCE.all()) {
            int worth = FenceCatalog.INSTANCE.worth(stock.item().toString());
            int cheapest = RegionalCatalog.INSTANCE.all().stream().filter(s -> s.item().equals(stock.item())).mapToInt(s -> (int) s.unitPrice()).min().orElse(Integer.MAX_VALUE);
            if (worth > 0) check(worth <= cheapest, stock.item() + ": a fence values it at " + worth + ", above the cheapest honest price " + cheapest);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFenceBuysOnlyMarkedGoodsAndLeavesHonestOnes(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        install();
        Journal journal = journal(helper);
        try {
            FakePlayer thief = player(helper, "farmer");
            QShopEconomy.applyBalance(thief, 10);
            thief.getInventory().add(stolen(Items.GOLD_INGOT, 2, "valewood"));
            thief.getInventory().add(new ItemStack(Items.GOLD_INGOT, 3));                  // honest gold beside it
            thief.getInventory().add(stolen(Items.COBBLESTONE, 16, "valewood"));          // marked but worthless to a fence
            var rows = FenceService.rows(thief.getInventory(), YARD, citizen(thief));
            check(rows.size() == 1 && rows.get(0).item().equals("minecraft:gold_ingot") && rows.get(0).count() == 2, "the fence offers for the wrong goods: " + rows);
            check(rows.get(0).unit() == 38 * 45 / 100, "goods from another city fetch 45 percent: " + rows.get(0).unit());
            long paid = FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "*", "", UUID.randomUUID(), 22);
            check(paid == 2L * (38 * 45 / 100), "paid " + paid);
            check(QShopEconomy.balance(thief) == 10 + paid, "the wallet is " + QShopEconomy.balance(thief));
            int honest = 0, marked = 0;
            for (ItemStack s : thief.getInventory().items) if (s.is(Items.GOLD_INGOT)) { if (StolenGoods.is(s)) marked += s.getCount(); else honest += s.getCount(); }
            check(marked == 0 && honest == 3, "after the sale: " + marked + " stolen and " + honest + " honest gold");
            check(thief.getInventory().countItem(Items.COBBLESTONE) == 16, "the worthless stolen stone was taken");
            check(journal.ledger().player(thief.getUUID()).getInt("sales") == 1 && !journal.ledger().player(thief.getUUID()).contains("pending"), "the sale did not settle exactly once");
            check(journal.ledger().heat(thief.getUUID(), "ironhaven") == 0, "goods from another city raised heat");
            // nothing left to sell, honest goods are never bought
            rejected(() -> FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "*", "", UUID.randomUUID(), 22));
            check(QShopEconomy.balance(thief) == 10 + paid, "a refused sale moved credits");
        } finally { journal.close(); restore(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sellingACitysOwnGoodsBackPaysLessAndRaisesHeat(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        install();
        Journal journal = journal(helper);
        try {
            FakePlayer thief = player(helper, "smith");
            QShopEconomy.applyBalance(thief, 0);
            thief.getInventory().add(stolen(Items.EMERALD, 2, "ironhaven"));
            var rows = FenceService.rows(thief.getInventory(), YARD, citizen(thief));
            check(rows.size() == 1 && rows.get(0).unit() == 30 * 30 / 100, "home-city goods should fetch 30 percent: " + rows);
            long paid = FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "minecraft:emerald", "ironhaven", UUID.randomUUID(), 23);
            check(paid == 2L * 9, "paid " + paid);
            check(journal.ledger().heat(thief.getUUID(), "ironhaven") == 1, "selling Ironhaven's goods in Ironhaven did not raise heat there");
        } finally { journal.close(); restore(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theBudgetLimitsASaleAndRefillsOnlyInActiveTime(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        install();
        Journal journal = journal(helper);
        try {
            FakePlayer thief = player(helper, "technician");
            QShopEconomy.applyBalance(thief, 0);
            thief.getInventory().add(stolen(Items.CLOCK, 1, "blackwater"));
            thief.getInventory().add(stolen(Items.CLOCK, 1, "blackwater"));
            thief.getInventory().add(stolen(Items.CLOCK, 1, "blackwater"));
            thief.getInventory().add(stolen(Items.CLOCK, 1, "blackwater"));
            int unit = 150 * 45 / 100;                                                    // 67
            long paid = FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "minecraft:clock", "blackwater", UUID.randomUUID(), 1);
            check(paid == 3L * unit, "a 240 budget pays for three clocks at " + unit + ", not " + paid);
            check(thief.getInventory().countItem(Items.CLOCK) == 1, "the fourth clock should still be carried");
            int left = journal.ledger().fenceBudget(YARD);
            check(left == 240 - 3 * unit, "budget left " + left);
            rejected(() -> FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "minecraft:clock", "blackwater", UUID.randomUUID(), 1));
            // a week away refills nothing: the clock is the active-play clock
            check(journal.ledger().fenceBudget(YARD) == left, "the budget refilled while nobody played");
            journal.ledger().advance(YARD.refillTicks());
            check(journal.ledger().fenceBudget(YARD) == left + 40, "ten active minutes should refill 40 credits");
            journal.ledger().advance(YARD.refillTicks() * 100);
            check(journal.ledger().fenceBudget(YARD) == 240, "the budget never stockpiles above its ceiling");
        } finally { journal.close(); restore(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFenceDealsOnlyAtNightAndNeverTwiceForOneNonce(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        install();
        Journal journal = journal(helper);
        try {
            FakePlayer thief = player(helper, "vintner");
            QShopEconomy.applyBalance(thief, 0);
            thief.getInventory().add(stolen(Items.GOLD_INGOT, 4, "blackwater"));
            rejected(() -> FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "*", "", UUID.randomUUID(), 12));
            check(thief.getInventory().countItem(Items.GOLD_INGOT) == 4 && QShopEconomy.balance(thief) == 0, "a midday refusal changed something");
            UUID nonce = UUID.randomUUID();
            thief.getInventory().removeItem(0, 2);   // keep two in the first slot
            long paid = FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "*", "", nonce, 21);
            thief.getInventory().add(stolen(Items.GOLD_INGOT, 2, "blackwater"));
            rejected(() -> FenceService.settle(thief, journal.ledger(), citizen(thief), YARD, "*", "", nonce, 21));
            check(QShopEconomy.balance(thief) == paid && thief.getInventory().countItem(Items.GOLD_INGOT) == 2, "a repeated nonce sold again");
        } finally { journal.close(); restore(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPreparedSaleIsReplayedOnceAfterACrash(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        install();
        Journal journal = journal(helper);
        try {
            FakePlayer thief = player(helper, "gunsmith");
            QShopEconomy.applyBalance(thief, 50);
            thief.getInventory().add(stolen(Items.GOLD_INGOT, 2, "valewood"));
            thief.getInventory().add(new ItemStack(Items.BREAD, 5));
            // the crash window: the decision is on disk, the wallet and the pockets were never touched
            var after = new net.minecraft.world.entity.player.Inventory(thief);
            after.add(new ItemStack(Items.BREAD, 5));
            int start = journal.ledger().fenceBudget(YARD);
            UUID nonce = UUID.randomUUID();
            journal.ledger().prepareSale(thief.getUUID(), nonce, YARD, 34, after.save(new ListTag()), 84, citizen(thief).careerXp(), Map.of());
            WorkOrderLedger restarted = new WorkOrderLedger(journal.path());
            check(restarted.player(thief.getUUID()).contains("pending"), "the prepared sale was lost on reload");
            check(restarted.fenceBudget(YARD) == start - 34, "the budget decrement was not durable with the record");
            WorkOrderService.recover(thief, restarted);
            WorkOrderService.recover(thief, restarted);
            check(QShopEconomy.balance(thief) == 84, "recovery paid " + QShopEconomy.balance(thief));
            check(thief.getInventory().countItem(Items.GOLD_INGOT) == 0 && thief.getInventory().countItem(Items.BREAD) == 5, "recovery left the pockets wrong");
            check(restarted.player(thief.getUUID()).getInt("sales") == 1 && !restarted.player(thief.getUUID()).contains("pending"), "recovery did not settle exactly once");
            check(restarted.hasSale(thief.getUUID(), nonce), "the sale's nonce was lost");
        } finally { journal.close(); restore(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theUndergroundSkillsRaiseTheRateWithinItsCeiling(GameTestHelper helper) {
        FakePlayer smith = player(helper, "smith");
        CitizenState.Citizen plain = citizen(smith);
        int base = FenceService.rate(YARD, "valewood", plain);
        check(base == 45, "a citizen with no skills gets the fence's own rate: " + base);
        int bonus = SkillRules.undergroundPayoutPercent(SkillCatalog.INSTANCE.tree("smith"), java.util.Set.copyOf(SkillCatalog.INSTANCE.tree("smith").stream().map(SkillNode::id).toList()));
        check(bonus > 0, "the smith tree has underground payout skills");
        check(FenceService.unitPrice(150, Math.min(FenceCatalog.MAX_RATE_PERCENT, 45 + Math.min(FenceService.SKILL_POINTS_MAX, bonus))) <= 150 * FenceCatalog.MAX_RATE_PERCENT / 100, "a skilled rate broke the ceiling");
        check(FenceService.unitPrice(1, 45) == 1 && FenceService.unitPrice(0, 45) == 0, "a worthless item has no price, a cheap one at least one credit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSafeGivesButNeverTakes(GameTestHelper helper) {
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        helper.getLevel().setBlock(at, UndergroundCity.PROPERTY_CACHE.get().defaultBlockState(), 3);
        var safe = (PropertyCacheBlockEntity) helper.getLevel().getBlockEntity(at);
        safe.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        FakePlayer player = player(helper, "farmer");
        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
        var menu = safe.createMenu(1, player.getInventory(), player);
        check(menu != null, "no menu");
        for (int i = 0; i < PropertyCacheBlockEntity.SLOTS; i++) check(!menu.slots.get(i).mayPlace(new ItemStack(Items.COBBLESTONE)), "safe slot " + i + " accepts a player's goods");
        check(menu.slots.get(0).mayPickup(player), "the safe's goods cannot be taken");
        check(!safe.canPlaceItem(1, new ItemStack(Items.COBBLESTONE)), "the safe accepts goods from outside");
        // a shift-click from the player's inventory moves nothing into the safe
        int playerSlot = PropertyCacheBlockEntity.SLOTS + 27;   // the first hotbar square in a one-row chest menu
        menu.quickMoveStack(player, playerSlot);
        for (int i = 0; i < PropertyCacheBlockEntity.SLOTS; i++) check(!safe.getItem(i).is(Items.COBBLESTONE), "a shift-click put stone into the safe");
        helper.succeed();
    }
}
