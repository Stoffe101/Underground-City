package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Pending;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import io.github.stoffe101.undergroundcity.ResidenceGuard.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server half of the two-client Crew journey (scenario {@code crew}); a disposable fixture that never runs in normal play.
 *
 * <p>Phase 1: two real clients found a Crew, invite, accept, deposit, lease the Row House from the Crew bank, grant a permission, withdraw and make a
 * container private, all by pressing buttons of the real Crew screen. This class only builds the Row Flats, puts both players in the house, opens their
 * screens and then watches the ledger. When the last step has happened it checks every number, leaves two transfers <em>unfinished on purpose</em> (one that
 * reached the wallet and the player file, one that stopped right after its prepare record), flushes the world and halts the JVM without a stop sequence.
 *
 * <p>Phase 2: the same world on a restarted server. Recovery has run at server start (before anyone can log in); the fixture checks that it decided both
 * transfers the right way, that every fact survived, that replayed transaction ids are refused, and lets the clients continue with the screen.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class CrewJourneyServer {
    static final boolean ENABLED = "crew".equals(System.getProperty("uc.journey.scenario", ""));
    private static final int PHASE = Integer.getInteger("uc.journey.phase", 0);
    static final String CREW = "Row Harvest";
    static final String FARMER = "UC_Farmer", SMITH = "UC_Smith";
    private static final int STALL_TICKS = 20 * 240;
    private static final double START = 200;

    private static boolean initialized, finished, opened;
    private static int stalled, seenTicks, lastStepSeenAt = -1;
    private static String lastProgress = "";
    private static BlockPos origin, chest, table, terminal;
    private static int pendingBeforeStart = -1, pendingAfterStart = -1;
    private static long startedAt;
    private CrewJourneyServer() {}

    /** Generated world, phase 1: the sites are generated step by step before the journey starts, so no tick is long enough to kill the server or disconnect a client (see {@link SurvivalJourney#planWarmUp}). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void warmUpSites(ServerStartedEvent event) {
        if (!ENABLED || PHASE != 1 || !SurvivalJourney.GENERATED) return;
        var flats = DistrictCatalog.INSTANCE.find("row_flats");
        if (flats == null) throw new IllegalStateException("Row Flats are not in the catalogue");
        SurvivalJourney.planWarmUp(event.getServer().overworld(), flats.max().getX() - flats.min().getX() + 1, flats.max().getZ() - flats.min().getZ() + 1);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void startingRecovery(ServerStartedEvent event) {
        if (!ENABLED || PHASE != 2) return;
        CrewLedger ledger = CrewService.ledger(event.getServer());
        pendingBeforeStart = ledger == null ? -1 : ledger.allPending().size();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void startedRecovery(ServerStartedEvent event) {
        if (!ENABLED || PHASE != 2) return;
        CrewLedger ledger = CrewService.ledger(event.getServer());
        pendingAfterStart = ledger == null ? -1 : ledger.allPending().size();
        startedAt = System.currentTimeMillis();
        System.out.println("[UC-JOURNEY] server start: unresolved Crew transfers " + pendingBeforeStart + " before recovery, " + pendingAfterStart + " after");
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || PHASE == 0 || finished || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        if (!folder.getFileName().toString().equals("run-journey-server")) throw new IllegalStateException("Journey fixture requires its dedicated disposable directory");
        if (PHASE == 1 && !SurvivalJourney.warmedUp()) {
            try { SurvivalJourney.warmStep(); } catch (RuntimeException | AssertionError error) {
                finished = true;
                com.mojang.logging.LogUtils.getLogger().error("Crew journey warm-up failed", error);
                try { Files.writeString(folder.resolve("journey-failure.txt"), error.toString() + "\n"); } catch (IOException ignored) { }
                server.halt(false);
            }
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() != 2) return;
        if (players.stream().anyMatch(p -> !List.of(FARMER, SMITH).contains(p.getGameProfile().getName()))) {
            throw new IllegalStateException("Unexpected player in isolated test fixture");
        }
        ServerPlayer farmer = players.stream().filter(p -> p.getGameProfile().getName().equals(FARMER)).findFirst().orElseThrow();
        ServerPlayer smith = players.stream().filter(p -> p.getGameProfile().getName().equals(SMITH)).findFirst().orElseThrow();
        try {
            if (PHASE == 1) phaseOne(server, folder, farmer, smith);
            else if (PHASE == 2) phaseTwo(server, folder, farmer, smith);
        } catch (Exception | AssertionError error) {
            finished = true;
            com.mojang.logging.LogUtils.getLogger().error("Crew journey failed", error);
            try { Files.writeString(folder.resolve("journey-failure.txt"), error.toString() + "\nlast progress: " + lastProgress + "\n"); } catch (IOException ignored) { }
            for (ServerPlayer player : players) player.connection.disconnect(Component.literal("Cloud test failed"));
            server.halt(false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ phase 1

    private static void phaseOne(MinecraftServer server, Path folder, ServerPlayer farmer, ServerPlayer smith) throws Exception {
        ServerLevel level = server.overworld();
        if (!initialized) {
            initialized = true;
            setUp(server, level, farmer, smith);
            return;
        }
        if (!opened) {
            // let the join settle for a second, then open both Crew screens exactly as the K key would
            if (++seenTicks < 40) return;
            opened = true;
            CrewNetwork.sendView(farmer, true);
            CrewNetwork.sendView(smith, true);
            System.out.println("[UC-JOURNEY] both Crew screens opened; waiting for the clients");
            return;
        }
        if (server.getTickCount() % 10 != 0) return;
        CrewLedger ledger = CrewService.need(server);
        String progress = progress(ledger, farmer, smith);
        if (!progress.equals(lastProgress)) {
            lastProgress = progress;
            stalled = 0;
            System.out.println("[UC-JOURNEY] " + progress);
        } else if (++stalled * 10 > STALL_TICKS) {
            throw new IllegalStateException("No progress for " + STALL_TICKS / 20 + " seconds; last state: " + progress);
        }
        Residence house = ledger.residence(houseId());
        boolean lastStepDone = house != null && smith.getUUID().equals(house.claims.get(chest.asLong()));
        if (!lastStepDone) return;

        // The last step has reached the ledger, but the member's screen has not necessarily taken the server's answer yet, and it records what it shows after each step (the runner compares those
        // observations). Give both clients five seconds before anything else happens to their screens or to the process: without it the abrupt halt below could come first and the last
        // observation never be written.
        if (lastStepSeenAt < 0) lastStepSeenAt = server.getTickCount();
        if (server.getTickCount() - lastStepSeenAt < 100) return;

        // The second player's backpack, through the real screens (D-092): he buys it at the house's exchange terminal, wears it from the inventory screen, opens it with the mod's key and
        // moves things into it. The server only hands over the goods and watches the effects; every check of the Crew journey below runs after it, with the wallet back where it began.
        if (SurvivalJourney.ON && !SurvivalJourney.guiDone()) {
            SurvivalJourney.guiBackpack(server, level, smith, terminal);
            return;
        }

        // every step happened: now check all of it, strictly
        Crew crew = ledger.crewOf(farmer.getUUID());
        expect(crew != null && crew.name.equals(CREW) && crew.members.size() == 2 && ledger.crewOf(smith.getUUID()) == crew, "one Crew of two");
        expect(crew.roleOf(farmer.getUUID()) == Role.FOUNDER && crew.roleOf(smith.getUUID()) == Role.MEMBER, "roles");
        expect(crew.perms.get(Role.MEMBER).contains(Perm.WITHDRAW) && !crew.perms.get(Role.MANAGER).isEmpty(), "the founder allowed members to withdraw");
        expect(crew.mode == Mode.BANK && crew.effectiveMode() == Mode.BANK, "default finance mode");
        expect(house.ownerType == OwnerType.CREW && crew.id.equals(house.owner), "the Row House belongs to the Crew, not to a person");
        expect(crew.bank == 30, "bank 30, was " + crew.bank);
        double farmerWallet = QShopEconomy.balance(farmer), smithWallet = QShopEconomy.balance(smith);
        expect(farmerWallet == 80 && smithWallet == 150, "wallets " + farmerWallet + " / " + smithWallet);
        expect(farmerWallet + smithWallet + crew.bank + 140 == 2 * START, "every credit is accounted for (the lease at the starter price, 140, is the one sink)");
        expect(ledger.allPending().isEmpty(), "nothing unresolved before the injected transfers");
        List<String> kinds = crew.log.stream().map(e -> e.kind() + (e.amount() > 0 ? ":" + e.amount() : "")).toList();
        for (String want : List.of("CREATED", "JOINED", "DEPOSIT:120", "DEPOSIT:60", "LEASE:140", "PERM", "WITHDRAW:10")) expect(kinds.contains(want), "history has " + want + ": " + kinds);
        expect(!ResidenceGuard.allowed(ledger, house, farmer.getUUID(), house.dimension, chest, Act.CONTAINER), "the founder opened a container the member made private");
        expect(ResidenceGuard.allowed(ledger, house, smith.getUUID(), house.dimension, chest, Act.CONTAINER), "the member cannot open their own private container");
        expect(ResidenceGuard.allowed(ledger, house, farmer.getUUID(), house.dimension, table, Act.WORKSTATION) && ResidenceGuard.allowed(ledger, house, smith.getUUID(), house.dimension, table, Act.WORKSTATION), "the Crew's smithing table");
        UUID stranger = UUID.nameUUIDFromBytes("uc-journey-stranger".getBytes());
        expect(!ResidenceGuard.allowed(ledger, house, stranger, house.dimension, table, Act.WORKSTATION) && !ResidenceGuard.allowed(ledger, house, stranger, house.dimension, chest, Act.CONTAINER)
            && !ResidenceGuard.allowed(ledger, house, stranger, house.dimension, table, Act.BREAK), "a stranger used the Crew's home");
        var citizens = CitizenState.get(server);
        expect(citizens.find(farmer.getUUID()).orElseThrow().career.equals("farmer") && citizens.find(smith.getUUID()).orElseThrow().career.equals("smith"), "careers stay personal");

        // The combined journey (D-091): the Crew goes to fully shared finances, a wayside shelter is built, the players carry, drink, cook and equip, and a Crew-funded purchase at the
        // shelter's desk is interrupted right after its prepared record. Its hold sits in the ledger beside the two transfers injected below.
        long held = 0;
        JsonObject survival = null;
        if (SurvivalJourney.ON) {
            survival = SurvivalJourney.prepare(server, level, farmer, smith, table, ledger);
            held = SurvivalJourney.CRASH_COST;
            expect(ledger.allHolds().size() == 1 && ledger.totalInFlight() == held && crew.bank == 30 - held, "the interrupted purchase's hold is in the ledger: " + ledger.allHolds() + ", bank " + crew.bank);
        }

        // Two transfers the process never finishes. The deposit reached the wallet and the saved player file; the withdrawal stopped right after its prepare record.
        UUID depositTx = UUID.randomUUID(), withdrawTx = UUID.randomUUID();
        Pending deposit = ledger.beginDeposit(depositTx, farmer.getUUID(), 15, QShopEconomy.balance(farmer));
        CrewService.wallet.set(farmer, deposit.after());
        PlayerStore.saveVerified(farmer);
        Pending withdraw = ledger.beginWithdraw(withdrawTx, smith.getUUID(), 10, QShopEconomy.balance(smith));
        expect(ledger.allPending().size() == 2 && ledger.totalInFlight() == 10 + held && crew.bank == 20 - held, "the withdrawal's hold is taken from the bank at once");

        JsonObject state = new JsonObject();
        state.addProperty("origin_x", origin.getX());
        state.addProperty("origin_y", origin.getY());
        state.addProperty("origin_z", origin.getZ());
        state.addProperty("deposit_tx", depositTx.toString());
        state.addProperty("withdraw_tx", withdrawTx.toString());
        if (survival != null) state.add("survival", survival);
        Files.writeString(folder.resolve("crew-journey-state.json"), state + "\n");
        server.saveEverything(false, true, true); // the world, the Row Flats and both player files are on disk; the ledger is already
        JsonObject marker = new JsonObject();
        marker.addProperty("scenario", "crew");
        marker.addProperty("phase", 1);
        marker.addProperty("connected_clients", 2);
        marker.addProperty("crew", crew.name);
        marker.addProperty("members", crew.members.size());
        marker.addProperty("bank_before_injection", 30);
        marker.addProperty("injected_deposit_reached_wallet_and_player_file", 15);
        marker.addProperty("injected_withdrawal_stopped_after_prepare_record", 10);
        marker.addProperty("farmer_wallet_at_halt", QShopEconomy.balance(farmer));
        marker.addProperty("smith_wallet_at_halt", QShopEconomy.balance(smith));
        marker.addProperty("history_lines", crew.log.size());
        marker.addProperty("bank_at_halt", crew.bank);
        marker.addProperty("purchase_hold_at_halt", held);
        if (survival != null) marker.add("survival", survival);
        marker.addProperty("process_end", "Runtime.halt: no stop sequence, no shutdown hooks, no world save after this point");
        marker.addProperty("status", "passed");
        Files.writeString(folder.resolve("journey-crew-phase1.json"), marker + "\n");
        System.out.println("[UC-JOURNEY] CREW PHASE 1 PASSED: halting the server process abruptly with two transfers unresolved (" + deposit.tx() + ", " + withdraw.tx() + ")");
        System.out.flush();
        finished = true;
        Runtime.getRuntime().halt(137);
    }

    private static void setUp(MinecraftServer server, ServerLevel level, ServerPlayer farmer, ServerPlayer smith) {
        var flats = DistrictCatalog.INSTANCE.find("row_flats");
        if (flats == null) throw new IllegalStateException("Row Flats are not in the catalogue");
        // The journey world is flat and thin (four layers above the world's floor): a district's own foundation would leave the build height, and the development launch's site finder
        // reads ground below sea level as water. So the Row Flats stand on a small stone mesa on the real surface beside the spawn.
        int width = flats.max().getX() - flats.min().getX() + 1, depth = flats.max().getZ() - flats.min().getZ() + 1, lift = 6;
        int x0, z0;
        if (SurvivalJourney.GENERATED) {
            // on a generated Lost Cities world the Row Flats stand inside the real exchange circle of the journey's city (Ironhaven, centred on the spawn), on open ground found by probing, so that the house's terminal belongs to a real city
            BlockPos corner = SurvivalJourney.flatsCorner != null ? SurvivalJourney.flatsCorner : SurvivalJourney.openGroundInCity(level, SurvivalJourney.CITY, width, depth);
            x0 = corner.getX(); z0 = corner.getZ();
        } else {
            BlockPos spawn = level.getSharedSpawnPos();
            x0 = spawn.getX() + 24; z0 = spawn.getZ() + 24;
        }
        for (int cx = (x0 - 4) >> 4; cx <= (x0 + width + 4) >> 4; cx++) for (int cz = (z0 - 4) >> 4; cz <= (z0 + depth + 4) >> 4; cz++) level.getChunk(cx, cz);
        BlockPos ground = DevCommands.surface(level, x0, z0);
        origin = ground.above(lift);
        for (int x = x0 - 3; x <= x0 + width + 3; x++) for (int z = z0 - 3; z <= z0 + depth + 3; z++) {
            for (int y = ground.getY(); y < origin.getY(); y++) level.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
        }
        var built = DistrictBuilder.build(level, flats, origin);
        expect(built.homes() == 3, "the Row Flats register three homes: " + built);
        locate(level);
        for (ServerPlayer player : List.of(farmer, smith)) {
            boolean isFarmer = player == farmer;
            if (!CitizenState.get(server).register(player.getUUID(), isFarmer ? "valewood" : "ironhaven", isFarmer ? "farmer" : "smith")) throw new IllegalStateException("Phase 1 requires fresh identities");
            CrewService.wallet.set(player, START);
            player.setInvulnerable(true);
            player.getInventory().clearContent();
        }
        stand(level, farmer, smith);
        System.out.println("[UC-JOURNEY] Row Flats built at " + origin.toShortString() + "; both citizens registered with " + (int) START + " credits and standing in the Row House");
    }

    /** Puts both players inside the Row House; the second faces the chest, so that "make container private" has a real target. */
    private static void stand(ServerLevel level, ServerPlayer farmer, ServerPlayer smith) {
        Residence house = CrewService.need(level.getServer()).residence(houseId());
        BlockPos farmerSpot = null, smithSpot = null;
        float yaw = 0, pitch = 0;
        for (double margin : new double[]{0.2, 0.1, 0.0}) { // how far to each side of the chest's centre the look ray must still meet the chest first; relaxed only if nothing in the room allows it
            for (int x = house.min.getX(); x <= house.max.getX() && smithSpot == null; x++) {
                for (int z = house.min.getZ(); z <= house.max.getZ(); z++) {
                    BlockPos feet = new BlockPos(x, house.min.getY(), z);
                    if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir() || level.getBlockState(feet.below()).isAir()) continue;
                    if (farmerSpot == null) { farmerSpot = feet; continue; }
                    if (feet.equals(farmerSpot)) continue;
                    Vec3 eye = new Vec3(x + 0.5, feet.getY() + 1.62, z + 0.5), target = Vec3.atCenterOf(chest);
                    double distance = eye.distanceTo(target);
                    if (distance < 1.8 || distance > 4.5) continue;
                    // the chest must be the first thing the look ray meets, at its centre and a little to every side of it: a ray that grazes the edge it shares with the
                    // block beside it hits one or the other depending on rounding (an early run of this fixture hit the crafting table next to the first chest)
                    boolean clear = true;
                    for (double[] nudge : new double[][]{{0, 0, 0}, {margin, 0, 0}, {-margin, 0, 0}, {0, margin, 0}, {0, -margin, 0}, {0, 0, margin}, {0, 0, -margin}}) {
                        Vec3 aim = target.add(nudge[0], nudge[1], nudge[2]);
                        BlockHitResult probe = level.clip(new ClipContext(eye, aim.add(aim.subtract(eye).normalize().scale(1.5)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, smith));
                        if (probe.getType() != HitResult.Type.BLOCK || !probe.getBlockPos().equals(chest)) { clear = false; break; }
                    }
                    if (!clear) continue;
                    double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    smithSpot = feet;
                    break;
                }
            }
            if (smithSpot != null) break;
        }
        if (farmerSpot == null || smithSpot == null) throw new IllegalStateException("no free, clear place in the Row House to stand and face the chest");
        farmer.teleportTo(level, farmerSpot.getX() + 0.5, farmerSpot.getY(), farmerSpot.getZ() + 0.5, 0, 0);
        smith.teleportTo(level, smithSpot.getX() + 0.5, smithSpot.getY(), smithSpot.getZ() + 0.5, yaw, pitch);
        // the same question the server will ask when the player presses "Make container private": what does the look ray from this body position and turn meet first?
        Vec3 eyeNow = smith.getEyePosition(), lookNow = Vec3.directionFromRotation(smith.getXRot(), smith.getYRot());
        BlockHitResult looked = level.clip(new ClipContext(eyeNow, eyeNow.add(lookNow.scale(6.0)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, smith));
        if (looked.getType() != HitResult.Type.BLOCK || !looked.getBlockPos().equals(chest)) {
            throw new IllegalStateException("the second player does not face the chest after the teleport: looking at " + looked.getType() + " " + looked.getBlockPos().toShortString() + " " + level.getBlockState(looked.getBlockPos())
                + ", chest " + chest.toShortString() + ", standing " + smith.position() + " eye " + eyeNow + " yaw " + smith.getYRot() + " pitch " + smith.getXRot() + ", wanted yaw " + yaw + " pitch " + pitch);
        }
        System.out.println("[UC-JOURNEY] the second player stands at " + smithSpot.toShortString() + " facing the chest at " + chest.toShortString() + " (yaw " + yaw + ", pitch " + pitch + ")");
    }

    private static void locate(ServerLevel level) {
        // the Row House has two chests side by side against the north wall; the one at (20, 0, 2) is open to the west and the south, the other is walled in by the crafting table and its twin
        chest = origin.offset(20, 0, 2);
        table = origin.offset(17, 0, 1);
        terminal = origin.offset(15, 0, 1);   // the house's own Operations Terminal, beside the smithing table
        expect(level.getBlockState(chest).is(Blocks.CHEST) && level.getBlockState(table).is(Blocks.SMITHING_TABLE) && level.getBlockState(terminal).is(UndergroundCity.TERMINAL.get()),
            "the Row House's chest, smithing table and terminal are where the data says");
    }

    private static String houseId() {
        return DistrictBuilder.homeId(DistrictCatalog.INSTANCE.find("row_flats"), DistrictCatalog.INSTANCE.find("row_flats").homeOps().get(2).home());
    }

    private static String progress(CrewLedger ledger, ServerPlayer farmer, ServerPlayer smith) {
        Crew crew = ledger.crewOf(farmer.getUUID());
        Residence house = ledger.residence(houseId());
        String crewText = crew == null ? "no Crew" : crew.name + " members=" + crew.members.size() + " bank=" + crew.bank + " memberPerms=" + crew.perms.get(Role.MEMBER).size();
        String houseText = house == null ? "no house" : house.ownerType + (house.claims.isEmpty() ? "" : " claims=" + house.claims.size());
        return crewText + " | house " + houseText + " | wallets " + (long) QShopEconomy.balance(farmer) + "/" + (long) QShopEconomy.balance(smith)
            + " | invites " + ledger.invitesFor(smith.getUUID()).size() + " | pending " + ledger.allPending().size() + (SurvivalJourney.ON ? " | " + SurvivalJourney.guiProgress() : "");
    }

    // ------------------------------------------------------------------------------------------------------------ phase 2

    private static boolean verified;
    private static boolean replaysChecked;
    private static int settled;
    private static JsonObject state;
    private static JsonObject survivalFacts;
    /** What the interrupted Crew-funded purchase took out of the bank for good (the combined journey only). */
    private static final long SPENT = SurvivalJourney.ON ? SurvivalJourney.CRASH_COST : 0;

    private static void phaseTwo(MinecraftServer server, Path folder, ServerPlayer farmer, ServerPlayer smith) throws Exception {
        ServerLevel level = server.overworld();
        CrewLedger ledger = CrewService.need(server);
        if (SurvivalJourney.ON) SurvivalJourney.packLookedAt(smith);   // every tick: the client's look at its pack may be shorter than the ten ticks between the checks below
        if (!verified) {
            verified = true;
            state = JsonParser.parseString(Files.readString(folder.resolve("crew-journey-state.json"))).getAsJsonObject();
            origin = new BlockPos(state.get("origin_x").getAsInt(), state.get("origin_y").getAsInt(), state.get("origin_z").getAsInt());
            locate(level);
            Crew crew = ledger.crewOf(farmer.getUUID());
            expect(crew != null && crew.name.equals(CREW) && crew.members.size() == 2 && ledger.crewOf(smith.getUUID()) == crew, "the Crew survived the restart");
            expect(crew.roleOf(farmer.getUUID()) == Role.FOUNDER && crew.roleOf(smith.getUUID()) == Role.MEMBER, "roles survived");
            expect(crew.perms.get(Role.MEMBER).contains(Perm.WITHDRAW), "the permission survived");
            expect(ledger.allPending().isEmpty(), "recovery left something unresolved: " + ledger.allPending());
            expect(pendingBeforeStart == 2, "the restarted server found the two unresolved transfers in the file: " + pendingBeforeStart);
            Residence house = ledger.residence(houseId());
            expect(house != null && house.ownerType == OwnerType.CREW && crew.id.equals(house.owner), "the Row House is still the Crew's");
            expect(smith.getUUID().equals(house.claims.get(chest.asLong())), "the private container survived");
            // the deposit had reached the wallet and the player file: recovery settled it. The withdrawal had not touched the wallet: recovery cancelled it.
            expect(crew.bank == 45 - SPENT, "bank " + (45 - SPENT) + " (30 + the settled deposit of 15; the cancelled withdrawal's hold returned" + (SPENT > 0 ? "; less the " + SPENT + " the interrupted purchase spent" : "") + "), was " + crew.bank);
            double farmerWallet = QShopEconomy.balance(farmer), smithWallet = QShopEconomy.balance(smith);
            expect(farmerWallet == 65 && smithWallet == 150, "wallets after recovery " + farmerWallet + " / " + smithWallet);
            expect(farmerWallet + smithWallet + crew.bank + 140 + SPENT == 400, "conservation after recovery");
            expect(crew.log.stream().anyMatch(e -> e.kind().equals("DEPOSIT") && e.amount() == 15), "the settled deposit is in the history");
            expect(crew.log.stream().filter(e -> e.kind().equals("WITHDRAW")).count() == 1, "the cancelled withdrawal must not appear as a second withdrawal in the history");
            var citizens = CitizenState.get(server);
            expect(citizens.find(farmer.getUUID()).orElseThrow().career.equals("farmer") && citizens.find(smith.getUUID()).orElseThrow().city.equals("ironhaven"), "personal careers survived");
            UUID stranger = UUID.nameUUIDFromBytes("uc-journey-stranger".getBytes());
            expect(ResidenceGuard.allowed(ledger, house, smith.getUUID(), house.dimension, chest, Act.CONTAINER) && !ResidenceGuard.allowed(ledger, house, farmer.getUUID(), house.dimension, chest, Act.CONTAINER)
                && !ResidenceGuard.allowed(ledger, house, stranger, house.dimension, table, Act.WORKSTATION), "the guard's decisions after the restart");
            if (SurvivalJourney.ON) {
                survivalFacts = SurvivalJourney.verifyAfterRestart(server, level, farmer, smith, state, ledger);
                System.out.println("[UC-JOURNEY] survival state verified after the restart: " + survivalFacts);
            }
            System.out.println("[UC-JOURNEY] Crew state verified after the restart: bank " + (45 - SPENT) + ", wallets 65/150, nothing unresolved");
            CrewNetwork.sendView(farmer, true);
            CrewNetwork.sendView(smith, true);
            return;
        }
        if (!replaysChecked) {
            // the transaction ids of the two interrupted transfers must be remembered across the restart: replaying them moves nothing
            replaysChecked = true;
            UUID depositTx = UUID.fromString(state.get("deposit_tx").getAsString()), withdrawTx = UUID.fromString(state.get("withdraw_tx").getAsString());
            Crew crew = ledger.crewOf(farmer.getUUID());
            long bank = crew.bank;
            double farmerWallet = QShopEconomy.balance(farmer), smithWallet = QShopEconomy.balance(smith);
            refuses(() -> CrewService.deposit(farmer, depositTx, 15), "replaying the settled deposit after a restart");
            refuses(() -> CrewService.withdraw(smith, withdrawTx, 10), "replaying the cancelled withdrawal after a restart");
            expect(crew.bank == bank && QShopEconomy.balance(farmer) == farmerWallet && QShopEconomy.balance(smith) == smithWallet && ledger.allPending().isEmpty(), "a refused replay moved a credit");
            System.out.println("[UC-JOURNEY] replayed transaction ids are refused after the restart");
            lastProgress = "";
            return;
        }
        if (server.getTickCount() % 10 != 0) return;
        Crew crew = ledger.crewOf(farmer.getUUID());
        String text = "bank=" + crew.bank + " wallets " + (long) QShopEconomy.balance(farmer) + "/" + (long) QShopEconomy.balance(smith) + " pending " + ledger.allPending().size();
        if (!text.equals(lastProgress)) { lastProgress = text; stalled = 0; System.out.println("[UC-JOURNEY] " + text); }
        else if (++stalled * 10 > STALL_TICKS) throw new IllegalStateException("No progress for " + STALL_TICKS / 20 + " seconds; last state: " + text);
        // the clients now continue with the screen: the founder deposits 5, the member withdraws 20
        if (crew.bank != 30 - SPENT || QShopEconomy.balance(farmer) != 60 || QShopEconomy.balance(smith) != 170 || !ledger.allPending().isEmpty()) return;
        // the numbers are final; give the clients a few seconds to take the server's answer into their screens and write down what they show before the server ends the run
        if (++settled * 10 < 20 * 5) return;
        // the second client opens the backpack the restart gave back, with the mod's key, looks at it (screenshots) and closes it; the pack's own container must be seen open and then closed
        if (SurvivalJourney.ON && !SurvivalJourney.packLookedAt(smith)) { lastProgress = text + " | waiting for the client to open and close the backpack"; return; }
        expect(QShopEconomy.balance(farmer) + QShopEconomy.balance(smith) + crew.bank + 140 + SPENT == 400, "conservation at the end");
        expect(crew.log.stream().filter(e -> e.kind().equals("DEPOSIT") && e.amount() == 5).count() == 1 && crew.log.stream().filter(e -> e.kind().equals("WITHDRAW") && e.amount() == 20).count() == 1,
            "the two transfers made after the restart are in the history once each");
        long bankAtClients = crew.bank;
        double farmerAtClients = QShopEconomy.balance(farmer), smithAtClients = QShopEconomy.balance(smith);
        long exchangeSpent = 0;
        if (SurvivalJourney.ON) {
            // the clients have written down their screens; now the same exchange code the terminal packet calls sells to both players: one purchase paid half by the bank and half by the
            // wallet, one paid by a wallet alone, and one nobody can pay (refused, nothing moves)
            exchangeSpent = SurvivalJourney.purchasesAfterRestart(server, level, farmer, smith, table, ledger, survivalFacts);
            expect(crew.bank == 0 && ledger.allHolds().isEmpty() && ledger.allPending().isEmpty(), "the Crew bank and holds after the exchange purchases: bank " + crew.bank + ", holds " + ledger.allHolds());
            expect(QShopEconomy.balance(farmer) + QShopEconomy.balance(smith) + crew.bank + 140 + SPENT + exchangeSpent == 400,
                "conservation after the exchange purchases: " + QShopEconomy.balance(farmer) + " + " + QShopEconomy.balance(smith) + " + " + crew.bank + " + 140 + " + SPENT + " + " + exchangeSpent);
            System.out.println("[UC-JOURNEY] exchange purchases after the restart passed: " + survivalFacts);
        }
        server.getPlayerList().saveAll();
        JsonObject marker = new JsonObject();
        marker.addProperty("scenario", "crew");
        marker.addProperty("phase", 2);
        marker.addProperty("connected_clients", 2);
        marker.addProperty("unresolved_transfers_found_in_file_at_start", pendingBeforeStart);
        marker.addProperty("unresolved_after_recovery_at_server_start", pendingAfterStart);
        marker.addProperty("bank_after_recovery", 45 - SPENT);
        marker.addProperty("bank_when_the_clients_looked", bankAtClients);
        marker.addProperty("bank_final", crew.bank);
        marker.addProperty("farmer_wallet_when_the_clients_looked", farmerAtClients);
        marker.addProperty("smith_wallet_when_the_clients_looked", smithAtClients);
        marker.addProperty("farmer_wallet_final", QShopEconomy.balance(farmer));
        marker.addProperty("smith_wallet_final", QShopEconomy.balance(smith));
        if (survivalFacts != null) marker.add("survival", survivalFacts);
        if (SurvivalJourney.ON) marker.addProperty("client_opened_and_closed_the_pack_after_restart", true);
        marker.addProperty("replays_refused_after_restart", 2);
        JsonArray history = new JsonArray();
        crew.log.forEach(e -> history.add(e.kind() + (e.amount() > 0 ? " " + e.amount() : "")));
        marker.add("history_newest_first", history);
        marker.addProperty("status", "passed");
        Files.writeString(folder.resolve("journey-crew-phase2.json"), marker + "\n");
        System.out.println("[UC-JOURNEY] CREW PHASE 2 PASSED with two real connected clients after an abrupt server death");
        finished = true;
        server.halt(false);
    }

    private static void refuses(Runnable action, String what) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Expected a refusal: " + what);
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("Crew journey check failed: " + what);
    }
}
