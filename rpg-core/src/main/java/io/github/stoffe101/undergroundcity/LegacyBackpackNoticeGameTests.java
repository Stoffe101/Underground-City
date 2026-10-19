package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/** The decision half of {@link LegacyBackpackNotice}: which missing registry names count. The server behaviour (Forge's event, the log, the data loss it warns about) is measured by scripts/run_backpack_migration_check.py. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LegacyBackpackNoticeGameTests {
    private LegacyBackpackNoticeGameTests() {}

    @GameTest(template = "empty", batch = "uc_legacy_notice")
    public static void onlyTheRetiredBackpackModsMissingNamesCount(GameTestHelper helper) {
        boolean legacyLoaded = net.minecraftforge.fml.ModList.get().isLoaded(LegacyBackpackNotice.LEGACY_MOD);
        check(LegacyBackpackNotice.concerns(ResourceLocation.fromNamespaceAndPath("travelersbackpack", "standard")) == !legacyLoaded, "a Traveler's Backpack name must count exactly when the mod is not installed");
        check(!LegacyBackpackNotice.concerns(ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", "backpack")), "a Sophisticated Backpacks name must not count");
        check(!LegacyBackpackNotice.concerns(ResourceLocation.withDefaultNamespace("stone")) && !LegacyBackpackNotice.concerns(null), "other names must not count");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_legacy_notice")
    public static void thisTestWorldHasNoLegacyBackpackNotice(GameTestHelper helper) {
        check(!LegacyBackpackNotice.detected(), "the test world is a fresh world and must not carry the legacy notice");
        helper.succeed();
    }
}
