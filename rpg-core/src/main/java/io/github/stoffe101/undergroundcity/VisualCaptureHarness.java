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
    }
    private static List<Case> cases;
    private static int current,frames;
    private static boolean started,completed;
    private VisualCaptureHarness() {}
    private static TerminalData fixture(int level,int held,boolean active,boolean online) {
        return new TerminalData("valewood","farmer",level,level==1?0:125,60,online,
            active?UUID.fromString("00000000-0000-0000-0000-000000000001"):null,0,"",
            List.of(new TerminalData.Row("underground_city:bread","Bake the canteen bread order",2,36,180,4,active,0,
                List.of(new TerminalData.Req("minecraft:bread",8,held)))),List.of());
    }
    private static final List<TerminalData.Offer> IRONHAVEN_OFFERS=List.of(
        new TerminalData.Offer("underground_city:machined_parts","underground_city:machined_parts",18,16,36,8),
        new TerminalData.Offer("underground_city:refined_alloy","underground_city:refined_alloy",28,14,30,8),
        new TerminalData.Offer("underground_city:security_fabric","underground_city:security_fabric",22,0,24,8));
    private static TerminalData exchange(String region,double balance) {
        return new TerminalData("valewood","farmer",3,520,balance,true,null,region.isEmpty()?0:1,region,
            List.of(),region.isEmpty()?List.of():IRONHAVEN_OFFERS);
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
    private static final java.util.Map<String,String> SHORT_TITLES=new java.util.HashMap<>();
    static { for(SkillNode node:realTree("farmer")) SHORT_TITLES.put(node.id(),node.shortTitle()); }
    private static TerminalData.SkillRow node(String id,String branch,String title,String text,int cost,int level,int state,String reason,String... requires) {
        java.util.Map<String,String> ids=java.util.Map.of("Greenhouse Management","farmer.greenhouse_management","Cultivar Broker","farmer.cultivar_broker",
            "Restricted Cultivation","farmer.restricted_cultivation","Underground Trade","farmer.underground_trade","Field Fundamentals","farmer.fundamentals");
        return new TerminalData.SkillRow("farmer."+id,branch,title,text,cost,level,state,reason,java.util.Arrays.stream(requires).map(name->ids.getOrDefault(name,name)).toList(),SHORT_TITLES.getOrDefault("farmer."+id,title));
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
                state,state==2||blocker==null?"":blocker,requires,node.shortTitle()));
        }
        return new TerminalData("valewood",career,level,5200,230,true,null,1,"valewood",List.of(),List.of(),free,rows,List.of(),VALEWOOD_LAW,0,"");
    }
    /** A level-9 farmer with six of eight points spent: legal and underground branches both partly learned. */
    private static List<TerminalData.SkillRow> farmerTree() {
        return List.of(
            node("greenhouse_management","legal","Greenhouse Management","Run a proper greenhouse. Unlocks wholesale greenhouse supply contracts.",2,3,2,""),
            node("cultivar_broker","legal","Cultivar Broker","Trade premium cultivars. Unlocks the heirloom seed contract and its seed-packet reward.",2,5,1,"","Greenhouse Management"),
            node("estate_wholesale","legal","Estate Wholesale","Supply estates and kitchens at contract rates. Unlocks the estate pantry contract.",3,8,0,"Requires Cultivar Broker","Cultivar Broker"),
            node("controlled_crop_licence","legal","Controlled-Crop Licence","A handler's licence. Where a city issues permits, controlled crops are legal for you to carry.",2,10,0,"Career level 10 required","Restricted Cultivation"),
            node("fundamentals","core","Field Fundamentals","Soil, seed and season. Opens every other farming skill.",1,2,2,""),
            node("restricted_cultivation","underground","Restricted Cultivation","Raise controlled cultivars under grow lamps. Needed to plant Sable Leaf cuttings.",3,6,2,"","Greenhouse Management"),
            node("concealed_stores","underground","Concealed Stores","False-bottomed crates: 12 points off the chance that a checkpoint search finds anything.",2,8,1,"","Restricted Cultivation"),
            node("underground_trade","underground","Underground Trade","Open a line to a Blackwater buyer. Unlocks quiet bale delivery contracts.",3,9,0,"Needs 3 skill points (2 available)","Restricted Cultivation"),
            node("grey_market_contacts","underground","Grey Market Contacts","A trusted buyer takes bigger loads. Unlocks the reserve contract (needs trust 2).",3,12,0,"Requires Underground Trade","Underground Trade"),
            node("field_logistics","utility","Field Logistics","Buy in bulk: 5% off goods at the regional exchange.",1,4,1,"","Field Fundamentals"));
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
        List.of("2 x Sable Leaf Cutting"));
    private static TerminalData.Row undergroundRow(String id,String title,int level,int credits,int xp,int count,int held,boolean accepted,TerminalData.Extra extra) {
        return new TerminalData.Row("underground_city:"+id,title,level,credits,xp,2,accepted,0,
            List.of(new TerminalData.Req("underground_city:sable_leaf_bale",count,held)),extra);
    }
    private static final TerminalData.Row LICENSED_ROW=new TerminalData.Row("underground_city:licensed_cultivar_supply","Licensed controlled-crop supply",10,200,400,2,false,0,
        List.of(new TerminalData.Req("underground_city:sable_leaf_bale",6,0)),
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
            ?List.of(new TerminalData.Contact("marrow","Marrow, grey-market broker","blackwater",true,"Pays for Sable Leaf bales, no questions, within limits.",1),
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
            undergroundRow("quiet_bales","Quiet bale delivery",9,330,380,6,accepted?held:0,accepted,QUIET_BALES),
            undergroundRow("reserve_load","Reserve load for the estate trade",12,700,600,12,0,false,RESERVE_LOAD));
        return new TerminalData("valewood","farmer",9,10125,230,true,accepted?UUID.fromString("00000000-0000-0000-0000-000000000003"):null,
            1,region,jobs,List.of(),2,farmerTree(),contacts,laws,region.isEmpty()?0:2,
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
            case "dialogue-employer" -> new DialogueScreen(-1,"Odette Marsh","Employer: Farmer and vintner","valewood",
                "The canteen wants bread again, the greenhouses want hands, and the co-op pays on delivery. Come through to the office and we will go over the orders.",
                List.of(c.apply("orders","Open my orders"),c.apply("demand","How does demand work?"),c.apply("bye","Goodbye")),true);
            default -> new DialogueScreen(-1,"Sergeant Halloran","Gate guard","valewood",
                "Modest patrols here. Hunting arms are licensed; controlled crops and unregistered parts are prohibited. Keep your papers where I can see them.",
                List.of(c.apply("law","What is the law here?"),c.apply("posts","Where are the checkpoints?"),c.apply("search","How do searches work?"),c.apply("bye","Goodbye")),true);
        };
    }
    private static void start(Minecraft mc) {
        int scale=Integer.getInteger("uc.visualCapture.scale",3);
        if(scale<1||scale>4) throw new IllegalArgumentException("Bad GUI scale");
        mc.options.guiScale().set(scale); mc.resizeDisplay();
        cases=new ArrayList<>();
        for(var page:TerminalScreen.Page.values()) {
            boolean rich=page==TerminalScreen.Page.SKILLS || page==TerminalScreen.Page.BLACK_MARKET || page==TerminalScreen.Page.GUIDE;
            cases.add(new Case("uc-terminal-"+page.name().toLowerCase(java.util.Locale.ROOT),page,
                rich?rich("valewood",false,0,VALEWOOD_LAW):fixture(2,8,false,true),0,false,page==TerminalScreen.Page.SKILLS?"farmer.restricted_cultivation":null));
        }
        cases.add(new Case("uc-skills-smith",TerminalScreen.Page.SKILLS,careerSkills("smith",11,java.util.Set.of("smith.fundamentals","smith.structural_works","smith.armoury_contracts","smith.unlisted_fabrication")),0,false,"smith.contraband_armoury"));
        cases.add(new Case("uc-skills-gunsmith",TerminalScreen.Page.SKILLS,careerSkills("gunsmith",11,java.util.Set.of("gunsmith.fundamentals","gunsmith.licensed_repairs","gunsmith.dealer_licence")),0,false,"gunsmith.unregistered_builds"));
        cases.add(new Case("uc-skills-technician",TerminalScreen.Page.SKILLS,careerSkills("technician",11,java.util.Set.of("technician.fundamentals","technician.municipal_repairs","technician.automation_install")),0,false,"technician.bypass_tooling"));
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
        cases.add(new Case("uc-contract-regional",TerminalScreen.Page.CONTRACTS,regionalContract(true,2,1),0));
        cases.add(new Case("uc-contract-visit-locked",TerminalScreen.Page.CONTRACTS,regionalContract(false,0,0),0));
        if(!QShopEconomy.available()) throw new IllegalStateException("Visual coverage requires installed Q Shop 1.8.4");
        cases.add(Case.registration("uc-register-city","city"));
        cases.add(Case.registration("uc-register-career","career"));
        cases.add(Case.registration("uc-register-confirm","confirm"));
        cases.add(Case.challenge("uc-challenge-show","show"));
        cases.add(Case.challenge("uc-challenge-input","input"));
        cases.add(Case.challenge("uc-challenge-urgent","urgent"));
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
            mc.setScreen(screen);
            if(capture.shop==3) QShopVisualCases.openLowFundsModal(screen);
        } else if(capture.challenge!=null && capture.challenge.startsWith("register-")) {
            mc.setScreen(switch(capture.challenge) {
                case "register-city" -> RegistrationScreen.forCapture(0,-1,-1,"");
                case "register-career" -> RegistrationScreen.forCapture(1,1,-1,"");
                default -> RegistrationScreen.forCapture(2,1,0,"");
            });
        } else if(capture.challenge!=null && capture.challenge.startsWith("dialogue-")) {
            mc.setScreen(dialogueCapture(capture.challenge));
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
        }
        frames=0;
    }
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
        if(++frames<12) return;
        String filename=cases.get(current).name+".png";
        Screenshot.grab(mc.gameDirectory,filename,mc.getMainRenderTarget(),
            response->System.out.println("[UC-VISUAL] Captured "+filename));
        current++;
        if(current<cases.size()) show(mc);
    }
}
