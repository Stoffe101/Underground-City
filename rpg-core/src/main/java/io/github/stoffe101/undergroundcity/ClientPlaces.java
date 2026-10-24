package io.github.stoffe101.undergroundcity;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The client's half of property intelligence (D-157). While the player looks at a door within reach for a moment, it asks the server what the place is and shows a small card under the
 * crosshair (what it is, its tier as a word and a numeral, whether they could try it); the card goes as soon as they look away, so a street full of doors draws nothing. The Inspect key cases the
 * door they look at; the Places key opens their book. A place chosen on the Places screen is tracked on the server ({@link Navigation}, D-161).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientPlaces {
    static final KeyMapping INSPECT = new KeyMapping("key.underground_city.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.underground_city");
    static final KeyMapping PLACES = new KeyMapping("key.underground_city.places", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.underground_city");

    /** How far the client asks about a door (the server answers within a little more), how long the crosshair must rest on it first, and how long an answer is kept. */
    static final double REACH = 6.0;
    static final int SETTLE_TICKS = 6, KEEP_TICKS = 100;

    private record Known(PropertyIntel.Peek peek, long at) {}
    private static final Map<Long, Known> ANSWERS = new HashMap<>();
    private static BlockPos looking;
    private static int restingTicks;
    private static long askedAt = -1000;
    private static BlockPos asked;

    private static volatile List<PropertyIntel.Row> rows = List.of();
    private static volatile int thiefLevel = 1;
    private static volatile boolean casing;
    private static volatile long bookVersion;
    /** The server's active clock when the book was sent (the ages on the Places screen are counted from it). */
    private static volatile long clock;

    /** How many glances this client has asked for (the scenario counts them while the player looks round a street). */
    private static int asks;
    static int asks() { return asks; }


    private ClientPlaces() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) { event.register(INSPECT); event.register(PLACES); }

    @SubscribeEvent
    public static void overlay(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "place_glance", (gui, graphics, partialTick, width, height) -> draw(graphics, width, height));
    }

    // ------------------------------------------------------------------------------------------------ answers from the server

    static void answer(BlockPos pos, PropertyIntel.Peek peek) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        ANSWERS.put(pos.asLong(), new Known(peek, now));
        if (peek != null) ANSWERS.put(peek.door().asLong(), new Known(peek, now));
        if (ANSWERS.size() > 256) ANSWERS.entrySet().removeIf(e -> now - e.getValue().at() > KEEP_TICKS);
    }

    static void book(List<PropertyIntel.Row> next, int level, boolean hasCasing, long serverClock) {
        rows = List.copyOf(next);
        clock = serverClock;
        thiefLevel = level;
        casing = hasCasing;
        bookVersion++;
    }

    static List<PropertyIntel.Row> rows() { return rows; }
    static int thiefLevel() { return thiefLevel; }
    static boolean casing() { return casing; }
    static long bookVersion() { return bookVersion; }
    static long clock() { return clock; }

    /** The glance the player is looking at now, if the server has answered for it (capture and scenario seam). */
    static PropertyIntel.Peek current() {
        if (looking == null) return null;
        Known known = ANSWERS.get(looking.asLong());
        if (known == null && Minecraft.getInstance().level != null) {
            var state = Minecraft.getInstance().level.getBlockState(looking);
            if (state.getBlock() instanceof DoorBlock) known = ANSWERS.get(BuildingGuard.lowerOf(looking, state).asLong());
        }
        return known == null ? null : known.peek();
    }

    // ------------------------------------------------------------------------------------------------ the clock

    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
    public static final class Ticks {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            ClientNavigation.clientTick(mc);   // the tracked destination on Xaero's map (D-161)
            if (mc.player == null || mc.level == null) { looking = null; return; }
            while (PLACES.consumeClick()) if (mc.screen == null) { mc.setScreen(new PlacesScreen()); PlacesNetwork.askBook(); }
            BlockPos door = doorInSight(mc);
            while (INSPECT.consumeClick()) {
                if (mc.screen != null) continue;
                if (door != null) PlacesNetwork.inspect(door);
                else mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Look at a building's street door to case it."), true);
            }
            if (door == null || !door.equals(looking)) { looking = door; restingTicks = 0; }
            else restingTicks++;
            long now = mc.level.getGameTime();
            if (looking != null && restingTicks == SETTLE_TICKS) {
                Known known = ANSWERS.get(looking.asLong());
                if ((known == null || now - known.at() > KEEP_TICKS) && !(looking.equals(asked) && now - askedAt < 20)) {
                    PlacesNetwork.askPeek(looking);
                    asks++;
                    asked = looking;
                    askedAt = now;
                }
            }
        }
    }

    /** The door under the crosshair within reach, or null. */
    static BlockPos doorInSight(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = block.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof DoorBlock)) return null;
        if (mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > REACH) return null;
        return pos.immutable();
    }

    // ------------------------------------------------------------------------------------------------ the card under the crosshair

    static final int[] TIER_COLOUR = {0xff5b7083, 0xff8aa2b5, 0xff86c45a, 0xff4fd1b8, 0xffe8b04a, 0xffe07b39, 0xffe2574c};

    private static void draw(GuiGraphics g, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || mc.screen != null || ClientBurglary.searching()) return;
        PropertyIntel.Peek p = current();
        if (p == null || restingTicks < SETTLE_TICKS) return;
        // the card is centred under the crosshair unless the status display stands beside it: then it ends just left of the display (1280x720 at GUI scale 3 put them over each other)
        int[] stack = ClientHud.drawn();
        drawCard(g, mc, p, width / 2, height / 2 + 14, stack == null ? Integer.MAX_VALUE : stack[1] <= height / 2 + 50 && stack[1] + stack[3] >= height / 2 + 14 ? stack[0] - 4 : Integer.MAX_VALUE);
    }

    /** The glance card centred on {@code centre}, its top at {@code top} (the capture harness draws it too). */
    static void drawCard(GuiGraphics g, Minecraft mc, PropertyIntel.Peek p, int centre, int top) { drawCard(g, mc, p, centre, top, Integer.MAX_VALUE); }

    /** The same, its right edge at most {@code right}. */
    static void drawCard(GuiGraphics g, Minecraft mc, PropertyIntel.Peek p, int centre, int top, int right) {
        var font = mc.font;
        String head = (p.label().isEmpty() ? "Building" : Casing.capitalise(p.label())) + (p.address().isEmpty() ? "" : "  ·  " + p.address());
        String line;
        int lineColour;
        int accent = TIER_COLOUR[Math.max(0, Math.min(6, p.tier()))];
        switch (p.status()) {
            case YOURS -> { line = "Your home"; lineColour = UcUi.OK; accent = UcUi.OK; }
            case PLAYER_HOME -> { line = "A player's home"; lineColour = UcUi.SOFT; accent = UcUi.DIM; }
            case TO_LET -> { line = "To let: see Home on a terminal"; lineColour = UcUi.SOFT; accent = UcUi.LEGAL; }
            case NOT_A_TARGET -> { line = "Public building"; lineColour = UcUi.DIM; accent = UcUi.FAINT; }
            default -> {
                line = Thievery.tierWord(p.tier()) + "  " + PropertyIntel.roman(p.tier()) + " / VI";
                lineColour = accent;
            }
        }
        String sub = "";
        int subColour = UcUi.DIM;
        if (p.status() == PropertyIntel.Status.TARGET || p.status() == PropertyIntel.Status.OPEN) {
            sub = p.status() == PropertyIntel.Status.OPEN ? "Open now" : PropertyIntel.accessWords(p.access());
            subColour = p.status() == PropertyIntel.Status.OPEN ? UcUi.LEGAL : p.access() == 0 ? UcUi.OK : p.access() == 3 ? UcUi.DIM : UcUi.AMBER;
            String extra = p.layer() >= 0 ? "cased" : p.beenIn() ? "been in" : "[" + INSPECT.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT) + "] case";
            sub = sub + "  ·  " + extra;
        }
        int w = Math.max(font.width(head), Math.max(font.width(line) + 50, font.width(sub))) + 14;
        w = Math.min(w, 220);
        int h = sub.isEmpty() ? 26 : 37;
        int x = Math.max(4, Math.min(centre - w / 2, right - w));
        g.fill(x - 1, top - 1, x + w + 1, top + h + 1, 0xd005080c);
        g.fillGradient(x, top, x + w, top + h, 0xe6182736, 0xe60f1823);
        g.fill(x, top, x + 2, top + h, accent);
        g.drawString(font, UcUi.fit(font, head, w - 10), x + 6, top + 4, UcUi.TEXT, false);
        if (p.status() == PropertyIntel.Status.TARGET || p.status() == PropertyIntel.Status.OPEN) {
            // six pips: filled to the tier, outlined beyond, so the tier reads without colour too
            for (int i = 0; i < 6; i++) {
                int px0 = x + 6 + i * 7;
                if (i < p.tier()) g.fill(px0, top + 16, px0 + 5, top + 21, accent);
                else { g.fill(px0, top + 16, px0 + 5, top + 21, 0xff4a5f73); g.fill(px0 + 1, top + 17, px0 + 4, top + 20, 0xff101a24); }
            }
            g.drawString(font, UcUi.fit(font, line, w - 56), x + 50, top + 15, lineColour, false);
        } else {
            g.drawString(font, UcUi.fit(font, line, w - 10), x + 6, top + 15, lineColour, false);
        }
        if (!sub.isEmpty()) g.drawString(font, UcUi.fit(font, sub, w - 10), x + 6, top + 26, subColour, false);
    }
}
