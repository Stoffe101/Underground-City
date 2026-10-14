package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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

import java.util.Optional;

/**
 * Initial playable server core: first-time registration and persistent XP.
 * Temporary slash commands will later be replaced by the onboarding and
 * character interfaces. No external mod dependencies.
 */
@Mod(UndergroundCity.MOD_ID)
public final class UndergroundCity {
    public static final String MOD_ID = "underground_city";

    public UndergroundCity() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Optional<CitizenState.Citizen> profile = CitizenState.get(player.server).find(player.getUUID());
        if (profile.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Welcome to Underground City! Register with /uc register <city> <career>."
            ));
            player.sendSystemMessage(Component.literal("Cities: valewood, ironhaven, blackwater. "
                    + "Careers: farmer, smith, gunsmith, technician."));
        } else {
            player.sendSystemMessage(Component.literal("Welcome back to " + profile.get().city
                    + " (" + profile.get().career + ", level " + profile.get().careerLevel() + ")."));
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("uc")
                .then(Commands.literal("register")
                    .then(Commands.argument("city", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.CITIES, builder))
                        .then(Commands.argument("career", StringArgumentType.word())
                            .suggests((context, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.CAREERS, builder))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                String city = StringArgumentType.getString(context, "city").toLowerCase(java.util.Locale.ROOT);
                                String career = StringArgumentType.getString(context, "career").toLowerCase(java.util.Locale.ROOT);
                                CitizenState state = CitizenState.get(player.server);
                                if (!ProgressionRules.validCity(city) || !ProgressionRules.validCareer(career)) {
                                    context.getSource().sendFailure(Component.literal("Unknown city or profession."));
                                    return 0;
                                }
                                if (!state.register(player.getUUID(), city, career)) {
                                    context.getSource().sendFailure(Component.literal(
                                            "Already registered. Identity cannot be overwritten with this command."));
                                    return 0;
                                }
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Registered with " + city + " as a " + career + ". Career level 1."), false);
                                return 1;
                            }))))
                .then(Commands.literal("status")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        Optional<CitizenState.Citizen> optional = CitizenState.get(player.server).find(player.getUUID());
                        if (optional.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("Not registered. Use /uc register."));
                            return 0;
                        }
                        CitizenState.Citizen citizen = optional.get();
                        context.getSource().sendSuccess(() -> Component.literal(
                                "City: " + citizen.city + " | Career: " + citizen.career
                                + " | Level: " + citizen.careerLevel() + " | Career XP: " + citizen.careerXp()), false);
                        return 1;
                    }))
                .then(Commands.literal("skills")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        Optional<CitizenState.Citizen> optional = CitizenState.get(player.server).find(player.getUUID());
                        if (optional.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("Not registered."));
                            return 0;
                        }
                        CitizenState.Citizen citizen = optional.get();
                        StringBuilder display = new StringBuilder("General skills: ");
                        for (String skill : new java.util.TreeSet<>(ProgressionRules.GENERAL_SKILLS)) {
                            display.append(skill).append(" L").append(citizen.generalLevel(skill)).append("  ");
                        }
                        context.getSource().sendSuccess(() -> Component.literal(display.toString()), false);
                        return 1;
                    }))
                .then(Commands.literal("dev").requires(source -> source.hasPermission(2))
                    .then(Commands.literal("career_xp")
                        .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                                .executes(context -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    CitizenState state = CitizenState.get(context.getSource().getServer());
                                    if (state.find(target.getUUID()).isEmpty()) {
                                        context.getSource().sendFailure(Component.literal("Player is not registered."));
                                        return 0;
                                    }
                                    int xp = state.addCareerXp(target.getUUID(), IntegerArgumentType.getInteger(context, "amount"));
                                    context.getSource().sendSuccess(() -> Component.literal("Updated career XP: " + xp), true);
                                    return 1;
                                }))))
                    .then(Commands.literal("general_xp")
                        .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ProgressionRules.GENERAL_SKILLS, builder))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                                    .executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        String skill = StringArgumentType.getString(context, "skill");
                                        if (!ProgressionRules.GENERAL_SKILLS.contains(skill)) {
                                            context.getSource().sendFailure(Component.literal("Unknown general skill."));
                                            return 0;
                                        }
                                        CitizenState state = CitizenState.get(context.getSource().getServer());
                                        if (state.find(target.getUUID()).isEmpty()) {
                                            context.getSource().sendFailure(Component.literal("Player is not registered."));
                                            return 0;
                                        }
                                        int xp = state.addGeneralXp(target.getUUID(), skill,
                                                IntegerArgumentType.getInteger(context, "amount"));
                                        context.getSource().sendSuccess(() -> Component.literal("Updated " + skill + " XP: " + xp), true);
                                        return 1;
                                    })))))
        );
    }
}
