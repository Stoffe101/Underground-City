package io.github.stoffe101.undergroundcity;

/**
 * Where everything on one row of the Trade page goes, as pure arithmetic from the page's width (so a test can prove that nothing overlaps at every width the panel can take, and the
 * screen cannot drift from the test). The 3440x1440 capture of October 20 showed the amber "need N more" text drawn through the Buy buttons: the old row gave the name, the price,
 * the stock bar, the shortfall text and two buttons overlapping ranges of the same line. A row now has two lines and four dedicated places:
 *
 * <pre>
 *   [icon]  Name of the goods (cut with an ellipsis, the full name in the tooltip)          [Buy 1] [Buy 4]
 *           (credit) 12 each   |   stock bar 12/40      or one status text instead of the bar
 * </pre>
 * The status text ("Sold out", "Only 3 left", "Need 40 more") replaces the stock bar rather than sitting beside it, so the second line never holds more than its own budget.
 */
final class TradeLayout {
    static final int ROW_H = 30, ICON = 16, GAP = 4, EDGE = 8, BUY_ONE_W = 46, BUY_LOT_W = 46, BUTTON_H = 20, BAR_W = 34;

    /** A horizontal range inside the page, in GUI pixels from the page's left edge. */
    record Span(int x, int w) {
        int right() { return x + w; }
        boolean overlaps(Span other) { return x < other.right() && other.x < right(); }
    }

    final int width;
    final Span icon, name, price, stockOrStatus, buyOne, buyLot;

    private TradeLayout(int width) {
        this.width = width;
        int buttons = BUY_ONE_W + GAP + BUY_LOT_W;
        int left = EDGE + ICON + GAP;
        int buttonsLeft = width - EDGE - buttons;
        icon = new Span(EDGE, ICON);
        name = new Span(left, Math.max(0, buttonsLeft - 6 - left));
        buyOne = new Span(buttonsLeft, BUY_ONE_W);
        buyLot = new Span(buttonsLeft + BUY_ONE_W + GAP, BUY_LOT_W);
        // line two lives under the name: the price first (a credit mark and "1,250 each"), then what is left of the name's width for the stock
        int priceW = Math.min(name.w() / 2, 64);
        price = new Span(left, priceW);
        stockOrStatus = new Span(left + priceW + 6, Math.max(0, name.w() - priceW - 6));
    }

    static TradeLayout of(int pageWidth) { return new TradeLayout(pageWidth); }

    /** The smallest page width this layout is designed for (the narrowest panel at GUI scale 3 on a 1080p screen leaves a page about 350 wide). */
    static int minimumWidth() { return 280; }

    /** Every pair of places that must never overlap on one row. */
    boolean disjoint() {
        Span[] all = {icon, name, buyOne, buyLot};
        for (int i = 0; i < all.length; i++) for (int j = i + 1; j < all.length; j++) if (all[i].overlaps(all[j])) return false;
        return !price.overlaps(stockOrStatus) && price.right() <= name.right() && stockOrStatus.right() <= name.right();
    }
}
