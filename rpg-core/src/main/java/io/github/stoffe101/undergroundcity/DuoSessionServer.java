package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two real clients on a dedicated server for the ninth pass (D-187, D-190, D-191; {@code scripts/run_duo_session.py}, a NEW furnished archipelago world): each client registers on the real
 * first-login screen (one for Valewood, one for Blackwater) and the server checks where each was really put; the server's console runs the operator commands (credits through Q Shop, levels,
 * points, general skills, the audit, and the mistakes an operator makes) and checks that a player who is not an operator cannot reach them; then a two-player burglary: only the player who holds a
 * breach permit for a house may break things in it, and nothing drops. Phase 2 starts the same world again: both rejoin as the same citizens where they were, nothing moves them again, what the
 * commands changed is still there, and the house is put back once its (shortened) wait is over.
 *
 * <p>Scripted, each logged {@code SCRIPTED}: the breach permit (as if the first player had picked the house's lock: the lock itself was played through a real client in the wreck scenario), the
 * teleports to the house and back, and the breaks, which are the real {@code destroyBlock} of each player's own game mode on the server. The wait is one minute ({@code -Duc.burglary.restoreMinutes=1}).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class DuoSessionServer {
    static final boolean ENABLED = "duo".equals(System.getProperty("uc.journey.scenario", ""));
    static final int PHASE = Integer.getInteger("uc.journey.phase", 1);
    static final String VALE = "UC_Valewood", BLACK = "UC_Blackwater";
    private static final JsonObject REPORT = new JsonObject();
    private static final JsonArray CHECKS = new JsonArray();
    private static int step, ticks;
    private static boolean finished;
    private static final Map<BlockPos, BlockState> BROKEN = new LinkedHashMap<>();

    private DuoSessionServer() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || finished) return;
        MinecraftServer server = event.getServer();
        ServerPlayer vale = server.getPlayerList().getPlayerByName(VALE), black = server.getPlayerList().getPlayerByName(BLACK);
        ticks++;
        try {
            if (PHASE == 1) phase1(server, vale, black); else phase2(server, vale, black);
        } catch (RuntimeException | IOException error) {
            System.out.println("[UC-DUO] FAILED: " + error);
            error.printStackTrace(System.out);
            REPORT.addProperty("status", "failed");
            REPORT.addProperty("error", String.valueOf(error));
            try { write(server); } catch (IOException ignored) { }
            finished = true;
            server.halt(false);
        }
    }

    private static void check(String what, boolean ok, String detail) {
        JsonObject c = new JsonObject();
        c.addProperty("check", what); c.addProperty("passed", ok); c.addProperty("detail", detail);
        CHECKS.add(c);
        System.out.println("[UC-DUO] " + (ok ? "PASS " : "FAIL ") + what + ": " + detail);
        if (!ok) throw new IllegalStateException(what + ": " + detail);
    }

    // ------------------------------------------------------------------------------------------------ phase 1

    private static void phase1(MinecraftServer server, ServerPlayer vale, ServerPlayer black) throws IOException {
        if (step == 0) {
            if (vale == null || black == null) return;
            REPORT.addProperty("scenario", "duo"); REPORT.addProperty("phase", 1); REPORT.addProperty("connected_clients", 2);
            server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.overworld().setDayTime(6000);
            System.out.println("[UC-DUO] SCRIPTED: noon, the daylight cycle stopped; both clients are in and register on their own first-login screens");
            step = 1; ticks = 0;
            return;
        }
        if (step == 1) {
            // both register through the screen and are moved by the server; four minutes at most
            boolean done = CityArrival.placed(server, vale.getUUID()).isPresent() && CityArrival.placed(server, black.getUUID()).isPresent();
            if (!done) { if (ticks > 20 * 240) throw new IllegalStateException("the clients did not both register and arrive in four minutes"); return; }
            if (ticks < 100) return;   // let both land
            arrivals(server, vale, black);
            admin(server, vale, black);
            step = 2; ticks = 0;
            return;
        }
        if (step == 2) { wreck(server, vale, black); step = 3; ticks = 0; return; }
        if (step == 3 && ticks > 100) {
            // positions to compare with after the restart, then a save and a normal stop
            REPORT.addProperty("vale_pos_before_stop", vale.blockPosition().toShortString());
            REPORT.addProperty("black_pos_before_stop", black.blockPosition().toShortString());
            long saveStart = System.nanoTime();
            server.saveEverything(false, true, true);
            REPORT.addProperty("save_ms", (System.nanoTime() - saveStart) / 1_000_000L);
            REPORT.addProperty("status", "passed");
            write(server);
            finished = true;
            System.out.println("[UC-DUO] phase 1 done; stopping the server normally");
            server.halt(false);
        }
    }

    private static void arrivals(MinecraftServer server, ServerPlayer vale, ServerPlayer black) {
        for (ServerPlayer p : List.of(vale, black)) {
            String chosen = p == vale ? "valewood" : "blackwater";
            var citizen = CitizenState.get(server).find(p.getUUID()).orElseThrow();
            BlockPos at = p.blockPosition();
            String region = CityRegions.INSTANCE.cityAt(p.level(), at).orElse("");
            boolean urban = CityRegions.INSTANCE.region(chosen).map(r -> r.distanceSquared(at) <= (double) r.cityRadius() * r.cityRadius()).orElse(false);
            boolean safe = CityArrival.standable(p.serverLevel(), at) || CityArrival.standable(p.serverLevel(), at.above());
            var placed = CityArrival.placed(server, p.getUUID()).orElseThrow();
            check(p.getGameProfile().getName() + " registered through the screen as " + chosen, citizen.city.equals(chosen), "citizen of " + citizen.city);
            check(p.getGameProfile().getName() + " is really in " + chosen, chosen.equals(region) && urban && safe && !p.isInWall() && !p.isInWater(),
                "at " + at.toShortString() + " (" + placed.how() + "), civic city " + region + ", in the streets " + urban + ", safe ground " + safe);
            check(p.getGameProfile().getName() + "'s own respawn point is the arrival", p.getRespawnPosition() != null && p.getRespawnPosition().distSqr(placed.pos()) <= 9, "respawn " + p.getRespawnPosition());
            REPORT.addProperty(p == vale ? "vale_arrival" : "black_arrival", at.toShortString());
        }
        check("the two players are in different cities", vale.distanceTo(black) > 500, Math.round(vale.distanceTo(black)) + " blocks apart");
        check("the world spawn is untouched", !server.overworld().getSharedSpawnPos().closerThan(vale.blockPosition(), 300), "world spawn " + server.overworld().getSharedSpawnPos().toShortString());
    }

    // ------------------------------------------------------------------------------------------------ the operator's commands, from the console

    private record Answer(int result, List<String> said) {
        boolean ok() { return result > 0; }
        String text() { return String.join(" / ", said); }
    }

    /** Runs a command as the server console (permission 4) and keeps what it said. */
    /** The changes phase 1's console commands make, each one audited: credits add and set, a career level, operator points added and removed, a Thief level and general XP (the
     *  refused ones change nothing and are not). Phase 2 expected one more than phase 1 had made (its first run, October 27). */
    static final int AUDITED = 7;

    private static Answer console(MinecraftServer server, String command) {
        List<String> said = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { said.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = new CommandSourceStack(capture, Vec3.ZERO, Vec2.ZERO, server.overworld(), 4, "Server", Component.literal("Server"), server, null);
        int result = server.getCommands().performPrefixedCommand(source, command);
        System.out.println("[UC-DUO] console> " + command + " -> " + result + ": " + String.join(" / ", said));
        return new Answer(result, said);
    }

    private static void admin(MinecraftServer server, ServerPlayer vale, ServerPlayer black) {
        double v0 = QShopEconomy.balance(vale);
        Answer info = console(server, "uc admin info " + VALE);
        check("info from the console", info.ok() && info.text().contains("Valewood") && info.text().contains("Credits"), info.text());
        Answer add = console(server, "uc admin credits " + VALE + " add 150");
        check("credits add through Q Shop", add.ok() && Math.abs(QShopEconomy.balance(vale) - (v0 + 150)) < 0.01, "balance " + QShopEconomy.balance(vale));
        Answer tooMuch = console(server, "uc admin credits " + VALE + " remove 100000");
        check("removing more than the balance is refused and changes nothing", !tooMuch.ok() && Math.abs(QShopEconomy.balance(vale) - (v0 + 150)) < 0.01 && tooMuch.text().contains("has only"), tooMuch.text());
        Answer set = console(server, "uc admin credits " + VALE + " set 75");
        check("credits set", set.ok() && Math.abs(QShopEconomy.balance(vale) - 75) < 0.01, "balance " + QShopEconomy.balance(vale));
        Answer zero = console(server, "uc admin credits " + VALE + " add 0");
        check("a zero amount is refused", !zero.ok() && Math.abs(QShopEconomy.balance(vale) - 75) < 0.01, zero.text());
        Answer level = console(server, "uc admin career " + BLACK + " level 4");
        var b = CitizenState.get(server).find(black.getUUID()).orElseThrow();
        check("career level set", level.ok() && b.careerLevel() == 4 && SkillService.pointsEarned(b) == 3, "level " + b.careerLevel() + ", points " + SkillService.pointsEarned(b));
        console(server, "uc admin career " + BLACK + " points add 2");
        Answer remove = console(server, "uc admin career " + BLACK + " points remove 1");
        check("operator points kept apart from level points", remove.ok() && b.careerBonus() == 1 && SkillService.pointsEarned(b) == 4 && b.careerLevel() == 4, "bonus " + b.careerBonus() + ", earned " + SkillService.pointsEarned(b));
        Answer thief = console(server, "uc admin thief " + VALE + " level 3");
        var v = CitizenState.get(server).find(vale.getUUID()).orElseThrow();
        check("Thief level set", thief.ok() && v.thiefLevel() == 3, "Thief level " + v.thiefLevel());
        Answer trading = console(server, "uc admin general " + VALE + " trading xp add 500");
        check("general skill XP added", trading.ok() && v.generalXp.getOrDefault("trading", 0) >= 500, "trading XP " + v.generalXp.getOrDefault("trading", 0));
        Answer unknown = console(server, "uc admin general " + VALE + " flying xp add 5");
        check("an unknown skill is refused", !unknown.ok() && unknown.text().contains("Unknown general skill"), unknown.text());
        Answer nobody = console(server, "uc admin info UC_Nobody");
        check("an unknown player is refused", !nobody.ok(), nobody.text());
        Answer audit = console(server, "uc admin audit 20");
        check("the audit lists the changes", audit.ok() && audit.said().size() >= AUDITED, audit.said().size() + " lines");
        REPORT.addProperty("vale_credits_after", QShopEconomy.balance(vale));
        boolean playerCan = server.getCommands().getDispatcher().getRoot().getChild("uc").getChild("admin").canUse(black.createCommandSourceStack());
        check("a player who is not an operator cannot reach /uc admin", !playerCan && !black.hasPermissions(2), "can use " + playerCan);
    }

    // ------------------------------------------------------------------------------------------------ two players in one burglary

    private static void wreck(MinecraftServer server, ServerPlayer vale, ServerPlayer black) {
        ServerLevel level = server.overworld();
        // an empty, locked Valewood house near the first player's arrival, with things in it that may be broken
        BlockPos near = vale.blockPosition();
        CityBuildings.Building house = null;
        List<BlockPos> targets = new ArrayList<>();
        long now = LockService.clock(server);
        for (int r = 1; r <= 6 && house == null; r++) for (int dx = -r; dx <= r && house == null; dx++) for (int dz = -r; dz <= r && house == null; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = (near.getX() >> 4) + dx, cz = (near.getZ() >> 4) + dz;
            if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            var b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
            if (b == null || !b.locked() || CityHomes.anyLet(level.getSeed(), b)) continue;
            BurglaryDamage.grant(vale, b, now);
            List<BlockPos> found = new ArrayList<>();
            for (int y = b.streetY(); y < b.streetY() + 10 && found.size() < 2; y++) for (int x = 0; x < 16 && found.size() < 2; x++) for (int z = 0; z < 16 && found.size() < 2; z++) {
                BlockPos p = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
                if (BurglaryDamage.judge(vale, level, b, p, now) == BurglaryDamage.Verdict.ALLOWED) found.add(p);
            }
            if (found.size() >= 2) { house = b; targets = found; }
        }
        check("a house with things to break near Valewood's welcome", house != null, house == null ? "none" : house.type() + " at chunk " + house.chunkX() + "," + house.chunkZ());
        System.out.println("[UC-DUO] SCRIPTED: the first player holds a breach permit for the house as if they had picked its lock; both players are put inside");
        BlockPos inside = targets.get(0);
        vale.teleportTo(level, inside.getX() + 0.5, inside.getY() + 1, inside.getZ() + 0.5, 0, 0);
        black.teleportTo(level, inside.getX() + 0.5, inside.getY() + 1, inside.getZ() + 0.5, 0, 0);
        level.getEntitiesOfClass(ItemEntity.class, new AABB(inside).inflate(16)).forEach(ItemEntity::discard);
        BlockPos first = targets.get(0), second = targets.get(1);
        BlockState firstState = level.getBlockState(first), secondState = level.getBlockState(second);
        black.gameMode.destroyBlock(first);
        check("the second player, who picked nothing, cannot break anything", level.getBlockState(first).equals(firstState), "still " + level.getBlockState(first));
        vale.gameMode.destroyBlock(first);
        vale.gameMode.destroyBlock(second);
        BROKEN.put(first, firstState); BROKEN.put(second, secondState);
        int drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(inside).inflate(16)).size();
        check("the permit holder breaks two things and nothing drops", level.getBlockState(first).isAir() && level.getBlockState(second).isAir() && drops == 0, "drops " + drops);
        var record = BurglaryDamage.store(server).records.get(house.key());
        check("the damage is recorded to be put back", record != null && record.cells.size() >= 2, record == null ? "none" : record.cells.size() + " cells");
        JsonArray cells = new JsonArray();
        BROKEN.forEach((p, s) -> { JsonObject c = new JsonObject(); c.addProperty("pos", p.asLong()); c.addProperty("block", s.getBlock().getDescriptionId()); cells.add(c); });
        REPORT.add("broken", cells);
        REPORT.addProperty("house", house.key());
        // both leave the house: back to the first player's arrival (still near enough for the house's chunk to stay loaded) and the second player's city
        BlockPos va = CityArrival.placed(server, vale.getUUID()).orElseThrow().pos(), ba = CityArrival.placed(server, black.getUUID()).orElseThrow().pos();
        vale.teleportTo(level, va.getX() + 0.5, va.getY(), va.getZ() + 0.5, 0, 0);
        black.teleportTo(level, ba.getX() + 0.5, ba.getY(), ba.getZ() + 0.5, 0, 0);
        System.out.println("[UC-DUO] SCRIPTED: both players were put back at their arrivals");
    }

    // ------------------------------------------------------------------------------------------------ phase 2

    private static void phase2(MinecraftServer server, ServerPlayer vale, ServerPlayer black) throws IOException {
        if (step == 0) {
            if (vale == null || black == null) return;
            if (ticks < 100) return;
            REPORT.addProperty("scenario", "duo"); REPORT.addProperty("phase", 2); REPORT.addProperty("connected_clients", 2);
            for (ServerPlayer p : List.of(vale, black)) {
                var citizen = CitizenState.get(server).find(p.getUUID());
                var placed = CityArrival.placed(server, p.getUUID());
                check(p.getGameProfile().getName() + " rejoined as the same citizen, not moved again", citizen.isPresent() && placed.isPresent() && !CityArrival.pending(server, p.getUUID()),
                    "citizen " + citizen.map(c -> c.city).orElse("none") + ", placed " + placed.map(a -> a.pos().toShortString()).orElse("none") + ", at " + p.blockPosition().toShortString());
            }
            check("credits set by the console are still there", Math.abs(QShopEconomy.balance(vale) - 75) < 0.01, "balance " + QShopEconomy.balance(vale));
            var b = CitizenState.get(server).find(black.getUUID()).orElseThrow();
            check("level and operator points are still there", b.careerLevel() == 4 && b.careerBonus() == 1, "level " + b.careerLevel() + ", bonus " + b.careerBonus());
            check("the audit survived the restart", AdminCommands.Audit.get(server).last(50).size() >= AUDITED, AdminCommands.Audit.get(server).last(50).size() + " entries");
            check("the house still waits to be put back after the restart", !BurglaryDamage.store(server).records.isEmpty(), BurglaryDamage.status(server));
            step = 1; ticks = 0;
            return;
        }
        if (step == 1) {
            if (!BurglaryDamage.store(server).records.isEmpty()) { if (ticks > 20 * 300) throw new IllegalStateException("the house was not put back in five minutes: " + BurglaryDamage.status(server)); return; }
            // what phase 1 broke, read from its report: each cell must hold its block again
            Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
            JsonObject first = com.google.gson.JsonParser.parseString(Files.readString(folder.resolve("duo-session-phase1.json"))).getAsJsonObject();
            List<String> wrong = new ArrayList<>();
            for (var e : first.getAsJsonArray("broken")) {
                BlockPos p = BlockPos.of(e.getAsJsonObject().get("pos").getAsLong());
                String was = e.getAsJsonObject().get("block").getAsString();
                String now = server.overworld().getBlockState(p).getBlock().getDescriptionId();
                if (!now.equals(was)) wrong.add(p.toShortString() + " is " + now + " (was " + was + ")");
            }
            check("the house was put back after its wait, nobody in it, as it was", wrong.isEmpty(), wrong.isEmpty() ? BurglaryDamage.status(server) : String.join("; ", wrong));
            REPORT.addProperty("status", "passed");
            write(server);
            finished = true;
            System.out.println("[UC-DUO] phase 2 done; stopping the server normally");
            server.halt(false);
        }
    }

    private static void write(MinecraftServer server) throws IOException {
        REPORT.add("checks", CHECKS);
        Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        Files.writeString(folder.resolve("duo-session-phase" + PHASE + ".json"), REPORT + "\n");
    }
}
