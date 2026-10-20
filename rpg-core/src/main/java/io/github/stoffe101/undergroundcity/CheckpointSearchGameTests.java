package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * What a checkpoint search can and cannot see (D-100). A traveller's pockets, hands, armour slots and what those carry (a worn or pocketed Sophisticated
 * Backpack, a shulker box, a bundle) are searched; a chest nearby, somebody else's pack and a house are not. Each case pairs the find with a control that proves
 * the legal goods beside it are left alone, and the crash case replays the journaled seizure twice to prove nothing is taken or duplicated a second time.
 * The backpack cases run only where Sophisticated Backpacks is loaded (the {@code survival} set).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CheckpointSearchGameTests {
    private static final CheckpointCatalog.Checkpoint GATE = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");

    private static Item core(String path) { return UndergroundCity.item(path); }

    private static CitizenState.Citizen citizen(ServerPlayer p) { return CitizenState.get(p.server).find(p.getUUID()).orElseThrow(); }

    private static final class Fix implements AutoCloseable {
        final WorkOrderLedger ledger;
        final Path path;
        Fix(GameTestHelper helper) {
            path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/search-" + UUID.randomUUID() + ".dat");
            ledger = new WorkOrderLedger(path);
        }
        CheckpointService.Result search(ServerPlayer p) { return CheckpointService.inspect(p, citizen(p), GATE, CityLaws.INSTANCE.find("valewood"), ledger); }
        @Override public void close() throws Exception { CheckpointService.crashAfterPrepare = false; Files.deleteIfExists(path); }
    }

    private static CompoundTag entry(ItemStack stack, int slot) {
        CompoundTag tag = stack.save(new CompoundTag());
        tag.putByte("Slot", (byte) slot);
        return tag;
    }

    private static ItemStack shulkerWith(ItemStack... contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ListTag items = new ListTag();
        for (int i = 0; i < contents.length; i++) items.add(entry(contents[i], i));
        CompoundTag entity = new CompoundTag();
        entity.put("Items", items);
        box.addTagElement("BlockEntityTag", entity);
        return box;
    }

    private static int count(ServerPlayer p, Item item) {
        int total = p.getInventory().countItem(item);
        for (ItemStack armour : p.getInventory().armor) if (armour.is(item)) total += armour.getCount();
        return total;
    }

    // ------------------------------------------------------------------------------------------------------------ carried storage in vanilla

    @GameTest(template = "empty")
    public static void armourSlotsAreSearchedLikePockets(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_ca", "farmer");
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(core("sable_leaf_bale"), 4));        // worn, not in the 36 slots the search used to read
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));                // a control: what is worn legitimately is left alone
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "contraband in an armour slot was not found: " + result);
            check(p.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && p.getItemBySlot(EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS), "the armour slot was not emptied of contraband only");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShulkerBoxIsSearchedAndOnlyTheContrabandInItIsTaken(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_cb", "farmer");
            p.getInventory().setItem(0, shulkerWith(new ItemStack(core("sable_leaf_bale"), 6), new ItemStack(Items.WHEAT, 16)));
            p.getInventory().setItem(1, shulkerWith(new ItemStack(core("sable_cutting"), 2)));    // a box with nothing but contraband
            p.getInventory().setItem(2, shulkerWith(new ItemStack(Items.BREAD, 3)));               // a control: a legal box is not touched
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 2, "the contraband in two shulker boxes was not found: " + result);
            ListTag first = p.getInventory().getItem(0).getTagElement("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND);
            check(first.size() == 1 && ItemStack.of(first.getCompound(0)).is(Items.WHEAT) && ItemStack.of(first.getCompound(0)).getCount() == 16, "the legal wheat in the same box was taken or damaged");
            check(p.getInventory().getItem(1).is(Items.SHULKER_BOX) && p.getInventory().getItem(1).getTagElement("BlockEntityTag") == null, "an emptied box keeps a stale tag (it would not stack with a new one)");
            check(shulkerHolds(p.getInventory().getItem(2), Items.BREAD, 3), "a legal box was changed");
            check(p.getInventory().countItem(Items.SHULKER_BOX) == 3, "a box was taken instead of its contraband");
            // the search is idempotent: a second look finds a clean traveller and changes nothing
            var again = f.search(p);
            check(again.outcome() == CheckpointService.Outcome.CLEARED, "the second search found something that should be gone");
        }
        helper.succeed();
    }

    private static boolean shulkerHolds(ItemStack box, Item item, int count) {
        CompoundTag entity = box.getTagElement("BlockEntityTag");
        if (entity == null) return false;
        ListTag items = entity.getList("Items", Tag.TAG_COMPOUND);
        return items.size() == 1 && ItemStack.of(items.getCompound(0)).is(item) && ItemStack.of(items.getCompound(0)).getCount() == count;
    }

    @GameTest(template = "empty")
    public static void aBundleIsSearchedToo(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_cc", "farmer");
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            ListTag items = new ListTag();
            items.add(new ItemStack(core("sable_leaf_bale"), 2).save(new CompoundTag()));
            items.add(new ItemStack(Items.WHEAT, 5).save(new CompoundTag()));
            bundle.getOrCreateTag().put("Items", items);
            p.getInventory().setItem(0, bundle);
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "contraband in a bundle was not found: " + result);
            ListTag left = p.getInventory().getItem(0).getTag().getList("Items", Tag.TAG_COMPOUND);
            check(left.size() == 1 && ItemStack.of(left.getCompound(0)).is(Items.WHEAT), "the bundle did not keep exactly its legal goods");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storageTheTravellerDoesNotCarryIsNeverSearched(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_cd", "farmer"), mate = player(helper, "uc_ce", "smith");
            BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) helper.getLevel().getBlockEntity(pos)).setItem(0, new ItemStack(core("sable_leaf_bale"), 9));   // a chest within reach
            mate.getInventory().setItem(0, new ItemStack(core("sable_leaf_bale"), 5));                                          // a Crew-mate's pocket
            p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
            mate.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.CLEARED, "a search read storage the traveller was not carrying: " + result);
            check(((ChestBlockEntity) helper.getLevel().getBlockEntity(pos)).getItem(0).getCount() == 9 && mate.getInventory().countItem(core("sable_leaf_bale")) == 5, "somebody else's goods were taken");
            helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everySearchIsRecordedWithItsReasonAndOutcome(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_cf", "farmer");
            f.search(p);                                                        // clean
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(core("sable_leaf_bale"), 2));
            p.getInventory().setItem(3, new ItemStack(core("sable_cutting"), 1));
            CheckpointService.inspect(p, citizen(p), GATE, CityLaws.INSTANCE.find("valewood"), f.ledger, null, new CheckpointService.Why(0.37, 3));
            ListTag history = f.ledger.searches(p.getUUID());
            check(history.size() == 2, "two searches, two records: " + history);
            CompoundTag clean = history.getCompound(0), seized = history.getCompound(1);
            check(clean.getString("outcome").equals("clean") && clean.getInt("stacks") == 0, "the clean search record: " + clean);
            check(seized.getString("outcome").equals("seized") && seized.getInt("stacks") == 2 && seized.getInt("permille") == 370 && seized.getInt("heat") == 3 && seized.getString("post").equals("test_gate"), "the seizure record: " + seized);
            check(seized.getList("where", Tag.TAG_STRING).size() == 2 && seized.getList("categories", Tag.TAG_STRING).size() >= 1, "where it was found and what it was: " + seized);
            // the history survives a restart of the journal
            WorkOrderLedger reread = new WorkOrderLedger(f.path);
            check(reread.searches(p.getUUID()).size() == 2, "the search history did not survive a reload");
            for (int i = 0; i < WorkOrderLedger.SEARCH_HISTORY + 3; i++) f.search(p);
            check(f.ledger.searches(p.getUUID()).size() == WorkOrderLedger.SEARCH_HISTORY, "the history is bounded");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ Sophisticated Backpacks

    private static ItemStack pack(String path) { return new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", path))); }

    /** What the player wears now. Settlement reloads the inventory from the journal, so a stack held from before the search is a stale object with its own cached handler. */
    private static ItemStack worn(ServerPlayer p) { return p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST); }

    private static int inPack(ItemStack pack, Item item) throws Exception {
        IItemHandlerModifiable inventory = BackpackSupport.inventory(pack);
        return BackpackSupport.count(inventory, item);
    }

    @GameTest(template = "empty")
    public static void aWornBackpackIsSearchedAndOnlyTheContrabandLeavesIt(GameTestHelper helper) throws Exception {
        if (!SurvivalSet.backpacksLoaded()) { helper.succeed(); return; }
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_pa", "farmer");
            ItemStack worn = pack("backpack"), pocketed = pack("copper_backpack");
            IItemHandlerModifiable wornInv = BackpackSupport.inventory(worn), pocketInv = BackpackSupport.inventory(pocketed);
            check(BackpackSupport.insertAll(wornInv, new ItemStack(core("sable_leaf_bale"), 6)) && BackpackSupport.insertAll(wornInv, new ItemStack(Items.WHEAT, 20))
                && BackpackSupport.insertAll(wornInv, new ItemStack(Items.IRON_INGOT, 7)), "setup: the worn pack");
            check(BackpackSupport.insertAll(pocketInv, new ItemStack(core("sable_cutting"), 2)) && BackpackSupport.insertAll(pocketInv, new ItemStack(Items.BREAD, 4)), "setup: the pocketed pack");
            BackpackSupport.wear(p, worn);
            p.getInventory().setItem(5, pocketed);
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 2, "contraband hidden in worn and pocketed backpacks was not found: " + result);
            ItemStack wornNow = worn(p), pocketedNow = p.getInventory().getItem(5);
            check(inPack(wornNow, core("sable_leaf_bale")) == 0 && inPack(pocketedNow, core("sable_cutting")) == 0, "the contraband is still in a pack");
            check(inPack(wornNow, Items.WHEAT) == 20 && inPack(wornNow, Items.IRON_INGOT) == 7 && inPack(pocketedNow, Items.BREAD) == 4, "legal goods in the packs were taken");
            check(count(p, core("sable_leaf_bale")) == 0 && count(p, core("sable_cutting")) == 0, "contraband appeared somewhere else (duplicated)");
            check(f.ledger.heat(p.getUUID(), "valewood") == 2 && f.ledger.player(p.getUUID()).getInt("seizures") == 1 && !f.ledger.player(p.getUUID()).contains("pending"), "one seizure, settled");
            check(f.ledger.searches(p.getUUID()).getCompound(0).getList("where", Tag.TAG_STRING).size() == 1
                && f.ledger.searches(p.getUUID()).getCompound(0).getList("where", Tag.TAG_STRING).getString(0).startsWith("your backpack"), "the record names the backpack: " + f.ledger.searches(p.getUUID()));
            check(f.search(p).outcome() == CheckpointService.Outcome.CLEARED, "a second search found more");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShulkerBoxInsideABackpackIsSearchedAndKeepsItsLegalGoods(GameTestHelper helper) throws Exception {
        if (!SurvivalSet.backpacksLoaded()) { helper.succeed(); return; }
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_pb", "farmer");
            ItemStack worn = pack("iron_backpack");
            IItemHandlerModifiable inv = BackpackSupport.inventory(worn);
            ItemStack box = shulkerWith(new ItemStack(core("sable_leaf_bale"), 3), new ItemStack(Items.WHEAT, 8));
            boolean accepted = BackpackSupport.insertAll(inv, box.copy());
            System.out.println("[UC-SEARCH-AUDIT] a shulker box can be put into a Sophisticated Backpack with the running configuration: " + accepted);
            if (!accepted) { helper.succeed(); return; }   // the pack does not accept boxes: nothing to hide in (the nested path is then covered only by the shulker-in-pockets test)
            BackpackSupport.wear(p, worn);
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "contraband in a shulker box in a backpack was not found: " + result);
            ItemStack boxNow = ItemStack.EMPTY;
            IItemHandlerModifiable invNow = BackpackSupport.inventory(worn(p));
            for (int slot = 0; slot < invNow.getSlots(); slot++) if (invNow.getStackInSlot(slot).is(Items.SHULKER_BOX)) boxNow = invNow.getStackInSlot(slot);
            check(shulkerHolds(boxNow, Items.WHEAT, 8), "the box in the pack did not keep exactly its legal goods: " + boxNow.getTag());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCrashAfterThePreparedRecordReplaysTheBackpackSeizureOnceAndLosesNothing(GameTestHelper helper) throws Exception {
        if (!SurvivalSet.backpacksLoaded()) { helper.succeed(); return; }
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_pc", "farmer");
            ItemStack worn = pack("backpack");
            IItemHandlerModifiable inv = BackpackSupport.inventory(worn);
            BackpackSupport.insertAll(inv, new ItemStack(core("sable_leaf_bale"), 6));
            BackpackSupport.insertAll(inv, new ItemStack(Items.WHEAT, 20));
            BackpackSupport.wear(p, worn);
            p.getInventory().setItem(0, new ItemStack(core("sable_cutting"), 2));
            CheckpointService.crashAfterPrepare = true;
            try { f.search(p); throw new AssertionError("the simulated crash did not happen"); } catch (CrewService.SimulatedCrash died) { /* the process died with the decision on disk and nothing applied */ }
            CheckpointService.crashAfterPrepare = false;
            check(f.ledger.player(p.getUUID()).contains("pending"), "the decision was not journaled before the crash");
            check(inPack(worn(p), core("sable_leaf_bale")) == 6, "setup: nothing was applied before the crash");
            // login recovery, twice: the second replay finds the slot already changed
            WorkOrderService.recover(p, f.ledger);
            check(inPack(worn(p), core("sable_leaf_bale")) == 0 && inPack(worn(p), Items.WHEAT) == 20 && p.getInventory().countItem(core("sable_cutting")) == 0, "recovery did not complete the seizure exactly");
            check(f.ledger.player(p.getUUID()).getInt("seizures") == 1 && f.ledger.heat(p.getUUID(), "valewood") == 2, "recovery settled the seizure not exactly once");
            WorkOrderService.recover(p, f.ledger);
            check(inPack(worn(p), Items.WHEAT) == 20 && f.ledger.player(p.getUUID()).getInt("seizures") == 1, "a second recovery changed something");
            check(f.ledger.searches(p.getUUID()).size() == 1, "the search was recorded more than once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anotherPlayersBackpackAndAPlacedBackpackAreNotSearched(GameTestHelper helper) throws Exception {
        if (!SurvivalSet.backpacksLoaded()) { helper.succeed(); return; }
        try (Fix f = new Fix(helper)) {
            ServerPlayer p = player(helper, "uc_pd", "farmer"), mate = player(helper, "uc_pe", "smith");
            ItemStack matesPack = pack("backpack");
            BackpackSupport.insertAll(BackpackSupport.inventory(matesPack), new ItemStack(core("sable_leaf_bale"), 6));
            BackpackSupport.wear(mate, matesPack);
            ItemStack mine = pack("backpack");
            BackpackSupport.insertAll(BackpackSupport.inventory(mine), new ItemStack(Items.WHEAT, 6));
            BackpackSupport.wear(p, mine);
            var result = f.search(p);
            check(result.outcome() == CheckpointService.Outcome.CLEARED, "a Crew-mate's pack made the traveller a suspect: " + result);
            check(inPack(worn(mate), core("sable_leaf_bale")) == 6, "somebody else's backpack was emptied");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void aStackTooBigForAByteSurvivesTheJournal(GameTestHelper helper) {
        // A stack upgrade doubles a slot of a 64-stacking item to 128; the journal must read back exactly what the search saw, or the settlement would find the slot "changed" and take nothing.
        for (int count : new int[] {1, 64, 127, 128, 200, 255, 1000}) {
            ItemStack stack = new ItemStack(Items.WHEAT, count);
            ItemStack back = CarriedStorage.loadStack(CarriedStorage.saveStack(stack));
            check(ItemStack.matches(back, stack), "a stack of " + count + " came back as " + back.getCount());
        }
        check(CarriedStorage.loadStack(CarriedStorage.saveStack(ItemStack.EMPTY)).isEmpty(), "an empty stack stays empty");
        ItemStack withTag = new ItemStack(Items.SHULKER_BOX, 1);
        withTag.getOrCreateTag().putString("uc_marker", "kept");
        check(ItemStack.matches(CarriedStorage.loadStack(CarriedStorage.saveStack(withTag)), withTag), "a stack with data lost it");
        // what the plain format does, recorded so that a change in the game's own format is noticed
        System.out.println("[UC-SEARCH-AUDIT] ItemStack.save/of keeps a stack of 128 as " + ItemStack.of(new ItemStack(Items.WHEAT, 128).save(new CompoundTag())).getCount());
        helper.succeed();
    }
}
