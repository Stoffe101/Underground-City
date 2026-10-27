package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The operator commands (D-191): the points an operator gives are kept apart from the points levels give and never take a skill away, and the commands themselves, run through the real command
 * tree, need permission level 2, change credits only through Q Shop's wallet, refuse what would go below nothing, and write every change to the audit record.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdminGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static CitizenState.Citizen of(MinecraftServer server, UUID id) { return CitizenState.get(server).find(id).orElseThrow(); }

    @GameTest(template = "empty")
    public static void operatorPointsAreKeptApartAndNeverTakeASkillAway(GameTestHelper helper) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_admin_points"));
        MinecraftServer server = player.server;
        CitizenState state = CitizenState.get(server);
        state.register(player.getUUID(), "valewood", "farmer");
        // a skill that needs more than level 1, unlocked at its level
        SkillNode node = SkillCatalog.INSTANCE.tree("farmer").stream().filter(n -> n.minLevel() > 1 && n.requires().isEmpty()).findFirst()
            .orElse(SkillCatalog.INSTANCE.tree("farmer").stream().filter(n -> n.minLevel() > 1).findFirst().orElseThrow(() -> new GameTestAssertException("no farmer skill above level 1")));
        state.setCareerXp(player.getUUID(), ProgressionRules.xpAtLevel(node.minLevel()));
        for (String required : node.requires()) state.unlockSkill(player.getUUID(), required);
        state.unlockSkill(player.getUUID(), node.id());
        CitizenState.Citizen c = of(server, player.getUUID());
        int earnedByLevel = SkillRules.pointsEarned(c.careerLevel());
        check(SkillService.pointsEarned(c) == earnedByLevel, "points earned are not the level's alone before any operator points");
        // operator points add to the earned points and nothing else: the level and its XP stay as they were
        int xp = c.careerXp();
        state.setBonusPoints(player.getUUID(), false, 3);
        check(SkillService.pointsEarned(of(server, player.getUUID())) == earnedByLevel + 3, "three operator points did not add three earned points");
        check(of(server, player.getUUID()).careerXp() == xp && of(server, player.getUUID()).careerLevel() == node.minLevel(), "operator points changed the level or the XP");
        // they are saved apart, and an older save without them loads with none
        CitizenState again = CitizenState.load(state.save(new CompoundTag()));
        check(of(again, player.getUUID()).careerBonus() == 3, "operator points did not survive a save and a load");
        CompoundTag old = state.save(new CompoundTag());
        old.getList("citizens", 10).forEach(t -> ((CompoundTag) t).remove("admin_points"));
        check(of(CitizenState.load(old), player.getUUID()).careerBonus() == 0, "an older save without operator points did not load with none");
        // lowering: below the unlocked skill's level is refused, at its level is allowed; points that would no longer cover what was spent are refused
        check(AdminCommands.lowering(of(server, player.getUUID()), false, node.minLevel() - 1, 0) != null, "a level below an unlocked skill's level was allowed");
        check(AdminCommands.lowering(of(server, player.getUUID()), false, node.minLevel(), 0) == null, "the unlocked skill's own level was refused");
        int spent = SkillService.pointsSpent(of(server, player.getUUID()));
        check(AdminCommands.lowering(of(server, player.getUUID()), false, node.minLevel(), spent - earnedByLevel - 1) != null, "operator points that leave spent points uncovered were allowed");
        check(of(server, player.getUUID()).skills().contains(node.id()), "the skill was taken away");
        helper.succeed();
    }

    private static CitizenState.Citizen of(CitizenState state, UUID id) { return state.find(id).orElseThrow(); }

    private static int run(MinecraftServer server, CommandSourceStack source, String command) throws CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command, source);
    }

    @GameTest(template = "empty", batch = "uc_admin_commands", timeoutTicks = 100)
    public static void theCommandsNeedAnOperatorUseTheWalletAndAreAudited(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        // the player is named as the source's own entity (@s), as a player running a command on themselves would be; a fake player is in no list of players
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_admin_cmd"));
        MinecraftServer server = player.server;
        try {
            CitizenState.get(server).register(player.getUUID(), "valewood", "farmer");
            String who = "@s";
            CommandSourceStack op = server.createCommandSourceStack().withEntity(player).withPermission(2).withSuppressedOutput();
            CommandSourceStack nobody = server.createCommandSourceStack().withEntity(player).withPermission(0).withSuppressedOutput();
            int audited = AdminCommands.Audit.get(server).last(AdminCommands.Audit.KEEP).size();
            QShopEconomy.applyBalance(player, 100);
            // a player who is not an operator does not reach the branch at all
            boolean refused = false;
            try { run(server, nobody, "uc admin credits " + who + " add 50"); } catch (CommandSyntaxException e) { refused = true; }
            check(refused && QShopEconomy.balance(player) == 100, "somebody without permission level 2 reached /uc admin");
            // credits through Q Shop's wallet: add, a refused removal below nothing, set; a zero is a syntax error
            check(run(server, op, "uc admin credits " + who + " add 150") == 1 && QShopEconomy.balance(player) == 250, "credits add did not reach the wallet");
            check(run(server, op, "uc admin credits " + who + " remove 100000") == 0 && QShopEconomy.balance(player) == 250, "a removal below nothing changed the wallet");
            check(run(server, op, "uc admin credits " + who + " set 75") == 1 && QShopEconomy.balance(player) == 75, "credits set did not reach the wallet");
            boolean zero = false;
            try { run(server, op, "uc admin credits " + who + " add 0"); } catch (CommandSyntaxException e) { zero = true; }
            check(zero && QShopEconomy.balance(player) == 75, "adding zero credits was not refused as invalid input");
            // the career: a level, operator points added and removed apart from the level's
            check(run(server, op, "uc admin career " + who + " level 4") == 1 && of(server, player.getUUID()).careerLevel() == 4, "career level 4 was not set");
            check(run(server, op, "uc admin career " + who + " points add 2") == 1 && run(server, op, "uc admin career " + who + " points remove 1") == 1, "career points add or remove failed");
            CitizenState.Citizen c = of(server, player.getUUID());
            check(c.careerBonus() == 1 && c.careerLevel() == 4 && SkillService.pointsEarned(c) == SkillRules.pointsEarned(4) + 1, "operator points are not +1 over level 4's: " + c.careerBonus());
            // the Thief track and a general skill; an unknown skill is refused
            check(run(server, op, "uc admin thief " + who + " level 3") == 1 && of(server, player.getUUID()).thiefLevel() == 3, "thief level 3 was not set");
            int trading = of(server, player.getUUID()).generalXp.getOrDefault("trading", 0);
            check(run(server, op, "uc admin general " + who + " trading xp add 500") == 1 && of(server, player.getUUID()).generalXp.getOrDefault("trading", 0) == trading + 500, "trading XP was not added");
            check(run(server, op, "uc admin general " + who + " juggling xp add 5") == 0, "an unknown general skill was accepted");
            // the audit record has every change (seven went through: add, set, the level, two point changes, the Thief level, the trading XP) and survives a save and a load
            int now = AdminCommands.Audit.get(server).last(AdminCommands.Audit.KEEP).size();
            check(now - audited == Math.min(7, AdminCommands.Audit.KEEP - audited), "the audit record has " + (now - audited) + " new changes, not 7");
            check(AdminCommands.Audit.load(AdminCommands.Audit.get(server).save(new CompoundTag())).last(AdminCommands.Audit.KEEP).size() == now, "the audit record did not survive a save and a load");
        } catch (CommandSyntaxException e) {
            throw new GameTestAssertException("a command did not parse: " + e.getMessage());
        }
        helper.succeed();
    }
}
