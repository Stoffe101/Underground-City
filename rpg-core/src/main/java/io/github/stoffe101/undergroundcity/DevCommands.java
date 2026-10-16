package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Operator-only helpers for testing the development build. They do not touch economy, careers or saved state; they only
 * move the operator or place a terminal so that every system can be reached without hours of walking.
 *
 * <ul>
 *   <li>{@code /uc_dev tp <city>} teleports to the surface at a city centre (city ids come from the loaded regions).</li>
 *   <li>{@code /uc_dev terminal} places an Operations Terminal two blocks in front of the operator.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class DevCommands {
    private DevCommands() {}

    /** The highest standable surface at a column, found by scanning blocks because Lost Cities heightmaps can be unprimed. */
    static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        for (int y = level.getMaxBuildHeight() - 1; y > level.getMinBuildHeight(); y--) {
            BlockPos at = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(at);
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.getCollisionShape(level, at).isEmpty()) continue;
            return at.above();
        }
        return new BlockPos(x, level.getSeaLevel(), z);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_dev").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("tp").then(Commands.argument("city", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CityRegions.INSTANCE.all().stream().map(CityRegions.Region::id), builder))
            .executes(ctx -> {
                try {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String id = StringArgumentType.getString(ctx, "city");
                    var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Unknown city " + id));
                    ServerLevel level = ctx.getSource().getServer().overworld();
                    BlockPos target = surface(level, (int) region.centerX(), (int) region.centerZ());
                    player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, player.getYRot(), player.getXRot());
                    ctx.getSource().sendSuccess(() -> Component.literal("Teleported to " + id + " at " + target.toShortString()), false);
                    return 1;
                } catch (IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException error) {
                    ctx.getSource().sendFailure(Component.literal(String.valueOf(error.getMessage())));
                    return 0;
                }
            })));
        root.then(Commands.literal("terminal").executes(ctx -> {
            try {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                ServerLevel level = player.serverLevel();
                var facing = player.getDirection();
                BlockPos at = player.blockPosition().relative(facing, 2);
                level.setBlock(at, UndergroundCity.TERMINAL.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing.getOpposite()), 3);
                ctx.getSource().sendSuccess(() -> Component.literal("Operations Terminal placed at " + at.toShortString()), false);
                return 1;
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
                ctx.getSource().sendFailure(Component.literal(String.valueOf(error.getMessage())));
                return 0;
            }
        }));
        event.getDispatcher().register(root);
    }

}
