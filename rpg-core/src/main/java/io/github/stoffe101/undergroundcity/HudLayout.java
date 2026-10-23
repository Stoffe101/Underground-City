package io.github.stoffe101.undergroundcity;

/**
 * Where the on-screen cards go, as pure arithmetic (no Minecraft types), so a test can prove at every window and GUI size that no card leaves the screen, that nothing is drawn where the game
 * or a common mod draws its own things by default, and that a small window drops the least important card instead of overlapping anything.
 *
 * <p>The corners and what lives in them (measured against the pinned pack, D-109): Xaero's minimap is at the top left by default and moves itself away from boss bars and status effects; the
 * game draws status effects at the top right (up to two rows of 24 pixels); boss bars and Jade's tooltip at the top centre (so no anchor stands there); chat at the bottom left; the hotbar, the health, hunger and thirst bars and the
 * held-item name at the bottom centre; TaCZ's ammunition readout at the bottom right. The default anchor is therefore the top right, below the status effects. Wherever the stack sits, on a
 * window narrow enough for it to reach over the hotbar's column it stops above the hotbar and the bars ({@link #HOTBAR_RESERVE}).
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
    /**
     * The hotbar and what the game stacks above it at the bottom centre (the experience bar, the health, armour, hunger, thirst and air rows, the held item's name): 182 pixels wide and up to
     * 60 high. A stack whose cards reach over that column keeps its bottom above it. A real client at 1280x720, GUI scale 3, showed the next-step card drawn over the hunger bar and the
     * hotbar's right-hand squares.
     */
    static final int HOTBAR_RESERVE = 60, HOTBAR_HALF_WIDTH = 100;

    /** True when a stack of width {@code w} at this anchor reaches over the hotbar's column. */
    static boolean overHotbar(Anchor anchor, int screenW, int w) {
        int left = anchor.right() ? screenW - MARGIN - w : MARGIN;
        return left + w > screenW / 2 - HOTBAR_HALF_WIDTH && left < screenW / 2 + HOTBAR_HALF_WIDTH;
    }

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
        int topLimit = MARGIN + (anchor.top() ? edge : 0), bottomEdge = anchor.bottom() ? edge : 0;
        int availableW = screenW - 2 * MARGIN;
        while (count > 0) {
            int total = stackHeight(cards, shown);
            // the largest scale (not above the wanted one) at which the stack fits, never below the minimum; over the hotbar's column the stack stops above it
            float fit = Math.min(scale, Math.min((screenH - topLimit - MARGIN - bottomEdge) / (float) Math.max(1, total), availableW / (float) CARD_WIDTH));
            if (overHotbar(anchor, screenW, Math.round(CARD_WIDTH * fit))) {
                fit = Math.min(fit, (screenH - topLimit - MARGIN - Math.max(bottomEdge, HOTBAR_RESERVE)) / (float) Math.max(1, total));
            }
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
        int bottomLimit = screenH - MARGIN - (overHotbar(anchor, screenW, w) ? Math.max(bottomEdge, HOTBAR_RESERVE) : bottomEdge);
        int top = anchor.top() ? topLimit : anchor.middle() ? topLimit + Math.max(0, (bottomLimit - topLimit - h) / 2) : bottomLimit - h;
        int cursor = top;
        for (int i = 0; i < n; i++) {
            if (!shown[i]) continue;
            x[i] = left;
            y[i] = cursor;
            cursor += Math.round((cards[i].height() + GAP) * scale);
        }
        return new Plan(scale, shown, x, y, w, h);
    }

    /**
     * The game's corner notices (toasts: ours, a recipe unlocked, an advancement) slide in at the top right, {@link #TOAST_WIDTH} wide, one under another; three of ours take about
     * {@link #TOAST_COLUMN} pixels. Where the stack stands in that column they are moved (a journey screenshot at 1280x720 showed two of them over the status card): below the stack when the
     * space under it holds the column, else beside it on its left, at the top; on a window too small for either they stay where the game puts them. Returns the shift {dx, dy} of the whole
     * column; the stack's rectangle is {@code sx, sy, sw, sh} (zero width when no stack is drawn).
     */
    static final int TOAST_WIDTH = 190, TOAST_COLUMN = 96;

    static int[] toastShift(int screenW, int screenH, int sx, int sy, int sw, int sh) {
        int tx = screenW - TOAST_WIDTH;
        if (sw <= 0 || sh <= 0 || sx + sw <= tx || sy >= TOAST_COLUMN) return new int[] {0, 0};
        int below = sy + sh + GAP;
        boolean overHotbar = tx + TOAST_WIDTH > screenW / 2 - HOTBAR_HALF_WIDTH && tx < screenW / 2 + HOTBAR_HALF_WIDTH;
        int bottomLimit = screenH - MARGIN - (overHotbar ? HOTBAR_RESERVE : reserved(Anchor.BOTTOM_RIGHT));
        if (below + TOAST_COLUMN <= bottomLimit) return new int[] {0, below};
        int dx = sx - GAP - screenW;   // the column's right edge a gap left of the stack
        if (tx + dx >= MARGIN) return new int[] {dx, 0};
        return new int[] {0, 0};
    }

    private static int stackHeight(Card[] cards, boolean[] shown) {
        int total = 0, count = 0;
        for (int i = 0; i < cards.length; i++) if (shown[i]) { total += cards[i].height(); count++; }
        return total + Math.max(0, count - 1) * GAP;
    }
}
