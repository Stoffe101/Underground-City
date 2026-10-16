package io.github.stoffe101.undergroundcity;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/**
 * The on-screen tracker for the accepted work order (see {@link OrderTracker}): a small panel at the top left with the order's title, one line
 * per requirement showing what is carried against what is needed, the reward and where to deliver. It is drawn only while no screen is open
 * and can be hidden with a key (default O) when it is in the way. Client-side display only; the server owns the order and its progress.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientOrderTracker {
    private static final KeyMapping TOGGLE = new KeyMapping("key.underground_city.tracker", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.underground_city");
    private static volatile OrderTracker.Tracked current;
    private static boolean visible = true;

    private ClientOrderTracker() {}

    static void update(OrderTracker.Tracked tracked) { current = tracked; }
    static OrderTracker.Tracked current() { return current; }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) { event.register(TOGGLE); }

    @SubscribeEvent
    public static void overlay(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "order_tracker",
            (gui, graphics, partialTick, width, height) -> draw(graphics));
    }

    /** Toggling is polled on the client tick (the key is only a convenience, never part of any rule). */
    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
    public static final class Toggle {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (TOGGLE.consumeClick()) visible = !visible;
        }
    }

    static void draw(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        OrderTracker.Tracked tracked = current;
        if (tracked == null || !visible || mc.screen != null || mc.options.hideGui || mc.player == null) return;
        var font = mc.font;
        int x = 6, y = 6, padding = 4, lineHeight = font.lineHeight + 2;
        java.util.List<Component> rows = new java.util.ArrayList<>();
        java.util.List<Integer> colours = new java.util.ArrayList<>();
        rows.add(Component.literal(tracked.underground() ? "ACTIVE ORDER - UNDERGROUND" : "ACTIVE ORDER")); colours.add(tracked.underground() ? 0xFFC95D5D : 0xFFE6B450);
        rows.add(Component.literal(tracked.title())); colours.add(0xFFFFFFFF);
        boolean ready = true;
        for (OrderTracker.Line line : tracked.lines()) {
            boolean complete = line.held() >= line.count();
            ready &= complete;
            rows.add(Component.literal((complete ? "[x] " : "[ ] ") + Math.min(line.held(), line.count()) + "/" + line.count() + " " + name(line)));
            colours.add(complete ? 0xFF7FD17F : 0xFFD0D0D0);
        }
        rows.add(Component.literal(tracked.credits() + " credits, " + tracked.xp() + " xp")); colours.add(0xFF9BB0C8);
        rows.add(Component.literal(ready ? "Ready: deliver at " + (tracked.deliverIn().isEmpty() ? "an Operations Terminal" : tracked.deliverIn())
            : "Deliver at " + (tracked.deliverIn().isEmpty() ? "an Operations Terminal" : tracked.deliverIn()))); colours.add(ready ? 0xFF7FD17F : 0xFF9BB0C8);
        int width = 0;
        for (Component row : rows) width = Math.max(width, font.width(row));
        width = Math.min(width, 240);
        int height = rows.size() * lineHeight + padding;
        graphics.fill(x - padding, y - padding, x + width + padding, y + height, 0xB0101820);
        graphics.renderOutline(x - padding, y - padding, width + 2 * padding, height + padding, ready ? 0xFF7FD17F : 0xFF3A4A5E);
        for (int i = 0; i < rows.size(); i++) {
            graphics.drawString(font, font.plainSubstrByWidth(rows.get(i).getString(), width), x, y + i * lineHeight, colours.get(i), false);
        }
    }

    private static String name(OrderTracker.Line line) {
        if (!line.label().isEmpty()) return line.label();
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(line.item()));
        return item == null ? line.item() : item.getDescription().getString();
    }
}
