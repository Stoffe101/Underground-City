package io.github.stoffe101.undergroundcity;

/**
 * Where the on-screen cards go, as pure arithmetic (no Minecraft types), so a test can prove at every window and GUI size that no card leaves the screen, that nothing is drawn where the game
 * or a common mod draws its own things by default, and that a small window drops the least important card instead of overlapping anything.
 *
 * <p>The corners and what lives in them (measured against the pinned pack, D-109): Xaero's minimap is at the top left by default and moves itself away from boss bars and status effects; the
 * game draws status effects at the top right (up to two rows of 24 pixels); boss bars and Jade's tooltip at the top centre (so no anchor stands there); chat at the bottom left; the hotbar, the health, hunger and thirst bars and the
 * held-item name at the bottom centre; TaCZ's ammunition readout at the bottom right. The default anchor is therefore the top right, below the status effects.
 */
final class HudLayout {
    private HudLayout() {}

    enum Anchor {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"), MIDDLE_LEFT("Left side"), MIDDLE_RIGHT("Right side"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");
        final String label;
        Anchor(String label) { this.label = label; }
        boolean right() { return this == TOP_RIGHT || this == MIDDLE_RIGHT || this == BOTTOM_RIGHT; }
        boolean top() { return this == TOP_LEFT || this == TOP_RIGHT; }
        boolean middle() { return this == MIDDLE_LEFT || this == MIDDLE_RIGHT; }
        boolean bottom() { return this == BOTTOM_LEFT || this == BOTTOM_RIGHT; }
        Anchor next() { return values()[(ordinal() + 1) % values().length]; }
        Anchor previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }

    static final int MARGIN = 6, GAP = 4, CARD_WIDTH = 176;
    static final float MIN_SCALE = 0.6f, MAX_SCALE = 1.5f;

    /** Pixels (GUI units) an anchor leaves free at the edge it sits on, for what the game or a common mod draws there. */
    static int reserved(Anchor anchor) {
        return switch (anchor) {
            case TOP_RIGHT -> 52;      // status effect icons: two rows of 24
            case BOTTOM_LEFT -> 110;   // chat
            case BOTTOM_RIGHT -> 40;   // ammunition and vehicle read-outs
            default -> 0;
        };
    }

    /** One card in a stack: how tall it is at scale 1 and how much it matters when there is not room for all (higher is kept longer). */
    record Card(int height, int priority) {}

    /** The result: the scale, and for each card whether it is shown and its top-left corner in GUI pixels. */
    record Plan(float scale, boolean[] shown, int[] x, int[] y, int width, int height) {
        boolean any() { for (boolean s : shown) if (s) return true; return false; }
    }

    /** Places a vertical stack of cards, shrinking it to fit and dropping the least important cards when even the smallest size would not. */
    static Plan plan(Anchor anchor, int screenW, int screenH, Card[] cards, float wantedScale) {
        int n = cards.length;
        boolean[] shown = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) { shown[i] = cards[i].height() > 0; if (shown[i]) count++; }
        float scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, wantedScale));
        int edge = reserved(anchor);
        int availableH = screenH - 2 * MARGIN - edge;
        int availableW = screenW - 2 * MARGIN;
        while (count > 0) {
            int total = stackHeight(cards, shown);
            // the largest scale (not above the wanted one) at which the stack fits, never below the minimum
            float fit = Math.min(scale, Math.min(availableH / (float) Math.max(1, total), availableW / (float) CARD_WIDTH));
            if (fit >= MIN_SCALE) { scale = fit; break; }
            // drop the least important shown card and try again
            int drop = -1;
            for (int i = 0; i < n; i++) if (shown[i] && (drop < 0 || cards[i].priority() < cards[drop].priority())) drop = i;
            shown[drop] = false;
            count--;
        }
        int[] x = new int[n], y = new int[n];
        if (count == 0) return new Plan(scale, shown, x, y, 0, 0);
        int w = Math.round(CARD_WIDTH * scale), h = Math.round(stackHeight(cards, shown) * scale);
        int left = anchor.right() ? screenW - MARGIN - w : MARGIN;
        int top = anchor.top() ? MARGIN + edge : anchor.middle() ? (screenH - h) / 2 : screenH - MARGIN - edge - h;
        int cursor = top;
        for (int i = 0; i < n; i++) {
            if (!shown[i]) continue;
            x[i] = left;
            y[i] = cursor;
            cursor += Math.round((cards[i].height() + GAP) * scale);
        }
        return new Plan(scale, shown, x, y, w, h);
    }

    private static int stackHeight(Card[] cards, boolean[] shown) {
        int total = 0, count = 0;
        for (int i = 0; i < cards.length; i++) if (shown[i]) { total += cards[i].height(); count++; }
        return total + Math.max(0, count - 1) * GAP;
    }
}
