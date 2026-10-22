package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkDirection;
import java.util.Optional;
import java.util.UUID;

import java.util.function.Supplier;

/**
 * Server-validated terminal bridge. Opening the initial civilian QShop requires
 * a physical nearby Operations Terminal and a registered citizen.
 * This is NOT a remote city supplier, contraband order, or raid service.
 */
public final class TerminalNetwork {
    private static final String VERSION = "11";
    private static final String STARTER_SHOP_ID = "uc_starter_supply";
    private static final double MAX_TERMINAL_DISTANCE_SQUARED = 64.0;
    private static final java.util.Map<ServerPlayer, Integer> REQUEST_TIMES = new java.util.WeakHashMap<>();
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "terminal"),
        () -> VERSION, VERSION::equals, VERSION::equals
    );

    private TerminalNetwork() {}

    public static void register() {
        registerChallenge();
        registerRegistration();
        registerTracker();
        registerObjective();
        registerDialogue();
        registerLedger();
        registerNotice();
        registerHud();
        CHANNEL.registerMessage(0, OpenTerminal.class,
            (packet, buffer) -> { buffer.writeBlockPos(packet.pos); packet.data.write(buffer); },
            buffer -> new OpenTerminal(buffer.readBlockPos(), TerminalData.read(buffer)),
            TerminalNetwork::receiveOpen, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, OpenStarterShop.class,
            (packet, buffer) -> buffer.writeBlockPos(packet.pos),
            buffer -> new OpenStarterShop(buffer.readBlockPos()),
            TerminalNetwork::receiveShop, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, WorkAction.class,
            (packet, buffer) -> {
                buffer.writeBlockPos(packet.pos); buffer.writeVarInt(packet.action);
                buffer.writeUtf(packet.id, 128); buffer.writeUUID(packet.nonce);
            }, buffer -> new WorkAction(buffer.readBlockPos(), buffer.readVarInt(), buffer.readUtf(128), buffer.readUUID()),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) WorkOrderService.action(player, packet.pos, packet.action, packet.id, packet.nonce);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, RegionalBuy.class,
            (packet, buffer) -> {
                buffer.writeBlockPos(packet.pos); buffer.writeUtf(packet.commodity, 128);
                buffer.writeVarInt(packet.quantity); buffer.writeUUID(packet.nonce);
            }, buffer -> new RegionalBuy(buffer.readBlockPos(), buffer.readUtf(128), buffer.readVarInt(), buffer.readUUID()),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) RegionalMarket.buy(player, packet.pos, packet.commodity, packet.quantity, packet.nonce);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, SkillAction.class,
            (packet, buffer) -> { buffer.writeBlockPos(packet.pos); buffer.writeUtf(packet.skill, 64); },
            buffer -> new SkillAction(buffer.readBlockPos(), buffer.readUtf(64)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) SkillService.unlock(player, packet.pos, packet.skill);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void registerNotice() {
        CHANNEL.registerMessage(17, NoticePacket.class,
            (packet, buffer) -> { buffer.writeEnum(packet.kind); buffer.writeUtf(packet.title, 48); buffer.writeUtf(packet.body, 120); },
            buffer -> new NoticePacket(buffer.readEnum(Notice.Kind.class), buffer.readUtf(48), buffer.readUtf(120)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientNotice.show(packet.kind, packet.title, packet.body)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void registerHud() {
        CHANNEL.registerMessage(18, HudPacket.class,
            (packet, buffer) -> packet.state.write(buffer),
            buffer -> new HudPacket(HudState.read(buffer)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHud.update(packet.state)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** The on-screen status of one player (see {@link HudService}). */
    static void sendHud(ServerPlayer player, HudState state) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new HudPacket(state)); }

    public static final class HudPacket {
        final HudState state;
        HudPacket(HudState state) { this.state = state; }
    }

    static void sendNotice(ServerPlayer player, Notice.Kind kind, String title, String body) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new NoticePacket(kind, title.length() > 48 ? title.substring(0, 48) : title, body.length() > 120 ? body.substring(0, 120) : body));
    }

    private static void registerLedger() {
        CHANNEL.registerMessage(16, OpenLedger.class,
            (packet, buffer) -> packet.data.write(buffer),
            buffer -> new OpenLedger(LedgerData.read(buffer)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientLedger.open(packet.data)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Called after a physical right-click on a Public Ledger. */
    static void openLedger(ServerPlayer player, BlockPos pos) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenLedger(LedgerData.snapshot(player, pos)));
    }

    private static void registerDialogue() {
        CHANNEL.registerMessage(13, OpenDialogue.class,
            (packet, buffer) -> {
                buffer.writeVarInt(packet.entityId); buffer.writeUtf(packet.name, 48); buffer.writeUtf(packet.subtitle, 64);
                buffer.writeUtf(packet.city, 16); buffer.writeUtf(packet.text, 600);
                buffer.writeVarInt(packet.choices.size());
                for (Dialogue.Choice choice : packet.choices) { buffer.writeUtf(choice.id(), 24); buffer.writeUtf(choice.label(), 56); buffer.writeBoolean(choice.enabled()); buffer.writeUtf(choice.hint(), 96); }
            },
            buffer -> {
                int entityId = buffer.readVarInt();
                String name = buffer.readUtf(48), subtitle = buffer.readUtf(64), city = buffer.readUtf(16), text = buffer.readUtf(600);
                int count = Math.min(buffer.readVarInt(), 6);
                java.util.List<Dialogue.Choice> choices = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) choices.add(new Dialogue.Choice(buffer.readUtf(24), buffer.readUtf(56), buffer.readBoolean(), buffer.readUtf(96)));
                return new OpenDialogue(entityId, name, subtitle, city, text, java.util.List.copyOf(choices));
            },
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientDialogue.open(packet.entityId, packet.name, packet.subtitle, packet.city, packet.text, packet.choices)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(14, DialogueChoice.class,
            (packet, buffer) -> { buffer.writeVarInt(packet.entityId); buffer.writeUtf(packet.choice, 24); },
            buffer -> new DialogueChoice(buffer.readVarInt(), buffer.readUtf(24)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) Dialogue.choose(player, packet.entityId, packet.choice);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(15, CloseDialogue.class,
            (packet, buffer) -> { },
            buffer -> new CloseDialogue(),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientDialogue::close));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Server to client: show (or refresh) a conversation. */
    static void sendDialogue(ServerPlayer player, int entityId, String name, String subtitle, String city, String text, java.util.List<Dialogue.Choice> choices) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenDialogue(entityId, name, subtitle, city, text, java.util.List.copyOf(choices)));
    }
    static void closeDialogue(ServerPlayer player) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CloseDialogue()); }
    /** Client to server: the player picked an answer; the server re-validates everything. */
    public static void sendDialogueChoice(int entityId, String choice) { CHANNEL.sendToServer(new DialogueChoice(entityId, choice)); }

    private record OpenDialogue(int entityId, String name, String subtitle, String city, String text, java.util.List<Dialogue.Choice> choices) {}
    private record DialogueChoice(int entityId, String choice) {}
    private record CloseDialogue() {}

    private static void registerObjective() {
        CHANNEL.registerMessage(12, ObjectivePacket.class,
            (packet, buffer) -> {
                var o = packet.objective;
                buffer.writeBoolean(o != null);
                if (o == null) return;
                buffer.writeUtf(o.title(), 48); buffer.writeUtf(o.hint(), 160); buffer.writeUtf(o.page(), 16);
                buffer.writeBoolean(o.target() != null);
                if (o.target() != null) buffer.writeBlockPos(o.target());
                buffer.writeUtf(o.targetName(), 48);
            },
            buffer -> {
                if (!buffer.readBoolean()) return new ObjectivePacket(null);
                String title = buffer.readUtf(48), hint = buffer.readUtf(160), page = buffer.readUtf(16);
                BlockPos target = buffer.readBoolean() ? buffer.readBlockPos() : null;
                return new ObjectivePacket(new Journey.Objective(title, hint, page, target, buffer.readUtf(48)));
            },
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientOrderTracker.updateObjective(packet.objective)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Server to client: the next guided-journey step (or null when the journey is complete). */
    public static void sendObjective(ServerPlayer player, Journey.Objective objective) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ObjectivePacket(objective));
    }

    public static final class ObjectivePacket {
        final Journey.Objective objective;
        ObjectivePacket(Journey.Objective objective) { this.objective = objective; }
    }

    private static void registerTracker() {
        CHANNEL.registerMessage(10, TrackedOrder.class,
            (packet, buffer) -> {
                buffer.writeBoolean(packet.tracked != null);
                if (packet.tracked == null) return;
                var tracked = packet.tracked;
                buffer.writeUtf(tracked.title(), 96); buffer.writeVarInt(tracked.credits()); buffer.writeVarInt(tracked.xp());
                buffer.writeUtf(tracked.deliverIn(), 96); buffer.writeBoolean(tracked.underground());
                buffer.writeVarInt(tracked.lines().size());
                for (var line : tracked.lines()) { buffer.writeUtf(line.item(), 96); buffer.writeUtf(line.label(), 64); buffer.writeVarInt(line.held()); buffer.writeVarInt(line.count()); }
            },
            buffer -> {
                if (!buffer.readBoolean()) return new TrackedOrder(null);
                String title = buffer.readUtf(96); int credits = buffer.readVarInt(), xp = buffer.readVarInt();
                String deliverIn = buffer.readUtf(96); boolean underground = buffer.readBoolean();
                int count = Math.min(buffer.readVarInt(), OrderTracker.MAX_LINES);
                java.util.List<OrderTracker.Line> lines = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) lines.add(new OrderTracker.Line(buffer.readUtf(96), buffer.readUtf(64), buffer.readVarInt(), buffer.readVarInt()));
                return new TrackedOrder(new OrderTracker.Tracked(title, credits, xp, deliverIn, underground, lines));
            },
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientOrderTracker.update(packet.tracked)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Server to client: the accepted order with its live progress, or null to clear the tracker. */
    public static void sendTracked(ServerPlayer player, OrderTracker.Tracked tracked) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TrackedOrder(tracked));
    }

    public static final class TrackedOrder {
        final OrderTracker.Tracked tracked;
        TrackedOrder(OrderTracker.Tracked tracked) { this.tracked = tracked; }
    }

    private static void registerRegistration() {
        CHANNEL.registerMessage(7, OpenRegistration.class,
            (packet, buffer) -> {
                buffer.writeVarInt(packet.kits.size());
                for (var career : packet.kits.entrySet()) {
                    buffer.writeUtf(career.getKey(), 16);
                    buffer.writeVarInt(career.getValue().size());
                    for (String id : career.getValue()) buffer.writeUtf(id, 96);
                }
            },
            buffer -> {
                java.util.Map<String, java.util.List<String>> kits = new java.util.LinkedHashMap<>();
                int careers = Math.min(buffer.readVarInt(), 8);
                for (int i = 0; i < careers; i++) {
                    String career = buffer.readUtf(16);
                    int count = Math.min(buffer.readVarInt(), 8);
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (int k = 0; k < count; k++) ids.add(buffer.readUtf(96));
                    kits.put(career, java.util.List.copyOf(ids));
                }
                return new OpenRegistration(kits);
            },
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientRegistration.open(packet.kits)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(11, AskRegistration.class,
            (packet, buffer) -> { },
            buffer -> new AskRegistration(),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null && requestSlot(player)) RegistrationService.prompt(player);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, RegisterChoice.class,
            (packet, buffer) -> { buffer.writeUtf(packet.city, 16); buffer.writeUtf(packet.career, 16); },
            buffer -> new RegisterChoice(buffer.readUtf(16), buffer.readUtf(16)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null && requestSlot(player)) RegistrationService.handleChoice(player, packet.city, packet.career);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(9, RegistrationResult.class,
            (packet, buffer) -> { buffer.writeBoolean(packet.ok); buffer.writeUtf(packet.message, 120); },
            buffer -> new RegistrationResult(buffer.readBoolean(), buffer.readUtf(120)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientRegistration.result(packet.ok, packet.message)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void registerChallenge() {
        CHANNEL.registerMessage(5, OpenChallenge.class,
            (packet, buffer) -> { buffer.writeBlockPos(packet.pos); buffer.writeByteArray(packet.sequence); },
            buffer -> new OpenChallenge(buffer.readBlockPos(), buffer.readByteArray(16)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ClientChallenge.open(packet.pos, packet.sequence)));
                }
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, ChallengeAnswer.class,
            (packet, buffer) -> { buffer.writeBlockPos(packet.pos); buffer.writeByteArray(packet.answer); },
            buffer -> new ChallengeAnswer(buffer.readBlockPos(), buffer.readByteArray(16)),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) SecureTerminalService.answer(player, packet.pos, packet.answer, player.server.getTickCount());
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /** Server to client: show the first-login registration screen. */
    public static void openRegistration(ServerPlayer player) {
        java.util.Map<String, java.util.List<String>> kits = new java.util.LinkedHashMap<>();
        for (String career : java.util.List.of("farmer", "vintner", "smith", "gunsmith", "technician")) kits.put(career, StarterKits.INSTANCE.displayItems(career, 5));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenRegistration(kits));
    }

    /** Client to server: show me the registration screen again (a terminal's REGISTER button). A registered citizen is never asked. */
    public static void requestRegistrationScreen() {
        CHANNEL.sendToServer(new AskRegistration());
    }

    /** Server to client: the outcome of a registration request. */
    public static void registrationResult(ServerPlayer player, boolean ok, String message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RegistrationResult(ok, message));
    }

    /** Client to server: the player's choice. The server validates both identifiers and registers once. */
    public static void sendRegistration(String city, String career) {
        CHANNEL.sendToServer(new RegisterChoice(city, career));
    }

    /** Server to client: the hidden sequence to flash. The client only displays it; the server checks the answer. */
    public static void openChallenge(ServerPlayer player, BlockPos pos, byte[] sequence) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenChallenge(pos.immutable(), sequence.clone()));
    }

    /** Client to server: the single answer for the open challenge. */
    public static void sendChallengeAnswer(BlockPos pos, byte[] answer) {
        CHANNEL.sendToServer(new ChallengeAnswer(pos.immutable(), answer.clone()));
    }

    private static void receiveOpen(OpenTerminal packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientTerminal.open(packet.pos, packet.data)));
        }
        context.setPacketHandled(true);
    }

    private static void receiveShop(OpenStarterShop packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) openVerifiedStarterShop(player, packet.pos);
            });
        }
        context.setPacketHandled(true);
    }

    /** Called after physical right-click, not by a remote client action. */
    public static void open(ServerPlayer player, BlockPos pos) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTerminal(pos.immutable(), TerminalData.snapshot(player, pos)));
    }

    /** Client click on a regional offer; quantity is validated and the price is server-owned. */
    public static void requestRegionalBuy(BlockPos pos, String commodity, int quantity) {
        CHANNEL.sendToServer(new RegionalBuy(pos.immutable(), commodity, quantity, UUID.randomUUID()));
    }

    /** Client click on a skill node; the server re-checks level, prerequisites and points. */
    public static void requestSkill(BlockPos pos, String skill) {
        CHANNEL.sendToServer(new SkillAction(pos.immutable(), skill));
    }

    public static void requestWork(BlockPos pos, int action, String id, UUID nonce) {
        CHANNEL.sendToServer(new WorkAction(pos.immutable(), action, id, nonce == null ? new UUID(0, 0) : nonce));
    }

    static boolean requestSlot(ServerPlayer player) {
        int now = player.server.getTickCount();
        Integer previous = REQUEST_TIMES.get(player);
        if (previous != null && now - previous < 5) return false;
        REQUEST_TIMES.put(player, now);
        return true;
    }

    /** Caller must be a click on the real PC screen, never a free-typed shop ID. */
    public static void requestStarterShop(BlockPos pos) {
        CHANNEL.sendToServer(new OpenStarterShop(pos.immutable()));
    }

    /** A terminal action at {@code pos}: a placed Operations Terminal (as for the starter shop), or an open shopkeeper's counter (D-126), which only sells. */
    static boolean authorizedForExchange(ServerPlayer player, BlockPos pos) {
        return authorizedForStarterShop(player, pos) || ShopCounters.authorized(player, pos);
    }

    static boolean authorizedForStarterShop(ServerPlayer player, BlockPos pos) {
        if (player == null || !player.isAlive() || player.isSpectator() || pos == null || !player.serverLevel().hasChunkAt(pos)) return false;
        if (!player.serverLevel().getBlockState(pos).is(UndergroundCity.TERMINAL.get())) return false;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_TERMINAL_DISTANCE_SQUARED) return false;
        return CitizenState.get(player.server).find(player.getUUID()).isPresent();
    }

    private static void openVerifiedStarterShop(ServerPlayer player, BlockPos pos) {
        if (!requestSlot(player)) return;
        if (!authorizedForStarterShop(player, pos)) {
            player.sendSystemMessage(Component.literal(
                "Use a nearby Operations Terminal after registering citizenship."));
            return;
        }
        if (!ModList.get().isLoaded("qshop")
                || player.server.getCommands().getDispatcher().getRoot().getChild("qshop") == null) {
            player.sendSystemMessage(Component.literal(
                "Q Shop is not installed. The Imports screen is a preview."));
            return;
        }
        if (WorkOrderLedger.get(player.server).faulted()
                || WorkOrderLedger.get(player.server).player(player.getUUID()).contains("pending")) {
            player.sendSystemMessage(Component.literal("Finish work-order recovery before opening shops."));
            return;
        }
        // Fixed server-authored ID, never arbitrary strings from a client packet.
        // QShop owns currency, inventory checks, shop stock and actual trades.
        // Only common civilian items are configured in this starter shop.
        player.server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack(), "qshop open " + STARTER_SHOP_ID);
    }

    public static final class NoticePacket {
        final Notice.Kind kind; final String title, body;
        NoticePacket(Notice.Kind kind, String title, String body) { this.kind = kind; this.title = title; this.body = body; }
    }

    public static final class OpenLedger {
        final LedgerData data;
        OpenLedger(LedgerData data) { this.data = data; }
    }

    public static final class OpenTerminal {
        final BlockPos pos;
        final TerminalData data;
        OpenTerminal(BlockPos pos, TerminalData data) { this.pos = pos; this.data = data; }
    }

    public static final class OpenStarterShop {
        final BlockPos pos;
        OpenStarterShop(BlockPos pos) { this.pos = pos; }
    }

    private record WorkAction(BlockPos pos, int action, String id, UUID nonce) {}
    private record RegionalBuy(BlockPos pos, String commodity, int quantity, UUID nonce) {}
    private record SkillAction(BlockPos pos, String skill) {}
    private record OpenRegistration(java.util.Map<String, java.util.List<String>> kits) {}
    private record AskRegistration() {}
    private record RegisterChoice(String city, String career) {}
    private record RegistrationResult(boolean ok, String message) {}
    private record OpenChallenge(BlockPos pos, byte[] sequence) {}
    private record ChallengeAnswer(BlockPos pos, byte[] answer) {}
}
