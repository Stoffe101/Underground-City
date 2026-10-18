package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.ResidenceGuard.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.rejected;

/**
 * Identity (D-090). What Underground City keeps per person is keyed by the account UUID. On an authenticating server that UUID is the Mojang account and
 * cannot be claimed by another player; these tests prove the other half: that nothing in the Crew system ever looks at a NAME to decide who somebody is, so
 * a player who merely has the same name (or claims to have) gets none of the member's Crew, bank, homes, private containers, payouts or wallet.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdentityGameTests {
    private static final String OVERWORLD = "minecraft:overworld";

    private static ServerPlayer named(GameTestHelper helper, UUID uuid, String name, String career) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    @GameTest(template = "empty")
    public static void crewMembershipMoneyHomesAndPayoutsBelongToTheAccountNotTheName(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            CrewService.wallet = rig.previousWallet;   // the live Q Shop wallet: its capability data is per player entity
            CrewLedger l = rig.ledger;
            UUID aliceId = UUID.randomUUID(), bobId = UUID.randomUUID();
            ServerPlayer alice = named(helper, aliceId, "Alice", "farmer"), bob = named(helper, bobId, "Bob", "smith");
            // the impostors share a name with the member but not the account
            ServerPlayer fakeAlice = named(helper, UUID.randomUUID(), "Alice", "gunsmith"), fakeBob = named(helper, UUID.randomUUID(), "Bob", "technician");
            check(!fakeAlice.getUUID().equals(alice.getUUID()) && fakeAlice.getGameProfile().getName().equals(alice.getGameProfile().getName()), "setup: same name, different account");
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            Residence home = l.registerResidence("test:identity", "Identity flat", "flat", OVERWORLD, "test", base, base.offset(4, 3, 4), 10, -1);
            for (ServerPlayer p : List.of(alice, bob, fakeAlice, fakeBob)) p.moveTo(base.getX() + 2.5, base.getY() + 1, base.getZ() + 2.5);

            Crew crew = l.create(alice.getUUID(), "Alice", "Real Crew");
            QShopEconomy.applyBalance(alice, 300);
            CrewService.deposit(alice, id(), 120);
            QShopEconomy.applyBalance(alice, 180);
            CrewService.lease(alice, id(), "test:identity", true, false);
            check(home.owned() && crew.bank == 120, "setup: a funded Crew with a home, bank " + crew.bank);
            BlockPos chest = base.offset(1, 0, 1);
            l.claimContainer(alice.getUUID(), "test:identity", chest);
            UUID invitation = l.invite(alice.getUUID(), bob.getUUID(), "Bob", "Alice").id();
            l.owed.put(alice.getUUID(), 40L);                       // a payout owed to Alice
            long bank = crew.bank;
            double aliceWallet = QShopEconomy.balance(alice), fakeWallet = QShopEconomy.balance(fakeAlice);
            long aliceSeen = crew.members.get(alice.getUUID()).lastSeen;

            // The impostor with Alice's name: no Crew, no bank, no payout, no home, no private container.
            check(l.crewOf(fakeAlice.getUUID()) == null, "a player with a member's name was treated as a member");
            rejected(() -> CrewService.withdraw(fakeAlice, id(), 50), "withdrawing from a Crew by having a member's name");
            rejected(() -> CrewService.deposit(fakeAlice, id(), 5), "depositing into a Crew by having a member's name");
            rejected(() -> CrewService.claimOwed(fakeAlice, id()), "claiming a payout owed to somebody else by having their name");
            rejected(() -> CrewService.lease(fakeAlice, id(), "test:identity", true, false), "leasing for a Crew by having a member's name");
            rejected(() -> l.invite(fakeAlice.getUUID(), fakeBob.getUUID(), "Bob", "Alice"), "inviting people into a Crew by having a member's name");
            rejected(() -> l.setPermission(fakeAlice.getUUID(), "Alice", CrewRules.Role.MEMBER, CrewRules.Perm.WITHDRAW, true), "editing a Crew's rules by having the founder's name");
            rejected(() -> l.disband(fakeAlice.getUUID(), "Alice"), "disbanding a Crew by having the founder's name");
            check(!ResidenceGuard.rights(l, home, fakeAlice.getUUID()).access(), "the impostor could enter a Crew home");
            for (Act act : Act.values()) {
                check(!ResidenceGuard.allowed(l, home, fakeAlice.getUUID(), OVERWORLD, chest, act), "the impostor was allowed " + act + " on the founder's private container");
            }
            // The impostor with Bob's name cannot take Bob's invitation.
            rejected(() -> l.accept(invitation, fakeBob.getUUID(), "Bob"), "accepting an invitation addressed to another account");
            check(l.invitesFor(fakeBob.getUUID()).isEmpty() && l.invitesFor(bob.getUUID()).size() == 1, "invitations follow the account");
            // Logging in under the same name touches nothing of the member's record.
            l.touch(fakeAlice.getUUID(), "Alice");
            check(crew.members.get(alice.getUUID()).lastSeen == aliceSeen && crew.members.size() == 1 && l.crewOf(fakeAlice.getUUID()) == null, "a login under a member's name changed the member's record");
            check(crew.bank == bank && l.owedTo(alice.getUUID()) == 40 && QShopEconomy.balance(alice) == aliceWallet && QShopEconomy.balance(fakeAlice) == fakeWallet,
                "the impostor's attempts moved money: bank " + crew.bank + ", owed " + l.owedTo(alice.getUUID()));

            // The real member, under a NEW name (accounts can be renamed), is still the same member with everything intact.
            l.touch(aliceId, "Alicia");
            check(l.crewOf(aliceId) == crew && crew.roleOf(aliceId) == CrewRules.Role.FOUNDER && "Alicia".equals(crew.members.get(aliceId).name) && crew.bank == bank,
                "a renamed account lost its Crew, its role or the bank");
            check(ResidenceGuard.allowed(l, home, aliceId, OVERWORLD, chest, Act.CONTAINER), "a renamed account lost its private container");
            // And a name never decides anything in the reverse direction either: Bob accepts under his own account.
            l.accept(invitation, bob.getUUID(), "Bob");
            check(l.crewOf(bob.getUUID()) == crew && l.crewOf(fakeBob.getUUID()) == null, "the invited account could not join");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void offlineDerivedIdentitiesAreRecognisedAndNeverMigratedSilently(GameTestHelper helper) throws Exception {
        UUID dev = IdentityAudit.offlineUuid("Dev");
        check(dev.version() == 3 && IdentityAudit.looksOffline(dev, "Dev") && !IdentityAudit.looksOffline(dev, "Dev2") && !IdentityAudit.looksOffline(UUID.randomUUID(), "Dev"),
            "a name-derived (version 3) UUID is the offline identity; a random (version 4) account UUID never is");
        check(IdentityAudit.offlineUuid("Dev").equals(IdentityAudit.offlineUuid("Dev")) && !IdentityAudit.offlineUuid("Dev").equals(IdentityAudit.offlineUuid("dev")),
            "the offline UUID depends on the exact name, which is why anyone can claim it");
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            ServerPlayer offline = named(helper, dev, "Dev", "farmer"), real = named(helper, UUID.randomUUID(), "Realplayer", "smith");
            Crew crew = rig.ledger.create(offline.getUUID(), "Dev", "Offline Crew");
            rig.ledger.accept(rig.ledger.invite(offline.getUUID(), real.getUUID(), "Realplayer", "Dev").id(), real.getUUID(), "Realplayer");
            IdentityAudit.Report report = IdentityAudit.audit(helper.getLevel().getServer());
            check(report.offlineCrewMembers() == 1 && report.offlineNames().contains("Dev") && report.crewMembers() >= 2, "the audit did not find the offline-derived Crew member: " + report);
            check(report.authenticated() == helper.getLevel().getServer().usesAuthentication(), "the audit reports whether the server verifies accounts");
            // reading the report changed nothing: the offline-derived account is still exactly what it was
            check(rig.ledger.crewOf(dev) == crew && crew.roleOf(dev) == CrewRules.Role.FOUNDER && "Dev".equals(crew.members.get(dev).name), "the audit changed an identity");
        }
        helper.succeed();
    }
}
