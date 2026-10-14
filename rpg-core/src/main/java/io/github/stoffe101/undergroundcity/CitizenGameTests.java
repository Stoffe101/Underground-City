package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** In-engine GameTests. Runs in dedicated GameTestServer without human clients. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CitizenGameTests {
    @GameTest(template = "empty")
    public static void citizenPersistence(GameTestHelper helper) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CitizenState state = new CitizenState();
        check(state.register(first, "valewood", "farmer"), "First registration rejected");
        check(!state.register(first, "blackwater", "gunsmith"), "Duplicate registration accepted");
        check(state.register(second, "ironhaven", "technician"), "Second registration rejected");
        check(state.addCareerXp(first, 350) == 350, "Career XP mismatch");
        check(state.addGeneralXp(second, "trading", 1200) == 1200, "General XP mismatch");

        CompoundTag serialized = state.save(new CompoundTag());
        check(serialized.getInt("schema_version") == 1, "Schema version mismatch");
        CitizenState loaded = CitizenState.load(serialized);
        CitizenState.Citizen farmer = loaded.find(first).orElseThrow();
        CitizenState.Citizen technician = loaded.find(second).orElseThrow();
        check(farmer.city.equals("valewood") && farmer.career.equals("farmer"), "Identity corrupted");
        check(farmer.careerXp() == 350, "Career XP did not survive roundtrip");
        check(technician.generalXp.get("trading") == 1200, "General XP did not survive roundtrip");
        check(loaded.find(UUID.randomUUID()).isEmpty(), "Unregistered player unexpectedly present");
        helper.succeed();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
