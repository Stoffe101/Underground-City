package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * The Engineer's Toolkit's list of machinery service calls (eighth pass): every machine that has broken down, the city you are in first, with what it is, where, what it pays, how long it has
 * waited and what its drive will need. Track points you at the machine (Xaero's Minimap when installed, the status display's arrow always).
 */
@OnlyIn(Dist.CLIENT)
public final class ServiceCallsScreen extends UcScreen {
    private List<SmithWork.Call> calls;
    private final List<UcButton> track = new ArrayList<>();
    private static final int ROW = 34;

    ServiceCallsScreen(List<SmithWork.Call> calls) {
        super(Component.literal("Underground City | Service calls"));
        this.calls = calls;
    }

    void update(List<SmithWork.Call> next) { this.calls = next; init(); }

    /** Scenario hooks. */
    List<SmithWork.Call> calls() { return calls; }
    int[] trackCentre(int row) {
        if (row < 0 || row >= track.size() || !track.get(row).visible) return null;
        UcButton b = track.get(row);
        return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
    }

    @Override protected int accent() { return UcUi.careerColor("smith"); }
    @Override protected String titleText() { return "Machinery service calls"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.GEAR; }

    private int visibleRows() { return Math.max(1, (bodyBottom() - bodyY() - 16) / ROW); }

    @Override protected void init() {
        clearWidgets();
        track.clear();
        layoutPanel(380, 220, 620, 360);
        for (int i = 0; i < Math.min(calls.size(), visibleRows()); i++) {
            SmithWork.Call call = calls.get(i);
            int y = bodyY() + 16 + i * ROW;
            UcButton b = add(new UcButton(px + pw - PAD - 58, y + 8, 54, 18, "Track", btn -> NavigationNetwork.track("machine", call.id().toString())).kind(UcButton.Kind.PRIMARY).icon(UcUi.Icon.PIN)
                .tooltip(call.blocked().isEmpty() ? "Point me at the machine." : call.blocked()));
            b.active = call.blocked().isEmpty();
            track.add(b);
        }
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), w = bodyW();
        if (calls.isEmpty()) {
            UcUi.wrapped(g, font, "No machinery is down. Now and then a hoist, a saw, a pump or a press breaks down in a city somebody is in; this toolkit lists the calls, and the Smiths in that city hear of them.", x, bodyY() + 4, w, UcUi.SOFT, 4);
            return;
        }
        UcUi.text(g, font, UcUi.fit(font, calls.size() + (calls.size() == 1 ? " machine is down." : " machines are down.") + " Use the toolkit on a machine to strip its drive.", w), x, bodyY(), UcUi.DIM);
        for (int i = 0; i < Math.min(calls.size(), visibleRows()); i++) {
            SmithWork.Call c = calls.get(i);
            int y = bodyY() + 16 + i * ROW;
            g.fill(x, y, x + w, y + ROW - 3, UcUi.ROW);
            g.fill(x, y, x + 3, y + ROW - 3, UcUi.cityColor(c.city()));
            int textW = w - 72;
            String head = Casing.capitalise(c.machine()) + " · " + c.label() + " · tier " + PropertyIntel.roman(c.tier());
            UcUi.text(g, font, UcUi.fit(font, head, textW), x + 8, y + 3, UcUi.TEXT);
            String where = c.address() + ", " + Casing.capitalise(c.city()) + " · " + c.credits() + " credits, " + c.xp() + " XP · waiting " + c.minutesOpen() + " min";
            UcUi.text(g, font, UcUi.fit(font, where, textW), x + 8, y + 13, UcUi.SOFT);
            String needs = c.blocked().isEmpty() ? "Bring: " + c.needs() : c.blocked();
            UcUi.text(g, font, UcUi.fit(font, needs, textW), x + 8, y + 22, c.blocked().isEmpty() ? UcUi.DIM : UcUi.WARN);
        }
    }
}
