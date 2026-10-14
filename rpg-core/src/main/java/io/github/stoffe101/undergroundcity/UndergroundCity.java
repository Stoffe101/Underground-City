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


    public UndergroundCity() {
        BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::addCreativeTab);
        TerminalNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void addCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(TERMINAL_ITEM);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Optional<CitizenState.Citizen> profile = CitizenState.get(player.server).find(player.getUUID());
        if (profile.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Welcome to Underground City! Register with /uc register <city> <career>."
            ));
            player.sendSystemMessage(Component.literal(
                "Cities: valewood, ironhaven, blackwater. Careers: farmer, smith, gunsmith, technician."
            ));
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
                        if (!CitizenState.get(player.server).register(player.getUUID(), city, career)) {
                            ctx.getSource().sendFailure(Component.literal("Already registered. Existing identity unchanged."));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("Registered with " + city + " as " + career + "."), false);
                        return 1;
                    })
                )
            )
        );

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
