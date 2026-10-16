package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Operator-only registration of a looked-at household safe. Players cannot create restocking safes. */
public final class PropertyCommands {
    private static PropertyCacheBlockEntity target(ServerPlayer player) {
        if (!(player.pick(8, 0, false) instanceof BlockHitResult hit)
            || !(player.serverLevel().getBlockEntity(hit.getBlockPos()) instanceof PropertyCacheBlockEntity cache)) {
            throw new IllegalStateException("Look at a placed Household Safe within eight blocks");
        }
        return cache;
    }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_property").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("set").then(Commands.argument("policy", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PropertyPolicies.INSTANCE.ids().stream().map(id -> id.substring(id.indexOf(':') + 1)), builder))
            .executes(ctx -> {
                try {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String name = StringArgumentType.getString(ctx, "policy");
                    PropertyPolicy policy = PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":" + name);
                    if (policy == null) throw new IllegalStateException("Unknown property policy");
                    PropertyCacheBlockEntity cache = target(player);
                    cache.register(player.serverLevel(), policy, WorkOrderLedger.get(player.server).clock());
                    ctx.getSource().sendSuccess(() -> Component.literal("Registered as " + policy.id() + " with " + cache.stock() + " items."), true);
                    return 1;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            })));
        root.then(Commands.literal("status").executes(ctx -> {
            try {
                PropertyCacheBlockEntity cache = target(ctx.getSource().getPlayerOrException());
                ctx.getSource().sendSuccess(() -> Component.literal("policy=" + (cache.registered() ? cache.policyId() : "none")
                    + " stock=" + cache.stock() + " vigilance=" + cache.vigilance() + " forced_entries=" + cache.breakins()
                    + " exceptional_find=" + cache.exceptional()), false);
                return 1;
            } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
        }));
        root.then(Commands.literal("clear").executes(ctx -> {
            try {
                target(ctx.getSource().getPlayerOrException()).clear();
                ctx.getSource().sendSuccess(() -> Component.literal("Safe unregistered and emptied."), true);
                return 1;
            } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
        }));
        event.getDispatcher().register(root);
    }
}
