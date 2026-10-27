package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /uc_prepare}: an operator's way to set up the playtest guide's prepared sessions (Track B, D-179) without a developer, the same shortcuts the scripted runs take and log. It raises a
 * career or Thief level (never lowers one), adds credits through Q Shop, or hands over one of a few named kits. Every use is written to the server log as {@code [UC-PREPARED]} and told to the
 * player in chat, so a report from a prepared session can never be mistaken for an organic one. Nothing here can be reached without permission level 2.
 */
public final class PrepareCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int MAX_LEVEL = 10, MAX_CREDITS = 50_000;

    /** The kits, each what one prepared session of the guide starts with (section 6i, session 3); built when used, after the items are registered. */
    static final List<String> KIT_NAMES = List.of("smith_crate", "technician", "thief", "gunsmith");

    static Map<String, List<ItemStack>> kits() {
        Map<String, List<ItemStack>> kits = new LinkedHashMap<>();
        kits.put("smith_crate", List.of(new ItemStack(Items.IRON_INGOT, 20), new ItemStack(Items.IRON_NUGGET, 48), new ItemStack(Items.REDSTONE, 2)));
        kits.put("technician", List.of(new ItemStack(UndergroundCity.item("repair_kit")), new ItemStack(UndergroundCity.item("bypass_kit"), 2)));
        kits.put("thief", List.of(new ItemStack(UndergroundCity.item("pro_pick_set")), new ItemStack(UndergroundCity.item("lockpick"), 6)));
        kits.put("gunsmith", List.of(new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.IRON_INGOT, 6)));
        return kits;
    }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_prepare").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("level").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
            .executes(ctx -> each(ctx, (player, citizens) -> {
                int level = IntegerArgumentType.getInteger(ctx, "level");
                int need = ProgressionRules.xpAtLevel(level) - citizens.find(player.getUUID()).orElseThrow().careerXp();
                if (need > 0) citizens.addCareerXp(player.getUUID(), need);
                return "career level " + level + (need > 0 ? " (+" + need + " XP)" : " (already there)");
            })))));
        root.then(Commands.literal("thief").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
            .executes(ctx -> each(ctx, (player, citizens) -> {
                int level = IntegerArgumentType.getInteger(ctx, "level");
                int need = ProgressionRules.thiefXpAtLevel(level) - citizens.find(player.getUUID()).orElseThrow().thiefXp();
                if (need > 0) citizens.addThiefXp(player.getUUID(), need);
                return "Thief level " + level + (need > 0 ? " (+" + need + " Thief XP)" : " (already there)");
            })))));
        root.then(Commands.literal("credits").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_CREDITS))
            .executes(ctx -> each(ctx, (player, citizens) -> {
                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                QShopEconomy.applyBalance(player, QShopEconomy.balance(player) + amount);
                return "+" + amount + " credits";
            })))));
        root.then(Commands.literal("kit").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("kit", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KIT_NAMES, builder))
            .executes(ctx -> each(ctx, (player, citizens) -> {
                String name = StringArgumentType.getString(ctx, "kit");
                List<ItemStack> kit = kits().get(name);
                if (kit == null) throw new IllegalStateException("Unknown kit " + name + "; the kits are " + String.join(", ", KIT_NAMES));
                StringBuilder given = new StringBuilder("the " + name + " kit:");
                for (ItemStack stack : kit) {
                    given.append(' ').append(stack.getCount()).append(' ').append(stack.getHoverName().getString()).append(',');
                    if (!player.getInventory().add(stack.copy())) player.drop(stack.copy(), false);
                }
                return given.substring(0, given.length() - 1);
            })))));
        event.getDispatcher().register(root);
    }

    private interface Step { String apply(ServerPlayer player, CitizenState citizens); }

    private static int each(CommandContext<CommandSourceStack> ctx, Step step) {
        try {
            Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
            CitizenState citizens = CitizenState.get(ctx.getSource().getServer());
            int done = 0;
            for (ServerPlayer player : players) {
                if (citizens.find(player.getUUID()).isEmpty()) { ctx.getSource().sendFailure(Component.literal(player.getGameProfile().getName() + " has not registered yet.")); continue; }
                String what = step.apply(player, citizens);
                LOGGER.info("[UC-PREPARED] {} gave {} {}", ctx.getSource().getTextName(), player.getGameProfile().getName(), what);
                player.sendSystemMessage(Component.literal("PREPARED (Track B): " + what + ", set up by " + ctx.getSource().getTextName() + ".").withStyle(ChatFormatting.GOLD));
                ctx.getSource().sendSuccess(() -> Component.literal("Prepared " + player.getGameProfile().getName() + ": " + what), true);
                done++;
            }
            return done;
        } catch (IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }
}
