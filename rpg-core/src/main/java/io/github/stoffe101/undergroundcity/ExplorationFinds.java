package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Exploration finds (eighth pass, D-186): the personal half of the game's loot. A battered crate tucked into each roadside place and wayside shelter on the roads between the cities holds
 * something for <b>each</b> player who finds it, once: what a person finds is rolled for them (the same roll whoever asks and however often), handed straight to them and never put in the
 * crate, so there is nothing in it to take from another player, to duplicate, or to go missing on a crash. The shared, finite loot (burglary, safes, the cities' shelves) is untouched: a
 * find is a small reward for going out to look, not a second economy. Goods from a find are a person's own, never marked stolen.
 *
 * <p>Like the Technician's panels and the Smith's drive housings, a crate is set in place by Core the first time a player comes near a built place, and only in a new archipelago world.
 */
final class ExplorationFinds {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ExplorationFinds() {}

    // ------------------------------------------------------------------------------------------------ the rules (data)

    /** One kind of site's finds: how many rolls, the weighted entries, and a rare extra. */
    record Table(int rollsLo, int rollsHi, List<Entry> entries, ResourceLocation rare, double rareChance, String text) {}
    record Entry(ResourceLocation item, int weight, int lo, int hi) {}

    static volatile Map<String, Table> TABLES = Map.of();

    static Map<String, Table> parse(JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported exploration finds schema");
        Map<String, Table> out = new LinkedHashMap<>();
        for (var e : json.getAsJsonObject("kinds").entrySet()) {
            JsonObject t = e.getValue().getAsJsonObject();
            var rolls = t.getAsJsonArray("rolls");
            List<Entry> entries = new ArrayList<>();
            for (JsonElement el : t.getAsJsonArray("entries")) {
                var a = el.getAsJsonArray();
                Entry entry = new Entry(ResourceLocation.parse(a.get(0).getAsString()), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt());
                if (entry.weight() < 1 || entry.lo() < 1 || entry.hi() < entry.lo() || entry.hi() > 16) throw new IllegalArgumentException(e.getKey() + ": a find entry must have a weight and 1 to 16 of the item");
                entries.add(entry);
            }
            int lo = rolls.get(0).getAsInt(), hi = rolls.get(1).getAsInt();
            if (lo < 1 || hi < lo || hi > 4 || entries.isEmpty()) throw new IllegalArgumentException(e.getKey() + ": one to four rolls of at least one entry");
            ResourceLocation rare = t.has("rare") ? ResourceLocation.parse(t.get("rare").getAsString()) : null;
            double chance = t.has("rare_chance") ? t.get("rare_chance").getAsDouble() : 0;
            if (chance < 0 || chance > 0.25) throw new IllegalArgumentException(e.getKey() + ": a rare find is at most one in four");
            out.put(e.getKey(), new Table(lo, hi, List.copyOf(entries), rare, chance, t.has("text") ? t.get("text").getAsString() : "You go through the crate."));
        }
        return Map.copyOf(out);
    }

    static final class Loader extends SimpleJsonResourceReloadListener {
        static final Loader INSTANCE = new Loader();
        private Loader() { super(new Gson(), "exploration_finds"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            JsonElement element = data.get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "finds"));
            TABLES = element == null ? Map.of() : parse(element.getAsJsonObject());
            LOGGER.info("Loaded exploration finds for {} kinds of site", TABLES.size());
        }
    }

    // ------------------------------------------------------------------------------------------------ the record

    /** A placed find: its id ("place:harrow_farm", "shelter:<road>#<n>"), the kind of site whose table it uses, and where the crate stands. */
    record Find(String id, String kind, BlockPos pos) {}

    static final class Record extends SavedData {
        static final String KEY = "underground_city_exploration_finds";
        final Map<String, Find> finds = new LinkedHashMap<>();
        final Map<Long, String> byPos = new HashMap<>();
        final Map<UUID, Set<String>> found = new HashMap<>();
        int opened;

        static Record load(CompoundTag tag) {
            Record r = new Record();
            for (Tag t : tag.getList("finds", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Find f = new Find(c.getString("id"), c.getString("kind"), BlockPos.of(c.getLong("pos")));
                r.finds.put(f.id(), f);
                r.byPos.put(f.pos().asLong(), f.id());
            }
            CompoundTag who = tag.getCompound("found");
            for (String k : who.getAllKeys()) try {
                Set<String> ids = new HashSet<>();
                for (Tag s : who.getList(k, Tag.TAG_STRING)) ids.add(s.getAsString());
                r.found.put(UUID.fromString(k), ids);
            } catch (IllegalArgumentException ignored) { }
            r.opened = tag.getInt("opened");
            return r;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Find f : finds.values()) { CompoundTag c = new CompoundTag(); c.putString("id", f.id()); c.putString("kind", f.kind()); c.putLong("pos", f.pos().asLong()); list.add(c); }
            tag.put("finds", list);
            CompoundTag who = new CompoundTag();
            found.forEach((k, v) -> { ListTag ids = new ListTag(); for (String s : v) ids.add(StringTag.valueOf(s)); who.put(k.toString(), ids); });
            tag.put("found", who);
            tag.putInt("opened", opened);
            return tag;
        }

        void add(Find f) { finds.put(f.id(), f); byPos.put(f.pos().asLong(), f.id()); setDirty(); }
    }

    static Record record(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Record::load, Record::new, Record.KEY); }

    // ------------------------------------------------------------------------------------------------ opening a find

    /** What {@code player} finds in {@code find}: the same for them every time (seeded by the world, the find and the player), different for another player. */
    static List<ItemStack> roll(long worldSeed, Find find, UUID player) {
        Table t = TABLES.get(find.kind());
        if (t == null) return List.of();
        Random r = new Random(worldSeed ^ (long) find.id().hashCode() * 0x9E3779B97F4A7C15L ^ player.getMostSignificantBits() ^ player.getLeastSignificantBits() * 31);
        int total = 0;
        for (Entry e : t.entries()) total += e.weight();
        List<ItemStack> out = new ArrayList<>();
        int rolls = t.rollsLo() + r.nextInt(t.rollsHi() - t.rollsLo() + 1);
        for (int i = 0; i < rolls; i++) {
            int pick = r.nextInt(total);
            for (Entry e : t.entries()) {
                pick -= e.weight();
                if (pick >= 0) continue;
                Item item = ForgeRegistries.ITEMS.getValue(e.item());
                if (item != null && item != net.minecraft.world.item.Items.AIR) out.add(new ItemStack(item, e.lo() + r.nextInt(e.hi() - e.lo() + 1)));
                break;
            }
        }
        if (t.rare() != null && r.nextDouble() < t.rareChance()) {
            Item item = ForgeRegistries.ITEMS.getValue(t.rare());
            if (item != null && item != net.minecraft.world.item.Items.AIR) out.add(new ItemStack(item));
        }
        return out;
    }

    /** A player goes through the crate at {@code pos}. Returns what they are told. */
    static String open(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player instanceof FakePlayer && !TESTING) return "";
        Record rec = record(level.getServer());
        String id = rec.byPos.get(pos.asLong());
        Find find = id == null ? null : rec.finds.get(id);
        if (find == null) return "An empty crate, picked over long ago.";
        Set<String> mine = rec.found.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        if (mine.contains(find.id())) return "You have been through this crate already. Somebody else may still find something in it.";
        List<ItemStack> got = roll(level.getSeed(), find, player.getUUID());
        mine.add(find.id());
        rec.opened++;
        rec.setDirty();
        List<String> names = new ArrayList<>();
        for (ItemStack s : got) {
            names.add(s.getCount() + " " + s.getHoverName().getString());
            ItemStack copy = s.copy();
            if (!player.getInventory().add(copy)) player.drop(copy, false);
        }
        player.getInventory().setChanged();
        level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.7f, 0.9f);
        Table t = TABLES.get(find.kind());
        String text = (t == null ? "You go through the crate." : t.text()) + (names.isEmpty() ? " Nothing worth taking." : " You keep: " + String.join(", ", names) + ".");
        Notice.send(player, Notice.Kind.DELIVERED, "A find", text);
        LOGGER.info("[UC-FIND] {} went through {} ({}): {}", player.getGameProfile().getName(), find.id(), find.kind(), names);
        return "";
    }

    static boolean TESTING;

    // ------------------------------------------------------------------------------------------------ placing the crates

    static final int CYCLE = 100, NEAR = 48;

    /** The places and shelters built on the roads, as find ids, kinds and their lots (x0, y, z0, x1, z1). */
    static List<Object[]> sites(ServerLevel level) {
        List<Object[]> out = new ArrayList<>();
        PlacedSites places = PlacedSites.get(level, RoadsidePlaces.KEY);
        for (var built : places.where.entrySet()) {
            RoadsidePlaces.Place place = RoadsidePlaces.CATALOG.find(built.getKey());
            DistrictCatalog.District d = place == null ? null : RoadsidePlaces.builtVariant(level, place);
            if (d == null) continue;
            BlockPos o = BlockPos.of(built.getValue());
            out.add(new Object[] {"place:" + place.id(), place.kind(), o, d});
        }
        PlacedSites shelters = PlacedSites.get(level, PlacedSites.SHELTERS);
        for (var built : shelters.where.entrySet()) {
            int hash = built.getKey().lastIndexOf('#');
            if (hash < 1) continue;
            try {
                DistrictCatalog.District d = DistrictCatalog.INSTANCE.find(WaysideShelters.kindOf(built.getKey().substring(0, hash), Integer.parseInt(built.getKey().substring(hash + 1))));
                if (d != null) out.add(new Object[] {"shelter:" + built.getKey(), "wayside", BlockPos.of(built.getValue()), d});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * Where a site's crate goes: the {@code k}-th (by the world and the site) of the cells on the lot a person can stand in, under something (a roof, a lean-to) or against a wall, never in a
     * doorway, scanned in a fixed order. Null when the lot has none.
     */
    static BlockPos spot(ServerLevel level, String id, BlockPos origin, DistrictCatalog.District d) {
        List<BlockPos> cells = new ArrayList<>();
        int y0 = origin.getY() + d.min().getY(), y1 = origin.getY() + Math.min(d.max().getY(), d.min().getY() + 6);
        for (int y = y0; y <= y1; y++) for (int x = origin.getX() + d.min().getX() + 1; x < origin.getX() + d.max().getX(); x++) for (int z = origin.getZ() + d.min().getZ() + 1; z < origin.getZ() + d.max().getZ(); z++) {
            BlockPos c = new BlockPos(x, y, z);
            if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir() || !level.getBlockState(c.below()).isSolidRender(level, c.below())) continue;
            int walls = 0;
            boolean door = false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockState n = level.getBlockState(c.relative(dir));
                if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || n.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) door = true;
                if (n.isSolidRender(level, c.relative(dir))) walls++;
            }
            if (door || walls == 0 || walls > 2) continue;
            cells.add(c.immutable());
        }
        if (cells.isEmpty()) return null;
        long h = PopulationPlan.mix(level.getSeed() ^ id.hashCode());
        return cells.get((int) Math.floorMod(h, (long) cells.size()));
    }

    /** Places the crate of a site a player is near, once. Returns whether one was placed. */
    static boolean ensure(ServerLevel level, Object[] site) {
        Record rec = record(level.getServer());
        String id = (String) site[0];
        if (rec.finds.containsKey(id)) return false;
        BlockPos at = spot(level, id, (BlockPos) site[2], (DistrictCatalog.District) site[3]);
        if (at == null) return false;
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(new net.minecraft.world.level.levelgen.LegacyRandomSource(level.getSeed() ^ id.hashCode()));
        level.setBlock(at, UndergroundCity.EXPLORATION_FIND.get().defaultBlockState().setValue(FindBlock.FACING, facing), 3);
        rec.add(new Find(id, (String) site[1], at));
        LOGGER.info("[UC-FIND] a crate for {} at {}", id, at.toShortString());
        return true;
    }

    /** Registered on the Forge bus: every five seconds, the crates of the built sites within {@link #NEAR} blocks of a player in a new archipelago world. */
    static final class Service {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 53) return;
            ServerLevel level = event.getServer().overworld();
            if (!TechService.placesPanels(level) || TABLES.isEmpty()) return;
            List<Object[]> sites = null;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                if (player instanceof FakePlayer || player.isSpectator() || player.level() != level) continue;
                if (sites == null) sites = sites(level);
                for (Object[] site : sites) {
                    BlockPos o = (BlockPos) site[2];
                    if (o.distSqr(player.blockPosition()) < NEAR * NEAR && ensure(level, site)) return;   // one a pass
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the crate

    /** The battered crate: no item, cannot be broken, holds nothing itself; a right click goes through it ({@link #open}). */
    static final class FindBlock extends HorizontalDirectionalBlock {
        FindBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            String said = open(sp, server, pos);
            if (!said.isEmpty()) sp.displayClientMessage(Component.literal(said), true);
            return InteractionResult.CONSUME;
        }
    }

    /** For the status line. */
    static String status(MinecraftServer server) { Record r = record(server); return r.finds.size() + " crates placed, " + r.opened + " opened by " + r.found.size() + " players"; }

    static Optional<Find> at(MinecraftServer server, BlockPos pos) {
        Record r = record(server);
        String id = r.byPos.get(pos.asLong());
        return id == null ? Optional.empty() : Optional.ofNullable(r.finds.get(id));
    }
}
