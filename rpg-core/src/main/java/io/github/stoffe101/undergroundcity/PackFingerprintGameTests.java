package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/** The pack fingerprint's verdict ({@link PackFingerprint}, eleventh pass): a game built from another commit or with other mods is named; a match, or a launch without one, is not. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackFingerprintGameTests {
    private PackFingerprintGameTests() {}

    @GameTest(template = "empty", batch = "uc_pack_fingerprint")
    public static void aDifferentFingerprintIsNamedAndAMatchOrAMissingOneIsNot(GameTestHelper helper) {
        var differs = PackFingerprint.mismatch("a1b2c3d4e5f6", "0f9e8d7c6b5a");
        check(differs.isPresent() && differs.get().contains("0f9e8d7c6b5a in your game") && differs.get().contains("a1b2c3d4e5f6 on the server"), "both fingerprints are named, the player's first");
        check(PackFingerprint.mismatch("a1b2c3d4e5f6", "a1b2c3d4e5f6").isEmpty(), "the same pack is not warned about");
        check(PackFingerprint.mismatch("", "0f9e8d7c6b5a").isEmpty() && PackFingerprint.mismatch("a1b2c3d4e5f6", "").isEmpty() && PackFingerprint.mismatch(null, null).isEmpty(),
            "a production pack, which sets no fingerprint, is never warned");
        helper.succeed();
    }
}
