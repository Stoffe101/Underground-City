package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;

/** Native terminal backed by a read-only server snapshot and bounded requests. */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends Screen {
    public enum Page {
        CONTRACTS("CONTRACTS", "CAREER WORK ORDERS", List.of()),
        IMPORTS("IMPORTS", "CIVILIAN SUPPLY", List.of("Buy ordinary supplies or sell surplus through Q Shop.", "Specialist regional suppliers still need world locations.", "Remote imports and cargo incidents are not available yet.")),
        DELIVERIES("DELIVERIES", "SHIPMENT TRACKING", List.of("No shipments are in transit.", "Cargo ordering and physical recovery scenes are pending.", "Recovery timers will pause while their owner is offline.")),
        BLACK_MARKET("MARKET", "PRIVATE EXCHANGE", List.of("The black market is unavailable.", "Funded buyers and physical dropbox escrow are pending.", "Keep your equipment until secure exchanges are enabled.")),
        RAIDS("RAIDS", "TACTICAL CONTRACTS", List.of("No surveyed raid targets are available.", "Raids require real buildings, guards and objectives.", "Progress through civilian work orders while this is developed."));
        public final String tab, heading;
        public final List<String> copy;
        Page(String tab, String heading, List<String> copy) { this.tab=tab; this.heading=heading; this.copy=copy; }
    }

    private Page current = Page.CONTRACTS;
    private final BlockPos terminalPosition;
    private final TerminalData data;
    private int selected, panelW, panelH, left, top;
    private Button shop, accept, cancel, previous, next;
    private final Button[] tabs = new Button[Page.values().length];

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
        refresh();
    }

    private TerminalData.Row row() { return data.jobs().isEmpty()?null:data.jobs().get(selected); }
    private void send(int action) {
        if (terminalPosition==null || row()==null) return;
        accept.active=false; cancel.active=false;
        TerminalNetwork.requestWork(terminalPosition,action,row().id(),data.active());
    }
    public void showPage(Page page) { current=page; refresh(); }
    public Page currentPage() { return current; }
    TerminalData snapshot() { return data; }
    private void refresh() {
        if (shop==null) return;
        for (int i=0;i<tabs.length;i++) tabs[i].active=current!=Page.values()[i];
        shop.visible=current==Page.IMPORTS;
        shop.active=terminalPosition!=null && data.economy();
        shop.setTooltip(Tooltip.create(Component.literal(data.economy()?"Buy and sell common goods using credits":"Q Shop 1.8.4 and citizenship required")));
        boolean work=current==Page.CONTRACTS && row()!=null;
        accept.visible=cancel.visible=previous.visible=next.visible=work;
        if (!work) return;
        TerminalData.Row job=row();
        previous.active=selected>0; next.active=selected+1<data.jobs().size();
        accept.setMessage(Component.literal(job.accepted()?"DELIVER & COLLECT":"ACCEPT ORDER"));
        String reason=!data.economy()?"Q Shop 1.8.4 required":job.accepted() && job.held()<job.count()?"Obtain the missing goods first":
            !job.accepted() && data.active()!=null?"Finish or cancel your accepted order":
            data.level()<job.level()?"Career level "+job.level()+" required":job.available()==0 && !job.accepted()?"Employer demand replenishes during active play":"";
        accept.active=terminalPosition!=null && reason.isEmpty();
        accept.setTooltip(reason.isEmpty()?null:Tooltip.create(Component.literal(reason)));
        cancel.active=terminalPosition!=null && job.accepted();
    }

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
        renderBackground(g);
        g.fill(left-2,top-2,left+panelW+2,top+panelH+2,0xff38546b);
        g.fill(left,top,left+panelW,top+panelH,0xff101c2a);
        g.fill(left,top,left+panelW,top+33,0xff182d42);
        g.fill(left+12,top+10,left+16,top+24,0xff55d3bc);
        g.drawString(font,"UNDERGROUND // OPERATIONS",left+24,top+10,0xffebf5fb,false);
        g.fill(left+9,top+70,left+panelW-9,top+panelH-66,0xff172738);
        g.drawString(font,current.heading,left+18,top+81,0xff55d3bc,false);
        int tabStep=(panelW-16)/5;
        g.fill(left+8+current.ordinal()*tabStep,top+63,left+8+current.ordinal()*tabStep+tabStep-2,top+65,0xff55d3bc);
        if (current==Page.CONTRACTS) renderWork(g);
        else {
            int y=top+108;
            for (String line:current.copy) { y=wrapped(g,line,left+18,y,panelW-36,0xffc0d0df)+12; }
        }
        String footer=data.career()+" L"+data.level()+" | "+(data.economy()?String.format(java.util.Locale.ROOT,"%.0f credits",data.balance()):"Economy offline");
        g.drawString(font,footer,left+12,top+panelH-28,0xffe1af6c,false);
        g.drawString(font,"ESC TO CLOSE",left+panelW-88,top+panelH-15,0xff9bb6ca,false);
        g.drawString(font,data.city(),left+12,top+panelH-15,0xff9bb6ca,false);
        super.render(g,mouseX,mouseY,partialTick);
    }
    private void renderWork(GuiGraphics g) {
        TerminalData.Row job=row();
        if (job==null) {
            wrapped(g,"Register with /uc register <city> <career> to see paid profession orders.",left+18,top+113,panelW-36,0xffc0d0df);
            return;
        }
        int y=wrapped(g,job.title(),left+18,top+109,panelW-36,0xffebf5fb)+8;
        Component item=ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(job.item())).getDescription();
        y=wrapped(g,"Deliver "+job.count()+" "+item.getString()+"  |  In inventory: "+job.held(),left+18,y,panelW-36,0xffc0d0df)+8;
        g.drawString(font,"+"+job.credits()+" credits  /  +"+job.xp()+" XP  /  Level "+job.level(),left+18,y,0xff55d3bc,false);
        y+=16;
        String state=job.accepted()?(job.held()<job.count()?"MISSING "+(job.count()-job.held())+" goods - produce before delivery":"READY - deliver plain, undamaged goods"):data.active()!=null?"Finish your accepted order first":data.level()<job.level()?"LOCKED - career level "+job.level()+" required":"Employer demand: "+job.available()+" orders";
        wrapped(g,state,left+18,y,panelW-36,0xffe1af6c);
    }
    private int wrapped(GuiGraphics g,String text,int x,int y,int wrap,int color) {
        for (var line:font.split(Component.literal(text),wrap)) { g.drawString(font,line,x,y,color,false);y+=10; }
        return y;
    }
    @Override public boolean isPauseScreen() { return false; }
}
