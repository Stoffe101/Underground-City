package io.github.stoffe101.undergroundcity;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/**
 * The on-screen guidance cards (client display only; the server owns the order, its progress and the journey): the accepted work order (title, one
 * line per requirement with what is carried against what is needed, the reward and where to deliver) and the next step of the guided first hours
 * with a direction arrow and a distance when it is a place. {@link ClientHud} stacks them under the status, where the player put it; they are drawn
 * only while no screen is open, and the key (default O) hides both when they are in the way.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientOrderTracker {
    private static final KeyMapping TOGGLE = new KeyMapping("key.underground_city.tracker", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.underground_city");
    private static volatile OrderTracker.Tracked current;
    private static volatile Journey.Objective objective;
    private static boolean visible = true;

    private ClientOrderTracker() {}

    static void update(OrderTracker.Tracked tracked) { current = tracked; }
    static void updateObjective(Journey.Objective next) { objective = next; }
    static OrderTracker.Tracked current() { return current; }
    static Journey.Objective objective() { return objective; }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) { event.register(TOGGLE); }

    /** Toggling is polled on the client tick (the key is only a convenience, never part of any rule). */
    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
    public static final class Toggle {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (TOGGLE.consumeClick()) visible = !visible;
        }
    }

    private static final int WIDTH = 176;

    /** True while the order and next-step cards are switched on (key O); the stack that draws them is {@link ClientHud}. */
    static boolean guidanceVisible() { return visible; }

    /** The height of the accepted-order card, at scale 1. */
    static int orderHeight(OrderTracker.Tracked tracked) { return 14 + 12 + tracked.lines().size() * 17 + 14 + 6; }

    /** The height of the next-step card, at scale 1. */
    static int objectiveHeight(Font font, Journey.Objective next) {
        int lines = Math.min(3, UcUi.wrapLines(font, next.hint(), WIDTH - 30).size());
        return 14 + 12 + lines * 10 + (next.target() != null ? 12 : 0) + 6;
    }

    private static void card(GuiGraphics g, int x, int y, int w, int h, int accent) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xff05080c);
        g.fillGradient(x, y, x + w, y + h, 0xe6182736, 0xe60f1823);
        g.fill(x, y, x + w, y + 2, accent);
        g.fill(x, y + 2, x + w, y + 3, UcUi.shade(accent, 0.45f));
    }

    static int drawOrder(GuiGraphics g, Font font, OrderTracker.Tracked tracked, int x, int y) {
        int accent = tracked.underground() ? UcUi.UNDERGROUND : UcUi.LEGAL;
        boolean ready = true;
        for (OrderTracker.Line line : tracked.lines()) ready &= line.held() >= line.count();
        int rows = tracked.lines().size();
        int h = 14 + 12 + rows * 17 + 14 + 6;
        card(g, x, y, WIDTH, h, ready ? UcUi.OK : accent);
        int cy = y + 6;
        UcUi.icon(g, tracked.underground() ? UcUi.Icon.UNDERGROUND : UcUi.Icon.LEGAL, x + 4, cy - 4, accent);
        UcUi.display(g, font, tracked.underground() ? "Underground order" : "Active order", x + 22, cy, accent);
        UcUi.displayRight(g, font, tracked.credits() + " cr", x + WIDTH - 5, cy, UcUi.AMBER);
        cy += 12;
        g.drawString(font, UcUi.fit(font, tracked.title(), WIDTH - 10), x + 5, cy, UcUi.TEXT, false);
        cy += 12;
        for (OrderTracker.Line line : tracked.lines()) {
            boolean complete = line.held() >= line.count();
            g.renderItem(new ItemStack(item(line.item())), x + 4, cy - 4);
            g.drawString(font, UcUi.fit(font, name(line), WIDTH - 62), x + 24, cy, complete ? UcUi.OK : UcUi.SOFT, false);
            String have = Math.min(line.held(), line.count()) + "/" + line.count();
            g.drawString(font, have, x + WIDTH - 6 - font.width(have), cy, complete ? UcUi.OK : UcUi.AMBER, false);
            UcUi.bar(g, x + 24, cy + 9, WIDTH - 30, 3, line.count() == 0 ? 1f : (float) line.held() / line.count(), complete ? UcUi.OK : UcUi.AMBER);
            cy += 17;
        }
        String where = tracked.deliverIn().isEmpty() ? "any Operations Terminal" : tracked.deliverIn();
        UcUi.icon(g, ready ? UcUi.Icon.CHECK : UcUi.Icon.PIN, x + 3, cy - 4, ready ? UcUi.OK : UcUi.DIM);
        g.drawString(font, UcUi.fit(font, (ready ? "Ready: deliver at " : "Deliver at ") + where, WIDTH - 24), x + 22, cy, ready ? UcUi.OK : UcUi.DIM, false);
        return y + h;
    }

    static void drawObjective(GuiGraphics g, Minecraft mc, Font font, Journey.Objective next, int x, int y) {
        int h = objectiveHeight(font, next);
        card(g, x, y, WIDTH, h, UcUi.AMBER);
        UcUi.chip(g, font, "Next", x + 4, y + 6, UcUi.AMBER, null);
        UcUi.display(g, font, UcUi.fit(font, next.title(), WIDTH - 50), x + 38, y + 9, UcUi.TEXT);
        int cy = UcUi.wrapped(g, font, next.hint(), x + 5, y + 24, WIDTH - 10, UcUi.DIM, 3);
        if (next.target() != null) {
            BlockPos t = next.target();
            double dx = t.getX() + .5 - mc.player.getX(), dz = t.getZ() + .5 - mc.player.getZ();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float relative = Mth.wrapDegrees(bearing - mc.player.getYRot());
            g.pose().pushPose();
            g.pose().translate(x + 13, cy + 5, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(relative - 90f));
            g.pose().translate(-8, -8, 0);
            UcUi.icon(g, UcUi.Icon.ARROW_RIGHT, 0, 0, UcUi.AMBER);
            g.pose().popPose();
            String where = (next.targetName().isEmpty() ? "Destination" : next.targetName()) + "  " + (distance < 4 ? "here" : distance + " m");
            g.drawString(font, UcUi.fit(font, where, WIDTH - 36), x + 26, cy + 1, UcUi.AMBER, false);
        }
    }

    private static net.minecraft.world.item.Item item(String id) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        return item == null ? net.minecraft.world.item.Items.BARRIER : item;
    }

    private static String name(OrderTracker.Line line) {
        if (!line.label().isEmpty()) return line.label();
        return item(line.item()).getDescription().getString();
    }
}
