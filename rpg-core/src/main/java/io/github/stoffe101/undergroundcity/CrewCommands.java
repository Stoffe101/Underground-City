package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/** {@code /crew} opens the Crew screen (the same as the K key); {@code /uc_crew} is the operator's view of the ledger and the only way to settle a money transfer that recovery could not decide. */
public final class CrewCommands {
    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        var crew = Commands.literal("crew").executes(ctx -> {
            CrewNetwork.sendView(ctx.getSource().getPlayerOrException(), true);
            return 1;
        });
        crew.then(Commands.literal("status").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            CrewLedger ledger = CrewService.ledger(player.server);
            Crew mine = ledger == null ? null : ledger.crewOf(player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal(ledger == null ? "Crew storage is unavailable." : mine == null ? "You are not in a Crew."
                : mine.name + ": " + mine.roleOf(player.getUUID()).title + ", " + mine.members.size() + " members, bank " + mine.bank + ", " + mine.effectiveMode().title), false);
            return 1;
        }));
        event.getDispatcher().register(crew);

        var op = Commands.literal("uc_crew").requires(source -> source.hasPermission(2));
        op.then(Commands.literal("list").executes(ctx -> {
            CrewLedger ledger = CrewService.ledger(ctx.getSource().getServer());
            if (ledger == null) { ctx.getSource().sendFailure(Component.literal("Crew storage is unavailable.")); return 0; }
            for (Crew c : ledger.allCrews()) ctx.getSource().sendSuccess(() -> Component.literal(CrewService.describe(c)), false);
            ctx.getSource().sendSuccess(() -> Component.literal(ledger.allCrews().size() + " Crews, " + ledger.allResidences().size() + " homes, " + ledger.totalHeld()
                + " credits held by the ledger, " + ledger.allPending().size() + " transfers unresolved"), false);
            return ledger.allCrews().size();
        }));
        op.then(Commands.literal("pending").executes(ctx -> {
            CrewLedger ledger = CrewService.ledger(ctx.getSource().getServer());
            if (ledger == null) return 0;
            ledger.allPending().values().forEach(p -> ctx.getSource().sendSuccess(() -> Component.literal(p.player() + " " + p.kind() + " " + p.amount() + " credits, wallet before "
                + p.before() + " after " + p.after() + " (tx " + p.tx() + ")"), false));
            return ledger.allPending().size();
        }));
        // An unresolved transfer is one whose wallet matches neither side. An operator who has looked at the wallet decides it.
        op.then(Commands.literal("resolve").requires(source -> source.hasPermission(3))
            .then(Commands.argument("player", StringArgumentType.word()).then(Commands.argument("apply", BoolArgumentType.bool()).executes(ctx -> {
                try {
                    CrewLedger ledger = CrewService.ledger(ctx.getSource().getServer());
                    UUID player = UUID.fromString(StringArgumentType.getString(ctx, "player"));
                    if (ledger == null || ledger.pendingFor(player) == null) throw new IllegalStateException("No unresolved transfer for that player.");
                    if (BoolArgumentType.getBool(ctx, "apply")) ledger.settle(player); else ledger.cancel(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Transfer " + (BoolArgumentType.getBool(ctx, "apply") ? "completed" : "cancelled") + "."), true);
                    return 1;
                } catch (IllegalArgumentException | IllegalStateException error) {
                    ctx.getSource().sendFailure(Component.literal(error.getMessage() == null ? "Bad argument" : error.getMessage()));
                    return 0;
                }
            }))));
        event.getDispatcher().register(op);
    }
}
