package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import io.github.stoffe101.undergroundcity.PropertyRules.Rules;
import io.github.stoffe101.undergroundcity.PropertyRules.Standing;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;
import static io.github.stoffe101.undergroundcity.CrewGameTests.rejected;

/**
 * Renting and buying a home (D-099): tenancy terms, the active-time billing clock, payments from a wallet and from the Crew bank, the grace period and the lapse that
 * never takes a person's things, moving out, selling back, upgrades, the storage and facility rules the guard enforces, migration of homes from older saves, and
 * conservation of credits under random play with simulated crashes. Everything runs on the dedicated test server against a scratch ledger and an in-memory wallet.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PropertyGameTests {
    private static final String OVERWORLD = "minecraft:overworld";

    static Terms flatTerms(String id) {
        return new Terms(id, "Test flat", "small_apartment", 2, "Test", Set.of(Tenure.RENT, Tenure.OWN), 40, 80, 2000, 8, 2, 1, Set.of("bed", "storage", "crafting", "furnace", "terminal"), "test");
    }

    static Terms motelTerms(String id) {
        return new Terms(id, "Test motel", "motel_room", 1, "Test", Set.of(Tenure.RENT), 10, 0, 0, 0, 2, 1, Set.of("bed", "storage", "terminal"), "test");
    }

    static Terms houseTerms(String id) {
        return new Terms(id, "Test house", "workshop_house", 6, "Test", Set.of(Tenure.RENT, Tenure.OWN), 100, 200, 7000, 20, 10, 3, Set.of("bed", "storage", "crafting", "furnace", "terminal", "workshop"), "test");
    }

    /** The scratch ledger and wallet plus a catalog holding the listings a test installs; everything is put back on close. */
    static final class Fix implements AutoCloseable {
        final CrewGameTests.Rig rig;
        CrewLedger l;
        final ServerLevel level;
        final BlockPos base;
        final Rules rules = Rules.DEFAULT;
        final Rules previousRules = PropertyCatalog.INSTANCE.rules();
        final Map<String, Terms> previousListings = PropertyCatalog.INSTANCE.all();
        final Map<String, io.github.stoffe101.undergroundcity.PropertyRules.Upgrade> previousUpgrades = PropertyCatalog.INSTANCE.upgrades();
        final Map<UUID, ServerPlayer> online = new HashMap<>();
        final java.util.function.BiFunction<net.minecraft.server.MinecraftServer, UUID, ServerPlayer> previousLookup = PropertyService.lookup;

        Fix(GameTestHelper helper, Map<String, Terms> listings) throws Exception {
            rig = new CrewGameTests.Rig(helper);
            l = rig.ledger;
            level = helper.getLevel();
            base = helper.absolutePos(new BlockPos(0, 2, 0));
            PropertyCatalog.INSTANCE.install(rules, listings, previousUpgrades);
            PropertyService.lookup = (server, uuid) -> online.get(uuid);
        }

        ServerPlayer person(GameTestHelper helper, String name, String career, double credits) {
            ServerPlayer p = player(helper, name, career);
            p.moveTo(base.getX() + 2.5, base.getY() + 1, base.getZ() + 2.5);
            p.setGameMode(GameType.SURVIVAL);
            rig.wallet.balances.put(p.getUUID(), credits);
            online.put(p.getUUID(), p);
            return p;
        }

        Residence home(String id, int slot) {
            BlockPos at = base.offset(slot * 20, 0, 0);
            return l.registerResidence(id, "Home " + id, "flat", OVERWORLD, "test", at, at.offset(4, 3, 4), 50, -1);
        }

        BlockPos in(int slot, int x, int y, int z) { return base.offset(slot * 20 + x, y, z); }

        double wallet(ServerPlayer p) { return rig.wallet.balances.get(p.getUUID()); }

        long tick(long ticks, ServerPlayer... who) {
            Set<UUID> set = new java.util.HashSet<>();
            for (ServerPlayer p : who) set.add(p.getUUID());
            PropertyService.tickClock(rig.server, ticks, set);
            return ticks;
        }

        /** What a restart does: the ledger is read from disk again (and the fixture follows it, so a test never holds a stale one). */
        void restart() { rig.restart(); l = rig.ledger; }

        @Override public void close() throws Exception {
            // the test world is shared: take back every block the test put in it, so a later test (or a district built there) finds it clear
            for (int x = -1; x <= 5 * 20 + 6; x++) for (int y = -1; y <= 5; y++) for (int z = -1; z <= 7; z++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
            PropertyService.lookup = previousLookup;
            PropertyCatalog.INSTANCE.install(previousRules, previousListings, previousUpgrades);
            rig.close();
        }
    }

    static Map<String, Terms> listings(Terms... terms) {
        Map<String, Terms> map = new LinkedHashMap<>();
        for (Terms t : terms) map.put(t.id(), t);
        return map;
    }

    // ------------------------------------------------------------------------------------------------------------ the data

    @GameTest(template = "empty")
    public static void theShippedLadderLoadsAndIsCoherent(GameTestHelper helper) {
        Rules rules = PropertyCatalog.INSTANCE.rules();
        check(rules.cycleMinutes() == 60 && rules.graceCycles() == 2 && rules.noticeCycles() == 1 && rules.lapseAt() == 4, "the shipped rules: " + rules);
        check(rules.sellBackPercent() < 100, "selling back must return less than was paid");
        for (String id : List.of("row_motel:motel_1", "row_motel:motel_2", "row_motel:motel_3", "row_motel:motel_4", "row_flats:flat_1", "row_flats:flat_2", "row_flats:row_house")) {
            check(PropertyCatalog.INSTANCE.find(id).isPresent(), "the ladder has no listing for " + id);
        }
        Terms motel = PropertyCatalog.INSTANCE.find("row_motel:motel_1").get(), flat = PropertyCatalog.INSTANCE.find("row_flats:flat_1").get(), house = PropertyCatalog.INSTANCE.find("row_flats:row_house").get();
        check(motel.canRent() && !motel.canBuy(), "a motel room is rented, never bought");
        check(flat.canRent() && flat.canBuy() && house.canRent() && house.canBuy(), "flats and the house can be rented or bought");
        check(motel.rent() < flat.rent() && flat.rent() < house.rent() && flat.buyPrice() < house.buyPrice(), "the ladder rises");
        check(flat.deposit() == flat.rent() * rules.depositCycles(), "the deposit is the stated number of cycles of rent");
        check(house.has("workshop") && !flat.has("workshop") && !motel.has("crafting"), "capabilities differ by tier");
        check(PropertyCatalog.INSTANCE.upgrades().size() == 3 && PropertyCatalog.INSTANCE.upgrade("plumbing") != null, "the upgrades are listed");
        check(PropertyRules.scaled(flat.rent(), 115) == 46 && PropertyRules.scaled(flat.buyPrice(), 90) == 1800, "the city price factor scales a price");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ signing

    @GameTest(template = "empty")
    public static void rentingAndBuyingAreDifferentThings(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:rent"), flatTerms("test:own")))) {
            ServerPlayer a = f.person(helper, "uc_pa", "farmer", 3000);
            Residence rent = f.home("test:rent", 0), own = f.home("test:own", 1);
            // renting: the deposit and the first hour, no purchase price
            PropertyService.rent(a, id(), "test:rent", false, false);
            check(rent.tenure == Tenure.RENT && rent.ownerType == OwnerType.PLAYER && rent.owner.equals(a.getUUID()), "rented, held by the renter");
            check(f.wallet(a) == 3000 - 80 - 40, "rent costs the deposit and the first hour: " + f.wallet(a));
            check(rent.charge == 40 && rent.deposit == 80 && rent.prepaid == 1 && rent.paidPrice == 0 && rent.arrears == 0 && !rent.legacy, "tenancy terms are fixed at signing");
            // buying: the purchase price, upkeep afterwards, no deposit, nothing prepaid
            a.moveTo(f.base.getX() + 22.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.buy(a, id(), "test:own", false, false);
            check(own.tenure == Tenure.OWN && own.paidPrice == 2000 && own.deposit == 0 && own.charge == 8 && own.prepaid == 0, "bought: price paid, upkeep set, no deposit");
            check(f.wallet(a) == 3000 - 120 - 2000, "buying costs the listed price: " + f.wallet(a));
            // a rent-only home cannot be bought; a home with no listing cannot be rented
            rejected(() -> PropertyService.buy(a, id(), "test:rent", false, false), "buying a home somebody holds");
            f.rig.wallet.balances.put(a.getUUID(), 5000d);
            PropertyCatalog.INSTANCE.install(f.rules, listings(motelTerms("test:motel")), f.previousUpgrades);
            Residence motel = f.home("test:motel", 2);
            a.moveTo(f.base.getX() + 42.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            rejected(() -> PropertyService.buy(a, id(), "test:motel", false, false), "buying a motel room");
            check(motel.ownerType == OwnerType.NONE && f.wallet(a) == 5000, "the refused purchase moved nothing");
            // fresh ids are required for every request
            UUID once = id();
            PropertyCatalog.INSTANCE.install(f.rules, listings(flatTerms("test:rent"), flatTerms("test:own"), flatTerms("test:third")), f.previousUpgrades);
            Residence third = f.home("test:third", 3);
            a.moveTo(f.base.getX() + 62.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.rent(a, once, "test:third", false, false);
            rejected(() -> PropertyService.pay(a, once, "test:third", 1, false), "replaying a transaction id");
            check(third.tenure == Tenure.RENT, "setup");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theMotelWelcomeIsFreeOncePerPerson(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(motelTerms("test:m1"), motelTerms("test:m2")))) {
            ServerPlayer a = f.person(helper, "uc_wa", "vintner", 0);
            Residence m1 = f.home("test:m1", 0), m2 = f.home("test:m2", 1);
            // a person with no credits at all can take the room: the first two hours are the city's
            PropertyService.rent(a, id(), "test:m1", false, false);
            check(m1.tenure == Tenure.RENT && m1.prepaid == f.rules.welcomeMotelCycles() && m1.deposit == 0 && f.wallet(a) == 0, "the welcome stay costs nothing: prepaid " + m1.prepaid);
            check(f.l.welcomeUsed.contains(a.getUUID()), "the welcome is recorded");
            // moving out and taking a second room is not free again: no farming the welcome
            PropertyService.moveOut(a, "test:m1");
            a.moveTo(f.base.getX() + 22.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            rejected(() -> PropertyService.rent(a, id(), "test:m2", false, false), "a second welcome stay with an empty wallet");
            f.rig.wallet.balances.put(a.getUUID(), 100d);
            PropertyService.rent(a, id(), "test:m2", false, false);
            check(m2.prepaid == 1 && f.wallet(a) == 90, "the second room costs its first hour: " + f.wallet(a) + " prepaid " + m2.prepaid);
            // another person's welcome is their own
            ServerPlayer b = f.person(helper, "uc_wb", "smith", 0);
            check(f.l.welcomeAvailable(b.getUUID(), motelTerms("x"), f.rules), "a second person has their own welcome");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theCitysPriceFactorScalesTheListedPrice(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:dear")))) {
            ServerPlayer a = f.person(helper, "uc_fa", "smith", 5000);
            BlockPos at = f.base;
            Residence dear = f.l.registerResidence("test:dear", "Dear flat", "flat", OVERWORLD, "test", at, at.offset(4, 3, 4), 50, -1, 115);
            PropertyService.rent(a, id(), "test:dear", false, false);
            check(dear.charge == 46 && dear.deposit == 92 && f.wallet(a) == 5000 - 92 - 46, "rent and deposit follow the factor: charge " + dear.charge + " deposit " + dear.deposit);
            rejected(() -> f.l.registerResidence("test:badfactor", "x", "flat", OVERWORLD, "test", f.base.offset(40, 0, 0), f.base.offset(44, 3, 4), 50, -1, 300), "an absurd price factor");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the clock

    @GameTest(template = "empty")
    public static void theRentClockRunsOnlyWhileAResidentIsOnline(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:clock")))) {
            ServerPlayer a = f.person(helper, "uc_ca", "farmer", 1000), stranger = f.person(helper, "uc_cs", "smith", 0);
            Residence r = f.home("test:clock", 0);
            PropertyService.rent(a, id(), "test:clock", false, false);
            // nobody online, only a stranger online, and a huge amount of server time: nothing moves
            f.l.advance(Set.of(), 72000L * 50, f.rules);
            f.l.advance(Set.of(stranger.getUUID()), 72000L * 50, f.rules);
            check(r.progress == 0 && r.arrears == 0 && r.due == 0 && r.prepaid == 1, "rent accrued with its resident offline: progress " + r.progress + " arrears " + r.arrears);
            // the resident online: it counts, and only by the time that passed
            f.tick(6000, a);
            check(r.progress == 6000, "the clock did not count the resident's time: " + r.progress);
            check(PropertyRules.minutesToNextCycle(r.progress, f.rules) == 55, "minutes to the next payment: " + PropertyRules.minutesToNextCycle(r.progress, f.rules));
            // a restart keeps the progress that was written
            f.l.flushProgress();
            f.restart();
            check(f.rig.ledger.residence("test:clock").progress == 6000, "the clock did not survive a restart");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoResidentsOnlineAdvanceOneHomesClockOnce(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:pair")))) {
            ServerPlayer founder = f.person(helper, "uc_pf", "farmer", 1000), mate = f.person(helper, "uc_pm", "gunsmith", 1000);
            Crew crew = f.l.create(founder.getUUID(), "uc_pf", "Pair Crew");
            f.l.accept(f.l.invite(founder.getUUID(), mate.getUUID(), "uc_pm", "uc_pf").id(), mate.getUUID(), "uc_pm");
            crew.bank = 500;
            Residence r = f.home("test:pair", 0);
            PropertyService.rent(founder, id(), "test:pair", true, true);   // paid from the bank
            check(r.ownerType == OwnerType.CREW && crew.bank == 500 - 120, "the Crew bank paid the deposit and the first hour: " + crew.bank);
            f.tick(3000, founder);
            long alone = r.progress;
            r.progress = 0;
            f.tick(3000, founder, mate);
            check(alone == 3000 && r.progress == 3000, "two residents online advanced the clock twice: alone " + alone + " together " + r.progress);
            // the bill belongs to the property: one charge, however many people live there
            f.tick(f.rules.cycleTicks(), founder, mate);
            check(r.prepaid == 0 && r.arrears == 0, "the first hour was prepaid: prepaid " + r.prepaid + " arrears " + r.arrears);
            f.tick(f.rules.cycleTicks(), founder, mate);
            check(r.arrears == 1, "one cycle, one bill: arrears " + r.arrears);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unpaidRentEscalatesButNeverTakesAPersonsThings(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:late")))) {
            ServerPlayer a = f.person(helper, "uc_la", "technician", 1000), outsider = f.person(helper, "uc_lo", "smith", 0);
            Residence r = f.home("test:late", 0);
            PropertyService.rent(a, id(), "test:late", false, false);
            BlockPos chest = f.in(0, 1, 0, 1);
            f.level.setBlockAndUpdate(f.in(0, 0, -1, 0), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) f.level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
            String dim = OVERWORLD;
            long cycle = f.rules.cycleTicks();
            f.tick(cycle, a);                                  // the prepaid hour is used
            check(r.standing(f.rules) == Standing.CURRENT && r.prepaid == 0, "the first hour was prepaid");
            f.tick(cycle, a);
            check(r.arrears == 1 && r.standing(f.rules) == Standing.OVERDUE, "one unpaid hour is overdue (grace): " + r.standing(f.rules));
            check(ResidenceGuard.rights(f.l, r, a.getUUID()).equals(ResidenceGuard.Rights.ALL), "during the grace period everything still works");
            f.tick(cycle, a);
            check(r.arrears == 2 && r.standing(f.rules) == Standing.OVERDUE, "two unpaid hours are still within the grace period");
            f.tick(cycle, a);
            check(r.arrears == 3 && r.standing(f.rules) == Standing.NOTICE, "the third is the final notice: " + r.standing(f.rules));
            check(ResidenceGuard.rights(f.l, r, a.getUUID()).build(), "the final notice still lets the tenant build");
            f.tick(cycle, a);
            check(r.arrears == 4 && r.standing(f.rules) == Standing.LAPSED, "four unpaid hours lapse the tenancy: " + r.standing(f.rules));
            // a lapsed home keeps the door and the containers, loses the comforts, and nothing is emptied, re-let or deleted
            ResidenceGuard.Rights lapsed = ResidenceGuard.rights(f.l, r, a.getUUID());
            check(lapsed.access() && lapsed.storage() && !lapsed.build() && !lapsed.workstations(), "a lapsed home is for visiting and taking things out: " + lapsed);
            check(ResidenceGuard.allowed(f.l, r, a.getUUID(), dim, chest, ResidenceGuard.Act.CONTAINER), "the tenant can still open their chest");
            check(!ResidenceGuard.allowed(f.l, r, a.getUUID(), dim, f.in(0, 2, 0, 2), ResidenceGuard.Act.PLACE), "a lapsed tenant cannot build");
            check(!ResidenceGuard.rights(f.l, r, outsider.getUUID()).access(), "lapsing does not open the door to anybody else");
            check(r.owned() && r.owner.equals(a.getUUID()) && r.tenure == Tenure.RENT, "a lapsed home is still the tenant's");
            check(((ChestBlockEntity) f.level.getBlockEntity(chest)).getItem(0).getCount() == 7, "the tenant's things were touched");
            // time passing with nobody home changes nothing more; time with the tenant home does not deepen the debt without bound
            f.l.advance(Set.of(), cycle * 20, f.rules);
            check(r.arrears == 4, "arrears grew while the tenant was offline");
            // recovery: paying what is owed restores everything
            PropertyService.pay(a, id(), "test:late", 4, false);
            check(r.arrears == 0 && r.standing(f.rules) == Standing.CURRENT && f.wallet(a) == 1000 - 120 - 160, "paying the arrears restored the home: wallet " + f.wallet(a));
            check(ResidenceGuard.rights(f.l, r, a.getUUID()).equals(ResidenceGuard.Rights.ALL), "the comforts came back");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void payingAppliesToWhatIsOwedFirstAndIsCapped(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:pay")))) {
            ServerPlayer a = f.person(helper, "uc_ya", "smith", 5000);
            Residence r = f.home("test:pay", 0);
            PropertyService.rent(a, id(), "test:pay", false, false);
            f.tick(f.rules.cycleTicks() * 3, a);   // prepaid hour, then two unpaid
            check(r.arrears == 2 && r.prepaid == 0, "setup: arrears " + r.arrears);
            double before = f.wallet(a);
            PropertyService.pay(a, id(), "test:pay", 5, false);
            check(r.arrears == 0 && r.prepaid == 3 && f.wallet(a) == before - 200, "five hours pay two owed and prepay three: arrears " + r.arrears + " prepaid " + r.prepaid);
            // the cap: no more than the prepayment limit ahead
            rejected(() -> PropertyService.pay(a, id(), "test:pay", 4, false), "prepaying beyond the cap");
            PropertyService.pay(a, id(), "test:pay", 3, false);
            check(r.prepaid == f.rules.prepayMaxCycles(), "prepaid up to the cap: " + r.prepaid);
            rejected(() -> PropertyService.pay(a, id(), "test:pay", 1, false), "one hour over the cap");
            // not enough credits: refused, nothing moves
            f.rig.wallet.balances.put(a.getUUID(), 10d);
            f.tick(f.rules.cycleTicks() * 2, a);
            long prepaid = r.prepaid;
            rejected(() -> PropertyService.pay(a, id(), "test:pay", 1, false), "paying with too few credits");
            check(f.wallet(a) == 10 && r.prepaid == prepaid && f.l.pendingFor(a.getUUID()) == null, "a refused payment moved credits or left a record");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void automaticPaymentFromTheCrewBankIsAtomicAndStopsWhenTheBankIsShort(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:auto")))) {
            ServerPlayer founder = f.person(helper, "uc_ba", "farmer", 0), member = f.person(helper, "uc_bm", "smith", 0);
            Crew crew = f.l.create(founder.getUUID(), "uc_ba", "Auto Crew");
            f.l.accept(f.l.invite(founder.getUUID(), member.getUUID(), "uc_bm", "uc_ba").id(), member.getUUID(), "uc_bm");
            crew.bank = 300;
            Residence r = f.home("test:auto", 0);
            PropertyService.rent(founder, id(), "test:auto", true, true);
            long afterRent = crew.bank;
            rejected(() -> f.l.setAutopay(member.getUUID(), "m", "test:auto", true), "a member who may not manage property switching automatic payment on");
            f.l.setAutopay(founder.getUUID(), "f", "test:auto", true);
            f.tick(f.rules.cycleTicks(), founder);           // prepaid hour
            check(crew.bank == afterRent, "the prepaid hour took bank credits");
            f.tick(f.rules.cycleTicks(), founder, member);
            check(crew.bank == afterRent - 40 && r.arrears == 0, "the next hour was paid from the bank, once: bank " + crew.bank);
            // the bank runs short: the hour becomes arrears, never a debt of a member's
            crew.bank = 10;
            f.tick(f.rules.cycleTicks(), founder, member);
            check(crew.bank == 10 && r.arrears == 1, "a short bank paid part of a bill or took a member's money: bank " + crew.bank + " arrears " + r.arrears);
            check(f.wallet(founder) == 0 && f.wallet(member) == 0, "automatic payment from the bank touched a wallet");
            // paying by hand from a wallet puts it right, and a member without the right to spend the bank cannot use it
            f.rig.wallet.balances.put(member.getUUID(), 100d);
            member.moveTo(f.base.getX() + 2.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            rejected(() -> PropertyService.pay(member, id(), "test:auto", 1, true), "a plain member spending the bank on rent");
            PropertyService.pay(member, id(), "test:auto", 1, false);
            check(r.arrears == 0 && f.wallet(member) == 60 && crew.bank == 10, "a member paid the Crew's rent from their own wallet");
            // switching it off
            f.l.setAutopay(founder.getUUID(), "f", "test:auto", false);
            f.tick(f.rules.cycleTicks(), founder);
            check(r.arrears == 1, "with automatic payment off the hour is simply unpaid");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void automaticPaymentFromAWalletUsesTheMoneyProtocol(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:wallet")))) {
            ServerPlayer a = f.person(helper, "uc_wa2", "gunsmith", 400);
            Residence r = f.home("test:wallet", 0);
            PropertyService.rent(a, id(), "test:wallet", false, false);
            PropertyService.setAutopay(a, "test:wallet", true);
            f.tick(f.rules.cycleTicks(), a);                  // prepaid
            double before = f.wallet(a);
            f.tick(f.rules.cycleTicks(), a);
            check(f.wallet(a) == before - 40 && r.arrears == 0 && r.due == 0 && f.l.pendingFor(a.getUUID()) == null, "an automatic payment from a wallet: wallet " + f.wallet(a));
            // too few credits: unpaid, nothing taken
            f.rig.wallet.balances.put(a.getUUID(), 15d);
            f.tick(f.rules.cycleTicks(), a);
            check(f.wallet(a) == 15 && r.arrears == 1 && r.due == 0, "an automatic payment with too few credits took money or lost the hour: arrears " + r.arrears);
            // a crash in the middle of an automatic payment is decided by the wallet, like every transfer
            f.rig.wallet.balances.put(a.getUUID(), 300d);
            CrewService.crashAt = "after_wallet";
            try { f.tick(f.rules.cycleTicks(), a); throw new AssertionError("the simulated crash did not happen"); } catch (CrewService.SimulatedCrash expected) { /* the process died with the wallet charged */ }
            CrewService.crashAt = "";
            f.restart();
            CrewService.recover(f.rig.server, f.rig.wallet);
            Residence after = f.l.residence("test:wallet");
            check(f.rig.ledger.pendingFor(a.getUUID()) == null, "recovery left the payment undecided");
            check(f.wallet(a) == 260 && after.arrears == 1 && after.due == 0, "the charged wallet must mean a paid hour: wallet " + f.wallet(a) + " arrears " + after.arrears + " due " + after.due);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ leaving

    @GameTest(template = "empty")
    public static void movingOutReturnsTheDepositLessWhatIsOwedAndNeverThrowsThingsAway(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:out")))) {
            ServerPlayer a = f.person(helper, "uc_ma", "smith", 1000);
            Residence r = f.home("test:out", 0);
            PropertyService.rent(a, id(), "test:out", false, false);
            BlockPos chest = f.in(0, 1, 0, 1);
            f.level.setBlockAndUpdate(f.in(0, 0, -1, 0), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) f.level.getBlockEntity(chest)).setItem(3, new ItemStack(Items.IRON_INGOT, 12));
            check(PropertyService.storedThings(f.rig.server, r) == 1, "a full chest is stored things");
            rejected(() -> PropertyService.moveOut(a, "test:out"), "moving out with a chest still holding items");
            check(r.owned() && r.tenure == Tenure.RENT && f.wallet(a) == 880, "a refused move-out changed the tenancy or the wallet");
            ((ChestBlockEntity) f.level.getBlockEntity(chest)).clearContent();
            // two unpaid hours come out of the deposit; the rest returns, through the owed payout that already survives a crash
            f.tick(f.rules.cycleTicks() * 3, a);
            check(r.arrears == 2, "setup: arrears " + r.arrears);
            int back = PropertyService.moveOut(a, "test:out");
            check(back == 0 + (80 - 80) || back == 0, "two unpaid hours (80) used up the deposit (80): returned " + back);
            check(!r.owned() && r.tenure == Tenure.NONE && r.charge == 0 && r.arrears == 0 && r.claims.isEmpty(), "the home is free and clean again");
            check(f.wallet(a) == 880, "no debt followed the tenant out: wallet " + f.wallet(a));
            // another move-out with credits to come back
            PropertyService.rent(a, id(), "test:out", false, false);
            check(f.wallet(a) == 880 - 120, "setup");
            int refund = PropertyService.moveOut(a, "test:out");
            check(refund == 80 + 40 && f.wallet(a) == 880 && f.l.owedTo(a.getUUID()) == 0, "the deposit and the unused prepaid hour came back into the wallet: " + f.wallet(a) + " refund " + refund);
            // a move-out is a request the tenant makes: somebody else cannot do it
            PropertyService.rent(a, id(), "test:out", false, false);
            ServerPlayer other = f.person(helper, "uc_mo", "farmer", 0);
            rejected(() -> PropertyService.moveOut(other, "test:out"), "somebody else moving a tenant out");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sellingBackReturnsAShareOfThePriceAndNeverMoreThanWasPaid(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:sell")))) {
            ServerPlayer a = f.person(helper, "uc_sa", "technician", 5000);
            Residence r = f.home("test:sell", 0);
            PropertyService.buy(a, id(), "test:sell", false, false);
            check(f.wallet(a) == 3000 && r.paidPrice == 2000, "bought");
            f.tick(f.rules.cycleTicks() * 2, a);                // upkeep of 8 an hour is unpaid for two hours
            check(r.arrears == 2, "upkeep falls due like rent: " + r.arrears);
            int back = PropertyService.sellBack(a, "test:sell");
            check(back == 1200 - 16, "sixty percent of 2000, less 16 owed: " + back);
            check(f.wallet(a) == 3000 + back && !r.owned(), "the sale reached the wallet and freed the home: " + f.wallet(a));
            check(f.wallet(a) < 5000, "buying and selling back is a loss, never a profit");
            // a renter cannot sell, an owner cannot move out
            Residence r2 = f.home("test:sell2", 1);
            PropertyCatalog.INSTANCE.install(f.rules, listings(flatTerms("test:sell"), flatTerms("test:sell2")), f.previousUpgrades);
            a.moveTo(f.base.getX() + 22.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.rent(a, id(), "test:sell2", false, false);
            rejected(() -> PropertyService.sellBack(a, "test:sell2"), "selling a home that is only rented");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ Crews and ownership

    @GameTest(template = "empty")
    public static void crewHomesFollowTheCrewsRulesAndNothingIsConfiscated(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:crewrent"), flatTerms("test:mine"), flatTerms("test:other")))) {
            ServerPlayer founder = f.person(helper, "uc_ka", "farmer", 3000), member = f.person(helper, "uc_km", "smith", 3000), manager = f.person(helper, "uc_kg", "gunsmith", 3000);
            Crew crew = f.l.create(founder.getUUID(), "uc_ka", "Rent Crew");
            for (ServerPlayer p : List.of(member, manager)) f.l.accept(f.l.invite(founder.getUUID(), p.getUUID(), p.getGameProfile().getName(), "uc_ka").id(), p.getUUID(), p.getGameProfile().getName());
            f.l.setRole(founder.getUUID(), "uc_ka", manager.getUUID(), Role.MANAGER);
            crew.bank = 1000;
            Residence crewHome = f.home("test:crewrent", 0), mine = f.home("test:mine", 1), other = f.home("test:other", 2);
            rejected(() -> PropertyService.rent(member, id(), "test:crewrent", true, true), "a plain member renting a home for the Crew");
            PropertyService.rent(manager, id(), "test:crewrent", true, true);
            check(crewHome.ownerType == OwnerType.CREW && crewHome.owner.equals(crew.id) && crew.bank == 880, "the Crew holds it, paid from the bank: " + crew.bank);
            // a member's own tenancy stays the member's: nobody else can end it, spend the bank on it, or hand it over
            member.moveTo(f.base.getX() + 22.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.rent(member, id(), "test:mine", false, false);
            check(mine.ownerType == OwnerType.PLAYER && mine.owner.equals(member.getUUID()), "a member's own tenancy");
            rejected(() -> f.l.moveOut(founder.getUUID(), "uc_ka", "test:mine"), "the founder ending a member's personal tenancy");
            rejected(() -> f.l.payFromBank(id(), founder.getUUID(), "f", "test:mine", 1, f.rules), "a member's personal home paid from the Crew bank");
            rejected(() -> f.l.transferToCrew(manager.getUUID(), "g", "test:mine"), "handing over somebody else's home");
            rejected(() -> f.l.transferToCrew(member.getUUID(), "uc_km", "test:mine"), "a plain member handing a home to the Crew without the right to manage property");
            check(mine.ownerType == OwnerType.PLAYER, "a refused transfer changed the owner");
            // a manager hands over one of their own, once nothing is owed; a debt cannot be passed on
            manager.moveTo(f.base.getX() + 42.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.rent(manager, id(), "test:other", false, false);
            f.tick(f.rules.cycleTicks() * 2, manager);
            check(other.arrears == 1, "setup: arrears " + other.arrears);
            rejected(() -> f.l.transferToCrew(manager.getUUID(), "g", "test:other"), "handing on a home with an unpaid hour");
            PropertyService.pay(manager, id(), "test:other", 1, false);
            f.l.transferToCrew(manager.getUUID(), "uc_kg", "test:other");
            check(other.ownerType == OwnerType.CREW && other.owner.equals(crew.id) && !other.autopay && other.deposit == 80, "the home joined the Crew with its deposit and a fresh payment setting");
            // only the founder takes a Crew home back
            rejected(() -> f.l.transferToFounder(manager.getUUID(), "g", "test:other"), "a manager taking a Crew home for themselves");
            f.l.transferToFounder(founder.getUUID(), "uc_ka", "test:other");
            check(other.ownerType == OwnerType.PLAYER && other.owner.equals(founder.getUUID()), "the founder took the home back");
            // leaving the Crew changes nothing about a personal home and the Crew's rent is still one bill
            f.l.leave(member.getUUID(), "uc_km");
            check(mine.ownerType == OwnerType.PLAYER && mine.owner.equals(member.getUUID()), "leaving a Crew took a personal home");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disbandingMovesATenancyToTheFounderWithItsDebtsAndDeposit(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:gone")))) {
            ServerPlayer founder = f.person(helper, "uc_da", "farmer", 0), member = f.person(helper, "uc_dm", "smith", 0);
            Crew crew = f.l.create(founder.getUUID(), "uc_da", "Gone Crew");
            f.l.accept(f.l.invite(founder.getUUID(), member.getUUID(), "uc_dm", "uc_da").id(), member.getUUID(), "uc_dm");
            crew.bank = 600;
            Residence r = f.home("test:gone", 0);
            PropertyService.rent(founder, id(), "test:gone", true, true);
            f.l.setAutopay(founder.getUUID(), "f", "test:gone", true);
            f.tick(f.rules.cycleTicks() * 2, founder);
            int deposit = r.deposit, prepaidAndPaid = r.prepaid;
            f.l.disband(founder.getUUID(), "uc_da");
            check(r.ownerType == OwnerType.PLAYER && r.owner.equals(founder.getUUID()) && r.tenure == Tenure.RENT, "the tenancy passed to the founder, still a rental");
            check(r.deposit == deposit && !r.autopay && r.charge == 40, "the deposit travelled with the home; the Crew bank is gone so automatic payment is off");
            check(!ResidenceGuard.rights(f.l, r, member.getUUID()).access(), "a former member has no key");
            check(f.l.owedTo(founder.getUUID()) + f.l.owedTo(member.getUUID()) > 0, "the bank was paid out to the members");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ old saves

    @GameTest(template = "empty")
    public static void homesFromASchemaOneSaveAreMigratedWithoutAnyNewCharge(GameTestHelper helper) throws Exception {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        Path path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/schema1-" + UUID.randomUUID() + ".dat");
        Files.createDirectories(path.getParent());
        UUID owner = id(), crewFounder = id();
        // the layout exactly as the October 18 build wrote it: schema 1, residences with an owner and no tenancy fields
        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", 1);
        ListTag residences = new ListTag();
        CompoundTag home = new CompoundTag();
        home.putString("id", "row_flats:flat_1"); home.putString("title", "Row Flat 1"); home.putString("kind", "flat"); home.putString("dimension", OVERWORLD); home.putString("district", "row_flats");
        home.putLong("min", new BlockPos(10, 70, 10).asLong()); home.putLong("max", new BlockPos(14, 73, 16).asLong()); home.putInt("price", 120); home.putInt("starter_price", 60);
        home.putString("owner_type", "PLAYER"); home.putUUID("owner", owner); home.putLong("since", 1234L);
        home.put("guests", new ListTag()); home.put("claims", new ListTag());
        residences.add(home);
        CompoundTag free = new CompoundTag();
        free.putString("id", "row_flats:flat_2"); free.putString("title", "Row Flat 2"); free.putString("kind", "flat"); free.putString("dimension", OVERWORLD); free.putString("district", "row_flats");
        free.putLong("min", new BlockPos(20, 70, 10).asLong()); free.putLong("max", new BlockPos(24, 73, 16).asLong()); free.putInt("price", 120); free.putInt("starter_price", 60);
        free.putString("owner_type", "NONE"); free.putLong("since", 0); free.put("guests", new ListTag()); free.put("claims", new ListTag());
        residences.add(free);
        root.put("residences", residences);
        root.put("crews", new ListTag()); root.put("invites", new ListTag()); root.put("owed", new ListTag()); root.put("starter_used", new ListTag());
        root.put("seen_tx", new ListTag()); root.put("pending", new ListTag()); root.put("holds", new ListTag());
        NbtIo.write(root, path.toFile());
        try {
            CrewLedger migrated = new CrewLedger(path, () -> 5_000L);
            Residence r = migrated.residence("row_flats:flat_1");
            check(r.owned() && r.owner.equals(owner) && r.tenure == Tenure.OWN && r.legacy && r.charge == 0 && !r.billed(), "an owner from a schema-1 save stays the owner with no charge");
            check(r.paidPrice == 60 && PropertyRules.sellBack(r.paidPrice, 0, Rules.DEFAULT) == 36, "selling back is bounded by the cheaper registered price");
            check(migrated.residence("row_flats:flat_2").tenure == Tenure.NONE, "an unheld home stays unheld");
            // time passing never bills a legacy owner
            migrated.advance(Set.of(owner), Rules.DEFAULT.cycleTicks() * 100, Rules.DEFAULT);
            check(r.arrears == 0 && r.due == 0, "a migrated owner was billed");
            check(ResidenceGuard.rights(migrated, r, owner).equals(ResidenceGuard.Rights.ALL), "a migrated owner kept every right");
            // saving writes the new schema and reading it back loses nothing
            migrated.persist();
            CompoundTag written = NbtIo.read(path.toFile());
            check(written.getInt("schema_version") == CrewLedger.SCHEMA && CrewLedger.SCHEMA == 2, "the ledger now writes schema 2");
            CrewLedger again = migrated.reloaded();
            Residence reread = again.residence("row_flats:flat_1");
            check(reread.legacy && reread.tenure == Tenure.OWN && reread.charge == 0 && reread.owner.equals(owner), "the migrated home survived a second read");
            // a file written by a newer build is refused loudly rather than half-read
            CompoundTag future = written.copy();
            future.putInt("schema_version", 9);
            NbtIo.write(future, path.toFile());
            try { new CrewLedger(path, () -> 0L); throw new AssertionError("a newer schema was read"); } catch (IllegalStateException expected) { /* refused */ }
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aTenancyAndItsClockSurviveARestart(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:keep")))) {
            ServerPlayer a = f.person(helper, "uc_ra", "farmer", 2000);
            Residence r = f.home("test:keep", 0);
            PropertyService.rent(a, id(), "test:keep", false, false);
            PropertyService.setAutopay(a, "test:keep", true);
            f.tick(f.rules.cycleTicks() + 4321, a);
            f.l.flushProgress();
            long progress = r.progress;
            int prepaid = r.prepaid, arrears = r.arrears;
            f.restart();
            Residence back = f.rig.ledger.residence("test:keep");
            check(back.tenure == Tenure.RENT && back.charge == 40 && back.deposit == 80 && back.autopay && back.progress == progress && back.prepaid == prepaid && back.arrears == arrears,
                "a tenancy changed across a restart: " + back.progress + " vs " + progress);
            check(!back.receipts.isEmpty() && back.signedBy.equals(a.getUUID()), "receipts and the signature were lost");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWalletRentCrashRecoversToRentedOrFreeNeverBoth(GameTestHelper helper) throws Exception {
        for (String stage : List.of("after_begin", "after_wallet", "after_save")) {
            for (boolean walletSurvived : new boolean[]{true, false}) {
                try (Fix f = new Fix(helper, listings(flatTerms("test:crash")))) {
                    ServerPlayer a = f.person(helper, "uc_xa", "gunsmith", 500);
                    Residence r = f.home("test:crash", 0);
                    CrewService.crashAt = stage;
                    try { PropertyService.rent(a, id(), "test:crash", false, false); throw new AssertionError("no crash at " + stage); } catch (CrewService.SimulatedCrash expected) { /* died */ }
                    CrewService.crashAt = "";
                    if (!walletSurvived && !stage.equals("after_save")) f.rig.wallet.balances.put(a.getUUID(), 500d);   // the wallet change never reached the disk
                    f.restart();
                    CrewService.recover(f.rig.server, f.rig.wallet);
                    Residence after = f.l.residence("test:crash");
                    boolean charged = f.wallet(a) == 380;
                    check(f.rig.ledger.pendingFor(a.getUUID()) == null && after.reservedBy == null, stage + "/" + walletSurvived + ": the rental was left undecided");
                    check(charged == (after.tenure == Tenure.RENT && after.owned()), stage + "/" + walletSurvived + ": credits and tenancy disagree (wallet " + f.wallet(a) + ", tenure " + after.tenure + ")");
                    check(charged || f.wallet(a) == 500, stage + ": the wallet holds neither value: " + f.wallet(a));
                }
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ what a home allows

    private static BlockHitResult click(BlockPos against, Direction face) { return new BlockHitResult(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, against, false); }

    private static net.minecraft.world.InteractionResult use(ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItemOn(player, player.serverLevel(), stack, InteractionHand.MAIN_HAND, click(against, face));
    }

    private static PlayerInteractEvent.RightClickBlock usedWith(ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, against, click(against, face));
        MinecraftForge.EVENT_BUS.post(event);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return event;
    }

    /** A tradesperson's skill is a real discount on a real purchase: the wallet, the receipt and the ledger's accounting all see the price actually paid. */
    @GameTest(template = "empty")
    public static void aTradeSkillCheapensTheUpgradesOfAHomeYouOwn(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:trade"), flatTerms("test:plain")))) {
            ServerPlayer smith = f.person(helper, "uc_ta", "smith", 9000), plain = f.person(helper, "uc_tb", "smith", 9000);
            CitizenState.get(f.rig.server).unlockSkill(smith.getUUID(), "smith.fit_out_trade");
            check(SkillService.upgradeDiscountPercent(smith) == 15 && SkillService.upgradeDiscountPercent(plain) == 0, "setup: 15% and nothing");
            Residence mine = f.home("test:trade", 0), theirs = f.home("test:plain", 1);
            smith.moveTo(f.base.getX() + 2.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.buy(smith, id(), "test:trade", false, false);
            plain.moveTo(f.base.getX() + 20 + 2.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.buy(plain, id(), "test:plain", false, false);
            long smithBefore = (long) f.wallet(smith), plainBefore = (long) f.wallet(plain), sunkBefore = f.l.sunk;
            List<PropertyRules.Upgrade> list = List.copyOf(PropertyCatalog.INSTANCE.upgrades().values());
            long listPrice = 0, tradePrice = 0;
            for (int i = 0; i < 3; i++) {
                PropertyService.upgrade(smith, id(), "test:trade", i, false);
                PropertyService.upgrade(plain, id(), "test:plain", i, false);
                listPrice += list.get(i).cost();
                tradePrice += list.get(i).discounted(15).cost();
            }
            check(plainBefore - (long) f.wallet(plain) == listPrice, "the unskilled owner pays the list price: " + (plainBefore - (long) f.wallet(plain)) + " of " + listPrice);
            check(smithBefore - (long) f.wallet(smith) == tradePrice && tradePrice < listPrice, "the skilled owner pays the discounted price: " + (smithBefore - (long) f.wallet(smith)) + " of " + tradePrice);
            check(list.get(0).discounted(15).cost() == list.get(0).cost() - list.get(0).cost() * 15 / 100, "the discount is rounded down, so the price rounds up");
            check(mine.receipts.stream().filter(e -> e.kind().equals("UPGRADE")).mapToInt(e -> (int) e.amount()).sum() == tradePrice, "the receipts record what was paid, not the list price");
            check(f.l.sunk - sunkBefore == tradePrice + listPrice, "the ledger's sink accounting counts the prices paid: " + (f.l.sunk - sunkBefore));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storageAndFacilitiesAreCapabilitiesTheGuardEnforces(GameTestHelper helper) throws Exception {
        try (Fix f = new Fix(helper, listings(flatTerms("test:cap")))) {
            ServerPlayer a = f.person(helper, "uc_ga", "smith", 9000);
            Residence r = f.home("test:cap", 0);
            PropertyService.buy(a, id(), "test:cap", false, false);
            ServerLevel level = f.level;
            for (int x = -1; x <= 5; x++) for (int z = -1; z <= 5; z++) level.setBlockAndUpdate(f.in(0, x, -1, z), Blocks.STONE.defaultBlockState());
            for (int x = 0; x <= 4; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 4; z++) level.setBlockAndUpdate(f.in(0, x, y, z), Blocks.AIR.defaultBlockState());
            // containers: the listing allows two; the third is refused, the first two stand (with a control that proves placing works)
            use(a, new ItemStack(Items.CHEST), f.in(0, 0, -1, 0), Direction.UP);
            check(level.getBlockState(f.in(0, 0, 0, 0)).is(Blocks.CHEST), "control: the first container could not be placed");
            use(a, new ItemStack(Items.BARREL), f.in(0, 2, -1, 0), Direction.UP);
            check(level.getBlockState(f.in(0, 2, 0, 0)).is(Blocks.BARREL), "control: the second container could not be placed");
            use(a, new ItemStack(Items.CHEST), f.in(0, 4, -1, 0), Direction.UP);
            check(!level.getBlockState(f.in(0, 4, 0, 0)).is(Blocks.CHEST), "a third container was placed in a home that holds two");
            // a heavy workstation needs the workshop facility
            use(a, new ItemStack(Items.ANVIL), f.in(0, 0, -1, 2), Direction.UP);
            check(!level.getBlockState(f.in(0, 0, 0, 2)).is(Blocks.ANVIL), "an anvil was placed in a home with no workshop space");
            use(a, new ItemStack(Items.FURNACE), f.in(0, 2, -1, 2), Direction.UP);
            check(level.getBlockState(f.in(0, 2, 0, 2)).is(Blocks.FURNACE), "control: a furnace could not be placed in a home that lists one");
            // water needs plumbing; lava is never allowed
            check(usedWith(a, new ItemStack(Items.WATER_BUCKET), f.in(0, 1, -1, 3), Direction.UP).isCanceled(), "water was poured in a home with no plumbing");
            check(usedWith(a, new ItemStack(Items.LAVA_BUCKET), f.in(0, 1, -1, 3), Direction.UP).isCanceled(), "lava was poured in a home");
            check(!usedWith(a, new ItemStack(Items.BUCKET), f.in(0, 1, -1, 3), Direction.UP).isCanceled(), "control: an empty bucket was stopped");
            // the upgrades: each is a purchase that changes exactly what the guard allows
            long before = (long) f.wallet(a);
            PropertyService.upgrade(a, id(), "test:cap", 0, false);   // secure_storage: three more containers, one more private
            PropertyService.upgrade(a, id(), "test:cap", 1, false);   // workshop_bench
            PropertyService.upgrade(a, id(), "test:cap", 2, false);   // plumbing
            check(before - (long) f.wallet(a) == 260 + 600 + 320, "the upgrades cost their listed prices: " + (before - (long) f.wallet(a)));
            check(r.upgrades.size() == 3 && PropertyService.maxContainers(r) == 5 && PropertyService.privateClaims(r) == 2, "the upgrades changed the limits: " + PropertyService.maxContainers(r));
            use(a, new ItemStack(Items.CHEST), f.in(0, 4, -1, 0), Direction.UP);
            check(level.getBlockState(f.in(0, 4, 0, 0)).is(Blocks.CHEST), "the storage upgrade did not allow a third container");
            use(a, new ItemStack(Items.ANVIL), f.in(0, 0, -1, 2), Direction.UP);
            check(level.getBlockState(f.in(0, 0, 0, 2)).is(Blocks.ANVIL), "the workshop upgrade did not allow an anvil");
            check(!usedWith(a, new ItemStack(Items.WATER_BUCKET), f.in(0, 1, -1, 3), Direction.UP).isCanceled(), "the plumbing upgrade did not allow water");
            check(usedWith(a, new ItemStack(Items.LAVA_BUCKET), f.in(0, 1, -1, 3), Direction.UP).isCanceled(), "lava became allowed");
            rejected(() -> PropertyService.upgrade(a, id(), "test:cap", 0, false), "fitting the same upgrade twice");
            // a rented home cannot be improved (rent is for living in a home as it is)
            Residence rented = f.home("test:cap2", 1);
            PropertyCatalog.INSTANCE.install(f.rules, listings(flatTerms("test:cap"), flatTerms("test:cap2")), f.previousUpgrades);
            a.moveTo(f.base.getX() + 22.5, f.base.getY() + 1, f.base.getZ() + 2.5);
            PropertyService.rent(a, id(), "test:cap2", false, false);
            rejected(() -> PropertyService.upgrade(a, id(), "test:cap2", 0, false), "improving a rented home");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ conservation

    private static String describe(Fix f, List<ServerPlayer> people) {
        StringBuilder out = new StringBuilder("sunk " + f.l.sunk + " homes " + f.l.heldInHomes() + " held " + f.l.totalHeld() + " flight " + f.l.totalInFlight() + " wallets");
        for (ServerPlayer p : people) out.append(' ').append(f.rig.wallet.balances.getOrDefault(p.getUUID(), 0d).longValue());
        for (Residence r : f.l.allResidences()) if (r.owned()) out.append(" | ").append(r.id).append(' ').append(r.tenure).append(" paid ").append(r.paidPrice).append(" dep ").append(r.deposit).append(" pre ").append(r.prepaid).append('/').append(r.freeCycles).append(" chg ").append(r.charge).append(" arr ").append(r.arrears).append(" due ").append(r.due);
        return out.toString();
    }

    private static long held(Fix f, List<ServerPlayer> people) {
        long total = f.l.sunk + f.l.totalHeld() + f.l.totalInFlight() + f.l.heldInHomes();
        for (ServerPlayer p : people) total += (long) (double) f.rig.wallet.balances.getOrDefault(p.getUUID(), 0d);
        return total;
    }

    /**
     * Random play with simulated crashes: rent, buy, pay, switch automatic payment, move out, sell back, upgrade and let time pass, for a Crew and a loner. After
     * every step the credits in wallets, banks, owed payouts, in-flight transfers, held deposits and prepaid hours, plus the ledger's own count of credits that left
     * circulation (rent consumed, prices, upgrades, the share of a deposit the city kept), equal what the households started with: no sequence creates or destroys a credit.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void randomisedHousingPlayConservesEveryCredit(GameTestHelper helper) throws Exception {
        int allCrashes = 0, allSigned = 0, allPaid = 0;
        for (long seed : new long[]{11, 29, 20261020}) {
            try (Fix f = new Fix(helper, listings(flatTerms("t:a"), flatTerms("t:b"), houseTerms("t:c"), motelTerms("t:d")))) {
                Random random = new Random(seed);
                ServerPlayer p1 = f.person(helper, "uc_r1", "farmer", 4000), p2 = f.person(helper, "uc_r2", "smith", 4000), loner = f.person(helper, "uc_r3", "gunsmith", 4000);
                Crew crew = f.l.create(p1.getUUID(), "uc_r1", "Random Crew");
                f.l.accept(f.l.invite(p1.getUUID(), p2.getUUID(), "uc_r2", "uc_r1").id(), p2.getUUID(), "uc_r2");
                crew.bank = 1500;
                List<ServerPlayer> people = List.of(p1, p2, loner);
                String[] ids = {"t:a", "t:b", "t:c", "t:d"};
                for (int i = 0; i < ids.length; i++) f.home(ids[i], i);
                long start = held(f, people);
                int crashes = 0, signed = 0, paid = 0;
                String[] stages = {"after_begin", "after_wallet", "after_save"};
                for (int step = 0; step < 240; step++) {
                    ServerPlayer who = people.get(random.nextInt(people.size()));
                    String home = ids[random.nextInt(ids.length)];
                    int slot = java.util.Arrays.asList(ids).indexOf(home);
                    who.moveTo(f.base.getX() + slot * 20 + 2.5, f.base.getY() + 1, f.base.getZ() + 2.5);
                    boolean crash = random.nextInt(4) == 0;
                    boolean crewMember = f.l.crewOf(who.getUUID()) != null;
                    boolean asCrew = crewMember && random.nextBoolean(), fromBank = crewMember && random.nextBoolean();
                    String op = "?";
                    String before = describe(f, people);
                    try {
                        if (crash) CrewService.crashAt = stages[random.nextInt(stages.length)];
                        switch (random.nextInt(11)) {
                            case 0 -> { op = "rent crew=" + asCrew + " bank=" + (asCrew && fromBank); PropertyService.rent(who, id(), home, asCrew, asCrew && fromBank); signed++; }
                            case 1 -> { op = "buy crew=" + asCrew + " bank=" + (asCrew && fromBank); PropertyService.buy(who, id(), home, asCrew, asCrew && fromBank); signed++; }
                            case 2, 3 -> { op = "pay bank=" + fromBank; PropertyService.pay(who, id(), home, 1 + random.nextInt(3), fromBank); paid++; }
                            case 4 -> { op = "upgrade bank=" + fromBank; PropertyService.upgrade(who, id(), home, random.nextInt(3), fromBank); }
                            case 5 -> { op = "move out"; PropertyService.moveOut(who, home); }
                            case 6 -> { op = "sell back"; PropertyService.sellBack(who, home); }
                            case 7 -> { op = "autopay"; f.l.setAutopay(who.getUUID(), who.getGameProfile().getName(), home, random.nextBoolean()); }
                            case 8 -> { op = "rent again"; PropertyService.rent(who, id(), home, false, false); signed++; }
                            case 9 -> { op = "move out then rent"; try { PropertyService.moveOut(who, home); } catch (IllegalStateException notMine) { /* fine */ } PropertyService.rent(who, id(), home, false, false); signed++; }
                            default -> {
                                op = "clock";
                                Set<UUID> online = new java.util.HashSet<>(random.nextBoolean() ? List.of(p1.getUUID(), p2.getUUID(), loner.getUUID()) : List.of(loner.getUUID()));
                                PropertyService.tickClock(f.rig.server, 6000L * (1 + random.nextInt(14)), online);
                            }
                        }
                    } catch (IllegalStateException refused) {
                        // a refusal is fine; it must not have moved money
                    } catch (CrewService.SimulatedCrash died) {
                        crashes++;
                        // the process died: whatever the wallet held in memory may or may not have reached the disk
                        Double saved = f.rig.wallet.balances.get(who.getUUID());
                        CrewLedger.Pending pending = f.rig.ledger.reloaded().pendingFor(who.getUUID());
                        if (pending != null && random.nextBoolean() && !died.getMessage().endsWith("after_save")) saved = pending.before();
                        f.rig.wallet.balances.put(who.getUUID(), saved);
                        f.restart();
                        CrewService.crashAt = "";
                        CrewService.recover(f.rig.server, f.rig.wallet);
                    } finally {
                        CrewService.crashAt = "";
                    }
                    check(held(f, people) == start, "seed " + seed + " step " + step + ": credits created or destroyed (" + held(f, people) + " vs " + start + ") by '" + op + "' of " + who.getGameProfile().getName() + " on " + home
                        + "\n before: " + before + "\n after:  " + describe(f, people));
                    if (step % 40 == 39) { f.restart(); check(held(f, people) == start, "seed " + seed + ": a restart changed the total"); }
                }
                allCrashes += crashes; allSigned += signed; allPaid += paid;
            }
        }
        // the conservation checks above are the test; this only proves the random walk was not a no-op (most random moves are refused by design)
        check(allCrashes >= 6 && allSigned >= 10 && allPaid >= 6, "the random runs did not exercise crashes, signings and payments (crashes " + allCrashes + ", signed " + allSigned + ", paid " + allPaid + ")");
        helper.succeed();
    }
    // ------------------------------------------------------------------------------------------------------------ cost

    /**
     * The billing clock runs on the server thread every five seconds. A thousand tenants is far beyond a private two-player world, so the bound is generous; the point is to
     * see that nothing is quadratic and to print the figures ({@code [UC-PERF]}) for the documents. The slow step is the one where every home owes a cycle, because it writes the ledger.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theBillingClockStaysCheapWithManyHomes(GameTestHelper helper) throws Exception {
        final int homes = 1000;
        try (Fix f = new Fix(helper, listings(flatTerms("test:perf")))) {
            Set<UUID> online = new java.util.HashSet<>();
            for (int i = 0; i < homes; i++) {
                BlockPos at = f.base.offset(i * 6, 0, 0);
                Residence r = f.l.registerResidence("test:perf_" + i, "Perf " + i, "flat", OVERWORLD, "test", at, at.offset(4, 3, 4), 50, -1);
                UUID tenant = new UUID(7, i);
                r.tenure = Tenure.RENT; r.ownerType = OwnerType.PLAYER; r.owner = tenant; r.charge = 40; r.deposit = 80; r.prepaid = 3;
                if (i % 2 == 0) online.add(tenant);
            }
            long begin = System.nanoTime();
            final int steps = 200;
            for (int step = 0; step < steps; step++) f.l.advance(online, PropertyService.STEP_TICKS, f.rules);
            double perStepMs = (System.nanoTime() - begin) / 1e6 / steps;
            long heavy = System.nanoTime();
            List<CrewLedger.Event> events = f.l.advance(online, f.rules.cycleTicks(), f.rules);
            double heavyMs = (System.nanoTime() - heavy) / 1e6;
            System.out.println(String.format(java.util.Locale.ROOT, "[UC-PERF] property clock, %d homes (%d with a resident online): %.3f ms per five-second step; the step where every online home owes a cycle (%d events, ledger written): %.1f ms",
                homes, online.size(), perStepMs, events.size(), heavyMs));
            check(events.size() == online.size(), "every online home paid one hour from its prepaid hours: " + events.size());
            check(perStepMs < 5.0, "a clock step must be a few milliseconds at most: " + perStepMs);
            check(heavyMs < 750.0, "the step that writes the ledger must stay well under a second: " + heavyMs);
        }
        helper.succeed();
    }
}
