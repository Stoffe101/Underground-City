package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roadside encounters (D-139): now and then, beside the road a little ahead of a traveller, somebody or something worth stopping for. A stranded driver who needs a drink or a part,
 * a courier with a letter that pays on delivery in the next city, a man selling goods that fell off a lorry, a load spilled at the verge. Every one is optional: it stands off the
 * deck (never on it), says what it wants, and is left behind by walking on, with nothing lost.
 *
 * <p>How often: a player earns the next encounter only by time spent travelling on the open road ({@link #QUIET_SECONDS} of it), then has a {@link #CHANCE} chance at each
 * check, so a crossing of ten to thirteen minutes meets one or two (never three) and a short trip often none. Nothing is set near a city, a shelter, a roadside place or a checkpoint (they are
 * the road's fixed company), and a partner travelling within {@link #SHARE_RANGE} blocks shares the encounter and the quiet time after it, so two players are not offered two.
 *
 * <p>What it may do: every outcome is settled through the work-order ledger's single commit point ({@link WorkOrderLedger#prepareEncounter}): what is handed over, what is given,
 * the credits either way, career XP and any heat are written durably before the live inventory or the Q Shop wallet changes, once per encounter per player, so a crash replays it
 * once and a second click, a second player or a copied envelope never pays twice. An encounter nobody is near ends on its own; its person leaves and the props it set are taken
 * back, except a barrel somebody opened (whatever is in it now may be theirs).
 *
 * <p>Where it is allowed: in a world that has the roadside places ({@link RoadsidePlaces#enabled}): a new archipelago world, or one an operator enabled.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class RoadEncounters {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String TAG = "uc_encounter", KEY = "underground_city_road_encounters";
    /** How often travellers are looked at; seconds of road travel a player needs between two encounters; the chance at each later check. */
    static final int CHECK_TICKS = 200, QUIET_SECONDS = 300;
    static final double CHANCE = 0.15;
    /** How far up the road an encounter is set, plus up to this much more; how far beyond the road's reach it stands. */
    static final int AHEAD = 56, AHEAD_SPREAD = 16, OFF_ROAD = 4;
    /**
     * No encounter within this many blocks (along the road) of a city circle, nor this near a shelter or a checkpoint's edge (a roadside place: thirty more, from its corner). 80 left
     * the Ironhaven-Valewood road, two thirds bridges, almost no dry verge between its shelters and places (journey run 58: "no fitting spot" 150 blocks out of Ironhaven).
     */
    static final int CITY_CLEARANCE = 120, CLEAR_OF_FIXED = 40;
    /** A partner this near shares an encounter; an encounter ends at once when every player is this far, or after its lifetime with nobody near. */
    static final int SHARE_RANGE = 96, FORGET_RANGE = 192, NEAR = 24;
    static final long LIFETIME_TICKS = 20L * 60 * 8, AFTER_RESOLVED_TICKS = 20L * 40;
    private static final double REACH_SQUARED = 64.0;
    /** A traveller is moving along the road when they covered at least this much of it between two checks. */
    static final double MOVING = 18.0;
    static final TagKey<Item> DRINKS = TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(UndergroundCity.MOD_ID, "roadside_drinks"));

    private RoadEncounters() {}

    // ------------------------------------------------------------------------------------------ the catalog
    record Person(String name, String profession) {}
    /** One prop block, at {@code along} blocks along the road, {@code out} away from it and {@code up} above the ground. */
    record Prop(int along, int out, int up, String block, String loot) {}
    /**
     * One answer: what it needs (an item or a tag, and credits), what it gives (credits, career XP, items, items from a loot table, optionally marked stolen, or the courier's
     * letter), the chance of heat in the nearest city, and what the person says.
     */
    record Choice(String id, String label, String needItem, String needTag, int needCount, int cost, int pay, int xp, String give, int giveCount, String giveLoot,
                  boolean stolen, boolean letter, int heat, double heatChance, String reply, boolean resolves) {}
    record Encounter(String id, String title, String kind, List<String> roads, int weight, List<Person> people, List<String> openings, List<Choice> choices, List<Prop> props, String notice) {
        boolean onRoad(String road) { return roads.isEmpty() || roads.contains(road); }
    }

    public static final class Catalog extends SimpleJsonResourceReloadListener {
        private volatile Map<String, Encounter> encounters = Map.of();

        private Catalog() { super(new Gson(), "road_encounters"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Encounter> next = new LinkedHashMap<>();
            try {
                for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    Encounter encounter = parse(entry.getValue().getAsJsonObject());
                    next.put(encounter.id(), encounter);
                }
                encounters = Map.copyOf(next);
                LOGGER.info("Loaded {} Underground City road encounters", encounters.size());
            } catch (RuntimeException error) {
                LOGGER.error("Road encounter data is invalid; keeping the previous encounters", error);
            }
        }

        static Encounter parse(JsonObject json) {
            if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("road encounter: schema_version must be 1");
            String id = json.get("id").getAsString();
            if (!id.matches("[a-z0-9_]{3,40}")) throw new IllegalArgumentException("road encounter: bad id " + id);
            String kind = json.get("kind").getAsString();
            if (!Set.of("legit", "underground", "environmental").contains(kind)) throw new IllegalArgumentException(id + ": kind must be legit, underground or environmental");
            List<String> roads = new ArrayList<>();
            if (json.has("roads")) json.getAsJsonArray("roads").forEach(e -> roads.add(e.getAsString()));
            List<Person> people = new ArrayList<>();
            if (json.has("people")) for (JsonElement e : json.getAsJsonArray("people")) {
                JsonObject p = e.getAsJsonObject();
                people.add(new Person(p.get("name").getAsString(), p.has("profession") ? p.get("profession").getAsString() : "none"));
            }
            List<String> openings = new ArrayList<>();
            if (json.has("openings")) json.getAsJsonArray("openings").forEach(e -> openings.add(e.getAsString()));
            if (!people.isEmpty() && openings.isEmpty()) throw new IllegalArgumentException(id + ": somebody who says nothing");
            List<Choice> choices = new ArrayList<>();
            if (json.has("choices")) for (JsonElement e : json.getAsJsonArray("choices")) {
                JsonObject c = e.getAsJsonObject();
                JsonObject needs = c.has("needs") ? c.getAsJsonObject("needs") : new JsonObject();
                JsonObject gives = c.has("gives") ? c.getAsJsonObject("gives") : new JsonObject();
                Choice choice = new Choice(c.get("id").getAsString(), c.get("label").getAsString(),
                    needs.has("item") ? needs.get("item").getAsString() : "", needs.has("tag") ? needs.get("tag").getAsString() : "", needs.has("count") ? needs.get("count").getAsInt() : 0,
                    c.has("cost") ? c.get("cost").getAsInt() : 0, c.has("pay") ? c.get("pay").getAsInt() : 0, c.has("xp") ? c.get("xp").getAsInt() : 0,
                    gives.has("item") ? gives.get("item").getAsString() : "", gives.has("count") ? gives.get("count").getAsInt() : 0, gives.has("loot") ? gives.get("loot").getAsString() : "",
                    gives.has("stolen") && gives.get("stolen").getAsBoolean(), gives.has("letter") && gives.get("letter").getAsBoolean(),
                    c.has("heat") ? c.get("heat").getAsInt() : 0, c.has("heat_chance") ? c.get("heat_chance").getAsDouble() : 0,
                    c.get("reply").getAsString(), !c.has("resolves") || c.get("resolves").getAsBoolean());
                if (!choice.id().matches("[a-z_]{2,20}") || Set.of("bye", "end").contains(choice.id())) throw new IllegalArgumentException(id + ": bad choice id " + choice.id());
                if (choice.cost() < 0 || choice.pay() < 0 || choice.xp() < 0 || choice.pay() > 500 || choice.cost() > 500 || choice.heatChance() < 0 || choice.heatChance() > 1)
                    throw new IllegalArgumentException(id + ": choice " + choice.id() + " is out of range");
                if (choice.needCount() > 0 && choice.needItem().isEmpty() == choice.needTag().isEmpty()) throw new IllegalArgumentException(id + ": choice " + choice.id() + " needs one item or one tag");
                choices.add(choice);
            }
            List<Prop> props = new ArrayList<>();
            if (json.has("props")) for (JsonElement e : json.getAsJsonArray("props")) {
                JsonObject p = e.getAsJsonObject();
                var at = p.getAsJsonArray("at");
                Prop prop = new Prop(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt(), p.get("block").getAsString(), p.has("loot") ? p.get("loot").getAsString() : "");
                if (Math.abs(prop.along()) > 4 || prop.out() < 0 || prop.out() > 4 || prop.up() < 0 || prop.up() > 2) throw new IllegalArgumentException(id + ": a prop outside its 9 x 5 x 3 plot");
                props.add(prop);
            }
            if (people.isEmpty() && props.isEmpty()) throw new IllegalArgumentException(id + ": an encounter with nobody and nothing");
            return new Encounter(id, json.get("title").getAsString(), kind, List.copyOf(roads), Math.max(1, json.has("weight") ? json.get("weight").getAsInt() : 1),
                List.copyOf(people), List.copyOf(openings), List.copyOf(choices), List.copyOf(props), json.has("notice") ? json.get("notice").getAsString() : "");
        }

        public List<Encounter> all() { return List.copyOf(encounters.values()); }
        public Encounter find(String id) { return encounters.get(id); }
    }

    public static final Catalog CATALOG = new Catalog();

    static boolean enabled(ServerLevel level) { return RoadsidePlaces.enabled(level); }

    // ------------------------------------------------------------------------------------------ the world's record
    /** One encounter in the world: where, who, what was set down, and whether it was settled. */
    static final class Active {
        UUID id; String encounter, road; BlockPos anchor; long created, resolved = -1; int person; String destination = "";
        final List<UUID> people = new ArrayList<>();
        /** Each prop: where, the block set there, and what stood there before. */
        final List<CompoundTag> props = new ArrayList<>();
        boolean ending;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", id); tag.putString("encounter", encounter); tag.putString("road", road); tag.putLong("anchor", anchor.asLong());
            tag.putLong("created", created); tag.putLong("resolved", resolved); tag.putInt("person", person); tag.putString("destination", destination); tag.putBoolean("ending", ending);
            ListTag who = new ListTag();
            for (UUID p : people) who.add(NbtUtils.createUUID(p));
            tag.put("people", who);
            ListTag set = new ListTag();
            set.addAll(props);
            tag.put("props", set);
            return tag;
        }

        static Active load(CompoundTag tag) {
            Active a = new Active();
            a.id = tag.getUUID("id"); a.encounter = tag.getString("encounter"); a.road = tag.getString("road"); a.anchor = BlockPos.of(tag.getLong("anchor"));
            a.created = tag.getLong("created"); a.resolved = tag.getLong("resolved"); a.person = tag.getInt("person"); a.destination = tag.getString("destination"); a.ending = tag.getBoolean("ending");
            for (Tag p : tag.getList("people", Tag.TAG_INT_ARRAY)) a.people.add(NbtUtils.loadUUID(p));
            for (Tag p : tag.getList("props", Tag.TAG_COMPOUND)) a.props.add((CompoundTag) p);
            return a;
        }
    }

    /** A traveller's road time since their last encounter and where they were at the last check. */
    static final class Traveller {
        int roadSeconds; String road = ""; double s = Double.NaN; String last = "";
    }

    static final class State extends SavedData {
        final Map<UUID, Traveller> travellers = new HashMap<>();
        final Map<UUID, Active> active = new LinkedHashMap<>();
        /** Letters paid out, by their encounter id: a letter is worth its fee once, to whoever delivers it. */
        final Set<UUID> delivered = new HashSet<>();
        /** The Northgate depot's freight (D-156), per player: when the next manifest is due (active ticks) and how many were handed over (the destinations take turns). */
        final Map<UUID, Long> freightNext = new HashMap<>();
        final Map<UUID, Integer> freightCount = new HashMap<>();
        int started, settled;

        static State load(CompoundTag tag) {
            State state = new State();
            CompoundTag who = tag.getCompound("travellers");
            for (String key : who.getAllKeys()) {
                CompoundTag t = who.getCompound(key);
                Traveller traveller = new Traveller();
                traveller.roadSeconds = t.getInt("road_seconds"); traveller.last = t.getString("last");
                state.travellers.put(UUID.fromString(key), traveller);
            }
            for (Tag a : tag.getList("active", Tag.TAG_COMPOUND)) { Active active = Active.load((CompoundTag) a); state.active.put(active.id, active); }
            for (Tag d : tag.getList("delivered", Tag.TAG_INT_ARRAY)) state.delivered.add(NbtUtils.loadUUID(d));
            state.started = tag.getInt("started"); state.settled = tag.getInt("settled");
            CompoundTag freight = tag.getCompound("freight");
            for (String key : freight.getAllKeys()) {
                CompoundTag f = freight.getCompound(key);
                UUID id = UUID.fromString(key);
                state.freightNext.put(id, f.getLong("next"));
                state.freightCount.put(id, f.getInt("count"));
            }
            return state;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag who = new CompoundTag();
            travellers.forEach((id, t) -> { CompoundTag e = new CompoundTag(); e.putInt("road_seconds", t.roadSeconds); e.putString("last", t.last); who.put(id.toString(), e); });
            tag.put("travellers", who);
            ListTag list = new ListTag();
            for (Active a : active.values()) list.add(a.save());
            tag.put("active", list);
            ListTag paid = new ListTag();
            for (UUID d : delivered) paid.add(NbtUtils.createUUID(d));
            tag.put("delivered", paid);
            tag.putInt("started", started); tag.putInt("settled", settled);
            CompoundTag freight = new CompoundTag();
            freightNext.forEach((id, next) -> { CompoundTag f = new CompoundTag(); f.putLong("next", next); f.putInt("count", freightCount.getOrDefault(id, 0)); freight.put(id.toString(), f); });
            tag.put("freight", freight);
            return tag;
        }

        Traveller traveller(UUID player) { return travellers.computeIfAbsent(player, k -> new Traveller()); }
    }

    static State state(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY);
    }

    // ------------------------------------------------------------------------------------------ pure rules
    /** Whether a traveller's next check may start an encounter: enough road time behind them, then the roll. */
    static boolean due(int roadSeconds, double roll) { return roadSeconds >= QUIET_SECONDS && roll < CHANCE; }

    /** A weighted pick among the encounters of a road, never the one this traveller met last when there is another. */
    static Encounter pick(List<Encounter> all, String road, String last, RandomSource random) {
        List<Encounter> fit = all.stream().filter(e -> e.onRoad(road)).toList();
        if (fit.size() > 1) fit = fit.stream().filter(e -> !e.id().equals(last)).toList();
        int total = fit.stream().mapToInt(Encounter::weight).sum();
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (Encounter e : fit) { roll -= e.weight(); if (roll < 0) return e; }
        return fit.get(fit.size() - 1);
    }

    /** The city at the end of {@code plan} in the direction of travel ({@code direction} > 0 toward the end of its line). */
    static String cityAhead(CorridorPlan plan, int direction) {
        double[] end = plan.pointAt(direction > 0 ? plan.length() : 0);
        String best = "";
        double nearest = Double.MAX_VALUE;
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) {
            double d = Math.hypot(region.centerChunkX() * 16 + 8 - end[0], region.centerChunkZ() * 16 + 8 - end[1]);
            if (d < nearest) { nearest = d; best = region.id(); }
        }
        return best;
    }

    /** A letter's fee: a little for the trouble plus a little for each 25 blocks still to go, between 20 and 75 credits. */
    static int letterFee(double blocksToGo) { return (int) Math.max(20, Math.min(75, 20 + blocksToGo / 25.0)); }

    // ------------------------------------------------------------------------------------------ the traveller's check
    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % CHECK_TICKS != 59 || !enabled(level)) return;
        MinecraftServer server = level.getServer();
        State state = state(server);
        tend(level, state);
        if (level.players().isEmpty() || CATALOG.all().isEmpty()) return;
        List<CorridorPlan> plans = CorridorRoadFeature.ready(level);   // the built plans: bridges, water and deck heights are known
        if (plans.isEmpty()) return;
        for (ServerPlayer player : level.players()) {
            if (player instanceof FakePlayer || player.isSpectator() || player.isCreative()) continue;
            Traveller traveller = state.traveller(player.getUUID());
            CorridorPlan plan = null;
            CorridorPlan.Hit hit = null;
            for (CorridorPlan p : plans) { CorridorPlan.Hit h = p.nearest(player.getX(), player.getZ()); if (h != null && (hit == null || h.distance() < hit.distance())) { plan = p; hit = h; } }
            if (plan == null) { traveller.road = ""; traveller.s = Double.NaN; continue; }
            double moved = plan.id().equals(traveller.road) && !Double.isNaN(traveller.s) ? hit.s() - traveller.s : 0;
            traveller.road = plan.id(); traveller.s = hit.s();
            if (Math.abs(moved) < MOVING) continue;
            traveller.roadSeconds += CHECK_TICKS / 20;
            state.setDirty();
            if (!due(traveller.roadSeconds, level.random.nextDouble())) continue;
            int direction = moved > 0 ? 1 : -1;
            if (start(level, state, plans, plan, hit.s(), direction, player, null) != null) {
                for (ServerPlayer other : level.players()) if (other.distanceToSqr(player) < SHARE_RANGE * SHARE_RANGE) state.traveller(other.getUUID()).roadSeconds = 0;
            }
        }
    }

    /**
     * Sets an encounter up the road from arc position {@code s} for {@code player}: the given one, or a weighted pick for the road. Returns it, or null when there was no fitting
     * spot (too near a city or the road's fixed company, ground not loaded, wet or built on), in which case nothing changed.
     */
    static Active start(ServerLevel level, State state, List<CorridorPlan> plans, CorridorPlan plan, double s, int direction, ServerPlayer player, Encounter forced) {
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
        double at = s + direction * (AHEAD + level.random.nextInt(AHEAD_SPREAD + 1));
        if (at < first + CITY_CLEARANCE || at > last - CITY_CLEARANCE) return refuse("too near a city");
        if (plan.bridgeAt(at) || plan.waterAt(at)) return refuse("on a bridge or over water");
        Traveller traveller = state.traveller(player.getUUID());
        Encounter encounter = forced != null ? forced : pick(CATALOG.all(), plan.id(), traveller.last, level.random);
        if (encounter == null) return null;
        double[] point = plan.pointAt(at), behind = plan.pointAt(Math.max(0, at - 3)), ahead = plan.pointAt(Math.min(plan.length(), at + 3));
        double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
        if (length < 1e-6) return null;
        tx /= length; tz /= length;
        int side = level.random.nextBoolean() ? 1 : -1;
        Spot spot = spot(level, plans, plan, point, tx, tz, side);
        if (spot == null) spot = spot(level, plans, plan, point, tx, tz, -side);
        if (spot == null) return null;
        Active active = new Active();
        active.id = UUID.randomUUID(); active.encounter = encounter.id(); active.road = plan.id(); active.anchor = spot.anchor; active.created = level.getGameTime();
        active.person = encounter.people().isEmpty() ? -1 : level.random.nextInt(encounter.people().size());
        active.destination = cityAhead(plan, direction);
        state.active.put(active.id, active);   // before its person is added: the join guard lets in only people of encounters the world knows
        // the plot's wild growth is cleared (and put back when the encounter is over, like the props); a prop's own cell keeps what was there as its "was"
        java.util.Set<BlockPos> propCells = new java.util.HashSet<>();
        for (Prop prop : encounter.props()) propCells.add(spot.offset(prop));
        for (int a = -4; a <= 4; a++) for (int o = 0; o <= 4; o++) for (int up = 0; up <= 2; up++) {
            BlockPos cell = spot.offset(new Prop(a, o, up, "", ""));
            BlockState here = level.getBlockState(cell);
            if (propCells.contains(cell) || !overgrown(here)) continue;
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
            CompoundTag record = new CompoundTag();
            record.putLong("pos", cell.asLong()); record.put("was", NbtUtils.writeBlockState(here)); record.put("put", NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState())); record.putBoolean("loot", false);
            active.props.add(record);
        }
        for (Prop prop : encounter.props()) {
            BlockPos at3 = spot.offset(prop);
            BlockState was = level.getBlockState(at3);
            BlockState put = BuiltInRegistries.BLOCK.get(new ResourceLocation(prop.block())).defaultBlockState();
            if (put.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) put = put.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, spot.along);
            level.setBlock(at3, put, 3);
            if (!prop.loot().isEmpty() && level.getBlockEntity(at3) instanceof RandomizableContainerBlockEntity container) container.setLootTable(new ResourceLocation(prop.loot()), level.random.nextLong());
            CompoundTag record = new CompoundTag();
            record.putLong("pos", at3.asLong()); record.put("was", NbtUtils.writeBlockState(was)); record.put("put", NbtUtils.writeBlockState(put)); record.putBoolean("loot", !prop.loot().isEmpty());
            active.props.add(record);
        }
        if (active.person >= 0) {
            Person who = encounter.people().get(active.person);
            Villager person = EntityType.VILLAGER.create(level);
            if (person != null) {
                person.setVillagerData(new VillagerData(VillagerType.PLAINS, CityNpcs.profession(who.profession()), 1));
                person.setVillagerXp(1);
                person.setCustomName(Component.literal(who.name()));
                person.setCustomNameVisible(true);
                person.setPersistenceRequired();
                person.setInvulnerable(true);
                CompoundTag tag = new CompoundTag();
                tag.putUUID("instance", active.id);
                tag.putLong("home", spot.anchor.asLong());
                person.getPersistentData().put(TAG, tag);
                person.restrictTo(spot.anchor, 2);
                person.moveTo(spot.anchor.getX() + .5, spot.anchor.getY(), spot.anchor.getZ() + .5, level.random.nextFloat() * 360, 0);
                if (level.addFreshEntity(person)) active.people.add(person.getUUID());
            }
        }
        state.started++;
        Traveller t = state.traveller(player.getUUID());
        t.roadSeconds = 0; t.last = encounter.id();
        state.setDirty();
        String where = side(plan, at, spot.anchor) + " of the road ahead";
        if (!encounter.notice().isEmpty()) Notice.send(player, Notice.Kind.INFO, encounter.title(), encounter.notice() + " (" + where + ")");
        LOGGER.info("[UC] road encounter {} set for {} at {} on {} (s {})", encounter.id(), player.getGameProfile().getName(), spot.anchor.toShortString(), plan.id(), Math.round(at));
        return active;
    }

    private static String side(CorridorPlan plan, double s, BlockPos at) {
        CorridorPlan.Hit hit = plan.project(at.getX() + .5, at.getZ() + .5);
        return hit != null && hit.u() > 0 ? "To the right" : "To the left";
    }

    /** Where an encounter stands: its person's spot, and the two cardinal directions its props are laid along and away from the road. */
    /** Why spots were refused since the last {@link #refusals()} reset: the operator's {@code meet} says so when it finds none. */
    private static final Map<String, Integer> REFUSED = new java.util.LinkedHashMap<>();
    private static <T> T refuse(String why) { REFUSED.merge(why, 1, Integer::sum); return null; }
    static synchronized String refusals() {
        String said = REFUSED.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "));
        REFUSED.clear();
        return said;
    }

    /** Wild growth an encounter may stand in, cleared while it stands and put back after: leaves, grass, flowers and the like (never a log, which may be somebody's wall). */
    static boolean overgrown(BlockState state) { return state.is(BlockTags.LEAVES) || state.canBeReplaced() && state.getFluidState().isEmpty() && !state.isAir(); }

    record Spot(BlockPos anchor, Direction along, Direction out) {
        BlockPos offset(Prop prop) { return anchor.relative(along, prop.along()).relative(out, prop.out()).above(prop.up()); }
    }

    /**
     * The spot {@code OFF_ROAD} blocks beyond the road's reach on {@code side}: in loaded chunks only, on dry standable natural ground with room for the props, clear of the
     * cities, their buildings, the checkpoints, the shelters and the roadside places, and not on anything somebody built.
     */
    static Spot spot(ServerLevel level, List<CorridorPlan> plans, CorridorPlan plan, double[] point, double tx, double tz, int side) {
        double nx = -tz * side, nz = tx * side, out = plan.reach() + OFF_ROAD + 1;
        int x = (int) Math.floor(point[0] + nx * out), z = (int) Math.floor(point[1] + nz * out);
        Direction along = Math.abs(tx) > Math.abs(tz) ? (tx > 0 ? Direction.EAST : Direction.WEST) : (tz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction away = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? Direction.EAST : Direction.WEST) : (nz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (away.getAxis() == along.getAxis()) return refuse("the road turns there");
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) if (level.getChunkSource().getChunkNow((x + dx) >> 4, (z + dz) >> 4) == null) return refuse("not loaded");
        DistrictSites.Ground ground = RoadsidePlaces.ground(level, plans);
        for (int a = -5; a <= 5; a++) for (int o = -1; o <= 5; o++) {
            BlockPos column = new BlockPos(x, 0, z).relative(along, a).relative(away, o);
            if (ground.reserved(column.getX(), column.getZ())) return refuse("built or reserved ground");
            if (ground.wet(column.getX(), column.getZ())) return refuse("water");
        }
        BlockPos anchor = DevCommands.surface(level, x, z);
        if (Math.abs(anchor.getY() - DevCommands.surface(level, x + away.getStepX() * 4, z + away.getStepZ() * 4).getY()) > 2) return refuse("too steep");
        if (CityRegions.INSTANCE.cityAt(level, anchor).isPresent() || CityBuildings.INSTANCE.at(level, anchor).isPresent()) return refuse("in a city");
        for (CheckpointCatalog.Checkpoint post : CheckpointCatalog.INSTANCE.all()) {
            double keep = post.radius() + CLEAR_OF_FIXED;
            if (post.dimension().equals(level.dimension().location().toString()) && post.distanceSquared(anchor.getX() + .5, anchor.getZ() + .5) < keep * keep) return refuse("near a checkpoint");
        }
        PlacedSites shelters = PlacedSites.get(level, PlacedSites.SHELTERS), places = PlacedSites.get(level, RoadsidePlaces.KEY);
        for (Long where : shelters.where.values()) if (BlockPos.of(where).distSqr(anchor) < (double) CLEAR_OF_FIXED * CLEAR_OF_FIXED) return refuse("near a shelter");
        for (Long where : places.where.values()) if (BlockPos.of(where).distSqr(anchor) < (double) (CLEAR_OF_FIXED + 30) * (CLEAR_OF_FIXED + 30)) return refuse("near a roadside place");
        // standable: a sturdy natural floor and two clear blocks over it, and the props' plot clear of anything built
        BlockState floor = level.getBlockState(anchor.below());
        // (the surface passes over leaves but stops on a trunk: a spot on a tree is no spot)
        if (!floor.isFaceSturdy(level, anchor.below(), Direction.UP) || !RoadApproaches.natural(floor) || RoadApproaches.growth(floor)
            || !level.getBlockState(anchor).getFluidState().isEmpty()) return refuse("no natural floor");
        Spot spot = new Spot(anchor, along, away);
        for (int a = -4; a <= 4; a++) for (int o = 0; o <= 4; o++) for (int up = 0; up <= 2; up++) {
            BlockPos at = spot.offset(new Prop(a, o, up, "", ""));
            BlockState here = level.getBlockState(at);
            // wild growth is cleared while the encounter stands (journey run 59: the Ironhaven-Valewood verge is forest, and a plot of plain air was found nowhere in 320 blocks)
            if (!here.isAir() && !overgrown(here)) return refuse("something in the way (a trunk, rock or a build)");
        }
        return spot;
    }

    // ------------------------------------------------------------------------------------------ ending
    /** Ends what has run its course: nobody near after its lifetime, everybody far away, or settled a while ago with nobody right beside it. Then takes its props back. */
    static void tend(ServerLevel level, State state) {
        long now = level.getGameTime();
        for (Active active : new ArrayList<>(state.active.values())) {
            double nearest = level.players().stream().mapToDouble(p -> Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(active.anchor)))).min().orElse(Double.MAX_VALUE);
            boolean end = active.ending
                || nearest > FORGET_RANGE
                || (now - active.created > LIFETIME_TICKS && nearest > NEAR)
                || (active.resolved >= 0 && now - active.resolved > AFTER_RESOLVED_TICKS && nearest > NEAR / 2.0);
            if (!end) continue;
            active.ending = true;
            if (finish(level, active)) state.active.remove(active.id);
            state.setDirty();
        }
    }

    /** Removes the person and takes back the props, where the chunks are in the world now. True when nothing is left to do. */
    static boolean finish(ServerLevel level, Active active) {
        boolean done = true;
        for (UUID id : active.people) {
            Entity person = level.getEntity(id);
            if (person != null) person.discard();
        }
        for (CompoundTag prop : new ArrayList<>(active.props)) {
            BlockPos at = BlockPos.of(prop.getLong("pos"));
            if (level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) == null) { done = false; continue; }
            BlockState put = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), prop.getCompound("put"));
            BlockState here = level.getBlockState(at);
            // a barrel whose loot was rolled has been opened: what is in it now may be somebody's, so it stays
            boolean opened = prop.getBoolean("loot") && level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity container && !container.saveWithoutMetadata().contains("LootTable");
            if (here.getBlock() == put.getBlock() && !opened) {
                if (level.getBlockEntity(at) instanceof net.minecraft.world.Clearable clearable) clearable.clearContent();
                level.setBlock(at, NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), prop.getCompound("was")), 3);
            }
            active.props.remove(prop);
        }
        return done;
    }

    /** A person of an encounter that is over (or of a world that forgot it) is not let back into the world. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager) || !isEncounter(villager)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        Active active = tag.hasUUID("instance") ? state(level.getServer()).active.get(tag.getUUID("instance")) : null;
        if (active == null || active.ending) { event.setCanceled(true); return; }
        if (tag.contains("home", Tag.TAG_LONG)) villager.restrictTo(BlockPos.of(tag.getLong("home")), 2);
    }

    static boolean isEncounter(Villager villager) { return villager.getPersistentData().contains(TAG, Tag.TAG_COMPOUND); }

    // ------------------------------------------------------------------------------------------ talking
    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !isEncounter(villager)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)) return;
        Active active = activeOf(player.server, villager);
        Encounter encounter = active == null ? null : CATALOG.find(active.encounter);
        if (encounter == null) { player.sendSystemMessage(Component.literal("They have nothing to say to you.").withStyle(ChatFormatting.GRAY)); return; }
        Dialogue.hold(player, villager);
        String opening = active.resolved >= 0 ? "Thanks again. I will be on my way soon." : encounter.openings().get(Math.floorMod(active.id.hashCode(), encounter.openings().size()));
        TerminalNetwork.sendDialogue(player, villager.getId(), villager.getCustomName() == null ? encounter.title() : villager.getCustomName().getString(), encounter.title(),
            "", opening.replace("{city}", TerminalScreenText.capitalize(active.destination)), choices(player, encounter, active));
    }

    static Active activeOf(MinecraftServer server, Villager villager) {
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        return tag.hasUUID("instance") ? state(server).active.get(tag.getUUID("instance")) : null;
    }

    static List<Dialogue.Choice> choices(ServerPlayer player, Encounter encounter, Active active) {
        List<Dialogue.Choice> out = new ArrayList<>();
        if (active.resolved < 0) for (Choice choice : encounter.choices()) {
            String blocker = blocker(player, choice);
            out.add(new Dialogue.Choice(choice.id(), choice.label().replace("{city}", TerminalScreenText.capitalize(active.destination)), blocker.isEmpty(), blocker));
        }
        out.add(new Dialogue.Choice("bye", "Goodbye", true, ""));
        return out;
    }

    /** Why a player cannot take a choice now, or "" when they can. */
    static String blocker(ServerPlayer player, Choice choice) {
        boolean paying = choice.pay() > 0 || choice.cost() > 0 || choice.xp() > 0 || choice.letter() || !choice.give().isEmpty() || !choice.giveLoot().isEmpty() || choice.needCount() > 0;
        if (paying && CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return "Register first";
        if (choice.needCount() > 0 && count(player.getInventory(), choice) < choice.needCount()) return "You have none to give";
        if (choice.cost() > 0 && QShopEconomy.available() && QShopEconomy.balance(player) < choice.cost()) return "Not enough credits";
        if ((choice.pay() > 0 || choice.cost() > 0) && (!QShopEconomy.available() || !QShopEconomy.configured(player.server))) return "Needs Q Shop";
        return "";
    }

    private static boolean matches(ItemStack stack, Choice choice) {
        if (stack.isEmpty() || StolenGoods.is(stack)) return false;
        if (!choice.needItem().isEmpty()) return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(choice.needItem());
        return stack.is(TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(choice.needTag())));
    }

    static int count(Inventory inventory, Choice choice) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (matches(inventory.getItem(i), choice)) n += inventory.getItem(i).getCount();
        return n;
    }

    /** A choice from the dialogue screen (routed here by {@link Dialogue#choose}). */
    static void choose(ServerPlayer player, Villager villager, String id) {
        if (player.distanceToSqr(villager) > REACH_SQUARED) return;
        if (id.equals("end")) { Dialogue.release(villager); return; }
        if (id.equals("bye")) { Dialogue.release(villager); TerminalNetwork.closeDialogue(player); return; }
        Active active = activeOf(player.server, villager);
        Encounter encounter = active == null ? null : CATALOG.find(active.encounter);
        if (encounter == null) return;
        Choice choice = encounter.choices().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        if (choice == null) return;
        String name = villager.getCustomName() == null ? encounter.title() : villager.getCustomName().getString();
        String reply;
        try {
            reply = settle(player, encounter, active, choice);
        } catch (IllegalStateException refused) {
            reply = refused.getMessage();
        }
        TerminalNetwork.sendDialogue(player, villager.getId(), name, encounter.title(), "", reply.replace("{city}", TerminalScreenText.capitalize(active.destination)), choices(player, encounter, active));
    }

    /**
     * Settles a choice: checks it again on the server, plans the inventory on a copy, commits the outcome durably through the ledger and applies it. A choice that only talks
     * changes nothing. Returns what the person says. Throws (with the reason as its message) when the choice cannot be taken now. Visible for the GameTests.
     */
    static String settle(ServerPlayer player, Encounter encounter, Active active, Choice choice) {
        return settle(player, WorkOrderLedger.get(player.server), encounter, active, choice);
    }

    static String settle(ServerPlayer player, WorkOrderLedger ledger, Encounter encounter, Active active, Choice choice) {
        if (active.resolved >= 0) throw new IllegalStateException("That is already settled.");
        String blocker = blocker(player, choice);
        if (!blocker.isEmpty()) throw new IllegalStateException(blocker + ".");
        boolean changes = choice.pay() > 0 || choice.cost() > 0 || choice.xp() > 0 || choice.letter() || !choice.give().isEmpty() || !choice.giveLoot().isEmpty() || choice.needCount() > 0;
        State state = state(player.server);
        if (!changes) {
            if (choice.resolves()) { active.resolved = player.serverLevel().getGameTime(); state.setDirty(); }
            return choice.reply();
        }
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow(() -> new IllegalStateException("Register first."));
        if (ledger.player(player.getUUID()).contains("pending")) WorkOrderService.recover(player, ledger);
        // plan on a copy: take what is handed over, then add what is given; refuse when it does not all fit
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        int need = choice.needCount();
        for (int i = 0; i < after.getContainerSize() && need > 0; i++) {
            ItemStack stack = after.getItem(i);
            if (!matches(stack, choice)) continue;
            int take = Math.min(need, stack.getCount());
            stack.shrink(take);
            need -= take;
        }
        if (need > 0) throw new IllegalStateException("You have none to give.");
        List<ItemStack> given = new ArrayList<>();
        if (!choice.give().isEmpty()) given.add(new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(choice.give())), Math.max(1, choice.giveCount())));
        if (!choice.giveLoot().isEmpty()) {
            var table = player.server.getLootData().getLootTable(new ResourceLocation(choice.giveLoot()));
            RandomSource seeded = RandomSource.create(active.id.getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits());
            given.addAll(table.getRandomItems(new LootParams.Builder(player.serverLevel()).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(active.anchor)).create(LootContextParamSets.CHEST), seeded.nextLong()));
        }
        if (choice.stolen()) {
            String from = otherCity(active.destination, active.id);
            for (ItemStack stack : given) StolenGoods.mark(stack, from);
        }
        if (choice.letter()) given.add(letter(active, encounter));
        for (ItemStack stack : given) if (!stack.isEmpty() && !after.add(stack.copy())) throw new IllegalStateException("Make room in your pockets first.");
        double balance = QShopEconomy.balance(player);
        double target = balance + choice.pay() - choice.cost();
        if (target < 0) throw new IllegalStateException("Not enough credits.");
        int xp = choice.xp() > 0 ? ProgressionRules.totalXp(citizen.careerXp(), choice.xp()) : citizen.careerXp();
        Map<String, Integer> heat = Map.of();
        String heated = active.destination;
        if (choice.heat() > 0 && !heated.isEmpty() && player.getRandom().nextDouble() < choice.heatChance()) heat = Map.of(heated, ledger.heatAfter(player.getUUID(), heated, choice.heat()));
        ledger.prepareEncounter(player.getUUID(), active.id, encounter.id(), choice.pay() - choice.cost(), after.save(new ListTag()), target, xp, heat);
        WorkOrderService.recover(player, ledger);
        EconomyTelemetry.record(player, "encounter", "encounter", encounter.id(), "credits", choice.pay() - choice.cost());
        if (choice.resolves()) active.resolved = player.serverLevel().getGameTime();
        state.settled++;
        state.setDirty();
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "encounters");
        String body = (choice.pay() > 0 ? "+" + choice.pay() + " credits" : choice.cost() > 0 ? "-" + choice.cost() + " credits" : "")
            + (choice.xp() > 0 ? (choice.pay() > 0 || choice.cost() > 0 ? ", " : "") + "+" + choice.xp() + " XP" : "")
            + (choice.letter() ? "Deliver the letter at any terminal or counter in " + TerminalScreenText.capitalize(active.destination) + "." : "");
        Notice.send(player, heat.isEmpty() ? Notice.Kind.LOOT : Notice.Kind.HEAT, encounter.title(), body.isEmpty() ? choice.label() : body + (heat.isEmpty() ? "" : " Somebody saw the hand-off: heat in " + TerminalScreenText.capitalize(heated) + "."));
        LOGGER.info("[UC] road encounter {} settled by {} with '{}': credits {}, xp {}, heat {}", encounter.id(), player.getGameProfile().getName(), choice.id(), choice.pay() - choice.cost(), choice.xp(), heat);
        return choice.reply();
    }

    /** The city stolen goods are said to come from: one of the other two (it is never the one they are sold on the way to). */
    static String otherCity(String destination, UUID seed) {
        List<String> others = CityRegions.INSTANCE.all().stream().map(CityRegions.Region::id).filter(id -> !id.equals(destination)).sorted().toList();
        return others.isEmpty() ? "" : others.get(Math.floorMod(seed.hashCode(), others.size()));
    }

    // ------------------------------------------------------------------------------------------ the courier's letter
    static ItemStack letter(Active active, Encounter encounter) {
        ItemStack stack = new ItemStack(UndergroundCity.SEALED_ENVELOPE.get());
        CompoundTag tag = stack.getOrCreateTagElement("uc_letter");
        tag.putUUID("id", letterId(active.id));   // its own id: the encounter that handed it over is settled under the encounter's
        tag.putString("to", active.destination);
        tag.putString("from", encounter.title());
        tag.putInt("fee", letterFee(distanceToCity(active)));
        tag.putInt("xp", 30);
        return stack;
    }

    /** The id a letter is paid under, derived from (and never equal to) the id of the encounter that gave it. */
    static UUID letterId(UUID encounter) { return UUID.nameUUIDFromBytes(("underground_city:letter:" + encounter).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private static double distanceToCity(Active active) {
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) if (region.id().equals(active.destination))
            return Math.max(0, Math.hypot(region.centerChunkX() * 16 + 8 - active.anchor.getX(), region.centerChunkZ() * 16 + 8 - active.anchor.getZ()) - region.cityRadius());
        return 0;
    }

    /**
     * Delivers every courier's letter a player carries that is addressed to the city of {@code pos} (a terminal or a shopkeeper's counter they just used), one ledger commit
     * each. A letter already paid (by anybody) is taken back with a word and pays nothing. Returns the credits paid.
     */
    static int deliver(ServerPlayer player, BlockPos pos) {
        if (player instanceof FakePlayer) return 0;
        return deliver(player, pos, WorkOrderLedger.get(player.server));
    }

    static int deliver(ServerPlayer player, BlockPos pos, WorkOrderLedger ledger) {
        if (player.isCreative() || player.isSpectator()) return 0;
        String city = CityRegions.INSTANCE.cityAt(player.level(), pos).orElse("");
        if (city.isEmpty() || !QShopEconomy.available() || !QShopEconomy.configured(player.server)) return 0;
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) return 0;
        State state = state(player.server);
        int paid = 0;
        for (int guard = 0; guard < 8; guard++) {
            int slot = -1;
            CompoundTag letter = null;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                CompoundTag tag = stack.is(UndergroundCity.SEALED_ENVELOPE.get()) ? stack.getTagElement("uc_letter") : null;
                if (tag != null && tag.hasUUID("id") && tag.getString("to").equals(city)) { slot = i; letter = tag; break; }
            }
            if (letter == null) break;
            UUID id = letter.getUUID("id");
            if (ledger.player(player.getUUID()).contains("pending")) WorkOrderService.recover(player, ledger);
            Inventory after = new Inventory(player);
            after.load(player.getInventory().save(new ListTag()));
            after.getItem(slot).shrink(1);
            boolean already = state.delivered.contains(id) || ledger.hasEncounter(player.getUUID(), id);
            int fee = already ? 0 : Math.max(0, Math.min(75, letter.getInt("fee")));
            int gain = already ? 0 : Math.max(0, Math.min(60, letter.getInt("xp")));
            int xp = gain > 0 ? ProgressionRules.totalXp(citizen.careerXp(), gain) : citizen.careerXp();
            UUID commit = already ? UUID.randomUUID() : id;   // a stale letter is still taken back, through the same durable path
            ledger.prepareEncounter(player.getUUID(), commit, "letter", fee, after.save(new ListTag()), QShopEconomy.balance(player) + fee, xp, Map.of());
            WorkOrderService.recover(player, ledger);
            EconomyTelemetry.record(player, "encounter", "encounter", "letter", "credits", fee);
            boolean freight = Freight.KIND.equals(letter.getString("kind"));   // a depot manifest (D-156) travels the letter's path, under its own name
            if (!already) {
                state.delivered.add(id);
                state.settled++;
                state.setDirty();
                CitizenState.get(player.server).bumpJourney(player.getUUID(), freight ? "freight" : "letters");
                paid += fee;
                Notice.send(player, Notice.Kind.DELIVERED, freight ? "Freight delivered" : "Letter delivered", "+" + fee + " credits for the " + (freight ? "manifest" : "letter") + " to " + TerminalScreenText.capitalize(city) + ".");
                LOGGER.info("[UC] {} {} delivered by {} in {} for {} credits", freight ? "freight manifest" : "letter", id, player.getGameProfile().getName(), city, fee);
            } else {
                player.sendSystemMessage(Component.literal("That " + (freight ? "manifest" : "letter") + " was already delivered; the clerk keeps it.").withStyle(ChatFormatting.GRAY));
            }
        }
        return paid;
    }

    // ------------------------------------------------------------------------------------------ operators
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_encounters").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("status").executes(ctx -> {
            State state = state(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal((enabled(ctx.getSource().getServer().overworld()) ? "Road encounters are on" : "Road encounters are off (they come with /uc_roadside enable)")
                + "; " + state.active.size() + " standing, " + state.started + " set and " + state.settled + " settled in this world."), false);
            for (Active a : state.active.values()) ctx.getSource().sendSuccess(() -> Component.literal(" " + a.encounter + " at " + a.anchor.toShortString() + (a.resolved >= 0 ? " (settled)" : "")), false);
            return state.active.size();
        }));
        // An operator's (and the scripted journey's) way to meet one now: it is set up the road from the player exactly as a natural one would be.
        root.then(Commands.literal("meet").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("encounter", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATALOG.all().stream().map(Encounter::id), builder))
            .executes(ctx -> {
                ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                Encounter encounter = CATALOG.find(StringArgumentType.getString(ctx, "encounter"));
                if (encounter == null) { ctx.getSource().sendFailure(Component.literal("No such encounter.")); return 0; }
                List<CorridorPlan> plans = CorridorRoadFeature.ready(player.serverLevel());
                // the road the player is on: the nearest centreline (the first plan in the list was taken before, whichever road the player stood on)
                CorridorPlan nearestPlan = null;
                double best = Double.MAX_VALUE;
                for (CorridorPlan candidate : plans) {
                    CorridorPlan.Hit h = candidate.nearest(player.getX(), player.getZ());
                    if (h != null && h.distance() < best) { best = h.distance(); nearestPlan = candidate; }
                }
                refusals();
                for (CorridorPlan plan : nearestPlan == null ? List.<CorridorPlan>of() : List.of(nearestPlan)) {
                    CorridorPlan.Hit hit = plan.nearest(player.getX(), player.getZ());
                    if (hit == null) continue;
                    double[] here = plan.pointAt(hit.s()), on = plan.pointAt(Math.min(plan.length(), hit.s() + 5));
                    Vec3 look = player.getLookAngle();
                    int direction = (on[0] - here[0]) * look.x + (on[1] - here[1]) * look.z >= 0 ? 1 : -1;
                    // the first fitting spot from a dozen blocks up the road, looking further on (past a bridge, a shelter or a roadside place) up to a couple of hundred
                    Active active = null;
                    for (int ahead = 12; ahead <= 220 && active == null; ahead += 8)
                        active = start(player.serverLevel(), state(ctx.getSource().getServer()), plans, plan, hit.s() + direction * (ahead - AHEAD), direction, player, encounter);
                    if (active == null) { String why = refusals(); ctx.getSource().sendFailure(Component.literal("No fitting spot up the road (" + why + "); walk on a little and try again.")); return 0; }
                    Active found = active;
                    ctx.getSource().sendSuccess(() -> Component.literal(encounter.title() + " is waiting at " + found.anchor.toShortString() + "."), true);
                    return 1;
                }
                ctx.getSource().sendFailure(Component.literal("That player is not on an open road."));
                return 0;
            }))));
        root.then(Commands.literal("clear").executes(ctx -> {
            State state = state(ctx.getSource().getServer());
            for (Active a : state.active.values()) a.ending = true;
            tend(ctx.getSource().getServer().overworld(), state);
            ctx.getSource().sendSuccess(() -> Component.literal("Every standing encounter was ended."), true);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    /** The current record, for tests. */
    static Optional<Active> activeForTest(MinecraftServer server, UUID id) { return Optional.ofNullable(state(server).active.get(id)); }
}
