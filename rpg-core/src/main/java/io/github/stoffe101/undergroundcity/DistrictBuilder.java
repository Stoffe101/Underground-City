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
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static final String KEY = "underground_city_districts";

    /** {@code npcs} is index-aligned with {@link DistrictCatalog.District#npcOps()}; {@link #NONE} marks a citizen this mod set did not place. */
    record Entry(String dimension, BlockPos origin, ListTag palette, int[] blocks, UUID clerk, List<UUID> npcs) {}
    static final UUID NONE = new UUID(0L, 0L);

    /** What a build produced; surfaced to the operator and to tests. */
    public record Result(int blocks, int safes, int secureTerminals, int terminals, boolean clerkPlaced, boolean insideCity, int citizens, int homes) {}

    static final class State extends SavedData {
        final Map<String, Entry> built = new LinkedHashMap<>();

        static State load(CompoundTag tag) {
            State state = new State();
            CompoundTag all = tag.getCompound("built");
            for (String id : all.getAllKeys()) {
                CompoundTag t = all.getCompound(id);
                List<UUID> npcs = new ArrayList<>();
                for (Tag npc : t.getList("npcs", Tag.TAG_INT_ARRAY)) npcs.add(NbtUtils.loadUUID(npc));
                state.built.put(id, new Entry(t.getString("dimension"), BlockPos.of(t.getLong("origin")), t.getList("palette", Tag.TAG_COMPOUND),
                    t.getIntArray("blocks"), t.hasUUID("clerk") ? t.getUUID("clerk") : null, npcs));
            }
            return state;
        }

        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version", 2);
            CompoundTag all = new CompoundTag();
            built.forEach((id, e) -> {
                CompoundTag t = new CompoundTag();
                t.putString("dimension", e.dimension()); t.putLong("origin", e.origin().asLong());
                t.put("palette", e.palette()); t.putIntArray("blocks", e.blocks());
                if (e.clerk() != null) t.putUUID("clerk", e.clerk());
                ListTag npcs = new ListTag();
                for (UUID npc : e.npcs()) npcs.add(NbtUtils.createUUID(npc));
                t.put("npcs", npcs);
                all.put(id, t);
            });
            tag.put("built", all);
            return tag;
        }
    }

    private static State state(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY);
    }

    /** A one-off district is stored under its id; a repeatable one (a checkpoint post, say) under its id and where it stands. */
    static String key(DistrictCatalog.District district, ServerLevel level, BlockPos origin) {
        return district.repeatable() ? district.id() + "@" + level.dimension().location() + "@" + origin.getX() + "," + origin.getY() + "," + origin.getZ() : district.id();
    }

    /** Every stored instance of a district, keyed as {@link #key}. */
    private static Map<String, Entry> instances(State state, DistrictCatalog.District district) {
        Map<String, Entry> found = new LinkedHashMap<>();
        state.built.forEach((key, entry) -> { if (key.equals(district.id()) || key.startsWith(district.id() + "@")) found.put(key, entry); });
        return found;
    }

    static boolean isBuilt(ServerLevel level, String id) {
        State state = state(level);
        return state.built.keySet().stream().anyMatch(key -> key.equals(id) || key.startsWith(id + "@"));
    }

    /** Where a one-off district was built in this world, or null. */
    static BlockPos originOf(ServerLevel level, String id) {
        Entry entry = state(level).built.get(id);
        return entry == null ? null : entry.origin();
    }

    /** How many instances of a district stand in this world. */
    static int count(ServerLevel level, String id) {
        return (int) state(level).built.keySet().stream().filter(key -> key.equals(id) || key.startsWith(id + "@")).count();
    }

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
            case "safe" -> UndergroundCity.PROPERTY_CACHE.get().defaultBlockState().setValue(PropertyCacheBlock.FACING, facing);
            case "secure_terminal" -> UndergroundCity.SECURE_TERMINAL.get().defaultBlockState().setValue(SecureTerminalBlock.FACING, facing);
            case "ledger" -> UndergroundCity.PUBLIC_LEDGER.get().defaultBlockState().setValue(PublicLedgerBlock.FACING, facing);
            default -> null;
        };
    }

    /** Resolves every block state and policy a district names; throws before any world change when one is wrong. */
    static Map<DistrictCatalog.Op, BlockState> validate(ServerLevel level, DistrictCatalog.District district) {
        Map<DistrictCatalog.Op, BlockState> states = new HashMap<>();
        for (DistrictCatalog.Op op : district.ops()) {
            if (!op.applies()) continue; // an operation that names an absent mod is skipped, never an error
            BlockState parsed = op.kind().equals("fill") || op.kind().equals("block") || op.kind().equals("sign") ? parse(level, op.text()) : special(op);
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
        String key = key(district, level, origin);
        if (state.built.containsKey(key)) throw new IllegalStateException(district.title() + " is already built " + (district.repeatable() ? "here" : "in this world") + ". Undo it first.");
        BlockPos lo = origin.offset(district.min()), hi = origin.offset(district.max());
        if (district.repeatable()) {
            // Two instances may not overlap, so an undo can never restore over a neighbour's blocks.
            for (Entry other : instances(state, district).values()) {
                if (!other.dimension().equals(level.dimension().location().toString())) continue;
                BlockPos olo = other.origin().offset(district.min()), ohi = other.origin().offset(district.max());
                if (lo.getX() <= ohi.getX() && hi.getX() >= olo.getX() && lo.getZ() <= ohi.getZ() && hi.getZ() >= olo.getZ()) {
                    throw new IllegalStateException(district.title() + " would overlap another one at " + other.origin().toShortString() + ".");
                }
            }
        }
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
        List<UUID> citizens = new ArrayList<>();
        int placedCitizens = 0;
        List<String> registeredHomes = new ArrayList<>();
        try {
            for (DistrictCatalog.Op op : district.ops()) {
                if (op.kind().equals("clerk") || op.kind().equals("npc") || op.kind().equals("residence") || !op.applies()) continue;
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
                    case "sign" -> writeSign(level, at, op.sign());
                    case "terminal" -> terminals++;
                    default -> { }
                }
            }
            for (DistrictCatalog.Op op : district.ops()) {
                if (!op.kind().equals("clerk") || SupplierNpc.hasClerk(level.getServer())) continue;
                clerk = SupplierNpc.spawnFor(level, origin.offset(op.reference()), origin.offset(op.a())).getUUID();
                clerkPlaced = true;
            }
            registerHomes(level, district, origin, registeredHomes);
            int slot = 0;
            for (DistrictCatalog.Op op : district.npcOps()) {
                if (!op.applies()) { citizens.add(NONE); slot++; continue; }
                citizens.add(CityNpcs.spawn(level, district.id(), slot++, op.npc(), origin.offset(op.a()), op.reference() == null ? null : origin.offset(op.reference())).getUUID());
                placedCitizens++;
            }
        } catch (RuntimeException error) {
            for (UUID id : citizens) discard(level, id);
            if (clerk != null) discard(level, clerk); // the supplier registry releases a discarded clerk on its own
            unregisterHomes(level, registeredHomes);
            restore(level, lo, hi, palette, before);
            throw error;
        }
        state.built.put(key, new Entry(level.dimension().location().toString(), origin.immutable(), saved, before, clerk, citizens));
        state.setDirty();
        level.getServer().overworld().getDataStorage().save();
        return new Result(blocks, safes, secure, terminals, clerkPlaced, CityRegions.INSTANCE.cityAt(level, origin).isPresent(), placedCitizens, registeredHomes.size());
    }

    /** The ledger id of a district's home: unique per world because a district with homes is never repeatable. */
    static String homeId(DistrictCatalog.District district, DistrictCatalog.Home home) { return district.id() + ":" + home.id(); }

    /** Registers the district's homes with the Crew ledger. A world whose ledger cannot be read refuses the build rather than leaving homes that nobody can lease. */
    private static void registerHomes(ServerLevel level, DistrictCatalog.District district, BlockPos origin, List<String> registered) {
        List<DistrictCatalog.Op> homes = district.homeOps();
        if (homes.isEmpty()) return;
        CrewLedger ledger = CrewService.need(level.getServer());
        for (DistrictCatalog.Op op : homes) {
            DistrictCatalog.Home home = op.home();
            ledger.registerResidence(homeId(district, home), home.title(), home.kind(), level.dimension().location().toString(), district.id(),
                origin.offset(op.a()), origin.offset(op.b()), home.price(), home.starterPrice());
            registered.add(homeId(district, home));
        }
    }

    private static void unregisterHomes(ServerLevel level, List<String> ids) {
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger == null) return;
        for (String id : ids) {
            try { ledger.unregisterResidence(id); } catch (RuntimeException error) { LOG.warn("Could not unregister home {}: {}", id, error.getMessage()); }
        }
    }

    /** Writes the words of a sign (front side) and waxes it, so a player cannot rewrite the district's own signage. */
    private static void writeSign(ServerLevel level, BlockPos at, DistrictCatalog.Sign sign) {
        if (!(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity entity) || sign == null) return;
        Component[] lines = new Component[4];
        for (int i = 0; i < 4; i++) lines[i] = Component.literal(i < sign.lines().size() ? sign.lines().get(i) : "");
        entity.setText(new net.minecraft.world.level.block.entity.SignText(lines, lines,
            net.minecraft.world.item.DyeColor.byName(sign.color(), net.minecraft.world.item.DyeColor.BLACK), sign.glow()), true);
        entity.setWaxed(true);
        entity.setChanged();
        level.sendBlockUpdated(at, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_ALL);
    }

    private static void discard(ServerLevel level, UUID id) {
        if (id == null || id.equals(NONE)) return;
        Entity found = level.getEntity(id);
        if (found != null) found.discard();
        else LOG.warn("Citizen {} is not loaded, so removing the district left them where they stand (an operator can remove them by hand)", id);
    }

    /** Where a district's operation sits in the world it was built in, or null when the district is not built (in this dimension). */
    static BlockPos locate(ServerLevel level, DistrictCatalog.District district, DistrictCatalog.Op op) {
        Entry entry = state(level).built.get(district.id());
        if (entry == null || !entry.dimension().equals(level.dimension().location().toString())) return null;
        return entry.origin().offset(op.a());
    }

    /** Puts back any citizen who has been lost, in every instance of the district. Returns how many were placed. */
    public static int repopulate(ServerLevel level, DistrictCatalog.District district) {
        State state = state(level);
        Map<String, Entry> found = instances(state, district);
        if (found.isEmpty()) throw new IllegalStateException(district.title() + " has not been built in this world.");
        int placed = 0;
        for (var instance : found.entrySet()) placed += repopulateInstance(level, state, district, instance.getKey(), instance.getValue());
        return placed;
    }

    private static int repopulateInstance(ServerLevel level, State state, DistrictCatalog.District district, String key, Entry entry) {
        if (!entry.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("It was built in " + entry.dimension() + ". Run this there.");
        BlockPos lo = entry.origin().offset(district.min()), hi = entry.origin().offset(district.max());
        if (!level.hasChunksAt(lo, hi)) throw new IllegalStateException("The lot at " + entry.origin().toShortString() + " is not fully loaded. Stand in it and try again.");
        List<DistrictCatalog.Op> ops = district.npcOps();
        List<UUID> ids = new ArrayList<>(entry.npcs());
        while (ids.size() < ops.size()) ids.add(NONE);
        int placed = 0;
        for (int i = 0; i < ops.size(); i++) {
            DistrictCatalog.Op op = ops.get(i);
            if (!op.applies()) continue;
            Entity existing = ids.get(i).equals(NONE) ? null : level.getEntity(ids.get(i));
            if (existing != null && existing.isAlive()) continue;
            ids.set(i, CityNpcs.spawn(level, district.id(), i, op.npc(), entry.origin().offset(op.a()), op.reference() == null ? null : entry.origin().offset(op.reference())).getUUID());
            placed++;
        }
        if (placed > 0) {
            state.built.put(key, new Entry(entry.dimension(), entry.origin(), entry.palette(), entry.blocks(), entry.clerk(), ids));
            state.setDirty();
            level.getServer().overworld().getDataStorage().save();
        }
        return placed;
    }

    private static void restore(ServerLevel level, BlockPos lo, BlockPos hi, List<BlockState> palette, int[] blocks) {
        int cursor = 0;
        for (int y = lo.getY(); y <= hi.getY(); y++) for (int z = lo.getZ(); z <= hi.getZ(); z++) for (int x = lo.getX(); x <= hi.getX(); x++) {
            level.setBlock(new BlockPos(x, y, z), palette.get(blocks[cursor++]), Block.UPDATE_ALL);
        }
    }

    /** Puts back exactly what the build replaced and removes the clerk and citizens it placed. For a repeatable district use {@link #undoNear}. */
    public static void undo(ServerLevel level, DistrictCatalog.District district) {
        if (district.repeatable()) throw new IllegalStateException(district.title() + " can stand in several places; stand in the one to remove.");
        undoEntry(level, district, district.id());
    }

    /** Removes the instance of a repeatable district whose lot contains (or is nearest to) {@code near}, within 64 blocks. */
    public static void undoNear(ServerLevel level, DistrictCatalog.District district, BlockPos near) {
        String best = null;
        double bestDistance = 64.0 * 64.0;
        for (var instance : instances(state(level), district).entrySet()) {
            double distance = instance.getValue().origin().offset(district.min().getX() + district.max().getX() >> 1, 0, district.min().getZ() + district.max().getZ() >> 1)
                .distSqr(new BlockPos(near.getX(), instance.getValue().origin().getY(), near.getZ()));
            if (distance < bestDistance) { best = instance.getKey(); bestDistance = distance; }
        }
        if (best == null) throw new IllegalStateException("No " + district.title() + " within 64 blocks.");
        undoEntry(level, district, best);
    }

    private static void undoEntry(ServerLevel level, DistrictCatalog.District district, String key) {
        State state = state(level);
        Entry entry = state.built.get(key);
        if (entry == null) throw new IllegalStateException(district.title() + " has not been built in this world.");
        if (!entry.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("It was built in " + entry.dimension() + ". Run undo there.");
        BlockPos lo = entry.origin().offset(district.min()), hi = entry.origin().offset(district.max());
        if (!level.hasChunksAt(lo, hi)) throw new IllegalStateException("The lot is not fully loaded. Stand in it and try again.");
        List<BlockState> palette = new ArrayList<>();
        for (Tag tag : entry.palette()) palette.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), (CompoundTag) tag));
        if (entry.blocks().length != district.volume()) throw new IllegalStateException("The district definition changed since it was built; undo needs the original definition.");
        CrewLedger homes = district.homeOps().isEmpty() ? null : CrewService.need(level.getServer());
        for (DistrictCatalog.Op op : district.homeOps()) {
            CrewLedger.Residence held = homes.residence(homeId(district, op.home()));
            if (held != null && (held.owned() || held.reservedBy != null)) {
                throw new IllegalStateException(held.title + " is leased. Undoing " + district.title() + " would take a home from its tenants.");
            }
        }
        restore(level, lo, hi, palette, entry.blocks());
        if (homes != null) for (DistrictCatalog.Op op : district.homeOps()) homes.unregisterResidence(homeId(district, op.home()));
        if (entry.clerk() != null) {
            Entity clerk = level.getEntity(entry.clerk());
            if (clerk != null) clerk.discard(); // the supplier registry releases a discarded clerk on its own
        }
        for (UUID citizen : entry.npcs()) discard(level, citizen);
        state.built.remove(key);
        state.setDirty();
        level.getServer().overworld().getDataStorage().save();
    }

    /** Test seam: the citizens a district placed, in file order. */
    static List<UUID> citizensForTest(ServerLevel level, String id) {
        List<UUID> all = new ArrayList<>();
        state(level).built.forEach((key, entry) -> { if (key.equals(id) || key.startsWith(id + "@")) all.addAll(entry.npcs()); });
        return List.copyOf(all);
    }

    static void forgetForTest(ServerLevel level, String id) {
        State state = state(level);
        state.built.keySet().removeIf(key -> key.equals(id) || key.startsWith(id + "@"));
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
                        + " safes, " + result.secureTerminals() + " secured terminal, " + result.citizens() + " citizens, " + result.homes() + " homes to lease. Undo with /uc_district undo " + district.id() + " confirm." + note), true);
                    return 1;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            })));
        root.then(Commands.literal("undo").then(Commands.argument("district", StringArgumentType.word())
            .then(Commands.literal("confirm").executes(ctx -> {
                try {
                    DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(StringArgumentType.getString(ctx, "district"));
                    if (district == null) throw new IllegalStateException("Unknown district");
                    if (district.repeatable()) undoNear(ctx.getSource().getLevel(), district, BlockPos.containing(ctx.getSource().getPosition()));
                    else undo(ctx.getSource().getLevel(), district);
                    ctx.getSource().sendSuccess(() -> Component.literal(district.title() + " removed and the original blocks restored."), true);
                    return 1;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            }))));
        root.then(Commands.literal("repopulate").then(Commands.argument("district", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DistrictCatalog.INSTANCE.ids(), builder))
            .executes(ctx -> {
                try {
                    DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(StringArgumentType.getString(ctx, "district"));
                    if (district == null) throw new IllegalStateException("Unknown district");
                    int placed = repopulate(ctx.getSource().getLevel(), district);
                    ctx.getSource().sendSuccess(() -> Component.literal(placed == 0 ? district.title() + " has all its citizens." : placed + " missing citizen(s) of " + district.title() + " placed again."), true);
                    return placed;
                } catch (IllegalStateException error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
            })));
        event.getDispatcher().register(root);
    }
}
