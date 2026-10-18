package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewGameTests.Rig;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * Purchases paid from a Crew bank under fully shared finances (D-089). The first test is the regression for the defect an independent review found:
 * the bank used to move credits into the buyer's wallet BEFORE the purchase was known to be possible, so a purchase that was then refused left the
 * bank's credits in a wallet whose owner had no right to withdraw them. Every other test here pins the protocol that replaced it: the bank's share
 * is a durable hold, the work-order journal's prepared record is the single commit point, and a hold nothing names is released.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrewPurchaseGameTests {
    private CrewPurchaseGameTests() {}

    static final String GOODS = "underground_city:uc_test_goods";
    static final int PRICE = 100;

    /** Everything a refused purchase must leave alone. */
    record Snap(double founderWallet, double memberWallet, long bank, int founderGoods, int memberGoods, int units, int holds, int logSize) {}

    /** A scratch world for one purchase test: a Crew of two, a priced test commodity, a terminal position inside Ironhaven. */
    static final class Shop implements AutoCloseable {
        final Rig rig;
        final GameTestHelper helper;
        final FakePlayer founder, member;
        WorkOrderLedger orders;
        final Path journal;
        final BlockPos pos;
        final List<CityRegions.Region> originalRegions;
        final List<RegionalStock> originalStock;

        Shop(GameTestHelper helper, int price, int stock) throws Exception {
            this.helper = helper;
            rig = new Rig(helper);
            CrewService.wallet = rig.previousWallet; // the live Q Shop provider
            String tag = Integer.toHexString(UUID.randomUUID().hashCode());
            founder = player(helper, "uc_pf" + tag, "farmer");
            member = player(helper, "uc_pm" + tag, "smith");
            rig.ledger.create(founder.getUUID(), founder.getGameProfile().getName(), "Purchase Crew");
            rig.ledger.accept(rig.ledger.invite(founder.getUUID(), member.getUUID(), member.getGameProfile().getName(), founder.getGameProfile().getName()).id(),
                member.getUUID(), member.getGameProfile().getName());
            journal = rig.server.getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/purchase-" + UUID.randomUUID() + ".dat");
            orders = new WorkOrderLedger(journal);
            pos = helper.absolutePos(new BlockPos(0, 2, 0));
            originalRegions = List.copyOf(CityRegions.INSTANCE.all());
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("ironhaven", helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
            originalStock = RegionalCatalog.INSTANCE.allForTest();
            RegionalCatalog.INSTANCE.replaceForTest(List.of(new RegionalStock(GOODS, "ironhaven", ResourceLocation.parse("minecraft:bread"), price, stock, 24, 1, 1200, 8)));
        }

        /** The bank is filled through the real deposit path; the wallets are set afterwards. */
        void fund(long bank, double founderWallet, double memberWallet) {
            QShopEconomy.applyBalance(founder, bank);
            if (bank > 0) CrewService.deposit(founder, id(), bank);
            QShopEconomy.applyBalance(founder, founderWallet);
            QShopEconomy.applyBalance(member, memberWallet);
        }

        /** Fully shared finances in force: proposed by the founder, agreed by the member. */
        void shared() {
            rig.ledger.setMode(founder.getUUID(), founder.getGameProfile().getName(), Mode.SHARED);
            rig.ledger.setConsent(member.getUUID(), member.getGameProfile().getName(), true);
            check(crew().effectiveMode() == Mode.SHARED, "fully shared finances should be in force");
        }

        void allowSpending() { rig.ledger.setPermission(founder.getUUID(), founder.getGameProfile().getName(), Role.MEMBER, Perm.SPEND, true); }

        /** The Crew as the current ledger holds it (the object changes when the ledger is read back after a restart). */
        Crew crew() { return rig.ledger.crewOf(founder.getUUID()); }

        CitizenState.Citizen citizen(FakePlayer who) { return CitizenState.get(rig.server).find(who.getUUID()).orElseThrow(); }

        int units() { return orders.regionalUnits(RegionalCatalog.INSTANCE.find(GOODS)); }

        int goods(FakePlayer who) { return who.getInventory().countItem(Items.BREAD); }

        void buy(FakePlayer who, int quantity, UUID nonce) { RegionalMarket.purchase(who, pos, orders, citizen(who), GOODS, quantity, nonce); }

        double wallet(FakePlayer who) { return QShopEconomy.balance(who); }

        Snap snap() {
            return new Snap(wallet(founder), wallet(member), crew().bank, goods(founder), goods(member), units(), rig.ledger.allHolds().size(), crew().log.size());
        }

        /** Every credit of the test world that is not yet spent on goods: both wallets, the bank, owed payouts and holds or transfers in flight. */
        double unspent() { return wallet(founder) + wallet(member) + rig.ledger.totalHeld() + rig.ledger.totalInFlight(); }

        /** What a restart does to the two ledgers: both files are read again from disk. */
        void restart() { rig.restart(); orders = new WorkOrderLedger(journal); }

        /** Server start, then the buyer logging in: the same recovery the real events run. */
        void recoverAll(FakePlayer... who) {
            CrewService.resolveHolds(rig.server, orders);
            for (FakePlayer p : who) WorkOrderService.recover(p, orders);
        }

        @Override public void close() throws Exception {
            CrewService.faultAt = "";
            RegionalCatalog.INSTANCE.replaceForTest(originalStock);
            CityRegions.INSTANCE.replaceForTest(originalRegions);
            Files.deleteIfExists(journal);
            rig.close();
        }
    }

    static void refused(Runnable action, String why) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Accepted: " + why);
    }

    static void same(Snap before, Snap after, String why) {
        check(before.equals(after), why + ": expected " + before + " but found " + after);
    }

    // ------------------------------------------------------------------------------------------------------------ the regression

    /**
     * THE REGRESSION. A Crew bank of 20, a member with 0 in their own wallet, a purchase of 100, fully shared finances in force. Before the fix the
     * bank moved its 20 credits into the member's wallet, the purchase was then refused for want of 100, and the member kept the 20. The member has no
     * right to withdraw. After the fix the refused purchase changes nothing at all. (Run against the unfixed code this test fails with
     * "the refused purchase left the member with 20.0 credits of the Crew's money".)
     */
    @GameTest(template = "empty")
    public static void aRefusedSharedPurchaseLeavesTheBanksCreditsInTheBank(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        for (boolean mayAlsoSpend : new boolean[]{false, true}) {
            try (Shop shop = new Shop(helper, PRICE, 5)) {
                shop.fund(20, 0, 0);
                shop.shared();
                if (mayAlsoSpend) shop.allowSpending();
                check(shop.crew().bank == 20 && shop.wallet(shop.member) == 0, "the scenario is a bank of 20 and an empty wallet");
                check(!shop.crew().can(shop.member.getUUID(), Perm.WITHDRAW), "the member must have no right to withdraw");
                Snap before = shop.snap();
                refused(() -> shop.buy(shop.member, 1, id()), "a purchase of 100 credits with 20 in the bank and 0 in the wallet");
                check(shop.wallet(shop.member) == 0, "the refused purchase left the member with " + shop.wallet(shop.member) + " credits of the Crew's money");
                check(shop.crew().bank == 20, "the bank holds " + shop.crew().bank + " after a refused purchase, expected 20");
                same(before, shop.snap(), "a refused purchase (member may spend: " + mayAlsoSpend + ")");
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ funding

    @GameTest(template = "empty")
    public static void insufficientCombinedFundsRefuseAndChangeNothing(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(20, 0, 50);
            shop.shared();
            shop.allowSpending();
            Snap before = shop.snap();
            refused(() -> shop.buy(shop.member, 1, id()), "20 from the bank and 50 of their own for a price of 100");
            same(before, shop.snap(), "insufficient combined funds");
            double total = shop.unspent();
            check(total == 70, "no credit was created or destroyed: " + total);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theBankPaysFirstAndTheBuyersOwnWalletPaysTheRest(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(60, 0, 50);
            shop.shared();
            shop.allowSpending();
            double start = shop.unspent();
            shop.buy(shop.member, 1, id());
            check(shop.goods(shop.member) == 1, "the goods were not delivered");
            check(shop.crew().bank == 0 && shop.wallet(shop.member) == 10, "bank " + shop.crew().bank + " wallet " + shop.wallet(shop.member) + ", expected 0 and 10 (60 from the bank, 40 from the wallet)");
            check(shop.units() == 4, "the shared stock was not reduced");
            check(start - shop.unspent() == PRICE, "exactly the price left the economy, not " + (start - shop.unspent()));
            check(shop.rig.ledger.allHolds().isEmpty() && shop.orders.player(shop.member.getUUID()).getCompound("pending").isEmpty(), "nothing is left in flight");
            check(shop.crew().log.getFirst().kind().equals("PURCHASE") && shop.crew().log.getFirst().amount() == 60, "the Crew's history records the 60 credits the bank paid");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPurchaseCanBePaidEntirelyByTheCrew(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(150, 0, 0);
            shop.shared();
            shop.allowSpending();
            shop.buy(shop.member, 1, id());
            check(shop.goods(shop.member) == 1 && shop.crew().bank == 50 && shop.wallet(shop.member) == 0,
                "bank " + shop.crew().bank + " wallet " + shop.wallet(shop.member) + " goods " + shop.goods(shop.member) + ", expected 50, 0 and 1");
            // and nothing was ever credited to the member's wallet on the way: the wallet is exactly what it was
            check(shop.unspent() == 50, "the unspent credits are the bank's 50: " + shop.unspent());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withoutTheRightToSpendTheBankIsNeverUsed(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        // 1. shared finances in force, but the member may not spend Crew money
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(500, 0, 0);
            shop.shared();
            check(!shop.crew().can(shop.member.getUUID(), Perm.SPEND), "a default member holds no right to spend");
            Snap before = shop.snap();
            try {
                shop.buy(shop.member, 1, id());
                throw new AssertionError("a member without the right to spend bought with an empty wallet");
            } catch (IllegalStateException refusal) {
                check(refusal.getMessage().contains("not allowed to spend Crew money"), "the refusal should say why the bank is closed to them: " + refusal.getMessage());
            }
            same(before, shop.snap(), "a member without the right to spend");
            // with their own credits they still buy, and the bank is not touched
            QShopEconomy.applyBalance(shop.member, 120);
            shop.buy(shop.member, 1, id());
            check(shop.goods(shop.member) == 1 && shop.crew().bank == 500 && shop.wallet(shop.member) == 20, "a member without the right pays from their own wallet only");
        }
        // 2. the right to spend is not enough while the Crew has not agreed to share
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(500, 0, 0);
            shop.allowSpending();
            rig(shop).setMode(shop.founder.getUUID(), "f", Mode.SHARED); // proposed, the member has not agreed
            check(shop.crew().effectiveMode() == Mode.BANK, "an unagreed proposal is not in force");
            Snap before = shop.snap();
            refused(() -> shop.buy(shop.member, 1, id()), "spending the bank before every member agreed");
            same(before, shop.snap(), "shared finances not in force");
        }
        helper.succeed();
    }

    private static CrewLedger rig(Shop shop) { return shop.rig.ledger; }

    // ------------------------------------------------------------------------------------------------------------ nothing to extract

    /** The reviewer's question: can a player pull Crew money out by making purchases fail on purpose? Seven kinds of failure, two kinds of member, many tries. */
    @GameTest(template = "empty")
    public static void failingPurchasesOnPurposeExtractNothingFromTheCrew(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        for (boolean mayAlsoSpend : new boolean[]{false, true}) {
            try (Shop shop = new Shop(helper, PRICE, 3)) {
                shop.fund(90, 0, 40); // 130 in all: enough for one unit of 100, so a plain one-unit purchase would succeed; every attempt below fails for another reason
                shop.shared();
                if (mayAlsoSpend) shop.allowSpending();
                Snap before = shop.snap();
                UUID reused = id();
                for (int attempt = 0; attempt < 70; attempt++) {
                    int kind = attempt % 7;
                    String what = "attempt " + attempt + " kind " + kind + " (member may spend: " + mayAlsoSpend + ")";
                    switch (kind) {
                        case 0 -> refused(() -> shop.buy(shop.member, 2, id()), what + ": more than the funds");          // 200 for 130
                        case 1 -> refused(() -> shop.buy(shop.member, 0, id()), what + ": quantity zero");
                        case 2 -> refused(() -> shop.buy(shop.member, 99, id()), what + ": more than one order allows");
                        case 3 -> refused(() -> RegionalMarket.purchase(shop.member, shop.pos, shop.orders, shop.citizen(shop.member), "underground_city:machined_parts", 1, id()),
                            what + ": a line this exchange does not stock");
                        case 4 -> refused(() -> shop.buy(shop.member, 4, id()), what + ": more than the shared stock holds"); // stock is 3
                        case 5 -> {
                            CrewService.faultAt = "purchase_after_reserve";
                            try { refused(() -> shop.buy(shop.member, 1, id()), what + ": a failure after the hold was taken"); } finally { CrewService.faultAt = ""; }
                        }
                        default -> { // a full inventory
                            for (int slot = 0; slot < 36; slot++) shop.member.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                            try { refused(() -> shop.buy(shop.member, 1, reused), what + ": no room"); } finally { shop.member.getInventory().clearContent(); }
                        }
                    }
                    same(before, shop.snap(), what);
                }
                check(shop.crew().bank == 90 && shop.wallet(shop.member) == 40 && shop.wallet(shop.founder) == 0, "bank and wallets are exactly what they were");
                check(shop.rig.ledger.allHolds().isEmpty() && shop.rig.ledger.allPending().isEmpty(), "no hold or transfer was left behind");
                // and nothing can be withdrawn without the right either
                refused(() -> CrewService.withdraw(shop.member, id(), 1), "withdrawing without the right to");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientStockAndAFullInventoryRefuseBeforeAnyCreditMoves(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, 10, 2)) {
            shop.fund(500, 0, 0);
            shop.shared();
            shop.allowSpending();
            Snap before = shop.snap();
            refused(() -> shop.buy(shop.member, 3, id()), "three units when two are in stock");
            same(before, shop.snap(), "insufficient stock");
            for (int slot = 0; slot < 36; slot++) shop.member.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            Snap full = shop.snap();
            refused(() -> shop.buy(shop.member, 1, id()), "a purchase with no room for the goods");
            same(full, shop.snap(), "a full inventory");
            shop.member.getInventory().clearContent();
            shop.buy(shop.member, 2, id()); // and the same member can buy once there is stock and room
            check(shop.goods(shop.member) == 2 && shop.crew().bank == 480 && shop.units() == 0, "the purchase that was possible went through once");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ duplicates

    @GameTest(template = "empty")
    public static void aDuplicatedRequestChargesAndDeliversOnce(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(300, 0, 0);
            shop.shared();
            shop.allowSpending();
            UUID nonce = id();
            shop.buy(shop.member, 1, nonce);
            Snap once = shop.snap();
            refused(() -> shop.buy(shop.member, 1, nonce), "the same request again after it was delivered");
            same(once, shop.snap(), "a replayed purchase request");
            check(once.bank() == 200 && once.memberGoods() == 1, "charged once: bank " + once.bank() + " goods " + once.memberGoods());
            // the same request again while the first is still waiting to be recovered
            UUID second = id();
            CrewService.crashAt = "purchase_after_prepare";
            try { shop.buy(shop.member, 1, second); throw new AssertionError("the simulated crash did not happen"); } catch (CrewService.SimulatedCrash expected) { /* died */ }
            CrewService.crashAt = "";
            refused(() -> shop.buy(shop.member, 1, second), "the same request while the first awaits recovery");
            shop.restart();
            shop.recoverAll(shop.member);
            check(shop.crew().bank == 100 && shop.goods(shop.member) == 2, "the interrupted purchase completed exactly once: bank " + shop.crew().bank + " goods " + shop.goods(shop.member));
            refused(() -> shop.buy(shop.member, 1, second), "the same request after recovery");
            check(shop.crew().bank == 100 && shop.goods(shop.member) == 2, "still charged once");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ failures and crashes

    @GameTest(template = "empty")
    public static void anExceptionAtEachStepEndsWithTheWholePurchaseOrNone(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        // before the journal's prepared record: everything goes back, at once, without any restart
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(150, 0, 0);
            shop.shared();
            shop.allowSpending();
            Snap before = shop.snap();
            CrewService.faultAt = "purchase_after_reserve";
            refused(() -> shop.buy(shop.member, 1, id()), "a failure after the bank's share was held and before the purchase was prepared");
            CrewService.faultAt = "";
            same(before, shop.snap(), "the hold returned to the bank");
        }
        // after the prepared record: the purchase is decided and rolls forward exactly once (the hold is NOT given back)
        for (String stage : List.of("purchase_after_prepare", "purchase_after_apply", "purchase_after_commit")) {
            try (Shop shop = new Shop(helper, PRICE, 5)) {
                shop.fund(150, 0, 30);
                shop.shared();
                shop.allowSpending();
                double start = shop.unspent();
                CrewService.faultAt = stage;
                try { shop.buy(shop.member, 1, id()); throw new AssertionError("the injected failure at " + stage + " did not happen"); } catch (IllegalStateException expected) { /* failed */ }
                CrewService.faultAt = "";
                // the buyer reconnects: the journal's recovery finishes the purchase
                shop.recoverAll(shop.member);
                check(shop.goods(shop.member) == 1, stage + ": goods delivered " + shop.goods(shop.member) + " times");
                check(shop.crew().bank == 50 && shop.wallet(shop.member) == 30, stage + ": bank " + shop.crew().bank + " wallet " + shop.wallet(shop.member) + ", expected 50 and 30");
                check(start - shop.unspent() == PRICE, stage + ": exactly the price left the economy");
                check(shop.rig.ledger.allHolds().isEmpty() && shop.orders.player(shop.member.getUUID()).getCompound("pending").isEmpty(), stage + ": nothing left in flight");
                check(shop.crew().log.stream().filter(e -> e.kind().equals("PURCHASE")).count() == 1, stage + ": the history records the spend once");
                // and recovery run again changes nothing
                Snap done = shop.snap();
                shop.recoverAll(shop.member);
                same(done, shop.snap(), stage + ": recovery is idempotent");
            }
        }
        helper.succeed();
    }

    /** The process dies at each boundary, with and without the player's data having been written, and the ledgers are read back from disk. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aCrashAtAnyBoundaryOfACrewFundedPurchaseLosesAndDuplicatesNothing(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        record Case(String stage, boolean playerSaved, boolean delivered) {}
        List<Case> cases = List.of(
            new Case("purchase_after_reserve", false, false),   // hold durable, no prepared record: the purchase never happened
            new Case("purchase_after_prepare", false, true),    // prepared record durable, nothing applied: rolls forward
            new Case("purchase_after_apply", false, true),      // applied in memory only, the player file was not written
            new Case("purchase_after_apply", true, true),       // applied and the player file was written
            new Case("purchase_after_commit", false, true),
            new Case("purchase_after_commit", true, true));
        for (Case c : cases) {
            try (Shop shop = new Shop(helper, PRICE, 5)) {
                shop.fund(70, 0, 90);
                shop.shared();
                shop.allowSpending();
                double startWallet = shop.wallet(shop.member);
                ListTag savedInventory = shop.member.getInventory().save(new ListTag());
                double start = shop.unspent();
                CrewService.crashAt = c.stage();
                try { shop.buy(shop.member, 1, id()); throw new AssertionError("the simulated crash at " + c + " did not happen"); } catch (CrewService.SimulatedCrash expected) { /* the process died */ }
                CrewService.crashAt = "";
                if (!c.playerSaved()) { // the player file on disk is the one from before the purchase
                    QShopEconomy.applyBalance(shop.member, startWallet);
                    shop.member.getInventory().load(savedInventory);
                }
                shop.restart();
                String where = c.toString();
                // the files on disk tell what was decided
                boolean committedBeforeTheCrash = c.stage().equals("purchase_after_commit");
                check(shop.rig.ledger.allHolds().isEmpty() == committedBeforeTheCrash, where + ": the hold must be durable until it is decided, and gone once committed");
                check(shop.rig.ledger.allPending().isEmpty(), where + ": a purchase never leaves a wallet-first transfer behind");
                int decided = CrewService.resolveHolds(shop.rig.server, shop.orders);
                WorkOrderService.recover(shop.member, shop.orders); // the buyer logs in
                if (c.stage().equals("purchase_after_commit")) check(decided == 0, where + ": the hold had been committed before the crash");
                else check(decided == 1, where + ": " + decided + " holds decided");
                check(shop.rig.ledger.allHolds().isEmpty(), where + ": the hold is still there after recovery");
                if (c.delivered()) {
                    check(shop.goods(shop.member) == 1, where + ": goods " + shop.goods(shop.member));
                    check(shop.crew().bank == 0 && shop.wallet(shop.member) == 60, where + ": bank " + shop.crew().bank + " wallet " + shop.wallet(shop.member) + ", expected 0 and 60");
                    check(shop.units() == 4, where + ": stock " + shop.units());
                    check(start - shop.unspent() == PRICE, where + ": exactly the price left the economy");
                    check(shop.crew().log.stream().filter(e -> e.kind().equals("PURCHASE")).count() == 1, where + ": history");
                } else {
                    check(shop.goods(shop.member) == 0 && shop.crew().bank == 70 && shop.wallet(shop.member) == 90 && shop.units() == 5, where + ": the undecided purchase left a trace: bank "
                        + shop.crew().bank + " wallet " + shop.wallet(shop.member) + " goods " + shop.goods(shop.member));
                    check(shop.unspent() == start, where + ": credits created or destroyed");
                    shop.buy(shop.member, 1, id()); // and the buyer can simply try again
                    check(shop.goods(shop.member) == 1 && shop.crew().bank == 0, where + ": a fresh attempt after the crash works");
                }
                Snap done = shop.snap();
                check(CrewService.resolveHolds(shop.rig.server, shop.orders) == 0, where + ": hold recovery is not idempotent");
                WorkOrderService.recover(shop.member, shop.orders);
                same(done, shop.snap(), where + ": a second recovery changed something");
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ two clients

    /**
     * Two members spend from one bank. Packets are handled one at a time on the server thread, so "concurrent" purchases are sequential steps; what can differ is
     * where a dead process left the other's purchase. Both orders are exercised, and the bank is never spent twice.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void twoMembersSpendingTheSameBankNeverSpendOneCreditTwice(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        // 1. in turn: the first uses 80 of the bank's 100, the second gets the remaining 20 and pays 60 itself
        try (Shop shop = new Shop(helper, 80, 5)) {
            shop.fund(100, 100, 100);
            shop.shared();
            shop.allowSpending();
            double start = shop.unspent();
            shop.buy(shop.founder, 1, id());
            check(shop.crew().bank == 20 && shop.wallet(shop.founder) == 100, "the founder's purchase came from the bank: " + shop.crew().bank + "/" + shop.wallet(shop.founder));
            shop.buy(shop.member, 1, id());
            check(shop.crew().bank == 0 && shop.wallet(shop.member) == 40, "the second member got the remaining 20 from the bank and paid 60: " + shop.crew().bank + "/" + shop.wallet(shop.member));
            check(start - shop.unspent() == 160, "exactly 160 credits were spent");
        }
        // 2. the first member's process died after taking its hold: the second member's purchase decides it (released) and sees the whole bank
        try (Shop shop = new Shop(helper, 80, 5)) {
            shop.fund(100, 100, 100);
            shop.shared();
            shop.allowSpending();
            CrewService.crashAt = "purchase_after_reserve";
            try { shop.buy(shop.founder, 1, id()); throw new AssertionError("no crash"); } catch (CrewService.SimulatedCrash expected) { /* died */ }
            CrewService.crashAt = "";
            check(shop.crew().bank == 20 && shop.rig.ledger.allHolds().size() == 1, "the dead purchase's 80 is held, not spendable by the other member");
            shop.restart();
            shop.buy(shop.member, 1, id());
            check(shop.rig.ledger.allHolds().isEmpty() && shop.crew().bank == 20 && shop.wallet(shop.member) == 100, "the undecided hold went back to the bank and the member's purchase used 80 of it: bank "
                + shop.crew().bank + " wallet " + shop.wallet(shop.member));
        }
        // 3. the first member's purchase had been prepared before the crash: the second member's purchase commits it, and the bank is spent exactly once
        try (Shop shop = new Shop(helper, 80, 5)) {
            shop.fund(100, 100, 100);
            shop.shared();
            shop.allowSpending();
            CrewService.crashAt = "purchase_after_prepare";
            try { shop.buy(shop.founder, 1, id()); throw new AssertionError("no crash"); } catch (CrewService.SimulatedCrash expected) { /* died */ }
            CrewService.crashAt = "";
            shop.restart();
            shop.buy(shop.member, 1, id());                        // 20 from the bank, 60 from its own wallet
            check(shop.crew().bank == 0 && shop.wallet(shop.member) == 40 && shop.rig.ledger.allHolds().isEmpty(), "the prepared purchase's hold was committed, not reused: bank "
                + shop.crew().bank + " wallet " + shop.wallet(shop.member));
            WorkOrderService.recover(shop.founder, shop.orders);   // the founder logs in later and the rolled-forward purchase completes
            check(shop.goods(shop.founder) == 1 && shop.goods(shop.member) == 1 && shop.wallet(shop.founder) == 100, "both purchases were delivered once");
            check(shop.crew().log.stream().filter(e -> e.kind().equals("PURCHASE")).count() == 2, "two spends are recorded");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCrewCannotBeDisbandedOrLeftWhileAPurchaseHoldIsUnresolved(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Shop shop = new Shop(helper, PRICE, 5)) {
            shop.fund(150, 0, 0);
            shop.shared();
            shop.allowSpending();
            CrewService.crashAt = "purchase_after_reserve";
            try { shop.buy(shop.member, 1, id()); throw new AssertionError("no crash"); } catch (CrewService.SimulatedCrash expected) { /* died */ }
            CrewService.crashAt = "";
            refused(() -> shop.rig.ledger.disband(shop.founder.getUUID(), "f"), "disbanding while credits are held for a purchase");
            refused(() -> shop.rig.ledger.leave(shop.member.getUUID(), "m"), "leaving while credits are held for a purchase");
            shop.restart();
            CrewService.resolveHolds(shop.rig.server, shop.orders);
            check(shop.crew().bank == 150, "the undecided hold returned to the bank");
            long paid = shop.rig.ledger.disband(shop.founder.getUUID(), "f");
            check(paid == 150, "and the bank is then paid out whole: " + paid);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ chaos

    /** Hundreds of random purchases by two members with random funding, random failures and random process deaths; the credits are conserved at every step. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void randomisedCrewPurchasesConserveEveryCredit(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        String[] stages = {"", "", "", "purchase_after_reserve", "purchase_after_prepare", "purchase_after_apply", "purchase_after_commit"};
        try (Shop shop = new Shop(helper, 7, 24)) {
            shop.fund(400, 300, 300);
            shop.shared();
            shop.allowSpending();
            Random random = new Random(20261018L);
            double total = shop.unspent();
            long goods = 0;
            int completed = 0;
            for (int step = 0; step < 220; step++) {
                FakePlayer who = random.nextBoolean() ? shop.founder : shop.member;
                int quantity = 1 + random.nextInt(5);
                String stage = stages[random.nextInt(stages.length)];
                boolean fault = random.nextInt(5) == 0;
                double walletBefore = shop.wallet(who);
                ListTag inventoryBefore = who.getInventory().save(new ListTag());
                boolean playerSaved = random.nextBoolean();
                CrewService.crashAt = fault ? "" : stage;
                CrewService.faultAt = fault ? stages[3 + random.nextInt(4)] : "";
                try {
                    shop.buy(who, quantity, random.nextInt(9) == 0 ? new UUID(1, 1) : id()); // the odd duplicated nonce
                } catch (CrewService.SimulatedCrash died) {
                    if (!playerSaved) { QShopEconomy.applyBalance(who, walletBefore); who.getInventory().load(inventoryBefore); }
                    shop.restart();
                } catch (IllegalStateException refusedOrFailed) { /* a refusal or an injected failure */ }
                CrewService.crashAt = ""; CrewService.faultAt = "";
                shop.recoverAll(shop.founder, shop.member); // every reconnect and start
                goods = shop.goods(shop.founder) + shop.goods(shop.member);
                check(shop.rig.ledger.allHolds().isEmpty() && shop.rig.ledger.allPending().isEmpty(), "step " + step + ": something was left unresolved");
                check(shop.crew().bank >= 0 && shop.wallet(shop.founder) >= 0 && shop.wallet(shop.member) >= 0, "step " + step + ": a balance went negative");
                check(total - shop.unspent() == goods * 7, "step " + step + ": " + goods + " goods at 7 credits but " + (total - shop.unspent()) + " credits left the economy");
                if (goods > completed) completed = (int) goods;
                if (shop.units() < 6) { // the shared stock is finite; let it refill so the run keeps buying
                    shop.orders.advance(1200L * 24);
                }
            }
            check(completed > 20, "the run should have completed many purchases, completed " + completed);
        }
        helper.succeed();
    }
}
