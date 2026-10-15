package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dedicated-server tests of the legal and underground career systems (D-049..D-053): skill trees, gated and
 * located deliveries, trust, the city laws and checkpoint inspections, once-only exceptional loot, the Sable Leaf
 * crop and the terminal packet. They run against the real loaded datapack.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UndergroundGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe action accepted");
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, path); }
    private static Item core(String path) { return ForgeRegistries.ITEMS.getValue(id(path)); }

    private static FakePlayer player(GameTestHelper helper, String city, String career, int level) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_u_" + career));
        player.getInventory().clearContent();
        CitizenState state = CitizenState.get(player.server);
        state.register(player.getUUID(), city, career);
        if (level > 1) state.addCareerXp(player.getUUID(), (int) (125.0 * Math.pow(level - 1, 2)));
        return player;
    }

    private static CitizenState.Citizen citizen(FakePlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
    }

    private static void learn(FakePlayer player, String... skills) {
        for (String skill : skills) CitizenState.get(player.server).unlockSkill(player.getUUID(), skill);
    }

    private record Fixture(WorkOrderLedger ledger, Path path, List<CityRegions.Region> regions, BlockPos pos) {
        void close() throws java.io.IOException {
            CityRegions.INSTANCE.replaceForTest(regions);
            Files.deleteIfExists(path);
        }
    }

    /** An isolated journal plus a city circle of {@code city} around the test terminal. */
    private static Fixture fixture(GameTestHelper helper, String city) {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        BlockPos pos = helper.absolutePos(new BlockPos(0, 2, 0));
        List<CityRegions.Region> original = CityRegions.INSTANCE.all();
        CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city,
            helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
        return new Fixture(new WorkOrderLedger(path), path, original, pos);
    }

    private static void setCity(GameTestHelper helper, BlockPos pos, String city) {
        CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city,
            helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
    }

    // ------------------------------------------------------------------------------------------ skills

    @GameTest(template = "empty")
    public static void skillTreesAreCompleteAndNeverForceAMoralChoice(GameTestHelper helper) {
        for (String career : ProgressionRules.CAREERS) {
            List<SkillNode> tree = SkillCatalog.INSTANCE.tree(career);
            check(!tree.isEmpty(), "Missing skill tree for " + career);
            check(tree.stream().anyMatch(n -> n.branch().equals("legal")) && tree.stream().anyMatch(n -> n.branch().equals("underground")),
                career + " needs a legal and an underground branch");
            // Simulate a character who buys EVERY node in dependency order with the points of a level-30 career:
            // the whole tree, both branches, must be affordable (no permanent alignment, no lockout).
            Set<String> owned = new HashSet<>();
            int points = SkillRules.pointsEarned(30);
            boolean progress = true;
            while (progress) {
                progress = false;
                for (SkillNode node : tree) {
                    if (!owned.contains(node.id()) && SkillRules.blocker(node, SkillCatalog.INSTANCE.index(), 30, owned, points) == null) {
                        owned.add(node.id()); points -= node.cost(); progress = true;
                    }
                }
            }
            check(owned.size() == tree.size(), career + ": every skill must be reachable, unlocked " + owned.size() + " of " + tree.size());
            check(points >= 0, career + ": tree costs more than a maximum-level career earns");
            // An underground-first character is never locked out of the legal branch: take underground nodes first.
            Set<String> ugFirst = new HashSet<>();
            int budget = SkillRules.pointsEarned(12);
            for (SkillNode node : SkillRules.displayOrder(tree)) {
                if (node.branch().equals("underground") && budget >= node.cost() && SkillRules.blocker(node, SkillCatalog.INSTANCE.index(), 12, ugFirst, budget) == null) {
                    ugFirst.add(node.id()); budget -= node.cost();
                }
            }
            check(tree.stream().filter(n -> n.branch().equals("legal") && n.requires().stream().allMatch(r -> ugFirst.contains(r) || SkillCatalog.INSTANCE.find(r).branch().equals("core")
                || SkillCatalog.INSTANCE.find(r).branch().equals("legal"))).count() > 0, career + ": legal skills must stay reachable");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void skillUnlockRulesPersistenceAndBothBranchesOnOneCharacter(GameTestHelper helper) {
        FakePlayer player = player(helper, "valewood", "farmer", 1);
        CitizenState state = CitizenState.get(player.server);
        SkillNode fundamentals = SkillCatalog.INSTANCE.find("farmer.fundamentals");
        check(SkillService.blocker(citizen(player), fundamentals).contains("level 2"), "Level gate missing");
        check(SkillService.pointsAvailable(citizen(player)) == 0, "A level-1 career has no skill points");
        rejected(() -> SkillService.learn(player, "farmer.fundamentals"));
        check(!SkillService.has(player, "farmer.fundamentals"), "Unlocked without a point");
        state.addCareerXp(player.getUUID(), 125 * 3 * 3); // level 4
        check(citizen(player).careerLevel() == 4 && SkillService.pointsAvailable(citizen(player)) == 3, "Points follow levels");
        SkillService.learn(player, "farmer.fundamentals");
        check(SkillService.has(player, "farmer.fundamentals") && SkillService.pointsAvailable(citizen(player)) == 2, "Fundamentals not learned");
        check("Already unlocked".equals(SkillService.blocker(citizen(player), fundamentals)), "A skill cannot be bought twice");
        rejected(() -> SkillService.learn(player, "farmer.restricted_cultivation")); // needs greenhouse_management and level 6
        check(!SkillService.has(player, "farmer.restricted_cultivation"), "Prerequisite skipped");
        SkillService.learn(player, "farmer.greenhouse_management");
        check(SkillService.has(player, "farmer.greenhouse_management") && SkillService.pointsAvailable(citizen(player)) == 0, "Legal skill not learned");
        // Take the underground branch too: level up, buy it. Nothing about the legal skills changes.
        state.addCareerXp(player.getUUID(), 125 * 40); // level 8
        SkillService.learn(player, "farmer.restricted_cultivation");
        check(SkillService.has(player, "farmer.restricted_cultivation") && SkillService.has(player, "farmer.greenhouse_management"),
            "Legal and underground skills must coexist on one character");
        // Persistence through the saved-data round trip.
        CitizenState reloaded = CitizenState.load(state.save(new CompoundTag()));
        check(reloaded.find(player.getUUID()).orElseThrow().skills().containsAll(Set.of("farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation")),
            "Skills lost on reload");
        // Another profession's skill cannot be bought.
        rejected(() -> SkillService.learn(player, "smith.fundamentals"));
        check(!SkillService.has(player, "smith.fundamentals"), "Cross-career skill accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void utilitySkillsDiscountTheExchangeWithinTheCap(GameTestHelper helper) {
        FakePlayer player = player(helper, "valewood", "farmer", 12);
        RegionalStock stock = RegionalCatalog.INSTANCE.forCity("ironhaven").get(0);
        check(RegionalMarket.unitPrice(stock, citizen(player)) == stock.unitPrice(), "No skill, no discount");
        learn(player, "farmer.fundamentals", "farmer.field_logistics");
        int discounted = RegionalMarket.unitPrice(stock, citizen(player));
        check(discounted == Math.round(stock.unitPrice() * 0.95f) && discounted < stock.unitPrice(), "Utility discount missing: " + discounted);
        check(SkillService.exchangeDiscount(citizen(player)) <= SkillNode.MAX_TOTAL_DISCOUNT, "Discount above the cap");
        for (String career : ProgressionRules.CAREERS) {
            Set<String> everything = new HashSet<>();
            SkillCatalog.INSTANCE.tree(career).forEach(n -> everything.add(n.id()));
            check(SkillRules.exchangeDiscount(SkillCatalog.INSTANCE.tree(career), everything) <= SkillNode.MAX_TOTAL_DISCOUNT, career + " total discount over cap");
            check(SkillRules.inspectionModifier(SkillCatalog.INSTANCE.tree(career), everything) >= -0.35, career + " concealment over cap");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ contracts

    @GameTest(template = "empty")
    public static void everyCareerHasLegalAndUndergroundWorkAndTheDataIsCoherent(GameTestHelper helper) {
        boolean tacz = ModList.get().isLoaded("tacz");
        for (String career : ProgressionRules.CAREERS) {
            List<WorkOrder> orders = WorkOrderCatalog.INSTANCE.forCareer(career);
            boolean legal = false, underground = false;
            for (WorkOrder order : orders) {
                WorkOrder.Terms terms = order.terms();
                if (!terms.requiresSkill().isEmpty()) {
                    SkillNode node = SkillCatalog.INSTANCE.find(terms.requiresSkill());
                    check(node != null && node.career().equals(career), order.id() + ": unknown or foreign skill " + terms.requiresSkill());
                    check(order.underground() == node.branch().equals("underground"), order.id() + ": legality must match the branch of its skill");
                }
                if (!terms.buyer().isEmpty()) {
                    BuyerCatalog.Buyer buyer = BuyerCatalog.INSTANCE.find(terms.buyer());
                    check(buyer != null && buyer.underground() == order.underground(), order.id() + ": buyer missing or of the wrong legality");
                }
                if (order.underground()) check(!terms.buyer().isEmpty() && !terms.requiresSkill().isEmpty(), order.id() + ": underground work needs a buyer and a skill");
                else check(terms.minTrust() == 0 && terms.trustGain() == 0, order.id() + ": legal work carries no trust");
                if (!terms.requiresSkill().isEmpty()) { if (order.underground()) underground = true; else legal = true; }
                else if (!order.underground()) legal = true;
            }
            if (career.equals("gunsmith") && !tacz) continue; // gun contracts need TaCZ; they are skipped, not broken
            check(legal && underground, career + " needs both legal and underground contracts");
        }
        check(!BuyerCatalog.INSTANCE.all().isEmpty() && BuyerCatalog.INSTANCE.all().stream().anyMatch(BuyerCatalog.Buyer::underground), "Buyers missing");
        for (BuyerCatalog.Buyer buyer : BuyerCatalog.INSTANCE.all()) check(ProgressionRules.validCity(buyer.city()), "Buyer in unknown city");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void undergroundDeliveryNeedsTheSkillTheBuyersCityAndPaysOnce(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            FakePlayer player = player(helper, "valewood", "farmer", 10);
            QShopEconomy.applyBalance(player, 0);
            WorkOrder order = WorkOrderCatalog.INSTANCE.find("underground_city:quiet_bales");
            check(order != null && order.underground() && order.terms().buyer().equals("marrow"), "Quiet bale contract missing");
            rejected(() -> WorkOrderService.accept(player, fixture.ledger, citizen(player), order.id()));
            learn(player, "farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.underground_trade");
            WorkOrderService.accept(player, fixture.ledger, citizen(player), order.id());
            UUID nonce = fixture.ledger.player(player.getUUID()).getUUID("order");
            player.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
            // Valewood is not the buyer's city: the goods must travel to Blackwater.
            rejected(() -> WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos));
            check(player.getInventory().countItem(core("sable_leaf_bale")) == 6 && QShopEconomy.balance(player) == 0, "A refused delivery changed something");
            setCity(helper, fixture.pos, "blackwater");
            rejected(() -> WorkOrderService.complete(player, fixture.ledger, nonce, null)); // no terminal, no delivery
            WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos);
            check(player.getInventory().countItem(core("sable_leaf_bale")) == 0, "Bales not consumed");
            check(QShopEconomy.balance(player) == order.credits(), "Wrong underground payout");
            check(fixture.ledger.trust(player.getUUID(), "marrow") == 1, "Trust not earned");
            check(fixture.ledger.heat(player.getUUID(), "blackwater") <= 1, "Heat can rise by at most one per hand-off");
            rejected(() -> WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos));
            check(QShopEconomy.balance(player) == order.credits(), "A repeated delivery paid twice");
            // The ledger survives a restart: trust is durable.
            check(new WorkOrderLedger(fixture.path).trust(player.getUUID(), "marrow") == 1, "Trust lost on reload");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void trustGatesTheBiggerContractAndRewardsArriveWithThePayment(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "blackwater");
        try {
            FakePlayer player = player(helper, "valewood", "farmer", 14);
            QShopEconomy.applyBalance(player, 0);
            learn(player, "farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.underground_trade", "farmer.grey_market_contacts");
            WorkOrder big = WorkOrderCatalog.INSTANCE.find("underground_city:reserve_load");
            check(big.terms().minTrust() == 2 && !big.terms().rewards().isEmpty(), "Reserve load must need trust and carry a reward");
            rejected(() -> WorkOrderService.accept(player, fixture.ledger, citizen(player), big.id()));
            // Two small contracts earn the trust (settled through the journal, exactly as a real delivery).
            for (int run = 0; run < 2; run++) {
                WorkOrder small = WorkOrderCatalog.INSTANCE.find("underground_city:quiet_bales");
                WorkOrderService.accept(player, fixture.ledger, citizen(player), small.id());
                UUID nonce = fixture.ledger.player(player.getUUID()).getUUID("order");
                player.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
                WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos);
                fixture.ledger.advance(small.refillTicks()); // let the buyer's demand recover between runs
            }
            check(fixture.ledger.trust(player.getUUID(), "marrow") == 2, "Two deliveries should earn trust 2");
            WorkOrderService.accept(player, fixture.ledger, citizen(player), big.id());
            UUID nonce = fixture.ledger.player(player.getUUID()).getUUID("order");
            player.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 12));
            double before = QShopEconomy.balance(player);
            WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos);
            check(QShopEconomy.balance(player) == before + big.credits(), "Wrong reserve payout");
            check(player.getInventory().countItem(core("sable_cutting")) == 2 && player.getInventory().countItem(core("sable_leaf_bale")) == 0, "Reward items not delivered with the payment");
            check(fixture.ledger.trust(player.getUUID(), "marrow") == 3, "Trust gain missing");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gunRequirementsAcceptOnlyPlainFactoryWeapons(GameTestHelper helper) {
        // A fake "gun" item with the same NBT shape as TaCZ's keeps this independent of the mod.
        ResourceLocation paper = ResourceLocation.parse("minecraft:paper");
        WorkOrder.Requirement requirement = new WorkOrder.Requirement(paper, 1, List.of("tacz:glock_17", "tacz:cz75"), "Licensed pistol");
        ItemStack plain = new ItemStack(Items.PAPER);
        plain.getOrCreateTag().putString("GunId", "tacz:glock_17");
        check(WorkOrder.matchesFactoryGun(requirement, plain), "Plain factory gun rejected");
        ItemStack wrong = new ItemStack(Items.PAPER);
        wrong.getOrCreateTag().putString("GunId", "tacz:uzi");
        check(!WorkOrder.matchesFactoryGun(requirement, wrong), "A gun of another class qualified");
        ItemStack scoped = plain.copy();
        scoped.getOrCreateTag().putString("AttachmentSCOPE", "tacz:scope_elcan_4x");
        check(!WorkOrder.matchesFactoryGun(requirement, scoped), "A customised gun would be consumed with its attachments");
        ItemStack named = plain.copy();
        named.setHoverName(net.minecraft.network.chat.Component.literal("Dad's pistol"));
        check(!WorkOrder.matchesFactoryGun(requirement, named), "A named gun qualified");
        check(!WorkOrder.matchesFactoryGun(requirement, new ItemStack(Items.PAPER)), "An item without a GunId qualified");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ law and inspection

    @GameTest(template = "empty")
    public static void contrabandIsClassifiedByItemAndNbtAndSpecificBeatsCatchAll(GameTestHelper helper) {
        check("restricted_cultivar".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(core("sable_leaf_bale")))), "Bale not controlled");
        check("restricted_cultivar".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(core("sable_cutting")))), "Cutting not controlled");
        check("restricted_components".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(core("unstamped_plate")))), "Unstamped plate not controlled");
        check("restricted_electronics".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(core("bypass_kit")))), "Bypass kit not restricted");
        check(ContrabandCatalog.INSTANCE.classify(new ItemStack(Items.WHEAT)) == null && ContrabandCatalog.INSTANCE.classify(new ItemStack(core("machined_parts"))) == null,
            "Ordinary and legal goods must not be contraband");
        var original = ContrabandCatalog.INSTANCE.all();
        try {
            ResourceLocation paper = ResourceLocation.parse("minecraft:paper");
            ContrabandCatalog.INSTANCE.replaceForTest(Map.of(
                "firearms_licensed", new ContrabandCatalog.Category("firearms_licensed", "Licensed", List.of(new ContrabandCatalog.Entry(paper, "GunId", Set.of("tacz:glock_17"), "", false))),
                "firearms_restricted", new ContrabandCatalog.Category("firearms_restricted", "Restricted", List.of(new ContrabandCatalog.Entry(paper, "GunId", Set.of(), "", true)))));
            ItemStack glock = new ItemStack(Items.PAPER); glock.getOrCreateTag().putString("GunId", "tacz:glock_17");
            ItemStack uzi = new ItemStack(Items.PAPER); uzi.getOrCreateTag().putString("GunId", "tacz:uzi");
            ItemStack unknown = new ItemStack(Items.PAPER); unknown.getOrCreateTag().putString("GunId", "otherpack:mystery");
            check("firearms_licensed".equals(ContrabandCatalog.INSTANCE.classify(glock)), "Licensed pistol misclassified");
            check("firearms_restricted".equals(ContrabandCatalog.INSTANCE.classify(uzi)), "Restricted weapon misclassified");
            check("firearms_restricted".equals(ContrabandCatalog.INSTANCE.classify(unknown)), "An unknown gun from another pack must be treated as restricted");
        } finally { ContrabandCatalog.INSTANCE.replaceForTest(original); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citiesGenuinelyDifferAndEveryCategoryHasAStatus(GameTestHelper helper) {
        for (String city : ProgressionRules.CITIES) {
            CityLaws.Law law = CityLaws.INSTANCE.find(city);
            check(law != null, "No law for " + city);
            for (String category : ContrabandCatalog.INSTANCE.all().keySet()) check(law.categories().containsKey(category), city + " has no status for " + category);
        }
        check(CityLaws.INSTANCE.find("ironhaven").baseChance("inspection") > CityLaws.INSTANCE.find("blackwater").baseChance("inspection"), "Ironhaven must search more than Blackwater");
        check(CityLaws.INSTANCE.find("blackwater").status("restricted_cultivar").equals("permit")
            && CityLaws.INSTANCE.find("valewood").status("restricted_cultivar").equals("prohibited"), "Controlled crops: permit in Blackwater, prohibited in Valewood");
        // Inspection chance arithmetic: base + heat steps - concealment, clamped.
        CityLaws.Law valewood = CityLaws.INSTANCE.find("valewood");
        check(Math.abs(LawMath.inspectionChance(valewood, "inspection", 0, 0) - 0.22) < 1e-9, "Base chance");
        check(Math.abs(LawMath.inspectionChance(valewood, "inspection", 0, -0.12) - 0.10) < 1e-9, "Concealment lowers the chance");
        check(Math.abs(LawMath.inspectionChance(valewood, "inspection", 3, 0) - 0.37) < 1e-9, "Heat raises the chance");
        check(LawMath.inspectionChance(valewood, "high", 20, 0) == valewood.maxChance(), "The chance is capped");
        check(LawMath.inspectionChance(valewood, "low", 0, -0.35) == 0.02, "Nobody is perfectly invisible");
        // Dice are deterministic functions of their seed.
        long seed = LawMath.mix(1L, 2L, 3L);
        check(LawMath.roll(0.5, seed) == LawMath.roll(0.5, seed) && LawMath.unit(seed) >= 0 && LawMath.unit(seed) < 1, "Dice must be pure");
        int hits = 0;
        for (int i = 0; i < 4000; i++) if (LawMath.roll(0.30, LawMath.mix(99L, i))) hits++;
        check(hits > 1000 && hits < 1400, "A 30% roll should hit about 30% of the time, got " + hits);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void checkpointSeizesOnlyWhatTheLawForbidsThisTravellerAndChargesTheFine(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            CheckpointCatalog.Checkpoint gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            CityLaws.Law valewood = CityLaws.INSTANCE.find("valewood");
            FakePlayer player = player(helper, "valewood", "farmer", 3);
            QShopEconomy.applyBalance(player, 100);
            ItemStack keepsake = new ItemStack(Items.IRON_SWORD); keepsake.setHoverName(net.minecraft.network.chat.Component.literal("Keepsake"));
            player.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
            player.getInventory().setItem(1, new ItemStack(Items.WHEAT, 16));
            player.getInventory().setItem(2, keepsake);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(core("sable_cutting"), 2));
            var result = CheckpointService.inspect(player, citizen(player), gate, valewood, fixture.ledger);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 2, "Bale and cutting should be seized: " + result);
            check(player.getInventory().countItem(core("sable_leaf_bale")) == 0 && player.getOffhandItem().isEmpty(), "Contraband not removed");
            check(player.getInventory().countItem(Items.WHEAT) == 16 && player.getInventory().getItem(2).hasCustomHoverName(), "Legal and personal goods must never be taken");
            check(QShopEconomy.balance(player) == 100 - valewood.fine(CityLaws.PROHIBITED) && result.fine() == valewood.fine(CityLaws.PROHIBITED),
                "One fine per category, not per stack: " + QShopEconomy.balance(player));
            check(fixture.ledger.heat(player.getUUID(), "valewood") == 2 && fixture.ledger.player(player.getUUID()).getInt("seizures") == 1, "Heat and receipt");
            check(!fixture.ledger.player(player.getUUID()).contains("pending"), "Seizure left a pending settlement");
            // A traveller carrying nothing illegal is searched and left alone.
            var clean = CheckpointService.inspect(player, citizen(player), gate, valewood, fixture.ledger);
            check(clean.outcome() == CheckpointService.Outcome.CLEARED && QShopEconomy.balance(player) == 100 - valewood.fine(CityLaws.PROHIBITED), "A clean search must cost nothing");
            // A fine can never exceed the wallet.
            QShopEconomy.applyBalance(player, 10);
            player.getInventory().setItem(0, new ItemStack(core("unstamped_plate"), 2));
            var broke = CheckpointService.inspect(player, citizen(player), gate, valewood, fixture.ledger);
            check(broke.outcome() == CheckpointService.Outcome.SEIZED && broke.fine() == 10 && QShopEconomy.balance(player) == 0, "The fine is capped at the wallet");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLicenceSatisfiesAPermitButNeverAProhibition(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "blackwater");
        try {
            CheckpointCatalog.Checkpoint gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "blackwater", "minecraft:overworld", 0, 0, 48, "inspection");
            FakePlayer grower = player(helper, "valewood", "farmer", 12);
            QShopEconomy.applyBalance(grower, 100);
            grower.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
            // Blackwater: controlled crops need a permit. Without the licence they are seized (permit-level fine) ...
            var unlicensed = CheckpointService.inspect(grower, citizen(grower), gate, CityLaws.INSTANCE.find("blackwater"), fixture.ledger);
            check(unlicensed.outcome() == CheckpointService.Outcome.SEIZED && unlicensed.fine() == CityLaws.INSTANCE.find("blackwater").fine(CityLaws.PERMIT), "Unlicensed carrying should cost the permit fine");
            // ... with the Controlled-Crop Licence the same goods are legal here.
            learn(grower, "farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.controlled_crop_licence");
            grower.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
            var licensed = CheckpointService.inspect(grower, citizen(grower), gate, CityLaws.INSTANCE.find("blackwater"), fixture.ledger);
            check(licensed.outcome() == CheckpointService.Outcome.CLEARED && grower.getInventory().countItem(core("sable_leaf_bale")) == 6, "A licensed handler must be left alone in a permit city");
            // ... but the licence is no defence in a city where the crop is prohibited outright.
            var valewood = new CheckpointCatalog.Checkpoint("test_gate2", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            var stillIllegal = CheckpointService.inspect(grower, citizen(grower), valewood, CityLaws.INSTANCE.find("valewood"), fixture.ledger);
            check(stillIllegal.outcome() == CheckpointService.Outcome.SEIZED, "A licence cannot legalise a prohibited good");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inspectionRollsAreNumberedAndCannotBeRerolledByRelogging(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper, "valewood");
        try {
            UUID traveller = UUID.randomUUID();
            long first = fixture.ledger.claimInspectionRoll(traveller, "gate");
            check(first == 1, "First roll is number 1");
            check(fixture.ledger.claimInspectionRoll(traveller, "gate") == -1, "Inside the cooldown there is no second roll at the same gate");
            check(fixture.ledger.claimInspectionRoll(traveller, "other_gate") == 2, "Another gate has its own cooldown but the counter keeps counting");
            fixture.ledger.advance(WorkOrderLedger.CHECKPOINT_COOLDOWN_TICKS);
            check(fixture.ledger.claimInspectionRoll(traveller, "gate") == 3, "After ten active minutes the same gate rolls again");
            check(new WorkOrderLedger(fixture.path).claimInspectionRoll(traveller, "gate") == -1, "The cooldown survives a server restart");
            // Heat cools with active time and is capped.
            UUID id = UUID.randomUUID();
            check(fixture.ledger.heatAfter(id, "valewood", 50) == WorkOrderLedger.MAX_HEAT, "Heat is capped");
        } finally { fixture.close(); }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ exceptional loot

    @GameTest(template = "empty")
    public static void exceptionalFindsAreRolledOnceAtRegistrationAndNeverByRestocking(GameTestHelper helper) {
        PropertyPolicy cache = PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":illegal_cache");
        check(cache != null && ExceptionalLoot.INSTANCE.find(cache.id()) != null, "Criminal caches need an exceptional table");
        check(ExceptionalLoot.INSTANCE.find(UndergroundCity.MOD_ID + ":apartment_common") == null, "Ordinary homes must never hold exceptional finds");
        var level = helper.getLevel();
        Set<String> exceptionalCore = Set.of("data_ledger", "unstamped_plate", "sable_leaf_bale", "heirloom_seed_packet",
            "prototype_alloy_ingot", "circuit_prototype", "optics_schematic");
        int exceptional = 0;
        for (int i = 0; i < 160; i++) {
            BlockPos pos = new BlockPos(i * 7 + 3, 64, 13 * i - 5);
            PropertyCacheBlockEntity safe = new PropertyCacheBlockEntity(pos, UndergroundCity.PROPERTY_CACHE.get().defaultBlockState());
            safe.register(level, cache, 1000);
            boolean flagged = safe.exceptional();
            if (flagged) exceptional++;
            // The same position always gives the same answer: clearing and re-registering cannot re-roll.
            safe.clear(); safe.register(level, cache, 1000);
            check(safe.exceptional() == flagged, "Re-registering a safe re-rolled its exceptional find");
            check(safe.stock() == cache.initialStock(), "An exceptional find replaces an item, it does not add one");
            // Empty it and let a very long absence restock it: nothing exceptional may come back.
            for (int slot = 0; slot < safe.getContainerSize(); slot++) safe.setItem(slot, ItemStack.EMPTY);
            safe.restock(level, 1000 + 5000L * 60 * 20);
            for (int slot = 0; slot < safe.getContainerSize(); slot++) {
                ItemStack stack = safe.getItem(slot);
                if (stack.isEmpty()) continue;
                var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                check(!(key.getNamespace().equals(UndergroundCity.MOD_ID) && exceptionalCore.contains(key.getPath())) && !key.getNamespace().equals("tacz")
                    && !key.getNamespace().equals("taczblueprints"), "Restocking produced an exceptional item: " + key);
            }
        }
        check(exceptional >= 25 && exceptional <= 90, "A 35% table should flag roughly a third of safes, got " + exceptional + "/160");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void firearmFindsKeepTheirRealItemDataWhenTaczIsInstalled(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tacz")) { helper.succeed(); return; }
        var level = helper.getLevel();
        PropertyPolicy cache = PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":illegal_cache");
        boolean sawGun = false, sawAmmo = false;
        for (int i = 0; i < 400 && !(sawGun && sawAmmo); i++) {
            BlockPos pos = new BlockPos(i * 11 + 1, 70, 17 * i + 9);
            PropertyCacheBlockEntity safe = new PropertyCacheBlockEntity(pos, UndergroundCity.PROPERTY_CACHE.get().defaultBlockState());
            safe.register(level, cache, 1000);
            PropertyCacheBlockEntity reloaded = new PropertyCacheBlockEntity(pos, safe.getBlockState());
            reloaded.load(safe.saveWithFullMetadata());
            for (int slot = 0; slot < safe.getContainerSize(); slot++) {
                ItemStack stack = safe.getItem(slot);
                var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key.toString().equals("tacz:modern_kinetic_gun")) {
                    sawGun = true;
                    check(stack.getTag() != null && stack.getTag().getString("GunId").startsWith("tacz:"), "A found gun lost its GunId");
                    check(ItemStack.matches(stack, reloaded.getItem(slot)), "A found gun changed through a save and reload");
                }
                if (key.toString().equals("tacz:ammo")) { sawAmmo = true; check(stack.getTag().getString("AmmoId").startsWith("tacz:"), "Ammo lost its AmmoId"); }
            }
        }
        check(sawGun, "No criminal cache of 400 held a real TaCZ firearm");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ crop

    @GameTest(template = "empty")
    public static void sableLeafGrowsOnlyWithNutrientAndYieldsBalesNotMoney(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(ground.east(), Blocks.GLOWSTONE.defaultBlockState()); // a grow lamp: brightness 15 whatever the weather
        level.setBlockAndUpdate(ground.above(), UndergroundCity.SABLE_CROP.get().defaultBlockState());
        // The light engine runs between ticks, so the plant is only judged once the lamp's light has settled.
        helper.runAfterDelay(40, () -> sableLeafBody(helper, ground));
    }

    private static void sableLeafBody(GameTestHelper helper, BlockPos ground) {
        var level = helper.getLevel();
        BlockPos pos = ground.above();
        RestrictedCropBlock crop = (RestrictedCropBlock) UndergroundCity.SABLE_CROP.get();
        FakePlayer player = player(helper, "valewood", "farmer", 1);
        RandomSource random = RandomSource.create(7);
        for (int i = 0; i < 400; i++) crop.randomTick(level.getBlockState(pos), level, pos, random);
        check(level.getBlockState(pos).getValue(RestrictedCropBlock.AGE) == 0, "An unfed plant must not grow");
        for (int stage = 1; stage <= 3; stage++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(core("cultivation_nutrient")));
            BlockState before = level.getBlockState(pos);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            check(crop.use(before, level, pos, player, InteractionHand.MAIN_HAND, hit) == InteractionResult.CONSUME, "Nutrient not accepted at stage " + stage);
            check(player.getMainHandItem().isEmpty() && level.getBlockState(pos).getValue(RestrictedCropBlock.NOURISHED), "One dose is consumed per stage");
            int guard = 0;
            while (level.getBlockState(pos).getValue(RestrictedCropBlock.AGE) < stage && guard++ < 2000) crop.randomTick(level.getBlockState(pos), level, pos, random);
            check(level.getBlockState(pos).getValue(RestrictedCropBlock.AGE) == stage && !level.getBlockState(pos).getValue(RestrictedCropBlock.NOURISHED), "Stage " + stage + " not reached or dose not used up");
        }
        // Mature: another dose does nothing, bone meal does nothing.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(core("cultivation_nutrient")));
        check(crop.use(level.getBlockState(pos), level, pos, player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)) == InteractionResult.PASS, "A mature plant must refuse nutrient");
        check(!crop.isValidBonemealTarget(level, pos, level.getBlockState(pos), false), "Bone meal must not grow Sable Leaf");
        // Harvest: mature plants yield two or three bales, young ones never do.
        int bales = 0, mature = 80, cuttings = 0;
        for (int i = 0; i < mature; i++) {
            for (ItemStack drop : Block_getDrops(level, pos, level.getBlockState(pos))) {
                if (drop.is(core("sable_leaf_bale"))) { check(drop.getCount() >= 2 && drop.getCount() <= 3, "A harvest yields two or three bales"); bales += drop.getCount(); }
                if (drop.is(core("sable_cutting"))) cuttings += drop.getCount();
            }
        }
        check(bales >= mature * 2 && bales <= mature * 3, "Bale yield out of range: " + bales);
        check(cuttings >= mature * 0.2 && cuttings <= mature * 0.65, "A mature plant returns a cutting about 40% of the time: " + cuttings);
        for (ItemStack drop : Block_getDrops(level, pos, crop.defaultBlockState())) check(!drop.is(core("sable_leaf_bale")), "A young plant must not yield bales");
        helper.succeed();
    }

    private static List<ItemStack> Block_getDrops(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state) {
        return net.minecraft.world.level.block.Block.getDrops(state, level, pos, null);
    }

    @GameTest(template = "empty")
    public static void onlyAFarmerWhoLearnedRestrictedCultivationCanPlantACutting(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(4, 1, 4));
        level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(ground.east(), Blocks.GLOWSTONE.defaultBlockState());
        FakePlayer untrained = player(helper, "valewood", "farmer", 10);
        ItemStack cutting = new ItemStack(core("sable_cutting"), 2);
        untrained.setItemInHand(InteractionHand.MAIN_HAND, cutting);
        var hit = new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false);
        check(cutting.useOn(new net.minecraft.world.item.context.UseOnContext(untrained, InteractionHand.MAIN_HAND, hit)) == InteractionResult.FAIL, "An untrained farmer planted a controlled cutting");
        check(level.getBlockState(ground.above()).isAir(), "The plant appeared anyway");
        learn(untrained, "farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation");
        check(cutting.useOn(new net.minecraft.world.item.context.UseOnContext(untrained, InteractionHand.MAIN_HAND, hit)).consumesAction(), "A trained farmer could not plant");
        check(level.getBlockState(ground.above()).is(UndergroundCity.SABLE_CROP.get()), "No Sable Leaf plant was placed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rareFindsUnpackIntoWhatTheExchangeSellsAndNothingMore(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        Map<String, String> unpack = Map.of("heirloom_seed_packet", "rare_seeds", "prototype_alloy_ingot", "refined_alloy",
            "circuit_prototype", "control_circuits", "optics_schematic", "optical_components");
        for (var entry : unpack.entrySet()) {
            var matching = recipes.stream().filter(r -> r.getIngredients().size() == 1 && r.getIngredients().get(0).test(new ItemStack(core(entry.getKey())))).toList();
            check(matching.size() == 1, entry.getKey() + " needs exactly one unpack recipe");
            ItemStack result = matching.get(0).getResultItem(helper.getLevel().registryAccess());
            check(result.is(core(entry.getValue())) && result.getCount() >= 3 && result.getCount() <= 6, entry.getKey() + " must unpack into a few regional goods");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ terminal packet

    @GameTest(template = "empty")
    public static void terminalSnapshotCarriesSkillsContactsLawAndHeatThroughTheWire(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper, "blackwater");
        try {
            FakePlayer player = player(helper, "valewood", "farmer", 10);
            learn(player, "farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.controlled_crop_licence");
            BlockPos pos = fixture.pos;
            helper.getLevel().setBlockAndUpdate(pos, UndergroundCity.TERMINAL.get().defaultBlockState());
            TerminalData sent = TerminalData.snapshot(player, pos);
            check(sent.region().equals("blackwater") && !sent.skills().isEmpty() && !sent.laws().isEmpty(), "Snapshot missing skills or law");
            check(sent.contacts().stream().anyMatch(c -> c.underground()), "A farmer with an underground skill must be introduced to a Blackwater buyer");
            check(sent.laws().stream().anyMatch(l -> l.category().equals("restricted_cultivar") && l.status().equals("permit") && l.licensed()), "The licence must show as satisfied");
            check(sent.jobs().stream().anyMatch(j -> j.extra().underground()) && sent.jobs().stream().anyMatch(j -> !j.extra().underground()), "Both legal and underground rows expected");
            TerminalData.Row bales = sent.jobs().stream().filter(j -> j.id().endsWith(":quiet_bales")).findFirst().orElseThrow();
            check(!bales.extra().skillOk() && bales.extra().buyerCity().equals("blackwater") && bales.extra().buyer().startsWith("Marrow"), "Underground row presentation");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            sent.write(buffer);
            TerminalData received = TerminalData.read(buffer);
            check(received.equals(sent), "The terminal snapshot changed on the wire");
            check(buffer.readableBytes() == 0, "Trailing bytes in the terminal packet");
            // An unregistered visitor still round-trips.
            FriendlyByteBuf offline = new FriendlyByteBuf(Unpooled.buffer());
            TerminalData.offline().write(offline);
            check(TerminalData.read(offline).equals(TerminalData.offline()), "Offline snapshot");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyOrderAcceptedBeforeTheUpdateStillLoadsFromItsSnapshot(GameTestHelper helper) {
        // Pre-schema-3 snapshots (no terms) must load unchanged: accepted orders survive the update.
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "underground_city:harvest"); legacy.putString("title", "Harvest for the local bakery");
        legacy.putString("career", "farmer"); legacy.putInt("level", 1); legacy.putString("item", "minecraft:wheat"); legacy.putInt("count", 16);
        legacy.putInt("credits", 24); legacy.putInt("xp", 125); legacy.putInt("capacity", 4); legacy.putInt("refill_ticks", 6000);
        WorkOrder loaded = WorkOrder.load(legacy);
        check(!loaded.underground() && loaded.terms() == WorkOrder.Terms.DEFAULT && loaded.requirements().get(0).count() == 16, "Legacy snapshot");
        for (WorkOrder order : WorkOrderCatalog.INSTANCE.all()) {
            WorkOrder again = WorkOrder.load(order.save());
            check(again.equals(order), "Snapshot round trip changed " + order.id());
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ the technician's trace

    private record Rig(Fixture fixture, FakePlayer player, SecureTerminalBlockEntity terminal, BlockPos pos) {}

    /** A secured terminal inside a Valewood circle and a Technician standing next to it; sessions are cleared first. */
    private static Rig rig(GameTestHelper helper, boolean skilled, int kits) {
        SecureTerminalService.clearForTest();
        Fixture fixture = fixture(helper, "valewood");
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); // a fresh block entity every time
        helper.getLevel().setBlockAndUpdate(pos, UndergroundCity.SECURE_TERMINAL.get().defaultBlockState());
        SecureTerminalBlockEntity terminal = (SecureTerminalBlockEntity) helper.getLevel().getBlockEntity(pos);
        FakePlayer player = player(helper, "valewood", "technician", 12);
        if (skilled) learn(player, "technician.bypass_tooling");
        if (kits > 0) player.getInventory().setItem(0, new ItemStack(core("bypass_kit"), kits));
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return new Rig(fixture, player, terminal, pos);
    }

    private static int kits(FakePlayer player) { return player.getInventory().countItem(core("bypass_kit")); }
    private static int ledgers(FakePlayer player) { return player.getInventory().countItem(core("data_ledger")); }
    private static int heat(FakePlayer player) { return WorkOrderLedger.get(player.server).heat(player.getUUID(), "valewood"); }
    private static final long T0 = 5000, SHOW = (long) SecureTerminalService.LENGTH * SecureTerminalService.SHOW_TICKS_PER_SYMBOL;

    @GameTest(template = "empty")
    public static void theTraceNeedsTheSkillAKitAndRoomToCarryTheLedger(GameTestHelper helper) throws Exception {
        Rig unskilled = rig(helper, false, 1);
        try {
            rejected(() -> SecureTerminalService.begin(unskilled.player, unskilled.terminal, T0));
            check(kits(unskilled.player) == 1 && !SecureTerminalService.hasSession(unskilled.player.getUUID()), "A refused attempt must not spend the kit");
        } finally { unskilled.fixture.close(); }
        Rig noKit = rig(helper, true, 0);
        try {
            rejected(() -> SecureTerminalService.begin(noKit.player, noKit.terminal, T0));
        } finally { noKit.fixture.close(); }
        Rig full = rig(helper, true, 1);
        try {
            for (int slot = 1; slot < 36; slot++) {
                ItemStack sword = new ItemStack(Items.IRON_SWORD);
                sword.setHoverName(net.minecraft.network.chat.Component.literal("keepsake " + slot));
                full.player.getInventory().setItem(slot, sword);
            }
            rejected(() -> SecureTerminalService.begin(full.player, full.terminal, T0));
            check(kits(full.player) == 1 && full.terminal.ledgers(0) == 1, "A full inventory must refuse before anything is spent");
        } finally { full.fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCorrectTraceRecoversOneLedgerAndSpendsTheKitAndTheStock(GameTestHelper helper) throws Exception {
        Rig rig = rig(helper, true, 2);
        try {
            SecureTerminalService.begin(rig.player, rig.terminal, T0);
            check(kits(rig.player) == 1 && SecureTerminalService.hasSession(rig.player.getUUID()), "The kit is spent as the attempt starts");
            byte[] sequence = SecureTerminalService.sequence(rig.player.getUUID(), rig.pos, 1);
            check(sequence.length == SecureTerminalService.LENGTH, "Sequence length");
            for (byte symbol : sequence) check(symbol >= 0 && symbol < SecureTerminalService.SYMBOLS, "Symbol outside the pad range");
            int before = heat(rig.player);
            SecureTerminalService.answer(rig.player, rig.pos, sequence, T0 + SHOW + 20);
            check(ledgers(rig.player) == 1, "One Encrypted Ledger expected");
            check(rig.terminal.ledgers(0) == 0, "The terminal's stock must drop");
            check(heat(rig.player) == before, "Success adds no heat");
            // The same answer cannot be replayed, and the empty drive refuses a second attempt.
            SecureTerminalService.answer(rig.player, rig.pos, sequence, T0 + SHOW + 30);
            check(ledgers(rig.player) == 1, "A replayed answer must pay nothing");
            rejected(() -> SecureTerminalService.begin(rig.player, rig.terminal, T0 + 400));
            check(kits(rig.player) == 1, "The refused second attempt must keep the remaining kit");
        } finally { rig.fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongEarlyLateAbandonedAndDistantAnswersAllTripTheAlarm(GameTestHelper helper) throws Exception {
        String[] kinds = {"wrong", "early", "late", "abandoned", "distant"};
        for (String kind : kinds) {
            Rig rig = rig(helper, true, 1);
            try {
                SecureTerminalService.begin(rig.player, rig.terminal, T0);
                byte[] right = SecureTerminalService.sequence(rig.player.getUUID(), rig.pos, 1);
                byte[] wrong = right.clone();
                wrong[0] = (byte) ((wrong[0] + 1) % SecureTerminalService.SYMBOLS);
                int before = heat(rig.player);
                switch (kind) {
                    case "wrong" -> SecureTerminalService.answer(rig.player, rig.pos, wrong, T0 + SHOW + 20);
                    case "early" -> SecureTerminalService.answer(rig.player, rig.pos, right, T0 + SHOW - 5);
                    case "late" -> SecureTerminalService.answer(rig.player, rig.pos, right, T0 + SHOW + SecureTerminalService.INPUT_TICKS + 1);
                    case "abandoned" -> SecureTerminalService.answer(rig.player, rig.pos, new byte[0], T0 + SHOW + 20);
                    default -> {
                        rig.player.setPos(rig.pos.getX() + 40.5, rig.pos.getY() + 1, rig.pos.getZ() + 0.5);
                        SecureTerminalService.answer(rig.player, rig.pos, right, T0 + SHOW + 20);
                    }
                }
                check(ledgers(rig.player) == 0, kind + ": nothing may be recovered");
                check(heat(rig.player) == before + 1, kind + ": the alarm adds one heat in the terminal's city");
                check(rig.terminal.lockedAt(WorkOrderLedger.get(rig.player.server).clock()), kind + ": the terminal locks down");
                check(rig.terminal.ledgers(0) == 1, kind + ": the stock is untouched by a failed attempt");
                check(kits(rig.player) == 0, kind + ": the kit is spent either way");
                check(!SecureTerminalService.hasSession(rig.player.getUUID()), kind + ": the session ends");
                rig.player.getInventory().setItem(0, new ItemStack(core("bypass_kit"), 1));
                rejected(() -> SecureTerminalService.begin(rig.player, rig.terminal, T0 + 600));
            } finally { rig.fixture.close(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSessionBelongsToOnePlayerAndOneTerminalAndIsIgnoredOtherwise(GameTestHelper helper) throws Exception {
        Rig rig = rig(helper, true, 3);
        try {
            SecureTerminalService.begin(rig.player, rig.terminal, T0);
            rejected(() -> SecureTerminalService.begin(rig.player, rig.terminal, T0 + 1)); // already working one
            FakePlayer other = player(helper, "valewood", "technician", 12);
            learn(other, "technician.bypass_tooling");
            other.getInventory().setItem(0, new ItemStack(core("bypass_kit"), 1));
            other.setPos(rig.pos.getX() + 0.5, rig.pos.getY() + 1, rig.pos.getZ() + 0.5);
            rejected(() -> SecureTerminalService.begin(other, rig.terminal, T0 + 2)); // the terminal is taken
            // An answer from someone with no session is dropped without cost, and with a different position too.
            int otherHeat = heat(other);
            SecureTerminalService.answer(other, rig.pos, new byte[SecureTerminalService.LENGTH], T0 + SHOW + 5);
            check(heat(other) == otherHeat && kits(other) == 1, "A stray answer must cost nothing");
            byte[] right = SecureTerminalService.sequence(rig.player.getUUID(), rig.pos, 1);
            SecureTerminalService.answer(rig.player, rig.pos.above(), right, T0 + SHOW + 5);
            check(SecureTerminalService.hasSession(rig.player.getUUID()) && ledgers(rig.player) == 0, "An answer naming another terminal must not consume or pay the session");
            SecureTerminalService.answer(rig.player, rig.pos, right, T0 + SHOW + 6);
            check(ledgers(rig.player) == 1, "The real answer still pays");
        } finally { rig.fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void terminalStockLockoutAndAttemptsRefillOnActiveTimeAndSurviveASave(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(pos, UndergroundCity.SECURE_TERMINAL.get().defaultBlockState());
        SecureTerminalBlockEntity terminal = (SecureTerminalBlockEntity) helper.getLevel().getBlockEntity(pos);
        long refill = SecureTerminalBlockEntity.REFILL_TICKS;
        check(terminal.ledgers(0) == 1, "A new terminal holds one ledger");
        terminal.take(100); // the interval started when the terminal was first looked at (clock 0)
        check(terminal.ledgers(100) == 0 && terminal.ledgers(refill - 1) == 0, "No refill before the interval");
        check(terminal.ledgers(refill) == 1, "One ledger returns after 45 active minutes");
        terminal.take(refill);
        check(terminal.ledgers(10 * refill) == SecureTerminalBlockEntity.CAPACITY, "Stock is capped and banks no hidden backlog");
        rejectedTake(terminal, 10 * refill);
        terminal.lock(1000);
        check(terminal.lockedAt(1000 + SecureTerminalBlockEntity.LOCKOUT_TICKS - 1) && !terminal.lockedAt(1000 + SecureTerminalBlockEntity.LOCKOUT_TICKS), "Lockout window");
        int attempt = terminal.nextAttempt();
        check(terminal.nextAttempt() == attempt + 1, "Attempts count up so the sequence never repeats");
        // A save and load keeps stock, lockout and attempts.
        CompoundTag saved = terminal.saveWithoutMetadata();
        SecureTerminalBlockEntity copy = new SecureTerminalBlockEntity(pos, terminal.getBlockState());
        copy.load(saved);
        check(copy.lockedAt(1001) && copy.nextAttempt() == attempt + 2, "Persisted lockout and attempts");
        // Sequences are deterministic per (player, terminal, attempt) and differ between attempts.
        UUID who = UUID.randomUUID();
        check(java.util.Arrays.equals(SecureTerminalService.sequence(who, pos, 4), SecureTerminalService.sequence(who, pos, 4)), "Deterministic");
        boolean differs = false;
        for (int attempts = 1; attempts <= 20 && !differs; attempts++) differs = !java.util.Arrays.equals(SecureTerminalService.sequence(who, pos, attempts), SecureTerminalService.sequence(who, pos, attempts + 1));
        check(differs, "Sequences must vary between attempts");
        helper.succeed();
    }

    private static void rejectedTake(SecureTerminalBlockEntity terminal, long now) {
        terminal.take(now); terminal.take(now);
        rejected(() -> terminal.take(now));
    }

    // ------------------------------------------------------------------------------------------ the district

    @GameTest(template = "empty")
    public static void districtDataIsBoundedAndEveryBlockAndPolicyItNamesExists(GameTestHelper helper) {
        check(DistrictCatalog.INSTANCE.find("founders_row") != null, "Founders Row must load from the datapack");
        for (String id : DistrictCatalog.INSTANCE.ids()) {
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(id);
            check(district.volume() > 0 && district.volume() <= DistrictCatalog.MAX_VOLUME, id + " volume");
            DistrictBuilder.validate(helper.getLevel(), district);
            int safes = 0, terminals = 0, secure = 0, clerks = 0;
            for (DistrictCatalog.Op op : district.ops()) {
                switch (op.kind()) { case "safe" -> safes++; case "terminal" -> terminals++; case "secure_terminal" -> secure++; case "clerk" -> clerks++; default -> { } }
            }
            check(terminals >= 1 && clerks <= 1 && secure >= 0 && safes >= 0, id + " must have a terminal and at most one clerk");
        }
        // Bad data is rejected before it can reach the world.
        JsonObject bad = new JsonObject();
        bad.addProperty("schema_version", 1); bad.addProperty("id", "huge");
        JsonArray ops = new JsonArray(); JsonObject fill = new JsonObject();
        fill.addProperty("op", "fill"); fill.addProperty("block", "minecraft:stone");
        JsonArray from = new JsonArray(), to = new JsonArray();
        for (int value : new int[]{0, 0, 0}) from.add(value);
        for (int value : new int[]{500, 10, 500}) to.add(value);
        fill.add("from", from); fill.add("to", to); ops.add(fill); bad.add("ops", ops);
        rejected(() -> DistrictCatalog.parse("huge", bad));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_district", timeoutTicks = 400)
    public static void foundersRowBuildsAWorkingLotRefusesToOverwriteContainersAndUndoRestoresTheWorld(GameTestHelper helper) throws Exception {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find("founders_row");
        BlockPos origin = helper.absolutePos(new BlockPos(0, 10, 0)).offset(-12, 0, -8);
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.getChunk(cx, cz);
        SupplierNpc.forgetForTest(level.getServer());
        DistrictBuilder.forgetForTest(level, district.id());
        Fixture fixture = fixture(helper, "valewood");
        BlockPos terminalPos = origin.offset(3, 0, 2), marker = origin.offset(3, 0, 8);
        BlockState keepsake = Blocks.GOLD_BLOCK.defaultBlockState();
        level.setBlockAndUpdate(marker, keepsake);
        BlockState terrain = level.getBlockState(terminalPos); // whatever the test arena sits in: undo must give exactly this back
        try {
            var result = DistrictBuilder.build(level, district, origin);
            check(result.safes() == 3 && result.secureTerminals() == 1 && result.terminals() == 1 && result.clerkPlaced() && result.insideCity(), "Build summary " + result);
            check(level.getBlockState(terminalPos).is(UndergroundCity.TERMINAL.get()), "Operations Terminal placed");
            for (int x : new int[]{8, 9, 10}) {
                check(level.getBlockEntity(origin.offset(x, 0, 2)) instanceof PropertyCacheBlockEntity cache && cache.registered(), "Safe at " + x + " must be registered");
            }
            check(level.getBlockEntity(origin.offset(15, 0, 2)) instanceof SecureTerminalBlockEntity secure && secure.ledgers(0) == 1, "Secured terminal placed with its drive");
            check(level.getBlockState(origin.offset(21, -1, 3)).is(Blocks.WATER) && level.getBlockState(origin.offset(20, -1, 2)).is(Blocks.FARMLAND), "Greenhouse soil and water");
            check(level.getBlockState(marker).isAir(), "The lot is cleared for the build");
            var clerk = SupplierNpc.state(level.getServer());
            check(terminalPos.equals(clerk.terminalForTest()), "The clerk is bound to the placed terminal: " + clerk.terminalForTest() + " vs " + terminalPos);
            var found = level.getEntity(clerk.clerkForTest());
            check(found instanceof net.minecraft.world.entity.npc.Villager, "The clerk entity exists: " + found + " uuid " + clerk.clerkForTest());
            check(found.hasCustomName() && found.blockPosition().distManhattan(terminalPos) <= 3, "The clerk is named and stands beside the PC: " + found.blockPosition());
            rejected(() -> DistrictBuilder.build(level, district, origin)); // one per world
            DistrictBuilder.undo(level, district);
            check(level.getBlockState(marker) == keepsake, "Undo must put back what the build replaced");
            check(level.getBlockState(terminalPos) == terrain, "Undo removes the terminal, found " + level.getBlockState(terminalPos));
            check(level.getBlockEntity(origin.offset(9, 0, 2)) == null, "Undo removes the safe's block entity");
            check(!SupplierNpc.hasClerk(level.getServer()), "Undo releases the clerk it placed");
            // A container anywhere in the lot stops the build before it changes anything.
            BlockPos chest = origin.offset(20, 0, 12);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            rejected(() -> DistrictBuilder.build(level, district, origin));
            check(level.getBlockState(terminalPos) == terrain && level.getBlockState(chest).is(Blocks.CHEST), "A refused build must not touch the world");
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        } finally {
            if (DistrictBuilder.isBuilt(level, district.id())) DistrictBuilder.undo(level, district);
            DistrictBuilder.forgetForTest(level, district.id());
            SupplierNpc.forgetForTest(level.getServer());
            level.setBlockAndUpdate(marker, Blocks.AIR.defaultBlockState());
            fixture.close();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------ vehicles (optional pack)

    /** Runs only with {@code -PucPack=vehicles}: Core keeps no compile-time dependency on the vehicle mods. */
    @GameTest(template = "empty")
    public static void theMotorboatKeepsItsTwentySevenSlotCargoThroughASaveAndLoad(GameTestHelper helper) {
        if (!ModList.get().isLoaded("modernlife")) { helper.succeed(); return; }
        net.minecraft.world.entity.EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("modernlife", "motorboat"));
        check(type != null && type != net.minecraft.world.entity.EntityType.PIG, "modernlife:motorboat must be registered");
        net.minecraft.world.entity.Entity boat = type.create(helper.getLevel());
        check(boat instanceof net.minecraft.world.Container, "The motorboat must expose a Container");
        net.minecraft.world.Container cargo = (net.minecraft.world.Container) boat;
        check(cargo.getContainerSize() == 27, "Cargo hold size " + cargo.getContainerSize());
        ItemStack named = new ItemStack(Items.IRON_SWORD);
        named.setHoverName(net.minecraft.network.chat.Component.literal("Dray's crate"));
        named.setDamageValue(37);
        ItemStack contract = new ItemStack(core("unstamped_plate"), 5);
        cargo.setItem(0, named); cargo.setItem(13, contract); cargo.setItem(26, new ItemStack(Items.WHEAT, 64));
        CompoundTag saved = boat.saveWithoutId(new CompoundTag());
        net.minecraft.world.entity.Entity restored = type.create(helper.getLevel());
        restored.load(saved);
        net.minecraft.world.Container again = (net.minecraft.world.Container) restored;
        for (int slot = 0; slot < 27; slot++) {
            check(ItemStack.matches(cargo.getItem(slot), again.getItem(slot)), "Cargo slot " + slot + " changed over a save and load");
        }
        check(again.getItem(0).hasCustomHoverName() && again.getItem(0).getDamageValue() == 37, "Named and damaged cargo must survive exactly");
        helper.succeed();
    }

    /** Core-only runs have no firearm contracts; the firearms pack must have all of them, paying real blueprint items. */
    @GameTest(template = "empty")
    public static void firearmContractsExistExactlyWhenTheirModsAreInstalledAndPayRealBlueprints(GameTestHelper helper) {
        boolean tacz = ModList.get().isLoaded("tacz"), blueprints = ModList.get().isLoaded("taczblueprints");
        for (String name : new String[]{"licensed_sidearm", "unregistered_smg", "arms_brokerage"}) {
            check((WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":" + name) != null) == tacz, name + " must load exactly when TaCZ is installed");
        }
        for (String name : new String[]{"licensed_shotgun", "dray_parts_run"}) {
            check((WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":" + name) != null) == blueprints, name + " must load exactly when TaCZ Blueprints is installed");
        }
        for (WorkOrder order : WorkOrderCatalog.INSTANCE.all()) {
            for (WorkOrder.Reward reward : order.terms().rewards()) {
                ItemStack stack = reward.stack();
                check(!stack.isEmpty() && stack.getCount() == reward.count(), order.id() + " rewards an item that does not exist");
                if (reward.item().getNamespace().equals("taczblueprints")) {
                    check(stack.getTag() != null && stack.getTag().getString("GunId").startsWith("tacz:"), order.id() + " blueprint must name a TaCZ gun");
                }
            }
            for (WorkOrder.Requirement requirement : order.requirements()) {
                check(!ForgeRegistries.ITEMS.getValue(requirement.item()).equals(Items.AIR), order.id() + " requires an item that does not exist");
            }
        }
        helper.succeed();
    }
}
