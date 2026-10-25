package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Exploration finds (D-186): the personal half of the loot. Every player finds something in a crate once, rolled for them (the same however often they ask), handed to them and never stored
 * in the crate; a second visit finds nothing more; another player still finds their own; nothing found is marked stolen; the record of who found what survives a save; the shipped tables
 * name only real items and every kind of roadside place has one.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExplorationFindGameTests {
    private ExplorationFindGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static int carried(FakePlayer p) { int n = 0; for (ItemStack s : p.getInventory().items) n += s.getCount(); return n; }

    @GameTest(template = "empty")
    public static void eachPlayerFindsTheirOwnOnceAndNothingIsStoredInTheCrate(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        for (String kind : List.of("farm", "service", "industry", "ruin", "lodging", "wayside")) check(ExplorationFinds.TABLES.containsKey(kind), "no finds for a " + kind);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(pos, UndergroundCity.EXPLORATION_FIND.get().defaultBlockState(), 3);
        String id = "test:find-" + UUID.randomUUID();
        ExplorationFinds.Record rec = ExplorationFinds.record(server);
        ExplorationFinds.Find find = new ExplorationFinds.Find(id, "industry", pos);
        rec.add(find);
        ExplorationFinds.TESTING = true;
        try {
            FakePlayer a = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_find_a")), b = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_find_b"));
            a.getInventory().clearContent(); b.getInventory().clearContent();
            List<ItemStack> rolledA = ExplorationFinds.roll(level.getSeed(), find, a.getUUID());
            check(!rolledA.isEmpty(), "a find rolled nothing");
            check(ItemStack.matches(rolledA.get(0), ExplorationFinds.roll(level.getSeed(), find, a.getUUID()).get(0)), "the same player rolled differently twice: a find could be rerolled");
            ExplorationFinds.open(a, level, pos);
            int got = carried(a);
            int expected = 0;
            for (ItemStack s : rolledA) expected += s.getCount();
            check(got == expected, "the first visit handed over " + got + " items, not the " + expected + " rolled");
            for (ItemStack s : a.getInventory().items) if (!s.isEmpty()) check(!StolenGoods.is(s), "a find was marked stolen");
            check(ExplorationFinds.open(a, level, pos).contains("already"), "a second visit was not told it had been through the crate");
            check(carried(a) == got, "a second visit found more");
            ExplorationFinds.open(b, level, pos);
            check(carried(b) > 0, "a second player found nothing after the first had been through the crate");
            check(level.getBlockState(pos).is(UndergroundCity.EXPLORATION_FIND.get()) && level.getBlockEntity(pos) == null, "the crate holds anything itself, or went away");
            // the record survives a save and a load
            ExplorationFinds.Record back = ExplorationFinds.Record.load(rec.save(new CompoundTag()));
            check(back.finds.containsKey(id) && back.found.get(a.getUUID()).contains(id) && back.found.get(b.getUUID()).contains(id) && back.byPos.get(pos.asLong()).equals(id),
                "who found what did not survive a save");
        } finally {
            ExplorationFinds.TESTING = false;
            rec.finds.remove(id);
            rec.byPos.remove(pos.asLong());
            rec.found.values().forEach(s -> s.remove(id));
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theShippedTablesNameRealItemsAndStaySmall(GameTestHelper helper) {
        for (var e : ExplorationFinds.TABLES.entrySet()) {
            ExplorationFinds.Table t = e.getValue();
            check(t.rollsHi() <= 3, e.getKey() + " rolls more than three times");
            for (ExplorationFinds.Entry entry : t.entries())
                check(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(entry.item()), e.getKey() + " names an unknown item " + entry.item());
            if (t.rare() != null) check(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(t.rare()) && t.rareChance() <= 0.15, e.getKey() + "'s rare find is unknown or too common");
        }
        helper.succeed();
    }
}
