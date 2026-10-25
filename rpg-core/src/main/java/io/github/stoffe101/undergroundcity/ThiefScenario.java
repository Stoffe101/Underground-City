package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import static io.github.stoffe101.undergroundcity.JourneyScenario.aimedAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.click;
import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.closeScreen;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.speakTo;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.JourneyScenario.use;

/**
 * Scenario A of the Thief brief, "a new burglar", through a real client ({@code -PucPlaytest=thief}, a new furnished archipelago world): a brand-new citizen with no Thief skill cases a house in
 * Valewood by crouching at its door with an empty hand (D-144), picks its deadbolt on the real lock screen, searches its furniture with the real right click and watches the bar, carries what they find
 * (marked stolen with their name) to the Blackwater fence at night, comes in by the door the fence keeps open at their hours (D-147), sells through the real fence screen, learns their first Thief skill
 * at a terminal on the Thief page, and cases the same house again to read what the skill shows.
 *
 * <p>What is scripted rather than played, each logged as SCRIPTED: the travel between the landing, Valewood, Blackwater and Founders Row (teleports: the roads were walked in the journey runs), the time
 * of day (13:00 for the burglary, 22:00 for the fence, with the daylight cycle stopped so the clock does not run on while the script waits), the choice of house (one with nobody in now, its alarm not
 * armed and at least three pieces of searchable furniture, from the same plan the game uses), five lockpicks put in the last hotbar slot, and any walk through a room the walker could not finish. Every
 * click, key and screen is the real one. It is evidence of one scripted burglar, not of how burglary feels to a person.
 */
final class ThiefScenario {
    private ThiefScenario() {}

    private static final class Facts {
        volatile DoorWalk.Spot door;
        volatile CityBuildings.Building house;
        volatile boolean looked;
        final List<BlockPos> spots = new ArrayList<>();
        volatile int xpStart, xpEntered, xpSearched, xpSold, finds, marked, lastSearched, searchedPieces;
        volatile double walletBefore;
        volatile DoorWalk.Spot clubDoor;
        volatile CityBuildings.Building club;
        volatile int fenceId = -1;
        volatile BlockPos terminal;
        final List<String> passed = new ArrayList<>(), scripted = new ArrayList<>();
    }

    private static final Facts F = new Facts();

    /** The street door of the house {@link #chooseHouse} chose (for the patrol scenario, which starts as this one does). */
    static DoorWalk.Spot door() { return F.door; }

    /** Points the shared steps (prepare at the door, case, pick, search) at another building: the raid's bonded store ({@link RaidScenario}). */
    static void useBuilding(DoorWalk.Spot door, CityBuildings.Building building, List<BlockPos> furniture) {
        F.door = door;
        F.house = building;
        F.spots.clear();
        F.spots.addAll(furniture);
    }
    /** The Thief skill learned first: the one that reads a place (D-143). */
    private static final String FIRST_SKILL = "thief.casing";

    /** The scenario the log lines and screenshots belong to: this one's, or the advanced burglary's ({@link HeistScenario}), which shares these helpers (one scenario runs per client). */
    static String tag = "THIEF";

    static void pass(String step) { F.passed.add(step); Playtest.log(tag + " PASS " + step); }
    static void scripted(String what) { F.scripted.add(what); Playtest.log(tag + " SCRIPTED " + what); }

    static void shot(String name) {
        Playtest.queue("screenshot " + name, () -> Playtest.screenshotNextFrame("uc-" + tag.toLowerCase(Locale.ROOT) + "-" + name));
        Playtest.wait("frame", 8);
    }

    static CitizenState.Citizen citizen() { return server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).orElse(null)); }
    static int thiefXp() { CitizenState.Citizen c = citizen(); return c == null ? 0 : c.thiefXp(); }

    /** Stolen units the player carries, and how many of them carry the player's own name as the thief. */
    static int[] stolen() {
        return server(() -> {
            int all = 0, mine = 0;
            for (ItemStack stack : me().getInventory().items) {
                if (stack.isEmpty() || !StolenGoods.is(stack)) continue;
                all += stack.getCount();
                if (me().getUUID().equals(StolenGoods.thief(stack))) mine += stack.getCount();
            }
            return new int[]{all, mine};
        });
    }

    static void build() {
        Playtest.keepScreens = true;
        register();
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped (the script waits; a clock that runs on would bring the residents home)");
        });
        Playtest.wait("settle", 10);
        travel("to the middle of Valewood", () -> centre("valewood"), 4);
        chooseHouse();
        stopIfFailed("finding a house");
        prepareAtDoor();
        caseIt("1-casing-new", false);
        waitForClearStreet("valewood");
        pickTheDoor();
        stopIfFailed("the door");
        search();
        fence();
        learn();
        caseAgain();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ registration

    private static void register() { register(0, "the Farmer card", "1 a new citizen (a Farmer) starts at Thief level 1 with no Thief skill"); }

    /** The first-login registration screen with real clicks: the first city, the career card {@code career}; a new citizen must start with an empty Thief track. */
    static void register(int career, String careerLabel, String passText) {
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 2400);
        until("the first-login registration screen opens by itself", () -> mc().screen instanceof RegistrationScreen, 1200);
        Playtest.wait("let the screen render", 20);
        clickAt("the first city card", () -> { Playtest.focusWindow(); return ((RegistrationScreen) mc().screen).cardCentre(0); });
        until("the profession step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 1, 100);
        Playtest.wait("settle", 6);
        // not the Technician: the Thief track is every career's (D-143)
        clickAt(careerLabel, () -> ((RegistrationScreen) mc().screen).cardCentre(career));
        until("the confirm step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 2, 100);
        clickAt("CONFIRM", () -> ((RegistrationScreen) mc().screen).confirmCentre());
        until("the screen closes", () -> !(mc().screen instanceof RegistrationScreen), 300);
        Playtest.wait("let the server answer", 20);
        Playtest.queue("survival, and the citizen", () -> {
            Playtest.command("gamemode survival");
            CitizenState.Citizen c = citizen();
            if (c == null) { Playtest.fail("no citizen after the click path"); return; }
            F.xpStart = c.thiefXp();
            Playtest.log(tag + " citizen " + c.city + "/" + c.career + ", Thief XP " + c.thiefXp() + ", level " + c.thiefLevel() + ", Thief points " + server(() -> SkillService.thiefPointsAvailable(c)));
            if (c.thiefXp() != 0 || !c.skills().stream().noneMatch(s -> s.startsWith("thief."))) Playtest.fail("a new citizen does not start with an empty Thief track");
            else pass(passText);
        });
        Playtest.wait("settle", 10);
    }

    // ------------------------------------------------------------------------------------------------ getting about

    static Vec3 centre(String city) {
        return server(() -> CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst()
            .map(r -> new Vec3(r.centerChunkX() * 16 + 8, 150, r.centerChunkZ() * 16 + 8)).orElse(null));
    }

    /** A teleport in spectator mode to {@code where}, logged as scripted, then a wait until the chunks around it are loaded (the client asks for them; nothing here generates terrain). */
    static void travel(String label, Supplier<Vec3> where, int chunkRadius) {
        final Vec3[] at = new Vec3[1];
        Playtest.queue(label, () -> {
            at[0] = where.get();
            if (at[0] == null) { Playtest.fail(label + ": nowhere to go"); return; }
            scripted(String.format(Locale.ROOT, "travel %s: a teleport from %.0f, %.0f to %.0f, %.0f (the roads were walked in the journey runs)", label, mc().player.getX(), mc().player.getZ(), at[0].x, at[0].z));
            Playtest.command("gamemode spectator");
            Playtest.command(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f", at[0].x, at[0].y, at[0].z));
        });
        until(label + ": the chunks around have loaded", () -> {
            if (at[0] == null) return true;
            if (Math.abs(mc().player.getX() - at[0].x) > 2 || Math.abs(mc().player.getZ() - at[0].z) > 2) return false;
            int cx = (int) Math.floor(at[0].x) >> 4, cz = (int) Math.floor(at[0].z) >> 4;
            return server(() -> {
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) for (int dz = -chunkRadius; dz <= chunkRadius; dz++) if (!level().hasChunk(cx + dx, cz + dz)) return false;
                return true;
            });
        }, 6000);
        Playtest.wait("settle", 40);
    }

    /** Puts the player on the street {@code spot} in survival, facing the door (a scripted step, logged by the caller). */
    static void standAt(DoorWalk.Spot spot) {
        var server = mc().getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = me();
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.teleportTo(player.serverLevel(), spot.stand().x, spot.stand().y, spot.stand().z, spot.yaw(), 0f);
            player.setDeltaMovement(Vec3.ZERO);
        });
    }

    /** Walks to {@code target} with the path walker; when it gets stuck the rest is a teleport, logged as scripted. */
    static void walkOrTeleport(String label, Supplier<Vec3> target, double within, int budget) {
        final PathWalker[] walker = new PathWalker[1];
        final int[] ticks = {0};
        Playtest.queue(label, () -> { ticks[0] = 0; walker[0] = new PathWalker(label, target, within); }, () -> {
            walker[0].tick();
            if (++ticks[0] > budget && !walker[0].done()) walker[0].stop();
            return walker[0].done() || ticks[0] > budget;
        }, budget + 20);
        Playtest.queue(label + ": walked, or the rest scripted", () -> {
            if (walker[0] != null) walker[0].stop();
            Vec3 goal = target.get();
            if (goal == null) { Playtest.fail(label + ": the target is gone"); return; }
            // the walker's arrival is by the ground plan: a goal a floor up (the club's safe is upstairs, and the walker does not climb stairs) counts only when the height is right too
            double rise = goal.y - mc().player.getY();
            if (walker[0].done() && !walker[0].failed() && Math.abs(rise) < 1.0) { Playtest.log(tag + " walked: " + label); return; }
            double left = mc().player.position().distanceTo(goal);
            scripted(String.format(Locale.ROOT, "%s: the walker stopped %.1f blocks short%s (%s); the rest was a teleport", label, left, Math.abs(rise) >= 1.0 ? String.format(Locale.ROOT, ", %.0f blocks below or above", Math.abs(rise)) : "", walker[0].summary()));
            var server = mc().getSingleplayerServer();
            server.execute(() -> { me().teleportTo(goal.x, goal.y, goal.z); me().setDeltaMovement(Vec3.ZERO); });
        });
        Playtest.wait("settle", 15);
    }

    /** A careful thief (D-155): on the street, waits until no officer on the beat of {@code city} can see them before working a lock, and logs it when one could. */
    static void waitForClearStreet(String city) {
        final int[] t = {0};
        Playtest.queue("no officer on the beat in sight", () -> t[0] = 0, () -> {
            String seen = server(() -> {
                PolicePatrols.Patrol patrol = PolicePatrols.patrolOf(city);
                if (patrol == null) return null;
                for (var id : patrol.officers)
                    if (me().serverLevel().getEntity(id) instanceof TownspersonEntity o && o.distanceTo(me()) <= PolicePatrols.SIGHT + 8 && o.hasLineOfSight(me())) return o.getName().getString();
                return null;
            });
            if (seen != null && t[0] % 100 == 0) Playtest.log(tag + " " + seen + " is on the beat and in sight; the thief waits");
            t[0]++;
            return seen == null;
        }, 6000);
    }

    static void stopIfFailed(String what) {
        Playtest.queue("stop if " + what + " failed", () -> { if (Playtest.failures.length() > 0) { Playtest.log(tag + " stopping: " + what + " failed, and the rest depends on it"); summary(); Playtest.finish(); } });
    }

    // ------------------------------------------------------------------------------------------------ the house

    /** The searchable furniture of a building, nearest {@code from} first: one block per piece (the lower half of a two-block one). */
    static List<BlockPos> furniture(ServerLevel level, CityBuildings.Building b, BlockPos from) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (int y = b.minY(); y < b.maxY(); y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(b.chunkX() * 16 + x, y, b.chunkZ() * 16 + z);
            BlockState state = level.getBlockState(pos);
            if (BurglarySearch.LOADER.kindOf(state) != null) found.add(BurglarySearch.anchor(state, pos).immutable());
        }
        List<BlockPos> out = new ArrayList<>(found);
        out.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return out;
    }

    /** True when a unit of the building is to let in this world (a vacant home on the Home page). */
    private static boolean toLet(ServerLevel level, CityBuildings.Building b) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (plan == null) return false;
        for (int i = 0; i < plan.units().size(); i++) if (CityHomes.toLet(level.getSeed(), b, i)) return true;
        return false;
    }

    static void chooseHouse() {
        Playtest.queue("choose a Valewood house with nobody in, its alarm not armed, and furniture to search (on the server thread)", () -> {
            var server = mc().getSingleplayerServer();
            int cx = mc().player.getBlockX() >> 4, cz = mc().player.getBlockZ() >> 4;
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    long time = HouseholdService.dayTime(level);
                    // what is about: every building in the loaded chunks round the player, and for each house why it would or would not do
                    java.util.Map<String, Integer> classes = new java.util.TreeMap<>();
                    StringBuilder houses = new StringBuilder();
                    int loaded = 0;
                    for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                        if (!level.hasChunk(cx + dx, cz + dz)) continue;
                        loaded++;
                        var found = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz);
                        if (found.isEmpty()) continue;
                        var b = found.get();
                        classes.merge(b.security() == null ? "(none)" : b.security().id() + (b.locked() ? "" : " unlocked"), 1, Integer::sum);
                        if (b.security() == null || !b.security().id().equals("house") || houses.length() > 1500) continue;
                        houses.append(String.format(Locale.ROOT, " [%s %d,%d in %d, armed %s, furniture %d%s]", b.type().replace("underground_city:", ""), b.chunkX(), b.chunkZ(), Casing.peopleIn(level, b, time),
                            b.security().response() != null && PoliceResponse.armed(level, b, b.security().response()), furniture(level, b, BlockPos.ZERO).size(), toLet(level, b) ? ", to let" : ""));
                    }
                    Playtest.log("THIEF around chunk " + cx + "," + cz + ": " + loaded + " loaded chunks, buildings by class " + classes + "; houses:" + houses);
                    List<CityBuildings.Building> fit = new ArrayList<>();
                    for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                        if (!level.hasChunk(cx + dx, cz + dz)) continue;
                        // a house to let is a vacant listing (D-127): nobody lives there and its door is the home's, not a burglar's
                        CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).filter(b -> b.locked() && b.security() != null && b.security().id().equals("house") && !toLet(level, b)
                            && Casing.peopleIn(level, b, time) == 0 && (b.security().response() == null || !PoliceResponse.armed(level, b, b.security().response()))
                            && furniture(level, b, BlockPos.ZERO).size() >= 3).ifPresent(fit::add);
                    }
                    fit.sort(Comparator.comparingInt(b -> Math.abs(b.chunkX() - cx) + Math.abs(b.chunkZ() - cz)));
                    for (CityBuildings.Building b : fit) {
                        F.door = streetDoor(level, b);
                        if (F.door != null) { F.house = b; F.spots.addAll(furniture(level, b, F.door.door())); break; }
                    }
                } finally { F.looked = true; }
            });
        }, () -> F.looked, 2400);
        Playtest.queue("the house", () -> {
            if (F.door == null || F.house == null || !F.house.security().id().equals("house")) { Playtest.fail("no empty, unarmed Valewood house with three pieces of furniture near the city's middle"); return; }
            List<String> kinds = new ArrayList<>();
            for (BlockPos p : F.spots) kinds.add(server(() -> BurglarySearch.LOADER.kindOf(level().getBlockState(p)).title()));
            Playtest.log("THIEF target: " + F.house.type() + " (" + F.house.security().id() + ", tier " + F.house.security().tier() + ", lock " + F.house.security().lockProfile() + ") at chunk "
                + F.house.chunkX() + "," + F.house.chunkZ() + ", door " + F.door.door().toShortString() + "; furniture " + kinds);
            scripted("the house was chosen by the script: nobody in at 13:00, alarm not armed (from the game's own hash), at least three searchable pieces");
        });
    }

    /**
     * The street door of a building, as the guard sees one (a door with a side open to the sky, {@link BuildingGuard#exterior}), with two clear cells and a floor in front of its outer side, and
     * the spot a little over two blocks out where a person stands to use it. Every door of the building is logged with what the guard makes of it (a door under the eaves on both sides would
     * not be locked at all).
     */
    static DoorWalk.Spot streetDoor(ServerLevel level, CityBuildings.Building b) { return streetDoor(level, b, spot -> true); }

    /** The same, among the doors {@code accept} approves (the advanced burglary wants a door with a straight way in). */
    static DoorWalk.Spot streetDoor(ServerLevel level, CityBuildings.Building b, java.util.function.Predicate<DoorWalk.Spot> accept) {
        DoorWalk.Spot best = null;
        StringBuilder doors = new StringBuilder();
        for (int y = b.streetY(); y <= b.streetY() + 3; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(b.chunkX() * 16 + x, y, b.chunkZ() * 16 + z);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
            Direction facing = state.getValue(DoorBlock.FACING);
            boolean front = BuildingGuard.leadsOut(level, pos, facing), back = BuildingGuard.leadsOut(level, pos, facing.getOpposite());
            doors.append(String.format(Locale.ROOT, " [%s facing %s, front leads out %s, back leads out %s, %s]", pos.toShortString(), facing, front, back, BuildingGuard.exterior(level, pos, state) ? "exterior (locked)" : "inside door (not locked)"));
            if (best != null || !(front || back)) continue;
            Direction out = front && !back ? facing : !front && back ? facing.getOpposite()
                : (pos.relative(facing).distSqr(new BlockPos(b.chunkX() * 16 + 8, y, b.chunkZ() * 16 + 8)) > pos.relative(facing.getOpposite()).distSqr(new BlockPos(b.chunkX() * 16 + 8, y, b.chunkZ() * 16 + 8)) ? facing : facing.getOpposite());
            boolean clear = true;
            for (int k = 1; k <= 2 && clear; k++) {
                BlockPos cell = pos.relative(out, k);
                clear = StaffZones.walkable(level, cell);
            }
            if (!clear) continue;
            Vec3 stand = Vec3.atBottomCenterOf(pos).add(out.getStepX() * 2.2, 0, out.getStepZ() * 2.2);
            float yaw = (float) Math.toDegrees(Math.atan2(out.getStepX(), -out.getStepZ()));
            DoorWalk.Spot spot = new DoorWalk.Spot(pos.immutable(), facing, front, out, stand, yaw, b.type(), b.security().id());
            if (accept.test(spot)) best = spot;
        }
        Playtest.log(tag + " doors of " + b.type() + " at chunk " + b.chunkX() + "," + b.chunkZ() + ":" + doors + (best == null ? "; none usable" : "; using " + best.door().toShortString()));
        return best;
    }

    static void prepareAtDoor() {
        Playtest.queue("on the street in front of the house, with five lockpicks in the last hotbar slot and the slot before it empty", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer player = me();
                ServerLevel level = player.serverLevel();
                BlockState state = level.getBlockState(F.door.door());
                if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) door.setOpen(null, level, state, F.door.door(), false);
                var inventory = player.getInventory();
                for (int slot : new int[]{7, 8}) {
                    ItemStack held = inventory.getItem(slot);
                    if (held.isEmpty()) continue;
                    inventory.setItem(slot, ItemStack.EMPTY);
                    if (!inventory.add(held)) player.drop(held, false);
                }
                inventory.setItem(8, new ItemStack(UndergroundCity.item("lockpick"), 5));
            });
            standAt(F.door);
            scripted("five lockpicks were put in hotbar slot 9 and slot 8 emptied (a new player buys picks; the shop was played in other runs)");
        }, () -> mc().player.position().distanceTo(F.door.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
    }

    /** Crouches with an empty hand and right-clicks the house's door until the casing card opens; photographs it and logs what it says. */
    static void caseIt(String name, boolean expectSkill) {
        final int[] t = {0};
        Playtest.queue("an empty hand (slot 8) and crouch", () -> { Playtest.focusWindow(); Playtest.tap("8"); Playtest.keyDown("shift"); });
        Playtest.wait("crouched", 10);
        Playtest.queue("right-click the door while crouched until the casing card opens", () -> t[0] = 0, () -> {
            if (mc().screen instanceof CasingScreen) return true;
            look(Vec3.atCenterOf(F.door.door()).add(0, 0.4, 0));
            t[0]++;
            boolean onDoor = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && (hit.getBlockPos().equals(F.door.door()) || hit.getBlockPos().equals(F.door.door().above()));
            if (t[0] % 12 == 6 && onDoor) Playtest.mouseButton(3, true);
            if (t[0] % 12 == 8) Playtest.mouseButton(3, false);
            if (t[0] % 60 == 59 && !onDoor) { Playtest.keyDown("w"); }
            if (t[0] % 60 == 5) Playtest.keyUp("w");
            if (t[0] % 100 == 99) {
                String what = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? mc().level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() + " at " + hit.getBlockPos().toShortString() : "nothing";
                Playtest.log("THIEF casing: standing at " + mc().player.blockPosition().toShortString() + ", crouching " + mc().player.isCrouching() + ", holding " + mc().player.getMainHandItem()
                    + ", the door " + (server(() -> DoorWalk.doorOpen(level(), F.door)) ? "open" : "shut") + ", the crosshair on " + what);
            }
            return false;
        }, 600);
        Playtest.queue("stand up", () -> { Playtest.keyUp("shift"); Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("the card draws", 12);
        shot(name);
        Playtest.queue("what the card says", () -> {
            if (!(mc().screen instanceof CasingScreen screen)) { Playtest.fail("the casing card did not open"); return; }
            Casing.Report r = screen.report();
            Playtest.log("THIEF casing (" + name + "): " + String.join(" | ", Casing.lines(r)));
            boolean doorOpen = server(() -> DoorWalk.doorOpen(level(), F.door));
            if (doorOpen && !expectSkill) Playtest.fail("casing opened the door");
            if (!expectSkill) {
                if (r.tier() != F.house.security().tier() || r.alarm() >= 0 || r.inside() >= 0) Playtest.fail("a new thief's card shows more than the tier and the door, or the wrong tier: " + Casing.lines(r));
                else pass("2 casing as a new thief (crouch, empty hand, the door): the card shows the tier (" + Thievery.tierWord(r.tier()) + ") and the lock, and names Casing for the rest");
            } else {
                if (r.alarm() < 0 || r.inside() < 0 || r.cameras() < 0) Playtest.fail("with Casing learned the card still hides the alarm, the cameras or who is in: " + Casing.lines(r));
                else pass("8 casing again with Casing learned: the card now reads the alarm, the cameras and who is in (" + r.inside() + " inside at 22:00)");
            }
        });
        closeScreen("close the card");
    }

    static void pickTheDoor() {
        final int[] t = {0};
        Playtest.queue("the lockpicks in hand (slot 9); walk at the door and right-click until the lock screen opens", () -> {
            t[0] = 0;
            F.xpStart = thiefXp();
            Playtest.focusWindow();
            Playtest.tap("9");
            Playtest.keyDown("w");
        }, () -> {
            var player = mc().player;
            t[0]++;
            player.setYRot(F.door.yaw());
            player.setXRot(0);
            if (mc().screen instanceof LockpickScreen) return true;
            if (F.door.past(player.position()) > -1.0) { if (t[0] % 4 == 1) Playtest.mouseButton(3, true); else if (t[0] % 4 == 2) Playtest.mouseButton(3, false); }
            return false;
        }, 400);
        Playtest.queue("let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("the screen draws", 12);
        shot("2-deadbolt");
        Playtest.queue("what the lock screen offers", () -> {
            boolean rake = mc().screen != null && JourneyScenario.widget("Rake it") != null;
            boolean raking = server(() -> SkillService.has(me(), "thief.raking"));
            Playtest.log("THIEF lock screen: " + (rake ? "a Rake button" : "no Rake button") + "; Raking " + (raking ? "learned" : "not learned") + " (a deadbolt can be raked only by a thief who has learned Raking)");
            if (rake != raking) Playtest.fail("the Rake button does not follow the Raking skill");
        });
        BurglaryScenario.solve("the house's deadbolt", false);
        Playtest.wait("the door opens", 15);
        Playtest.queue("the door is open and getting in paid Thief XP by the house's tier", () -> {
            boolean open = server(() -> DoorWalk.doorOpen(level(), F.door));
            F.xpEntered = thiefXp();
            int expected = Thievery.ENTRY_XP_PER_TIER * F.house.security().tier();
            Playtest.log("THIEF door " + (open ? "OPEN" : "closed") + "; Thief XP " + F.xpStart + " -> " + F.xpEntered + " (expected +" + expected + "); police " + PoliceResponse.status());
            if (!open) Playtest.fail("the deadbolt was solved on the real screen but the door is shut");
            else if (F.xpEntered - F.xpStart != expected) Playtest.fail("getting in paid " + (F.xpEntered - F.xpStart) + " Thief XP, not " + expected);
            else pass("3 pick the deadbolt pin by pin on the real lock screen; the door opens and pays " + expected + " Thief XP (tier " + F.house.security().tier() + ")");
        });
        shot("3-door-open");
    }

    // ------------------------------------------------------------------------------------------------ the rooms

    /**
     * A cell a person can stand in near {@code spot}, inside the house, from which the piece can be seen and reached: floor below, room for the body, within three blocks, and a ray from the
     * eye that meets the piece itself first (not a wall or a counter in between). The nearest such cell.
     */
    private static BlockPos standBeside(BlockPos spot) { return standBeside(spot, F.house); }

    static BlockPos standBeside(BlockPos spot, CityBuildings.Building building) {
        return server(() -> {
            ServerLevel level = level();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BlockPos cell : BlockPos.betweenClosed(spot.offset(-3, -1, -3), spot.offset(3, 0, 3))) {
                if (!building.contains(cell) || cell.equals(spot)) continue;
                if (!StaffZones.walkable(level, cell)) continue;
                Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, 1.62, 0), target = Vec3.atCenterOf(spot);
                double distance = eye.distanceTo(target);
                if (distance > 4.0 || distance >= bestDistance) continue;
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, null));
                if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(spot)) continue;
                best = cell.immutable();
                bestDistance = distance;
            }
            return best;
        });
    }

    /**
     * Right-clicks a piece of furniture until its search starts: the view turns to the block (its middle, then above and below it, as a person tries another angle when something is in the way),
     * one click when the crosshair is on it, and a log line of what the crosshair is on when it is not.
     */
    static void searchClick(String label, Supplier<BlockPos> block, int budget) {
        final int[] t = {0};
        Playtest.queue(label, () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (ClientBurglary.searching()) return true;
            BlockPos b = block.get();
            if (b == null) return true;   // a piece the caller skipped
            Vec3 c = Vec3.atCenterOf(b);
            Vec3[] aims = {c, c.add(0, 0.3, 0), c.add(0, -0.3, 0), c.add(0, 0.45, 0)};
            look(aims[(t[0] / 24) % aims.length]);
            t[0]++;
            boolean onIt = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b);
            if (t[0] % 24 == 12 && onIt) Playtest.click(3);
            if (t[0] % 96 == 95) {
                String what = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? mc().level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() + " at " + hit.getBlockPos().toShortString() : "nothing";
                Playtest.log("THIEF " + label + ": standing at " + mc().player.blockPosition().toShortString() + ", the furniture at " + b.toShortString() + ", the crosshair on " + what);
            }
            return false;
        }, budget);
    }

    static void search() {
        final int[] index = {0};
        Playtest.queue("into the house", () -> F.xpSearched = F.xpEntered);
        // every piece of the house, as a burglar strips it (nine in a Valewood house), nearest the door first
        for (int n = 0; n < 9; n++) {
            final int which = n;
            final BlockPos[] stand = new BlockPos[1];
            Playtest.queue("the next piece of furniture", () -> {
                if (which >= F.spots.size()) return;
                stand[0] = standBeside(F.spots.get(which));
                if (stand[0] == null) Playtest.log("THIEF no cell with a clear view of " + F.spots.get(which).toShortString() + "; this piece is skipped");
            });
            Playtest.queue("stand beside the furniture " + (n + 1) + " (scripted: the walk between rooms is not what this scenario tests)", () -> {
                if (which >= F.spots.size() || stand[0] == null) return;
                BlockPos at = stand[0], spot = F.spots.get(which);
                scripted("the player was put beside the furniture " + (which + 1) + " at " + at.toShortString());
                var server = mc().getSingleplayerServer();
                server.execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
            }, () -> which >= F.spots.size() || stand[0] == null || mc().player.blockPosition().equals(stand[0]), 200);
            Playtest.wait("settle", 10);
            searchClick("right-click the furniture " + (n + 1) + " until the search starts", () -> which < F.spots.size() && stand[0] != null ? F.spots.get(which) : null, 400);
            if (n == 0) {
                Playtest.wait("the bar fills a little", 14);
                shot("4-searching");
            }
            until("the search " + (which + 1) + " ends", () -> which >= F.spots.size() || stand[0] == null || !ClientBurglary.searching() && !server(() -> BurglarySearch.searching(me().getUUID())), 400);
            Playtest.wait("the notice", 10);
            if (n == 0) shot("5-found");
            Playtest.queue("what the search " + (which + 1) + " found", () -> {
                if (which >= F.spots.size() || stand[0] == null) return;
                int xp = thiefXp();
                int[] s = stolen();
                Playtest.log("THIEF search " + (which + 1) + " at " + F.spots.get(which).toShortString() + " (" + server(() -> BurglarySearch.LOADER.kindOf(level().getBlockState(F.spots.get(which))).title())
                    + "): carrying " + s[0] + " stolen unit(s), " + s[1] + " with the player's name; Thief XP " + F.xpSearched + " -> " + xp);
                F.xpSearched = xp;
                F.finds = s[0];
                F.marked = s[1];
                F.lastSearched = which;
                F.searchedPieces++;
                index[0]++;
            });
        }
        // the piece just searched, once more: it is empty until it refills, so nothing starts
        final int[] k = {0};
        final boolean[] started = {false};
        Playtest.queue("right-click the piece just searched once more", () -> k[0] = 0, () -> {
            BlockPos p = F.spots.get(F.lastSearched);
            look(Vec3.atCenterOf(p));
            k[0]++;
            if (k[0] == 10 && aimedAt(p)) Playtest.mouseButton(3, true);
            if (k[0] == 12) Playtest.mouseButton(3, false);
            started[0] |= ClientBurglary.searching();
            return k[0] >= 40;
        }, 80);
        Playtest.queue("the furniture and the marks", () -> {
            boolean again = started[0] || server(() -> BurglarySearch.searching(me().getUUID()));
            if (again) Playtest.fail("a piece searched a moment ago could be searched again");
            Playtest.log("THIEF after " + F.searchedPieces + " searches: " + F.finds + " stolen unit(s), " + F.marked + " marked with the thief; Thief XP " + F.xpEntered + " -> " + F.xpSearched + "; a fourth search running: " + again);
            if (F.searchedPieces < 3) Playtest.fail("only " + F.searchedPieces + " pieces of furniture could be searched");
            else if (F.finds == 0) Playtest.fail(F.searchedPieces + " searches of a tier-" + F.house.security().tier() + " house found nothing at all");
            else if (F.marked != F.finds) Playtest.fail("found goods are not all marked stolen with the thief's name (" + F.marked + " of " + F.finds + ")");
            else pass("4 search " + F.searchedPieces + " pieces of furniture with the real right click: " + F.finds + " stolen unit(s), every one marked with the thief's name, " + (F.xpSearched - F.xpEntered) + " Thief XP");
        });
    }

    // ------------------------------------------------------------------------------------------------ the fence

    private static void fence() {
        Playtest.queue("night: 22:00, the fences' hours", () -> {
            Playtest.command("time set 16000");
            scripted("the time was set to 22:00 for the fence (fences deal from 21:00 to 03:00)");
        });
        travel("to the middle of Blackwater", () -> centre("blackwater"), 4);
        final boolean[] looked = {false};
        final int[] nights = {0};
        Playtest.queue("find the club, and a night its fence is in (on the server thread)", () -> {
            var server = mc().getSingleplayerServer();
            int cx = mc().player.getBlockX() >> 4, cz = mc().player.getBlockZ() >> 4;
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    F.clubDoor = DoorWalk.findNear(level, cx, cz, true, "club", b -> level.hasChunk(b.chunkX(), b.chunkZ()) && b.type().startsWith("underground_city:bw_club")).orElse(null);
                    if (F.clubDoor != null) F.club = CityBuildings.INSTANCE.at(level, F.clubDoor.door()).orElse(null);
                } finally { looked[0] = true; }
            });
        }, () -> looked[0], 2400);
        Playtest.queue("the fence is in tonight, or the next night", () -> { }, () -> {
            if (F.club == null) { Playtest.fail("no Blackwater club with a street door near the city's middle"); return true; }
            if (server(() -> FenceService.receivingNow(level(), F.club))) return true;
            if (nights[0]++ >= 6) { Playtest.fail("the club's fence was not in on seven nights running"); return true; }
            Playtest.command("time add 24000");
            scripted("the fence was not in that night; the clock moved on a day to 22:00");
            return false;
        }, 200);
        stopIfFailed("finding the fence");
        Playtest.queue("on the street in front of the club", () -> {
            Playtest.log("THIEF club " + F.club.type() + " at chunk " + F.club.chunkX() + "," + F.club.chunkZ() + ", door " + F.clubDoor.door().toShortString() + "; lock " + F.club.security().lockProfile());
            standAt(F.clubDoor);
            scripted("the player was put on the street in front of the club's door");
        }, () -> mc().player.position().distanceTo(F.clubDoor.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
        final int[] t = {0};
        Playtest.queue("right-click the club's door with the picks put away", () -> { t[0] = 0; Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            if (server(() -> DoorWalk.doorOpen(level(), F.clubDoor)) || mc().screen != null) return true;
            look(Vec3.atCenterOf(F.clubDoor.door()).add(0, 0.6, 0));
            t[0]++;
            boolean onDoor = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && (hit.getBlockPos().equals(F.clubDoor.door()) || hit.getBlockPos().equals(F.clubDoor.door().above()));
            if (t[0] % 16 == 8 && onDoor) Playtest.click(3);
            if (t[0] % 64 == 63) {
                String what = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? mc().level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() + " at " + hit.getBlockPos().toShortString() : "nothing";
                Playtest.log("THIEF at the club's door: standing at " + mc().player.blockPosition().toShortString() + ", the crosshair on " + what + "; open to this player " + server(() -> BuildingGuard.openToPublic(level(), F.club, me())));
            }
            return false;
        }, 300);
        Playtest.queue("let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("the door", 6);
        Playtest.queue("the fence's door opened for somebody carrying stolen goods", () -> {
            boolean open = server(() -> DoorWalk.doorOpen(level(), F.clubDoor));
            boolean forAnybody = server(() -> BuildingGuard.openToPublic(level(), F.club, null)), forMe = server(() -> BuildingGuard.openToPublic(level(), F.club, me()));
            Playtest.log("THIEF club door " + (open ? "OPEN" : "shut") + "; open to the public " + forAnybody + ", to this player " + forMe + "; screen " + (mc().screen == null ? "none" : mc().screen.getClass().getSimpleName()));
            if (mc().screen instanceof LockpickScreen) Playtest.fail("the fence's door asked for a lock at the fence's hours");
            else if (!open) Playtest.fail("the fence's door did not open at the fence's hours for somebody carrying stolen goods");
            else pass("5 at night the fence's door (a " + F.club.security().lockProfile() + " lock) opens to somebody carrying stolen goods, without a pick");
        });
        shot("6-club-door");
        Playtest.queue("a search inside the club while the fence is in is refused", () -> {
            String refusal = server(() -> {
                List<BlockPos> inside = furniture(level(), F.club, F.clubDoor.door());
                if (inside.isEmpty()) return "(no searchable furniture in the club)";
                BlockPos spot = inside.get(0);
                return String.valueOf(BurglarySearch.begin(me(), level(), spot, F.club, BurglarySearch.LOADER.kindOf(level().getBlockState(spot)), false));
            });
            Playtest.log("THIEF a search in the open club (asked of the server, not clicked): " + refusal);
            if (refusal.equals("null")) Playtest.fail("a search started inside the fence's building during the fence's hours");
        });
        final boolean[] seen = {false};
        until("the fence is in the building", () -> {
            Integer id = server(() -> {
                for (TownspersonEntity body : level().getEntitiesOfClass(TownspersonEntity.class, me().getBoundingBox().inflate(48), e -> e.role().equals("fence") && e.isAlive())) return body.getId();
                return null;
            });
            if (id != null) { F.fenceId = id; seen[0] = true; }
            return seen[0];
        }, 1200);
        // the fence keeps the club's back office, up a flight of stairs the path walker does not climb: the player is put on a cell near them with a clear view (scripted, logged)
        Playtest.queue("up to the fence's back office (scripted)", () -> {
            BlockPos at = server(() -> {
                Entity fence = level().getEntity(F.fenceId);
                if (fence == null) return null;
                BlockPos best = null;
                double bestDistance = Double.MAX_VALUE;
                for (BlockPos cell : BlockPos.betweenClosed(fence.blockPosition().offset(-3, -1, -3), fence.blockPosition().offset(3, 1, 3))) {
                    if (cell.equals(fence.blockPosition()) || !StaffZones.walkable(level(), cell)) continue;
                    Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, 1.62, 0);
                    double distance = eye.distanceTo(fence.getEyePosition());
                    if (distance < 1.5 || distance > 3.5 || distance >= bestDistance) continue;
                    var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, fence.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, null));
                    if (hit.getType() == HitResult.Type.BLOCK) continue;
                    best = cell.immutable();
                    bestDistance = distance;
                }
                return best;
            });
            if (at == null) { Playtest.fail("no cell near the fence with a clear view of them"); return; }
            scripted("the player was put at " + at.toShortString() + " in the club's back office, beside the fence (the path walker does not climb its stairs)");
            var server = mc().getSingleplayerServer();
            server.execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
        });
        Playtest.wait("settle", 20);
        Playtest.queue("before the sale", () -> { F.walletBefore = server(() -> QShopEconomy.balance(me())); F.xpSearched = thiefXp(); });
        speakTo("the fence", () -> mc().level.getEntity(F.fenceId));
        until("the conversation", () -> mc().screen instanceof DialogueScreen, 100);
        shot("7-fence-talk");
        clickAt("Show me what you would pay", () -> {
            if (!(mc().screen instanceof DialogueScreen d)) return null;
            for (int i = 0; d.choiceLabel(i) != null; i++) if (JourneyScenario.says(d.choiceLabel(i), "Show me what you would pay")) return d.choiceCentre(i);   // shown as "1  Show me..."
            return null;
        });
        until("the fence's screen", () -> mc().screen instanceof FenceScreen, 100);
        Playtest.wait("draw", 10);
        shot("8-fence-offer");
        clickAt("Sell all", () -> mc().screen instanceof FenceScreen f ? f.sellCentre("*") : null);
        Playtest.wait("the server answers", 30);
        shot("9-fence-sold");
        Playtest.queue("what the sale paid", () -> {
            double wallet = server(() -> QShopEconomy.balance(me()));
            F.xpSold = thiefXp();
            int[] s = stolen();
            Playtest.log("THIEF sale: wallet " + F.walletBefore + " -> " + wallet + ", Thief XP " + F.xpSearched + " -> " + F.xpSold + ", stolen units left " + s[0]);
            if (wallet <= F.walletBefore) Playtest.fail("selling to the fence paid nothing");
            else if (F.xpSold <= F.xpSearched) Playtest.fail("selling goods the thief took themselves paid no Thief XP");
            else pass(String.format(Locale.ROOT, "6 sell to the Blackwater fence through the real screen: %.0f credits, %d Thief XP for goods the thief took", wallet - F.walletBefore, F.xpSold - F.xpSearched));
        });
        closeScreen("close the fence's screen");
    }

    // ------------------------------------------------------------------------------------------------ the terminal

    private static void learn() {
        Playtest.queue("find the Founders Row terminal", () -> {
            F.terminal = server(() -> {
                BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
                if (row == null) return null;
                BlockPos guess = row.offset(4, 0, 2);
                for (BlockPos p : BlockPos.betweenClosed(guess.offset(-3, -2, -3), guess.offset(3, 2, 3))) if (level().getBlockState(p).is(UndergroundCity.TERMINAL.get())) return p.immutable();
                return null;
            });
            if (F.terminal == null) Playtest.fail("no terminal on Founders Row");
        });
        travel("to Founders Row", () -> F.terminal == null ? null : new Vec3(F.terminal.getX() + 0.5, F.terminal.getY() + 30, F.terminal.getZ() + 2.5), 2);
        Playtest.queue("stand in front of the terminal", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                Direction facing = level().getBlockState(F.terminal).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
                BlockPos stand = F.terminal.relative(facing);
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(level(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, facing.getOpposite().toYRot(), 20f);
                me().setDeltaMovement(Vec3.ZERO);
            });
            scripted("the player was put in front of the Founders Row terminal");
        }, () -> mc().player.position().distanceTo(Vec3.atBottomCenterOf(F.terminal)) < 2.5, 600);
        Playtest.wait("settle", 25);
        use("right-click the terminal", () -> F.terminal, () -> mc().screen instanceof TerminalScreen, 400);
        until("the terminal opens", () -> mc().screen instanceof TerminalScreen, 100);
        click("Thief", true);
        Playtest.wait("draw", 12);
        shot("10-thief-page");
        Playtest.queue("Thief level and points", () -> {
            CitizenState.Citizen c = citizen();
            int points = server(() -> SkillService.thiefPointsAvailable(c));
            Playtest.log("THIEF level " + c.thiefLevel() + " with " + c.thiefXp() + " XP, " + points + " Thief point(s); career points are separate");
            if (c.thiefLevel() < 2 || points < 1) Playtest.fail("one house and one sale did not reach Thief level 2 (" + c.thiefXp() + " XP)");
        });
        stopIfFailed("reaching Thief level 2");
        clickAt("the Casing card", () -> mc().screen instanceof TerminalScreen t ? t.skillCentre(FIRST_SKILL) : null);
        Playtest.wait("select", 8);
        shot("11-casing-selected");
        click("Unlock", true);
        until("learned", () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.skills().contains(FIRST_SKILL)).orElse(false)), 200);
        Playtest.wait("draw", 10);
        shot("12-casing-learned");
        Playtest.queue("learned", () -> pass("7 learn Casing on the terminal's Thief page with a Thief point (the career's points untouched)"));
        closeScreen("close the terminal");
    }

    private static void caseAgain() {
        Playtest.queue("back to the house's door (scripted)", () -> {
            scripted("the player was put back in front of the Valewood house, hotbar slot 8 emptied again");
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                var inventory = me().getInventory();
                ItemStack held = inventory.getItem(7);
                if (held.isEmpty()) return;
                inventory.setItem(7, ItemStack.EMPTY);
                if (!inventory.add(held)) me().drop(held, false);
            });
            standAt(F.door);
        }, () -> mc().player.position().distanceTo(F.door.stand()) < 1.5, 1200);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        until("the house's door is on the client and shut", () -> mc().level.isLoaded(F.door.door()) && !server(() -> DoorWalk.doorOpen(level(), F.door)), 1200);
        Playtest.wait("settle", 30);
        caseIt("13-casing-with-skill", true);
    }

    static void summary() {
        Playtest.log(tag + " summary: " + F.passed.size() + " step(s) passed, " + F.scripted.size() + " scripted shortcut(s)");
        for (String p : F.passed) Playtest.log(tag + "   passed: " + p);
        for (String s : F.scripted) Playtest.log(tag + "   scripted: " + s);
    }
}
