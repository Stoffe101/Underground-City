package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real rendered screens with controlled snapshot fixtures; no gameplay claims. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class VisualCaptureHarness {
    private static final boolean ENABLED=Boolean.getBoolean("uc.visualCapture");
    private record Case(String name,TerminalScreen.Page page,TerminalData data,int shop,boolean underground,String skill,String challenge) {
        Case(String name,TerminalScreen.Page page,TerminalData data,int shop,boolean underground,String skill) { this(name,page,data,shop,underground,skill,null); }
        Case(String name,TerminalScreen.Page page,TerminalData data,int shop) { this(name,page,data,shop,false,null,null); }
        static Case challenge(String name,String pose) { return new Case(name,null,null,0,false,null,pose); }
        /** First-login registration screen poses: city, profession (city chosen) and confirm. */
        static Case registration(String name,String pose) { return new Case(name,null,null,0,false,null,"register-"+pose); }
        /** Conversation poses: the registrar for a newcomer, an employer and the gate guard. */
        static Case dialogue(String name,String pose) { return new Case(name,null,null,0,false,null,"dialogue-"+pose); }
        static Case ledger(String name) { return new Case(name,null,null,0,false,null,"ledger-board"); }
        static Case notices(String name) { return new Case(name,null,null,0,false,null,"notices-stack"); }
        /** The Crew screen in one of its states (CrewCaptures). */
        static Case crew(String name,String pose) { return new Case(name,null,null,0,false,null,"crew-"+pose); }
        /** The lockpicking screen in one of its poses (D-105). */
        static Case lock(String name,String pose) { return new Case(name,null,null,0,false,null,"lock-"+pose); }
        static Case civilian(String name,String pose) { return new Case(name,null,null,0,false,null,"civilian-"+pose); }
        static Case hud(String name,String pose) { return new Case(name,null,null,0,false,null,"hud-"+pose); }
    }
    private static List<Case> cases;
    private static int current,frames;
    private static boolean started,completed;
    private VisualCaptureHarness() {}
    private static TerminalData fixture(int level,int held,boolean active,boolean online) {
        return new TerminalData("valewood","farmer",level,level==1?0:125,60,online,
            active?UUID.fromString("00000000-0000-0000-0000-000000000001"):null,0,"",
            List.of(new TerminalData.Row("underground_city:bread","Bake the canteen bread order",2,38,180,4,active,0,
                List.of(new TerminalData.Req("minecraft:bread",8,held)))),List.of());
    }
    private static final List<TerminalData.Offer> IRONHAVEN_OFFERS=List.of(
        new TerminalData.Offer("underground_city:machined_parts","underground_city:machined_parts",18,16,36,8),
        new TerminalData.Offer("underground_city:refined_alloy","underground_city:refined_alloy",28,14,30,8),
        new TerminalData.Offer("underground_city:security_fabric","underground_city:security_fabric",22,0,24,8));
    /** The lines of a wayside provisions desk (D-091): a few basics, small stock, no city: the region stays empty and no orders are listed. */
    private static final List<TerminalData.Offer> WAYSIDE_OFFERS=List.of(
        new TerminalData.Offer("underground_city:survival/wayside_bread_ironhaven","minecraft:bread",7,8,12,4),
        new TerminalData.Offer("underground_city:survival/wayside_water_ironhaven","minecraft:potion",6,0,12,4),
        new TerminalData.Offer("underground_city:survival/wayside_waterskin_ironhaven","minecraft:leather",45,2,3,1),
        new TerminalData.Offer("underground_city:survival/wayside_torch_ironhaven","minecraft:torch",2,12,16,8));
    private static TerminalData waysideDesk(double balance) {
        return new TerminalData("ironhaven","farmer",3,520,balance,true,null,0,"",List.of(),WAYSIDE_OFFERS,0,List.of(),List.of(),List.of(),0,"",List.of(),"wayside");
    }
    /** A shopkeeper's counter (D-126): the Ironhaven hardware shop's real lines on the Trade page (copper sold out). */
    private static TerminalData counter(double balance) {
        List<TerminalData.Offer> offers=List.of(
            new TerminalData.Offer("underground_city:materials/iron_ingot_ironhaven","minecraft:iron_ingot",9,14,32,16),
            new TerminalData.Offer("underground_city:materials/coal_ironhaven","minecraft:coal",3,30,48,24),
            new TerminalData.Offer("underground_city:materials/copper_ingot_ironhaven","minecraft:copper_ingot",7,0,24,16));
        return new TerminalData("ironhaven","smith",3,520,balance,true,null,1,"ironhaven",List.of(),offers,0,List.of(),List.of(),List.of(),0,"",List.of(),"shop:hardware|Hardware and Metals");
    }

    /** A fence's quote (D-125): stolen goods in the pockets, priced at the fence's rate; partial when the budget is low, empty when nothing is marked. */
    private static FenceService.Quote fenceQuote(String pose) {
        List<FenceService.Row> rows=switch(pose) {
            case "fence-nothing" -> List.of();
            default -> List.of(new FenceService.Row("underground_city:jewellery_box","valewood",2,40),new FenceService.Row("minecraft:gold_ingot","valewood",5,17),
                new FenceService.Row("underground_city:old_laptop","ironhaven",1,36),new FenceService.Row("minecraft:clock","blackwater",1,67),new FenceService.Row("underground_city:pocket_watch","valewood",3,27));
        };
        int budget=pose.equals("fence-partial")?55:400;   // full: enough for everything (349); partial: less than most rows
        // open (after dark): a closed fence greys every button and the full and short-budget screens looked alike
        return new FenceService.Quote("ironhaven","The Yard","The night man in a warehouse office. Metal, tools and parts; cash in small amounts.",45,30,budget,true,rows);
    }

    private static TerminalData exchange(String region,double balance) {
        return new TerminalData("valewood","farmer",3,520,balance,true,null,region.isEmpty()?0:1,region,
            List.of(),region.isEmpty()?List.of():IRONHAVEN_OFFERS);
    }
    /** A long Trade list: twenty-four lines (the most the page holds) of goods with short and very long names, prices up to four digits and every stock state, to prove that nothing overlaps or runs off. */
    private static TerminalData manyOffers(int count,boolean soldOut,double balance) {
        String[] goods={"minecraft:bread","minecraft:enchanted_golden_apple","minecraft:iron_ingot","minecraft:netherite_upgrade_smithing_template","minecraft:copper_ingot","minecraft:redstone","minecraft:coal","minecraft:paper","minecraft:leather","minecraft:gold_nugget","minecraft:emerald","minecraft:clock",
            "minecraft:compass","minecraft:book","minecraft:string","minecraft:torch","minecraft:potion","minecraft:lapis_lazuli","minecraft:glass_bottle","minecraft:honey_bottle","minecraft:fermented_spider_eye","minecraft:music_disc_pigstep","minecraft:diamond","minecraft:armor_stand"};
        int[] prices={3,1250,28,9800,18,12,5,4,22,60,140,95,410,36,8,2,6,25,7,31,300,2400,1999,64};
        int[] stocks={40,2,12,0,30,24,16,40,1,5,3,8,0,20,12,40,9,7,0,3,2,1,6,11};
        List<TerminalData.Offer> offers=new ArrayList<>();
        for(int i=0;i<Math.min(count,goods.length);i++) offers.add(new TerminalData.Offer("underground_city:capture_"+i,goods[i],prices[i],soldOut?0:stocks[i],40,8));
        return new TerminalData("valewood","farmer",3,520,balance,true,null,1,"ironhaven",List.of(),offers);
    }
    /** The civilian counter in Core's own types (no Q Shop class, no transaction): a short counter, a poor wallet, a long scrolled list with very long names, the deliveries section, nothing at all and a daily limit. */
    private static CivilianShopScreen civilianCapture(String pose) {
        java.util.function.BiFunction<String,Integer,net.minecraft.world.item.ItemStack> stack=(id,count)->new net.minecraft.world.item.ItemStack(TerminalScreen.item(id),count);
        List<CivilianShopData.Entry> goods=new ArrayList<>(List.of(
            new CivilianShopData.Entry(0,false,"Basic seeds (x4)",stack.apply("minecraft:wheat_seeds",4),8,32,16,"DAILY"),
            new CivilianShopData.Entry(1,false,"Rations (x4)",stack.apply("minecraft:bread",4),20,-1,-1,"NEVER"),
            new CivilianShopData.Entry(2,false,"Materials (x16)",stack.apply("minecraft:cobblestone",16),24,32,3,"DAILY"),
            new CivilianShopData.Entry(3,false,"Workbench",stack.apply("minecraft:crafting_table",1),18,-1,-1,"NEVER")));
        List<CivilianShopData.Entry> wanted=List.of(
            new CivilianShopData.Entry(4,true,"Deliver wheat (x8)",stack.apply("minecraft:wheat",8),12,16,8,"DAILY"),
            new CivilianShopData.Entry(5,true,"Deliver logs (x8)",stack.apply("minecraft:oak_log",8),12,16,8,"DAILY"),
            new CivilianShopData.Entry(6,true,"Deliver basic stone (x16)",stack.apply("minecraft:cobblestone",16),6,16,8,"DAILY"));
        List<CivilianShopData.Entry> many=new ArrayList<>();
        String[] ids={"minecraft:bread","minecraft:enchanted_golden_apple","minecraft:iron_ingot","minecraft:netherite_upgrade_smithing_template","minecraft:copper_ingot","minecraft:redstone","minecraft:coal","minecraft:paper","minecraft:leather","minecraft:gold_nugget","minecraft:emerald","minecraft:clock","minecraft:compass","minecraft:book","minecraft:string","minecraft:torch","minecraft:potion","minecraft:lapis_lazuli"};
        String[] names={"Rations (x4)","Enchanted golden apple, a very long name for one apple that should be cut with an ellipsis","Iron ingot (x2)","Netherite upgrade smithing template","Copper ingot (x8)","Redstone dust (x16)","Coal (x8)","Paper (x8)","Leather (x4)","Gold nugget (x9)","Emerald","Clock","Compass","Book","String (x8)","Torches (x16)","Water bottle (x2)","Lapis lazuli (x8)"};
        int[] prices={20,1250,28,9800,18,12,5,4,22,60,140,95,410,36,8,2,6,25};
        for(int i=0;i<ids.length;i++) many.add(new CivilianShopData.Entry(i,false,names[i],stack.apply(ids[i],i%4==0?4:1),prices[i],i%5==2?4:-1,-1,"DAILY"));
        String id="uc_starter_supply";
        CivilianShopData data=switch(pose) {
            case "poor" -> new CivilianShopData(id,"Supply","credits",9,List.of(new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),goods)));
            case "scrolled" -> new CivilianShopData(id,"Supply","credits",400,List.of(new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),many)));
            case "deliveries" -> new CivilianShopData(id,"Supply","credits",72,List.of(
                new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),goods),
                new CivilianShopData.Tab(1,"Deliver",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT),wanted)));
            case "empty" -> new CivilianShopData(id,"Supply","credits",72,List.of(new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),List.of())));
            case "limited" -> new CivilianShopData(id,"Supply","credits",72,List.of(
                new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),List.of(
                    new CivilianShopData.Entry(0,false,"Basic seeds (x4)",stack.apply("minecraft:wheat_seeds",4),8,1,16,"DAILY"),
                    new CivilianShopData.Entry(1,false,"Workbench",stack.apply("minecraft:crafting_table",1),18,2,1,"DAILY"))),
                new CivilianShopData.Tab(1,"Deliver",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT),wanted)));
            default -> new CivilianShopData(id,"Supply","credits",72,List.of(
                new CivilianShopData.Tab(0,"Goods",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),goods),
                new CivilianShopData.Tab(1,"Deliver",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT),wanted)));
        };
        CivilianShopScreen.forgetCarried();
        return new CivilianShopScreen(data,(shop,tab,entry,units)->{});
    }
    private static LockpickScreen lockCapture(String pose) {
        return switch(pose) {
            case "lock-idle" -> LockpickScreen.forCapture("Deadbolt",3,0,0,4,0.92,0.18,false);
            case "lock-close" -> LockpickScreen.forCapture("Deadbolt",3,1,2,10,0.7,0.52,false);
            case "lock-pressing" -> LockpickScreen.forCapture("Commercial",4,2,3,24,0.55,0.61,true);
            case "lock-strain" -> LockpickScreen.forCapture("High-security",5,2,0,88,0.3,0.9,true);
            case "lock-opened" -> LockpickScreen.forCapture("Deadbolt",3,3,3,10,0.5,0.4,false).poseResult(LockpickSession.State.OPENED,"Click. It is open.");
            case "lock-snapped" -> LockpickScreen.forCapture("Commercial",4,1,0,100,0.4,0.77,true).poseResult(LockpickSession.State.BROKEN,"The pick snapped in the lock.");
            case "lock-spool" -> LockpickScreen.forCapture("Apartment tower",4,1,2,18,0.7,0.46,false).poseSpool(1,false);
            case "lock-turned" -> LockpickScreen.forCapture("High-security",5,2,1,46,0.5,0.58,true).poseSpool(2,false);
            case "lock-rake" -> LockpickScreen.forCapture("Cottage",2,0,0,0,0.95,0.5,false).poseSpool(0,true);
            default -> LockpickScreen.forCapture("Latch",2,1,1,0,0.0,0.3,false).poseResult(LockpickSession.State.TIMED_OUT,"You ran out of time. The lock is as it was.");
        };
    }
    private static TerminalData regionalContract(boolean accepted,int held,int visits) {
        return new TerminalData("valewood","farmer",3,520,230,true,
            accepted?UUID.fromString("00000000-0000-0000-0000-000000000002"):null,visits,"valewood",
            List.of(new TerminalData.Row("underground_city:greenhouse_service","Service the greenhouse irrigation line",3,150,300,2,accepted,1,
                List.of(new TerminalData.Req("underground_city:irrigation_service_kit",4,held)))),List.of());
    }
    /** The mod's own skill-tree JSON, read from the classpath, so captures use the real titles and short titles. */
    private static List<SkillNode> realTree(String career) {
        try(var reader=new java.io.InputStreamReader(VisualCaptureHarness.class.getResourceAsStream("/data/"+UndergroundCity.MOD_ID+"/skill_trees/"+career+".json"),java.nio.charset.StandardCharsets.UTF_8)) {
            var json=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            List<SkillNode> nodes=new ArrayList<>();
            for(var element:json.getAsJsonArray("nodes")) nodes.add(SkillCatalog.parse(career,element.getAsJsonObject()));
            return nodes;
        } catch(java.io.IOException error) { throw new IllegalStateException("Cannot read the "+career+" skill tree",error); }
    }
    /** A whole career page built from the real tree: the states come from the same pure rules the server uses. */
    private static TerminalData careerSkills(String career,int level,java.util.Set<String> learned) {
        List<SkillNode> tree=realTree(career);
        java.util.Map<String,SkillNode> index=new java.util.HashMap<>();
        for(SkillNode node:tree) index.put(node.id(),node);
        int free=SkillRules.pointsEarned(level)-SkillRules.pointsSpent(tree,learned);
        List<TerminalData.SkillRow> rows=new ArrayList<>();
        for(SkillNode node:SkillRules.displayOrder(tree)) {
            String blocker=SkillRules.blocker(node,index,level,learned,free);
            int state=learned.contains(node.id())?2:blocker==null?1:0;
            List<String> requires=node.requires();
            rows.add(new TerminalData.SkillRow(node.id(),node.branch(),node.title(),node.description(),node.cost(),node.minLevel(),
                state,state==2||blocker==null?"":blocker,requires,node.shortTitle(),node.effectSummary()));
        }
        if(free<0) throw new IllegalStateException("The capture fixture spends more skill points than level "+level+" earns");
        return new TerminalData("valewood",career,level,125*(level-1)*(level-1),230,true,null,1,"valewood",List.of(),List.of(),free,rows,List.of(),VALEWOOD_LAW,0,"");
    }
    /** The Thief page from the real Thief tree (D-143) for a character of this Thief level with these skills; the states come from the server's own rules. */
    private static TerminalData thiefSkills(int level,java.util.Set<String> learned) {
        List<SkillNode> tree=new ArrayList<>();
        try(var reader=new java.io.InputStreamReader(VisualCaptureHarness.class.getResourceAsStream("/data/"+UndergroundCity.MOD_ID+"/specialization_trees/thief.json"),java.nio.charset.StandardCharsets.UTF_8)) {
            for(var element:com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("nodes")) tree.add(SkillCatalog.parse(SkillNode.THIEF,element.getAsJsonObject()));
        } catch(java.io.IOException error) { throw new IllegalStateException("Cannot read the Thief tree",error); }
        java.util.Map<String,SkillNode> index=new java.util.HashMap<>();
        for(SkillNode node:tree) index.put(node.id(),node);
        int free=SkillRules.thiefPointsEarned(level)-SkillRules.pointsSpent(tree,learned);
        if(free<0) throw new IllegalStateException("The capture fixture spends more Thief points than level "+level+" earns");
        List<TerminalData.SkillRow> rows=new ArrayList<>();
        for(SkillNode node:SkillRules.displayOrder(tree)) {
            String blocker=SkillRules.blocker(node,index,level,learned,free);
            int state=learned.contains(node.id())?2:blocker==null?1:0;
            rows.add(new TerminalData.SkillRow(node.id(),node.branch(),node.title(),node.description(),node.cost(),node.minLevel(),
                state,state==2||blocker==null?"":blocker,node.requires(),node.shortTitle(),node.effectSummary()));
        }
        int xp=ProgressionRules.thiefXpAtLevel(level)+ProgressionRules.THIEF_XP_STEP;
        TerminalData base=farmerData();
        return new TerminalData(base.city(),base.career(),base.level(),base.xp(),base.balance(),base.economy(),base.active(),base.foreignVisits(),base.region(),base.jobs(),base.offers(),
            base.skillPoints(),base.skills(),base.contacts(),base.laws(),base.heat(),base.lawSummary(),base.journey(),base.outlet(),
            new TerminalData.Thief(level,xp,ProgressionRules.thiefXpAtLevel(level+1),free,List.copyOf(rows)));
    }
    /** A casing card as the server would send it, for a beginner and for a thief who knows what to look for. */
    private static Casing.Report casingFixture(String pose) {
        return switch(pose) {
            case "casing-novice" -> new Casing.Report("Apartment block","Ironhaven",2,"a deadbolt","A lockpick will do.",true,-1,-1,-1,"",-1,-1,false,0);
            case "casing-locked" -> new Casing.Report("High-value premises","Blackwater",6,"a high-security lock","A high-security lock. Lockpicks are no use on it: it needs a Pro Pick Set.",false,-1,-1,-1,"",-1,-1,false,1);
            default -> new Casing.Report("Apartment tower","Blackwater",4,"a secured lock with a spool pin","A lockpick will do, with care: it has 1 spool pin.",true,3,2,1,"Empty from about 09:00 (in 3 hours)",2,1,true,2);
        };
    }
    /** A level-9 farmer built from the real tree: the foundation, a legal skill, the controlled crop and the logistics skill learned, one point free. */
    private static TerminalData farmerData() {
        return careerSkills("farmer",9,java.util.Set.of("farmer.fundamentals","farmer.greenhouse_management","farmer.restricted_cultivation","farmer.field_logistics"));
    }
    private static final List<TerminalData.LawRow> VALEWOOD_LAW=List.of(
        new TerminalData.LawRow("restricted_cultivar","Controlled crops","prohibited",false),
        new TerminalData.LawRow("restricted_components","Unregistered components","prohibited",false),
        new TerminalData.LawRow("restricted_electronics","Restricted electronics","permit",false),
        new TerminalData.LawRow("firearms_licensed","Licensed civilian firearms","permit",false),
        new TerminalData.LawRow("firearms_restricted","Restricted weapons","prohibited",false));
    private static final List<TerminalData.LawRow> BLACKWATER_LAW=List.of(
        new TerminalData.LawRow("restricted_cultivar","Controlled crops","permit",true),
        new TerminalData.LawRow("restricted_components","Unregistered components","legal",false),
        new TerminalData.LawRow("restricted_electronics","Restricted electronics","legal",false),
        new TerminalData.LawRow("firearms_licensed","Licensed civilian firearms","legal",false),
        new TerminalData.LawRow("firearms_restricted","Restricted weapons","permit",false));
    private static final TerminalData.Extra QUIET_BALES=new TerminalData.Extra(true,"Underground Trade",false,"Marrow, grey-market broker","blackwater",0,1,List.of());
    private static final TerminalData.Extra RESERVE_LOAD=new TerminalData.Extra(true,"Grey Market Contacts",false,"Marrow, grey-market broker","blackwater",2,1,
        List.of("2 x Cannabis Seeds"));
    private static TerminalData.Row undergroundRow(String id,String title,int level,int credits,int xp,int count,int held,boolean accepted,TerminalData.Extra extra) {
        return new TerminalData.Row("underground_city:"+id,title,level,credits,xp,2,accepted,0,
            List.of(new TerminalData.Req("underground_city:cannabis_flower",count,held)),extra);
    }
    private static final TerminalData.Row LICENSED_ROW=new TerminalData.Row("underground_city:licensed_cultivar_supply","Licensed controlled-crop supply",10,225,400,2,false,0,
        List.of(new TerminalData.Req("underground_city:cannabis_flower",6,0)),
        new TerminalData.Extra(false,"Controlled-Crop Licence",false,"Blackwater Licensed Dispensary","blackwater",0,0,List.of()));
    /** The first half hour part way through: registered, met the employer, accepted an order. */
    private static List<TerminalData.Step> journeyFixture() {
        List<TerminalData.Step> steps=new ArrayList<>();
        int n=0;
        for(Journey.Def def:Journey.STEPS) steps.add(new TerminalData.Step(def.id(),def.title(),def.hint(),n++<3,def.page()));
        return steps;
    }
    private static TerminalData rich(String region,boolean accepted,int held,List<TerminalData.LawRow> laws) {
        boolean blackwater=region.equals("blackwater");
        List<TerminalData.Contact> contacts=region.isEmpty()?List.of():blackwater
            ?List.of(new TerminalData.Contact("marrow","Marrow, grey-market broker","blackwater",true,"Pays for cannabis flower, no questions, within limits.",1),
                new TerminalData.Contact("blackwater_dispensary","Blackwater Licensed Dispensary","blackwater",false,"A permitted handler that buys controlled crops from licensed growers.",0))
            :List.of(new TerminalData.Contact("hollis","Hollis & Sons Haulage","valewood",true,"Takes unstamped plate and parts that never saw a register.",0),
                new TerminalData.Contact("valewood_coop","Valewood Hunting Co-op","valewood",false,"Buys licensed shotguns and sidearms from registered dealers.",0));
        List<TerminalData.Row> jobs=List.of(
            new TerminalData.Row("underground_city:greenhouse_wholesale","Greenhouse wholesale: carrots and potatoes",3,100,260,4,false,0,
                List.of(new TerminalData.Req("minecraft:carrot",24,24),new TerminalData.Req("minecraft:potato",24,10)),
                new TerminalData.Extra(false,"Greenhouse Management",true,"","",0,0,List.of())),
            new TerminalData.Row("underground_city:heirloom_seeds","Broker heirloom seed stock",5,150,330,2,false,0,
                List.of(new TerminalData.Req("underground_city:rare_seeds",8,3)),
                new TerminalData.Extra(false,"Cultivar Broker",false,"","",0,0,List.of("1 x Heirloom Seed Packet"))),
            LICENSED_ROW,
            undergroundRow("quiet_bales","Quiet bale delivery",9,237,380,6,accepted?held:0,accepted,QUIET_BALES),
            undergroundRow("reserve_load","Reserve load for the estate trade",12,474,600,12,0,false,RESERVE_LOAD));
        return new TerminalData("valewood","farmer",9,10125,230,true,accepted?UUID.fromString("00000000-0000-0000-0000-000000000003"):null,
            1,region,jobs,List.of(),farmerData().skillPoints(),farmerData().skills(),contacts,laws,region.isEmpty()?0:2,
            blackwater?"Uneven enforcement. Most trade is tolerated if you hold the right licence; the buyers here pay for discretion."
                :"Modest patrols. Hunting arms are licensed; controlled crops and unregistered parts are prohibited.",
            journeyFixture());
    }
    private static DialogueScreen dialogueCapture(String pose) {
        var c=(java.util.function.BiFunction<String,String,DialogueScreen.Choice>)(id,label)->new DialogueScreen.Choice(id,label,true,"");
        return switch(pose) {
            case "dialogue-registrar" -> new DialogueScreen(-1,"Imani Okafor","Registrar","valewood",
                "Welcome to Founders Row. Nobody gets a terminal without a name on the register. Tell me where you want to live and how you mean to earn it, and I will file it.",
                List.of(c.apply("register","Register me"),c.apply("cities","Tell me about the three cities"),c.apply("bye","Goodbye")),true);
            case "dialogue-employer" -> new DialogueScreen(-1,"Odette Marsh","Employer: Farmer","valewood",
                "The canteen wants bread again, the greenhouses want hands, and the co-op pays on delivery. Come through to the office and we will go over the orders.",
                List.of(c.apply("orders","Open my orders"),c.apply("demand","How does demand work?"),c.apply("bye","Goodbye")),true);
            default -> new DialogueScreen(-1,"Sergeant Halloran","Gate guard","valewood",
                "Modest patrols here. Hunting arms are licensed; controlled crops and unregistered parts are prohibited. Keep your papers where I can see them.",
                List.of(c.apply("law","What is the law here?"),c.apply("posts","Where are the checkpoints?"),c.apply("search","How do searches work?"),c.apply("bye","Goodbye")),true);
        };
    }
    private static LedgerData ledgerFixture() {
        var p=new java.util.ArrayList<LedgerData.Posting>();
        p.add(new LedgerData.Posting("farmer","Bake the canteen bread order",2,38,180,4,6,false));
        p.add(new LedgerData.Posting("farmer","Greenhouse wholesale: carrots and potatoes",3,100,260,4,4,false));
        p.add(new LedgerData.Posting("farmer","Broker heirloom seed stock",5,150,330,2,3,false));
        p.add(new LedgerData.Posting("farmer","Reserve harvest for the co-op",8,240,500,0,3,true));
        p.add(new LedgerData.Posting("smith","Structural brackets for the bridge works",2,48,200,6,8,false));
        p.add(new LedgerData.Posting("smith","Alloy ingots for the foundry",4,90,260,3,4,false));
        p.add(new LedgerData.Posting("gunsmith","Hunting arrows for the guard post",2,40,190,5,6,false));
        p.add(new LedgerData.Posting("gunsmith","Licensed sidearm for a dealer",6,310,420,1,2,false));
        p.add(new LedgerData.Posting("technician","Repair the market's street lamps",2,44,210,3,5,false));
        p.add(new LedgerData.Posting("technician","Install an automation controller",7,260,480,2,2,true));
        return new LedgerData("valewood","valewood","farmer",4,java.util.List.copyOf(p));
    }
    private static void start(Minecraft mc) {
        int scale=Integer.getInteger("uc.visualCapture.scale",3);
        if(scale<1||scale>4) throw new IllegalArgumentException("Bad GUI scale");
        mc.options.guiScale().set(scale); mc.resizeDisplay();
        cases=new ArrayList<>();
        for(var page:TerminalScreen.Page.values()) {
            boolean rich=page==TerminalScreen.Page.SKILLS || page==TerminalScreen.Page.BLACK_MARKET || page==TerminalScreen.Page.GUIDE;
            TerminalData data=page==TerminalScreen.Page.THIEF?thiefSkills(6,java.util.Set.of("thief.casing","thief.steady_hands","thief.soft_steps","thief.fence_contacts"))
                :rich?rich("valewood",false,0,VALEWOOD_LAW):fixture(2,8,false,true);
            cases.add(new Case("uc-terminal-"+page.name().toLowerCase(java.util.Locale.ROOT),page,data,0,false,
                page==TerminalScreen.Page.SKILLS?"farmer.restricted_cultivation":page==TerminalScreen.Page.THIEF?"thief.raking":null));
        }
        cases.add(new Case("uc-thief-expert",TerminalScreen.Page.THIEF,thiefSkills(16,java.util.Set.of("thief.casing","thief.steady_hands","thief.raking","thief.feel_for_pins","thief.pro_tools",
            "thief.soft_steps","thief.quick_hands","thief.routines","thief.camera_sense","thief.fence_contacts","thief.haggler")),0,false,"thief.ghost"));
        cases.add(new Case("uc-thief-new",TerminalScreen.Page.THIEF,thiefSkills(1,java.util.Set.of()),0,false,"thief.casing"));
        cases.add(Case.challenge("uc-casing-novice","casing-novice"));
        cases.add(Case.challenge("uc-casing-expert","casing-expert"));
        cases.add(Case.challenge("uc-casing-locked","casing-locked"));
        cases.add(new Case("uc-skills-vintner",TerminalScreen.Page.SKILLS,careerSkills("vintner",11,java.util.Set.of("vintner.fundamentals","vintner.cellar_craft","vintner.still_craft")),0,false,"vintner.bootleg_trade"));
        cases.add(new Case("uc-skills-smith",TerminalScreen.Page.SKILLS,careerSkills("smith",11,java.util.Set.of("smith.fundamentals","smith.structural_works","smith.armoury_contracts","smith.unlisted_fabrication")),0,false,"smith.contraband_armoury"));
        cases.add(new Case("uc-skills-gunsmith",TerminalScreen.Page.SKILLS,careerSkills("gunsmith",11,java.util.Set.of("gunsmith.fundamentals","gunsmith.licensed_repairs","gunsmith.dealer_licence")),0,false,"gunsmith.unregistered_builds"));
        cases.add(new Case("uc-skills-technician",TerminalScreen.Page.SKILLS,careerSkills("technician",11,java.util.Set.of("technician.fundamentals","technician.municipal_repairs","technician.automation_install")),0,false,"technician.bypass_tooling"));
        cases.add(new Case("uc-skills-farmer-deep",TerminalScreen.Page.SKILLS,careerSkills("farmer",22,java.util.Set.of("farmer.fundamentals","farmer.greenhouse_management","farmer.cultivar_broker","farmer.estate_wholesale","farmer.restricted_cultivation","farmer.concealed_stores","farmer.underground_trade","farmer.grey_market_contacts","farmer.field_logistics")),0,false,"farmer.back_lot_network"));
        cases.add(new Case("uc-skills-available",TerminalScreen.Page.SKILLS,rich("valewood",false,0,VALEWOOD_LAW),0,false,"farmer.concealed_stores"));
        cases.add(new Case("uc-skills-locked",TerminalScreen.Page.SKILLS,rich("valewood",false,0,VALEWOOD_LAW),0,false,"farmer.underground_trade"));
        cases.add(new Case("uc-skills-legal-locked",TerminalScreen.Page.SKILLS,rich("valewood",false,0,VALEWOOD_LAW),0,false,"farmer.controlled_crop_licence"));
        cases.add(new Case("uc-contract-underground",TerminalScreen.Page.CONTRACTS,rich("valewood",true,6,VALEWOOD_LAW),0,true,null));
        cases.add(new Case("uc-contract-underground-locked",TerminalScreen.Page.CONTRACTS,rich("valewood",false,0,VALEWOOD_LAW),0,true,null));
        cases.add(new Case("uc-contract-legal-rewards",TerminalScreen.Page.CONTRACTS,rich("valewood",false,0,VALEWOOD_LAW),0,false,null));
        cases.add(new Case("uc-market-blackwater",TerminalScreen.Page.BLACK_MARKET,rich("blackwater",false,0,BLACKWATER_LAW),0,false,null));
        cases.add(new Case("uc-market-outside-city",TerminalScreen.Page.BLACK_MARKET,rich("",false,0,List.of()),0,false,null));
        cases.add(new Case("uc-contract-accepted",TerminalScreen.Page.CONTRACTS,fixture(2,8,true,true),0));
        cases.add(new Case("uc-contract-missing",TerminalScreen.Page.CONTRACTS,fixture(2,2,true,true),0));
        cases.add(new Case("uc-contract-locked",TerminalScreen.Page.CONTRACTS,fixture(1,0,false,true),0));
        cases.add(new Case("uc-contract-unregistered",TerminalScreen.Page.CONTRACTS,TerminalData.offline(),0));
        cases.add(new Case("uc-contract-offline",TerminalScreen.Page.CONTRACTS,fixture(2,8,false,false),0));
        cases.add(new Case("uc-exchange-ironhaven",TerminalScreen.Page.IMPORTS,exchange("ironhaven",120),0));
        cases.add(new Case("uc-exchange-low-funds",TerminalScreen.Page.IMPORTS,exchange("ironhaven",20),0));
        cases.add(new Case("uc-imports-outside-city",TerminalScreen.Page.IMPORTS,exchange("",120),0));
        cases.add(new Case("uc-exchange-wayside-desk",TerminalScreen.Page.IMPORTS,waysideDesk(120),0));
        cases.add(new Case("uc-exchange-overflow",TerminalScreen.Page.IMPORTS,manyOffers(24,false,60),0));
        cases.add(new Case("uc-exchange-overflow-scrolled",TerminalScreen.Page.IMPORTS,manyOffers(24,false,2500),0));
        cases.add(new Case("uc-exchange-sold-out",TerminalScreen.Page.IMPORTS,manyOffers(12,true,500),0));
        cases.add(new Case("uc-contract-regional",TerminalScreen.Page.CONTRACTS,regionalContract(true,2,1),0));
        cases.add(new Case("uc-contract-visit-locked",TerminalScreen.Page.CONTRACTS,regionalContract(false,0,0),0));
        if(!QShopEconomy.available()) throw new IllegalStateException("Visual coverage requires installed Q Shop 1.8.4");
        cases.add(Case.registration("uc-register-city","city"));
        cases.add(Case.registration("uc-register-career","career"));
        cases.add(Case.registration("uc-register-confirm","confirm"));
        cases.add(Case.challenge("uc-challenge-show","show"));
        cases.add(Case.challenge("uc-challenge-input","input"));
        cases.add(Case.challenge("uc-challenge-urgent","urgent"));
        cases.add(Case.challenge("uc-alarm-show","alarm-show"));
        cases.add(Case.challenge("uc-alarm-input","alarm-input"));
        cases.add(Case.challenge("uc-fence-full","fence-full"));
        cases.add(Case.challenge("uc-fence-partial","fence-partial"));
        cases.add(Case.challenge("uc-fence-nothing","fence-nothing"));
        cases.add(new Case("uc-counter-hardware",TerminalScreen.Page.IMPORTS,counter(140),0));
        cases.add(new Case("uc-counter-poor",TerminalScreen.Page.IMPORTS,counter(6),0));
        cases.add(Case.ledger("uc-ledger"));
        cases.add(Case.notices("uc-notices"));
        for(String pose:new String[]{"nocrew","nocrew-invited","unregistered","unavailable","members","members-member","money","money-consent","money-shared","money-shared-nospend","home","home-notice","home-owned","home-find","home-find-many","home-solo","rules-permissions","rules-mode","rules-mode-active","rules-disband"}) {
            cases.add(Case.crew("uc-crew-"+pose,pose));
        }
        for(String pose:new String[]{"idle","close","pressing","strain","opened","snapped","timeout","spool","turned","rake"}) cases.add(Case.lock("uc-lock-"+pose,pose));
        for(String pose:new String[]{"counter","poor","scrolled","deliveries","empty","limited"}) cases.add(Case.civilian("uc-civilian-shop-"+pose,pose));
        for(String pose:new String[]{"settings","left","minimal"}) cases.add(Case.hud("uc-hud-"+pose,pose));
        cases.add(Case.dialogue("uc-dialogue-registrar","registrar"));
        cases.add(Case.dialogue("uc-dialogue-employer","employer"));
        cases.add(Case.dialogue("uc-dialogue-guard","guard"));
        cases.add(new Case("uc-qshop-main",null,null,1));
        cases.add(new Case("uc-qshop-low-funds",null,null,2));
        cases.add(new Case("uc-qshop-trade-modal",null,null,3));
        started=true; show(mc);
    }
    private static void show(Minecraft mc) {
        Case capture=cases.get(current);
        if(capture.shop>0) {
            Screen screen=QShopVisualCases.shop(capture.shop==1);
            CivilianShopHook.suspended=true; // these three are the provider's own screens, kept as evidence of them
            try { mc.setScreen(screen); } finally { CivilianShopHook.suspended=false; }
            if(capture.shop==3) QShopVisualCases.openLowFundsModal(screen);
        } else if(capture.challenge!=null && capture.challenge.startsWith("register-")) {
            mc.setScreen(switch(capture.challenge) {
                case "register-city" -> RegistrationScreen.forCapture(0,-1,-1,"");
                case "register-career" -> RegistrationScreen.forCapture(1,1,-1,"");
                default -> RegistrationScreen.forCapture(2,1,0,"");
            });
        } else if(capture.challenge!=null && capture.challenge.startsWith("crew-")) {
            mc.setScreen(CrewCaptures.screen(capture.challenge.substring("crew-".length())));
        } else if(capture.challenge!=null && capture.challenge.startsWith("civilian-")) {
            String pose=capture.challenge.substring("civilian-".length());
            CivilianShopScreen screen=civilianCapture(pose);
            mc.setScreen(screen);
            if(pose.equals("deliveries")) screen.showTab(1);
            if(pose.equals("scrolled")) screen.scrollTo(1);
        } else if(capture.challenge!=null && capture.challenge.startsWith("hud-")) {
            // the status display's settings, with the real stack drawn at the real place beside the panel; the choices are the capture's own, never read from or written to the config file
            String pose=capture.challenge.substring("hud-".length());
            HudConfig.Settings choices=new HudConfig.Settings();
            choices.anchor=pose.equals("left")?HudLayout.Anchor.MIDDLE_LEFT:HudLayout.Anchor.TOP_RIGHT;
            choices.scale=pose.equals("left")?1.2:pose.equals("minimal")?0.8:1.0;
            choices.opacity=pose.equals("minimal")?0.6:0.9;
            if(pose.equals("minimal")) { choices.guidance=false; choices.clock=false; choices.crew=false; choices.home=false; choices.career=false; choices.heat=false; }
            mc.setScreen(HudSettingsScreen.forCapture(choices));
        } else if(capture.challenge!=null && capture.challenge.startsWith("lock-")) {
            mc.setScreen(lockCapture(capture.challenge));
        } else if(capture.challenge!=null && capture.challenge.startsWith("notices-")) {
            // the corner notices over a terminal: an order taken, a delivery paid with a level-up, a search at a checkpoint and a rare find
            TerminalScreen screen=new TerminalScreen(BlockPos.ZERO,fixture(2,8,true,true));
            mc.setScreen(screen); screen.showPage(TerminalScreen.Page.CONTRACTS);
            // The game draws toasts after the screen, which is after the event this harness grabs its frame in, and they time out; so the plates are
            // drawn by hand, stacked, at a fixed moment. (In the game itself at most two of these fit on screen at once and the others wait their turn.)
            noticeCaptures.clear();
            noticeCaptures.add(new UcToast(Notice.Kind.ORDER,"Order accepted","Bake the canteen bread order"));
            noticeCaptures.add(new UcToast(Notice.Kind.DELIVERED,"Delivered","Bake the canteen bread order: +38 credits, +180 XP"));
            noticeCaptures.add(new UcToast(Notice.Kind.HEAT,"Contraband seized","1 stack taken, 40 credits fined. Heat 2 in Valewood."));
            noticeCaptures.add(new UcToast(Notice.Kind.LOOT,"A rare find","Worth more than the rest. Take it and go."));
        } else if(capture.challenge!=null && capture.challenge.startsWith("ledger-")) {
            mc.setScreen(new LedgerScreen(ledgerFixture()));
        } else if(capture.challenge!=null && capture.challenge.startsWith("dialogue-")) {
            mc.setScreen(dialogueCapture(capture.challenge));
        } else if(capture.challenge!=null && capture.challenge.startsWith("alarm-")) {
            ChallengeScreen screen=ChallengeScreen.forCapture(new byte[]{1,3,0,2,2,1},ChallengeScreen.Kind.ALARM,AlarmPanels.BASE_PER_SYMBOL,AlarmPanels.BASE_INPUT_TICKS);
            mc.setScreen(screen);
            if(capture.challenge.equals("alarm-show")) screen.poseShowing(3,true); else screen.poseInput(3,0.6);
        } else if(capture.challenge!=null && capture.challenge.startsWith("casing-")) {
            mc.setScreen(new CasingScreen(casingFixture(capture.challenge)));
        } else if(capture.challenge!=null && capture.challenge.startsWith("fence-")) {
            mc.setScreen(FenceScreen.forCapture(fenceQuote(capture.challenge),0));
        } else if(capture.challenge!=null) {
            ChallengeScreen screen=ChallengeScreen.forCapture(new byte[]{0,2,1,3,2});
            mc.setScreen(screen);
            switch(capture.challenge) {
                case "show" -> screen.poseShowing(2,true);
                case "input" -> screen.poseInput(2,0.35);
                default -> screen.poseInput(4,0.85);
            }
        } else {
            TerminalScreen screen=new TerminalScreen(BlockPos.ZERO,capture.data);
            mc.setScreen(screen); screen.showPage(capture.page);
            if(capture.page==TerminalScreen.Page.CONTRACTS && capture.underground) screen.showView(true);
            if(capture.skill!=null) screen.selectSkill(capture.skill);
            // The "legal with rewards" case selects the heirloom contract (index 1 of the legal list).
            if(capture.name.equals("uc-contract-legal-rewards")) screen.selectJob(1);
            if(capture.name.equals("uc-exchange-overflow-scrolled")) for(int i=0;i<8;i++) screen.mouseScrolled(0,0,-1.0);
        }
        frames=0;
    }
    private static final java.util.List<UcToast> noticeCaptures=new java.util.ArrayList<>();
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent event) {
        if(!ENABLED||started||completed||event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen) start(mc);
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||!started||completed) return;
        Minecraft mc=Minecraft.getInstance();
        if(current>=cases.size()) {
            try {
                Path dir=mc.gameDirectory.toPath().resolve("screenshots");
                for(Case capture:cases) if(!Files.isRegularFile(dir.resolve(capture.name+".png")) || Files.size(dir.resolve(capture.name+".png"))<1024) return;
                Files.writeString(mc.gameDirectory.toPath().resolve("visual-capture-complete.txt"),
                    "screens="+cases.size()+"\ngui_scale="+Integer.getInteger("uc.visualCapture.scale",3)+"\n");
                completed=true; mc.stop();
            } catch(java.io.IOException error) { throw new IllegalStateException("Visual capture failed",error); }
            return;
        }
        if(cases.get(current).challenge!=null && cases.get(current).challenge.startsWith("notices-")) {
            var g=event.getGuiGraphics();
            int y=4;
            // a review of the plates themselves, not of where the game puts them: in the game they sit in the screen's corner, and while that corner would cover an open screen's panel
            // (this terminal at 1280x720 or 1920x1080) they wait until it closes (HudLayout.toastsCoverPanel); here they are drawn beside the panel, or over its edge where there is no room
            int x=Math.min(g.guiWidth()-190-2,event.getScreen() instanceof UcScreen ui?ui.panelRight()+6:g.guiWidth());
            for(UcToast toast:noticeCaptures) {
                g.pose().pushPose();
                g.pose().translate(x,y,400);
                toast.render(g,mc.getToasts(),1000L);
                g.pose().popPose();
                y+=toast.height()+4;
            }
        }
        if(++frames<12) return;
        String filename=cases.get(current).name+".png";
        Screenshot.grab(mc.gameDirectory,filename,mc.getMainRenderTarget(),
            response->System.out.println("[UC-VISUAL] Captured "+filename));
        current++;
        if(current<cases.size()) show(mc);
    }
}
