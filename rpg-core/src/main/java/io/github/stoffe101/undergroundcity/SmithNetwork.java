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
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The Smith's channel (eighth pass): the server sends what a person sees at the bench (the drawing and the bar as it is turned) or at a machine (the stripped drive, its plate and what it does now),
 * and the toolkit's list of service calls; the client sends moves. The answers stay on the server: the client is told the drawing's lines because the drawing shows them, and the drive's parts'
 * wear because stripping it shows that; nothing it sends is taken on trust.
 */
public final class SmithNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "smith"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** The drawing: each section's line and where the bar is now, which sections carry tool marks, the roughing depth, the passes and the standard. */
    public record TurnView(String title, int[] target, int[] radius, boolean[] marked, int rough, int passes, int standard, int sleeves, String note) {}
    /** The drive: the motor, the parts in order with their state, each stage's gears and idler and whether it is open, the plate, the output now, the tests left. */
    public record DriveView(String machine, String address, int motor, int[] parts, int[] state, int[] driver, int[] driven, boolean[] idler, boolean[] open, int lo, int hi, boolean clockwise,
                            int output, boolean outClockwise, int tests, String needs, String note) {}
    record TurnMsg(TurnView view) {}
    record DriveMsg(DriveView view) {}
    record CallsMsg(List<SmithWork.Call> calls) {}
    record EndMsg(boolean done, String text) {}
    /** A move: {@code kind} and up to four numbers. */
    record Move(int kind, int a, int b, int c, int d) {}

    static final int CUT = 0, SLEEVE = 1, REPLACE = 2, GEAR = 3, IDLER = 4, RUN = 5, CLOSE = 6;

    private SmithNetwork() {}

    public static void register() {
        toClient(0, TurnMsg.class, (m, b) -> writeTurn(m.view, b), b -> new TurnMsg(readTurn(b)), m -> ClientSmith.turn(m.view));
        toClient(1, DriveMsg.class, (m, b) -> writeDrive(m.view, b), b -> new DriveMsg(readDrive(b)), m -> ClientSmith.drive(m.view));
        toClient(2, CallsMsg.class, (m, b) -> writeCalls(m.calls, b), b -> new CallsMsg(readCalls(b)), m -> ClientSmith.calls(m.calls));
        toClient(3, EndMsg.class, (m, b) -> { b.writeBoolean(m.done); b.writeUtf(m.text, 300); }, b -> new EndMsg(b.readBoolean(), b.readUtf(300)), m -> ClientSmith.end(m.done, m.text));
        toServer(4, Move.class, (m, b) -> { b.writeVarInt(m.kind); b.writeVarInt(m.a); b.writeVarInt(m.b); b.writeVarInt(m.c); b.writeVarInt(m.d); },
            b -> new Move(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()), (m, player) -> {
                switch (m.kind) {
                    case CUT -> SmithWork.cut(player, m.a, m.b, m.c, m.d != 0);
                    case SLEEVE -> SmithWork.sleeve(player, m.a);
                    case REPLACE -> SmithWork.replace(player, m.a);
                    case GEAR -> SmithWork.gear(player, m.a, m.b != 0, m.c);
                    case IDLER -> SmithWork.idler(player, m.a);
                    case RUN -> SmithWork.run(player);
                    case CLOSE -> SmithWork.close(player);
                    default -> { }
                }
            });
    }

    // ------------------------------------------------------------------------------------------------ server side

    static boolean reachable(ServerPlayer player) { return !(player instanceof FakePlayer) && player.connection != null; }
    static void send(ServerPlayer player, Object message) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message); }

    static void sendTurn(ServerPlayer player, SmithWork.Turn t, String title) {
        send(player, new TurnMsg(new TurnView(title, t.t.target.clone(), t.t.radius.clone(), t.t.marked.clone(), t.t.rough, t.t.passes, t.t.standard, t.t.sleeves, t.note)));
    }

    static void sendDrive(ServerPlayer player, SmithWork.Repair r, MachineService.Machine known) {
        MachineService.Machine m = known != null ? known : MachineService.works(player.server).machines.get(r.key);
        SmithPuzzles.Drive d = r.d;
        int[] parts = new int[d.parts.length], state = new int[d.parts.length];
        for (int i = 0; i < parts.length; i++) { parts[i] = d.parts[i].ordinal(); state[i] = !d.worn[i] ? 0 : d.replaced[i] ? 2 : 1; }
        StringBuilder needs = new StringBuilder();
        java.util.Map<String, Integer> count = new java.util.LinkedHashMap<>();
        for (String id : d.uses()) count.merge(id, 1, Integer::sum);
        for (var e : count.entrySet()) {
            if (needs.length() > 0) needs.append(", ");
            needs.append(e.getValue()).append(' ').append(SmithWork.item(e.getKey()).getDescription().getString().toLowerCase(java.util.Locale.ROOT));
        }
        send(player, new DriveMsg(new DriveView(m == null ? "Machine" : Casing.capitalise(m.name()), m == null ? "" : m.address(), d.motor, parts, state, d.driver.clone(), d.driven.clone(),
            d.idler.clone(), d.open.clone(), d.lo, d.hi, d.clockwise, (int) Math.round(d.output()), d.clockwise(), d.tests, needs.toString(), r.note)));
    }

    static void sendCalls(ServerPlayer player, List<SmithWork.Call> calls) { send(player, new CallsMsg(calls)); }
    static void sendEnd(ServerPlayer player, boolean done, String text) { send(player, new EndMsg(done, text)); }

    // ------------------------------------------------------------------------------------------------ client side

    static void move(int kind, int a, int b, int c, int d) { CHANNEL.sendToServer(new Move(kind, a, b, c, d)); }

    // ------------------------------------------------------------------------------------------------ the wire

    private static void writeInts(int[] v, FriendlyByteBuf b) { b.writeVarInt(v.length); for (int x : v) b.writeVarInt(x); }
    private static int[] readInts(FriendlyByteBuf b) { int n = Math.min(16, b.readVarInt()); int[] v = new int[n]; for (int i = 0; i < n; i++) v[i] = b.readVarInt(); return v; }
    private static void writeFlags(boolean[] v, FriendlyByteBuf b) { b.writeVarInt(v.length); for (boolean x : v) b.writeBoolean(x); }
    private static boolean[] readFlags(FriendlyByteBuf b) { int n = Math.min(16, b.readVarInt()); boolean[] v = new boolean[n]; for (int i = 0; i < n; i++) v[i] = b.readBoolean(); return v; }

    private static void writeTurn(TurnView v, FriendlyByteBuf b) {
        b.writeUtf(v.title, 64); writeInts(v.target, b); writeInts(v.radius, b); writeFlags(v.marked, b);
        b.writeVarInt(v.rough); b.writeVarInt(v.passes); b.writeVarInt(v.standard); b.writeVarInt(v.sleeves); b.writeUtf(v.note, 200);
    }

    private static TurnView readTurn(FriendlyByteBuf b) {
        return new TurnView(b.readUtf(64), readInts(b), readInts(b), readFlags(b), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(200));
    }

    private static void writeDrive(DriveView v, FriendlyByteBuf b) {
        b.writeUtf(v.machine, 48); b.writeUtf(v.address, 64); b.writeVarInt(v.motor); writeInts(v.parts, b); writeInts(v.state, b); writeInts(v.driver, b); writeInts(v.driven, b);
        writeFlags(v.idler, b); writeFlags(v.open, b); b.writeVarInt(v.lo); b.writeVarInt(v.hi); b.writeBoolean(v.clockwise); b.writeVarInt(v.output); b.writeBoolean(v.outClockwise);
        b.writeVarInt(v.tests); b.writeUtf(v.needs, 160); b.writeUtf(v.note, 200);
    }

    private static DriveView readDrive(FriendlyByteBuf b) {
        return new DriveView(b.readUtf(48), b.readUtf(64), b.readVarInt(), readInts(b), readInts(b), readInts(b), readInts(b), readFlags(b), readFlags(b), b.readVarInt(), b.readVarInt(),
            b.readBoolean(), b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readUtf(160), b.readUtf(200));
    }

    private static void writeCalls(List<SmithWork.Call> calls, FriendlyByteBuf b) {
        b.writeVarInt(calls.size());
        for (SmithWork.Call c : calls) {
            b.writeUUID(c.id()); b.writeUtf(c.machine(), 48); b.writeUtf(c.city(), 32); b.writeUtf(c.address(), 64); b.writeUtf(c.label(), 48); b.writeVarInt(c.tier());
            b.writeVarInt(c.credits()); b.writeVarInt(c.xp()); b.writeBlockPos(c.access()); b.writeVarLong(c.minutesOpen()); b.writeUtf(c.needs(), 160); b.writeUtf(c.blocked(), 64);
        }
    }

    private static List<SmithWork.Call> readCalls(FriendlyByteBuf b) {
        int n = Math.min(12, b.readVarInt());
        List<SmithWork.Call> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = b.readUUID();
            String machine = b.readUtf(48), city = b.readUtf(32), address = b.readUtf(64), label = b.readUtf(48);
            int tier = b.readVarInt(), credits = b.readVarInt(), xp = b.readVarInt();
            BlockPos access = b.readBlockPos();
            long minutes = b.readVarLong();
            out.add(new SmithWork.Call(id, machine, city, address, label, tier, credits, xp, access, minutes, b.readUtf(160), b.readUtf(64)));
        }
        return out;
    }

    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, BiConsumer<T, ServerPlayer> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            // the work queue keeps an exception to itself; it is written to the log instead (the Crew screen's lesson, seventh pass)
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> {
                try { handle.accept(m, player); }
                catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(SmithNetwork.class).error("[UC] a Smith's move from {} failed", player.getGameProfile().getName(), error); }
            });
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
}
