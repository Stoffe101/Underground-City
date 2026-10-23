package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Searching a building (D-144). Inside a city building that is not your own, a right click on a piece of furniture a household keeps things in (a night stand, a kitchen cupboard, a bookshelf, a
 * desk's equipment, a till, a workbench, a bin; data: {@code search_spots}) starts a <b>search</b>: a few seconds of standing at it, a bar under the crosshair, a little noise. When it ends the
 * thief finds what such a place in such a building holds, by the building's tier, and every find is <b>marked stolen</b> (the city and the thief). A real container (a chest or a barrel) is opened
 * after the same search and what is in it is marked. Each spot is searched once and refills after some hours of active play (never while nobody plays, never on a timer a player can reset by
 * breaking anything: the furniture of a building cannot be broken).
 *
 * <p>Risk: a person of the household in the room who can see the thief notices the search (a quiet search with Quick Hands, or a crouched thief with Soft Steps, is noticed only close by), and that
 * is a witnessed break-in for the police. Thief XP comes from what is found, once per refill of a spot.
 */
public final class BurglarySearch {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Loader LOADER = new Loader();

    /** One entry of a kind's loot. */
    record Entry(ResourceLocation item, String nbt, int min, int max, int weight, int minTier, int maxTier, List<String> uses, String requiresMod) {}
    /** One kind of searchable furniture. */
    record Kind(String id, String title, int ticks, List<String> exact, List<String[]> suffixes, int emptyWeight, List<Entry> loot, List<String> tillUses) {}

    /** How far a thief may be from what they search (squared), and how far a person of the household notices a search (blocks): normally, quietly, and quietly while crouched with Soft Steps. */
    static final double REACH_SQ = 3.2 * 3.2, NOTICE = 10, NOTICE_QUIET = 4, NOTICE_CROUCHED = 5;
    static final int MAX_XP_PER_SEARCH = 30;

    /** One search in progress. */
    record Active(UUID player, BlockPos pos, Kind kind, long endsAt, BlockPos from, boolean container) {}
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    static long searches, finds, caught;

    /** Registered on the Forge bus for its clicks and its clock. */
    BurglarySearch() {}

    // ------------------------------------------------------------------------------------------------ data

    public static final class Loader extends SimpleJsonResourceReloadListener {
        private volatile Map<String, Kind> kinds = Map.of();
        private volatile long refillTicks = 4 * 72_000L;
        private Loader() { super(new Gson(), "search_spots"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Kind> next = new LinkedHashMap<>();
            long refill = 4 * 72_000L;
            for (var file : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                JsonObject json = file.getValue().getAsJsonObject();
                if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported search-spot schema: " + file.getKey());
                if (json.has("refill_hours")) refill = Math.max(1, json.get("refill_hours").getAsInt()) * 72_000L;
                for (var entry : json.getAsJsonObject("kinds").entrySet()) next.put(entry.getKey(), parse(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
            kinds = Map.copyOf(next);
            refillTicks = refill;
            LOGGER.info("Loaded {} kinds of searchable furniture", kinds.size());
        }

        static Kind parse(String id, JsonObject json) {
            List<String> exact = new ArrayList<>();
            List<String[]> suffixes = new ArrayList<>();
            for (JsonElement m : json.getAsJsonArray("match")) {
                JsonObject o = m.getAsJsonObject();
                if (o.has("id")) exact.add(o.get("id").getAsString());
                else suffixes.add(new String[] {o.get("namespace").getAsString(), o.get("suffix").getAsString()});
            }
            List<Entry> loot = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("loot")) {
                JsonObject o = e.getAsJsonObject();
                int min = 1, max = 1;
                if (o.has("count")) { min = o.getAsJsonArray("count").get(0).getAsInt(); max = o.getAsJsonArray("count").get(1).getAsInt(); }
                List<String> uses = new ArrayList<>();
                if (o.has("uses")) for (JsonElement u : o.getAsJsonArray("uses")) uses.add(u.getAsString());
                Entry entry = new Entry(ResourceLocation.parse(o.get("item").getAsString()), o.has("nbt") ? o.get("nbt").getAsString() : "", min, max, o.get("weight").getAsInt(),
                    o.has("min_tier") ? o.get("min_tier").getAsInt() : 1, o.has("max_tier") ? o.get("max_tier").getAsInt() : 6, List.copyOf(uses), o.has("requires_mod") ? o.get("requires_mod").getAsString() : "");
                if (entry.weight() < 1 || entry.min() < 1 || entry.max() < entry.min() || entry.max() > 64 || entry.minTier() < 1 || entry.maxTier() > 6 || entry.minTier() > entry.maxTier()) {
                    throw new IllegalArgumentException("Invalid search loot entry in " + id + ": " + o);
                }
                loot.add(entry);
            }
            List<String> tillUses = new ArrayList<>();
            if (json.has("till_uses")) for (JsonElement u : json.getAsJsonArray("till_uses")) tillUses.add(u.getAsString());
            double seconds = json.get("seconds").getAsDouble();
            if (seconds < 0.5 || seconds > 20) throw new IllegalArgumentException("Search time of " + id + " must be 0.5 to 20 seconds");
            return new Kind(id, json.get("title").getAsString(), (int) Math.round(seconds * 20), List.copyOf(exact), List.copyOf(suffixes), json.get("empty_weight").getAsInt(), List.copyOf(loot), List.copyOf(tillUses));
        }

        Map<String, Kind> kinds() { return kinds; }
        long refillTicks() { return refillTicks; }

        /** The kind of a block, or null when it is not searchable furniture. */
        Kind kindOf(BlockState state) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String full = id.toString();
            for (Kind kind : kinds.values()) {
                if (kind.exact().contains(full)) return kind;
                for (String[] s : kind.suffixes()) if (s[0].equals(id.getNamespace()) && id.getPath().endsWith(s[1])) return kind;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ records

    /** When each spot was last searched, in active ticks. Saved, bounded, newest kept. */
    static final class Records extends SavedData {
        static final String DATA_KEY = "underground_city_searched";
        static final int LIMIT = 16_384;
        final LinkedHashMap<Long, Long> searched = new LinkedHashMap<>();

        static Records load(CompoundTag root) {
            Records r = new Records();
            long[] keys = root.getLongArray("keys"), ticks = root.getLongArray("ticks");
            for (int i = 0; i < Math.min(keys.length, ticks.length) && i < LIMIT; i++) r.searched.put(keys[i], ticks[i]);
            return r;
        }

        @Override public CompoundTag save(CompoundTag root) {
            root.putInt("schema_version", 1);
            root.putLongArray("keys", searched.keySet().stream().mapToLong(Long::longValue).toArray());
            root.putLongArray("ticks", searched.values().stream().mapToLong(Long::longValue).toArray());
            return root;
        }

        boolean ready(long pos, long now, long refill) { Long at = searched.get(pos); return at == null || now - at >= refill; }

        void mark(long pos, long now) {
            searched.remove(pos);
            searched.put(pos, now);
            while (searched.size() > LIMIT) searched.remove(searched.keySet().iterator().next());
            setDirty();
        }
    }

    static Records records(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Records::load, Records::new, Records.DATA_KEY); }

    // ------------------------------------------------------------------------------------------------ starting a search

    /** The building a search would happen in for this player, or empty when this is not somebody else's city building (their own unit, a creative player, the countryside). */
    static Optional<CityBuildings.Building> foreignBuilding(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player.isCreative() || player.isSpectator()) return Optional.empty();
        Optional<CityBuildings.Building> found = BuildingGuard.protectedAt(level, pos);
        // a registered home (a let flat, a house a player rents or owns, a vacant unit's showroom) answers to its own guard, so it is never "somebody else's building" here
        if (found.isEmpty() || !found.get().locked() || Thievery.tier(found.get()) <= 0) return Optional.empty();
        return found;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        BlockState state = level.getBlockState(event.getPos());
        boolean container = state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
        Kind kind = container ? null : LOADER.kindOf(state);
        if (!container && kind == null) return;
        Optional<CityBuildings.Building> building = foreignBuilding(player, level, event.getPos());
        if (building.isEmpty()) return;
        long now = LockService.clock(level.getServer());
        // a container that was searched once opens normally afterwards: what is in it is already marked
        if (container && !records(level.getServer()).ready(event.getPos().asLong(), now, Long.MAX_VALUE)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        String refusal = begin(player, level, event.getPos().immutable(), building.get(), container ? CONTAINER : kind, container);
        if (refusal != null) player.displayClientMessage(Component.literal(refusal), true);
    }

    /** A chest or a barrel: searched like furniture, then opened. */
    static final Kind CONTAINER = new Kind("container", "storage", 50, List.of(), List.of(), 0, List.of(), List.of());

    /** Starts a search; returns why it cannot start, or null. */
    static String begin(ServerPlayer player, ServerLevel level, BlockPos pos, CityBuildings.Building building, Kind kind, boolean container) {
        if (ACTIVE.containsKey(player.getUUID())) return "You are already searching.";
        if (LockService.busy(player.getUUID())) return "Your hands are on a lock.";
        if (WorkOrderLedger.get(level.getServer()).faulted()) return "Searching is unavailable until the server is restarted.";
        long now = LockService.clock(level.getServer());
        if (!container && !records(level.getServer()).ready(pos.asLong(), now, LOADER.refillTicks())) return "Somebody has been through this already. Nothing left worth taking.";
        Kind effective = kind;
        if (!container && !kind.tillUses().isEmpty() && kind.tillUses().contains(use(building)) && LOADER.kinds().containsKey("till")) effective = LOADER.kinds().get("till");
        int ticks = Math.max(10, Thievery.has(player, "thief.quick_hands") ? effective.ticks() / 2 : effective.ticks());
        ACTIVE.put(player.getUUID(), new Active(player.getUUID(), pos, effective, level.getServer().getTickCount() + ticks, player.blockPosition(), container));
        searches++;
        BurglaryNetwork.searchStart(player, "Searching the " + effective.title(), ticks);
        if (!Thievery.has(player, "thief.quick_hands")) level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.35f, 1.6f);
        return null;
    }

    static String use(CityBuildings.Building building) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(building.type());
        return plan == null ? "" : plan.use();
    }

    // ------------------------------------------------------------------------------------------------ the clock

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        for (Iterator<Active> it = ACTIVE.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(a.player());
            if (player == null || !player.isAlive() || player.distanceToSqr(a.pos().getX() + 0.5, a.pos().getY() + 0.5, a.pos().getZ() + 0.5) > REACH_SQ
                || player.blockPosition().distSqr(a.from()) > 2.5) {
                it.remove();
                if (player != null) { BurglaryNetwork.searchEnd(player, false); player.displayClientMessage(Component.literal("You stopped searching."), true); }
                continue;
            }
            if (tick < a.endsAt()) continue;
            it.remove();
            BurglaryNetwork.searchEnd(player, true);
            try { finish(player, player.serverLevel(), a); }
            catch (RuntimeException error) { LOGGER.error("A search could not finish", error); }
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { ACTIVE.remove(event.getEntity().getUUID()); }

    /** The end of a search: the finds (or the container), the marks, the XP, and whether anybody in the room saw it. Visible for the tests. */
    static List<ItemStack> finish(ServerPlayer player, ServerLevel level, Active a) {
        Optional<CityBuildings.Building> found = foreignBuilding(player, level, a.pos());
        if (found.isEmpty()) return List.of();
        CityBuildings.Building building = found.get();
        long now = LockService.clock(level.getServer());
        Records records = records(level.getServer());
        String city = building.city();
        List<ItemStack> got = new ArrayList<>();
        if (a.container()) {
            records.mark(a.pos().asLong(), now);
            BlockEntity be = level.getBlockEntity(a.pos());
            if (be instanceof RandomizableContainerBlockEntity box) {
                box.unpackLootTable(player);
                for (int i = 0; i < box.getContainerSize(); i++) { ItemStack stack = box.getItem(i); if (!stack.isEmpty()) { StolenGoods.mark(stack, city, player.getUUID()); got.add(stack.copy()); } }
                box.setChanged();
                if (be instanceof MenuProvider provider) player.openMenu(provider);
            }
        } else {
            if (!records.ready(a.pos().asLong(), now, LOADER.refillTicks())) return List.of();
            records.mark(a.pos().asLong(), now);
            RandomSource random = RandomSource.create(PopulationPlan.mix(a.pos().asLong() ^ level.getSeed() ^ now * 0x9E3779B97F4A7C15L));
            got = roll(a.kind(), Thievery.tier(building), use(building), random);
            for (ItemStack stack : got) {
                StolenGoods.mark(stack, city, player.getUUID());
                if (!player.getInventory().add(stack.copy())) player.drop(stack.copy(), false);
            }
        }
        finds += got.size();
        int xp = 0;
        for (ItemStack stack : got) xp += Math.max(1, FenceCatalog.INSTANCE.worth(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) * stack.getCount() / 8);
        xp = Math.min(MAX_XP_PER_SEARCH, got.isEmpty() ? 0 : xp);
        xp = Thievery.award(player, xp, "", false);
        if (!a.container()) {
            String what = got.isEmpty() ? "Nothing worth taking." : summary(got);
            Notice.send(player, got.isEmpty() ? Notice.Kind.INFO : Notice.Kind.LOOT, got.isEmpty() ? "Searched the " + a.kind().title() : "Found in the " + a.kind().title(), what + (xp > 0 ? " +" + xp + " Thief XP." : ""));
        }
        if (noticed(player, level, building, a.pos())) {
            caught++;
            player.sendSystemMessage(Component.literal("Somebody in the room saw you going through their things.").withStyle(ChatFormatting.RED));
            PoliceResponse.raise(player, level, building, a.pos(), false, false, true);
        }
        return got;
    }

    /** The finds of one search: one draw, a second one in a wealthy place, each from the entries the tier and the building's use allow, or nothing. Pure apart from the registry (tested). */
    static List<ItemStack> roll(Kind kind, int tier, String use, RandomSource random) {
        List<ItemStack> out = new ArrayList<>();
        int draws = tier >= 4 ? 2 : 1;
        for (int d = 0; d < draws; d++) {
            List<Entry> fits = new ArrayList<>();
            int total = 0;
            for (Entry e : kind.loot()) {
                if (tier < e.minTier() || tier > e.maxTier() || (!e.uses().isEmpty() && !e.uses().contains(use))) continue;
                if (!e.requiresMod().isEmpty() && !ModList.get().isLoaded(e.requiresMod())) continue;
                fits.add(e); total += e.weight();
            }
            // the chance of nothing falls as the tier rises: a shabby drawer is mostly empty, a wealthy one rarely
            int empty = Math.max(4, kind.emptyWeight() * (8 - tier) / 7);
            if (fits.isEmpty()) continue;
            int pick = random.nextInt(total + empty);
            if (pick >= total) continue;
            for (Entry e : fits) {
                pick -= e.weight();
                if (pick >= 0) continue;
                Item item = BuiltInRegistries.ITEM.get(e.item());
                if (item == null || item == net.minecraft.world.item.Items.AIR) break;
                ItemStack stack = new ItemStack(item, e.min() + random.nextInt(e.max() - e.min() + 1));
                if (!e.nbt().isEmpty()) {
                    try { stack.getOrCreateTag().merge(TagParser.parseTag(e.nbt())); }
                    catch (Exception error) { LOGGER.warn("Bad nbt in search loot {}: {}", e.item(), error.toString()); }
                }
                out.add(stack);
                break;
            }
        }
        return out;
    }

    /** True when a person of the household, in the building and in sight, notices the search. */
    static boolean noticed(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos at) {
        double radius = Thievery.has(player, "thief.quick_hands") ? NOTICE_QUIET : player.isCrouching() && Thievery.has(player, "thief.soft_steps") ? NOTICE_CROUCHED : NOTICE;
        return !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(at).inflate(radius), person -> person.isAlive() && !person.police() && person.homeKey() == building.key()
            && person.distanceTo(player) <= radius && person.hasLineOfSight(player)).isEmpty();
    }

    static String summary(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack s : stacks) parts.add((s.getCount() > 1 ? s.getCount() + " " : "") + s.getHoverName().getString());
        return String.join(", ", parts) + ".";
    }

    // ------------------------------------------------------------------------------------------------ seams
    static boolean searching(UUID player) { return ACTIVE.containsKey(player); }
    static Active activeFor(UUID player) { return ACTIVE.get(player); }
    static void resetForTest() { ACTIVE.clear(); searches = finds = caught = 0; }
    static String status() { return "searches " + searches + ", finds " + finds + ", caught " + caught + ", in progress " + ACTIVE.size(); }
}
