package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

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

    @GameTest(template = "empty")
    public static void terminalAndOptionalQShopCommand(GameTestHelper helper) {
        check(new ResourceLocation(UndergroundCity.MOD_ID, "operations_terminal")
            .equals(ForgeRegistries.BLOCKS.getKey(UndergroundCity.TERMINAL.get())),
            "Original one-block operations PC missing from block registry");
        if (ModList.get().isLoaded("qshop")) {
            check(helper.getLevel().getServer().getCommands().getDispatcher()
                    .getRoot().getChild("qshop") != null,
                "QShop was loaded without registering its expected server command");
        }
        helper.succeed();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
