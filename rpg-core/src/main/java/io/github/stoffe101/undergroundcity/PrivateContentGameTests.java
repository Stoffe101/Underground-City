package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * The private-content loader ({@link PrivateContent}, D-116) with synthetic folders made here: invented packs in temporary directories, never anybody's content. When a run is given a private folder
 * ({@code -PucPrivateContent=}) the last test also proves that what it holds reached the game's data (public CI gives it the synthetic fixture).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PrivateContentGameTests {
    private PrivateContentGameTests() {}

    private static final String GOOD = "{\"schema_version\":1,\"id\":\"%s\",\"title\":\"A pack\",\"provides\":{\"data\":true,\"assets\":false},\"private_only\":true,\"permission\":{\"holder\":\"%s\",\"summary\":\"%s\"}}";

    private static Path pack(Path root, String folder, String manifest, boolean mcmeta) throws IOException {
        Path dir = Files.createDirectories(root.resolve(folder));
        Files.writeString(dir.resolve(PrivateContent.MANIFEST), manifest);
        if (mcmeta) Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"x\"}}");
        Files.createDirectories(dir.resolve("data/underground_city/anything"));
        Files.writeString(dir.resolve("data/underground_city/anything/a.json"), "{}");
        return dir;
    }

    @GameTest(template = "empty", batch = "uc_private")
    public static void aFolderWithAValidManifestIsDiscoveredAndNothingElseIs(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("uc-private-");
        pack(root, "good", String.format(GOOD, "demo_pack", "somebody", "private use"), true);
        Files.createDirectories(root.resolve("not_a_pack"));   // no manifest: not a candidate, not an error
        PrivateContent.Scan scan = PrivateContent.discover(root);
        check(scan.packs().size() == 1 && scan.refused().isEmpty(), "a valid pack was not discovered cleanly: " + scan);
        PrivateContent.Pack found = scan.packs().get(0);
        check(found.id().equals("demo_pack") && found.data() && !found.assets() && found.holder().equals("somebody") && found.files() >= 3, "the pack was read wrongly: " + found);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_private")
    public static void aPackThatDoesNotSayWhoGavePermissionOrIsNotPrivateOnlyIsRefusedButItsNeighbourLoads(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("uc-private-");
        pack(root, "a_good", String.format(GOOD, "good", "somebody", "private use"), true);
        pack(root, "b_no_holder", String.format(GOOD, "b", " ", "private use"), true);
        pack(root, "c_no_summary", String.format(GOOD, "c", "somebody", ""), true);
        pack(root, "d_public", GOOD.replace("\"private_only\":true", "\"private_only\":false").formatted("d", "somebody", "x"), true);
        pack(root, "e_no_flag", "{\"schema_version\":1,\"id\":\"e\",\"permission\":{\"holder\":\"h\",\"summary\":\"s\"}}", true);
        pack(root, "f_bad_id", String.format(GOOD, "Bad Id", "somebody", "x"), true);
        pack(root, "g_no_mcmeta", String.format(GOOD, "g", "somebody", "x"), false);
        pack(root, "h_not_json", "this is not json", true);
        pack(root, "i_schema", String.format(GOOD, "i", "somebody", "x").replace("\"schema_version\":1", "\"schema_version\":2"), true);
        PrivateContent.Scan scan = PrivateContent.discover(root);
        check(scan.packs().size() == 1 && scan.packs().get(0).id().equals("good"), "exactly the good pack should load: " + scan.packs());
        check(scan.refused().size() == 8, "eight folders should be refused, with reasons: " + scan.refused());
        for (String line : scan.refused()) check(line.contains(": ") && line.length() > 12, "a refusal has no reason: " + line);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_private")
    public static void aFolderInsideACheckoutIsRefusedUnlessItIsThatCheckoutsIgnoredPrivateContentFolder(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("uc-private-");
        Path checkout = Files.createDirectories(root.resolve("checkout"));
        Files.createDirectories(checkout.resolve(".git"));
        pack(checkout.resolve("elsewhere"), "p", String.format(GOOD, "p", "somebody", "x"), true);
        pack(checkout.resolve("private-content"), "p", String.format(GOOD, "p", "somebody", "x"), true);
        check(PrivateContent.discover(checkout.resolve("elsewhere")).packs().isEmpty(), "a pack folder inside a checkout was loaded");
        check(!PrivateContent.discover(checkout.resolve("elsewhere")).refused().isEmpty(), "a refusal inside a checkout was not reported");
        check(PrivateContent.discover(checkout.resolve("private-content")).packs().size() == 1, "the checkout's ignored private-content folder was refused");
        check(PrivateContent.checkoutProblem(root.resolve("outside")) == null, "a folder outside every checkout was refused");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_private")
    public static void aFolderThatDoesNotExistGivesNoPacksAndOneReason(GameTestHelper helper) throws IOException {
        PrivateContent.Scan scan = PrivateContent.discover(Files.createTempDirectory("uc-private-").resolve("missing"));
        check(scan.packs().isEmpty() && scan.refused().size() == 1, "a missing folder: " + scan);
        helper.succeed();
    }

    /** With a private folder configured (public CI configures the synthetic fixture) what it holds must have reached the game's own data. */
    @GameTest(template = "empty", batch = "uc_private")
    public static void aConfiguredPrivatePackReachesTheGamesData(GameTestHelper helper) {
        Optional<Path> root = PrivateContent.configuredRoot();
        if (root.isEmpty()) { helper.succeed(); return; }   // the public game: nothing configured, nothing to prove
        PrivateContent.Scan scan = PrivateContent.scan();
        if (scan.packs().stream().anyMatch(p -> p.id().equals("uc_private_fixture"))) {
            check(CityBuildings.INSTANCE.securityClass("private_fixture_class") != null, "the synthetic pack was found but its data did not reach the game (building security class missing)");
        }
        helper.succeed();
    }
}
