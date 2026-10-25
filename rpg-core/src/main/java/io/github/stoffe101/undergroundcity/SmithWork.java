package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A Smith at work with their hands (eighth pass; design in docs/design/smith-engineer.md): a precision part turned to a drawing at the Machinist's Bench ({@link SmithPuzzles.Turning}), and a
 * machine's drive stripped, mended, geared and run at a service call ({@link SmithPuzzles.Drive}). The server holds both; the client is sent what a person at the bench or the machine sees and sends
 * moves. Who may do what is the Smith's tree: any Smith turns parts and works tier I and II machinery, <b>Millwright</b> to tier IV with a fourth test run, <b>Master Engineer</b> any machinery and a
 * roughing step deeper.
 *
 * <p>A turned part takes its materials (and a nugget for each sleeve) when it is finished, never before, and closing the drawing changes nothing. A repair pays once, the first Smith to run the
 * machine right, through the work-order ledger's single commit (the parts it used, the credits and the career XP together), as the Technician's work does.
 */
public final class SmithWork {
    private static final Logger LOGGER = LogUtils.getLogger();

    SmithWork() {}

    /** A part being turned at a bench. */
    static final class Turn {
        final UUID player;
        final BlockPos bench;
        final String recipe;
        final SmithPuzzles.Turning t;
        String note = "";

        Turn(UUID player, BlockPos bench, String recipe, SmithPuzzles.Turning t) { this.player = player; this.bench = bench; this.recipe = recipe; this.t = t; }
    }

    /** A machine's drive open at a service call. */
    static final class Repair {
        final UUID player, breakdown;
        final long key;
        final SmithPuzzles.Drive d;
        final BlockPos at;
        String note = "";

        Repair(UUID player, UUID breakdown, long key, SmithPuzzles.Drive d, BlockPos at) { this.player = player; this.breakdown = breakdown; this.key = key; this.d = d; this.at = at; }
    }

    private static final Map<UUID, Turn> TURNING = new HashMap<>();
    private static final Map<UUID, Repair> REPAIRING = new HashMap<>();
    static long turned, repaired, abandoned;

    static Turn turnOf(UUID player) { return TURNING.get(player); }
    static Repair repairOf(UUID player) { return REPAIRING.get(player); }

    static Item item(String id) { return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, id)); }

    private static CitizenState.Citizen citizen(ServerPlayer player) { return CitizenState.get(player.server).find(player.getUUID()).orElse(null); }

    /** How many steps one roughing pass takes on a bench of this tier: two at a Vice and Files, three on a Drill Press, four on a Lathe; a Master Engineer one more. */
    static int roughDepth(int tier, CitizenState.Citizen c) { return 1 + Math.max(1, Math.min(3, tier)) + (c != null && SkillService.has(c, "smith.master_engineer") ? 1 : 0); }

    /** The highest machinery tier a Smith may work: II for anybody, IV with Millwright, any with Master Engineer. */
    static int reach(CitizenState.Citizen c) {
        return SkillService.has(c, "smith.master_engineer") ? 6 : SkillService.has(c, "smith.millwright") ? 4 : 2;
    }

    // ------------------------------------------------------------------------------------------------ turning at the bench

    /** Opens the drawing of a turned recipe at a bench. Returns why not, or null when the drawing is open. */
    static String startTurning(ServerPlayer player, ServerLevel level, WorkshopBlock.Entity e, String recipeId) {
        if (player instanceof FakePlayer && !TESTING) return "Not a player.";
        Workshops.Station st = e.station();
        Workshops.Recipe r = st == null ? null : st.recipe(recipeId);
        if (r == null || !r.turned()) return "That part is not turned by hand.";
        CitizenState.Citizen c = citizen(player);
        if (c == null) return "Register first.";
        if (!st.career().equals(c.career)) return "Only a " + Casing.capitalise(st.career()) + " turns parts by hand at a " + st.title() + ".";
        if (TURNING.containsKey(player.getUUID()) || REPAIRING.containsKey(player.getUUID())) return "You are already at work on something.";
        for (Turn other : TURNING.values()) if (other.bench.equals(e.getBlockPos())) return "Somebody is already turning a part at this bench.";
        if (e.busy()) return "The " + st.title() + " is at work on a batch; turn a part by hand when it is done.";
        String why = WorkshopBlock.refusal(player, e, st, r, 1);
        if (why != null) return why;
        long seed = player.getUUID().getLeastSignificantBits() ^ (long) r.id().hashCode() << 20 ^ level.getGameTime();
        SmithPuzzles.Turning t = SmithPuzzles.Turning.generate(seed, r.handsOn(), roughDepth(e.tier, c));
        Turn turn = new Turn(player.getUUID(), e.getBlockPos().immutable(), r.id(), t);
        turn.note = "Rough down to one step above each line, then finish the last step. " + (e.jigs.containsKey(r.id()) ? "The bench has a jig at " + Workshops.GRADES[e.jigs.get(r.id())] + "." : "");
        TURNING.put(player.getUUID(), turn);
        SmithNetwork.sendTurn(player, turn, r.title());
        return null;
    }

    /** A pass of the tool. */
    static void cut(ServerPlayer player, int from, int to, int line, boolean finish) {
        Turn turn = TURNING.get(player.getUUID());
        if (turn == null) return;
        String why = turn.t.cut(from, to, line, finish);
        turn.note = why != null ? why : finish ? "Finished: a clean cut." : "Roughed: tool marks to finish off.";
        if (why == null && turn.t.done()) { finishTurning(player, turn); return; }
        SmithNetwork.sendTurn(player, turn, title(player, turn));
    }

    /** A sleeve on a spoiled section (one iron nugget, taken when the part is finished). */
    static void sleeve(ServerPlayer player, int section) {
        Turn turn = TURNING.get(player.getUUID());
        if (turn == null) return;
        Workshops.Recipe r = recipeOf(player, turn);
        int nuggetsNeeded = turn.t.sleeves + 1 + (r == null ? 0 : r.inputs().getOrDefault(ResourceLocation.parse("minecraft:iron_nugget"), 0));
        if (player.getInventory().countItem(Items.IRON_NUGGET) < nuggetsNeeded) { turn.note = "A sleeve takes an iron nugget, and you have none to spare."; SmithNetwork.sendTurn(player, turn, title(player, turn)); return; }
        String why = turn.t.sleeve(section);
        turn.note = why != null ? why : "Sleeved: the section is built back up a step. The part can be no better than Fine now.";
        SmithNetwork.sendTurn(player, turn, title(player, turn));
    }

    private static WorkshopBlock.Entity benchOf(ServerPlayer player, Turn turn) {
        return player.serverLevel().getBlockEntity(turn.bench) instanceof WorkshopBlock.Entity e ? e : null;
    }

    private static Workshops.Recipe recipeOf(ServerPlayer player, Turn turn) {
        WorkshopBlock.Entity e = benchOf(player, turn);
        Workshops.Station st = e == null ? null : e.station();
        return st == null ? null : st.recipe(turn.recipe);
    }

    private static String title(ServerPlayer player, Turn turn) {
        Workshops.Recipe r = recipeOf(player, turn);
        return r == null ? "Part" : r.title();
    }

    /**
     * A finished drawing: the materials and a nugget for each sleeve taken, the part handed over at its grade, the career XP (half again for Reserve), and the bench's jig for this recipe kept at
     * the best grade turned. If the materials are gone (dropped, sold) nothing is taken and nothing is made.
     */
    static void finishTurning(ServerPlayer player, Turn turn) {
        TURNING.remove(player.getUUID());
        WorkshopBlock.Entity e = benchOf(player, turn);
        Workshops.Recipe r = recipeOf(player, turn);
        if (e == null || r == null) { SmithNetwork.sendEnd(player, false, "The bench is gone; nothing was made."); return; }
        Map<ResourceLocation, Integer> take = new HashMap<>(r.inputs());
        if (turn.t.sleeves > 0) take.merge(ResourceLocation.parse("minecraft:iron_nugget"), turn.t.sleeves, Integer::sum);
        for (var in : take.entrySet()) if (WorkshopBlock.carried(player, in.getKey()) < in.getValue()) {
            SmithNetwork.sendEnd(player, false, "You no longer carry " + in.getValue() + " " + WorkshopBlock.name(in.getKey()) + "; nothing was taken and nothing made.");
            return;
        }
        for (var in : take.entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(in.getKey());
            int need = in.getValue();
            for (ItemStack s : player.getInventory().items) { if (need <= 0) break; if (s.is(item)) { int n = Math.min(need, s.getCount()); s.shrink(n); need -= n; } }
        }
        int grade = Math.max(0, turn.t.grade());
        Item out = ForgeRegistries.ITEMS.getValue(r.output());
        ItemStack made = Workshops.graded(new ItemStack(out, r.count()), grade);
        if (!player.getInventory().add(made)) player.drop(made, false);
        player.getInventory().setChanged();
        int xp = r.xp() + (grade == 2 ? r.xp() / 2 : 0);
        CitizenState.get(player.server).addCareerXp(player.getUUID(), xp);
        Integer had = e.jigs.get(r.id());
        boolean better = had == null || grade > had;
        if (better) { e.jigs.put(r.id(), grade); e.setChanged(); }
        turned++;
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "smith_turned");
        player.serverLevel().playSound(null, turn.bench, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.4f, 1.4f);
        String jig = e.tier < 2 ? "" : better ? " The bench's jig is set at " + Workshops.GRADES[grade] + "." : " The bench's jig stays at " + Workshops.GRADES[had] + ".";
        String text = "Turned " + r.count() + " " + out.getDescription().getString() + " at " + Workshops.GRADES[grade] + ": " + turn.t.passes + (turn.t.passes == 1 ? " pass" : " passes")
            + " against a standard of " + turn.t.standard + (turn.t.sleeves > 0 ? ", " + turn.t.sleeves + (turn.t.sleeves == 1 ? " sleeve" : " sleeves") : "") + ". +" + xp + " XP." + jig;
        Notice.send(player, Notice.Kind.DELIVERED, "Turned by hand", text);
        LOGGER.info("[UC-SMITH] {} turned {} at {} ({} passes, standard {}, {} sleeves)", player.getGameProfile().getName(), r.id(), Workshops.GRADES[grade], turn.t.passes, turn.t.standard, turn.t.sleeves);
        SmithNetwork.sendEnd(player, true, text);
    }

    // ------------------------------------------------------------------------------------------------ at the machine

    /** A right click on a drive housing. */
    static void useMachine(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MachineService.Works w = MachineService.works(level.getServer());
        for (MachineService.Machine m : w.machines.values()) if (pos.equals(m.pos())) { use(player, level, m); return; }
        tell(player, "This drive serves no building Core knows.");
    }

    /** A right click on a street door with the Engineer's Toolkit, where the door stands in for the building's drive. Returns whether it was taken as work. */
    static boolean useDoor(ServerPlayer player, ServerLevel level, CityBuildings.Building b) {
        if (!player.getMainHandItem().is(item("engineer_toolkit"))) return false;
        MachineService.ensureMachine(level, b);
        MachineService.Machine m = MachineService.works(level.getServer()).machines.get(b.key());
        if (m == null || m.pos() != null) return false;
        use(player, level, m);
        return true;
    }

    static void use(ServerPlayer player, ServerLevel level, MachineService.Machine m) {
        MachineService.Breakdown b = MachineService.works(level.getServer()).breakdowns.get(m.key());
        String state = b == null ? "running" + (MachineService.runsWell(level.getServer(), m.key()) ? " well" : "") : "stopped (a service call is open)";
        if (!player.getMainHandItem().is(item("engineer_toolkit"))) { tell(player, Casing.capitalise(m.name()) + " of " + m.label() + ", " + m.address() + ": " + state + "."); return; }
        if (b == null) { tell(player, Casing.capitalise(m.name()) + ": " + state + ". Nothing to do here."); return; }
        String refusal = startRepair(player, level, m, b);
        if (refusal != null) tell(player, refusal);
    }

    /** Why this player may not work a machine at all, or null. */
    static String gate(ServerPlayer player) {
        if (player instanceof FakePlayer && !TESTING) return "Not a player.";
        CitizenState.Citizen c = citizen(player);
        if (c == null) return "Register first.";
        if (!"smith".equals(c.career)) return "Only a Smith works the cities' machinery.";
        if (TURNING.containsKey(player.getUUID()) || REPAIRING.containsKey(player.getUUID())) return "You are already at work on something.";
        if (WorkOrderLedger.get(player.server).faulted()) return "Service work is unavailable until the server is restarted.";
        return null;
    }

    /** The drive of a breakdown: the same for every Smith who opens it, so the board can say what it needs. */
    static SmithPuzzles.Drive driveOf(MachineService.Breakdown b, MachineService.Machine m, int tests) {
        return SmithPuzzles.Drive.generate(b.id().getMostSignificantBits() ^ b.id().getLeastSignificantBits(), m.tier(), tests);
    }

    static String startRepair(ServerPlayer player, ServerLevel level, MachineService.Machine m, MachineService.Breakdown b) {
        String gate = gate(player);
        if (gate != null) return gate;
        CitizenState.Citizen c = citizen(player);
        if (m.tier() > reach(c)) return "A tier " + PropertyIntel.roman(m.tier()) + " machine is beyond you yet: " + (reach(c) < 4 ? "Millwright" : "Master Engineer") + " would let you.";
        for (Repair other : REPAIRING.values()) if (other.key == m.key()) return "Another Smith has this drive open.";
        SmithPuzzles.Drive d = driveOf(b, m, SkillService.has(c, "smith.millwright") ? 4 : 3);
        Repair r = new Repair(player.getUUID(), b.id(), m.key(), d, m.access());
        r.note = "Replace the worn parts, gear the open stages to the plate, then Run.";
        wear(player, item("engineer_toolkit"));
        REPAIRING.put(player.getUUID(), r);
        SmithNetwork.sendDrive(player, r, MachineService.works(level.getServer()).machines.get(m.key()));
        return null;
    }

    private static void wear(ServerPlayer player, Item tool) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(tool) || !stack.isDamageableItem()) continue;
            stack.hurtAndBreak(1, player, p -> {});
            return;
        }
    }

    /** How many of the replacement for {@code kind} the repair needs so far: the parts replaced of that kind (and the open stages' gear sets for a gear stage). */
    static int needed(SmithPuzzles.Drive d, SmithPuzzles.Kind kind, boolean counting) {
        int n = 0;
        for (int i = 0; i < d.parts.length; i++) if (d.worn[i] && d.replaced[i] && d.parts[i] == kind) n++;
        if (kind == SmithPuzzles.Kind.STAGE) for (int s = 0; s < d.stages; s++) if (d.open[s] && !d.worn[d.partOfStage(s)]) n++;
        return n + (counting ? 1 : 0);
    }

    static void replace(ServerPlayer player, int part) {
        Repair r = REPAIRING.get(player.getUUID());
        if (r == null) return;
        if (part >= 0 && part < r.d.parts.length && r.d.worn[part] && !r.d.replaced[part]) {
            SmithPuzzles.Kind kind = r.d.parts[part];
            Item spare = item(kind.replacement);
            if (player.getInventory().countItem(spare) < needed(r.d, kind, true)) {
                r.note = "You carry no spare " + spare.getDescription().getString().toLowerCase(java.util.Locale.ROOT) + " for the " + kind.title.toLowerCase(java.util.Locale.ROOT) + ".";
                SmithNetwork.sendDrive(player, r, null);
                return;
            }
        }
        String why = r.d.replace(part);
        r.note = why != null ? why : "Fitted a new " + r.d.parts[part].title.toLowerCase(java.util.Locale.ROOT) + ".";
        SmithNetwork.sendDrive(player, r, null);
    }

    static void gear(ServerPlayer player, int stage, boolean driving, int step) {
        Repair r = REPAIRING.get(player.getUUID());
        if (r == null) return;
        String why = r.d.gear(stage, driving, step);
        r.note = why != null ? why : "";
        SmithNetwork.sendDrive(player, r, null);
    }

    static void idler(ServerPlayer player, int stage) {
        Repair r = REPAIRING.get(player.getUUID());
        if (r == null) return;
        String why = r.d.idler(stage);
        r.note = why != null ? why : r.d.idler[stage] ? "An idler gear fitted on stage " + (stage + 1) + ": its direction is kept." : "The idler on stage " + (stage + 1) + " taken out.";
        SmithNetwork.sendDrive(player, r, null);
    }

    /** A test run: the machine paid for and running if all is right, else what is wrong; out of tests, the drive needs a second look (it can be opened again). */
    static void run(ServerPlayer player) {
        Repair r = REPAIRING.get(player.getUUID());
        if (r == null) return;
        String wrong = r.d.run();
        if (!wrong.isEmpty()) {
            if (r.d.tests <= 0) { REPAIRING.remove(player.getUUID()); abandoned++; SmithNetwork.sendEnd(player, false, wrong + " Out of test runs: the drive needs a second look. Open it again to start over; nothing was used."); return; }
            r.note = wrong + " " + r.d.tests + (r.d.tests == 1 ? " test run left." : " test runs left.");
            SmithNetwork.sendDrive(player, r, null);
            return;
        }
        succeed(player, r);
    }

    /** A drive that runs to its plate: paid once through the ledger (the parts used, the credits and the career XP together), the machine running again and the building's catch-up. */
    static void succeed(ServerPlayer player, Repair r) {
        REPAIRING.remove(player.getUUID());
        ServerLevel level = player.serverLevel();
        MachineService.Works w = MachineService.works(player.server);
        MachineService.Machine m = w.machines.get(r.key);
        MachineService.Breakdown b = w.breakdowns.get(r.key);
        CitizenState.Citizen c = citizen(player);
        if (m == null || c == null) { SmithNetwork.sendEnd(player, false, "The job is gone."); return; }
        if (b == null || !b.id().equals(r.breakdown)) { SmithNetwork.sendEnd(player, false, "Somebody else put it right first."); return; }
        List<Item> uses = new ArrayList<>();
        for (String id : r.d.uses()) uses.add(item(id));
        int credits = SkillRules.payout(MachineService.fee(m.tier()), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
        int xp = MachineService.xp(m.tier());
        if (!TechWork.settle(player, r.breakdown, "smith_repair", credits, xp, uses)) { SmithNetwork.sendEnd(player, false, "The repair could not be settled: check you still carry the parts. Nothing was taken."); return; }
        int shift = MachineService.repaired(player.server, level, m);
        repaired++;
        level.playSound(null, m.access(), SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.8f, 0.7f);
        Navigation.finished(player.server, "machine", r.breakdown.toString(), player.getUUID(), null, "Somebody else put that machine right first.");
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "smith_repair");
        String effect = m.industrial() ? (shift > 0 ? " The works runs a catch-up shift: " + shift + " units on " + Casing.capitalise(m.city()) + "'s exchange." : " The works is back at it.") : " The shop is supplied again.";
        String text = Casing.capitalise(m.name()) + " running: +" + credits + " credits, +" + xp + " XP." + effect;
        Notice.send(player, Notice.Kind.DELIVERED, "Machine repaired", Casing.capitalise(m.label()) + ", " + m.address() + ": " + text);
        LOGGER.info("[UC-SMITH] repair by {} of the {} at {} for {} credits (catch-up {})", player.getGameProfile().getName(), m.name(), m.address(), credits, shift);
        SmithNetwork.sendEnd(player, true, text);
    }

    static void close(ServerPlayer player) {
        if (TURNING.remove(player.getUUID()) != null || REPAIRING.remove(player.getUUID()) != null) abandoned++;
    }

    // ------------------------------------------------------------------------------------------------ the toolkit: service calls

    /** One machinery service call as the toolkit lists it, and whether this player may take it (and if not, why). */
    record Call(UUID id, String machine, String city, String address, String label, int tier, int credits, int xp, BlockPos access, long minutesOpen, String needs, String blocked) {}

    /** The open breakdowns, the player's own city first; what each needs is the same drive any Smith would open. */
    static List<Call> calls(ServerPlayer player) {
        MinecraftServer server = player.server;
        MachineService.Works w = MachineService.works(server);
        CitizenState.Citizen c = citizen(player);
        long now = LockService.clock(server);
        boolean smith = c != null && "smith".equals(c.career);
        String here = CityRegions.INSTANCE.cityAt(player.serverLevel(), player.blockPosition()).orElse("");
        List<Call> out = new ArrayList<>();
        for (MachineService.Breakdown b : w.breakdowns.values()) {
            MachineService.Machine m = w.machines.get(b.key());
            if (m == null) continue;
            SmithPuzzles.Drive d = driveOf(b, m, 3);
            Map<String, Integer> parts = new java.util.LinkedHashMap<>();
            for (String id : d.uses()) parts.merge(id, 1, Integer::sum);
            StringBuilder needs = new StringBuilder();
            for (var p : parts.entrySet()) {
                if (needs.length() > 0) needs.append(", ");
                needs.append(p.getValue()).append(' ').append(item(p.getKey()).getDescription().getString().toLowerCase(java.util.Locale.ROOT));
            }
            int credits = c == null ? MachineService.fee(m.tier()) : SkillRules.payout(MachineService.fee(m.tier()), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            String blocked = !smith ? "Smiths only" : m.tier() > reach(c) ? (reach(c) < 4 ? "Needs Millwright" : "Needs Master Engineer") : "";
            out.add(new Call(b.id(), m.name(), m.city(), m.address(), m.label(), m.tier(), credits, MachineService.xp(m.tier()), m.access(), (now - b.since()) / 1200, needs.toString(), blocked));
        }
        out.sort(java.util.Comparator.comparing((Call call) -> call.city().equals(here) ? 0 : 1).thenComparing(Call::minutesOpen, java.util.Comparator.reverseOrder()));
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    /** The toolkit used in the air: the service calls. */
    @SubscribeEvent
    public void useToolkit(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItemStack().is(item("engineer_toolkit"))) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        SmithNetwork.sendCalls(player, calls(player));
    }

    // ------------------------------------------------------------------------------------------------ the clock

    /** How far a Smith may step from the bench or the machine before the work is put down. */
    static final double REACH_SQUARED = 8 * 8;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 7 || TURNING.isEmpty() && REPAIRING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (Turn t : new ArrayList<>(TURNING.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(t.player);
            if (player == null) { TURNING.remove(t.player); continue; }
            if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(t.bench)) > REACH_SQUARED) { TURNING.remove(t.player); abandoned++; SmithNetwork.sendEnd(player, false, "You walked away from the bench. Nothing was taken."); }
        }
        for (Repair r : new ArrayList<>(REPAIRING.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(r.player);
            if (player == null) { REPAIRING.remove(r.player); continue; }
            if (!MachineService.works(server).breakdowns.containsKey(r.key)) { REPAIRING.remove(r.player); SmithNetwork.sendEnd(player, false, "Somebody else put it right first."); continue; }
            if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(r.at)) > REACH_SQUARED) { REPAIRING.remove(r.player); abandoned++; SmithNetwork.sendEnd(player, false, "You walked away from the machine. Nothing was used."); }
        }
    }

    private static void tell(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }

    /** Test seam: lets FakePlayers work (GameTests). */
    static volatile boolean TESTING;

    static void resetForTest() { TURNING.clear(); REPAIRING.clear(); turned = repaired = abandoned = 0; }

    static String status() { return "turned " + turned + ", repaired " + repaired + ", put down " + abandoned + ", at work " + (TURNING.size() + REPAIRING.size()); }

    static Optional<Turn> turningAt(BlockPos bench) { return TURNING.values().stream().filter(t -> t.bench.equals(bench)).findFirst(); }
}
