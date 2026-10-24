package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tracked destinations (D-161): one per player, built on the server from the record it points at, saved, and ended when that record ends. Whether Xaero's Minimap shows it is a real client's to
 * show (the Technician scenario with the navigation set); these tests hold what the server decides.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrackingGameTests {
    private TrackingGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer technician(GameTestHelper helper, String name) {
        FakePlayer p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        CitizenState.get(helper.getLevel().getServer()).register(p.getUUID(), "valewood", "technician");
        return p;
    }

    @GameTest(template = "empty")
    public static void aDestinationIsSavedAndReadBackAsItWas(GameTestHelper helper) {
        Navigation.Destination d = new Navigation.Destination("tech", UUID.randomUUID().toString(), "Alarm repair · 3 Willow Way", "66 credits", "minecraft:overworld", new BlockPos(10, 72, -30), "valewood", 1234);
        Navigation.Store store = new Navigation.Store();
        UUID player = UUID.randomUUID();
        store.put(player, d);
        Navigation.Store back = Navigation.Store.load(store.save(new CompoundTag()));
        check(d.equals(back.byPlayer.get(player)), "the destination changed in the save: " + back.byPlayer.get(player));
        check(Navigation.Store.load(new CompoundTag()).byPlayer.isEmpty(), "an old save without destinations does not load empty");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aServiceCallIsTrackedFromItsOwnRecordAndEndsWhenItIsPutRight(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TechService.Systems s = TechService.systems(server);
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        long key = 0x51A7E00DL;
        TechService.Panel panel = new TechService.Panel(key, door.east(), door, "valewood", 1, "residential", "house", "7 Test Lane");
        TechService.Fault fault = new TechService.Fault(UUID.randomUUID(), key, false, LockService.clock(server));
        FakePlayer a = technician(helper, "uc_track_a"), b = technician(helper, "uc_track_b");
        try {
            s.panels.put(key, panel);
            s.faults.put(key, fault);
            var built = Navigation.build(a, "tech", fault.id().toString());
            check(built.isPresent() && built.get().pos().equals(panel.access()) && built.get().title().contains("7 Test Lane"), "the call was not built from its panel: " + built);
            check(Navigation.build(a, "tech", UUID.randomUUID().toString()).isEmpty(), "a call that does not exist was tracked");
            Navigation.track(a, built.get());
            Navigation.track(b, built.get());
            check(Navigation.ended(server, a.getUUID(), built.get()) == null, "an open call reads as ended");
            // the first to finish ends it for everybody tracking it; each player's own destination is theirs
            Navigation.finished(server, "tech", fault.id().toString(), a.getUUID(), null, "Somebody else put that call right first.");
            check(Navigation.of(server, a.getUUID()).isEmpty() && Navigation.of(server, b.getUUID()).isEmpty(), "finishing the call left somebody tracking it");
            // a call put right by somebody else (it expired) ends when it is next checked
            Navigation.track(a, built.get());
            s.faults.remove(key);
            check(Navigation.ended(server, a.getUUID(), built.get()) != null, "a call that is gone does not read as ended");
        } finally {
            s.panels.remove(key);
            s.faults.remove(key);
            Navigation.store(server).remove(a.getUUID());
            Navigation.store(server).remove(b.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoPlayersTrackTheirOwnAndAPlaceEndsWhenForgotten(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        FakePlayer a = technician(helper, "uc_track_c"), b = technician(helper, "uc_track_d");
        BlockPos door = helper.absolutePos(new BlockPos(2, 2, 2));
        Navigation.Destination one = new Navigation.Destination("place", "11", "House · 1 Lane", "", "minecraft:overworld", door, "valewood", 0);
        Navigation.Destination two = new Navigation.Destination("place", "22", "Shop · 2 Lane", "", "minecraft:overworld", door.east(4), "valewood", 0);
        try {
            Navigation.track(a, one);
            Navigation.track(b, two);
            check(Navigation.of(server, a.getUUID()).orElseThrow().equals(one) && Navigation.of(server, b.getUUID()).orElseThrow().equals(two), "one player's choice touched the other's");
            Navigation.track(a, two);
            check(Navigation.of(server, a.getUUID()).orElseThrow().equals(two) && Navigation.store(server).byPlayer.values().stream().filter(two::equals).count() == 2, "tracking something else did not replace the first, once");
            // neither place is in either book, so both read as ended (forgotten), and stopping clears
            check(Navigation.ended(server, a.getUUID(), two) != null, "a place not in the book does not read as ended");
            Navigation.clear(a, null);
            check(Navigation.of(server, a.getUUID()).isEmpty() && Navigation.of(server, b.getUUID()).isPresent(), "stopping cleared the wrong player");
        } finally {
            Navigation.store(server).remove(a.getUUID());
            Navigation.store(server).remove(b.getUUID());
        }
        helper.succeed();
    }
}
