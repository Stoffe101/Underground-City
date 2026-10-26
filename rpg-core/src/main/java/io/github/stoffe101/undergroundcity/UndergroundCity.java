package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.material.MapColor;

import java.util.Optional;
import java.util.TreeSet;

@Mod(UndergroundCity.MOD_ID)
public final class UndergroundCity {
    public static final String MOD_ID = "underground_city";
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Block> TERMINAL = BLOCKS.register("operations_terminal",
        () -> new TerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
            .strength(2.5f).noOcclusion()));
    public static final RegistryObject<Item> TERMINAL_ITEM = ITEMS.register("operations_terminal",
        () -> new BlockItem(TERMINAL.get(), new Item.Properties()));

    /** A city's public noticeboard of legal work (read-only). Placed by the district builder or operators; unbreakable in survival like the other district fittings. */
    public static final RegistryObject<Block> PUBLIC_LEDGER = BLOCKS.register("public_ledger",
        () -> new PublicLedgerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0f, 3_600_000f).noLootTable().noOcclusion()));
    public static final RegistryObject<Item> PUBLIC_LEDGER_ITEM = ITEMS.register("public_ledger",
        () -> new BlockItem(PUBLIC_LEDGER.get(), new Item.Properties()));

    /** The Farmer's controlled crop (a real crop block; its seed item is registered with the other Core items). */
    public static final RegistryObject<Block> CANNABIS_PLANT = BLOCKS.register("cannabis_plant",
        () -> new RestrictedCropBlock(BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.WHEAT)));

    /** The Technician's job site: placed by operators or the district builder, never craftable or breakable by survival players. */
    public static final RegistryObject<Block> SECURE_TERMINAL = BLOCKS.register("secure_data_terminal",
        () -> new SecureTerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
            .strength(-1.0f, 3_600_000f).noLootTable().noOcclusion().lightLevel(state -> 6)));
    public static final RegistryObject<Item> SECURE_TERMINAL_ITEM = ITEMS.register("secure_data_terminal",
        () -> new BlockItem(SECURE_TERMINAL.get(), new Item.Properties()));

    /** A furnished building's alarm panel (D-128): placed by the furnished buildings beside the street door, its light set by the server; never craftable or breakable in survival. */
    public static final RegistryObject<Block> ALARM_PANEL = BLOCKS.register("alarm_panel",
        () -> new AlarmPanelBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(-1.0f, 3_600_000f).noLootTable().noOcclusion()
            .lightLevel(state -> switch (state.getValue(AlarmPanelBlock.LIGHT)) { case OFF -> 0; case ARMED -> 3; case ENTRY -> 6; case JAMMED -> 4; })));
    public static final RegistryObject<Item> ALARM_PANEL_ITEM = ITEMS.register("alarm_panel",
        () -> new BlockItem(ALARM_PANEL.get(), new Item.Properties()));

    /**
     * A heap of scrap at a salvage yard (D-138): stripped with a pickaxe for metal and parts (its loot table), and filled again by the yard's resource site in active play time only
     * ({@link ResourceSites}). It never drops itself, so a player cannot carry a heap home and farm it.
     */
    public static final RegistryObject<Block> SCRAP_HEAP = BLOCKS.register("scrap_heap",
        () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN).strength(1.8f, 6.0f).requiresCorrectToolForDrops()
            .sound(net.minecraft.world.level.block.SoundType.METAL)));
    public static final RegistryObject<Item> SCRAP_HEAP_ITEM = ITEMS.register("scrap_heap",
        () -> new BlockItem(SCRAP_HEAP.get(), new Item.Properties()));
    // the Technician's blocks (D-158): a building's utility panel (placed by Core), the personal computer and the electronics bench
    public static final RegistryObject<Block> UTILITY_PANEL = BLOCKS.register("utility_panel",
        () -> new TechBlocks.UtilityPanel(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.METAL).strength(5.0f, 1200.0f).requiresCorrectToolForDrops().sound(net.minecraft.world.level.block.SoundType.METAL)));
    public static final RegistryObject<Item> UTILITY_PANEL_ITEM = ITEMS.register("utility_panel", () -> new BlockItem(UTILITY_PANEL.get(), new Item.Properties()));
    // the Smith's machinery (eighth pass): a building's drive housing, placed by Core beside the street doors of industrial buildings and the shops that run on a machine
    public static final RegistryObject<Block> MACHINE_DRIVE = BLOCKS.register("machine_drive",
        () -> new MachineBlock(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.METAL).strength(5.0f, 1200.0f).requiresCorrectToolForDrops().sound(net.minecraft.world.level.block.SoundType.METAL)));
    // exploration finds (eighth pass, D-186): a battered crate Core sets into roadside places and wayside shelters; each player finds something in it once
    public static final RegistryObject<Block> EXPLORATION_FIND = BLOCKS.register("exploration_find",
        () -> new ExplorationFinds.FindBlock(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(-1.0f, 3600000.0f).noLootTable().sound(net.minecraft.world.level.block.SoundType.WOOD)));
    public static final RegistryObject<Block> PERSONAL_COMPUTER = BLOCKS.register("personal_computer",
        () -> new TechBlocks.Computer(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.SAND).strength(1.5f).noOcclusion().sound(net.minecraft.world.level.block.SoundType.METAL)));
    public static final RegistryObject<Item> PERSONAL_COMPUTER_ITEM = ITEMS.register("personal_computer", () -> new BlockItem(PERSONAL_COMPUTER.get(), new Item.Properties()));
    public static final RegistryObject<Block> ELECTRONICS_BENCH = BLOCKS.register("electronics_bench",
        () -> new TechBlocks.Bench(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.0f).sound(net.minecraft.world.level.block.SoundType.WOOD)));
    public static final RegistryObject<Item> ELECTRONICS_BENCH_ITEM = ITEMS.register("electronics_bench", () -> new BlockItem(ELECTRONICS_BENCH.get(), new Item.Properties()));
    // the Farmer's drying rack (D-160): two Cannabis Flower dry into one Dried Cannabis in six minutes of play
    public static final RegistryObject<Block> DRYING_RACK = BLOCKS.register("drying_rack",
        () -> new DryingRack.Rack(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(1.0f).noOcclusion().sound(net.minecraft.world.level.block.SoundType.WOOD)));
    public static final RegistryObject<Item> DRYING_RACK_ITEM = ITEMS.register("drying_rack", () -> new BlockItem(DRYING_RACK.get(), new Item.Properties()));
    // the workshops of the trades (D-163): one block a station; its tiers and recipes are data (data/underground_city/workshops)
    static final String[] WORKSHOPS = {"grading_table", "mill", "fermentation_vat", "oak_cask", "pot_still", "machinist_bench", "gunsmith_bench"};
    public static final java.util.Map<String, RegistryObject<Block>> WORKSHOP_BLOCKS = new java.util.LinkedHashMap<>();
    public static final java.util.Map<String, RegistryObject<Item>> WORKSHOP_ITEMS = new java.util.LinkedHashMap<>();
    static {
        for (String id : WORKSHOPS) {
            boolean metal = id.equals("pot_still"), stone = id.equals("mill");
            RegistryObject<Block> block = BLOCKS.register(id, () -> new WorkshopBlock(id, WorkshopBlock.shapeOf(id), BlockBehaviour.Properties.of()
                .mapColor(metal ? net.minecraft.world.level.material.MapColor.COLOR_ORANGE : stone ? net.minecraft.world.level.material.MapColor.STONE : net.minecraft.world.level.material.MapColor.WOOD)
                .strength(2.0f).noOcclusion().sound(metal ? net.minecraft.world.level.block.SoundType.COPPER : stone ? net.minecraft.world.level.block.SoundType.STONE : net.minecraft.world.level.block.SoundType.WOOD)));
            WORKSHOP_BLOCKS.put(id, block);
            WORKSHOP_ITEMS.put(id, ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties())));
        }
    }
    /** A courier's sealed letter from a roadside encounter (D-139): pays its fee once, at any terminal or counter in the city it is addressed to. */
    public static final RegistryObject<Item> SEALED_ENVELOPE = ITEMS.register("sealed_envelope", () -> new EnvelopeItem(new Item.Properties().stacksTo(1)));

    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);
    // Unbreakable by survival players: a smash-and-grab would bypass lock, vigilance and restock rules.
    public static final RegistryObject<Block> PROPERTY_CACHE = BLOCKS.register("property_cache",
        () -> new PropertyCacheBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
            .strength(-1.0f, 3_600_000f).noLootTable().noOcclusion()));
    public static final RegistryObject<Item> PROPERTY_CACHE_ITEM = ITEMS.register("property_cache",
        () -> new BlockItem(PROPERTY_CACHE.get(), new Item.Properties()));
    public static final RegistryObject<net.minecraft.world.level.block.entity.BlockEntityType<PropertyCacheBlockEntity>> PROPERTY_CACHE_ENTITY =
        BLOCK_ENTITIES.register("property_cache", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(PropertyCacheBlockEntity::new, PROPERTY_CACHE.get()).build(null));

    public static final RegistryObject<net.minecraft.world.level.block.entity.BlockEntityType<WorkshopBlock.Entity>> WORKSHOP_ENTITY =
        BLOCK_ENTITIES.register("workshop", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(WorkshopBlock.Entity::new, WORKSHOP_BLOCKS.values().stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));
    public static final RegistryObject<net.minecraft.world.level.block.entity.BlockEntityType<DryingRack.Entity>> DRYING_RACK_ENTITY =
        BLOCK_ENTITIES.register("drying_rack", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(DryingRack.Entity::new, DRYING_RACK.get()).build(null));

    public static final RegistryObject<net.minecraft.world.level.block.entity.BlockEntityType<SecureTerminalBlockEntity>> SECURE_TERMINAL_ENTITY =
        BLOCK_ENTITIES.register("secure_data_terminal", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(SecureTerminalBlockEntity::new, SECURE_TERMINAL.get()).build(null));

    private static final DeferredRegister<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES =
        DeferredRegister.create(ForgeRegistries.FEATURES, MOD_ID);

    private static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    /** An ambient person of a city (D-108): a body for a roster entry. Never saved, never spawned by the game itself. */
    public static final RegistryObject<net.minecraft.world.entity.EntityType<TownspersonEntity>> TOWNSPERSON = ENTITIES.register("townsperson",
        () -> net.minecraft.world.entity.EntityType.Builder.of(TownspersonEntity::new, net.minecraft.world.entity.MobCategory.MISC).sized(0.6f, 1.8f).clientTrackingRange(8).updateInterval(3).build(MOD_ID + ":townsperson"));
    /** Inter-city roads and bridges; only active where the world prototype datapack places it in the Overworld. */
    public static final RegistryObject<net.minecraft.world.level.levelgen.feature.Feature<?>> CORRIDOR_ROADS =
        FEATURES.register("corridor_roads", CorridorRoadFeature::new);

    /** Registry lookup of an original Core item by path. */
    public static Item item(String path) {
        RegistryObject<Item> registered = CORE_ITEMS.get(path);
        if (registered == null) throw new IllegalArgumentException("Unknown Core item " + path);
        return registered.get();
    }


    /** How many uses a durable Core tool has: the Pro Pick Set takes one per lock opened and four when a pin snaps (D-143). */
    static final int DURABLE_USES = 40;
    private static final java.util.Map<String, RegistryObject<Item>> CORE_ITEMS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String> CORE_ITEM_KINDS = new java.util.LinkedHashMap<>();

    public UndergroundCity(FMLJavaModLoadingContext context) {
        for (CoreItems.Definition definition : CoreItems.definitions()) {
            CORE_ITEMS.put(definition.id(), ITEMS.register(definition.id(), () -> definition.kind().equals("seed")
                ? new RestrictedCuttingItem(CANNABIS_PLANT.get(), new Item.Properties().stacksTo(definition.stack()))
                : definition.kind().equals("durable") ? new Item(new Item.Properties().durability(DURABLE_USES))   // the Pro Pick Set (D-143) wears with use
                : new Item(new Item.Properties().stacksTo(definition.stack()))));
            CORE_ITEM_KINDS.put(definition.id(), definition.kind());
        }
        BLOCKS.register(context.getModEventBus());
        BLOCK_ENTITIES.register(context.getModEventBus());
        FEATURES.register(context.getModEventBus());
        ENTITIES.register(context.getModEventBus());
        context.getModEventBus().addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent event) -> event.put(TOWNSPERSON.get(), TownspersonEntity.attributes().build()));
        LostCitiesLink.register(context.getModEventBus());
        RegionalWorldgen.register(context.getModEventBus());
        PrivateContent.register(context.getModEventBus());   // privately licensed packs from a folder outside the checkout, if one is configured (D-116)
        RenamedIds.register(context.getModEventBus());       // the crop's old ids load as Cannabis in older saves (D-142)
        ITEMS.register(context.getModEventBus());
        context.getModEventBus().addListener(this::addCreativeTab);
        TerminalNetwork.register();
        CrewNetwork.register();
        LockNetwork.register();
        BurglaryNetwork.register();
        PlacesNetwork.register();
        NavigationNetwork.register();
        WorkshopNetwork.register();
        TechNetwork.register();
        SmithNetwork.register();
        FenceNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new RenamedIds());
        MinecraftForge.EVENT_BUS.register(new BurglarySearch());
        MinecraftForge.EVENT_BUS.register(new SecurityDevices());
        MinecraftForge.EVENT_BUS.register(new WorkOrderService());
        MinecraftForge.EVENT_BUS.register(new SupplierNpc());
        MinecraftForge.EVENT_BUS.register(new PropertyCommands());
        MinecraftForge.EVENT_BUS.register(new PrepareCommands());
        MinecraftForge.EVENT_BUS.register(new DistrictBuilder());
        MinecraftForge.EVENT_BUS.register(new CityNpcs());
        MinecraftForge.EVENT_BUS.register(new CheckpointPosts());
        MinecraftForge.EVENT_BUS.register(new OrderTracker());
        MinecraftForge.EVENT_BUS.register(new Journey());
        MinecraftForge.EVENT_BUS.register(new DevBootstrap());
        MinecraftForge.EVENT_BUS.register(new CityArrival());   // a new citizen is moved, once, to the welcome of the city they chose (D-187)
        // the roads are planned in the background as the overworld loads, not on the server thread's first road chunk (a nine-second freeze mid-play)
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.LevelEvent.Load event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) CorridorRoadFeature.prepare(level);
        });
        MinecraftForge.EVENT_BUS.register(new TerrainMapHarness());
        MinecraftForge.EVENT_BUS.register(new CityProbe());
        MinecraftForge.EVENT_BUS.register(new OreAudit());
        MinecraftForge.EVENT_BUS.register(new RoadCheck());
        MinecraftForge.EVENT_BUS.register(new BurglaryHarness());
        MinecraftForge.EVENT_BUS.register(new PopulationService());
        MinecraftForge.EVENT_BUS.register(new PoliceResponse());
        MinecraftForge.EVENT_BUS.register(new CrimeWatch());   // who sees an assault, a lock being worked, an intruder, a search or damage (D-189)
        MinecraftForge.EVENT_BUS.register(new BurglaryDamage());   // what a burglar breaks, and the city putting it back (D-190)
        MinecraftForge.EVENT_BUS.register(new FurnitureGuard());   // the furniture set's mail boxes deliver nothing (D-192)
        MinecraftForge.EVENT_BUS.register(new ChunkPregen());   // the cities generated ahead of play (/uc admin pregen, -PucPregen)
        MinecraftForge.EVENT_BUS.register(new PolicePatrols());
        MinecraftForge.EVENT_BUS.register(new PropertyIntel());
        MinecraftForge.EVENT_BUS.register(new TechService());
        MinecraftForge.EVENT_BUS.register(new Navigation());
        MinecraftForge.EVENT_BUS.register(new TechWork());
        MinecraftForge.EVENT_BUS.register(new MachineService());
        MinecraftForge.EVENT_BUS.register(new SmithWork());
        MinecraftForge.EVENT_BUS.register(new ExplorationFinds.Service());
        MinecraftForge.EVENT_BUS.register(new TechWork.SneakUse());   // a crouched toolkit click reaches a utility panel (the back channel, D-166)
        MinecraftForge.EVENT_BUS.register(new StaffZones());
        MinecraftForge.EVENT_BUS.register(new ResourceSites());
        MinecraftForge.EVENT_BUS.register(new HouseholdService());
        MinecraftForge.EVENT_BUS.register(new HudService());
        if (net.minecraftforge.fml.ModList.get().isLoaded("qshop")) MinecraftForge.EVENT_BUS.register(new QShopTradeWatcher());
        MinecraftForge.EVENT_BUS.register(WorldLayout.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new GenerationPackGuard());
        CityCharacter.register(MinecraftForge.EVENT_BUS);
        MinecraftForge.EVENT_BUS.register(new CheckpointService());
        MinecraftForge.EVENT_BUS.register(new SecureTerminalService());
        MinecraftForge.EVENT_BUS.register(new AlarmPanels());
        MinecraftForge.EVENT_BUS.register(new RoadApproaches());
        MinecraftForge.EVENT_BUS.register(new CrewService());
        MinecraftForge.EVENT_BUS.register(new PropertyService());
        MinecraftForge.EVENT_BUS.register(new CrewCommands());
        MinecraftForge.EVENT_BUS.register(new IdentityAudit());
        MinecraftForge.EVENT_BUS.register(new ResidenceGuard());
        MinecraftForge.EVENT_BUS.register(new BuildingGuard());
        MinecraftForge.EVENT_BUS.register(new LockService());
        MinecraftForge.EVENT_BUS.register(new BackpackPolicy());
        // the world's rule about hostile creatures (D-094): SIMULATION unless an operator chooses CLASSIC; the default lives in code, so no file has to exist
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, SimulationWorld.SPEC, "underground_city-server.toml");
        // the player's own choices for the on-screen status (client side only; the file is never read by a server)
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, HudConfig.SPEC, "underground_city-client.toml");
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> ClientHud::registerSettingsScreen);
        context.getModEventBus().addListener(SimulationWorld::reloaded);
        MinecraftForge.EVENT_BUS.register(new SimulationWorld());
        RangeService.register();
    }

    @SubscribeEvent
    public void reload(AddReloadListenerEvent event) {
        event.addListener(WorkOrderCatalog.INSTANCE);
        event.addListener(RegionalCatalog.INSTANCE);
        event.addListener(CityRegions.INSTANCE);
        event.addListener(CityArrival.Loader.INSTANCE);
        event.addListener(BurglaryDamage.Loader.INSTANCE);
        event.addListener(CityCharacter.INSTANCE);
        event.addListener(ResourceSiteCatalog.INSTANCE);
        event.addListener(BuildingSpots.INSTANCE);
        event.addListener(WorldLayout.INSTANCE);
        event.addListener(PropertyPolicies.INSTANCE);
        event.addListener(ExceptionalLoot.INSTANCE);
        event.addListener(SkillCatalog.INSTANCE);
        event.addListener(TechDesigns.INSTANCE);
        event.addListener(Workshops.INSTANCE);
        event.addListener(MachineService.Loader.INSTANCE);
        event.addListener(BusinessHours.Loader.INSTANCE);
        event.addListener(ExplorationFinds.Loader.INSTANCE);
        event.addListener(SkillCatalog.Specializations.INSTANCE);
        event.addListener(PropertyCatalog.INSTANCE);
        event.addListener(ContrabandCatalog.INSTANCE);
        event.addListener(CityLaws.INSTANCE);
        event.addListener(BuyerCatalog.INSTANCE);
        event.addListener(CheckpointCatalog.INSTANCE);
        event.addListener(DistrictCatalog.INSTANCE);
        event.addListener(StarterKits.INSTANCE);
        event.addListener(LockProfiles.INSTANCE);
        event.addListener(BurglarySearch.LOADER);
        event.addListener(CityBuildings.INSTANCE);
        event.addListener(PopulationCatalog.INSTANCE);
        event.addListener(FenceCatalog.INSTANCE);
        event.addListener(ShopCounters.INSTANCE);
        event.addListener(RoadsidePlaces.CATALOG);
        event.addListener(RoadEncounters.CATALOG);
        event.addListener(CrewContracts.CATALOG);
        event.addListener(CivicProjects.CATALOG);
    }

    private void addCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(TERMINAL_ITEM);
            event.accept(PUBLIC_LEDGER_ITEM);
            event.accept(PERSONAL_COMPUTER_ITEM);
            event.accept(ELECTRONICS_BENCH_ITEM);
            event.accept(DRYING_RACK_ITEM);
            WORKSHOP_ITEMS.values().forEach(event::accept);
        }
        if (event.getTabKey() == CreativeModeTabs.OP_BLOCKS && event.hasPermissions()) {
            event.accept(PROPERTY_CACHE_ITEM);
            event.accept(SECURE_TERMINAL_ITEM);
            event.accept(UTILITY_PANEL_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            CORE_ITEMS.forEach((id, item) -> { if (!CORE_ITEM_KINDS.get(id).equals("tool")) event.accept(item); });
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            CORE_ITEMS.forEach((id, item) -> { if (CORE_ITEM_KINDS.get(id).equals("tool")) event.accept(item); });
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            WorkOrderService.recover(player);
        } catch (RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().error("Citizen settlement recovery failed", error);
            player.connection.disconnect(Component.literal("Work-order recovery failed. Restore provider/storage and reconnect."));
            return;
        }
        Optional<CitizenState.Citizen> profile = CitizenState.get(player.server).find(player.getUUID());
        if (profile.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Welcome to Underground City! Choose your city and profession on the screen that just opened (/uc start opens it again)."
            ));
            RegistrationService.prompt(player);
        } else {
            player.sendSystemMessage(Component.literal("Welcome back to " + CityArrival.title(profile.get().city) +
                " (" + profile.get().career + ", level " + profile.get().careerLevel() + ")."));
            RegistrationService.teachRecipes(player);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("uc");
        root.then(Commands.literal("register")
            .then(Commands.argument("city", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.CITIES, builder))
                .then(Commands.argument("career", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.CAREERS, builder))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        String city = StringArgumentType.getString(ctx, "city").toLowerCase(java.util.Locale.ROOT);
                        String career = StringArgumentType.getString(ctx, "career").toLowerCase(java.util.Locale.ROOT);
                        if (!ProgressionRules.validCity(city) || !ProgressionRules.validCareer(career)) {
                            ctx.getSource().sendFailure(Component.literal("Unknown city or profession."));
                            return 0;
                        }
                        if (RegistrationService.choose(player, city, career) != RegistrationService.Outcome.REGISTERED) {
                            ctx.getSource().sendFailure(Component.literal("Already registered. Existing identity unchanged."));
                            return 0;
                        }
                        return 1;
                    })
                )
            )
        );

        root.then(Commands.literal("start").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (!RegistrationService.prompt(player)) {
                ctx.getSource().sendFailure(Component.literal("You are already registered."));
                return 0;
            }
            return 1;
        }));
        root.then(Commands.literal("status").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Optional<CitizenState.Citizen> profile = CitizenState.get(player.server).find(player.getUUID());
            if (profile.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("Not registered. Use /uc register."));
                return 0;
            }
            CitizenState.Citizen citizen = profile.get();
            ctx.getSource().sendSuccess(() -> Component.literal("City: " + citizen.city
                + " | Career: " + citizen.career + " | Level: " + citizen.careerLevel()
                + " | XP: " + citizen.careerXp()), false);
            return 1;
        }));

        root.then(Commands.literal("skills").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Optional<CitizenState.Citizen> profile = CitizenState.get(player.server).find(player.getUUID());
            if (profile.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("Not registered."));
                return 0;
            }
            CitizenState.Citizen who = profile.get();
            ctx.getSource().sendSuccess(() -> Component.literal("Career skills: " + SkillService.pointsAvailable(who) + " of "
                + SkillService.pointsEarned(who) + " points free; unlocked: " + (who.skills().isEmpty() ? "none" : String.join(", ", who.skills()))
                + ". Spend points at an Operations Terminal (Skills tab)."), false);
            StringBuilder result = new StringBuilder("General skills: ");
            for (String skill : new TreeSet<>(ProgressionRules.GENERAL_SKILLS)) {
                result.append(skill).append(" L").append(profile.get().generalLevel(skill));
                if (skill.equals(GeneralSkills.TRADING) || skill.equals(GeneralSkills.SOCIAL)) result.append(" (").append(GeneralSkills.gives(skill, profile.get().generalLevel(skill))).append(")");
                result.append("  ");
            }
            ctx.getSource().sendSuccess(() -> Component.literal(result.toString()), false);
            return 1;
        }));

        LiteralArgumentBuilder<CommandSourceStack> dev = Commands.literal("dev")
            .requires(source -> source.hasPermission(2));
        dev.then(Commands.literal("career_xp")
            .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                    .executes(ctx -> {
                        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                        CitizenState state = CitizenState.get(ctx.getSource().getServer());
                        if (state.find(player.getUUID()).isEmpty()) {
                            ctx.getSource().sendFailure(Component.literal("Player is not registered."));
                            return 0;
                        }
                        int xp = state.addCareerXp(player.getUUID(), IntegerArgumentType.getInteger(ctx, "amount"));
                        ctx.getSource().sendSuccess(() -> Component.literal("Updated career XP: " + xp), true);
                        return 1;
                    })
                )
            )
        );
        dev.then(Commands.literal("general_xp")
            .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("skill", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.GENERAL_SKILLS, builder))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            String skill = StringArgumentType.getString(ctx, "skill");
                            CitizenState state = CitizenState.get(ctx.getSource().getServer());
                            if (!ProgressionRules.GENERAL_SKILLS.contains(skill)
                                    || state.find(player.getUUID()).isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal("Unknown skill or unregistered citizen."));
                                return 0;
                            }
                            int xp = state.addGeneralXp(player.getUUID(), skill,
                                IntegerArgumentType.getInteger(ctx, "amount"));
                            ctx.getSource().sendSuccess(() -> Component.literal("Updated " + skill + " XP: " + xp), true);
                            return 1;
                        })
                    )
                )
            )
        );
        root.then(dev);
        root.then(AdminCommands.branch());   // the operator's commands (D-191)
        event.getDispatcher().register(root);
    }
}
