package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;

/**
 * The owner's police case 8 with two real clients (twelfth pass, {@link DuoSessionServer}, after the supply chain): the Valewood Farmer strikes a passer-by in Valewood's street while the
 * Blackwater Vintner, their Crew partner, stands two blocks away with marked goods in their pockets. The blow is the server's own path for a client's left click ({@code attack}); the
 * response, the officers' walk and the arrest are the game's own. Only the Farmer may be fined, take a prior conviction and lose anything; the Vintner's wallet, record and pockets
 * must not change. Phase 2 checks both records after the restart.
 *
 * <p>Scripted, each logged {@code SCRIPTED}: the Vintner brought to the Farmer's side, the passer-by brought within reach, and the marked gold put in the Vintner's pockets (as a share of a
 * burglary the Farmer did not commit). A first blow seen by an officer on the beat with a clean record is only a warning (D-209); then a second passer-by is struck.
 */
final class DuoPolice {
    static final String CITY = "valewood";
    static final int GOLD = 2;

    private int step, ticks, blows;
    private TownspersonEntity victim;
    private double valeBefore, blackBefore;
    private int valePriors;
    private long arrestsBefore, warningsBefore, assaultsBefore;

    private static void scripted(String what) { System.out.println("[UC-DUO] SCRIPTED: " + what); }

    /** One tick of the case; true when it has run to its end. */
    boolean tick(MinecraftServer server, ServerPlayer vale, ServerPlayer black, JsonObject report, DuoSupplyChain.Check check) {
        ServerLevel level = server.overworld();
        ticks++;
        switch (step) {
            case 0 -> {
                if (ticks < 60 || !DuoSupplyChain.groundTicking(level, vale)) { if (ticks > 20 * 120) throw new IllegalStateException("the ground round the Farmer did not load in two minutes"); return false; }
                check.check("the Farmer stands in Valewood for the police case", CITY.equals(CityRegions.INSTANCE.cityAt(level, vale.blockPosition()).orElse("")), "at " + vale.blockPosition().toShortString());
                BlockPos beside = near(level, vale.blockPosition(), 2, null);
                if (beside == null) throw new IllegalStateException("no free ground two blocks from the Farmer at " + vale.blockPosition().toShortString());
                black.teleportTo(level, beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5, black.getYRot(), 0);
                ItemStack gold = new ItemStack(Items.GOLD_INGOT, GOLD);
                StolenGoods.mark(gold, CITY);
                black.getInventory().add(gold);
                scripted("the Vintner was brought two blocks from the Farmer, with " + GOLD + " gold ingots marked stolen in their pockets (a share of a burglary the Farmer did not commit)");
                WorkOrderLedger orders = WorkOrderLedger.get(server);
                valeBefore = QShopEconomy.balance(vale); blackBefore = QShopEconomy.balance(black);
                valePriors = orders.priors(vale.getUUID(), CITY);
                arrestsBefore = PoliceResponse.arrests; warningsBefore = CrimeWatch.warnings; assaultsBefore = CrimeWatch.assaults;
                System.out.println("[UC-DUO] police before: Farmer " + valeBefore + " credits, priors " + valePriors + "; Vintner " + blackBefore + " credits, priors " + orders.priors(black.getUUID(), CITY)
                    + ", warrant " + (orders.warrant(black.getUUID(), CITY) != null) + "; " + PoliceResponse.status());
                next();
            }
            case 1 -> {
                // a passer-by within the street people's reach of the Farmer (not an officer)
                victim = level.getEntitiesOfClass(TownspersonEntity.class, new AABB(vale.blockPosition()).inflate(48), p -> p.isAlive() && !p.police() && p.city() != null && p.city().equals(CITY))
                    .stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(vale))).orElse(null);
                if (victim == null) { if (ticks > 20 * 120) throw new IllegalStateException("no passer-by came within 48 blocks of the Farmer in two minutes"); return false; }
                BlockPos reach = near(level, vale.blockPosition(), 1, black.blockPosition());
                if (reach == null) throw new IllegalStateException("no free ground beside the Farmer for the passer-by");
                victim.teleportTo(reach.getX() + 0.5, reach.getY(), reach.getZ() + 0.5);
                scripted("the passer-by " + victim.getName().getString() + " was brought within reach of the Farmer");
                next();
            }
            case 2 -> {
                if (ticks < 10) return false;
                vale.attack(victim);
                vale.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                blows++;
                boolean counted = CrimeWatch.assaults > assaultsBefore;
                boolean warned = CrimeWatch.warnings > warningsBefore;
                System.out.println("[UC-DUO] police: the Farmer's blow " + blows + " on " + victim.getName().getString() + ": counted " + counted + ", warned " + warned + "; " + PoliceResponse.status());
                if (!counted) { if (blows >= 3) throw new IllegalStateException("three blows by the Farmer were never counted as an assault"); ticks = 0; return false; }
                if (warned && !PoliceResponse.wanted(vale.getUUID())) {
                    // an officer on the beat saw a first minor offence and warned: the next one is pursued; strike somebody else
                    check.check("a first blow seen by an officer on the beat is a warning", true, "warnings " + CrimeWatch.warnings);
                    warningsBefore = CrimeWatch.warnings; assaultsBefore = CrimeWatch.assaults; victim = null; step = 1; ticks = 0;
                    return false;
                }
                check.check("the Farmer's blow is reported and a response is out for the Farmer alone", PoliceResponse.wanted(vale.getUUID()) && !PoliceResponse.wanted(black.getUUID()),
                    "Farmer wanted " + PoliceResponse.wanted(vale.getUUID()) + ", Vintner wanted " + PoliceResponse.wanted(black.getUUID()) + "; " + PoliceResponse.status());
                next();
            }
            case 3 -> {
                // both stand still: the officers walk in and arrest (five minutes at most)
                if (ticks % 400 == 0) System.out.println("[UC-DUO] police waiting " + ticks / 20 + " s: " + PoliceResponse.status());
                if (PoliceResponse.arrests > arrestsBefore) { settled(server, vale, black, report, check); return true; }
                if (!PoliceResponse.wanted(vale.getUUID()) && ticks > 200) throw new IllegalStateException("the response for the Farmer ended without an arrest: " + PoliceResponse.status());
                if (ticks > 20 * 300) throw new IllegalStateException("no officer arrested the Farmer, who stood still, in five minutes: " + PoliceResponse.status());
            }
            default -> { return true; }
        }
        return false;
    }

    private void next() { step++; ticks = 0; }

    /** A free cell on the ground {@code distance} blocks from {@code at} (on the same level, a step up or down allowed), other than {@code not}; null when there is none. */
    private static BlockPos near(ServerLevel level, BlockPos at, int distance, BlockPos not) {
        for (int dy : new int[] {0, 1, -1}) for (int dx = -distance; dx <= distance; dx++) for (int dz = -distance; dz <= distance; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
            BlockPos c = at.offset(dx, dy, dz);
            if (!c.equals(not) && CityArrival.standable(level, c)) return c;
        }
        return null;
    }

    private void settled(MinecraftServer server, ServerPlayer vale, ServerPlayer black, JsonObject report, DuoSupplyChain.Check check) {
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        int fine = (int) Math.min(valeBefore, LawMath.escalatedFine(CityLaws.INSTANCE.offence(CITY, CityLaws.ASSAULT).fine(), valePriors));
        double vale1 = QShopEconomy.balance(vale), black1 = QShopEconomy.balance(black);
        int gold = black.getInventory().countItem(Items.GOLD_INGOT);
        System.out.println("[UC-DUO] police after: Farmer " + valeBefore + " -> " + vale1 + " (fine " + fine + "), priors " + orders.priors(vale.getUUID(), CITY) + ", warrant " + (orders.warrant(vale.getUUID(), CITY) != null)
            + "; Vintner " + blackBefore + " -> " + black1 + ", priors " + orders.priors(black.getUUID(), CITY) + ", warrant " + (orders.warrant(black.getUUID(), CITY) != null) + ", gold " + gold + "; " + PoliceResponse.status());
        check.check("the Farmer, who struck, is arrested and fined", Math.abs(valeBefore - vale1 - fine) < 0.01 && orders.priors(vale.getUUID(), CITY) == valePriors + 1 && orders.warrant(vale.getUUID(), CITY) == null,
            "wallet " + valeBefore + " -> " + vale1 + " (the fine " + fine + "), priors " + valePriors + " -> " + orders.priors(vale.getUUID(), CITY));
        check.check("the Vintner beside them, carrying marked goods, is not arrested, fined, searched or wanted", Math.abs(black1 - blackBefore) < 0.01 && orders.priors(black.getUUID(), CITY) == 0
            && orders.warrant(black.getUUID(), CITY) == null && !PoliceResponse.wanted(black.getUUID()) && gold == GOLD,
            "wallet " + blackBefore + " -> " + black1 + ", priors " + orders.priors(black.getUUID(), CITY) + ", gold " + gold + " of " + GOLD);
        report.addProperty("police_farmer_credits", vale1);
        report.addProperty("police_vintner_credits", black1);
        report.addProperty("police_farmer_priors", orders.priors(vale.getUUID(), CITY));
        report.addProperty("police_fine", fine);
        BlockPos va = CityArrival.placed(server, vale.getUUID()).orElseThrow().pos(), ba = CityArrival.placed(server, black.getUUID()).orElseThrow().pos();
        vale.teleportTo(server.overworld(), va.getX() + 0.5, va.getY(), va.getZ() + 0.5, 0, 0);
        black.teleportTo(server.overworld(), ba.getX() + 0.5, ba.getY(), ba.getZ() + 0.5, 0, 0);
        scripted("both players were put back at their arrivals");
    }

    /** Phase 2: both records as phase 1 left them. */
    static void kept(MinecraftServer server, ServerPlayer vale, ServerPlayer black, JsonObject first, DuoSupplyChain.Check check) {
        if (!first.has("police_farmer_priors")) return;
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        int priors = first.get("police_farmer_priors").getAsInt();
        check.check("the Farmer's conviction and the Vintner's clean record survived the restart", orders.priors(vale.getUUID(), CITY) == priors && orders.warrant(vale.getUUID(), CITY) == null
            && orders.priors(black.getUUID(), CITY) == 0 && orders.warrant(black.getUUID(), CITY) == null && !PoliceResponse.wanted(black.getUUID()),
            "Farmer priors " + orders.priors(vale.getUUID(), CITY) + " (was " + priors + "), Vintner priors " + orders.priors(black.getUUID(), CITY));
    }
}
