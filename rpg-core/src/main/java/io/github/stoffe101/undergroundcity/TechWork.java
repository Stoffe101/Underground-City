package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A Technician at work (D-158): a repair, an install or an upgrade at a building's utility panel, each a puzzle the server holds ({@link TechPuzzles}); the bench's assembly; and the service board
 * the computer shows. Who may do what is the Technician's tree:
 *
 * <ul>
 * <li>any Technician repairs tier I and II buildings; <b>Bench Electronics</b> up to tier III; <b>Municipal Repairs</b> any building, with a third try;</li>
 * <li><b>Installer</b> fits monitored alarms (an install job and a Municipal Security Array); <b>Security Systems</b> makes them last twice as long;</li>
 * <li><b>Systems Integrator</b> takes network upgrades, the larger patch grids.</li>
 * </ul>
 *
 * <p>A job pays once, the first Technician to finish it, through the work-order ledger's single commit (the parts used, the credits and the career XP together), so a crash or a second player
 * changes nothing. A repair of a tier III building or above uses a circuit board.
 */
public final class TechWork {
    private static final Logger LOGGER = LogUtils.getLogger();

    TechWork() {}

    /** One puzzle in progress. */
    static final class Session {
        final UUID player, ref;
        final String kind;
        final long key;
        final TechPuzzles.Trace trace;
        final TechPuzzles.Patch patch;
        final long endsAt;
        int tries, probes;
        final Map<Integer, Boolean> readings = new HashMap<>();
        final int[] scan;

        Session(UUID player, String kind, long key, UUID ref, TechPuzzles.Trace trace, TechPuzzles.Patch patch, long endsAt, int tries, int[] scan) {
            this.player = player; this.kind = kind; this.key = key; this.ref = ref; this.trace = trace; this.patch = patch; this.endsAt = endsAt; this.tries = tries; this.scan = scan;
        }
    }

    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    static long started, finished, failed;

    static Session sessionOf(UUID player) { return ACTIVE.get(player); }

    // ------------------------------------------------------------------------------------------------ at the panel

    static Item item(String id) { return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, id)); }

    /** A right click on a utility panel. */
    static void usePanel(ServerPlayer player, ServerLevel level, BlockPos pos) {
        TechService.Systems s = TechService.systems(level.getServer());
        for (TechService.Panel p : s.panels.values()) if (pos.equals(p.pos())) { use(player, level, p); return; }
        tell(player, "This panel serves no building Core knows.");
    }

    /** A right click on a street door with a Technician's tool, where the door is the building's access point. Returns whether it was taken as work. */
    static boolean useDoor(ServerPlayer player, ServerLevel level, CityBuildings.Building b) {
        ItemStack held = player.getMainHandItem();
        if (!held.is(item("repair_kit")) && !held.is(item("security_array"))) return false;
        TechService.Systems s = TechService.systems(level.getServer());
        TechService.ensurePanel(level, b);
        TechService.Panel p = s.panels.get(b.key());
        if (p == null || p.pos() != null) return false;
        use(player, level, p);
        return true;
    }

    static void use(ServerPlayer player, ServerLevel level, TechService.Panel p) {
        TechService.Systems s = TechService.systems(level.getServer());
        TechService.Fault fault = s.faults.get(p.key());
        TechService.Job job = null, quiet = null;
        for (TechService.Job j : s.jobs.values()) if (j.key() == p.key()) { if (j.kind().equals("quiet")) quiet = j; else job = j; }
        ItemStack held = player.getMainHandItem();
        // back-channel work (D-166): only a Technician with Bypass Tooling knows the job is there, and starts it crouched at the panel with a toolkit in hand
        if (quiet != null && player.isCrouching() && held.is(item("repair_kit")) && SkillService.has(citizen(player), QUIET_SKILL)) {
            String refusal = startQuiet(player, level, p, quiet);
            if (refusal != null) tell(player, refusal);
            return;
        }
        String status = fault != null ? (fault.shop() ? "Fault: the till and card network are down." : "Fault: the cameras are down.")
            : job != null ? (job.kind().equals("install") ? "Install ordered: a monitored alarm." : "Upgrade ordered: the network.") : "All systems normal.";
        if (!held.is(item("repair_kit")) && !held.is(item("security_array"))) { tell(player, Casing.capitalise(p.label()) + ", " + p.address() + ". " + status); return; }
        String refusal;
        if (fault != null && held.is(item("repair_kit"))) refusal = startRepair(player, level, p, fault);
        else if (job != null && job.kind().equals("install")) refusal = startInstall(player, level, p, job);
        else if (job != null && job.kind().equals("upgrade") && held.is(item("repair_kit"))) refusal = startUpgrade(player, level, p, job);
        else refusal = status + " Nothing to do here.";
        if (refusal != null) tell(player, refusal);
    }

    private static CitizenState.Citizen citizen(ServerPlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElse(null); }

    /** Why this player may not work a building's systems at all, or null. */
    static String gate(ServerPlayer player) {
        if (player instanceof FakePlayer && !TESTING) return "Not a player.";
        CitizenState.Citizen c = citizen(player);
        if (c == null) return "Register first.";
        if (!"technician".equals(c.career)) return "Only a Technician works a building's systems.";
        if (ACTIVE.containsKey(player.getUUID())) return "You are already at work on a panel.";
        if (WorkOrderLedger.get(player.server).faulted()) return "Service work is unavailable until the server is restarted.";
        return null;
    }

    /** The highest tier a Technician may repair. */
    static int repairReach(CitizenState.Citizen c) {
        return SkillService.has(c, "technician.municipal_repairs") ? 6 : SkillService.has(c, "technician.fundamentals") ? 3 : 2;
    }

    static String startRepair(ServerPlayer player, ServerLevel level, TechService.Panel p, TechService.Fault fault) {
        String gate = gate(player);
        if (gate != null) return gate;
        CitizenState.Citizen c = citizen(player);
        if (p.tier() > repairReach(c)) return "A tier " + PropertyIntel.roman(p.tier()) + " building is beyond you yet: " + (repairReach(c) < 3 ? "Bench Electronics" : "Municipal Repairs") + " would let you.";
        if (p.tier() >= 3 && player.getInventory().countItem(item("control_circuits")) < 1) return "This building's repair needs a circuit board.";
        boolean scanner = player.getInventory().countItem(item("diagnostic_scanner")) > 0;
        TechPuzzles.Trace trace = TechPuzzles.Trace.generate(fault.id().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits(), p.tier());
        int tries = SkillService.has(c, "technician.municipal_repairs") ? 3 : 2;
        Session session = new Session(player.getUUID(), "repair", p.key(), fault.id(), trace, null, player.server.getTickCount() + TechService_TRACE_SECONDS * 20L, tries, scanner ? trace.scan() : null);
        if (scanner) wear(player, item("diagnostic_scanner"));
        wear(player, item("repair_kit"));
        begin(player, session);
        return null;
    }

    static String startInstall(ServerPlayer player, ServerLevel level, TechService.Panel p, TechService.Job job) {
        String gate = gate(player);
        if (gate != null) return gate;
        CitizenState.Citizen c = citizen(player);
        if (!SkillService.has(c, "technician.automation_install")) return "An install needs the Installer skill.";
        if (player.getInventory().countItem(item("security_array")) < 1) return "An install needs a Municipal Security Array (an Electronics Bench makes one).";
        TechPuzzles.Patch patch = TechPuzzles.Patch.generate(job.id().getLeastSignificantBits() ^ player.getUUID().getMostSignificantBits(), p.tier() >= 4 ? 1 : 0);
        begin(player, new Session(player.getUUID(), "install", p.key(), job.id(), null, patch, player.server.getTickCount() + TechService_PATCH_SECONDS * 20L, 0, null));
        return null;
    }

    static String startUpgrade(ServerPlayer player, ServerLevel level, TechService.Panel p, TechService.Job job) {
        String gate = gate(player);
        if (gate != null) return gate;
        CitizenState.Citizen c = citizen(player);
        if (!SkillService.has(c, "technician.systems_integrator")) return "A network upgrade needs the Systems Integrator skill.";
        TechPuzzles.Patch patch = TechPuzzles.Patch.generate(job.id().getLeastSignificantBits() ^ player.getUUID().getMostSignificantBits(), 2);
        wear(player, item("repair_kit"));
        begin(player, new Session(player.getUUID(), "upgrade", p.key(), job.id(), null, patch, player.server.getTickCount() + TechService_PATCH_SECONDS * 20L, 0, null));
        return null;
    }

    static final int TechService_TRACE_SECONDS = 120, TechService_PATCH_SECONDS = 180, QUIET_SECONDS = 150;
    static final String QUIET_SKILL = "technician.bypass_tooling", QUIET_HIGH = "technician.signal_scrubbing";

    /**
     * Back-channel work (D-166): at a building's panel, crouched, with a toolkit and a Bypass Kit, a Technician with Bypass Tooling works a patch grid (the same abstract puzzle as an install) to
     * loop the building's cameras and quieten its alarm line for a while, for somebody who pays off the books. Anybody in sight of the panel may see it and call the police; an officer who sees
     * it comes at once. Out of time, the panel trips its tamper alarm. A tier V or VI building needs Signal Scrubbing.
     */
    static String startQuiet(ServerPlayer player, ServerLevel level, TechService.Panel p, TechService.Job job) {
        String gate = gate(player);
        if (gate != null) return gate;
        CitizenState.Citizen c = citizen(player);
        if (p.tier() >= 5 && !SkillService.has(c, QUIET_HIGH)) return "A tier " + PropertyIntel.roman(p.tier()) + " building's line is beyond you yet: Signal Scrubbing would let you.";
        if (player.getInventory().countItem(item("bypass_kit")) < 1) return "Quiet work takes a Bypass Kit.";
        Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, p.door().getX() >> 4, p.door().getZ() >> 4);
        if (b.isEmpty()) return "This panel's building is not known here.";
        // the people in sight of the panel: an officer comes at once, anybody else may call it in (the same roll as a break-in's witnesses)
        for (TownspersonEntity person : level.getEntitiesOfClass(TownspersonEntity.class, new net.minecraft.world.phys.AABB(p.access()).inflate(PoliceResponse.witnessRadius(player)),
                x -> x.isAlive() && x.hasLineOfSight(player))) {
            if (person.police()) { PoliceResponse.seenByPatrol(player, level, b.get(), p.door(), person, "at a building's utility panel"); return "An officer saw you at the panel."; }
        }
        var at = new BuildingBreachEvent(player.getUUID(), p.door(), b.get().city(), b.get().security() == null ? "" : b.get().security().id(), BuildingBreachEvent.Outcome.NOISE, LockService.clock(player.server));
        if (PoliceResponse.witnessed(level, player, at)) {
            PoliceResponse.raise(player, level, b.get(), p.door(), false, false, true);
            return "Somebody saw you at the panel and is calling it in.";
        }
        TechPuzzles.Patch patch = TechPuzzles.Patch.generate(job.id().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits(), p.tier() >= 5 ? 2 : 1);
        wear(player, item("repair_kit"));
        begin(player, new Session(player.getUUID(), "quiet", p.key(), job.id(), null, patch, player.server.getTickCount() + QUIET_SECONDS * 20L, 0, null));
        return null;
    }

    private static void begin(ServerPlayer player, Session session) {
        ACTIVE.put(player.getUUID(), session);
        started++;
        TechNetwork.sendSession(player, session);
    }

    private static void wear(ServerPlayer player, Item tool) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(tool) || !stack.isDamageableItem()) continue;
            stack.hurtAndBreak(1, player, p -> {});
            return;
        }
    }

    // ------------------------------------------------------------------------------------------------ moves

    /** A probe of the test point after {@code part}. Returns the reading, or null when it is not this player's repair. */
    static Boolean probe(ServerPlayer player, int part) {
        Session s = ACTIVE.get(player.getUUID());
        if (s == null || s.trace == null || part < 0 || part >= s.trace.size()) return null;
        if (!s.readings.containsKey(part)) { s.readings.put(part, s.trace.live(part)); s.probes++; }
        return s.readings.get(part);
    }

    /** Replaces {@code part}: the repair is done if it was the dead one, else a try is spent (the last ends the repair, the fault still open). */
    static void replace(ServerPlayer player, int part) {
        Session s = ACTIVE.get(player.getUUID());
        if (s == null || s.trace == null || part < 0 || part >= s.trace.size()) return;
        if (part == s.trace.faulty) { succeed(player, s); return; }
        s.tries--;
        if (s.tries <= 0) { end(player, s, false, "That part was fine, and you are out of tries. The fault is still open."); return; }
        TechNetwork.sendSession(player, s);
        TechNetwork.sendNote(player, "That part was fine. " + s.tries + (s.tries == 1 ? " try" : " tries") + " left.");
    }

    /** Turns a tile of the patch grid; the job is done when every device is fed. */
    static void turn(ServerPlayer player, int cell) {
        Session s = ACTIVE.get(player.getUUID());
        if (s == null || s.patch == null) return;
        s.patch.turn(cell);
        if (s.patch.solved()) succeed(player, s);
        else TechNetwork.sendSession(player, s);
    }

    static void abandon(ServerPlayer player) {
        Session s = ACTIVE.get(player.getUUID());
        if (s != null) end(player, s, false, "You closed the panel. Nothing changed.");
    }

    private static void end(ServerPlayer player, Session s, boolean done, String note) {
        ACTIVE.remove(s.player);
        if (done) finished++; else failed++;
        if (player != null) TechNetwork.sendEnd(player, done, note);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        for (Iterator<Session> it = new ArrayList<>(ACTIVE.values()).iterator(); it.hasNext(); ) {
            Session s = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(s.player);
            if (player == null) { ACTIVE.remove(s.player); continue; }
            if (now > s.endsAt) {
                if (s.kind.equals("quiet")) tamper(player, s);
                end(player, s, false, s.kind.equals("quiet") ? "Out of time. The panel's tamper alarm went off." : "Out of time. The panel is as it was.");
            }
        }
    }

    /** A back-channel job run out of time (D-166): the panel's tamper alarm rings as a break-in's would, and the police come to the building. */
    static void tamper(ServerPlayer player, Session s) {
        ServerLevel level = player.serverLevel();
        TechService.Panel p = TechService.systems(player.server).panels.get(s.key);
        if (p == null) return;
        CityBuildings.INSTANCE.ofChunk(level, p.door().getX() >> 4, p.door().getZ() >> 4).ifPresent(b -> PoliceResponse.raise(player, level, b, p.door(), true, false, false));
    }

    // ------------------------------------------------------------------------------------------------ the pay

    /** A finished puzzle: the building put right (or fitted, or upgraded) and the job paid once, if it is still open. */
    static void succeed(ServerPlayer player, Session s) {
        ServerLevel level = player.serverLevel();
        TechService.Systems sys = TechService.systems(player.server);
        TechService.Panel p = sys.panels.get(s.key);
        CitizenState.Citizen c = citizen(player);
        if (p == null || c == null) { end(player, s, false, "The job is gone."); return; }
        long now = LockService.clock(player.server);
        int credits, xp;
        List<Item> uses = new ArrayList<>();
        switch (s.kind) {
            case "repair" -> {
                TechService.Fault f = sys.faults.get(s.key);
                if (f == null || !f.id().equals(s.ref)) { end(player, s, false, "Somebody else put it right first."); return; }
                credits = TechService.repairFee(p.tier());
                xp = TechService.repairXp(p.tier());
                if (p.tier() >= 3) uses.add(item("control_circuits"));
            }
            default -> {
                TechService.Job j = sys.jobs.get(s.ref);
                if (j == null) { end(player, s, false, "Somebody else took that job."); return; }
                credits = j.credits();
                xp = j.xp();
                if (s.kind.equals("install")) uses.add(item("security_array"));
                if (s.kind.equals("quiet")) uses.add(item("bypass_kit"));
            }
        }
        credits = SkillRules.payout(credits, s.kind.equals("quiet"), SkillCatalog.INSTANCE.tree(c.career), c.skills());
        if (!settle(player, s.ref, "tech_" + s.kind, credits, xp, uses)) { end(player, s, false, "The job could not be settled; nothing was taken."); return; }
        switch (s.kind) {
            case "repair" -> { sys.faults.remove(s.key); sys.repairs++; TechService.lamp(level, p, TechBlocks.UtilityPanel.State.OK); }
            case "install" -> {
                sys.jobs.remove(s.ref); sys.installs++;
                sys.monitored.put(s.key, now + TechService.MONITOR_TICKS * (SkillService.has(c, "technician.security_systems") ? 2 : 1));
            }
            case "quiet" -> {
                sys.jobs.remove(s.ref); sys.quiets++;
                sys.quieted.put(s.key, now + TechService.QUIET_TICKS);
                CityBuildings.INSTANCE.ofChunk(level, p.door().getX() >> 4, p.door().getZ() >> 4).ifPresent(b -> SecurityDevices.loop(level, b, TechService.QUIET_LOOP_TICKS));
            }
            default -> { sys.jobs.remove(s.ref); sys.upgrades++; sys.upgraded.put(s.key, now + TechService.UPGRADE_TICKS); }
        }
        sys.setDirty();
        // the call is done: nobody needs to be pointed at it any more (D-161)
        Navigation.finished(player.server, "tech", s.ref.toString(), player.getUUID(), null, "Somebody else put that call right first.");
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "tech_" + s.kind);
        String what = switch (s.kind) { case "repair" -> "Repaired"; case "install" -> "Monitored alarm fitted"; case "quiet" -> "Feeds looped, alarm line quiet"; default -> "Network upgraded"; };
        Notice.send(player, Notice.Kind.DELIVERED, what, Casing.capitalise(p.label()) + ", " + p.address() + ": +" + credits + " credits, +" + xp + " XP.");
        LOGGER.info("[UC-TECH] {} by {} at {} for {} credits", s.kind, player.getGameProfile().getName(), p.address(), credits);
        end(player, s, true, what + ": +" + credits + " credits and " + xp + " XP." + (s.kind.equals("repair") && s.trace != null ? " " + s.probes + (s.probes == 1 ? " probe" : " probes") + (s.probes <= s.trace.optimalProbes() + 1 ? ", neatly done." : ".") : ""));
    }

    /**
     * Pays a job through the work-order ledger's single commit: the parts it used gone from the inventory, the credits and the career XP, together and once per job (a job paid already, or parts
     * missing, pays nothing and takes nothing). Returns whether it was paid.
     */
    static boolean settle(ServerPlayer player, UUID commit, String what, int credits, int xpGain, List<Item> uses) {
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        CitizenState.Citizen c = citizen(player);
        if (c == null || ledger.faulted() || !QShopEconomy.available() || !QShopEconomy.configured(player.server)) return false;
        if (ledger.player(player.getUUID()).contains("pending")) WorkOrderService.recover(player, ledger);
        if (ledger.hasEncounter(player.getUUID(), commit)) return false;
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        for (Item use : uses) {
            int slot = -1;
            for (int i = 0; i < after.getContainerSize() && slot < 0; i++) if (after.getItem(i).is(use)) slot = i;
            if (slot < 0) return false;
            after.getItem(slot).shrink(1);
        }
        int xp = ProgressionRules.totalXp(c.careerXp(), Math.max(0, xpGain));
        ledger.prepareEncounter(player.getUUID(), commit, what, credits, after.save(new ListTag()), QShopEconomy.balance(player) + credits, xp, Map.of());
        WorkOrderService.recover(player, ledger);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ the bench

    /** Makes one design from the parts the player carries. Returns why not, or null when it was made. */
    static String assemble(ServerPlayer player, String designId) {
        TechDesigns.Design d = TechDesigns.INSTANCE.find(designId);
        if (d == null) return "No such design.";
        CitizenState.Citizen c = citizen(player);
        if (c == null) return "Register first.";
        if (!d.skill().isEmpty() && !SkillService.has(c, d.skill())) {
            SkillNode node = SkillCatalog.INSTANCE.find(d.skill());
            return "This design needs " + (node == null ? d.skill() : node.title()) + ".";
        }
        for (var in : d.inputs().entrySet()) {
            Item part = ForgeRegistries.ITEMS.getValue(in.getKey());
            if (part == null || player.getInventory().countItem(part) < in.getValue()) return "Missing parts: " + in.getValue() + " " + (part == null ? in.getKey().toString() : part.getDescription().getString()) + ".";
        }
        for (var in : d.inputs().entrySet()) {
            Item part = ForgeRegistries.ITEMS.getValue(in.getKey());
            int left = in.getValue();
            for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.is(part)) continue;
                int take = Math.min(left, stack.getCount());
                stack.shrink(take);
                left -= take;
            }
        }
        Item out = ForgeRegistries.ITEMS.getValue(d.output());
        ItemStack made = new ItemStack(out, d.count());
        if (!player.getInventory().add(made)) player.drop(made, false);
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "tech_assembled");
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the service board

    /** One line of the service board: a service call or a job, and whether this player may take it (and if not, why). */
    record Call(UUID id, String kind, String city, String address, String label, int tier, int credits, int xp, BlockPos access, long minutesOpen, String blocked) {}

    static List<Call> board(ServerPlayer player) {
        MinecraftServer server = player.server;
        TechService.Systems s = TechService.systems(server);
        CitizenState.Citizen c = citizen(player);
        long now = LockService.clock(server);
        List<Call> out = new ArrayList<>();
        boolean tech = c != null && "technician".equals(c.career);
        for (TechService.Fault f : s.faults.values()) {
            TechService.Panel p = s.panels.get(f.key());
            if (p == null) continue;
            int credits = c == null ? TechService.repairFee(p.tier()) : SkillRules.payout(TechService.repairFee(p.tier()), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            String blocked = !tech ? "Technicians only" : p.tier() > repairReach(c) ? (repairReach(c) < 3 ? "Needs Bench Electronics" : "Needs Municipal Repairs")
                : p.tier() >= 3 && player.getInventory().countItem(item("control_circuits")) < 1 ? "Bring a circuit board" : "";
            out.add(new Call(f.id(), f.shop() ? "repair_shop" : "repair_security", p.city(), p.address(), p.label(), p.tier(), credits, TechService.repairXp(p.tier()), p.access(), (now - f.since()) / 1200, blocked));
        }
        for (TechService.Job j : s.jobs.values()) {
            TechService.Panel p = s.panels.get(j.key());
            if (p == null) continue;
            if (j.kind().equals("quiet")) {
                // back-channel work (D-166) is never on a board for anybody who cannot take it
                if (!tech || !SkillService.has(c, QUIET_SKILL)) continue;
                int pay = SkillRules.payout(j.credits(), true, SkillCatalog.INSTANCE.tree(c.career), c.skills());
                String blocked = p.tier() >= 5 && !SkillService.has(c, QUIET_HIGH) ? "Needs Signal Scrubbing" : player.getInventory().countItem(item("bypass_kit")) < 1 ? "Bring a Bypass Kit" : "";
                out.add(new Call(j.id(), "quiet", p.city(), p.address(), p.label(), p.tier(), pay, j.xp(), p.access(), Math.max(0, (j.until() - now) / 1200), blocked));
                continue;
            }
            int credits = c == null ? j.credits() : SkillRules.payout(j.credits(), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            String blocked = !tech ? "Technicians only" : j.kind().equals("install") ? (!SkillService.has(c, "technician.automation_install") ? "Needs Installer"
                : player.getInventory().countItem(item("security_array")) < 1 ? "Bring a Security Array" : "") : !SkillService.has(c, "technician.systems_integrator") ? "Needs Systems Integrator" : "";
            out.add(new Call(j.id(), j.kind(), p.city(), p.address(), p.label(), p.tier(), credits, j.xp(), p.access(), Math.max(0, (j.until() - now) / 1200), blocked));
        }
        return out;
    }

    private static void tell(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }

    /** Test seam: lets FakePlayers work (GameTests). */
    static volatile boolean TESTING;

    static void resetForTest() { ACTIVE.clear(); started = finished = failed = 0; }

    static String status() { return "work started " + started + ", finished " + finished + ", failed " + failed + ", in progress " + ACTIVE.size(); }

    static Set<UUID> activePlayers() { return new HashSet<>(ACTIVE.keySet()); }
}
