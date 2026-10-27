package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * Who may open which door (D-188) through a real client ({@code -PucPlaytest=doors}, a new furnished archipelago world): a real right click with an empty hand on the street door of a Valewood
 * house that is to let (it stays shut and says how to lease it), of a house somebody lives in (locked), of a shop in its hours (it opens) and of the same shop at night (locked); then the house is
 * leased and its tenant's click opens it; then, in an Ironhaven block of flats, the own door of a flat its household lives in stays locked to a stranger in the stairwell, and a lockpick starts on it.
 *
 * <p>Scripted, each logged: the clock, the travel to each city, standing at each door (the doors are what is tested, not the walk), the credits for the lease (the operator command) and the lease
 * itself (made by the server as the Home page's Lease button makes it; the Home page was played in the homes runs), and standing in the stairwell of the flats.
 */
final class DoorsScenario {
    private DoorsScenario() {}

    private static final class Facts {
        volatile DoorWalk.Spot vacant, occupied, shop, flatStreet;
        volatile CityBuildings.Building vacantBuilding, flats;
        volatile BlockPos flatDoor, stairwell;
        volatile String vacantUnit;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "DOORS";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        Playtest.wait("the streets load", 200);
        Playtest.queue("the doors to try (on the server)", () -> {
            server(() -> { find(level()); return null; });
            Playtest.log("DOORS vacant " + describe(F.vacant) + ", occupied " + describe(F.occupied) + ", shop " + describe(F.shop));
            if (F.vacant == null || F.occupied == null || F.shop == null) Playtest.fail("a house to let, a house somebody lives in and a shop were not all found near Valewood's middle");
        });
        ThiefScenario.stopIfFailed("finding the doors");
        tryDoor("2 the street door of a house to let", () -> F.vacant, false, "To let", "1-to-let");
        Playtest.queue("the card under the crosshair at the house to let", () -> {
            PropertyIntel.Peek card = ClientPlaces.current();
            Playtest.log("DOORS card at the house to let: " + (card == null ? "none" : card.status() + " " + card.address()));
            if (card == null || card.status() != PropertyIntel.Status.TO_LET) Playtest.fail("the card at a house to let does not say it is to let (" + (card == null ? "no card" : card.status()) + ")");
            else pass("2b the card at the house to let reads To let, with the address, before anybody has leased it");
        });
        tryDoor("3 the street door of a house somebody lives in, by day", () -> F.occupied, false, "lock", "2-lived-in");
        tryDoor("4 a shop's street door in its hours", () -> F.shop, true, null, "3-shop-open");
        Playtest.queue("the clock: 23:00", () -> { Playtest.command("time set 17000"); scripted("the time was set to 23:00"); });
        Playtest.wait("the shopkeeper goes home", 200);
        tryDoor("5 the same shop's street door at night", () -> F.shop, false, "lock", "4-shop-night");
        // the lease: the house to let opens for its tenant
        Playtest.queue("credits and the lease of the house to let", () -> {
            Playtest.command("uc admin credits " + mc().player.getGameProfile().getName() + " add 3000");
            scripted("3000 credits were added with /uc admin credits, then the house was leased by the server as the Home page's Lease button does");
        });
        Playtest.wait("the command", 10);
        Playtest.queue("lease", () -> {
            String why = server(() -> {
                CityHomes.ensureBuilding(level(), F.vacantBuilding);
                CrewLedger ledger = CrewService.ledger(level().getServer());
                CrewLedger.Residence unit = null;
                for (CrewLedger.Residence r : ledger.allResidences()) if (r.id.startsWith(CityHomes.buildingPrefix(F.vacantBuilding))) unit = r;
                if (unit == null) return "the house's unit was never registered";
                F.vacantUnit = unit.id;
                PropertyService.rent(me(), UUID.randomUUID(), unit.id, false, false);
                CrewLedger.Residence after = ledger.residence(unit.id);
                return after != null && after.owned() ? null : "the lease did not go through";
            });
            Playtest.log("DOORS lease of " + F.vacantUnit + ": " + (why == null ? "done" : why));
            if (why != null) Playtest.fail(why);
        });
        Playtest.wait("settle", 20);
        tryDoor("6 the leased house's street door, for its tenant", () -> F.vacant, true, null, "5-tenant");
        // a household's own flat in a block of flats
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 4);
        Playtest.queue("the clock: 13:00", () -> Playtest.command("time set 7000"));
        Playtest.wait("the streets load", 200);
        Playtest.queue("a block of flats with a household's flat (on the server)", () -> {
            server(() -> { findFlats(level()); return null; });
            Playtest.log("DOORS flats " + (F.flats == null ? "none" : F.flats.type() + " at chunk " + F.flats.chunkX() + "," + F.flats.chunkZ()) + ", a household's flat door " + F.flatDoor + ", stairwell " + F.stairwell);
            if (F.flatDoor == null || F.stairwell == null) Playtest.fail("no household's flat door with a stairwell cell in front of it was found near Ironhaven's middle");
        });
        ThiefScenario.stopIfFailed("finding the flats");
        Playtest.queue("into the stairwell", () -> {
            BlockPos at = F.stairwell;
            scripted("the player was put in the stairwell of the flats at " + at.toShortString() + " (as if a neighbour had let them in)");
            mc().getSingleplayerServer().execute(() -> {
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                me().setDeltaMovement(Vec3.ZERO);
            });
        });
        Playtest.wait("settle", 30);
        clickDoorAt("7 a household's own flat door, from the stairwell, empty-handed", () -> F.flatDoor, false, "Somebody lives here", "6-flat-door");
        Playtest.queue("summary", ThiefScenario::summary);
    }

    private static String describe(DoorWalk.Spot s) { return s == null ? "none" : s.type() + " door " + s.door().toShortString(); }

    /** Near the player: a single-unit house to let, a house somebody lives in (not to let), and a shop with a counter. Server thread. */
    private static void find(ServerLevel level) {
        int cx = me().getBlockX() >> 4, cz = me().getBlockZ() >> 4;
        for (int r = 0; r <= 10; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r || !level.hasChunk(cx + dx, cz + dz)) continue;
            var b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).orElse(null);
            if (b == null || !b.locked()) continue;
            BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
            if (plan == null) continue;
            boolean let = CityHomes.anyLet(level.getSeed(), b);
            if (F.vacant == null && plan.use().equals("house") && plan.units().size() == 1 && let) { F.vacant = ThiefScenario.streetDoor(level, b); if (F.vacant != null) F.vacantBuilding = b; }
            else if (F.occupied == null && plan.use().equals("house") && !let) F.occupied = ThiefScenario.streetDoor(level, b);
            else if (F.shop == null && plan.use().equals("shop") && ShopCounters.openNow(level, b)) F.shop = ThiefScenario.streetDoor(level, b);
        }
    }

    /** A block of flats near the player with a unit its household lives in: that unit's own door (inside the block) and a cell in front of it on the stair side. Server thread. */
    private static void findFlats(ServerLevel level) {
        int cx = me().getBlockX() >> 4, cz = me().getBlockZ() >> 4;
        // a block of flats first; a pub or a shop with rooms over it only when no block of flats is in reach
        for (int pass = 0; pass < 2 && F.flatDoor == null; pass++)
        for (int r = 0; r <= 12 && F.flatDoor == null; r++) for (int dx = -r; dx <= r && F.flatDoor == null; dx++) for (int dz = -r; dz <= r && F.flatDoor == null; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r || !level.hasChunk(cx + dx, cz + dz)) continue;
            var b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).orElse(null);
            if (b == null || !b.locked()) continue;
            BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
            if (plan == null || plan.units().size() < 2 || pass == 0 && !plan.use().equals("flats")) continue;
            for (int y = b.streetY(); y < b.streetY() + 20 && F.flatDoor == null; y++) for (int x = 0; x < 16 && F.flatDoor == null; x++) for (int z = 0; z < 16 && F.flatDoor == null; z++) {
                BlockPos p = new BlockPos((b.chunkX() << 4) + x, y, (b.chunkZ() << 4) + z);
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof DoorBlock) || s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
                int unit = BuildingGuard.householdUnitDoor(level, b, p, s);
                if (unit < 0) continue;
                BuildingSpots.Unit u = plan.units().get(unit);
                // the cell on the side of the door outside the unit, where a person in the stairwell stands
                for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    BlockPos cell = p.relative(side);
                    int lx = cell.getX() - (b.chunkX() << 4), ly = cell.getY() - b.streetY(), lz = cell.getZ() - (b.chunkZ() << 4);
                    if (!u.contains(lx, ly, lz) && StaffZones.walkable(level, cell)) { F.flats = b; F.flatDoor = p.immutable(); F.stairwell = cell.immutable(); break; }
                }
            }
        }
    }

    private static void tryDoor(String step, Supplier<DoorWalk.Spot> spot, boolean shouldOpen, String saying, String picture) {
        Playtest.queue("stand at the door for: " + step, () -> {
            DoorWalk.Spot s = spot.get();
            if (s == null) return;
            // shut it first, as a passer-by finds it
            server(() -> { BlockState st = level().getBlockState(s.door()); if (st.getBlock() instanceof DoorBlock d && st.getValue(DoorBlock.OPEN)) d.setOpen(null, level(), st, s.door(), false); return null; });
            ThiefScenario.standAt(s);
            scripted("the player was put on the street at the door (" + step + ")");
        });
        Playtest.wait("settle", 25);
        clickDoorAt(step, () -> spot.get() == null ? null : spot.get().door(), shouldOpen, saying, picture);
    }

    /** An empty hand, the crosshair on the door, one real right click; then whether it opened and what the player was told. */
    private static void clickDoorAt(String step, Supplier<BlockPos> door, boolean shouldOpen, String saying, String picture) {
        final int[] t = {0}, mark = {0};
        final boolean[] clicked = {false};
        Playtest.queue("an empty hand and one right click on the door (" + step + ")", () -> { t[0] = 0; clicked[0] = false; mark[0] = Playtest.heardCount(); Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            BlockPos d = door.get();
            if (d == null) return true;
            look(Vec3.atCenterOf(d).add(0, 0.5, 0));
            t[0]++;
            boolean onIt = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && (hit.getBlockPos().equals(d) || hit.getBlockPos().equals(d.above()));
            if (onIt && t[0] > 6) { Playtest.click(3); clicked[0] = true; return true; }
            if (t[0] > 120) Playtest.log("DOORS the crosshair never reached the door at " + d.toShortString() + " (" + step + "): it rests on " + mc().hitResult.getType() + (mc().hitResult instanceof BlockHitResult h ? " " + h.getBlockPos().toShortString() : ""));
            return t[0] > 120;
        }, 140);
        Playtest.wait("the answer", 20);
        shot(picture);
        Playtest.queue("what the door did (" + step + ")", () -> {
            BlockPos d = door.get();
            if (d == null) { Playtest.fail("no door for " + step); return; }
            boolean open = server(() -> level().getBlockState(d).getValue(DoorBlock.OPEN));
            String said = saying == null ? null : Playtest.heardSince(mark[0], saying);
            Playtest.log(String.format(Locale.ROOT, "DOORS %s: %s; told %s", step, open ? "OPEN" : "shut", said));
            boolean survival = mc().gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SURVIVAL;
            if (!clicked[0]) Playtest.fail(step + ": the door was never clicked");
            else if (!survival) Playtest.fail(step + ": the player was not in survival mode (" + mc().gameMode.getPlayerMode() + ")");
            else if (open != shouldOpen) Playtest.fail(step + ": the door is " + (open ? "open" : "shut") + ", it should be " + (shouldOpen ? "open" : "shut"));
            else if (saying != null && said == null) Playtest.fail(step + ": the player was not told \"" + saying + "...\"");
            else pass(step + ": " + (open ? "it opens" : "it stays shut") + (said == null ? "" : " (\"" + said + "\")"));
            if (open) server(() -> { BlockState st = level().getBlockState(d); if (st.getBlock() instanceof DoorBlock dd) dd.setOpen(null, level(), st, d, false); return null; });
        });
    }
}
