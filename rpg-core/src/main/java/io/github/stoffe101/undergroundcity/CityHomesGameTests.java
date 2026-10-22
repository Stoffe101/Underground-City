package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;

/**
 * Homes and premises in the furnished cities (D-127): the shipped templates, which units are to let (a fixed share, the same in every run), how a unit becomes an ordinary registered home with
 * its template's terms and an address, that a let unit has no household and no stash, and that a tenant of a flat has a key to the building's street door while a stranger does not.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CityHomesGameTests {
    private static final String FLATS = "underground_city:test_flats";

    private static BuildingSpots.Plan flats() {
        return new BuildingSpots.Plan(FLATS, "flats", "ironhaven", List.of(
            new BuildingSpots.Spot("door", 1, 1, 7, "west", "street door"), new BuildingSpots.Spot("bed", 3, 1, 3, "east", "flat 1 bed"), new BuildingSpots.Spot("bed", 3, 1, 12, "east", "flat 2 bed")),
            List.of(new BuildingSpots.Unit("flat_1", "Flat 1", 2, 1, 2, 13, 3, 5), new BuildingSpots.Unit("flat_2", "Flat 2", 2, 1, 10, 13, 3, 13)));
    }

    @GameTest(template = "empty")
    public static void theShippedTemplatesAreCoherentAndUnitIdsRoundTrip(GameTestHelper helper) {
        Map<String, PropertyCatalog.CityTemplate> templates = PropertyCatalog.INSTANCE.cityTemplates();
        check(templates.size() >= 7, "the city templates are not loaded: " + templates.keySet());
        for (PropertyCatalog.CityTemplate t : templates.values()) {
            check(t.sharePercent() > 0 && t.sharePercent() <= 50, t.building() + ": a city is mostly lived in; " + t.sharePercent() + "% to let");
            check(t.terms().canRent(), t.building() + " cannot be rented");
            check(t.terms().deposit() == t.terms().rent() * PropertyCatalog.INSTANCE.rules().depositCycles(), t.building() + ": the deposit is not the stated cycles of rent");
        }
        var b = new CityBuildings.Building("ironhaven", -12, 34, "underground_city:ih_flats", "", 3, 0, 70, null);
        String id = CityHomes.id(b, new BuildingSpots.Unit("flat_3", "Flat 3", 0, 0, 0, 1, 1, 1));
        check(id.equals("city:underground_city/ih_flats:-12_34:flat_3") && id.length() <= 64, "the unit id is " + id);
        check(CityHomes.typeOf(id).equals(Optional.of("underground_city:ih_flats")), "the type of " + id + " is " + CityHomes.typeOf(id));
        check(PropertyCatalog.INSTANCE.find(id).map(t -> t.id().equals(id) && t.category().equals("small_apartment")).orElse(false), "a city flat does not take its template's terms");
        check(CityHomes.typeOf("row_flats:flat_1").isEmpty(), "a district home is not a city unit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFixedShareIsToLetAndTheSameInEveryRun(GameTestHelper helper) {
        var previous = PropertyCatalog.INSTANCE.installCityTemplatesForTest(Map.of(FLATS, new PropertyCatalog.CityTemplate(FLATS, 34, PropertyGameTests.flatTerms("template"))));
        try {
            int let = 0, total = 0;
            for (int cx = 0; cx < 30; cx++) for (int cz = 0; cz < 30; cz++) {
                var b = new CityBuildings.Building("ironhaven", cx, cz, FLATS, "", 3, 0, 70, null);
                for (int unit = 0; unit < 2; unit++) {
                    boolean once = CityHomes.toLet(5908L, b, unit);
                    check(once == CityHomes.toLet(5908L, b, unit), "a unit changed between two calls");
                    if (once) let++;
                    total++;
                }
            }
            double share = let / (double) total;
            check(share > 0.30 && share < 0.38, "34% to let came out as " + share);
            var other = new CityBuildings.Building("ironhaven", 3, 3, "underground_city:no_template", "", 3, 0, 70, null);
            check(!CityHomes.toLet(5908L, other, 0), "a building with no template has a unit to let");
        } finally { PropertyCatalog.INSTANCE.installCityTemplatesForTest(previous); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aLetUnitBecomesAHomeWithNoHouseholdAndItsTenantHoldsAKey(GameTestHelper helper) throws Exception {
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        var building = new CityBuildings.Building("ironhaven", cx, cz, FLATS, "", 3, 0, origin.getY(), CityBuildings.INSTANCE.securityClass("flat_block"));
        var previousTemplates = PropertyCatalog.INSTANCE.installCityTemplatesForTest(Map.of(FLATS, new PropertyCatalog.CityTemplate(FLATS, 100, PropertyGameTests.flatTerms("template"))));
        BuildingSpots.INSTANCE.replaceForTest(Map.of(FLATS, flats()));
        CityBuildings.INSTANCE.overrideForTest((x, z) -> x == cx && z == cz ? Optional.of(building) : Optional.empty());
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of())) {
            check(CityHomes.ensureBuilding(helper.getLevel(), building) == 2, "both flats of a fully let building should be registered");
            check(CityHomes.ensureBuilding(helper.getLevel(), building) == 0, "a second look registered them again");
            String flat1 = CityHomes.id(building, flats().units().get(0));
            CrewLedger.Residence r = f.l.residence(flat1);
            check(r != null && r.min.equals(new BlockPos(cx * 16 + 2, origin.getY() + 1, cz * 16 + 2)) && r.max.equals(new BlockPos(cx * 16 + 13, origin.getY() + 3, cz * 16 + 5)), "flat 1's box is wrong: " + r);
            check(r.title.startsWith("Flat 1, ") && r.title.length() <= 40, "flat 1's address: " + r.title);
            check(r.title.equals(CityHomes.title(helper.getLevel().getSeed(), building, flats(), flats().units().get(0))), "the address changed between two calls");
            check(PropertyCatalog.INSTANCE.find(flat1).map(PropertyRules.Terms::canRent).orElse(false), "the flat cannot be rented");
            // a let unit has no household and no stash
            check(CityHomes.spotLet(helper.getLevel().getSeed(), building, flats(), flats().spots().get(1)), "flat 1's bed is not seen as in a let unit");
            check(!CityHomes.spotLet(helper.getLevel().getSeed(), building, flats(), flats().spots().get(0)), "the street door is in no unit");
            check(CityHomes.letAt(helper.getLevel(), building, new BlockPos(cx * 16 + 5, origin.getY() + 2, cz * 16 + 3)), "a point in flat 1 is not let");
            // a tenant has a key to the street door; a stranger does not
            ServerPlayer tenant = f.person(helper, "uc_ch_tenant", "smith", 500), stranger = f.person(helper, "uc_ch_stranger", "farmer", 500);
            tenant.moveTo(r.min.getX() + 1.5, r.min.getY(), r.min.getZ() + 1.5);
            check(!CityHomes.holdsKey(tenant, building), "a key before renting");
            PropertyService.rent(tenant, id(), flat1, false, false);
            check(f.l.residence(flat1).owned(), "the flat was not rented");
            check(CityHomes.holdsKey(tenant, building), "the tenant has no key to the street door");
            check(!CityHomes.holdsKey(stranger, building), "a stranger has a key");
        } finally {
            CityBuildings.INSTANCE.overrideForTest(null);
            BuildingSpots.INSTANCE.replaceForTest(Map.of());
            PropertyCatalog.INSTANCE.installCityTemplatesForTest(previousTemplates);
        }
        helper.succeed();
    }
}
