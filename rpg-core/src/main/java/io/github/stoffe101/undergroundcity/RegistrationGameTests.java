package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** Server-side rules of first-login registration (the screen itself is covered by real-client captures). */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RegistrationGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static FakePlayer fresh(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static int count(FakePlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) total += stack.getCount();
        return total;
    }

    @GameTest(template = "empty")
    public static void registrationCreatesOneCitizenAndGrantsTheWelcomePackOnce(GameTestHelper helper) {
        FakePlayer player = fresh(helper, "uc_register_a");
        check(!RegistrationService.registered(player), "A new player must start unregistered");
        check(RegistrationService.choose(player, "VALEWOOD", "Farmer") == RegistrationService.Outcome.REGISTERED, "Valid choices (any letter case) must register");
        var citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
        check(citizen.city.equals("valewood") && citizen.career.equals("farmer"), "Identity must be stored normalised");
        check(count(player, UndergroundCity.TERMINAL_ITEM.get()) == 1, "The welcome pack must include one Operations Terminal");
        check(count(player, Items.WHEAT_SEEDS) == 12 && count(player, Items.WOODEN_HOE) == 1, "The farmer kit must arrive");
        int before = count(player, Items.BREAD);
        check(RegistrationService.choose(player, "ironhaven", "smith") == RegistrationService.Outcome.ALREADY_REGISTERED, "A second registration must be refused");
        check(count(player, Items.BREAD) == before && count(player, UndergroundCity.TERMINAL_ITEM.get()) == 1, "A refused registration must not grant another pack");
        check(CitizenState.get(player.server).find(player.getUUID()).orElseThrow().city.equals("valewood"), "A refused registration must leave the identity unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidChoicesAreRefusedWithoutSideEffects(GameTestHelper helper) {
        FakePlayer player = fresh(helper, "uc_register_b");
        for (String[] bad : new String[][]{{"atlantis", "farmer"}, {"valewood", "wizard"}, {"", ""}, {null, "farmer"}, {"valewood", null}, {"valewood; op", "farmer"}}) {
            check(RegistrationService.choose(player, bad[0], bad[1]) == RegistrationService.Outcome.INVALID, "Must refuse " + bad[0] + "/" + bad[1]);
        }
        check(!RegistrationService.registered(player), "No record may be created by an invalid request");
        check(count(player, UndergroundCity.TERMINAL_ITEM.get()) == 0, "No pack may be granted by an invalid request");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyCareerHasAKitOfRealItems(GameTestHelper helper) {
        for (String career : ProgressionRules.CAREERS) {
            var kit = StarterKits.INSTANCE.kit(career);
            check(!kit.isEmpty() && kit.size() <= StarterKits.MAX_STACKS, career + " needs a kit of 1 to " + StarterKits.MAX_STACKS + " stacks");
            for (var entry : kit) {
                if (!entry.requiresMod().isEmpty() && !net.minecraftforge.fml.ModList.get().isLoaded(entry.requiresMod())) continue;
                var item = ForgeRegistries.ITEMS.getValue(entry.item());
                check(item != null && item != Items.AIR, career + " kit names an unknown item " + entry.item());
            }
            check(StarterKits.INSTANCE.stacks(career).get(0).is(UndergroundCity.TERMINAL_ITEM.get()), career + " pack must start with the terminal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theGunsmithKitCarriesTheBlueprintTheTableAndMaterialsWhenTaczIsInstalled(GameTestHelper helper) {
        var stacks = StarterKits.INSTANCE.stacks("gunsmith");
        boolean tacz = net.minecraftforge.fml.ModList.get().isLoaded("tacz"), blueprints = net.minecraftforge.fml.ModList.get().isLoaded("taczblueprints");
        boolean table = stacks.stream().anyMatch(s -> ForgeRegistries.ITEMS.getKey(s.getItem()).toString().equals("tacz:gun_smith_table"));
        check(table == tacz, "The gun smith table must be in the kit exactly when TaCZ is installed");
        var blueprint = stacks.stream().filter(s -> ForgeRegistries.ITEMS.getKey(s.getItem()).toString().equals("taczblueprints:blueprint_pistol")).findFirst();
        check(blueprint.isPresent() == blueprints, "The pistol blueprint must be in the kit exactly when TaCZ Blueprints is installed");
        if (blueprint.isPresent()) check("tacz:glock_17".equals(blueprint.get().getTag().getString("GunId")), "The starter blueprint must unlock the Glock 17");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFullInventoryDropsThePackInsteadOfLosingIt(GameTestHelper helper) {
        FakePlayer player = fresh(helper, "uc_register_c");
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) player.getInventory().items.set(slot, new ItemStack(Items.DIRT, 64));
        check(RegistrationService.choose(player, "ironhaven", "technician") == RegistrationService.Outcome.REGISTERED, "Registration must still succeed");
        var around = player.getBoundingBox().inflate(6);
        long dropped = player.level().getEntitiesOfClass(ItemEntity.class, around).stream().filter(e -> e.getItem().is(UndergroundCity.TERMINAL_ITEM.get())).count();
        check(dropped >= 1, "The terminal must drop at the player's feet when the inventory is full");
        helper.succeed();
    }
}
