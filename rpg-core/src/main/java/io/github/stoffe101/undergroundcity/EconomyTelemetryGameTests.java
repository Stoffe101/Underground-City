package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The economy telemetry (twelfth pass): a line per event in the world folder, a pseudonym and never a name, the order's time from acceptance, and nothing written when it is off. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EconomyTelemetryGameTests {
    private EconomyTelemetryGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @GameTest(template = "empty")
    public static void anEventIsOneLineWithAPseudonymAndNoName(GameTestHelper helper) throws Exception {
        FakePlayer player = CrewGameTests.player(helper, "uc_tel_smith", "smith");
        Path file = player.server.getWorldPath(LevelResource.ROOT).resolve(EconomyTelemetry.FILE);
        boolean was = EconomyTelemetry.on;
        try {
            EconomyTelemetry.on = true;
            long before = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
            EconomyTelemetry.accepted(player, "test_order");
            EconomyTelemetry.record(player, "order", "order", "test_order", "legality", "legal", "credits", 120, "minutes", EconomyTelemetry.minutesSinceAccepted(player, "test_order"), "level_up", false);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            check(lines.size() == before + 2, "two events did not write two lines: " + (lines.size() - before));
            JsonObject order = JsonParser.parseString(lines.get(lines.size() - 1)).getAsJsonObject();
            check(order.get("kind").getAsString().equals("order") && order.get("credits").getAsInt() == 120 && order.get("career").getAsString().equals("smith"), "the order line is wrong: " + order);
            check(order.get("minutes").getAsDouble() >= 0, "the order's time from acceptance was not known: " + order);
            check(order.get("p").getAsString().equals(EconomyTelemetry.pseudonym(player.server, player.getUUID())) && order.get("p").getAsString().length() == 10, "the pseudonym is not the player's");
            check(lines.stream().skip(before).noneMatch(l -> l.contains("uc_tel_smith") || l.contains(player.getUUID().toString())), "a line names the player or holds their id");
            check(EconomyTelemetry.minutesSinceAccepted(player, "test_order") < 0, "an order's acceptance was used twice");
            EconomyTelemetry.on = false;
            EconomyTelemetry.record(player, "order", "credits", 1);
            check(Files.readAllLines(file, StandardCharsets.UTF_8).size() == lines.size(), "a line was written with the telemetry off");
        } finally {
            EconomyTelemetry.on = was;
        }
        helper.succeed();
    }
}
