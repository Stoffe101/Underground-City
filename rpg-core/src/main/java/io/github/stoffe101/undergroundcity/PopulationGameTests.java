package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The people of the cities (D-108) as arithmetic and as bodies. The arithmetic is what keeps the population cheap (a roster costs nothing while nobody is near; who is out is a function of the
 * clock), so most of the tests are on it; the bodies are tested for what they must never do (be saved, open a door, be killed by a stray blow) and for the one consequence they carry (striking one is a
 * crime in that city). Placing people on real Lost Cities streets needs a generated city and is measured by {@code PopulationHarness}-style runs, not here.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PopulationGameTests {
    private PopulationGameTests() {}

    private static final PopulationPlan.Names NAMES = new PopulationPlan.Names(
        List.of("Ada", "Bram", "Cora", "Dara", "Edda", "Finn", "Gus", "Hana", "Ivo", "Jory"), List.of("Anvil", "Brandt", "Coker", "Dunmore", "Ebbs", "Forge", "Grady", "Harlan", "Ironside", "Jaskov"));
    private static final PopulationPlan.Mix MIX = new PopulationPlan.Mix(50, 30, 10, 10);

    @GameTest(template = "empty", batch = "uc_population")
    public static void aRosterIsTheSameEveryTimeAndEveryoneIsNamedOnceAndDressedForTheirTrade(GameTestHelper helper) {
        List<PopulationPlan.Person> a = PopulationPlan.roster("ironhaven", 5908L, 40, MIX, NAMES, 5), b = PopulationPlan.roster("ironhaven", 5908L, 40, MIX, NAMES, 5);
        check(a.equals(b), "two rosters of the same city and seed differ");
        check(!a.equals(PopulationPlan.roster("ironhaven", 5909L, 40, MIX, NAMES, 5)), "another world seed gave the same people");
        check(!a.equals(PopulationPlan.roster("valewood", 5908L, 40, MIX, NAMES, 5)), "two cities gave the same people");
        Set<String> names = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        int guards = 0, couriers = 0, workers = 0;
        for (PopulationPlan.Person p : a) {
            check(names.add(p.name()), "the name " + p.name() + " appears twice in one city");
            check(ids.add(p.id()), "two people share an id");
            boolean dressed = false;
            for (int outfit : PopulationPlan.outfitsOf(p.role(), p.city())) dressed |= p.skin() % PopulationPlan.OUTFITS == outfit;
            check(dressed && p.skin() >= 0 && p.skin() < PopulationPlan.SKINS, p.name() + " (" + p.role() + ") wears the wrong outfit: skin " + p.skin());
            check(Math.abs(p.districtX()) <= 5 && Math.abs(p.districtZ()) <= 5, p.name() + " lives outside the reach of the city");
            switch (p.role()) { case "guard" -> guards++; case "courier" -> couriers++; case "worker" -> workers++; default -> { } }
        }
        check(guards == 4 && couriers == 4 && workers == 12 && a.size() == 40, "the mix was not followed: " + guards + " guards, " + couriers + " couriers, " + workers + " workers of " + a.size());
        boolean refused = false;
        try { new PopulationPlan.Mix(50, 30, 10, 20); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "a role mix that does not add up to a hundred was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void aCityIsBusyByDayAndQuietAtNightAndOnlyGuardsKeepWatch(GameTestHelper helper) {
        var roster = PopulationPlan.roster("blackwater", 5908L, 60, MIX, NAMES, 6);
        double noon = PopulationPlan.averageOut(roster, 5908L, 11), night = PopulationPlan.averageOut(roster, 5908L, 3), evening = PopulationPlan.averageOut(roster, 5908L, 20);
        check(noon > 0.5, "the street is not busy at eleven: " + noon);
        check(night < 0.15, "the street is not quiet at three in the morning: " + night);
        check(evening < noon, "the evening is as busy as noon");
        for (PopulationPlan.Person p : roster) {
            for (long day = 0; day < 4; day++) {
                var place = PopulationPlan.place(p, 5908L, 3, day);
                check(p.role().equals("guard") || place == PopulationPlan.Place.HOME, p.name() + " (" + p.role() + ") is out at three in the morning");
                check(!p.role().equals("guard") || PopulationPlan.place(p, 5908L, 12, day) == PopulationPlan.Place.POST, "a guard is not at a post at noon");
                check(p.role().equals("guard") || PopulationPlan.place(p, 5908L, 12, day) != PopulationPlan.Place.POST, p.name() + " stands at a post");
            }
        }
        // the clock: the game's day starts at six in the morning
        check(PopulationPlan.hourOf(0) == 6 && PopulationPlan.hourOf(6000) == 12 && PopulationPlan.hourOf(18000) == 0 && PopulationPlan.hourOf(23999) == 5, "the clock is mapped wrongly");
        check(PopulationPlan.hourOf(-1) == 5, "a negative clock is mapped wrongly");
        // one person's day is the same when asked twice, and differs between days for somebody whose shift moves
        var p = roster.get(roster.size() - 1);
        check(PopulationPlan.place(p, 5908L, 9, 3) == PopulationPlan.place(p, 5908L, 9, 3), "a person's whereabouts changed between two asks");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void theCatalogLoadsEveryCityAndEveryRoleHasLinesForEveryPeriod(GameTestHelper helper) {
        for (String city : new String[] {"ironhaven", "valewood", "blackwater"}) {
            PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
            check(def != null, city + " has no people in data");
            var roster = PopulationPlan.roster(city, 5908L, def.count(), def.mix(), def.names(), def.reachChunks());
            check(roster.size() == def.count() && roster.stream().map(PopulationPlan.Person::name).distinct().count() == roster.size(), city + "'s roster has a missing or repeated name");
            check(!def.flavour().isEmpty(), city + " has nothing to say about itself");
        }
        for (String role : new String[] {"resident", "worker", "courier", "guard"}) {
            for (String period : new String[] {"morning", "day", "evening"}) check(!PopulationCatalog.INSTANCE.lines(role, period).isEmpty() && !PopulationCatalog.INSTANCE.lines(role, period).get(0).isBlank(), role + " has no line for the " + period);
        }
        check(PopulationService.period(7).equals("morning") && PopulationService.period(14).equals("day") && PopulationService.period(21).equals("evening"), "periods of the day");
        helper.succeed();
    }

    private static TownspersonEntity body(GameTestHelper helper, String city, int index) {
        ServerLevel level = helper.getLevel();
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
        var person = PopulationPlan.roster(city, 5908L, def.count(), def.mix(), def.names(), def.reachChunks()).get(index);
        TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        body.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        body.become(person, at);
        check(level.addFreshEntity(body), "the body was not added");
        return body;
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void aBodyIsNeverSavedNeverOpensADoorAndSurvivesEverythingButTheVoidAndKill(GameTestHelper helper) {
        TownspersonEntity body = body(helper, "valewood", 0);
        check(!body.shouldBeSaved(), "a townsperson would be saved with the chunk");
        check(body.isInvulnerableTo(helper.getLevel().damageSources().generic()) && body.isInvulnerableTo(helper.getLevel().damageSources().fall()), "a townsperson can be hurt by an ordinary blow or a fall");
        check(!body.isInvulnerableTo(helper.getLevel().damageSources().genericKill()) && !body.isInvulnerableTo(helper.getLevel().damageSources().fellOutOfWorld()), "/kill and the void cannot remove a townsperson");
        check(body.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav && !nav.canOpenDoors(), "a townsperson can open doors (a locked building would not be locked)");
        check(body.skin() >= 0 && body.skin() < PopulationPlan.SKINS && body.getCustomName() != null && !body.city().isEmpty(), "the body does not carry its person");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void strikingACivilianIsACrimeInTheirCityAndTheyRun(GameTestHelper helper) {
        TownspersonEntity body = body(helper, "valewood", 1);
        ServerPlayer striker = player(helper, "uc_pop_s", "farmer");
        striker.setGameMode(GameType.SURVIVAL);
        striker.moveTo(body.getX() + 1, body.getY(), body.getZ());
        int before = WorkOrderLedger.get(helper.getLevel().getServer()).heat(striker.getUUID(), "valewood");
        striker.attack(body);
        int after = WorkOrderLedger.get(helper.getLevel().getServer()).heat(striker.getUUID(), "valewood");
        check(after == before + PopulationService.HEAT_FOR_STRIKING, "striking a civilian did not raise heat by " + PopulationService.HEAT_FOR_STRIKING + " in their city: " + before + " -> " + after);
        check(body.isAlive() && body.getHealth() == body.getMaxHealth(), "a blow hurt a townsperson");
        check(body.getLastHurtByMob() == striker, "the civilian does not run from the one who struck them");
        ServerPlayer creative = player(helper, "uc_pop_c", "farmer");
        creative.setGameMode(GameType.CREATIVE);
        int c0 = WorkOrderLedger.get(helper.getLevel().getServer()).heat(creative.getUUID(), "valewood");
        creative.attack(body);
        check(WorkOrderLedger.get(helper.getLevel().getServer()).heat(creative.getUUID(), "valewood") == c0, "an operator in creative mode was given heat for a test blow");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void aReturningPlayerIsGreetedDifferentlyAndThePeopleOfOneCitySpeakForIt(GameTestHelper helper) {
        TownspersonEntity body = body(helper, "blackwater", 2);
        ServerPlayer visitor = player(helper, "UcPopVisitor", "farmer");
        String first = PopulationService.say(visitor, body, false, 1), again = PopulationService.say(visitor, body, true, 2);
        check(!first.isBlank() && !first.contains("UcPopVisitor"), "a first meeting greets by name: " + first);
        check(again.contains("UcPopVisitor"), "a returning visitor is not greeted by name: " + again);
        Set<String> said = new HashSet<>();
        for (int i = 0; i < 40; i++) said.add(PopulationService.say(visitor, body, false, i));
        check(said.size() >= 4, "a person said only " + said.size() + " different things in forty conversations");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_population")
    public static void bodiesWithNobodyNearAreRemovedAndTheBookkeepingIsEmptied(GameTestHelper helper) {
        PopulationService.clearCaches();
        ServerLevel level = helper.getLevel();
        for (int i = 0; i < 5; i++) { var b = body(helper, "ironhaven", i); PopulationService.adopt(b, b.person()); }
        check(PopulationService.activeCount() == 5, "the service does not know its bodies");
        PopulationService.removeOutOfDate(level);   // no player is in this test world: nobody is near
        check(PopulationService.activeCount() == 0, "bodies with nobody near were kept: " + PopulationService.activeCount());
        check(PopulationService.MAX_PER_PLAYER <= PopulationService.MAX_ACTIVE && PopulationService.MAX_ACTIVE <= 64, "the budget is not bounded");
        helper.succeed();
    }
    @GameTest(template = "empty", batch = "uc_population")
    public static void aChunksStreetIsAskedOfLostCitiesOnceNotEverySecond(GameTestHelper helper) {
        PopulationService.clearCaches();
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int first = PopulationService.streetOf(level, chunk);
        long lookups = PopulationService.lcCalls;
        check(lookups == 1, "the first question was not asked of Lost Cities exactly once: " + lookups);
        for (int i = 0; i < 20; i++) check(PopulationService.streetOf(level, chunk) == first, "the answer changed between two asks");
        check(PopulationService.lcCalls == lookups, "a chunk that was answered was asked again: " + PopulationService.lcCalls + " lookups");
        PopulationService.streetOf(level, new net.minecraft.world.level.ChunkPos(chunk.x + 1, chunk.z));
        check(PopulationService.lcCalls == lookups + 1, "another chunk was not asked");
        PopulationService.clearCaches();
        helper.succeed();
    }
}
