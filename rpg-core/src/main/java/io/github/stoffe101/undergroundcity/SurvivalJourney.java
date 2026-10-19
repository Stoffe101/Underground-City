package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.ModReflect.call;
import static io.github.stoffe101.undergroundcity.ModReflect.callOn;
import static io.github.stoffe101.undergroundcity.ModReflect.cls;
import static io.github.stoffe101.undergroundcity.ModReflect.item;

/**
 * The combined journey's survival and economy half (scenario {@code crew} with {@code uc.journey.survival=true}; D-091): what a real two-player session on a real
 * generated world with every pack set loaded does beyond the Crew screen. It runs inside {@link CrewJourneyServer}'s phases, with the two real connected players.
 *
 * <p>Before the abrupt kill (phase 1): the Crew switches to fully shared finances; a wayside shelter with its provisions desk is built outside every city; the
 * second player equips a backpack and fills it, fills a canteen from a pond and drinks, carries a TaCZ gun; the first cooks a meal in a cooking pot; and the second
 * buys two loaves at the wayside desk with the Crew's money, with the process dying right after the purchase's prepared record is written.
 *
 * <p>After the restart (phase 2): recovery has decided the held credits at server start, the purchase is delivered at login, everything the players carry survived in
 * their real player files, the shared stock decrement survived, a replayed purchase is refused, and two more purchases run on the real exchange with partial Crew funding
 * and a refusal. Nothing here is a human playing; it is the same code paths, with real network-connected players and real files, driven by the server.
 */
final class SurvivalJourney {
    private SurvivalJourney() {}

    static final boolean ON = CrewJourneyServer.ENABLED && Boolean.getBoolean("uc.journey.survival");
    static final boolean GENERATED = "generated".equals(System.getProperty("uc.journey.world", ""));
    /** The purchase that is interrupted: two loaves at the wayside desk (7 credits each), paid entirely by the bank. */
    static final int CRASH_UNITS = 2, CRASH_COST = 14;
    private static final String THIRST = "thirstwastaken2:waterskin", BACKPACK = SurvivalSet.BACKPACK, GUN = "tacz:modern_kinetic_gun";

    static void expect(boolean condition, String what) { if (!condition) throw new AssertionError(what); }

    /** The city the journey works in: Ironhaven is centred on the world spawn, so its middle is inside the chunks the server generates at start. */
    static final String CITY = "ironhaven";

    // ------------------------------------------------------------------------------------------------------------ where things stand

    /** A spot of open ground near {@code x, z}: the chunk is generated, the surface found. */
    static BlockPos ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return DevCommands.surface(level, x, z);
    }

    /**
     * Where the Row Flats go on the generated world: inside the city's own exchange circle, on the lowest open ground found by probing a grid around the centre (a street or a
     * park, not a skyscraper's roof), with the whole footprint still inside the circle. Returns the corner (x, surface, z).
     */
    /** How far from the city's centre the Row Flats' site is searched: the cities are 400 to 600 blocks in radius, and every chunk beyond the spawn area is generated for this. */
    static final int PROBE_REACH = 48;

    static BlockPos openGroundInCity(ServerLevel level, String city, int width, int depth) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst().orElseThrow(() -> new IllegalStateException("no city region " + city));
        BlockPos best = null;
        int reach = Math.min(region.radius() / 2, PROBE_REACH);
        for (int dx = -reach; dx <= reach; dx += Math.max(16, reach / 2)) for (int dz = -reach; dz <= reach; dz += Math.max(16, reach / 2)) {
            int x = (int) region.centerX() + dx, z = (int) region.centerZ() + dz;
            BlockPos far = new BlockPos(x + width + 4, 64, z + depth + 4), near = new BlockPos(x - 4, 64, z - 4);
            if (!CityRegions.INSTANCE.cityAt(level, far).equals(java.util.Optional.of(city)) || !CityRegions.INSTANCE.cityAt(level, near).equals(java.util.Optional.of(city))) continue;
            BlockPos here = ground(level, x, z);
            if (best == null || here.getY() < best.getY()) best = here;
        }
        if (best == null) throw new IllegalStateException("no room inside the " + city + " exchange circle for the Row Flats");
        return best;
    }

    /** Where the wayside shelter will stand on a generated world, found while the fixture warms up (see {@link #planWarmUp}). */
    static BlockPos shelterSite;
    static BlockPos flatsCorner;
    private static final List<java.util.function.BooleanSupplier> STAGES = new ArrayList<>();
    private static int stage;
    private static boolean planned;
    private static long warmStarted, lastReport;

    /**
     * Plans the generation of the chunks the fixture will build on, without ever generating one on the server thread. A Lost Cities chunk that has never been generated needs its whole
     * neighbourhood generated first, so asking for one with {@code level.getChunk} blocked the server thread for about a minute and the server watchdog killed the second run of this
     * journey (and the first run's clients were disconnected by their keep-alive timeout when a few such chunks were requested during a login). Here every chunk is requested with a forced-load
     * ticket (added through the chunk cache directly, because {@code ServerLevel.setChunkForced} calls {@code getChunk} itself), which the chunk system generates on its own worker threads, and {@link #warmStep} only polls. The journey does not start until all of them are in memory.
     */
    static void planWarmUp(ServerLevel level, int width, int depth) {
        planned = true;
        warmStarted = lastReport = System.currentTimeMillis();
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(CITY)).findFirst().orElseThrow(() -> new IllegalStateException("no city region " + CITY));
        int reach = Math.min(region.radius() / 2, PROBE_REACH), cx = (int) region.centerX(), cz = (int) region.centerZ();
        // 1. the middle of the city, where the Row Flats will look for open ground
        boolean[] asked1 = {false};
        STAGES.add(() -> awaitChunks(level, cx - reach - 8, cz - reach - 8, cx + reach + width + 12, cz + reach + depth + 12, asked1));
        // 2. the lowest open ground among a grid of candidates whose whole footprint stays inside the city's exchange circle
        STAGES.add(() -> {
            flatsCorner = openGroundInCity(level, CITY, width, depth);
            return true;
        });
        // 3. the country beside the city, where the wayside shelter will stand
        boolean[] asked2 = {false};
        int[] site = outsideEveryCityXZ(level, CITY);
        STAGES.add(() -> awaitChunks(level, site[0] - 24, site[1] - 24, site[0] + 48, site[1] + 40, asked2));
        STAGES.add(() -> {
            shelterSite = ground(level, site[0], site[1]);
            return true;
        });
    }

    private static boolean awaitChunks(ServerLevel level, int x0, int z0, int x1, int z1, boolean[] asked) {
        int missing = 0, total = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
            if (!asked[0]) level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(cx, cz), true);   // only the ticket: ServerLevel.setChunkForced would call getChunk and block
            total++;
            if (level.getChunkSource().getChunkNow(cx, cz) == null) missing++;
        }
        asked[0] = true;
        long now = System.currentTimeMillis();
        if (missing > 0 && now - lastReport > 15_000) {
            lastReport = now;
            System.out.println("[UC-JOURNEY] warming up: " + (total - missing) + " of " + total + " chunks of this stage are in memory (" + (now - warmStarted) / 1000 + " s so far)");
        }
        return missing == 0;
    }

    /** True when there is nothing to warm up or every stage is done. */
    static boolean warmedUp() { return !GENERATED || (planned && stage >= STAGES.size()); }

    /** Polls the current stage; never blocks. */
    static void warmStep() {
        if (stage >= STAGES.size()) return;
        if (STAGES.get(stage).getAsBoolean()) stage++;
        if (stage >= STAGES.size()) System.out.println("[UC-JOURNEY] fixture sites generated and held before the journey starts: Row Flats at " + flatsCorner.toShortString() + ", wayside shelter at "
            + shelterSite.toShortString() + " (" + (System.currentTimeMillis() - warmStarted) / 1000 + " s)");
    }

    /** The first x, z at least 60 blocks outside every city region, searching along +x from the centre of {@code city}. Pure arithmetic: no chunk is touched. */
    static int[] outsideEveryCityXZ(ServerLevel level, String city) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst().orElseThrow(() -> new IllegalStateException("no city region " + city));
        for (int step = 0; step < 12; step++) {
            int x = (int) region.centerX() + region.radius() + 60 + step * 24, z = (int) region.centerZ();
            if (CityRegions.INSTANCE.cityAt(level, new BlockPos(x, 64, z)).isEmpty() && CityRegions.INSTANCE.nearest(level, new BlockPos(x, 64, z)).orElse("").equals(city)) return new int[]{x, z};
        }
        throw new IllegalStateException("no open country beside " + city + " for the wayside shelter");
    }

    static BlockPos outsideEveryCity(ServerLevel level, String city) {
        int[] xz = outsideEveryCityXZ(level, city);
        return ground(level, xz[0], xz[1]);
    }

    // ------------------------------------------------------------------------------------------------------------ phase 1

    /**
     * Runs the survival half of phase 1. {@code houseTerminal} is a placed terminal inside the Crew's house. Returns the facts the marker and the state file carry.
     * Every step checks its own result strictly and throws {@link AssertionError} on the first thing that is not as it should be.
     */
    static JsonObject prepare(MinecraftServer server, ServerLevel level, ServerPlayer farmer, ServerPlayer smith, BlockPos houseTerminal, CrewLedger ledger) throws Exception {
        JsonObject facts = new JsonObject();
        // 1. the world and the pack
        var generator = level.getChunkSource().getGenerator();
        facts.addProperty("generator", generator.getClass().getName());
        facts.addProperty("flat", level.isFlat());
        facts.addProperty("authenticated", server.usesAuthentication());
        facts.addProperty("difficulty", level.getDifficulty().getKey());
        JsonArray mods = new JsonArray();
        for (String id : List.of("underground_city", "qshop", "lostcities", "tacz", "taczblueprints", "mts", "mtsofficialpack", "oamp", "modernlife", "sophisticatedbackpacks", "sophisticatedcore", "thirstwastaken2", "farmersdelight", "comforts",
            "vinery", "farm_and_charm", "kaleidoscope_tavern")) {
            if (ModList.get().isLoaded(id)) mods.add(id);
        }
        facts.add("mods", mods);
        for (String id : List.of("underground_city", "qshop", "sophisticatedbackpacks", "sophisticatedcore", "thirstwastaken2", "farmersdelight", "comforts")) expect(ModList.get().isLoaded(id), "the combined journey needs " + id);
        if (GENERATED) {
            expect(!level.isFlat(), "the combined journey is meant for a generated world, but the level is flat");
            // Lost Cities does not replace the overworld's noise generator, it adds its terrain feature to it, so the class name says nothing; its own planner API does: it must plan a city at the centre chunk
            var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(CITY)).findFirst().orElseThrow(() -> new IllegalStateException("no city region " + CITY));
            expect(ModList.get().isLoaded("lostcities") && LostCitiesLink.ready(), "Lost Cities' planner API is not ready");
            var centre = LostCitiesLink.chunk(level, region.centerChunkX(), region.centerChunkZ());
            expect(centre.isPresent() && centre.get().city(), "Lost Cities does not plan a city at the centre chunk of " + CITY + ": " + centre);
            facts.addProperty("lostcities_plans_city_at_centre_chunk", true);
            facts.addProperty("lostcities_building_at_centre_chunk", String.valueOf(centre.get().building()));
        }
        expect(!server.usesAuthentication(), "the journey's offline test clients need online-mode=false (see docs/technical/identity.md); the authenticated launch is checked by scripts/run_auth_check.py");

        // 2. the exchange the house terminal belongs to: a real city of the generated world (or, only on a flat world, a fixture region)
        if (!GENERATED) {
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(CITY, level.dimension().location().toString(), houseTerminal.getX() >> 4, houseTerminal.getZ() >> 4, 128)));
        }
        var exchange = Exchanges.at(level, houseTerminal).orElseThrow(() -> new AssertionError("the Crew's house terminal is not inside any exchange"));
        expect(exchange.city().equals(CITY) && !exchange.wayside(), "the house terminal belongs to " + exchange);
        facts.addProperty("exchange_region", GENERATED ? "real city region of the generated world" : "fixture region on a flat world");

        // 3. the Crew goes to fully shared finances, and the member may spend Crew money
        Crew crew = ledger.crewOf(farmer.getUUID());
        ledger.setMode(farmer.getUUID(), CrewJourneyServer.FARMER, Mode.SHARED);
        for (ServerPlayer p : List.of(farmer, smith)) if (!crew.consent.contains(p.getUUID())) ledger.setConsent(p.getUUID(), p.getGameProfile().getName(), true);
        ledger.setPermission(farmer.getUUID(), CrewJourneyServer.FARMER, Role.MEMBER, Perm.SPEND, true);
        expect(crew.effectiveMode() == Mode.SHARED && crew.can(smith.getUUID(), Perm.SPEND), "fully shared finances are in force and the member may spend");

        // 4. a wayside shelter with its provisions desk, outside every city
        BlockPos shelter = shelterSite != null ? shelterSite : outsideEveryCity(level, CITY);
        var district = DistrictCatalog.INSTANCE.find(WaysideShelters.kindOf("journey_road", 0));
        DistrictBuilder.build(level, district, shelter);
        PlacedSites sites = PlacedSites.get(level, PlacedSites.SHELTERS);
        sites.done.add("journey_road#0");
        sites.where.put("journey_road#0", shelter.asLong());
        sites.setDirty();
        BlockPos desk = null;
        for (int dx = 0; dx <= district.max().getX() && desk == null; dx++) for (int dz = 0; dz <= district.max().getZ(); dz++) {
            BlockPos at = shelter.offset(dx, 0, dz);
            if (level.getBlockState(at).is(UndergroundCity.TERMINAL.get())) { desk = at; break; }
        }
        expect(desk != null, "the built shelter has a provisions desk");
        var wayside = Exchanges.at(level, desk).orElseThrow(() -> new AssertionError("the desk is not recognised"));
        expect(wayside.wayside() && wayside.city().equals(CITY), "the desk belongs to the nearest city's wayside outlet: " + wayside);
        facts.addProperty("shelter_kind", district.id());
        facts.addProperty("shelter_x", shelter.getX()); facts.addProperty("shelter_y", shelter.getY()); facts.addProperty("shelter_z", shelter.getZ());
        facts.addProperty("desk_x", desk.getX()); facts.addProperty("desk_y", desk.getY()); facts.addProperty("desk_z", desk.getZ());

        // 5. the second player: a backpack with things in it, a canteen filled at a pond and drunk from, a gun
        ItemStack pack = new ItemStack(item(BACKPACK));
        BackpackSupport.wear(smith, pack);
        expect(BackpackSupport.worn(smith), "the backpack is worn");
        ItemStack worn = smith.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        var storage = BackpackSupport.inventory(worn);
        expect(BackpackSupport.insertAll(storage, new ItemStack(Items.DIAMOND, 7)) && BackpackSupport.insertAll(storage, new ItemStack(Items.BREAD, 12)), "items did not go into the backpack");
        expect(BackpackSupport.insertAll(BackpackSupport.upgrades(worn), BackpackSupport.stack("sophisticatedbackpacks:pickup_upgrade")), "the pickup upgrade did not go into the upgrade slot");
        facts.addProperty("backpack_slots", BackpackSupport.slots(worn));
        facts.addProperty("backpack_contents_uuid", BackpackSupport.contentsUuid(worn).map(java.util.UUID::toString).orElse(""));

        Class<?> skin = cls(THIRST, "com.thirstwastaken2.item.WaterskinItem"), manager = cls(THIRST, "com.thirstwastaken2.data.ThirstManager"), data = cls(THIRST, "com.thirstwastaken2.data.ThirstData"),
            purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity"), api = cls(THIRST, "com.thirstwastaken2.api.ThirstApi");
        BlockPos pond = ground(level, shelter.getX() + 16, shelter.getZ());
        level.setBlockAndUpdate(pond.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pond, Blocks.WATER.defaultBlockState());
        for (BlockPos around : List.of(pond.north(), pond.south(), pond.east(), pond.west())) level.setBlockAndUpdate(around, Blocks.STONE.defaultBlockState());
        ItemStack flask = new ItemStack(item("thirstwastaken2:copper_canteen"));
        smith.getInventory().setItem(1, flask);
        smith.getInventory().selected = 1;
        smith.teleportTo(level, pond.getX() + 0.5, pond.getY() + 1.0, pond.getZ() + 0.5, 0f, 90f);   // standing over the pond, looking straight down
        smith.gameMode.useItem(smith, level, smith.getMainHandItem(), InteractionHand.MAIN_HAND);
        flask = smith.getMainHandItem();
        int filled = (Integer) call(skin, "servings", flask);
        expect(filled > 0, "using the canteen at the pond filled nothing");
        call(manager, "set", smith, callOn(call(data, "full"), "withLevels", 6, 0));
        int thirstyAt = (Integer) call(api, "thirst", smith);
        ItemStack afterDrink = smith.getMainHandItem().finishUsingItem(level, smith);
        smith.getInventory().setItem(1, afterDrink);
        int drunkTo = (Integer) call(api, "thirst", smith);
        expect(drunkTo > thirstyAt && (Integer) call(skin, "servings", afterDrink) == filled - 1, "drinking from the canteen restored thirst " + thirstyAt + " -> " + drunkTo);
        facts.addProperty("thirst_at_save", drunkTo);
        facts.addProperty("flask_servings", (Integer) call(skin, "servings", afterDrink));
        facts.addProperty("flask_quality", String.valueOf(call(purity, "quality", afterDrink)));

        ItemStack gun = new ItemStack(item(GUN));
        CompoundTag tag = new CompoundTag();
        tag.putString("GunId", "tacz:glock_17");
        tag.putString("GunFireMode", "SEMI");
        tag.putInt("GunCurrentAmmoCount", 7);
        tag.putBoolean("HasBulletInBarrel", true);
        gun.setTag(tag);
        smith.getInventory().setItem(2, gun);

        // 6. the first player cooks: three beetroots and a bowl in a cooking pot over a heat source, and keeps the soup
        BlockPos kitchen = ground(level, shelter.getX() + 16, shelter.getZ() + 12);
        level.setBlockAndUpdate(kitchen.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(kitchen, ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("farmersdelight:cooking_pot")).defaultBlockState());
        BlockEntity pot = level.getBlockEntity(kitchen);
        expect(pot != null, "the cooking pot has a block entity");
        ItemStackHandler inventory = (ItemStackHandler) callOn(pot, "getInventory");
        for (int slot = 0; slot < 3; slot++) inventory.setStackInSlot(slot, new ItemStack(Items.BEETROOT));
        Class<?> potType = pot.getClass();
        inventory.setStackInSlot(ModReflect.staticInt(potType, "CONTAINER_SLOT"), new ItemStack(Items.BOWL));
        BlockState potState = level.getBlockState(kitchen);
        Method tick = null;
        for (Method m : potType.getMethods()) if (m.getName().equals("cookingTick")) tick = m;
        for (int i = 0; i < 260; i++) tick.invoke(null, level, kitchen, potState, pot);
        ItemStack soup = inventory.getStackInSlot(ModReflect.staticInt(potType, "OUTPUT_SLOT")).copy();
        expect(soup.is(Items.BEETROOT_SOUP), "the cooking pot made " + soup);
        farmer.getInventory().setItem(3, soup);
        facts.addProperty("soup", soup.getCount());

        // 7. the interrupted purchase: the second player buys two loaves at the wayside desk, the bank pays, and the process dies after the prepared record
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        CitizenState.Citizen citizen = CitizenState.get(server).find(smith.getUUID()).orElseThrow();
        String line = "underground_city:survival/wayside_bread_" + CITY;
        RegionalStock bread = RegionalCatalog.INSTANCE.find(line);
        expect(bread != null && bread.soldAt("wayside") && bread.unitPrice() * CRASH_UNITS == CRASH_COST, "the wayside bread line is as the journey expects");
        int shelfBefore = orders.regionalUnits(bread);
        facts.addProperty("bread_line", line);
        facts.addProperty("bread_units_before", shelfBefore);
        facts.addProperty("bread_clock", orders.clock());
        long bankBefore = crew.bank;
        double smithWallet = QShopEconomy.balance(smith);
        UUID nonce = UUID.randomUUID();
        CrewService.crashAt = "purchase_after_prepare";
        try {
            RegionalMarket.purchase(smith, desk, orders, citizen, line, CRASH_UNITS, nonce);
            throw new AssertionError("the simulated crash did not happen");
        } catch (CrewService.SimulatedCrash expected) { /* the process "died" here: the prepared record and the hold are on disk, the player's inventory is not changed */ }
        finally { CrewService.crashAt = ""; }
        expect(ledger.allHolds().size() == 1 && ledger.allHolds().get(0).amount() == CRASH_COST && crew.bank == bankBefore - CRASH_COST, "the bank's share is held: " + ledger.allHolds() + ", bank " + crew.bank);
        expect(orders.preparedCrewHold(smith.getUUID()) != null && QShopEconomy.balance(smith) == smithWallet && smith.getInventory().countItem(Items.BREAD) == 0,
            "a prepared purchase waits; the wallet and the inventory are untouched");
        facts.addProperty("crash_purchase_nonce", nonce.toString());
        facts.addProperty("crash_purchase_cost", CRASH_COST);
        facts.addProperty("bank_before_purchase", bankBefore);
        return facts;
    }

    // ------------------------------------------------------------------------------------------------------------ phase 2

    /** Everything that must have survived the kill and the restart, read from the real, reloaded world and player files. */
    static JsonObject verifyAfterRestart(MinecraftServer server, ServerLevel level, ServerPlayer farmer, ServerPlayer smith, JsonObject state, CrewLedger ledger) throws Exception {
        JsonObject facts = new JsonObject();
        JsonObject saved = state.getAsJsonObject("survival");
        Crew crew = ledger.crewOf(farmer.getUUID());
        expect(crew.effectiveMode() == Mode.SHARED && crew.can(smith.getUUID(), Perm.SPEND), "fully shared finances and the spend right survived the restart");
        // the purchase that was interrupted after its prepared record: decided at server start, delivered at login, charged once
        expect(ledger.allHolds().isEmpty(), "a purchase hold was left unresolved after the restart: " + ledger.allHolds());
        expect(crew.log.stream().filter(e -> e.kind().equals("PURCHASE") && e.amount() == CRASH_COST).count() == 1, "the interrupted purchase is in the Crew's history exactly once");
        expect(smith.getInventory().countItem(Items.BREAD) == CRASH_UNITS, "the interrupted purchase was delivered at login: " + smith.getInventory().countItem(Items.BREAD) + " loaves");
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        expect(!orders.player(smith.getUUID()).contains("pending") && orders.preparedCrewHold(smith.getUUID()) == null, "the prepared purchase was settled");
        RegionalStock bread = RegionalCatalog.INSTANCE.find(state.getAsJsonObject("survival").get("bread_line").getAsString());
        long intervals = (orders.clock() - saved.get("bread_clock").getAsLong()) / bread.intervalTicks();
        int expectedUnits = (int) Math.min(bread.capacity(), saved.get("bread_units_before").getAsInt() - CRASH_UNITS + intervals * bread.unitsPerInterval());
        expect(orders.regionalUnits(bread) == expectedUnits, "the shared stock decrement survived: " + orders.regionalUnits(bread) + " expected " + expectedUnits);
        // a replayed purchase request is refused
        BlockPos desk = new BlockPos(saved.get("desk_x").getAsInt(), saved.get("desk_y").getAsInt(), saved.get("desk_z").getAsInt());
        var exchange = Exchanges.at(level, desk).orElseThrow(() -> new AssertionError("the wayside desk is no longer recognised after the restart"));
        expect(exchange.wayside() && exchange.city().equals(CITY), "the shelter's desk survived the restart: " + exchange);
        CitizenState.Citizen citizen = CitizenState.get(server).find(smith.getUUID()).orElseThrow();
        UUID nonce = UUID.fromString(saved.get("crash_purchase_nonce").getAsString());
        long bank = crew.bank;
        try {
            RegionalMarket.purchase(smith, desk, orders, citizen, saved.get("bread_line").getAsString(), CRASH_UNITS, nonce);
            throw new AssertionError("a replayed purchase was accepted after the restart");
        } catch (IllegalStateException refused) { /* as it must be */ }
        expect(crew.bank == bank && smith.getInventory().countItem(Items.BREAD) == CRASH_UNITS, "the refused replay moved something");

        // what the players carry came back from their real player files
        expect(BackpackSupport.worn(smith), "the backpack is still worn after the restart");
        ItemStack worn = smith.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        var storage = BackpackSupport.inventory(worn);
        expect(BackpackSupport.count(storage, Items.DIAMOND) == 7 && BackpackSupport.count(storage, Items.BREAD) == 12, "the backpack's contents survived: " + BackpackSupport.count(storage, Items.DIAMOND) + " diamonds, " + BackpackSupport.count(storage, Items.BREAD) + " bread");
        expect(BackpackSupport.count(BackpackSupport.upgrades(worn), item("sophisticatedbackpacks:pickup_upgrade")) == 1, "the pickup upgrade survived the restart");
        expect(BackpackSupport.contentsUuid(worn).map(java.util.UUID::toString).orElse("").equals(saved.get("backpack_contents_uuid").getAsString()), "the backpack kept its contents identity across the restart");
        Class<?> skin = cls(THIRST, "com.thirstwastaken2.item.WaterskinItem"), purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity"), api = cls(THIRST, "com.thirstwastaken2.api.ThirstApi");
        ItemStack flask = smith.getInventory().getItem(1);
        expect(flask.getItem() == item("thirstwastaken2:copper_canteen") && (Integer) call(skin, "servings", flask) == saved.get("flask_servings").getAsInt()
            && String.valueOf(call(purity, "quality", flask)).equals(saved.get("flask_quality").getAsString()), "the canteen kept its water and its quality: " + flask + " " + call(skin, "servings", flask));
        int thirst = (Integer) call(api, "thirst", smith), thirstThen = saved.get("thirst_at_save").getAsInt();
        expect(thirst <= thirstThen && thirst >= thirstThen - 2, "the thirst bar survived: " + thirst + " (was " + thirstThen + ")");
        ItemStack gun = smith.getInventory().getItem(2);
        expect(gun.getItem() == item(GUN) && gun.getTag() != null && "tacz:glock_17".equals(gun.getTag().getString("GunId")) && gun.getTag().getInt("GunCurrentAmmoCount") == 7, "the gun kept its state: " + gun + " " + gun.getTag());
        ItemStack soup = farmer.getInventory().getItem(3);
        expect(soup.is(Items.BEETROOT_SOUP) && soup.getCount() == saved.get("soup").getAsInt(), "the cooked soup survived: " + soup);
        facts.addProperty("thirst_after_restart", thirst);
        facts.addProperty("flask_servings_after_restart", (Integer) call(skin, "servings", flask));
        facts.addProperty("backpack_diamonds", BackpackSupport.count(storage, Items.DIAMOND));
        facts.addProperty("loaves_delivered_at_login", smith.getInventory().countItem(Items.BREAD));
        facts.addProperty("shared_stock_units", orders.regionalUnits(bread));
        facts.addProperty("replayed_purchase", "refused");
        return facts;
    }

    /** After the numbers are final: two purchases on the real exchange (one split between the bank and the wallet) and a refusal. Returns the credits spent. */
    static long purchasesAfterRestart(MinecraftServer server, ServerLevel level, ServerPlayer farmer, ServerPlayer smith, BlockPos houseTerminal, CrewLedger ledger, JsonObject facts) {
        Crew crew = ledger.crewOf(farmer.getUUID());
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        var citizens = CitizenState.get(server);
        // the member (may spend) buys a waterskin for 32: the bank has less, so it pays what it holds and the wallet the rest
        RegionalStock skin = RegionalCatalog.INSTANCE.find("underground_city:survival/waterskin_" + CITY);
        expect(skin != null, "the first flask is on sale in " + CITY);
        long bank = crew.bank;
        double wallet = QShopEconomy.balance(smith);
        long price = skin.unitPrice();
        expect(bank > 0 && bank < price, "the bank holds " + bank + ", less than the flask's " + price + ", so this purchase is split");
        int flasksBefore = smith.getInventory().countItem(skin.registryItem());
        RegionalMarket.purchase(smith, houseTerminal, orders, citizens.find(smith.getUUID()).orElseThrow(), skin.id(), 1, UUID.randomUUID());
        expect(crew.bank == 0 && QShopEconomy.balance(smith) == wallet - (price - bank) && smith.getInventory().countItem(skin.registryItem()) == flasksBefore + 1,
            "split purchase: bank " + crew.bank + ", wallet " + QShopEconomy.balance(smith) + " (was " + wallet + "), goods " + smith.getInventory().countItem(skin.registryItem()));
        facts.addProperty("split_purchase_bank_paid", bank);
        facts.addProperty("split_purchase_wallet_paid", price - bank);
        // the founder buys the same flask with their own money (the bank is empty) ...
        double farmerWallet = QShopEconomy.balance(farmer);
        RegionalMarket.purchase(farmer, houseTerminal, orders, citizens.find(farmer.getUUID()).orElseThrow(), skin.id(), 1, UUID.randomUUID());
        expect(crew.bank == 0 && QShopEconomy.balance(farmer) == farmerWallet - price && farmer.getInventory().countItem(skin.registryItem()) == 1, "the founder paid for a flask from their own wallet");
        // ... and is refused a purchase that neither the bank nor the wallet can pay: nothing moves
        RegionalStock canteen = RegionalCatalog.INSTANCE.find("underground_city:survival/copper_canteen_" + CITY);
        double before = QShopEconomy.balance(farmer);
        expect(before < canteen.unitPrice(), "the founder cannot afford a copper canteen now (" + before + " of " + canteen.unitPrice() + ")");
        int shelf = orders.regionalUnits(canteen);
        try {
            RegionalMarket.purchase(farmer, houseTerminal, orders, citizens.find(farmer.getUUID()).orElseThrow(), canteen.id(), 1, UUID.randomUUID());
            throw new AssertionError("a purchase nobody could pay was accepted");
        } catch (IllegalStateException refused) { facts.addProperty("refused_purchase", refused.getMessage()); }
        expect(crew.bank == 0 && QShopEconomy.balance(farmer) == before && orders.regionalUnits(canteen) == shelf && ledger.allHolds().isEmpty(), "the refused purchase changed something");
        return 2 * price;
    }
}
