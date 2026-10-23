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
        rejected(() -> SkillService.learn(player, "farmer.restricted_cultivation")); // needs level 6 and three points
        check(!SkillService.has(player, "farmer.restricted_cultivation"), "Level gate skipped");
        rejected(() -> SkillService.learn(player, "farmer.controlled_crop_licence")); // needs restricted_cultivation
        check(!SkillService.has(player, "farmer.controlled_crop_licence"), "Prerequisite skipped");
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
            // the Vintner's wine and spirits contracts need Vinery and Kaleidoscope Tavern: skipped without them, not broken
            if (career.equals("vintner") && !(ModList.get().isLoaded("vinery") && ModList.get().isLoaded("kaleidoscope_tavern"))) { check(legal, "vintner needs legal work"); continue; }
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

    /** The Vintner's spirits (D-067): contraband by item tag, a different status in every city, a licence node, and wine stays ordinary trade. */
    @GameTest(template = "empty")
    public static void spiritsAreContrabandWineIsNotAndTheVintnerHasBothPaths(GameTestHelper helper) {
        check(CityLaws.INSTANCE.find("ironhaven").status("restricted_spirits").equals("prohibited")
            && CityLaws.INSTANCE.find("valewood").status("restricted_spirits").equals("permit")
            && CityLaws.INSTANCE.find("blackwater").status("restricted_spirits").equals("legal"), "Spirits: prohibited in Ironhaven, permit in Valewood, legal in Blackwater");
        var tree = SkillCatalog.INSTANCE.tree("vintner");
        check(!tree.isEmpty() && tree.stream().anyMatch(n -> n.branch().equals("legal")) && tree.stream().anyMatch(n -> n.branch().equals("underground")), "The Vintner needs both a legal and an underground branch");
        check(tree.stream().anyMatch(n -> n.id().equals("vintner.distillers_licence") && n.licences().contains("restricted_spirits")), "The Distiller's Licence must license restricted spirits");
        check(WorkOrderCatalog.INSTANCE.forCareer("vintner").stream().filter(o -> o.foreignVisits() > 0).count() == 3, "The Vintner always has three supply-chain orders, with or without the wine and spirits mods");
        if (ModList.get().isLoaded("kaleidoscope_tavern")) {
            check("restricted_spirits".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_tavern:vodka"))))), "Vodka must be restricted spirits");
            check("restricted_spirits".equals(ContrabandCatalog.INSTANCE.classify(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_tavern:whiskey"))))), "Whiskey must be restricted spirits");
            check(ContrabandCatalog.INSTANCE.classify(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_tavern:wine")))) == null, "Wine is not contraband");
            check(WorkOrderCatalog.INSTANCE.forCareer("vintner").stream().anyMatch(o -> o.id().equals("underground_city:quiet_bottles") && o.underground()), "The quiet bottle contract must exist when Kaleidoscope Tavern is installed");
        } else {
            check(WorkOrderCatalog.INSTANCE.forCareer("vintner").stream().noneMatch(o -> o.id().equals("underground_city:quiet_bottles")), "Without Kaleidoscope Tavern the spirits contracts must not be offered");
        }
        if (ModList.get().isLoaded("vinery")) {
            check(ContrabandCatalog.INSTANCE.classify(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("vinery:red_wine")))) == null, "Vinery wine is not contraband");
            check(WorkOrderCatalog.INSTANCE.forCareer("vintner").stream().anyMatch(o -> o.id().equals("underground_city:tavern_table_wine")), "The tavern wine contract must exist when Vinery is installed");
        }
        helper.succeed();
    }

    /**
     * The Vintner's contracts ask for bottles the wine and spirits mods make. A contract nobody can fill is a trap, so every item a loaded Vintner order
     * asks for must be the result of at least one recipe in the loaded recipe manager (Vinery's wine fermentation, Kaleidoscope Tavern's barrel).
     * This proves a recipe exists, not that the chain was brewed by a person.
     */
    @GameTest(template = "empty")
    public static void everyBottleAVintnerOrderAsksForHasARecipe(GameTestHelper helper) {
        if (!ModList.get().isLoaded("vinery") && !ModList.get().isLoaded("kaleidoscope_tavern")) { helper.succeed(); return; }
        var access = helper.getLevel().registryAccess();
        java.util.Set<net.minecraft.world.item.Item> made = new java.util.HashSet<>();
        for (var recipe : helper.getLevel().getRecipeManager().getRecipes()) {
            try { made.add(recipe.getResultItem(access).getItem()); } catch (RuntimeException ignored) { /* a recipe type without a plain result */ }
        }
        int checked = 0;
        for (WorkOrder order : WorkOrderCatalog.INSTANCE.forCareer("vintner")) {
            for (WorkOrder.Requirement requirement : order.requirements()) {
                String namespace = requirement.item().getNamespace();
                if (!namespace.equals("vinery") && !namespace.equals("kaleidoscope_tavern")) continue;
                check(made.contains(ForgeRegistries.ITEMS.getValue(requirement.item())), order.id() + " asks for " + requirement.item() + ", which no recipe makes");
                checked++;
            }
        }
        check(checked > 0, "No mod-made bottle was checked");
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
    public static void aRidersVehicleCargoIsSearchedAtTheCheckpointAndAParkedVehicleIsNot(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            CheckpointCatalog.Checkpoint gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            CityLaws.Law valewood = CityLaws.INSTANCE.find("valewood");
            FakePlayer driver = player(helper, "valewood", "farmer", 3);
            QShopEconomy.applyBalance(driver, 100);
            var level = helper.getLevel();
            var boat = net.minecraft.world.entity.EntityType.CHEST_BOAT.create(level);
            boat.moveTo(helper.absolutePos(new BlockPos(2, 2, 2)).getX() + 0.5, helper.absolutePos(new BlockPos(2, 2, 2)).getY(), helper.absolutePos(new BlockPos(2, 2, 2)).getZ() + 0.5);
            level.addFreshEntity(boat);
            var hold = (net.minecraft.world.Container) boat;
            hold.setItem(0, new ItemStack(core("sable_leaf_bale"), 6));
            hold.setItem(1, new ItemStack(Items.WHEAT, 16));
            // The boat is parked somewhere else: the driver is searched, the hold is not (nothing is known about what is not here).
            var parked = CheckpointService.inspect(driver, citizen(driver), gate, valewood, fixture.ledger);
            check(parked.outcome() == CheckpointService.Outcome.CLEARED && hold.getItem(0).getCount() == 6, "A parked vehicle must not be searched: " + parked);
            // Riding it into the zone brings the cargo along: the contraband goes, the legal goods stay, the fine is one per category.
            driver.startRiding(boat, true);
            check(driver.getVehicle() == boat, "The driver is in the boat");
            var result = CheckpointService.inspect(driver, citizen(driver), gate, valewood, fixture.ledger);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "The bale in the hold is seized: " + result);
            check(hold.getItem(0).isEmpty() && hold.getItem(1).getCount() == 16, "Only the forbidden cargo is taken from the hold");
            check(QShopEconomy.balance(driver) == 100 - valewood.fine(CityLaws.PROHIBITED), "The fine is charged: " + QShopEconomy.balance(driver));
            boat.discard();
        } finally { fixture.close(); }
        helper.succeed();
    }

    /**
     * Immersive Vehicles' crate parts (D-077): a real Quad is placed from its item, a real crate is fitted in its rack and filled, and the checkpoint search
     * takes the contraband from it when the Quad comes into the zone, and from the seat's side of the vehicle too. Nothing is taken from a Quad left outside.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anImmersiveVehiclesCrateIsSearchedWithItsRiderAndAParkedOneIsNot(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("mts") || !ModList.get().isLoaded("mtsofficialpack") || !QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            CheckpointCatalog.Checkpoint gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            CityLaws.Law valewood = CityLaws.INSTANCE.find("valewood");
            FakePlayer driver = player(helper, "valewood", "farmer", 3);
            QShopEconomy.applyBalance(driver, 100);
            BlockPos ground = helper.absolutePos(new BlockPos(3, 1, 3));
            helper.getLevel().setBlock(ground, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            List<String> problems = new java.util.ArrayList<>();
            net.minecraft.world.entity.Entity quad = OptionalModProbes.placeVehicleWithCrate(driver, ground, "mts:mtsofficialpack.quad_red", "mts:mtsofficialpack.crate",
                List.of(new ItemStack(core("sable_leaf_bale"), 6), new ItemStack(Items.WHEAT, 16)), problems);
            check(problems.isEmpty() && quad != null, "A real Quad with a real crate: " + problems);
            // The crate is the vehicle's hold: three slots or more, the bale first, the wheat second.
            VehicleCargo.Hold before = VehicleCargo.of(quad);
            check(before != null && before.size() >= 2 && before.get(0).getCount() == 6 && before.get(1).getCount() == 16, "The adapter must see the crate's slots");
            // The seat side: a rider is attached to a seat part, and the seat leads to the same vehicle and the same crate.
            var seats = OptionalModProbes.vehicleParts(quad, "PartSeat");
            check(!seats.isEmpty(), "The Quad has a seat");
            VehicleCargo.Hold fromSeat = VehicleCargo.ImmersiveVehicles.holdOfEntity(seats.get(0), quad.getClass().getClassLoader());
            check(fromSeat != null && fromSeat.size() == before.size(), "A seat must lead to the vehicle's crates");
            // Parked somewhere else: the driver is searched, the Quad is not.
            var parked = CheckpointService.inspect(driver, citizen(driver), gate, valewood, fixture.ledger, null);
            check(parked.outcome() == CheckpointService.Outcome.CLEARED && VehicleCargo.of(quad).get(0).getCount() == 6, "A parked Quad must not be searched: " + parked);
            // Brought into the zone: the contraband goes, the wheat stays, the fine is charged.
            var result = CheckpointService.inspect(driver, citizen(driver), gate, valewood, fixture.ledger, quad);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "The bale in the crate is seized: " + result);
            VehicleCargo.Hold after = VehicleCargo.of(quad);
            check(after.get(0).isEmpty() && after.get(1).getCount() == 16, "Only the forbidden cargo leaves the crate");
            check(QShopEconomy.balance(driver) == 100 - valewood.fine(CityLaws.PROHIBITED), "The fine is charged: " + QShopEconomy.balance(driver));
            com.mojang.logging.LogUtils.getLogger().info("[UC-TEST] Immersive Vehicles crate: {} slots, seized {} stack(s) from {}, fine charged {}", before.size(), result.stacksSeized(), quad.getClass().getSimpleName(), result.fine());
            quad.discard();
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

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void sableLeafGrowsOnlyWithNutrientAndYieldsBalesNotMoney(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        // The test cell may sit inside natural terrain (the grid of tests moves when tests are added; a hosted run found the lamp's light walled off from the plant): clear its room first.
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 3; dy++) level.setBlockAndUpdate(ground.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(ground.east(), Blocks.GLOWSTONE.defaultBlockState()); // a grow lamp: brightness 15 whatever the weather
        level.setBlockAndUpdate(ground.above(), UndergroundCity.SABLE_CROP.get().defaultBlockState());
        // The light engine runs between ticks, so the plant is only judged once the lamp's light has reached it. A fixed wait of 40 ticks failed on a hosted runner (the stage
        // never came), where a busy light engine or a cell with no sky above can take longer.
        whenLit(helper, ground.above(), 0, () -> sableLeafBody(helper, ground));
    }

    /** Runs {@code then} once the block at {@code pos} has the light a crop needs (checked every ten ticks, at least twenty in); fails with the light it had if it never comes. */
    private static void whenLit(GameTestHelper helper, BlockPos pos, int waited, Runnable then) {
        int light = helper.getLevel().getRawBrightness(pos, 0);
        if (waited >= 20 && light >= RestrictedCropBlock.LIGHT_NEEDED) { then.run(); return; }
        check(waited < 600, "The lamp's light never reached the plant: light " + light + " after " + waited + " ticks, " + RestrictedCropBlock.LIGHT_NEEDED + " needed");
        helper.runAfterDelay(10, () -> whenLit(helper, pos, waited + 10, then));
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
            check(level.getBlockState(pos).getValue(RestrictedCropBlock.AGE) == stage && !level.getBlockState(pos).getValue(RestrictedCropBlock.NOURISHED),
                "Stage " + stage + " not reached or dose not used up (light " + level.getRawBrightness(pos, 0) + ", " + RestrictedCropBlock.LIGHT_NEEDED + " needed)");
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
        // The test cell may sit inside natural terrain (the grid of tests moves when tests are added): clear the room the plant needs first.
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 3; dy++) level.setBlockAndUpdate(ground.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(ground.east(), Blocks.GLOWSTONE.defaultBlockState());
        // A crop only survives in light 8 or more and the light engine runs between ticks, so wait for the lamp. A test
        // world under a roof, or at night, would otherwise refuse the plant for a reason that has nothing to do with the skill.
        helper.runAfterDelay(40, () -> plantingBody(helper, ground));
    }

    private static void plantingBody(GameTestHelper helper, BlockPos ground) {
        var level = helper.getLevel();
        FakePlayer untrained = player(helper, "valewood", "farmer", 10);
        ItemStack cutting = new ItemStack(core("sable_cutting"), 2);
        untrained.setItemInHand(InteractionHand.MAIN_HAND, cutting);
        var hit = new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false);
        InteractionResult refused = cutting.useOn(new net.minecraft.world.item.context.UseOnContext(untrained, InteractionHand.MAIN_HAND, hit));
        check(refused == InteractionResult.FAIL, "An untrained farmer planted a controlled cutting (result " + refused + ")");
        check(level.getBlockState(ground.above()).isAir(), "The plant appeared anyway: " + level.getBlockState(ground.above()) + " above " + level.getBlockState(ground)
            + ", skill held: " + SkillService.has(untrained, RestrictedCuttingItem.SKILL));
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
            check(clerks <= 1 && secure >= 0 && safes >= 0, id + " must have at most one clerk");
            if (id.equals("founders_row")) check(terminals >= 1, "Founders Row must have an Operations Terminal");
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
        // A citizen is validated like a block: unknown roles, an employer without a career and terminal, and over-long lines are refused.
        rejected(() -> DistrictCatalog.parse("npcs", districtWithCitizen("wizard", "Nobody", null, null, "Hello.")));
        rejected(() -> DistrictCatalog.parse("npcs", districtWithCitizen("employer", "Nobody", null, null, "Hello.")));
        rejected(() -> DistrictCatalog.parse("npcs", districtWithCitizen("resident", "Nobody", null, null, "x".repeat(DistrictCatalog.MAX_LINE + 1))));
        rejected(() -> DistrictCatalog.parse("npcs", districtWithCitizen("resident", " ", null, null, "Hello.")));
        DistrictCatalog.District fine = DistrictCatalog.parse("npcs", districtWithCitizen("employer", "Nobody", "smith", new int[]{0, 0, 0}, "Hello."));
        check(fine.npcOps().size() == 1 && fine.npcOps().get(0).npc().career().equals("smith"), "A well-formed employer is accepted");
        helper.succeed();
    }

    private static JsonObject districtWithCitizen(String role, String name, String career, int[] terminal, String line) {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", 1); json.addProperty("id", "npcs");
        JsonArray ops = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("op", "block"); block.addProperty("block", "minecraft:stone");
        JsonArray origin = new JsonArray(); for (int value : new int[]{0, -1, 0}) origin.add(value);
        block.add("at", origin); ops.add(block);
        JsonObject citizen = new JsonObject();
        citizen.addProperty("op", "npc"); citizen.addProperty("role", role); citizen.addProperty("name", name);
        JsonArray at = new JsonArray(); for (int value : new int[]{0, 0, 0}) at.add(value);
        citizen.add("at", at);
        if (career != null) citizen.addProperty("career", career);
        if (terminal != null) { JsonArray ref = new JsonArray(); for (int value : terminal) ref.add(value); citizen.add("terminal", ref); }
        JsonArray lines = new JsonArray(); lines.add(line); citizen.add("lines", lines);
        ops.add(citizen); json.add("ops", ops);
        return json;
    }

    // ------------------------------------------------------------------------------------------ blueprints, the server's side

    @GameTest(template = "empty")
    public static void theGunSmithTableRefusesAGunWithoutItsBlueprintOnTheServerAndBuildsItWithOne(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("tacz") || !ModList.get().isLoaded("taczblueprints")) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        Boolean wasOpen = OptionalModProbes.setGunTableOpen(false);   // the pack's setting: guns are built from blueprints only
        check(wasOpen != null, "The addon's configuration is reachable");
        try {
            FakePlayer player = player(helper, "valewood", "gunsmith", 4);
            StarterKits.INSTANCE.grant(player, "gunsmith");
            ItemStack blueprint = player.getInventory().items.stream().filter(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())).equals("taczblueprints:blueprint_pistol")).findFirst().orElseThrow();
            int iron = player.getInventory().countItem(Items.IRON_INGOT), copper = player.getInventory().countItem(Items.COPPER_INGOT);
            // Without the blueprint: a craft request for the pistol is refused on the server, and takes nothing.
            String refused = OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:glock_17");
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == 0, "No pistol without the blueprint (" + refused + ")");
            check(player.getInventory().countItem(Items.IRON_INGOT) == iron && player.getInventory().countItem(Items.COPPER_INGOT) == copper, "A refused craft consumed nothing");
            // The decision itself, for another gun the player has no blueprint for, and for one they have.
            ItemStack other = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("tacz:modern_kinetic_gun")));
            other.getOrCreateTag().putString("GunId", "tacz:ak47");
            check(!BlueprintGate.allows(other, player), "A gun without a blueprint is refused");
            // With the blueprint used, the same request is built.
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, blueprint.copy());
            blueprint.getItem().use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            String crafted = OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:glock_17");
            check(crafted == null && OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == 1, "With the blueprint the table builds the pistol: " + crafted);
            check(!BlueprintGate.allows(other, player), "One blueprint opens one gun, not all of them");
            // An open table (the addon's default) is not gated at all.
            OptionalModProbes.setGunTableOpen(true);
            check(BlueprintGate.allows(other, player), "An ungated table allows every recipe");
        } finally {
            if (wasOpen != null) OptionalModProbes.setGunTableOpen(wasOpen);
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theRangeScoresByDistanceFromTheCentreOfTheFaceAndTheLedgerNeverListsUndergroundWork(GameTestHelper helper) throws Exception {
        BlockPos block = new BlockPos(10, 70, 10);
        check(RangeService.score(Vec3.atCenterOf(block).add(-0.5, 0, 0), block, net.minecraft.core.Direction.WEST) == 10, "The centre of the face scores 10");
        check(RangeService.score(Vec3.atCenterOf(block).add(-0.5, 0.25, 0), block, net.minecraft.core.Direction.WEST) == 5, "Halfway to the edge scores 5");
        check(RangeService.score(Vec3.atCenterOf(block).add(-0.5, 0.5, 0.5), block, net.minecraft.core.Direction.WEST) == 0, "A corner scores 0");
        check(RangeService.score(Vec3.atCenterOf(block).add(0.1, 0.5, 0.1), block, net.minecraft.core.Direction.UP) >= 7, "The top face is scored on its own plane");
        Fixture fixture = fixture(helper, "valewood");
        try {
            FakePlayer player = player(helper, "valewood", "farmer", 2);
            LedgerData data = LedgerData.snapshot(player, fixture.pos);
            check(!data.postings().isEmpty() && data.postings().size() <= LedgerData.MAX_POSTINGS, "The ledger lists the postings");
            for (String career : ProgressionRules.CAREERS) check(data.postings().stream().anyMatch(p -> p.career().equals(career)), "The ledger lists work for " + career);
            long legal = WorkOrderCatalog.INSTANCE.all().stream().filter(order -> !order.underground()).count();
            check(data.postings().size() == Math.min(legal, LedgerData.MAX_POSTINGS), "Only the legal postings are listed: " + data.postings().size() + " of " + legal);
            for (LedgerData.Posting posting : data.postings()) {
                check(WorkOrderCatalog.INSTANCE.all().stream().anyMatch(order -> !order.underground() && order.title().equals(posting.title())), "A listed posting is a real legal order: " + posting.title());
            }
            net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            data.write(buffer);
            LedgerData back = LedgerData.read(buffer);
            check(back.postings().equals(data.postings()) && back.career().equals("farmer") && back.region().equals("valewood"), "The ledger survives the wire");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_district", timeoutTicks = 400)
    public static void foundersRowBuildsAWorkingLotRefusesToOverwriteContainersAndUndoRestoresTheWorld(GameTestHelper helper) throws Exception {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find("founders_row");
        // Build in the sky well above any terrain: the builder rightly refuses a lot that contains a block entity, and
        // a test world (especially one a screening mod has altered) can hold chests, structure blocks or other machines
        // at arena height. Open air is also the only place a test is guaranteed not to overwrite its neighbours.
        BlockPos arena = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos origin = new BlockPos(arena.getX() - 12, level.getMaxBuildHeight() - 40, arena.getZ() - 8);
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        // Keep the lot's chunks loaded for the whole test (a plain getChunk leaves them unticketed, so they may unload
        // and take the clerk entity with them), exactly as a standing operator would.
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        SupplierNpc.forgetForTest(level.getServer());
        DistrictBuilder.forgetForTest(level, district.id());
        Fixture fixture = fixture(helper, "valewood");
        // Every position below comes from the district's own data, so redesigning the buildings never needs this test to change.
        java.util.List<DistrictCatalog.Op> terminalOps = district.ops().stream().filter(op -> op.kind().equals("terminal")).toList();
        java.util.List<DistrictCatalog.Op> safeOps = district.ops().stream().filter(op -> op.kind().equals("safe")).toList();
        DistrictCatalog.Op secureOp = district.ops().stream().filter(op -> op.kind().equals("secure_terminal")).findFirst().orElseThrow();
        BlockPos terminalPos = origin.offset(terminalOps.get(0).a()), marker = origin.offset(16, 0, 12);
        BlockState keepsake = Blocks.GOLD_BLOCK.defaultBlockState();
        level.setBlockAndUpdate(marker, keepsake);
        BlockState terrain = level.getBlockState(terminalPos); // whatever the test arena sits in: undo must give exactly this back
        Runnable cleanup = () -> {
            try {
                if (DistrictBuilder.isBuilt(level, district.id())) DistrictBuilder.undo(level, district);
                DistrictBuilder.forgetForTest(level, district.id());
                SupplierNpc.forgetForTest(level.getServer());
                level.setBlockAndUpdate(marker, Blocks.AIR.defaultBlockState());
                for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
                fixture.close();
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        };
        // A freshly forced chunk takes a few ticks to become entity-ticking; until then an entity added to it is not yet
        // visible to getEntity. A standing operator's chunks are long since ticking, so the test waits the same way.
        helper.runAfterDelay(40, () -> {
            try {
                var result = DistrictBuilder.build(level, district, origin);
                check(result.safes() == safeOps.size() && result.secureTerminals() == 1 && result.terminals() == terminalOps.size() && result.clerkPlaced()
                    && result.insideCity() && result.citizens() == district.npcOps().size(), "Build summary " + result);
                check(level.getBlockState(terminalPos).is(UndergroundCity.TERMINAL.get()), "Operations Terminal placed");
                for (DistrictCatalog.Op safe : safeOps) {
                    check(level.getBlockEntity(origin.offset(safe.a())) instanceof PropertyCacheBlockEntity cache && cache.registered(), "Safe at " + safe.a() + " must be registered");
                }
                check(level.getBlockEntity(origin.offset(secureOp.a())) instanceof SecureTerminalBlockEntity secure && secure.ledgers(0) == 1, "Secured terminal placed with its drive");
                var farmland = district.ops().stream().filter(op -> op.kind().equals("fill") && op.text().startsWith("minecraft:farmland")).findFirst().orElseThrow();
                check(level.getBlockState(origin.offset(farmland.a())).is(Blocks.FARMLAND) && district.ops().stream().anyMatch(op -> op.kind().equals("block") && op.text().equals("minecraft:water")
                    && level.getBlockState(origin.offset(op.a())).is(Blocks.WATER)), "Greenhouse soil and water");
                // The new fittings: a Public Ledger, signs with their words, and a door on every house.
                var ledgerOp = district.ops().stream().filter(op -> op.kind().equals("ledger")).findFirst().orElseThrow();
                check(level.getBlockState(origin.offset(ledgerOp.a())).is(UndergroundCity.PUBLIC_LEDGER.get()), "The Public Ledger is placed");
                var signOp = district.ops().stream().filter(op -> op.kind().equals("sign")).findFirst().orElseThrow();
                check(level.getBlockEntity(origin.offset(signOp.a())) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signEntity
                    && signEntity.getFrontText().getMessage(0, false).getString().equals(signOp.sign().lines().get(0)) && signEntity.isWaxed(), "Signs carry their words and are waxed");
                check(level.getBlockState(marker).isAir(), "The lot is cleared for the build");
                var clerk = SupplierNpc.state(level.getServer());
                check(terminalPos.equals(clerk.terminalForTest()), "The clerk is bound to the placed terminal: " + clerk.terminalForTest() + " vs " + terminalPos);
                var found = level.getEntity(clerk.clerkForTest());
                check(found instanceof net.minecraft.world.entity.npc.Villager, "The clerk entity exists: " + found + " uuid " + clerk.clerkForTest());
                check(found.hasCustomName() && found.blockPosition().distManhattan(terminalPos) <= 3, "The clerk is named and stands beside the PC: " + found.blockPosition());
                // The people: nine named, persistent villagers placed on the spots the data names, each bound to its own entry.
                var citizens = DistrictBuilder.citizensForTest(level, district.id());
                var npcOps = district.npcOps();
                check(citizens.size() == npcOps.size() && npcOps.size() >= 11, "Every citizen is recorded: " + citizens.size() + " of " + npcOps.size());
                java.util.Set<String> roles = new java.util.HashSet<>();
                for (int i = 0; i < npcOps.size(); i++) {
                    var op = npcOps.get(i);
                    var entity = level.getEntity(citizens.get(i));
                    check(entity instanceof net.minecraft.world.entity.npc.Villager, "Citizen " + op.npc().name() + " exists: " + entity);
                    var villager = (net.minecraft.world.entity.npc.Villager) entity;
                    check(CityNpcs.isCityNpc(villager) && villager.isInvulnerable() && villager.hasCustomName()
                        && villager.getCustomName().getString().equals(op.npc().name()), op.npc().name() + " is a named, protected district citizen");
                    check(villager.blockPosition().equals(origin.offset(op.a())), op.npc().name() + " stands where the data says: " + villager.blockPosition());
                    check(CityNpcs.definition(villager).map(entry -> entry.name().equals(op.npc().name())).orElse(false), "The villager finds its own entry in the data");
                    roles.add(op.npc().role());
                    if (op.npc().role().equals("employer")) {
                        check(level.getBlockState(origin.offset(op.reference())).is(UndergroundCity.TERMINAL.get()), op.npc().name() + " has a real terminal in the office");
                    }
                }
                check(roles.containsAll(java.util.Set.of("registrar", "employer", "resident", "guard", "vendor")), "Every role is represented: " + roles);
                int beds = 0, bells = 0, workstations = 0;
                for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
                    BlockState seen = level.getBlockState(pos);
                    if (seen.getBlock() instanceof net.minecraft.world.level.block.BedBlock && seen.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) beds++;
                    if (seen.is(Blocks.BELL)) bells++;
                    if (seen.is(Blocks.COMPOSTER) || seen.is(Blocks.SMITHING_TABLE) || seen.is(Blocks.GRINDSTONE) || seen.is(Blocks.LECTERN) || seen.is(Blocks.CARTOGRAPHY_TABLE)) workstations++;
                }
                check(beds >= 7 && bells == 1 && workstations >= 6, "Homes, a meeting bell and workstations exist: beds " + beds + ", bells " + bells + ", workstations " + workstations);
                // A lost citizen is put back; a complete district is left alone.
                var lost = level.getEntity(citizens.get(4));
                lost.discard();
                check(DistrictBuilder.repopulate(level, district) == 1, "One missing citizen is placed again");
                check(DistrictBuilder.repopulate(level, district) == 0, "A complete district has nothing to repopulate");
                var replacement = DistrictBuilder.citizensForTest(level, district.id());
                check(!replacement.get(4).equals(citizens.get(4)) && level.getEntity(replacement.get(4)) != null, "The replacement is recorded");
                rejected(() -> DistrictBuilder.build(level, district, origin)); // one per world
                DistrictBuilder.undo(level, district);
                for (java.util.UUID id : replacement) check(level.getEntity(id) == null, "Undo removes the citizens it placed");
                check(level.getBlockState(marker) == keepsake, "Undo must put back what the build replaced");
                check(level.getBlockState(terminalPos) == terrain, "Undo removes the terminal, found " + level.getBlockState(terminalPos));
                check(level.getBlockEntity(origin.offset(safeOps.get(0).a())) == null, "Undo removes the safe's block entity");
                check(!SupplierNpc.hasClerk(level.getServer()), "Undo releases the clerk it placed");
                // A container anywhere in the lot stops the build before it changes anything.
                BlockPos chest = origin.offset(20, 0, 12);   // on the east pavement
                level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                rejected(() -> DistrictBuilder.build(level, district, origin));
                check(level.getBlockState(terminalPos) == terrain && level.getBlockState(chest).is(Blocks.CHEST), "A refused build must not touch the world");
                level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void aDistrictSiteSearchStopsWhereNothingNearerCanWin(GameTestHelper helper) {
        // The archipelago's landing is a flat plateau: every lot fits. The first start must not read the terrain under all of them (an exhaustive search took
        // more than nine minutes with the real chunk generator).
        int[] calls = {0};
        DistrictSites.Ground flat = new DistrictSites.Ground() {
            @Override public int height(int x, int z) { calls[0]++; return 70; }
            @Override public boolean wet(int x, int z) { calls[0]++; return false; }
            @Override public boolean reserved(int x, int z) { return false; }
        };
        var site = DistrictSites.find(flat, 0, 0, 94, 60, 3, 160).orElseThrow(() -> new IllegalStateException("no site on flat ground"));
        check(site.relief() == 0 && site.distance() == 0, "On flat ground the nearest lot wins: " + site);
        check(calls[0] < 4000, "The search stopped at the first lot (" + calls[0] + " terrain reads)");
        // The answer is still the best one: a bump at the origin pushes the winner out, to the cheapest of all candidates.
        DistrictSites.Ground bump = new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return Math.abs(x) < 20 && Math.abs(z) < 20 ? 78 : 70; }
            @Override public boolean wet(int x, int z) { return false; }
            @Override public boolean reserved(int x, int z) { return false; }
        };
        var pushed = DistrictSites.find(bump, 0, 0, 25, 41, 3, 160).orElseThrow(() -> new IllegalStateException("no site beside the bump"));
        double best = Double.MAX_VALUE;
        for (int dx = -160; dx <= 160; dx += DistrictSites.STEP) for (int dz = -160; dz <= 160; dz += DistrictSites.STEP) {
            double distance = Math.hypot(dx, dz);
            if (distance > 160) continue;
            var other = DistrictSites.evaluate(bump, dx - 12, dz - 20, 25, 41, distance);
            if (other != null && other.relief() <= 3) best = Math.min(best, other.relief() * 12.0 + other.distance());
        }
        check(Math.abs(pushed.relief() * 12.0 + pushed.distance() - best) < 1e-9, "The early exit finds the same cost as an exhaustive search: " + pushed + " vs " + best);
        // A site that will be graded does not care about relief: with a weight of zero the nearest lot that is not too steep wins at once, on ground that is never level.
        int[] rolling = {0};
        DistrictSites.Ground hills = new DistrictSites.Ground() {
            @Override public int height(int x, int z) { rolling[0]++; return 70 + (Math.floorMod(x / 8 + z / 8, 5)) * 3; }
            @Override public boolean wet(int x, int z) { return false; }
            @Override public boolean reserved(int x, int z) { return false; }
        };
        var graded = DistrictSites.find(hills, 0, 0, 94, 60, 24, 200, 0.0).orElseThrow(() -> new IllegalStateException("no site on rolling ground"));
        check(graded.distance() == 0, "With a relief weight of zero the nearest feasible lot wins: " + graded);
        check(rolling[0] < 4000, "The search for a lot that will be graded stopped at the first lot (" + rolling[0] + " terrain reads)");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theLandingIsLevelInsideTheLotAndEasesBackOverTheRamp(GameTestHelper helper) {
        var lot = new LandingGrader.Rect(0, 0, 100, 50);
        check(LandingGrader.target(lot, 72, 85, 50, 25) == 72 && LandingGrader.target(lot, 72, 60, 0, 0) == 72 && LandingGrader.target(lot, 72, 60, 100, 50) == 72, "Inside the lot (corners included) the ground is the level");
        check(LandingGrader.target(lot, 72, 85, 100 + LandingGrader.RAMP, 25) == 85 && LandingGrader.target(lot, 72, 85, -LandingGrader.RAMP - 5, 25) == 85, "Beyond the ramp the ground is as it was made");
        int previous = 72;
        for (int d = 1; d <= LandingGrader.RAMP; d++) {
            int height = LandingGrader.target(lot, 72, 85, 100 + d, 25);
            check(height >= previous && height - previous <= 2, "The ramp climbs without a cliff: " + previous + " to " + height + " at " + d);
            previous = height;
        }
        check(previous == 85, "The ramp ends at the natural height");
        var footprint = DevBootstrap.footprint(33, 49);
        check(footprint.width() == footprint.left() + footprint.right() && footprint.depth() == 57, "The footprint is the range, the Row, the flats and the motel and the lane's gate: " + footprint);
        check(footprint.left() == 40 && footprint.right() == 33 + 3 + 24 + 3 + 24, "The footprint matches the district catalogue's sizes: " + footprint);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDistrictSiteIsLevelDryAndClearOfCitiesAndTheNearestOneWins(GameTestHelper helper) {
        // Flat ground at 70 with a hill (height 80) east of x=40, a pond around (0, 0) and a reserved city circle south of z=100.
        DistrictSites.Ground ground = new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return x > 40 ? 80 : 70; }
            @Override public boolean wet(int x, int z) { return Math.hypot(x, z) < 30; }
            @Override public boolean reserved(int x, int z) { return z > 100; }
        };
        var site = DistrictSites.find(ground, 0, 0, 25, 41, 3, 160).orElseThrow(() -> new IllegalStateException("no site found on open flat ground"));
        check(site.relief() == 0 && site.y() == 70, "The site is flat ground at 70: " + site);
        for (int dx : new int[]{0, 25}) for (int dz : new int[]{0, 41}) {
            int x = site.x() + dx, z = site.z() + dz;
            check(!ground.wet(x, z) && !ground.reserved(x, z) && x <= 40, "Corner " + x + "," + z + " is dry, unreserved and off the hill");
        }
        // A pond or a city centred on the search origin pushes the site out, but never onto them.
        check(Math.hypot(site.x() + 12, site.z() + 20) >= 30, "The site clears the pond: " + site);
        // No dry, level ground anywhere: nothing is invented.
        check(DistrictSites.find(new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return 60 + ((x / 4 + z / 4) & 7) * 3; }
            @Override public boolean wet(int x, int z) { return false; }
            @Override public boolean reserved(int x, int z) { return false; }
        }, 0, 0, 25, 41, 3, 160).isEmpty(), "Bumpy ground yields no site");
        check(DistrictSites.find(new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return 70; }
            @Override public boolean wet(int x, int z) { return true; }
            @Override public boolean reserved(int x, int z) { return false; }
        }, 0, 0, 25, 41, 3, 160).isEmpty(), "Open water yields no site");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCheckpointPostIsPlacedBesideTheRoadNeverOnIt(GameTestHelper helper) {
        var city = List.<CorridorPlan.City>of();
        CorridorPlan straight = new CorridorPlan("straight", new double[]{0, 0}, new double[]{0, 400}, 7, city);
        CorridorPlan diagonal = new CorridorPlan("diagonal", new double[]{100, 400}, new double[]{100, 300}, 5, city);
        CheckpointCatalog.Checkpoint onStraight = new CheckpointCatalog.Checkpoint("t1", "Test gate", "ironhaven", "minecraft:overworld", 0, 200, 48, "inspection");
        CheckpointCatalog.Checkpoint onDiagonal = new CheckpointCatalog.Checkpoint("t2", "Test gate two", "valewood", "minecraft:overworld", 250, 200, 48, "high");
        CheckpointCatalog.Checkpoint nowhere = new CheckpointCatalog.Checkpoint("t3", "Test gate three", "blackwater", "minecraft:overworld", 5000, 5000, 48, "low");
        for (var pair : List.of(java.util.Map.entry(straight, onStraight), java.util.Map.entry(diagonal, onDiagonal))) {
            var placement = CheckpointPosts.placement(pair.getValue(), List.of(straight, diagonal), 9, 9).orElseThrow(() -> new IllegalStateException("no placement"));
            for (int dx : new int[]{0, 8}) for (int dz : new int[]{0, 8}) {
                var corner = pair.getKey().project(placement.origin().getX() + dx, placement.origin().getZ() + dz);
                check(corner.distance() > pair.getKey().reach() + 1, pair.getValue().id() + ": a corner of the post is " + corner.distance() + " from the road, which reaches " + pair.getKey().reach());
            }
        }
        check(CheckpointPosts.placement(nowhere, List.of(straight, diagonal), 9, 9).isEmpty(), "A zone with no road gets no post");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_checkpoint", timeoutTicks = 700)
    public static void checkpointPostsAreRepeatableReversibleAndTheirGuardIsFound(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find("checkpoint_post");
        check(district != null && district.repeatable() && district.npcOps().size() == 1 && district.npcOps().get(0).npc().role().equals("guard"), "The post is a repeatable district with one guard");
        BlockPos arena = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos one = new BlockPos(arena.getX() + 40, level.getMaxBuildHeight() - 40, arena.getZ() - 4), two = one.offset(40, 0, 0);
        BlockPos lo = one.offset(district.min()), hi = two.offset(district.max());
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        DistrictBuilder.forgetForTest(level, district.id());
        Runnable cleanup = () -> {
            DistrictBuilder.forgetForTest(level, district.id());
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
        };
        // The lots are far from the test structure: wait until their chunks really tick entities (a slow runner needs longer than a fixed delay).
        List<BlockPos> corners = List.of(lo, hi, one.offset(4, 0, 4), two.offset(4, 0, 4));
        whenEntitiesTick(helper, level, corners, 40, () -> {
            try {
                DistrictBuilder.build(level, district, one);
                DistrictBuilder.build(level, district, two);
                check(DistrictBuilder.count(level, district.id()) == 2, "Two posts stand in the world");
                rejected(() -> DistrictBuilder.build(level, district, one)); // the same spot twice
                rejected(() -> DistrictBuilder.build(level, district, one.offset(4, 0, 4))); // overlapping
            } catch (RuntimeException error) {
                cleanup.run();
                throw error;
            }
            // A villager added to a chunk whose entity section has not been promoted yet is invisible to lookups for a few ticks: wait until both guards can be found.
            waitUntil(helper, () -> DistrictBuilder.citizensForTest(level, district.id()).size() == 2
                && DistrictBuilder.citizensForTest(level, district.id()).stream().allMatch(id -> level.getEntity(id) != null), 0, () -> {
                try {
                    CheckpointCatalog.Checkpoint zoneOne = new CheckpointCatalog.Checkpoint("p1", "Post one", "ironhaven", level.dimension().location().toString(), one.getX() + 4, one.getZ() + 4, 48, "inspection");
                    var guard = CheckpointPosts.guardAt(level, zoneOne);
                    check(guard.isPresent() && guard.get().hasCustomName() && guard.get().isInvulnerable(), "The zone's guard is found and is a named, protected citizen");
                    // A villager keeps its vanilla routine, so by now it may have strolled a little: it must still be inside the post's 9 x 9 lot.
                    var at = guard.get().position();
                    check(at.x >= one.getX() && at.x <= one.getX() + 9 && at.z >= one.getZ() && at.z <= one.getZ() + 9 && Math.abs(at.y - one.getY()) < 2, "The guard stands in the post's lot " + one.toShortString() + ": " + at);
                    UUID guardOne = guard.get().getUUID();
                    DistrictBuilder.undoNear(level, district, one.offset(4, 0, 4));
                    check(DistrictBuilder.count(level, district.id()) == 1, "Removing one post leaves the other");
                    check(level.getBlockState(one.offset(3, 0, 3)).getBlock() != Blocks.STONE_BRICKS, "The removed post's booth is gone");
                    check(level.getBlockState(two.offset(3, 0, 3)).is(Blocks.STONE_BRICKS), "The other post's booth stands");
                    // What matters is that the undo took its own post's guard and nobody else's. Whether a distant chunk has already made the other villager
                    // visible to a lookup is the engine's timing, so the bookkeeping (not a lookup in that chunk) is what is asserted.
                    var remaining = DistrictBuilder.citizensForTest(level, district.id());
                    check(level.getEntity(guardOne) == null, "The removed post's guard is gone");
                    check(remaining.size() == 1 && !remaining.contains(guardOne), "The other post keeps exactly its own citizen: " + remaining + " (removed " + guardOne + ")");
                    DistrictBuilder.undoNear(level, district, two.offset(4, 0, 4));
                    check(DistrictBuilder.count(level, district.id()) == 0, "No posts remain");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            });
        });
    }

    /** Runs {@code body} once {@code ready} holds: checked every ten ticks, and after 200 ticks regardless so the assertion that follows reports what is missing. */
    static void waitUntil(GameTestHelper helper, java.util.function.BooleanSupplier ready, int waited, Runnable body) {
        helper.runAfterDelay(10, () -> {
            if (ready.getAsBoolean() || waited >= 200) { body.run(); return; }
            waitUntil(helper, ready, waited + 10, body);
        });
    }

    /** Runs {@code body} once every position is in an entity-ticking chunk: checked after {@code first} ticks, then every ten, giving up after 200 more. */
    static void whenEntitiesTick(GameTestHelper helper, net.minecraft.server.level.ServerLevel level, List<BlockPos> positions, int first, Runnable body) {
        waitForTicking(helper, level, positions, first, 0, body);
    }

    private static void waitForTicking(GameTestHelper helper, net.minecraft.server.level.ServerLevel level, List<BlockPos> positions, int delay, int waited, Runnable body) {
        helper.runAfterDelay(delay, () -> {
            if (positions.stream().allMatch(level::isPositionEntityTicking) || waited >= 200) { body.run(); return; }
            waitForTicking(helper, level, positions, 10, waited + 10, body);
        });
    }

    // ------------------------------------------------------------------------------------------ vehicles (optional pack)

    /** Runs only where Modern Life can load (it cannot under the ForgeGradle dev environment; see scripts/run_prod_boot_check.py). */
    @GameTest(template = "empty")
    public static void theMotorboatKeepsItsTwentySevenSlotCargoThroughASaveAndLoad(GameTestHelper helper) {
        if (!ModList.get().isLoaded("modernlife")) { helper.succeed(); return; }
        List<String> problems = OptionalModProbes.boatCargoRoundTrip(helper.getLevel());
        check(problems.isEmpty(), "Motorboat cargo: " + problems);
        helper.succeed();
    }

    /** Core-only runs have no firearm contracts; the firearms pack must have all of them, paying real blueprint items. */
    @GameTest(template = "empty")
    public static void firearmContractsExistExactlyWhenTheirModsAreInstalledAndPayRealBlueprints(GameTestHelper helper) {
        boolean tacz = ModList.get().isLoaded("tacz"), blueprints = ModList.get().isLoaded("taczblueprints");
        for (String name : new String[]{"licensed_sidearm", "unregistered_smg", "arms_brokerage"}) {
            check((WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":" + name) != null) == tacz, name + " must load exactly when TaCZ is installed");
        }
        for (String name : new String[]{"licensed_shotgun", "dray_parts_run", "dray_rifle_run"}) {
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
                // a contract takes plain factory guns only (WorkOrder.matchesFactoryGun): at least one of its guns must come off the Gun Smith Table without an attachment already fitted
                // (the M4A1's own table recipe fits a stock, so a contract that listed only the M4A1 could never be delivered from a crafted rifle)
                if (!requirement.gunIds().isEmpty() && tacz) {
                    java.util.Set<String> plain = OptionalModProbes.plainTableGuns(helper.getLevel());
                    check(requirement.gunIds().stream().anyMatch(plain::contains), order.id() + ": none of " + requirement.gunIds() + " is built plain at the table (plain: " + plain + ")");
                }
            }
        }
        helper.succeed();
    }

    /**
     * The Gunsmith loop on real TaCZ, as far as a server can show it: the starter kit's blueprint unlocks the Glock 17, the real Gun Smith Table
     * recipe turns the kit's own iron into the gun and its copper and powder into ammunition, and the Valewood co-op buys that crafted gun.
     * It does not prove the table's screen, firing, or that a client cannot craft without a blueprint (TaCZ Blueprints 0.1.3 hides recipes in
     * the client screen only).
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void gunsmithKitBlueprintTableAndAmmoCraftARealLicensedSidearmThatTheCoopBuys(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("tacz") || !ModList.get().isLoaded("taczblueprints") || !QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            FakePlayer player = player(helper, "valewood", "gunsmith", 4);
            QShopEconomy.applyBalance(player, 0);
            StarterKits.INSTANCE.grant(player, "gunsmith");
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == 0, "The kit must not hand out the finished gun");
            ItemStack blueprint = player.getInventory().items.stream().filter(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())).equals("taczblueprints:blueprint_pistol")).findFirst().orElse(ItemStack.EMPTY);
            check(!blueprint.isEmpty(), "The kit carries a pistol blueprint");
            // TaCZ crafts guns at the Gun Smith Table and ammunition at a different workstation, the Ammo Workbench (an item named workbench_a that carries BlockId).
            check(player.getInventory().items.stream().anyMatch(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())).equals("tacz:gun_smith_table")), "The kit carries a Gun Smith Table");
            check(player.getInventory().items.stream().anyMatch(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())).equals("tacz:workbench_a") && s.getTag() != null && s.getTag().getString("BlockId").equals("tacz:ammo_workbench")), "The kit carries an Ammo Workbench");
            // An item's use() acts on the stack in the player's hand, so the blueprint goes into the main hand first.
            check(!OptionalModProbes.unlockedGuns(player).contains("tacz:glock_17"), "The pistol is not unlocked before the blueprint is used");
            ItemStack inHand = blueprint.copy();
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, inHand);
            inHand.getItem().use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
            check(OptionalModProbes.unlockedGuns(player).contains("tacz:glock_17"), "Using the blueprint unlocks the Glock 17 for this player: " + OptionalModProbes.unlockedGuns(player));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            int iron = player.getInventory().countItem(Items.IRON_INGOT);
            String crafted = OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:glock_17");
            check(crafted == null, "Crafting the Glock 17 at the real table: " + crafted);
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == 1, "A Glock 17 is in the inventory");
            check(player.getInventory().countItem(Items.IRON_INGOT) < iron, "The recipe consumed the player's iron");
            String ammo = OptionalModProbes.craftAtTaczTable(player, "ammo", "tacz:9mm");
            check(ammo == null, "Crafting 9mm ammunition at the real Ammo Workbench: " + ammo);
            check(OptionalModProbes.countTagged(player, "tacz:ammo", "AmmoId", "tacz:9mm") >= 50, "Fifty rounds of 9mm were made: " + OptionalModProbes.describeInventory(player));
            check(player.getInventory().countItem(Items.COPPER_INGOT) == 0 && player.getInventory().countItem(Items.GUNPOWDER) == 0, "The ammunition recipe consumed copper and powder");
            // Without the materials the same craft is refused and changes nothing.
            int guns = OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17");
            OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:glock_17");
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == guns, "A craft without materials must not create a gun");
            // The legal contract buys the crafted gun and leaves the ammunition with the smith.
            learn(player, "gunsmith.fundamentals", "gunsmith.licensed_repairs");
            WorkOrder order = WorkOrderCatalog.INSTANCE.find("underground_city:licensed_sidearm");
            check(order != null && !order.underground(), "The licensed sidearm contract is the legal one");
            WorkOrderService.accept(player, fixture.ledger, citizen(player), order.id());
            UUID nonce = fixture.ledger.player(player.getUUID()).getUUID("order");
            WorkOrderService.complete(player, fixture.ledger, nonce, fixture.pos);
            check(QShopEconomy.balance(player) == order.credits(), "The co-op paid " + QShopEconomy.balance(player) + " for the crafted pistol");
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:glock_17") == guns - 1, "The pistol was handed over");
            check(OptionalModProbes.countTagged(player, "tacz:ammo", "AmmoId", "tacz:9mm") >= 50, "The smith keeps the ammunition");
        } finally { fixture.close(); }
        helper.succeed();
    }

    /**
     * The whole restricted-rifle chain on real TaCZ and TaCZ Blueprints, as far as a server can show it: components are <b>bought at two rival cities' exchanges</b>, two of Dray's
     * component runs turn them into credits, XP, trust and the SMG and rifle blueprints, the blueprint-gated Gun Smith Table refuses the rifle until the blueprint is used and then
     * builds it (a plain AK-47: the M4A1 comes off the table with a stock already fitted and a contract takes only plain guns), and the level-11 arms contract buys it. Every credit, part and unit of stock is accounted for. It does not prove the table's screen, firing, or a person's time.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theRestrictedRifleChainRunsFromExchangePartsThroughTheBlueprintAndTheTableToPaymentAndTrust(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("tacz") || !ModList.get().isLoaded("taczblueprints") || !QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "ironhaven");
        Boolean wasOpen = OptionalModProbes.setGunTableOpen(false); // the pack's setting: guns are built from blueprints only
        check(wasOpen != null, "The addon's configuration is reachable");
        try {
            FakePlayer player = player(helper, "valewood", "gunsmith", 12);
            QShopEconomy.applyBalance(player, 400);
            learn(player, "gunsmith.fundamentals", "gunsmith.licensed_repairs", "gunsmith.unregistered_builds", "gunsmith.filed_serials", "gunsmith.arms_brokerage");
            CitizenState.Citizen citizen = citizen(player);
            RegionalStock parts = RegionalCatalog.INSTANCE.find("underground_city:machined_parts"), optics = RegionalCatalog.INSTANCE.find("underground_city:optical_components");
            check(parts != null && optics != null && !parts.city().equals(optics.city()), "The two components are sold in different rival cities");
            int partsBefore = fixture.ledger.regionalUnits(parts), opticsBefore = fixture.ledger.regionalUnits(optics), xpBefore = citizen.careerXp();

            // 1. Trade: ten machined parts at the Ironhaven exchange (two orders, the line's largest order is eight), five optical components in Blackwater.
            setCity(helper, fixture.pos, parts.city());
            RegionalMarket.purchase(player, fixture.pos, fixture.ledger, citizen, parts.id(), 8, UUID.randomUUID());
            RegionalMarket.purchase(player, fixture.pos, fixture.ledger, citizen, parts.id(), 2, UUID.randomUUID());
            setCity(helper, fixture.pos, optics.city());
            RegionalMarket.purchase(player, fixture.pos, fixture.ledger, citizen, optics.id(), 5, UUID.randomUUID());
            long spent = 10L * RegionalMarket.unitPrice(parts, citizen) + 5L * RegionalMarket.unitPrice(optics, citizen);
            check(player.getInventory().countItem(core("machined_parts")) == 10 && player.getInventory().countItem(core("optical_components")) == 5, "The parts arrived: " + OptionalModProbes.describeInventory(player));
            check(QShopEconomy.balance(player) == 400 - spent, "The purchases cost exactly " + spent + " credits, balance " + QShopEconomy.balance(player));
            check(fixture.ledger.regionalUnits(parts) == partsBefore - 10 && fixture.ledger.regionalUnits(optics) == opticsBefore - 5, "The shared stock of both cities went down by what was bought");

            // 2. Dray's first run (trust 1, the SMG blueprint), then the rifle run (needs that trust; pays the rifle blueprint). The buyer is in Blackwater.
            WorkOrder first = WorkOrderCatalog.INSTANCE.find("underground_city:dray_parts_run"), second = WorkOrderCatalog.INSTANCE.find("underground_city:dray_rifle_run");
            WorkOrder brokerage = WorkOrderCatalog.INSTANCE.find("underground_city:arms_brokerage");
            check(first != null && second != null && brokerage != null && second.terms().minTrust() == 1 && brokerage.terms().minTrust() == 2, "The three contracts and their trust ladder exist");
            rejected(() -> WorkOrderService.accept(player, fixture.ledger, citizen(player), second.id())); // no trust yet
            WorkOrderService.accept(player, fixture.ledger, citizen(player), first.id());
            WorkOrderService.complete(player, fixture.ledger, fixture.ledger.player(player.getUUID()).getUUID("order"), fixture.pos);
            check(fixture.ledger.trust(player.getUUID(), "dray") == 1 && QShopEconomy.balance(player) == 400 - spent + first.credits(), "The first run paid " + first.credits() + " and earned trust 1");
            check(player.getInventory().countItem(core("machined_parts")) == 6 && player.getInventory().countItem(core("optical_components")) == 3, "The first run took exactly its parts");
            WorkOrderService.accept(player, fixture.ledger, citizen(player), second.id());
            WorkOrderService.complete(player, fixture.ledger, fixture.ledger.player(player.getUUID()).getUUID("order"), fixture.pos);
            check(fixture.ledger.trust(player.getUUID(), "dray") == 2 && player.getInventory().countItem(core("machined_parts")) == 0 && player.getInventory().countItem(core("optical_components")) == 0, "The rifle run took the rest of the parts and earned trust 2");
            ItemStack rifleBlueprint = player.getInventory().items.stream().filter(stack -> String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())).equals("taczblueprints:blueprint_rifle")
                && stack.getTag() != null && stack.getTag().getString("GunId").equals("tacz:ak47")).findFirst().orElse(ItemStack.EMPTY);
            check(!rifleBlueprint.isEmpty(), "The rifle run paid the AK-47 blueprint with the payment: " + OptionalModProbes.describeInventory(player));

            // 3. The table: materials in hand (iron, lapis, logs: what mining and trade supply), no blueprint used yet => refused and nothing taken; then the blueprint => built.
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 38));
            player.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 6));
            player.getInventory().add(new ItemStack(Items.OAK_LOG, 10));
            String refused = OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:ak47");
            check(OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:ak47") == 0 && player.getInventory().countItem(Items.IRON_INGOT) == 38,
                "The blueprint-gated table refuses the rifle before the blueprint is used and takes nothing (" + refused + ")");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, rifleBlueprint.copy());
            rifleBlueprint.getItem().use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(OptionalModProbes.unlockedGuns(player).contains("tacz:ak47"), "Using the blueprint unlocks the AK-47: " + OptionalModProbes.unlockedGuns(player));
            String crafted = OptionalModProbes.craftAtTaczTable(player, "gun", "tacz:ak47");
            check(crafted == null && OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:ak47") == 1, "With the blueprint the table builds the rifle: " + crafted);
            check(player.getInventory().countItem(Items.IRON_INGOT) == 0 && player.getInventory().countItem(Items.LAPIS_LAZULI) == 0 && player.getInventory().countItem(Items.OAK_LOG) == 0, "The recipe consumed its materials");

            // 4. The buyer: the level-11 contract takes the crafted rifle and pays.
            double beforeSale = QShopEconomy.balance(player);
            WorkOrderService.accept(player, fixture.ledger, citizen(player), brokerage.id());
            WorkOrderService.complete(player, fixture.ledger, fixture.ledger.player(player.getUUID()).getUUID("order"), fixture.pos);
            check(QShopEconomy.balance(player) == beforeSale + brokerage.credits() && OptionalModProbes.countTagged(player, "tacz:modern_kinetic_gun", "GunId", "tacz:ak47") == 0, "The buyer took the rifle and paid " + brokerage.credits());
            check(fixture.ledger.trust(player.getUUID(), "dray") == 3, "A third delivery, trust 3");
            check(citizen(player).careerXp() - xpBefore == first.xp() + second.xp() + brokerage.xp(), "The three contracts gave exactly their XP: " + (citizen(player).careerXp() - xpBefore));
            // the loop closes: the credits it earned buy the next batch of parts
            check(QShopEconomy.balance(player) == 400 - spent + first.credits() + second.credits() + brokerage.credits(), "Every credit is accounted for");
        } finally {
            if (wasOpen != null) OptionalModProbes.setGunTableOpen(wasOpen);
            fixture.close();
        }
        helper.succeed();
    }
}
