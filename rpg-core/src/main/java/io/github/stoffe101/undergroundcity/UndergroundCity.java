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
    public static final RegistryObject<Block> SABLE_CROP = BLOCKS.register("sable_leaf_crop",
        () -> new RestrictedCropBlock(BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.WHEAT)));

    /** The Technician's job site: placed by operators or the district builder, never craftable or breakable by survival players. */
    public static final RegistryObject<Block> SECURE_TERMINAL = BLOCKS.register("secure_data_terminal",
        () -> new SecureTerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
            .strength(-1.0f, 3_600_000f).noLootTable().noOcclusion().lightLevel(state -> 6)));
    public static final RegistryObject<Item> SECURE_TERMINAL_ITEM = ITEMS.register("secure_data_terminal",
        () -> new BlockItem(SECURE_TERMINAL.get(), new Item.Properties()));

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


    private static final java.util.Map<String, RegistryObject<Item>> CORE_ITEMS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String> CORE_ITEM_KINDS = new java.util.LinkedHashMap<>();

    public UndergroundCity(FMLJavaModLoadingContext context) {
        for (CoreItems.Definition definition : CoreItems.definitions()) {
            CORE_ITEMS.put(definition.id(), ITEMS.register(definition.id(), () -> definition.kind().equals("seed")
                ? new RestrictedCuttingItem(SABLE_CROP.get(), new Item.Properties().stacksTo(definition.stack()))
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
        ITEMS.register(context.getModEventBus());
        context.getModEventBus().addListener(this::addCreativeTab);
        TerminalNetwork.register();
        CrewNetwork.register();
        LockNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new WorkOrderService());
        MinecraftForge.EVENT_BUS.register(new SupplierNpc());
        MinecraftForge.EVENT_BUS.register(new PropertyCommands());
        MinecraftForge.EVENT_BUS.register(new DistrictBuilder());
        MinecraftForge.EVENT_BUS.register(new CityNpcs());
        MinecraftForge.EVENT_BUS.register(new CheckpointPosts());
        MinecraftForge.EVENT_BUS.register(new OrderTracker());
        MinecraftForge.EVENT_BUS.register(new Journey());
        MinecraftForge.EVENT_BUS.register(new DevBootstrap());
        MinecraftForge.EVENT_BUS.register(new TerrainMapHarness());
        MinecraftForge.EVENT_BUS.register(new CityProbe());
        MinecraftForge.EVENT_BUS.register(new OreAudit());
        MinecraftForge.EVENT_BUS.register(new RoadCheck());
        MinecraftForge.EVENT_BUS.register(new BurglaryHarness());
        MinecraftForge.EVENT_BUS.register(new PopulationService());
        MinecraftForge.EVENT_BUS.register(new HudService());
        if (net.minecraftforge.fml.ModList.get().isLoaded("qshop")) MinecraftForge.EVENT_BUS.register(new QShopTradeWatcher());
        MinecraftForge.EVENT_BUS.register(WorldLayout.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new GenerationPackGuard());
        CityCharacter.register(MinecraftForge.EVENT_BUS);
        MinecraftForge.EVENT_BUS.register(new CheckpointService());
        MinecraftForge.EVENT_BUS.register(new SecureTerminalService());
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
        event.addListener(CityCharacter.INSTANCE);
        event.addListener(WorldLayout.INSTANCE);
        event.addListener(PropertyPolicies.INSTANCE);
        event.addListener(ExceptionalLoot.INSTANCE);
        event.addListener(SkillCatalog.INSTANCE);
        event.addListener(PropertyCatalog.INSTANCE);
        event.addListener(ContrabandCatalog.INSTANCE);
        event.addListener(CityLaws.INSTANCE);
        event.addListener(BuyerCatalog.INSTANCE);
        event.addListener(CheckpointCatalog.INSTANCE);
        event.addListener(DistrictCatalog.INSTANCE);
        event.addListener(StarterKits.INSTANCE);
        event.addListener(LockProfiles.INSTANCE);
        event.addListener(CityBuildings.INSTANCE);
        event.addListener(PopulationCatalog.INSTANCE);
    }

    private void addCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(TERMINAL_ITEM);
            event.accept(PUBLIC_LEDGER_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.OP_BLOCKS && event.hasPermissions()) {
            event.accept(PROPERTY_CACHE_ITEM);
            event.accept(SECURE_TERMINAL_ITEM);
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
            player.sendSystemMessage(Component.literal("Welcome back to " + profile.get().city +
                " (" + profile.get().career + ", level " + profile.get().careerLevel() + ")."));
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
                result.append(skill).append(" L").append(profile.get().generalLevel(skill)).append("  ");
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
        event.getDispatcher().register(root);
    }
}
