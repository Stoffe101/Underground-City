package io.github.stoffe101.undergroundcity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * The Crew screen's own channel. The client may ask to open or refresh the screen and may send one kind of request, an operation with a few
 * bounded arguments. The server answers with a whole {@link CrewView} and never trusts the request: roles, permissions, balances, prices and
 * ownership are all decided by {@link CrewLedger} and {@link CrewService}.
 */
public final class CrewNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "crew"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static final Map<ServerPlayer, Integer> LAST_REQUEST = new WeakHashMap<>();

    /** Every request the screen can make. The order is the wire format: append only. */
    public enum Op {
        REFRESH, CREATE, INVITE, ACCEPT, DECLINE, WITHDRAW_INVITE, LEAVE, KICK, SET_ROLE, TRANSFER, CLAIM_LEAD, RENAME, PERMISSION, MODE, CONSENT, PAY_OUT,
        DISBAND_ASK, DISBAND, DEPOSIT, WITHDRAW, CLAIM_OWED, LEASE, CLAIM_CONTAINER, RELEASE_CONTAINER,
        RENT, PAY, AUTOPAY, MOVE_OUT, SELL_BACK, UPGRADE, HAND_TO_CREW, HAND_TO_FOUNDER
    }

    /** One request: {@code text}, {@code target}, {@code amount} and {@code flag} mean what the operation says (see {@link CrewActions}). */
    public record Act(Op op, String text, UUID target, long amount, boolean flag, boolean flag2, UUID tx) {
        public static Act of(Op op) { return new Act(op, "", new UUID(0, 0), 0, false, false, UUID.randomUUID()); }
        public Act text(String text) { return new Act(op, text, target, amount, flag, flag2, tx); }
        public Act target(UUID target) { return new Act(op, text, target, amount, flag, flag2, tx); }
        public Act amount(long amount) { return new Act(op, text, target, amount, flag, flag2, tx); }
        public Act flag(boolean flag) { return new Act(op, text, target, amount, flag, flag2, tx); }
        public Act flag2(boolean flag2) { return new Act(op, text, target, amount, flag, flag2, tx); }
    }

    private record Ask(boolean open) {}
    private record Show(CrewView view, boolean open, String note) {}

    private CrewNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, Ask.class, (packet, buffer) -> buffer.writeBoolean(packet.open), buffer -> new Ask(buffer.readBoolean()),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null && slot(player)) sendView(player, packet.open);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Show.class, (packet, buffer) -> { packet.view.write(buffer); buffer.writeBoolean(packet.open); buffer.writeUtf(packet.note, 160); },
            buffer -> new Show(CrewView.read(buffer), buffer.readBoolean(), buffer.readUtf(160)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientCrew.show(packet.view, packet.open, packet.note)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Act.class, CrewNetwork::write, CrewNetwork::read,
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null && slot(player)) CrewActions.perform(player, packet);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void write(Act act, FriendlyByteBuf buffer) {
        buffer.writeEnum(act.op); buffer.writeUtf(act.text, 48); buffer.writeUUID(act.target); buffer.writeVarLong(act.amount);
        buffer.writeBoolean(act.flag); buffer.writeBoolean(act.flag2); buffer.writeUUID(act.tx);
    }

    private static Act read(FriendlyByteBuf buffer) {
        return new Act(buffer.readEnum(Op.class), buffer.readUtf(48), buffer.readUUID(), buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(), buffer.readUUID());
    }

    /** At most one request every three ticks per player; a dropped request is simply asked again by the next click or refresh. */
    private static boolean slot(ServerPlayer player) {
        int now = player.server.getTickCount();
        Integer last = LAST_REQUEST.get(player);
        if (last != null && now - last < 3) return false;
        LAST_REQUEST.put(player, now);
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------ server side

    /** Sends the player's Crew view. {@code open} tells a client with no Crew screen up to open one. */
    public static void sendView(ServerPlayer player, boolean open) { sendView(player, open, ""); }

    /** {@code note} is a line for the screen's footer (the answer to the last request); a leading '!' marks a refusal. */
    public static void sendView(ServerPlayer player, boolean open, String note) {
        String line = note == null ? "" : note.length() > 160 ? note.substring(0, 160) : note;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Show(CrewView.of(player, CrewActions.armed(player)), open, line));
    }

    // ------------------------------------------------------------------------------------------------------------ client side

    /** Client: ask the server for the screen (the K key, a button) or for fresh numbers for the open screen. */
    public static void ask(boolean open) { CHANNEL.sendToServer(new Ask(open)); }
    public static void send(Act act) { CHANNEL.sendToServer(act); }
}
