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

    @GameTest(template = "empty", batch = "uc_legacy_notice")
    public static void theGuardRefusesByDefaultAndOnlyADeliberateOverrideBacksUpAndLoads(GameTestHelper helper) {
        check(LegacyBackpackNotice.verdict(false, false) == LegacyBackpackNotice.Verdict.LOAD && LegacyBackpackNotice.verdict(false, true) == LegacyBackpackNotice.Verdict.LOAD, "a world that never named the mod loads, override or not");
        check(LegacyBackpackNotice.verdict(true, false) == LegacyBackpackNotice.Verdict.REFUSE, "a world that names the retired mod is refused by default (fail closed)");
        check(LegacyBackpackNotice.verdict(true, true) == LegacyBackpackNotice.Verdict.BACK_UP_AND_LOAD, "a deliberate override backs up first");
        check(!LegacyBackpackNotice.overridden() || Boolean.getBoolean(LegacyBackpackNotice.OVERRIDE_PROPERTY) || "true".equalsIgnoreCase(System.getenv(LegacyBackpackNotice.OVERRIDE_ENVIRONMENT)), "the override is only ever the property or the environment variable");
        helper.succeed();
    }
}
