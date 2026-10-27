package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Technician's channel (D-158). The server opens the computer and the bench with what they show, sends a puzzle's state as the player works it (only what may be seen: never the dead part,
 * never the solved grid) and how it ended; the client sends moves (a probe, a replacement, a turn), closing, and a bench assembly. Every move is judged on the server.
 */
public final class TechNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "tech"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** One design as the bench shows it: each part with how many are needed and how many the player has, and whether the player may make it. */
    public record Part(String item, int need, int have) {}
    public record DesignRow(String id, String title, String output, int count, List<Part> parts, String skill, boolean allowed, String note) {}

    /** What the repair screen may show. {@code readings}: -1 not probed, 0 dead, 1 live, per part. */
    public record TraceView(int trunk, int branch, boolean split, int[] parts, int[] readings, boolean loadA, boolean loadB, int tries, int scanLo, int scanHi, int secondsLeft, int probes) {}
    /** What the patch screen may show. */
    public record PatchView(String kind, int w, int h, int source, int[] outputs, int[] tiles, int secondsLeft) {}

    record ComputerOpen(List<TechWork.Call> calls, List<DesignRow> designs, boolean technician, int level) {}
    record BenchOpen(List<DesignRow> designs, String note) {}
    record TraceMsg(TraceView view) {}
    record PatchMsg(PatchView view) {}
    record NoteMsg(String text) {}
    record EndMsg(boolean done, String text) {}
    record Move(int kind, int index) {}
    record Assemble(String id) {}

    /** Move kinds: a probe, a replacement, a turn of a tile, closing the panel. */
    static final int PROBE = 0, REPLACE = 1, TURN = 2, CLOSE = 3;

    private TechNetwork() {}

    public static void register() {
        toClient(0, ComputerOpen.class, TechNetwork::writeComputer, TechNetwork::readComputer, m -> ClientTech.computer(m.calls, m.designs, m.technician, m.level));
        toClient(1, BenchOpen.class, (m, b) -> { writeDesigns(m.designs, b); b.writeUtf(m.note, 200); }, b -> new BenchOpen(readDesigns(b), b.readUtf(200)), m -> ClientTech.bench(m.designs, m.note));
        toClient(2, TraceMsg.class, (m, b) -> writeTrace(m.view, b), b -> new TraceMsg(readTrace(b)), m -> ClientTech.trace(m.view));
        toClient(3, PatchMsg.class, (m, b) -> writePatch(m.view, b), b -> new PatchMsg(readPatch(b)), m -> ClientTech.patch(m.view));
        toClient(4, NoteMsg.class, (m, b) -> b.writeUtf(m.text, 200), b -> new NoteMsg(b.readUtf(200)), m -> ClientTech.note(m.text));
        toClient(5, EndMsg.class, (m, b) -> { b.writeBoolean(m.done); b.writeUtf(m.text, 240); }, b -> new EndMsg(b.readBoolean(), b.readUtf(240)), m -> ClientTech.end(m.done, m.text));
        toServer(6, Move.class, (m, b) -> { b.writeVarInt(m.kind); b.writeVarInt(m.index); }, b -> new Move(b.readVarInt(), b.readVarInt()), (m, player) -> {
            switch (m.kind) {
                case PROBE -> { Boolean live = TechWork.probe(player, m.index); TechWork.Session s = TechWork.sessionOf(player.getUUID()); if (live != null && s != null) sendSession(player, s); }
                case REPLACE -> TechWork.replace(player, m.index);
                case TURN -> TechWork.turn(player, m.index);
                default -> TechWork.abandon(player);
            }
        });
        toServer(7, Assemble.class, (m, b) -> b.writeUtf(m.id, 48), b -> new Assemble(b.readUtf(48)), (m, player) -> {
            String refusal = TechWork.assemble(player, m.id);
            TechDesigns.Design d = TechDesigns.INSTANCE.find(m.id);
            send(player, new BenchOpen(designs(player), refusal != null ? refusal : "Made: " + (d == null ? m.id : d.title()) + "."));
        });
    }

    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, BiConsumer<T, ServerPlayer> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> handle.accept(m, player));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static <T> void toClient(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, Consumer<T> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handle.accept(m)));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    // ------------------------------------------------------------------------------------------------ what is shown

    static List<DesignRow> designs(ServerPlayer player) {
        CitizenState.Citizen c = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        List<DesignRow> out = new ArrayList<>();
        for (TechDesigns.Design d : TechDesigns.INSTANCE.all()) {
            List<Part> parts = new ArrayList<>();
            for (var in : d.inputs().entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(in.getKey());
                parts.add(new Part(in.getKey().toString(), in.getValue(), item == null ? 0 : player.getInventory().countItem(item)));
            }
            SkillNode node = d.skill().isEmpty() ? null : SkillCatalog.INSTANCE.find(d.skill());
            boolean allowed = c != null && (d.skill().isEmpty() || SkillService.has(c, d.skill()));
            out.add(new DesignRow(d.id(), d.title(), d.output().toString(), d.count(), parts, node == null ? "" : node.title(), allowed, d.note()));
        }
        return out;
    }

    static TraceView view(TechWork.Session s, long now) {
        TechPuzzles.Trace t = s.trace;
        int[] parts = new int[t.size()], readings = new int[t.size()];
        for (int i = 0; i < t.size(); i++) { parts[i] = t.parts[i].ordinal(); readings[i] = s.readings.containsKey(i) ? (s.readings.get(i) ? 1 : 0) : -1; }
        return new TraceView(t.trunk, t.branch, t.split, parts, readings, t.loadLit(0), t.split && t.loadLit(1), s.tries, s.scan == null ? -1 : s.scan[0], s.scan == null ? -1 : s.scan[1],
            (int) Math.max(0, (s.endsAt - now) / 20), s.probes);
    }

    static PatchView view(TechWork.Session s, long now, String kind) {
        TechPuzzles.Patch p = s.patch;
        return new PatchView(kind, p.w, p.h, p.source, p.outputs.clone(), p.tiles.clone(), (int) Math.max(0, (s.endsAt - now) / 20));
    }

    // ------------------------------------------------------------------------------------------------ wire formats

    private static void writeComputer(ComputerOpen m, FriendlyByteBuf b) {
        b.writeVarInt(m.calls.size());
        for (TechWork.Call c : m.calls) {
            b.writeUUID(c.id()); b.writeUtf(c.kind(), 24); b.writeUtf(c.city(), 24); b.writeUtf(c.address(), 48); b.writeUtf(c.label(), 40); b.writeVarInt(c.tier());
            b.writeVarInt(c.credits()); b.writeVarInt(c.xp()); b.writeBlockPos(c.access()); b.writeVarLong(c.minutesOpen()); b.writeUtf(c.blocked(), 64);
        }
        writeDesigns(m.designs, b);
        b.writeBoolean(m.technician);
        b.writeVarInt(m.level);
    }

    private static ComputerOpen readComputer(FriendlyByteBuf b) {
        int n = Math.min(64, b.readVarInt());
        List<TechWork.Call> calls = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            calls.add(new TechWork.Call(b.readUUID(), b.readUtf(24), b.readUtf(24), b.readUtf(48), b.readUtf(40), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBlockPos(), b.readVarLong(), b.readUtf(64)));
        return new ComputerOpen(calls, readDesigns(b), b.readBoolean(), b.readVarInt());
    }

    private static void writeDesigns(List<DesignRow> designs, FriendlyByteBuf b) {
        b.writeVarInt(designs.size());
        for (DesignRow d : designs) {
            b.writeUtf(d.id(), 48); b.writeUtf(d.title(), 48); b.writeUtf(d.output(), 96); b.writeVarInt(d.count());
            b.writeVarInt(d.parts().size());
            for (Part p : d.parts()) { b.writeUtf(p.item(), 96); b.writeVarInt(p.need()); b.writeVarInt(p.have()); }
            b.writeUtf(d.skill(), 48); b.writeBoolean(d.allowed()); b.writeUtf(d.note(), 200);
        }
    }

    private static List<DesignRow> readDesigns(FriendlyByteBuf b) {
        int n = Math.min(64, b.readVarInt());
        List<DesignRow> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = b.readUtf(48), title = b.readUtf(48), output = b.readUtf(96);
            int count = b.readVarInt();
            int parts = Math.min(9, b.readVarInt());
            List<Part> list = new ArrayList<>(parts);
            for (int k = 0; k < parts; k++) list.add(new Part(b.readUtf(96), b.readVarInt(), b.readVarInt()));
            out.add(new DesignRow(id, title, output, count, list, b.readUtf(48), b.readBoolean(), b.readUtf(200)));
        }
        return out;
    }

    private static void writeTrace(TraceView v, FriendlyByteBuf b) {
        b.writeVarInt(v.trunk()); b.writeVarInt(v.branch()); b.writeBoolean(v.split()); b.writeVarIntArray(v.parts());
        int[] r = new int[v.readings().length];
        for (int i = 0; i < r.length; i++) r[i] = v.readings()[i] + 1;
        b.writeVarIntArray(r);
        b.writeBoolean(v.loadA()); b.writeBoolean(v.loadB()); b.writeVarInt(v.tries()); b.writeVarInt(v.scanLo() + 1); b.writeVarInt(v.scanHi() + 1); b.writeVarInt(v.secondsLeft()); b.writeVarInt(v.probes());
    }

    private static TraceView readTrace(FriendlyByteBuf b) {
        int trunk = b.readVarInt(), branch = b.readVarInt();
        boolean split = b.readBoolean();
        int[] parts = b.readVarIntArray(32), r = b.readVarIntArray(32);
        for (int i = 0; i < r.length; i++) r[i] -= 1;
        return new TraceView(trunk, branch, split, parts, r, b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt(), b.readVarInt());
    }

    private static void writePatch(PatchView v, FriendlyByteBuf b) {
        b.writeUtf(v.kind(), 16); b.writeVarInt(v.w()); b.writeVarInt(v.h()); b.writeVarInt(v.source()); b.writeVarIntArray(v.outputs()); b.writeVarIntArray(v.tiles()); b.writeVarInt(v.secondsLeft());
    }

    private static PatchView readPatch(FriendlyByteBuf b) {
        return new PatchView(b.readUtf(16), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarIntArray(8), b.readVarIntArray(64), b.readVarInt());
    }

    // ------------------------------------------------------------------------------------------------ sending

    private static boolean reachable(ServerPlayer player) { return player != null && !(player instanceof FakePlayer) && player.connection != null; }

    static void send(ServerPlayer player, Object message) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message); }

    static void openComputer(ServerPlayer player, BlockPos pos) {
        CitizenState.Citizen c = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        send(player, new ComputerOpen(TechWork.board(player), designs(player), c != null && "technician".equals(c.career), c == null ? 1 : c.careerLevel()));
    }

    static void openBench(ServerPlayer player, BlockPos pos) { send(player, new BenchOpen(designs(player), "")); }

    static void sendSession(ServerPlayer player, TechWork.Session s) {
        long now = player.server.getTickCount();
        if (s.trace != null) send(player, new TraceMsg(view(s, now)));
        else send(player, new PatchMsg(view(s, now, s.kind)));
    }

    static void sendNote(ServerPlayer player, String text) { send(player, new NoteMsg(text)); }
    static void sendEnd(ServerPlayer player, boolean done, String text) { send(player, new EndMsg(done, text)); }

    // client to server
    static void move(int kind, int index) { CHANNEL.sendToServer(new Move(kind, index)); }
    static void assemble(String id) { CHANNEL.sendToServer(new Assemble(id)); }
}
