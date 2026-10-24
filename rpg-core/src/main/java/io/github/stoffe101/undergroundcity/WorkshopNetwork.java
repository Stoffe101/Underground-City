package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The workshop channel (D-163): the server sends what a station shows (its tier, the next tier's parts, its recipes with what the player carries, the batch at work and what is ready); the client
 * asks to start a batch, to take what is ready, or to fit the next tier. Every request is judged on the server against the player's reach and inventory.
 */
public final class WorkshopNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "workshop"), () -> VERSION, VERSION::equals, VERSION::equals);

    record Part(String item, int need, int have) {}
    record Row(String id, String title, String output, int count, int seconds, int tier, boolean allowed, String reason, boolean graded, boolean underground, String note, List<Part> parts) {}
    record View(BlockPos pos, String station, String title, String career, int tier, String tierName, int maxTier, int batch, int speedPercent, String nextName, String nextSkill, boolean nextSkillOk,
                List<Part> nextParts, String running, int secondsLeft, int secondsTotal, int grade, int readyCount, String readyItem, String note, List<Row> rows) {}
    record Start(BlockPos pos, String recipe, int batches) {}
    record Collect(BlockPos pos) {}
    record Upgrade(BlockPos pos) {}

    static final double REACH_SQUARED = 64.0;

    private WorkshopNetwork() {}

    public static void register() {
        toClient(0, View.class, WorkshopNetwork::write, WorkshopNetwork::read, m -> ClientWorkshop.view(m));
        toServer(1, Start.class, (m, b) -> { b.writeBlockPos(m.pos); b.writeUtf(m.recipe, 64); b.writeVarInt(m.batches); }, b -> new Start(b.readBlockPos(), b.readUtf(64), b.readVarInt()),
            (m, player) -> act(player, m.pos, e -> WorkshopBlock.start(player, player.serverLevel(), e, m.recipe, m.batches)));
        toServer(2, Collect.class, (m, b) -> b.writeBlockPos(m.pos), b -> new Collect(b.readBlockPos()), (m, player) -> act(player, m.pos, e -> WorkshopBlock.collect(player, player.serverLevel(), e)));
        toServer(3, Upgrade.class, (m, b) -> b.writeBlockPos(m.pos), b -> new Upgrade(b.readBlockPos()), (m, player) -> act(player, m.pos, e -> WorkshopBlock.upgrade(player, player.serverLevel(), e)));
    }

    private static void act(ServerPlayer player, BlockPos pos, Function<WorkshopBlock.Entity, String> what) {
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > REACH_SQUARED || !(player.serverLevel().getBlockEntity(pos) instanceof WorkshopBlock.Entity e)) return;
        String said = what.apply(e);
        open(player, pos, e, said);
    }

    /** Sends the station's screen to the player, with {@code note} (what the last action did) in its footer. */
    static void open(ServerPlayer player, BlockPos pos, WorkshopBlock.Entity e, String note) {
        if (player instanceof FakePlayer || player.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view(player, pos, e, note));
    }

    static View view(ServerPlayer player, BlockPos pos, WorkshopBlock.Entity e, String note) {
        Workshops.Station st = e.station();
        long now = LockService.clock(player.server);
        if (st == null) return new View(pos, "", "Unknown station", "", 1, "", 1, 1, 100, "", "", false, List.of(), "", 0, 0, 0, 0, "", note, List.of());
        if (e.busy() && e.done(now)) e.finish();
        Workshops.Tier tier = st.tier(e.tier);
        Workshops.Tier next = e.tier < st.tiers().size() ? st.tier(e.tier + 1) : null;
        CitizenState.Citizen c = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        List<Part> nextParts = new ArrayList<>();
        if (next != null) for (var p : next.parts().entrySet()) nextParts.add(new Part(p.getKey().toString(), p.getValue(), WorkshopBlock.carried(player, p.getKey())));
        List<Row> rows = new ArrayList<>();
        for (Workshops.Recipe r : st.recipes()) {
            if (!r.mod().isEmpty() && !net.minecraftforge.fml.ModList.get().isLoaded(r.mod())) continue;
            List<Part> parts = new ArrayList<>();
            for (var in : r.inputs().entrySet()) parts.add(new Part(in.getKey().toString(), in.getValue(), WorkshopBlock.carried(player, in.getKey())));
            String why = WorkshopBlock.refusal(player, e, st, r, 1);
            boolean gated = e.tier < r.tier() || !r.skill().isEmpty() && !WorkshopBlock.has(c, r.skill());
            rows.add(new Row(r.id(), r.title(), r.output().toString(), r.count(), (int) (Workshops.ticksFor(r, tier) / 20), r.tier(), !gated, why == null ? "" : why, r.graded(), r.legality().equals("underground"), r.note(), parts));
        }
        Workshops.Recipe running = e.busy() ? st.recipe(e.recipe) : null;
        int readyCount = 0;
        String readyItem = "";
        for (var s : e.ready) { readyCount += s.getCount(); readyItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()).toString(); }
        return new View(pos, st.id(), st.title(), st.career(), e.tier, tier.name(), st.tiers().size(), tier.batch(), (int) Math.round(tier.speed() * 100), next == null ? "" : next.name(),
            next == null || next.skill().isEmpty() ? "" : WorkshopBlock.skillTitle(next.skill()), next != null && (next.skill().isEmpty() || WorkshopBlock.has(c, next.skill())), nextParts,
            running == null ? "" : running.title() + " x" + e.batches, (int) (e.ticksLeft(now) / 20), (int) (e.duration / 20), e.grade, readyCount, readyItem, note, rows);
    }

    // ------------------------------------------------------------------------------------------------ the wire

    private static void writeParts(List<Part> parts, FriendlyByteBuf b) {
        b.writeVarInt(parts.size());
        for (Part p : parts) { b.writeUtf(p.item, 128); b.writeVarInt(p.need); b.writeVarInt(p.have); }
    }

    private static List<Part> readParts(FriendlyByteBuf b) {
        int n = Math.min(32, b.readVarInt());
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Part(b.readUtf(128), b.readVarInt(), b.readVarInt()));
        return out;
    }

    private static void write(View v, FriendlyByteBuf b) {
        b.writeBlockPos(v.pos); b.writeUtf(v.station, 64); b.writeUtf(v.title, 64); b.writeUtf(v.career, 32); b.writeVarInt(v.tier); b.writeUtf(v.tierName, 64); b.writeVarInt(v.maxTier);
        b.writeVarInt(v.batch); b.writeVarInt(v.speedPercent); b.writeUtf(v.nextName, 64); b.writeUtf(v.nextSkill, 64); b.writeBoolean(v.nextSkillOk); writeParts(v.nextParts, b);
        b.writeUtf(v.running, 96); b.writeVarInt(v.secondsLeft); b.writeVarInt(v.secondsTotal); b.writeVarInt(v.grade); b.writeVarInt(v.readyCount); b.writeUtf(v.readyItem, 128); b.writeUtf(v.note, 256);
        b.writeVarInt(v.rows.size());
        for (Row r : v.rows) {
            b.writeUtf(r.id, 64); b.writeUtf(r.title, 96); b.writeUtf(r.output, 128); b.writeVarInt(r.count); b.writeVarInt(r.seconds); b.writeVarInt(r.tier); b.writeBoolean(r.allowed);
            b.writeUtf(r.reason, 128); b.writeBoolean(r.graded); b.writeBoolean(r.underground); b.writeUtf(r.note, 256); writeParts(r.parts, b);
        }
    }

    private static View read(FriendlyByteBuf b) {
        BlockPos pos = b.readBlockPos();
        String station = b.readUtf(64), title = b.readUtf(64), career = b.readUtf(32);
        int tier = b.readVarInt();
        String tierName = b.readUtf(64);
        int maxTier = b.readVarInt(), batch = b.readVarInt(), speed = b.readVarInt();
        String nextName = b.readUtf(64), nextSkill = b.readUtf(64);
        boolean nextOk = b.readBoolean();
        List<Part> nextParts = readParts(b);
        String running = b.readUtf(96);
        int left = b.readVarInt(), total = b.readVarInt(), grade = b.readVarInt(), readyCount = b.readVarInt();
        String readyItem = b.readUtf(128), note = b.readUtf(256);
        int n = Math.min(64, b.readVarInt());
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < n; i++)
            rows.add(new Row(b.readUtf(64), b.readUtf(96), b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(128), b.readBoolean(), b.readBoolean(), b.readUtf(256), readParts(b)));
        return new View(pos, station, title, career, tier, tierName, maxTier, batch, speed, nextName, nextSkill, nextOk, nextParts, running, left, total, grade, readyCount, readyItem, note, rows);
    }

    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, BiConsumer<T, ServerPlayer> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> handle.accept(m, player));
            context.setPacketHandled(true);
        }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static <T> void toClient(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, java.util.function.Consumer<T> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handle.accept(m)));
            context.setPacketHandled(true);
        }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Client side. */
    static void start(BlockPos pos, String recipe, int batches) { CHANNEL.sendToServer(new Start(pos, recipe, batches)); }
    static void collect(BlockPos pos) { CHANNEL.sendToServer(new Collect(pos)); }
    static void upgrade(BlockPos pos) { CHANNEL.sendToServer(new Upgrade(pos)); }
}
