package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Alarm panels and how a Technician gets past one (D-128). A furnished building whose security class has an alarm has a panel on the wall just inside its street door (an {@code alarm} spot of its
 * plan). When somebody comes in through a door and the building's alarm is armed ({@link PoliceResponse#armed}), the alarm does not ring at once: the panel starts an <b>entry delay</b> and beeps, and
 * when the delay runs out it rings and the police come, exactly as an alarm without a panel would. Inside the delay a Technician with Bypass Tooling and a Bypass Kit can work the panel: the kit is
 * spent, the server picks a hidden signal, the client shows it and sends back one answer ({@link ChallengeScreen}, kind ALARM). The right answer in time <b>jams</b> the panel: the entry delay ends, and for
 * ten active minutes the building's alarm calls nobody, whoever comes in; a wrong, early or late answer, or walking away, rings it at once and raises the thief's heat in the city by one. A panel can be
 * jammed before anybody comes in too, by somebody who is already inside. Anybody can look at a panel and see its light; only the server sets the light.
 *
 * <p>What it costs and who it is for: one Bypass Kit a try, the Technician's Bypass Tooling, and the signal is longer for a high-security building; Signal Analysis slows the signal down and Security
 * Systems gives longer to key it in. Deliberate limits: the entry delays, the traces and the jams are kept in memory only (a restart forgets them, and a jammed panel is armed again; nothing is ever
 * held against an absent player), an entry delay is dropped when its thief leaves the game, and a panel is only ever where a furnished building put one (a stock Lost Cities building has none, and its
 * alarm rings at once as before).
 */
public final class AlarmPanels {
    /** The entry delay, in seconds; how long a jam lasts, in active minutes; how far from the panel a trace may be answered from (squared). */
    static final int ENTRY_SECONDS = 25, JAM_MINUTES = 10;
    static final double REACH_SQUARED = 36.0;
    static final int BASE_LENGTH = 5, BASE_PER_SYMBOL = 12, BASE_INPUT_TICKS = 20 * 8, ANALYSIS_PER_SYMBOL = 4, SYSTEMS_INPUT_TICKS = 20 * 4;
    static final String SKILL = "technician.bypass_tooling", ANALYSIS = "technician.signal_analysis", SYSTEMS = "technician.security_systems", WIRE_WORK = "thief.wire_work";

    /** One entry delay: who came in, at which building, through which door, and the server tick it runs out. */
    record Entry(ServerPlayer player, UUID thief, ResourceKey<Level> dimension, long buildingKey, BlockPos door, BlockPos panel, long endsAt) {}
    /** One trace of a panel: the hidden signal and its window, in server ticks. */
    record Session(UUID player, BlockPos panel, long buildingKey, byte[] sequence, long showUntil, long deadline) {}
    /** How hard this panel is for this Technician: the signal's length, its pace and the time to key it back. */
    record Trace(int length, int perSymbol, int inputTicks) {}

    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** Building key to the active-clock tick its jam ends. */
    private static final Map<Long, Long> JAMMED = new HashMap<>();
    private static int attempts;
    // measurements
    static long entries, jams, failures, rung;

    // ------------------------------------------------------------------------------------------------ where a panel is

    /** The panel of a building: the block of its plan's first {@code alarm} spot, or empty when the building has none (a stock building, or a class without an alarm). */
    static Optional<BlockPos> panelOf(CityBuildings.Building b) {
        if (b == null || b.security() == null || b.security().response() == null) return Optional.empty();
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (plan == null) return Optional.empty();
        for (BuildingSpots.Spot spot : plan.spots()) {
            if (spot.kind().equals("alarm")) return Optional.of(new BlockPos(b.chunkX() * 16 + spot.x(), b.streetY() + spot.y(), b.chunkZ() * 16 + spot.z()));
        }
        return Optional.empty();
    }

    /** The panel of a building, only when the block is really there (an old world, a griefed wall or a building of another package has none). */
    static Optional<BlockPos> placedPanel(ServerLevel level, CityBuildings.Building b) {
        Optional<BlockPos> pos = panelOf(b);
        if (pos.isEmpty() || !level.isLoaded(pos.get()) || !(level.getBlockState(pos.get()).getBlock() instanceof AlarmPanelBlock)) return Optional.empty();
        return pos;
    }

    static boolean jammed(ServerLevel level, CityBuildings.Building b) {
        Long until = JAMMED.get(b.key());
        return until != null && until > LockService.clock(level.getServer());
    }

    /** What the panel's light should show now. */
    static AlarmPanelBlock.Light light(ServerLevel level, CityBuildings.Building b) {
        if (jammed(level, b)) return AlarmPanelBlock.Light.JAMMED;
        if (ENTRIES.containsKey(b.key())) return level.getServer().getTickCount() / 10 % 2 == 0 ? AlarmPanelBlock.Light.ENTRY : AlarmPanelBlock.Light.ARMED;
        CityBuildings.Response response = b.security() == null ? null : b.security().response();
        return response != null && PoliceResponse.armed(level, b, response) ? AlarmPanelBlock.Light.ARMED : AlarmPanelBlock.Light.OFF;
    }

    private static void show(ServerLevel level, CityBuildings.Building b) {
        placedPanel(level, b).ifPresent(pos -> {
            BlockState state = level.getBlockState(pos);
            AlarmPanelBlock.Light want = light(level, b);
            if (state.getValue(AlarmPanelBlock.LIGHT) != want) level.setBlock(pos, state.setValue(AlarmPanelBlock.LIGHT, want), Block.UPDATE_CLIENTS);
        });
    }

    // ------------------------------------------------------------------------------------------------ the entry delay

    /**
     * Called by the police response when somebody came in and the building's alarm would ring: starts (or keeps) the entry delay of the building's panel. False when the building has no panel in the
     * world, and the alarm rings at once as it always did.
     */
    static boolean startEntry(ServerPlayer thief, ServerLevel level, CityBuildings.Building b, BlockPos door) {
        Optional<BlockPos> panel = placedPanel(level, b);
        if (panel.isEmpty()) return false;
        long now = level.getServer().getTickCount();
        Entry existing = ENTRIES.get(b.key());
        if (existing == null || !existing.thief().equals(thief.getUUID())) {
            ENTRIES.put(b.key(), new Entry(thief, thief.getUUID(), level.dimension(), b.key(), door.immutable(), panel.get().immutable(), existing == null ? now + ENTRY_SECONDS * 20L : existing.endsAt()));
            entries++;
        }
        show(level, b);
        Notice.send(thief, Notice.Kind.WARN, "Alarm panel", "The panel by the door is counting down: " + ENTRY_SECONDS + " seconds to work it before it calls the police.");
        thief.sendSystemMessage(Component.literal("A panel beside the door starts to beep. The alarm is armed: " + ENTRY_SECONDS + " seconds until it calls the police.").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** The entry delay ran out, or a trace failed: the alarm rings and the response is the building's own, as with an alarm without a panel. */
    private static void ring(ServerPlayer thief, ServerLevel level, CityBuildings.Building b, BlockPos door) {
        ENTRIES.remove(b.key());
        rung++;
        PoliceResponse.raise(thief, level, b, door, true, false, false);
        show(level, b);
    }

    // ------------------------------------------------------------------------------------------------ working the panel

    static Trace trace(CitizenState.Citizen citizen, CityBuildings.Building b) {
        int length = BASE_LENGTH + ("high_security".equals(b.security().lockProfile()) ? 1 : 0);
        int perSymbol = BASE_PER_SYMBOL + (SkillService.has(citizen, ANALYSIS) ? ANALYSIS_PER_SYMBOL : 0);
        int input = BASE_INPUT_TICKS + (SkillService.has(citizen, SYSTEMS) ? SYSTEMS_INPUT_TICKS : 0);
        return new Trace(length, perSymbol, input);
    }

    /** The hidden signal: a pure function of the player, the panel and the attempt. */
    static byte[] sequence(UUID player, BlockPos panel, int attempt, int length) {
        byte[] out = new byte[length];
        long seed = LawMath.mix(player.getMostSignificantBits(), player.getLeastSignificantBits(), panel.asLong(), attempt * 31L + 0xA1A2L);
        for (int i = 0; i < length; i++) out[i] = (byte) (LawMath.mix(seed, i) >>> 33 & 3);
        return out;
    }

    private static void tell(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal(message)); }

    /** A right click on a panel. */
    static void use(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.at(level, pos);
        if (found.isEmpty() || panelOf(found.get()).filter(pos::equals).isEmpty()) { tell(player, "This panel is not connected to anything."); return; }
        CityBuildings.Building b = found.get();
        AlarmPanelBlock.Light light = light(level, b);
        if (light == AlarmPanelBlock.Light.OFF) { tell(player, "The panel's light is dark: this building's alarm is not wired."); return; }
        if (light == AlarmPanelBlock.Light.JAMMED) {
            long left = (JAMMED.get(b.key()) - LockService.clock(level.getServer())) / 1200 + 1;
            tell(player, "The panel is jammed for about " + left + " more minute" + (left == 1 ? "" : "s") + ". It calls nobody until then.");
            return;
        }
        if (player.isCreative() || player.isSpectator()) { tell(player, "The panel is armed (creative players are never policed)."); return; }
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) { tell(player, "Register as a citizen first."); return; }
        // a Technician's Bypass Tooling, or a thief's Wire Work whatever their trade (D-143)
        if (!SkillService.has(citizen, SKILL) && !SkillService.has(citizen, WIRE_WORK)) {
            tell(player, "A keypad with a red light: this building's alarm is armed. Getting past it takes a Bypass Kit and either a Technician's Bypass Tooling or a thief's Wire Work.");
            return;
        }
        if (SESSIONS.containsKey(player.getUUID()) || SecureTerminalService.hasSession(player.getUUID())) { tell(player, "You are already working a trace."); return; }
        if (SESSIONS.values().stream().anyMatch(s -> s.panel().equals(pos))) { tell(player, "Someone is already working this panel."); return; }
        Item kit = UndergroundCity.item("bypass_kit");
        Inventory inventory = player.getInventory();
        if (inventory.countItem(kit) < 1) { tell(player, "You need a Bypass Kit to work an alarm panel."); return; }
        for (ItemStack stack : inventory.items) {
            if (stack.is(kit)) { stack.shrink(1); break; }   // the kit is spent the moment the trace starts
        }
        Trace trace = trace(citizen, b);
        byte[] sequence = sequence(player.getUUID(), pos, ++attempts, trace.length());
        long now = player.server.getTickCount();
        long showUntil = now + (long) trace.length() * trace.perSymbol();
        SESSIONS.put(player.getUUID(), new Session(player.getUUID(), pos.immutable(), b.key(), sequence, showUntil, showUntil + trace.inputTicks()));
        if (!(player instanceof FakePlayer)) TerminalNetwork.openChallenge(player, pos, sequence, 1, trace.perSymbol(), trace.inputTicks());
        tell(player, "You open the panel's cover and clip the kit on. Watch the signal, then key it back.");
    }

    /** The client's single answer for a panel trace. Anything but the exact signal, inside the window and within reach of the panel, rings the alarm. */
    static void answer(ServerPlayer player, BlockPos pos, byte[] answer, long now) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.panel().equals(pos)) return;
        SESSIONS.remove(player.getUUID());
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > REACH_SQUARED) { fail(player, session, "You walked away from the panel."); return; }
        boolean inTime = now >= session.showUntil() && now <= session.deadline();
        if (inTime && Arrays.equals(session.sequence(), answer)) { jam(player, session); return; }
        fail(player, session, answer.length == 0 ? "You let go of the panel." : inTime ? "Wrong code." : now < session.showUntil() ? "You keyed in before the signal finished." : "Too slow.");
    }

    private static Optional<CityBuildings.Building> buildingOf(ServerPlayer player, Session session) {
        return CityBuildings.INSTANCE.at(player.serverLevel(), session.panel()).filter(b -> b.key() == session.buildingKey());
    }

    private static void jam(ServerPlayer player, Session session) {
        ServerLevel level = player.serverLevel();
        Optional<CityBuildings.Building> found = buildingOf(player, session);
        if (found.isEmpty()) return;
        CityBuildings.Building b = found.get();
        JAMMED.put(b.key(), LockService.clock(level.getServer()) + JAM_MINUTES * 1200L);
        ENTRIES.remove(b.key());
        jams++;
        show(level, b);
        tell(player, "The light goes blue. The panel is jammed for " + JAM_MINUTES + " minutes: it will not call anybody, whoever comes in.");
        Notice.send(player, Notice.Kind.LOOT, "Alarm jammed", "The panel will call nobody for " + JAM_MINUTES + " minutes.");
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "alarms_jammed");
        Thievery.bypassed(player, b.key(), "alarm panel");
    }

    private static void fail(ServerPlayer player, Session session, String why) {
        failures++;
        ServerLevel level = player.serverLevel();
        Optional<CityBuildings.Building> found = buildingOf(player, session);
        if (found.isEmpty()) return;
        CityBuildings.Building b = found.get();
        if (!b.city().isEmpty()) WorkOrderLedger.get(player.server).addHeat(player.getUUID(), b.city(), 1);
        tell(player, why + " The panel shrieks: the alarm is ringing.");
        Entry entry = ENTRIES.get(b.key());
        ring(player, level, b, entry != null ? entry.door() : session.panel());
    }

    // ------------------------------------------------------------------------------------------------ the clock

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 0) return;
        pass(event.getServer());
    }

    /** Twice a second: beeps and ends entry delays, expires traces, and every two seconds sets the lights of the panels round each player. Visible for the tests. */
    static void pass(MinecraftServer server) {
        long now = server.getTickCount();
        for (Iterator<Session> it = SESSIONS.values().iterator(); it.hasNext(); ) {
            Session session = it.next();
            if (now <= session.deadline()) continue;
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(session.player());
            if (player != null && !(player instanceof FakePlayer)) fail(player, session, "Too slow.");
        }
        for (Entry entry : List.copyOf(ENTRIES.values())) {
            ServerLevel level = server.getLevel(entry.dimension());
            ServerPlayer thief = entry.player().isRemoved() || entry.player().hasDisconnected() ? server.getPlayerList().getPlayer(entry.thief()) : entry.player();   // a respawned player is a new object
            Optional<CityBuildings.Building> found = level == null ? Optional.empty() : CityBuildings.INSTANCE.at(level, entry.panel()).filter(b -> b.key() == entry.buildingKey());
            if (thief == null || !thief.isAlive() || found.isEmpty()) {                        // nobody is policed while away: the delay is forgotten
                ENTRIES.remove(entry.buildingKey());
                found.ifPresent(b -> show(level, b));
                continue;
            }
            if (now >= entry.endsAt()) {
                if (SESSIONS.containsKey(entry.thief())) continue;                                 // a trace in progress decides it
                thief.sendSystemMessage(Component.literal("The panel's countdown runs out.").withStyle(ChatFormatting.RED));
                ring(thief, level, found.get(), entry.door());
                continue;
            }
            if (now % 20 == 0) level.playSound(null, entry.panel(), SoundEvents.NOTE_BLOCK_PLING.get(), SoundSource.BLOCKS, 1.0f, 1.8f);
            show(level, found.get());
        }
        JAMMED.values().removeIf(until -> until <= LockService.clock(server) - 1200L);
        if (now % 40 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            ServerLevel level = player.serverLevel();
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!CityBuildings.INSTANCE.known(cx + dx, cz + dz)) continue;               // never a first Lost Cities lookup from here
                CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).ifPresent(b -> show(level, b));
            }
        }
    }

    @SubscribeEvent
    public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        SESSIONS.remove(id);
        ENTRIES.values().removeIf(entry -> entry.thief().equals(id));
    }

    // ------------------------------------------------------------------------------------------------ seams

    static boolean hasSession(UUID player) { return SESSIONS.containsKey(player); }
    /** Sets one building's panel light now (the pass does it only round real players). */
    static void refresh(ServerLevel level, CityBuildings.Building b) { show(level, b); }
    static Session sessionOf(UUID player) { return SESSIONS.get(player); }
    static boolean inEntry(long buildingKey) { return ENTRIES.containsKey(buildingKey); }
    /** Test seam: brings an entry delay to its end now. */
    static void expireEntryForTest(long buildingKey, long now) { ENTRIES.computeIfPresent(buildingKey, (k, e) -> new Entry(e.player(), e.thief(), e.dimension(), e.buildingKey(), e.door(), e.panel(), now)); }
    static void resetForTest() { ENTRIES.clear(); SESSIONS.clear(); JAMMED.clear(); entries = jams = failures = rung = 0; }

    static String status() { return "entry delays " + ENTRIES.size() + ", traces " + SESSIONS.size() + ", jammed " + JAMMED.size() + "; started " + entries + ", jammed " + jams + ", failed " + failures + ", rung " + rung; }
}
