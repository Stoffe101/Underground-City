package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** First-pass responsive panel. All operational buttons intentionally disabled. */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends Screen {
    public enum Page {
        CONTRACTS("CONTRACTS", "CAREER & CITY JOBS",
            "Your contracts, profession work and civic assignments.",
            "Select a city to inspect future work orders.",
            "Authoritative quest provider integration is pending."),
        IMPORTS("IMPORTS", "REGIONAL SUPPLIERS",
            "Find components produced by Ironhaven, Valewood and Blackwater.",
            "Remote shipments cost more than direct foreign purchases.",
            "Starter civilian shop available with Q Shop installed."),
        DELIVERIES("DELIVERIES", "SHIPMENT TRACKING",
            "Watch incoming cargo and potential recovery events.",
            "Personal incident timers will pause while you are offline.",
            "No world-grounded crash scenes are available yet."),
        BLACK_MARKET("BLACK MARKET", "PRIVATE EXCHANGE",
            "List real owned equipment against a market-price guide.",
            "Funded buyers and one-time physical dropboxes are planned.",
            "Listing is locked until item escrow is implemented."),
        RAIDS("RAIDS", "TACTICAL CONTRACT BOARD",
            "Gang safehouses, guarded warehouses and high-tier targets.",
            "Contracts lead to real-world locations, not instant rewards.",
            "Raid launch is locked until real targets and NPCs exist.");

        public final String tab;
        public final String heading;
        public final List<String> copy;

        Page(String tab, String heading, String a, String b, String c) {
            this.tab = tab;
            this.heading = heading;
            this.copy = List.of(a, b, c);
        }
    }

    private static final int PANEL_W = 366;
    private static final int PANEL_H = 252;
    private Page current = Page.CONTRACTS;
    private final BlockPos terminalPosition;
    private Button starterMarket;
    private final Button[] tabs = new Button[Page.values().length];

    public TerminalScreen() {
        this(null);
    }

    public TerminalScreen(BlockPos terminalPosition) {
        super(Component.literal("Underground City | Operations"));
        this.terminalPosition = terminalPosition;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        int step = 70;
        for (int i = 0; i < Page.values().length; i++) {
            Page page = Page.values()[i];
            tabs[i] = addRenderableWidget(Button.builder(Component.literal(page.tab),
                button -> showPage(page)).bounds(left + 8 + i * step, top + 45, 68, 20).build());
        }
        starterMarket = addRenderableWidget(Button.builder(
            Component.literal("OPEN CIVILIAN SUPPLY SHOP"),
            button -> {
                if (terminalPosition != null) TerminalNetwork.requestStarterShop(terminalPosition);
            }
        ).bounds(left + 19, top + 193, PANEL_W - 38, 19).build());
        refreshTabs();
    }

    public void showPage(Page page) {
        current = page;
        refreshTabs();
    }

    public Page currentPage() {
        return current;
    }

    private void refreshTabs() {
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] != null) tabs[i].active = current != Page.values()[i];
        }
        if (starterMarket != null) {
            starterMarket.visible = current == Page.IMPORTS;
            starterMarket.active = terminalPosition != null && current == Page.IMPORTS;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        graphics.fill(left - 2, top - 2, left + PANEL_W + 2, top + PANEL_H + 2, 0xff38546b);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xff101c2a);
        graphics.fill(left, top, left + PANEL_W, top + 37, 0xff182d42);
        graphics.fill(left + 12, top + 11, left + 16, top + 25, 0xff55d3bc);
        graphics.drawString(font, "UNDERGROUND // NETWORK", left + 24, top + 10, 0xffebf5fb, false);
        graphics.drawString(font, "EARLY ACCESS", left + PANEL_W - 88, top + 12, 0xffe7b47c, false);
        graphics.fill(left + 9, top + 74, left + PANEL_W - 9, top + PANEL_H - 43, 0xff172738);
        graphics.drawString(font, current.heading, left + 19, top + 85, 0xff55d3bc, false);
        for (int i = 0; i < current.copy.size(); i++) {
            // Controlled wrapping keeps 1080p GUI scale 3 within the panel.
            String line = current.copy.get(i);
            List<net.minecraft.util.FormattedCharSequence> wrapped =
                font.split(Component.literal(line), PANEL_W - 36);
            int y = top + 109 + i * 27;
            for (int j = 0; j < Math.min(2, wrapped.size()); j++) {
                graphics.drawString(font, wrapped.get(j), left + 19, y + j * 10,
                    0xffc0d0df, false);
            }
        }
        graphics.drawString(font, current == Page.IMPORTS ? "QSHOP: CIVILIAN GOODS ONLY" : "OPERATIONS: PREVIEW ONLY", left + 12, top + PANEL_H - 30,
            0xffe1af6c, false);
        graphics.drawString(font, "ESC TO CLOSE", left + PANEL_W - 88, top + PANEL_H - 30,
            0xff9bb6ca, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
