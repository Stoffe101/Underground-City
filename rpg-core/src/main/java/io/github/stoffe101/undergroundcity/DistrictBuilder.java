package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a data-defined district (D-055) at an operator's position, with the safeguards a real save needs:
 * everything is validated before the world is touched, a build that finds any block entity in its footprint is
 * refused (so it can never delete a chest or somebody's safe), the previous blocks are saved so {@code undo} can
 * restore them, and one district id can exist once per world.
 */
public final class DistrictBuilder {
    private static final String KEY = "underground_city_districts";

    record Entry(String dimension, BlockPos origin, ListTag palette, int[] blocks, UUID clerk) {}

    /** What a build produced; surfaced to the operator and to tests. */
    public record Result(int blocks, int safes, int secureTerminals, int terminals, boolean clerkPlaced, boolean insideCity) {}

    static final class State extends SavedData {
        final Map<String, Entry> built = new LinkedHashMap<>();

        static State load(CompoundTag tag) {
            State state = new State();
            CompoundTag all = tag.getCompound("built");
            for (String id : all.getAllKeys()) {
                CompoundTag t = all.getCompound(id);
                state.built.put(id, new Entry(t.getString("dimension"), BlockPos.of(t.getLong("origin")), t.getList("palette", Tag.TAG_COMPOUND),
                    t.getIntArray("blocks"), t.hasUUID("clerk") ? t.getUUID("clerk") : null));
            }
            return state;
        }

        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version", 1);
            CompoundTag all = new CompoundTag();
            built.forEach((id, e) -> {
                CompoundTag t = new CompoundTag();
                t.putString("dimension", e.dimension()); t.putLong("origin", e.origin().asLong());
                t.put("palette", e.palette()); t.putIntArray("blocks", e.blocks());
                if (e.clerk() != null) t.putUUID("clerk", e.clerk());
                all.put(id, t);
            });
            tag.put("built", all);
            return tag;
        }
    }

    private static State state(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY);
    }

    static boolean isBuilt(ServerLevel level, String id) { return state(level).built.containsKey(id); }

    private static BlockState parse(ServerLevel level, String text) {
        try {
            return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState();
        } catch (CommandSyntaxException error) {
            throw new IllegalStateException("District names an invalid block: " + text);
        }
    }

    private static BlockState special(DistrictCatalog.Op op) {
        Direction facing = Direction.byName(op.facing());
        return switch (op.kind()) {
            case "terminal" -> UndergroundCity.TERMINAL.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
            case "safe" -> UndergroundCity.PROPERTY_CACHE.get().defaultBlockState();
            case "secure_terminal" -> UndergroundCity.SECURE_TERMINAL.get().defaultBlockState();
            default -> null;
        };
    }

    /** Resolves every block state and policy a district names; throws before any world change when one is wrong. */
    static Map<DistrictCatalog.Op, BlockState> validate(ServerLevel level, DistrictCatalog.District district) {
        Map<DistrictCatalog.Op, BlockState> states = new HashMap<>();
        for (DistrictCatalog.Op op : district.ops()) {
            BlockState parsed = op.kind().equals("fill") || op.kind().equals("block") ? parse(level, op.text()) : special(op);
            if (parsed != null) states.put(op, parsed);
            if (op.kind().equals("safe") && PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":" + op.text()) == null) {
                throw new IllegalStateException("Unknown property policy " + op.text());
            }
        }
        return states;
    }

    /** Builds {@code district} with its minimum corner at {@code origin}. Throws IllegalStateException with the reason on refusal. */
    public static Result build(ServerLevel level, DistrictCatalog.District district, BlockPos origin) {
        State state = state(level);
        if (state.built.containsKey(district.id())) throw new IllegalStateException(district.title() + " is already built in this world. Undo it first.");
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        if (lo.getY() <= level.getMinBuildHeight() || hi.getY() >= level.getMaxBuildHeight()) throw new IllegalStateException("The lot would leave the world's build height.");
        if (!level.hasChunksAt(lo, hi)) throw new IllegalStateException("The lot is not fully loaded. Stand in the middle of the area and try again.");
        // 1. Validate the data and the lot before touching anything.
        Map<DistrictCatalog.Op, BlockState> states = validate(level, district);
        for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
            if (level.getBlockEntity(pos) != null) {
                throw new IllegalStateException("Refusing to build: there is a container or machine at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " inside the lot. Choose a clear area.");
            }
        }
        // 2. Save what is there now.
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] before = new int[district.volume()];
        int cursor = 0;
        for (int y = lo.getY(); y <= hi.getY(); y++) for (int z = lo.getZ(); z <= hi.getZ(); z++) for (int x = lo.getX(); x <= hi.getX(); x++) {
            BlockState current = level.getBlockState(new BlockPos(x, y, z));
            Integer slot = index.get(current);
            if (slot == null) { slot = palette.size(); palette.add(current); index.put(current, slot); }
            before[cursor++] = slot;
        }
        ListTag saved = new ListTag();
        for (BlockState entry : palette) saved.add(NbtUtils.writeBlockState(entry));
        // 3. Build; a failure part-way puts the previous blocks back.
        int blocks = 0, safes = 0, secure = 0, terminals = 0;
        boolean clerkPlaced = false;
        UUID clerk = null;
        try {
            for (DistrictCatalog.Op op : district.ops()) {
                if (op.kind().equals("clerk")) continue;
                BlockState place = states.get(op);
                for (BlockPos pos : BlockPos.betweenClosed(origin.offset(op.a()), origin.offset(op.b()))) {
                    level.setBlock(pos, place, Block.UPDATE_ALL);
                    blocks++;
                }
                BlockPos at = origin.offset(op.a());
                switch (op.kind()) {
                    case "safe" -> {
                        PropertyPolicy policy = PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":" + op.text());
                        ((PropertyCacheBlockEntity) level.getBlockEntity(at)).register(level, policy, WorkOrderLedger.get(level.getServer()).clock());
                        safes++;
                    }
                    case "secure_terminal" -> secure++;
                    case "terminal" -> terminals++;
                    default -> { }
                }
            }
            for (DistrictCatalog.Op op : district.ops()) {
                if (!op.kind().equals("clerk") || SupplierNpc.hasClerk(level.getServer())) continue;
                clerk = SupplierNpc.spawnFor(level, origin.offset(op.reference()), origin.offset(op.a())).getUUID();
                clerkPlaced = true;
            }
        } catch (RuntimeException error) {
            restore(level, lo, hi, palette, before);
            throw error;
        }
        state.built.put(district.id(), new Entry(level.dimension().location().toString(), origin.immutable(), saved, before, clerk));
        state.setDirty();
        level.getServer().overworld().getDataStorage().save();
        return new Result(blocks, safes, secure, terminals, clerkPlaced, CityRegions.INSTANCE.cityAt(level, origin).isPresent());
    }

    private static void restore(ServerLevel level, BlockPos lo, BlockPos hi, List<BlockState> palette, int[] blocks) {
        int cursor = 0;
        for (int y = lo.getY(); y <= hi.getY(); y++) for (int z = lo.getZ(); z <= hi.getZ(); z++) for (int x = lo.getX(); x <= hi.getX(); x++) {
            level.setBlock(new BlockPos(x, y, z), palette.get(blocks[cursor++]), Block.UPDATE_ALL);
        }
    }

    /** Puts back exactly what the build replaced and removes the clerk it placed. */
    public static void undo(ServerLevel level, DistrictCatalog.District district) {
        State state = state(level);
        Entry entry = state.built.get(district.id());
        if (entry == null) throw new IllegalStateException(district.title() + " has not been built in this world.");
        if (!entry.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("It was built in " + entry.dimension() + ". Run undo there.");
        BlockPos lo = entry.origin().offset(district.min()), hi = entry.origin().offset(district.max());
        if (!level.hasChunksAt(lo, hi)) throw new IllegalStateException("The lot is not fully loaded. Stand in it and try again.");
        List<BlockState> palette = new ArrayList<>();
        for (Tag tag : entry.palette()) palette.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), (CompoundTag) tag));
        if (entry.blocks().length != district.volume()) throw new IllegalStateException("The district definition changed since it was built; undo needs the original definition.");
        restore(level, lo, hi, palette, entry.blocks());
        if (entry.clerk() != null) {
            Entity clerk = level.getEntity(entry.clerk());
            if (clerk != null) clerk.discard(); // the supplier registry releases a discarded clerk on its own
        }
        state.built.remove(district.id());
        state.setDirty();
        level.getServer().overworld().getDataStorage().save();
    }

    static void forgetForTest(ServerLevel level, String id) {
        State state = state(level);
        state.built.remove(id);
        state.setDirty();
    }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_district").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("list").executes(ctx -> {
            ServerLevel level = ctx.getSource().getLevel();
            for (String id : DistrictCatalog.INSTANCE.ids()) {
                String line = id + (isBuilt(level, id) ? " (built)" : "");
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return DistrictCatalog.INSTANCE.ids().size();
        }));
        root.then(Commands.literal("build").then(Commands.argument("district", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DistrictCatalog.INSTANCE.ids(), builder))
            .executes(ctx -> {
                try {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(StringArgumentType.getString(ctx, "district"));
                    if (district == null) throw new IllegalStateException("Unknown district");
                    Result result = build(player.serverLevel(), district, player.blockPosition());
                    String note = (result.insideCity() ? "" : " Warning: this lot is outside every city circle, so city features will not apply here.")
                        + (result.clerkPlaced() ? "" : " The world already has a supply clerk; none was added.");
                    ctx.getSource().sendSuccess(() -> Component.literal(district.title() + " built: " + result.blocks() + " blocks, " + result.safes()
                        + " safes, " + result.secureTerminals() + " secured terminal. Undo with /uc_district undo " + district.id() + " confirm." + note), true);
                    return 1;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            })));
        root.then(Commands.literal("undo").then(Commands.argument("district", StringArgumentType.word())
            .then(Commands.literal("confirm").executes(ctx -> {
                try {
                    DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(StringArgumentType.getString(ctx, "district"));
                    if (district == null) throw new IllegalStateException("Unknown district");
                    undo(ctx.getSource().getLevel(), district);
                    ctx.getSource().sendSuccess(() -> Component.literal(district.title() + " removed and the original blocks restored."), true);
                    return 1;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            }))));
        event.getDispatcher().register(root);
    }
}
