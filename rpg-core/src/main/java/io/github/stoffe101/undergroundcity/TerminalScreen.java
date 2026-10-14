package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;

/** Native terminal backed by a read-only server snapshot and bounded requests. */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends Screen {
    public enum Page {
        CONTRACTS("CONTRACTS", "CAREER WORK ORDERS", List.of()),
        IMPORTS("IMPORTS", "CIVILIAN SUPPLY", List.of("Buy ordinary supplies or sell surplus through Q Shop.", "Specialist goods are sold only inside their own city.", "Remote imports and cargo incidents are not available yet.")),
        DELIVERIES("DELIVERIES", "SHIPMENT TRACKING", List.of("No shipments are in transit.", "Cargo ordering and physical recovery scenes are pending.", "Recovery timers will pause while their owner is offline.")),
        BLACK_MARKET("MARKET", "PRIVATE EXCHANGE", List.of("The black market is unavailable.", "Funded buyers and physical dropbox escrow are pending.", "Keep your equipment until secure exchanges are enabled.")),
        RAIDS("RAIDS", "TACTICAL CONTRACTS", List.of("No surveyed raid targets are available.", "Raids require real buildings, guards and objectives.", "Progress through civilian work orders while this is developed."));
        public final String tab, heading;
        public final List<String> copy;
        Page(String tab, String heading, List<String> copy) { this.tab=tab; this.heading=heading; this.copy=copy; }
    }

    private static final int MAX_OFFER_ROWS = 3, LOT = 4, ROW_TOP = 108, ROW_STEP = 26;
    private Page current = Page.CONTRACTS;
    private final BlockPos terminalPosition;
    private final TerminalData data;
    private int selected, panelW, panelH, left, top;
    private Button shop, accept, cancel, previous, next;
    private final Button[] tabs = new Button[Page.values().length];
    private final Button[] buyOne = new Button[MAX_OFFER_ROWS], buyLot = new Button[MAX_OFFER_ROWS];

    public TerminalScreen() { this(null, TerminalData.offline()); }
    public TerminalScreen(BlockPos position, TerminalData data) {
        super(Component.literal("Underground City | Operations"));
        this.terminalPosition=position; this.data=data;
    }

    @Override protected void init() {
        panelW=Math.min(420,width-16); panelH=Math.min(268,height-16);
        left=(width-panelW)/2; top=(height-panelH)/2;
        int step=(panelW-16)/5;
        for (int i=0;i<tabs.length;i++) {
            Page page=Page.values()[i];
            tabs[i]=addRenderableWidget(Button.builder(Component.literal(page.tab), b->showPage(page))
                .bounds(left+8+i*step,top+41,step-2,20).build());
        }
        shop=addRenderableWidget(Button.builder(Component.literal("OPEN CIVILIAN SHOP"), b->{
            if (terminalPosition!=null) { shop.active=false; TerminalNetwork.requestStarterShop(terminalPosition); }
        }).bounds(left+18,top+panelH-57,panelW-36,20).build());
        accept=addRenderableWidget(Button.builder(Component.literal("ACCEPT ORDER"),b->send(row().accepted()?1:0))
            .bounds(left+18,top+panelH-57,panelW-132,20).build());
        cancel=addRenderableWidget(Button.builder(Component.literal("CANCEL"),b->send(2))
            .bounds(left+panelW-106,top+panelH-57,88,20).build());
        previous=addRenderableWidget(Button.builder(Component.literal("<"),b->{selected--;refresh();})
            .bounds(left+panelW-70,top+77,24,20).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),b->{selected++;refresh();})
            .bounds(left+panelW-43,top+77,24,20).build());
        for (int i=0;i<MAX_OFFER_ROWS;i++) {
            int index=i, y=top+ROW_TOP-2+i*ROW_STEP;
            buyOne[i]=addRenderableWidget(Button.builder(Component.literal("BUY 1"),b->buy(index,1))
                .bounds(left+panelW-124,y,48,20).build());
            buyLot[i]=addRenderableWidget(Button.builder(Component.literal("BUY "+LOT),b->buy(index,LOT))
                .bounds(left+panelW-70,y,52,20).build());
        }
        refresh();
    }

    private TerminalData.Row row() { return data.jobs().isEmpty()?null:data.jobs().get(selected); }
    private boolean exchangeOpen() { return !data.region().isEmpty() && !data.offers().isEmpty(); }
    private void send(int action) {
        if (terminalPosition==null || row()==null) return;
        accept.active=false; cancel.active=false;
        TerminalNetwork.requestWork(terminalPosition,action,row().id(),data.active());
    }
    private void buy(int index,int quantity) {
        if (terminalPosition==null || index>=data.offers().size()) return;
        for (Button button:buyOne) button.active=false;
        for (Button button:buyLot) button.active=false;
        TerminalNetwork.requestRegionalBuy(terminalPosition,data.offers().get(index).id(),quantity);
    }
    public void showPage(Page page) { current=page; refresh(); }
    public Page currentPage() { return current; }
    TerminalData snapshot() { return data; }

    private static boolean ready(TerminalData.Row job) {
        return job.reqs().stream().allMatch(req->req.held()>=req.count());
    }
    private static int missing(TerminalData.Row job) {
        return job.reqs().stream().mapToInt(req->Math.max(0,req.count()-req.held())).sum();
    }
    private String lockReason(TerminalData.Row job) {
        if (!data.economy()) return "Q Shop 1.8.4 required";
        if (job.accepted()) return ready(job)?"":"Obtain the missing goods first";
        if (data.active()!=null) return "Finish or cancel your accepted order";
        if (data.level()<job.level()) return "Career level "+job.level()+" required";
        if (data.foreignVisits()<job.foreignVisits()) return visitText(job);
        if (job.available()==0) return "Employer demand replenishes during active play";
        return "";
    }
    private String visitText(TerminalData.Row job) {
        int more=job.foreignVisits()-data.foreignVisits();
        return "Use a terminal inside "+more+" more rival "+(more==1?"city":"cities")+" first";
    }

    private void refresh() {
        if (shop==null) return;
        for (int i=0;i<tabs.length;i++) tabs[i].active=current!=Page.values()[i];
        shop.visible=current==Page.IMPORTS;
        shop.active=terminalPosition!=null && data.economy();
        shop.setTooltip(Tooltip.create(Component.literal(data.economy()?"Buy and sell common goods using credits":"Q Shop 1.8.4 and citizenship required")));
        boolean exchange=current==Page.IMPORTS && exchangeOpen();
        for (int i=0;i<MAX_OFFER_ROWS;i++) {
            boolean present=exchange && i<data.offers().size();
            buyOne[i].visible=buyLot[i].visible=present;
            if (!present) continue;
            TerminalData.Offer offer=data.offers().get(i);
            int lot=Math.min(LOT,offer.maxOrder());
            buyLot[i].setMessage(Component.literal("BUY "+lot));
            applyBuy(buyOne[i],offer,1); applyBuy(buyLot[i],offer,lot);
        }
        boolean work=current==Page.CONTRACTS && row()!=null;
        accept.visible=cancel.visible=previous.visible=next.visible=work;
        if (!work) return;
        TerminalData.Row job=row();
        previous.active=selected>0; next.active=selected+1<data.jobs().size();
        accept.setMessage(Component.literal(job.accepted()?"DELIVER & COLLECT":"ACCEPT ORDER"));
        String reason=lockReason(job);
        accept.active=terminalPosition!=null && reason.isEmpty();
        accept.setTooltip(reason.isEmpty()?null:Tooltip.create(Component.literal(reason)));
        cancel.active=terminalPosition!=null && job.accepted();
    }

    private void applyBuy(Button button,TerminalData.Offer offer,int quantity) {
        String reason=!data.economy()?"Q Shop 1.8.4 required":offer.stock()<quantity?(offer.stock()==0?"Sold out; stock replenishes during active play":"Only "+offer.stock()+" left")
            :data.balance()<(double)offer.price()*quantity?"Needs "+(offer.price()*quantity)+" credits":"";
        button.active=terminalPosition!=null && reason.isEmpty();
        button.setTooltip(reason.isEmpty()?Tooltip.create(Component.literal(quantity+" x "+itemName(offer.item())+" for "+offer.price()*quantity+" credits")):Tooltip.create(Component.literal(reason)));
    }

    static Item item(String id) {
        ResourceLocation location=ResourceLocation.tryParse(id);
        Item value=location==null?null:ForgeRegistries.ITEMS.getValue(location);
        return value==null?Items.BARRIER:value;
    }
    static String itemName(String id) { return item(id).getDescription().getString(); }

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
        renderBackground(g);
        g.fill(left-2,top-2,left+panelW+2,top+panelH+2,0xff38546b);
        g.fill(left,top,left+panelW,top+panelH,0xff101c2a);
        g.fill(left,top,left+panelW,top+33,0xff182d42);
        g.fill(left+12,top+10,left+16,top+24,0xff55d3bc);
        g.drawString(font,"UNDERGROUND // OPERATIONS",left+24,top+10,0xffebf5fb,false);
        g.fill(left+9,top+70,left+panelW-9,top+panelH-66,0xff172738);
        boolean exchange=current==Page.IMPORTS && exchangeOpen();
        g.drawString(font,exchange?capitalize(data.region()).toUpperCase(java.util.Locale.ROOT)+" EXCHANGE":current.heading,left+18,top+81,0xff55d3bc,false);
        int tabStep=(panelW-16)/5;
        g.fill(left+8+current.ordinal()*tabStep,top+63,left+8+current.ordinal()*tabStep+tabStep-2,top+65,0xff55d3bc);
        if (current==Page.CONTRACTS) renderWork(g);
        else if (exchange) renderExchange(g);
        else {
            int y=top+108;
            for (String line:current.copy) { y=wrapped(g,line,left+18,y,panelW-36,0xffc0d0df)+12; }
            if (current==Page.IMPORTS) {
                wrapped(g,data.region().isEmpty()?"This terminal is outside every city exchange.":"No regional stock is listed for this city.",left+18,y,panelW-36,0xffe1af6c);
            }
        }
        String footer=capitalize(data.career())+" L"+data.level()+" | "+(data.economy()?String.format(java.util.Locale.ROOT,"%.0f credits",data.balance()):"Economy offline");
        g.drawString(font,footer,left+12,top+panelH-28,0xffe1af6c,false);
        g.drawString(font,"ESC TO CLOSE",left+panelW-88,top+panelH-15,0xff9bb6ca,false);
        g.drawString(font,capitalize(data.city())+(data.region().isEmpty()?"":"  |  at "+capitalize(data.region())),left+12,top+panelH-15,0xff9bb6ca,false);
        super.render(g,mouseX,mouseY,partialTick);
    }

    static String capitalize(String id) {
        return id.isEmpty()?"":Character.toUpperCase(id.charAt(0))+id.substring(1);
    }

    private void renderExchange(GuiGraphics g) {
        g.drawString(font,"Shared stock | replenishes during active play",left+18,top+93,0xff9bb6ca,false);
        for (int i=0;i<Math.min(MAX_OFFER_ROWS,data.offers().size());i++) {
            TerminalData.Offer offer=data.offers().get(i);
            int y=top+ROW_TOP+i*ROW_STEP;
            g.renderItem(new ItemStack(item(offer.item())),left+18,y);
            g.drawString(font,font.plainSubstrByWidth(itemName(offer.item()),panelW-210),left+40,y,0xffebf5fb,false);
            boolean soldOut=offer.stock()==0, poor=data.economy() && data.balance()<offer.price();
            String line=offer.price()+" Cr each  |  "+(soldOut?"SOLD OUT":offer.stock()+"/"+offer.capacity()+" in stock");
            if (!soldOut && poor) line+="  |  NEED "+(int)Math.ceil(offer.price()-data.balance())+" MORE";
            g.drawString(font,font.plainSubstrByWidth(line,panelW-170),left+40,y+10,soldOut?0xffe1736c:poor?0xffe1af6c:0xffc0d0df,false);
        }
        if (data.offers().size()>MAX_OFFER_ROWS) g.drawString(font,"+"+(data.offers().size()-MAX_OFFER_ROWS)+" more lines not shown",left+18,top+ROW_TOP+MAX_OFFER_ROWS*ROW_STEP,0xff9bb6ca,false);
    }

    /** Journey/QA hook: a real mouse click at the centre of an offer's buy button. */
    boolean clickBuy(int row,boolean lot) {
        Button button=(lot?buyLot:buyOne)[row];
        return mouseClicked(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0);
    }

    private void renderWork(GuiGraphics g) {
        TerminalData.Row job=row();
        if (job==null) {
            wrapped(g,"Register with /uc register <city> <career> to see paid profession orders.",left+18,top+113,panelW-36,0xffc0d0df);
            return;
        }
        int y=wrapped(g,job.title(),left+18,top+98,panelW-90,0xffebf5fb)+3;
        for (TerminalData.Req req:job.reqs()) {
            boolean enough=req.held()>=req.count();
            g.renderItem(new ItemStack(item(req.item())),left+18,y-3);
            g.drawString(font,font.plainSubstrByWidth(req.count()+" x "+itemName(req.item()),panelW-150),left+38,y+1,0xffc0d0df,false);
            String held="have "+req.held()+"/"+req.count();
            g.drawString(font,held,left+panelW-18-font.width(held),y+1,enough?0xff55d3bc:0xffe1af6c,false);
            y+=17;
        }
        y+=2;
        g.drawString(font,"+"+job.credits()+" credits  /  +"+job.xp()+" XP  /  Level "+job.level(),left+18,y,0xff55d3bc,false);
        y+=12;
        String state=job.accepted()?(ready(job)?"READY - deliver plain, undamaged goods":"MISSING "+missing(job)+" goods - produce before delivery")
            :data.active()!=null?"Finish your accepted order first"
            :data.level()<job.level()?"LOCKED - career level "+job.level()+" required"
            :data.foreignVisits()<job.foreignVisits()?"LOCKED - "+visitText(job)
            :"Employer demand: "+job.available()+" orders";
        wrapped(g,state,left+18,y,panelW-36,0xffe1af6c);
    }
    private int wrapped(GuiGraphics g,String text,int x,int y,int wrap,int color) {
        for (var line:font.split(Component.literal(text),wrap)) { g.drawString(font,line,x,y,color,false);y+=10; }
        return y;
    }
    @Override public boolean isPauseScreen() { return false; }
}
