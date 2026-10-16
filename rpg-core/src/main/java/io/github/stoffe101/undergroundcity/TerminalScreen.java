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

/**
 * Native terminal backed by a read-only server snapshot and bounded requests.
 * Colour language: teal is legal/unlocked, amber is available/needs attention, red is underground/prohibited.
 * Legal and underground work sit side by side on every profession (D-049); nothing here locks a character
 * into one of them.
 */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends Screen {
    public enum Page {
        CONTRACTS("CONTRACTS", "CAREER WORK ORDERS", List.of()),
        SKILLS("SKILLS", "CAREER SKILLS", List.of()),
        IMPORTS("IMPORTS", "CIVILIAN SUPPLY", List.of("Buy ordinary supplies or sell surplus through Q Shop.", "Specialist goods are sold only inside their own city.", "Remote imports and cargo incidents are not available yet.")),
        DELIVERIES("DELIVERY", "SHIPMENT TRACKING", List.of("No shipments are in transit.", "Cargo ordering and physical recovery scenes are pending.", "Recovery timers will pause while their owner is offline.")),
        BLACK_MARKET("MARKET", "LAW AND CONTACTS", List.of("Use a terminal inside a city to see its law and buyers.", "Possession is never detected by itself.", "Searches happen at road checkpoints; hand-offs can be seen.")),
        RAIDS("RAIDS", "TACTICAL CONTRACTS", List.of("No surveyed raid targets are available.", "Raids require real buildings, guards and objectives.", "Progress through civilian work orders while this is developed."));
        public final String tab, heading;
        public final List<String> copy;
        Page(String tab, String heading, List<String> copy) { this.tab=tab; this.heading=heading; this.copy=copy; }
    }

    /** What survives a server refresh of an open terminal. */
    public record Carry(Page page, boolean underground, String jobId, String skillId) {}

    private static final int MAX_OFFER_ROWS = 3, LOT = 4, ROW_TOP = 108, ROW_STEP = 26, MAX_NODES = 16, NODE_TOP = 97, NODE_STEP = 15;
    private static final int TEAL = 0xff55d3bc, AMBER = 0xffe1af6c, RED = 0xffe1736c, GREY = 0xff5b6f82;
    private static final int TEXT = 0xffebf5fb, SOFT = 0xffc0d0df, DIM = 0xff9bb6ca;
    private Page current = Page.CONTRACTS;
    private boolean undergroundView;
    private final BlockPos terminalPosition;
    private final TerminalData data;
    private int selected, selectedSkill, panelW, panelH, left, top;
    private Button shop, accept, cancel, previous, next, unlock, legalFilter, undergroundFilter;
    private final Button[] tabs = new Button[Page.values().length];
    private final Button[] buyOne = new Button[MAX_OFFER_ROWS], buyLot = new Button[MAX_OFFER_ROWS];
    private final Button[] nodes = new Button[MAX_NODES];

    public TerminalScreen() { this(null, TerminalData.offline(), null); }
    public TerminalScreen(BlockPos position, TerminalData data) { this(position, data, null); }
    public TerminalScreen(BlockPos position, TerminalData data, Carry carry) {
        super(Component.literal("Underground City | Operations"));
        this.terminalPosition=position; this.data=data;
        TerminalData.Row accepted=data.jobs().stream().filter(TerminalData.Row::accepted).findFirst().orElse(null);
        if (carry!=null) { current=carry.page(); undergroundView=carry.underground(); }
        if (accepted!=null) undergroundView=accepted.extra().underground();
        List<TerminalData.Row> list=jobs();
        selected=0;
        if (accepted!=null) selected=Math.max(0,indexOf(list,accepted.id()));
        else if (carry!=null && carry.jobId()!=null) selected=Math.max(0,indexOf(list,carry.jobId()));
        if (carry!=null && carry.skillId()!=null) {
            for (int i=0;i<data.skills().size();i++) if (data.skills().get(i).id().equals(carry.skillId())) selectedSkill=i;
        }
    }
    private static int indexOf(List<TerminalData.Row> list,String id) {
        for (int i=0;i<list.size();i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    BlockPos position() { return terminalPosition; }
    Carry carry() {
        String job=row()==null?null:row().id();
        String skill=data.skills().isEmpty()?null:data.skills().get(Math.min(selectedSkill,data.skills().size()-1)).id();
        return new Carry(current,undergroundView,job,skill);
    }

    @Override protected void init() {
        panelW=Math.min(440,width-16); panelH=Math.min(268,height-16);
        left=(width-panelW)/2; top=(height-panelH)/2;
        int step=(panelW-16)/tabs.length;
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
        legalFilter=addRenderableWidget(Button.builder(Component.literal("LEGAL"),b->setView(false))
            .bounds(left+18,top+75,68,16).build());
        undergroundFilter=addRenderableWidget(Button.builder(Component.literal("UNDERGROUND"),b->setView(true))
            .bounds(left+90,top+75,100,16).build());
        unlock=addRenderableWidget(Button.builder(Component.literal("UNLOCK"),b->{
            if (terminalPosition!=null && selectedSkill<data.skills().size()) { unlock.active=false; TerminalNetwork.requestSkill(terminalPosition,data.skills().get(selectedSkill).id()); }
        }).bounds(left+18,top+panelH-57,panelW-36,20).build());
        for (int i=0;i<MAX_NODES;i++) {
            int index=i;
            nodes[i]=addRenderableWidget(Button.builder(Component.literal(""),b->{selectedSkill=index;refresh();})
                .bounds(left+18,top+98,100,14).build());
        }
        for (int i=0;i<MAX_OFFER_ROWS;i++) {
            int index=i, y=top+ROW_TOP-2+i*ROW_STEP;
            buyOne[i]=addRenderableWidget(Button.builder(Component.literal("BUY 1"),b->buy(index,1))
                .bounds(left+panelW-124,y,48,20).build());
            buyLot[i]=addRenderableWidget(Button.builder(Component.literal("BUY "+LOT),b->buy(index,LOT))
                .bounds(left+panelW-70,y,52,20).build());
        }
        refresh();
    }

    private List<TerminalData.Row> jobs() { return data.jobs().stream().filter(job->job.extra().underground()==undergroundView).toList(); }
    private TerminalData.Row row() { List<TerminalData.Row> list=jobs(); return list.isEmpty()?null:list.get(Math.max(0,Math.min(selected,list.size()-1))); }
    private boolean exchangeOpen() { return !data.region().isEmpty() && !data.offers().isEmpty(); }
    private void setView(boolean underground) { undergroundView=underground; selected=0; refresh(); }
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
    /** QA hook: choose the legal or the underground contract list. */
    public void showView(boolean underground) { setView(underground); }
    /** QA hook: pick the n-th contract of the current list. */
    public void selectJob(int index) { selected=index; refresh(); }
    /** QA hook: pick a skill node by id. */
    public void selectSkill(String id) {
        for (int i=0;i<data.skills().size();i++) if (data.skills().get(i).id().equals(id)) selectedSkill=i;
        refresh();
    }
    TerminalData snapshot() { return data; }

    private static boolean ready(TerminalData.Row job) {
        return job.reqs().stream().allMatch(req->req.held()>=req.count());
    }
    private static int missing(TerminalData.Row job) {
        return job.reqs().stream().mapToInt(req->Math.max(0,req.count()-req.held())).sum();
    }
    private String lockReason(TerminalData.Row job) {
        TerminalData.Extra extra=job.extra();
        if (!data.economy()) return "Q Shop 1.8.4 required";
        if (job.accepted()) {
            if (!ready(job)) return "Obtain the missing goods first";
            if (!extra.buyerCity().isEmpty() && !extra.buyerCity().equals(data.region())) return "Deliver to "+extra.buyer()+": use a terminal inside "+capitalize(extra.buyerCity());
            return "";
        }
        if (data.active()!=null) return "Finish or cancel your accepted order";
        if (!extra.skillOk()) return "Requires the skill "+extra.skill();
        if (data.level()<job.level()) return "Career level "+job.level()+" required";
        if (extra.minTrust()>extra.trust()) return extra.buyer()+" trusts you "+extra.trust()+"/"+extra.minTrust();
        if (data.foreignVisits()<job.foreignVisits()) return visitText(job);
        if (job.available()==0) return "Employer demand replenishes during active play";
        return "";
    }
    private String visitText(TerminalData.Row job) {
        int more=job.foreignVisits()-data.foreignVisits();
        return "Use a terminal inside "+more+" more rival "+(more==1?"city":"cities")+" first";
    }

    private long count(boolean underground) { return data.jobs().stream().filter(job->job.extra().underground()==underground).count(); }

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
        boolean contracts=current==Page.CONTRACTS;
        legalFilter.visible=undergroundFilter.visible=contracts;
        legalFilter.setMessage(Component.literal("LEGAL "+count(false)));
        undergroundFilter.setMessage(Component.literal("UNDERGROUND "+count(true)));
        legalFilter.active=undergroundView; undergroundFilter.active=!undergroundView;
        undergroundFilter.setTooltip(Tooltip.create(Component.literal("Higher pay for restricted goods. Needs an underground skill, and carrying them through checkpoints is a risk.")));
        legalFilter.setTooltip(Tooltip.create(Component.literal("Dependable, licensed work. Always available, never a crime.")));
        boolean work=contracts && row()!=null;
        accept.visible=cancel.visible=previous.visible=next.visible=work;
        if (work) {
            TerminalData.Row job=row();
            List<TerminalData.Row> list=jobs();
            previous.active=selected>0; next.active=selected+1<list.size();
            accept.setMessage(Component.literal(job.accepted()?"DELIVER & COLLECT":"ACCEPT ORDER"));
            String reason=lockReason(job);
            accept.active=terminalPosition!=null && reason.isEmpty();
            accept.setTooltip(reason.isEmpty()?null:Tooltip.create(Component.literal(reason)));
            cancel.active=terminalPosition!=null && job.accepted();
        }
        refreshSkills();
    }

    /** The full title when it fits the button, else the data's short title, else an ellipsis (never a silent cut). */
    private String fit(TerminalData.SkillRow row,int width) {
        if (font.width(row.title())<=width) return row.title();
        if (font.width(row.shortTitle())<=width) return row.shortTitle();
        return font.plainSubstrByWidth(row.shortTitle(),width-font.width("..."))+"...";
    }

    private void refreshSkills() {
        boolean page=current==Page.SKILLS;
        unlock.visible=page && !data.skills().isEmpty();
        int colW=(panelW-36-12)/3;
        int[] used=new int[3];
        for (int i=0;i<MAX_NODES;i++) {
            boolean present=page && i<data.skills().size();
            nodes[i].visible=present;
            if (!present) continue;
            TerminalData.SkillRow row=data.skills().get(i);
            int column=row.branch().equals("legal")?0:row.branch().equals("underground")?2:1;
            nodes[i].setX(left+18+column*(colW+6)); nodes[i].setY(top+NODE_TOP+used[column]++*NODE_STEP); nodes[i].setWidth(colW);
            nodes[i].setMessage(Component.literal(fit(row,colW-22)));
            nodes[i].active=i!=selectedSkill;
            nodes[i].setTooltip(Tooltip.create(Component.literal(row.description()+(row.reason().isEmpty()?"":"  ["+row.reason()+"]"))));
        }
        if (!page || data.skills().isEmpty()) return;
        selectedSkill=Math.max(0,Math.min(selectedSkill,data.skills().size()-1));
        TerminalData.SkillRow row=data.skills().get(selectedSkill);
        unlock.setMessage(Component.literal(row.state()==2?"UNLOCKED":"UNLOCK  -  "+row.cost()+" SKILL POINT"+(row.cost()==1?"":"S")));
        unlock.active=terminalPosition!=null && row.state()==1;
        unlock.setTooltip(row.state()==2?null:row.reason().isEmpty()?null:Tooltip.create(Component.literal(row.reason())));
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
        g.fill(left+12,top+10,left+16,top+24,TEAL);
        g.drawString(font,"UNDERGROUND // OPERATIONS",left+24,top+10,TEXT,false);
        g.fill(left+9,top+70,left+panelW-9,top+panelH-66,0xff172738);
        boolean exchange=current==Page.IMPORTS && exchangeOpen();
        int tabStep=(panelW-16)/tabs.length;
        g.fill(left+8+current.ordinal()*tabStep,top+63,left+8+current.ordinal()*tabStep+tabStep-2,top+65,TEAL);
        if (current==Page.CONTRACTS) renderWork(g);
        else if (current==Page.SKILLS) renderSkills(g);
        else if (current==Page.BLACK_MARKET) renderMarket(g);
        else {
            g.drawString(font,exchange?capitalize(data.region()).toUpperCase(java.util.Locale.ROOT)+" EXCHANGE":current.heading,left+18,top+81,TEAL,false);
            if (exchange) renderExchange(g);
            else {
                int y=top+108;
                for (String line:current.copy) { y=wrapped(g,line,left+18,y,panelW-36,SOFT)+12; }
                if (current==Page.IMPORTS) {
                    wrapped(g,data.region().isEmpty()?"This terminal is outside every city exchange.":"No regional stock is listed for this city.",left+18,y,panelW-36,AMBER);
                }
            }
        }
        String points=data.career().equals("NONE")?"":"  |  "+data.skillPoints()+" skill pt"+(data.skillPoints()==1?"":"s");
        String footer=capitalize(data.career())+" L"+data.level()+points+" | "+(data.economy()?String.format(java.util.Locale.ROOT,"%.0f credits",data.balance()):"Economy offline");
        g.drawString(font,footer,left+12,top+panelH-28,AMBER,false);
        g.drawString(font,"ESC TO CLOSE",left+panelW-88,top+panelH-15,DIM,false);
        g.drawString(font,capitalize(data.city())+(data.region().isEmpty()?"":"  |  at "+capitalize(data.region())),left+12,top+panelH-15,DIM,false);
        super.render(g,mouseX,mouseY,partialTick);
        if (current==Page.SKILLS) markNodes(g);
    }

    static String capitalize(String id) {
        return id.isEmpty()?"":Character.toUpperCase(id.charAt(0))+id.substring(1);
    }

    private void renderExchange(GuiGraphics g) {
        g.drawString(font,"Shared stock | replenishes during active play",left+18,top+93,DIM,false);
        for (int i=0;i<Math.min(MAX_OFFER_ROWS,data.offers().size());i++) {
            TerminalData.Offer offer=data.offers().get(i);
            int y=top+ROW_TOP+i*ROW_STEP;
            g.renderItem(new ItemStack(item(offer.item())),left+18,y);
            g.drawString(font,font.plainSubstrByWidth(itemName(offer.item()),panelW-210),left+40,y,TEXT,false);
            boolean soldOut=offer.stock()==0, poor=data.economy() && data.balance()<offer.price();
            String line=offer.price()+" Cr each  |  "+(soldOut?"SOLD OUT":offer.stock()+"/"+offer.capacity()+" in stock");
            if (!soldOut && poor) line+="  |  NEED "+(int)Math.ceil(offer.price()-data.balance())+" MORE";
            g.drawString(font,font.plainSubstrByWidth(line,panelW-170),left+40,y+10,soldOut?RED:poor?AMBER:SOFT,false);
        }
        if (data.offers().size()>MAX_OFFER_ROWS) g.drawString(font,"+"+(data.offers().size()-MAX_OFFER_ROWS)+" more lines not shown",left+18,top+ROW_TOP+MAX_OFFER_ROWS*ROW_STEP,DIM,false);
    }

    /** Journey/QA hook: a real mouse click at the centre of an offer's buy button. */
    boolean clickBuy(int row,boolean lot) {
        Button button=(lot?buyLot:buyOne)[row];
        return mouseClicked(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0);
    }

    private void renderWork(GuiGraphics g) {
        // The selected filter is also a darker, inactive button; the underline makes the choice unmistakable at a glance.
        Button chosen=undergroundView?undergroundFilter:legalFilter;
        g.fill(chosen.getX(),chosen.getY()+chosen.getHeight(),chosen.getX()+chosen.getWidth(),chosen.getY()+chosen.getHeight()+2,undergroundView?RED:TEAL);
        TerminalData.Row job=row();
        if (job==null) {
            String message=data.jobs().isEmpty()?"Register with /uc register <city> <career> to see paid profession orders."
                :undergroundView?"No underground contracts yet. Learn an underground skill in the SKILLS tab to open the trade."
                :"No legal contracts are listed for this profession.";
            wrapped(g,message,left+18,top+104,panelW-36,SOFT);
            return;
        }
        TerminalData.Extra extra=job.extra();
        int x0=left+18, y=top+94;
        g.drawString(font,font.plainSubstrByWidth(job.title(),panelW-100),x0,y,TEXT,false);
        y+=13;
        if (!extra.buyer().isEmpty()) {
            String buyer="Buyer: "+extra.buyer()+" ("+capitalize(extra.buyerCity())+")"+(extra.minTrust()>0||extra.underground()?"  |  trust "+extra.trust()+(extra.minTrust()>0?"/"+extra.minTrust():""):"");
            g.drawString(font,font.plainSubstrByWidth(buyer,panelW-36),x0,y,extra.underground()?RED:SOFT,false);
            y+=12;
        }
        for (TerminalData.Req req:job.reqs()) {
            boolean enough=req.held()>=req.count();
            g.renderItem(new ItemStack(item(req.item())),x0,y-3);
            String name=req.label().isEmpty()?itemName(req.item()):req.label();
            g.drawString(font,font.plainSubstrByWidth(req.count()+" x "+name,panelW-150),x0+20,y+1,SOFT,false);
            String held="have "+req.held()+"/"+req.count();
            g.drawString(font,held,left+panelW-18-font.width(held),y+1,enough?TEAL:AMBER,false);
            y+=17;
        }
        y+=2;
        g.drawString(font,"+"+job.credits()+" credits  /  +"+job.xp()+" XP  /  Level "+job.level(),x0,y,extra.underground()?AMBER:TEAL,false);
        y+=12;
        if (!extra.rewards().isEmpty()) {
            g.drawString(font,font.plainSubstrByWidth("Also: "+String.join(", ",extra.rewards()),panelW-36),x0,y,TEAL,false);
            y+=11;
        }
        String state=job.accepted()?(ready(job)?"READY - deliver plain, undamaged goods":"MISSING "+missing(job)+" goods - produce before delivery")
            :data.active()!=null?"Finish your accepted order first"
            :!extra.skillOk()?"LOCKED - requires the skill "+extra.skill()
            :data.level()<job.level()?"LOCKED - career level "+job.level()+" required"
            :extra.minTrust()>extra.trust()?"LOCKED - "+extra.buyer()+" trusts you "+extra.trust()+"/"+extra.minTrust()
            :data.foreignVisits()<job.foreignVisits()?"LOCKED - "+visitText(job)
            :"Employer demand: "+job.available()+" orders";
        if (job.accepted() && ready(job) && !extra.buyerCity().isEmpty() && !extra.buyerCity().equals(data.region())) {
            state="READY - carry it to "+extra.buyer()+" in "+capitalize(extra.buyerCity())+" and deliver at a terminal there";
        }
        wrapped(g,state,x0,y,panelW-36,AMBER);
    }

    private void renderSkills(GuiGraphics g) {
        g.drawString(font,capitalize(data.career()).toUpperCase(java.util.Locale.ROOT)+" SKILLS",left+18,top+76,TEAL,false);
        String points=data.skillPoints()+" POINT"+(data.skillPoints()==1?"":"S")+" FREE";
        g.drawString(font,points,left+panelW-18-font.width(points),top+76,AMBER,false);
        if (data.skills().isEmpty()) {
            wrapped(g,"Register with /uc register <city> <career> to learn career skills.",left+18,top+104,panelW-36,SOFT);
            return;
        }
        int colW=(panelW-36-12)/3;
        String[] titles={"LEGAL","FOUNDATION","UNDERGROUND"};
        int[] colors={TEAL,SOFT,RED};
        for (int i=0;i<3;i++) {
            int x=left+18+i*(colW+6);
            g.drawString(font,titles[i],x+(colW-font.width(titles[i]))/2,top+87,colors[i],false);
        }
        TerminalData.SkillRow row=data.skills().get(Math.min(selectedSkill,data.skills().size()-1));
        int branchColor=row.branch().equals("underground")?RED:row.branch().equals("legal")?TEAL:SOFT;
        g.fill(left+14,top+158,left+panelW-14,top+159,0xff2a4358);
        String meta=row.state()==2?"UNLOCKED":row.cost()+" point"+(row.cost()==1?"":"s")+"  |  level "+row.minLevel();
        g.drawString(font,font.plainSubstrByWidth(row.title(),panelW-190),left+18,top+162,branchColor,false);
        g.drawString(font,meta,left+panelW-18-font.width(meta),top+162,row.state()==2?TEAL:AMBER,false);
        var lines=font.split(Component.literal(row.description()),panelW-36);
        for (int i=0;i<Math.min(2,lines.size());i++) g.drawString(font,lines.get(i),left+18,top+172+i*10,SOFT,false);
        String note=row.state()==2?"":!row.reason().isEmpty()?row.reason():"Ready to unlock";
        if (!note.isEmpty()) g.drawString(font,font.plainSubstrByWidth(note,panelW-36),left+18,top+192,row.state()==1?TEAL:AMBER,false);
    }

    /** State markers drawn on top of the node buttons: teal unlocked, amber available, grey locked. */
    private void markNodes(GuiGraphics g) {
        for (int i=0;i<Math.min(MAX_NODES,data.skills().size());i++) {
            TerminalData.SkillRow row=data.skills().get(i);
            int color=row.state()==2?TEAL:row.state()==1?AMBER:GREY;
            int x=nodes[i].getX()+4,y=nodes[i].getY()+5;
            g.fill(x,y,x+4,y+4,color);
        }
    }

    private void renderMarket(GuiGraphics g) {
        if (data.region().isEmpty() || data.laws().isEmpty()) {
            g.drawString(font,"LAW AND CONTACTS",left+18,top+81,TEAL,false);
            int y=top+100;
            for (String line:Page.BLACK_MARKET.copy) y=wrapped(g,line,left+18,y,panelW-36,SOFT)+8;
            return;
        }
        g.drawString(font,capitalize(data.region()).toUpperCase(java.util.Locale.ROOT)+" LAW AND CONTACTS",left+18,top+76,TEAL,false);
        String heat=data.heat()==0?"HEAT 0 (quiet)":"HEAT "+data.heat()+" - searches likelier";
        g.drawString(font,heat,left+panelW-18-font.width(heat),top+76,data.heat()==0?DIM:RED,false);
        int y=wrapped(g,data.lawSummary(),left+18,top+88,panelW-36,SOFT)+3;
        int half=(panelW-36)/2;
        g.drawString(font,"CONTROLLED GOODS HERE",left+18,y,DIM,false);
        g.drawString(font,"BUYERS HERE",left+18+half+8,y,DIM,false);
        int rowY=y+12;
        for (TerminalData.LawRow law:data.laws()) {
            String status=law.status().equals("permit")?(law.licensed()?"LICENSED":"PERMIT"):law.status().toUpperCase(java.util.Locale.ROOT);
            int color=law.status().equals("legal")||law.licensed()?TEAL:law.status().equals("permit")?AMBER:RED;
            g.drawString(font,font.plainSubstrByWidth(law.title(),half-62),left+18,rowY,SOFT,false);
            g.drawString(font,status,left+18+half-font.width(status)-4,rowY,color,false);
            rowY+=10;
        }
        int contactY=y+12;
        if (data.contacts().isEmpty()) {
            wrapped(g,"No contacts yet. Learn an underground skill to be introduced.",left+18+half+8,contactY,half-8,DIM);
        }
        for (TerminalData.Contact contact:data.contacts()) {
            g.drawString(font,font.plainSubstrByWidth(contact.title(),half-14),left+18+half+8,contactY,contact.underground()?RED:TEAL,false);
            String detail=contact.underground()?"trust "+contact.trust()+"/"+BuyerCatalog.MAX_TRUST:"licensed, no trust needed";
            g.drawString(font,detail,left+18+half+8,contactY+9,DIM,false);
            contactY+=21;
        }
    }

    private int wrapped(GuiGraphics g,String text,int x,int y,int wrap,int color) {
        for (var line:font.split(Component.literal(text),wrap)) { g.drawString(font,line,x,y,color,false);y+=10; }
        return y;
    }
    @Override public boolean isPauseScreen() { return false; }
}
