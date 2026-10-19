package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.Kind;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Crew rules, the Crew bank protocol and its recovery, finance modes, and the ledger's storage discipline. Everything here runs on the
 * dedicated test server against a scratch ledger file (never the world's own) and, for the transfer protocol, against an in-memory wallet so
 * that every crash point can be reached; the live Q Shop wallet is exercised by {@link #realQShopTransfers}.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrewGameTests {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void rejected(Runnable action, String why) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Unsafe action accepted: " + why);
    }

    /** A scratch ledger on disk with a clock the test moves by hand. */
    static final class Rig implements AutoCloseable {
        final MinecraftServer server;
        final Path path;
        final long[] now = {1_700_000_000_000L};
        CrewLedger ledger;
        CrewLedger displaced;
        final CrewService.MemoryWallet wallet = new CrewService.MemoryWallet();
        CrewService.Wallet previousWallet;

        Rig(GameTestHelper helper) {
            server = helper.getLevel().getServer();
            path = server.getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/crew-" + UUID.randomUUID() + ".dat");
            ledger = new CrewLedger(path, () -> now[0]);
            displaced = CrewLedger.swap(server, ledger);
            previousWallet = CrewService.wallet;
            CrewService.wallet = wallet;
            CrewService.crashAt = "";
        }

        /** What a restart does: read the file again. */
        void restart() { ledger = ledger.reloaded(); CrewLedger.swap(server, ledger); }

        @Override public void close() throws Exception {
            CrewService.wallet = previousWallet;
            CrewService.crashAt = "";
            CrewLedger.swap(server, displaced);
            Files.deleteIfExists(path);
        }
    }

    static FakePlayer player(GameTestHelper helper, String name, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    static UUID id() { return UUID.randomUUID(); }

    // ------------------------------------------------------------------------------------------------------------ membership

    @GameTest(template = "empty")
    public static void crewNamesAndInvitations(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            CrewLedger l = rig.ledger;
            UUID a = id(), b = id(), c = id(), d = id();
            rejected(() -> l.create(a, "A", "ab"), "name too short");
            rejected(() -> l.create(a, "A", "x".repeat(25)), "name too long");
            rejected(() -> l.create(a, "A", "Bad<Name>"), "illegal characters");
            rejected(() -> l.create(a, "A", "123 456"), "no letter");
            Crew crew = l.create(a, "A", "  Night   Owls  ");
            check(crew.name.equals("Night Owls"), "name was not cleaned: '" + crew.name + "'");
            rejected(() -> l.create(a, "A", "Other Crew"), "a founder starting a second Crew");
            rejected(() -> l.create(b, "B", "night owls"), "a duplicate name differing in case");
            check(l.crewOf(a) == crew && l.crewOf(b) == null, "membership lookup");

            // invitations
            rejected(() -> l.invite(b, c, "C", "B"), "an outsider inviting");
            CrewLedger.Invite toB = l.invite(a, b, "B", "A");
            rejected(() -> l.invite(a, b, "B", "A"), "a duplicate invitation");
            rejected(() -> l.invite(a, a, "A", "A"), "inviting yourself");
            rejected(() -> l.accept(toB.id(), c, "C"), "somebody else accepting");
            rig.now[0] += CrewRules.INVITE_TTL_MILLIS + 1;
            rejected(() -> l.accept(toB.id(), b, "B"), "an expired invitation");
            check(l.invitesFor(b).isEmpty(), "an expired invitation is still listed");
            CrewLedger.Invite again = l.invite(a, b, "B", "A");
            l.accept(again.id(), b, "B");
            check(l.crewOf(b) == crew && crew.roleOf(b) == Role.MEMBER, "accepted member is a Member");
            rejected(() -> l.accept(again.id(), b, "B"), "accepting twice");
            rejected(() -> l.invite(b, c, "C", "B"), "a plain member inviting (not permitted by default)");
            l.setPermission(a, "A", Role.MEMBER, Perm.INVITE, true);
            CrewLedger.Invite toC = l.invite(b, c, "C", "B");
            l.decline(toC.id(), c);
            check(l.invitesFor(c).isEmpty(), "a declined invitation remains");
            rejected(() -> l.invite(a, b, "B", "A"), "inviting somebody already in the Crew");
            // capacity counts members and open invitations
            List<UUID> extra = new ArrayList<>();
            for (int i = 0; i < CrewRules.MAX_OPEN_INVITES; i++) { UUID u = id(); extra.add(u); l.invite(a, u, "X" + i, "A"); }
            rejected(() -> l.invite(a, d, "D", "A"), "more open invitations than allowed");
            // an invitation lapses when its target founds a Crew of their own
            l.create(extra.get(0), "X0", "Rival Crew");
            check(l.invitesFor(extra.get(0)).isEmpty(), "an invitation survived its target founding a Crew");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crewRolesPermissionsAndLeadership(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            CrewLedger l = rig.ledger;
            UUID founder = id(), manager = id(), member = id(), other = id();
            Crew crew = l.create(founder, "F", "Crew One");
            for (UUID u : List.of(manager, member, other)) l.accept(l.invite(founder, u, "n" + u.toString().substring(0, 4), "F").id(), u, "n" + u.toString().substring(0, 4));
            rejected(() -> l.setRole(manager, "M", member, Role.MANAGER), "a non-founder changing roles");
            rejected(() -> l.setRole(founder, "F", founder, Role.MEMBER), "demoting the founder");
            rejected(() -> l.setRole(founder, "F", member, Role.FOUNDER), "promoting to founder by role change");
            l.setRole(founder, "F", manager, Role.MANAGER);
            check(crew.roleOf(manager) == Role.MANAGER, "promotion");
            check(crew.can(manager, Perm.WITHDRAW) && !crew.can(member, Perm.WITHDRAW) && crew.can(member, Perm.STORAGE), "default permissions");
            check(crew.can(founder, Perm.WITHDRAW), "the founder may always");

            // permissions are the founder's to edit and never restrict the founder
            rejected(() -> l.setPermission(manager, "M", Role.MEMBER, Perm.WITHDRAW, true), "a manager editing permissions");
            rejected(() -> l.setPermission(founder, "F", Role.FOUNDER, Perm.WITHDRAW, false), "restricting the founder");
            l.setPermission(founder, "F", Role.MEMBER, Perm.WITHDRAW, true);
            check(crew.can(member, Perm.WITHDRAW), "granted permission has no effect");
            l.setPermission(founder, "F", Role.MEMBER, Perm.WITHDRAW, false);
            check(!crew.can(member, Perm.WITHDRAW), "revoked permission still applies");

            // removing members: only below your own rank, with the right
            rejected(() -> l.kick(member, "m", manager), "a member removing a manager");
            rejected(() -> l.kick(manager, "M", founder), "a manager removing the founder");
            rejected(() -> l.kick(manager, "M", manager), "a manager removing themself by kick");
            l.kick(manager, "M", other);
            check(l.crewOf(other) == null && crew.members.size() == 3, "kick did not remove");
            rejected(() -> l.kick(manager, "M", other), "removing somebody who is gone");

            // leaving
            rejected(() -> l.leave(founder, "F"), "the founder leaving without handing over");
            l.leave(member, "m");
            check(l.crewOf(member) == null, "leave");
            rejected(() -> l.leave(member, "m"), "leaving when not in a Crew");

            // leadership
            rejected(() -> l.transfer(manager, "M", founder), "a manager transferring leadership");
            rejected(() -> l.transfer(founder, "F", id()), "transferring to a stranger");
            rejected(() -> l.claimLeadership(manager, "M"), "claiming leadership from an active founder");
            l.transfer(founder, "F", manager);
            check(crew.founder.equals(manager) && crew.roleOf(manager) == Role.FOUNDER && crew.roleOf(founder) == Role.MANAGER, "transfer swaps roles");
            check(crew.can(manager, Perm.MANAGE_PROPERTY) && crew.perms.get(Role.MEMBER).contains(Perm.STORAGE), "permission table survives a transfer");
            // an absent founder can be succeeded, never automatically
            UUID third = id();
            l.accept(l.invite(manager, third, "T", "M").id(), third, "T");
            rig.now[0] += CrewRules.FOUNDER_INACTIVE_MILLIS - 1000;
            rejected(() -> l.claimLeadership(founder, "F"), "claiming before 30 days");
            rig.now[0] += 2000;
            rejected(() -> l.claimLeadership(third, "T"), "a member claiming while a manager exists");
            l.claimLeadership(founder, "F");
            check(crew.founder.equals(founder) && crew.roleOf(manager) == Role.MANAGER, "succession");
            // a restart sees the same Crew
            rig.restart();
            Crew back = rig.ledger.crewOf(founder);
            check(back != null && back.name.equals("Crew One") && back.members.size() == 3 && back.roleOf(founder) == Role.FOUNDER, "Crew lost across a restart");
            check(back.perms.get(Role.MEMBER).equals(crew.perms.get(Role.MEMBER)) && back.log.size() == crew.log.size(), "permissions or history lost across a restart");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ledgerStorageDiscipline(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("uc-test-journals");
        Files.createDirectories(dir);
        // a world that has never had a Crew writes nothing until something happens
        Path fresh = dir.resolve("crew-fresh-" + UUID.randomUUID() + ".dat");
        CrewLedger empty = new CrewLedger(fresh, System::currentTimeMillis);
        check(empty.allCrews().isEmpty() && !Files.exists(fresh), "an unused ledger created a file");
        empty.create(id(), "A", "First Crew");
        check(Files.exists(fresh), "the first Crew was not written");
        check(!Files.exists(fresh.resolveSibling(fresh.getFileName() + ".tmp")), "a temporary file was left behind");
        // an unknown schema or damaged file fails loudly instead of being treated as empty
        net.minecraft.nbt.CompoundTag future = new net.minecraft.nbt.CompoundTag();
        future.putInt("schema_version", 99);
        Path unknown = dir.resolve("crew-future-" + UUID.randomUUID() + ".dat");
        net.minecraft.nbt.NbtIo.write(future, unknown.toFile());
        rejected(() -> new CrewLedger(unknown, System::currentTimeMillis), "a newer ledger schema");
        Path damaged = dir.resolve("crew-damaged-" + UUID.randomUUID() + ".dat");
        Files.write(damaged, new byte[]{1, 2, 3, 4, 5});
        rejected(() -> new CrewLedger(damaged, System::currentTimeMillis), "a damaged ledger file");
        // a write that cannot be made durable stops the ledger instead of carrying on
        Path blocker = dir.resolve("crew-blocker-" + UUID.randomUUID());
        Files.write(blocker, new byte[]{0});
        CrewLedger doomed = new CrewLedger(blocker.resolve("inner.dat"), System::currentTimeMillis);
        rejected(() -> doomed.create(id(), "B", "Doomed Crew"), "a ledger that cannot write");
        check(doomed.faulted(), "a failed write did not fault the ledger");
        rejected(() -> doomed.create(id(), "C", "Another Crew"), "carrying on after a failed write");
        for (Path p : List.of(fresh, unknown, damaged, blocker)) Files.deleteIfExists(p);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ bank protocol

    private static long total(Rig rig, List<ServerPlayer> players) {
        double wallets = 0;
        for (ServerPlayer p : players) wallets += rig.wallet.balances.getOrDefault(p.getUUID(), 0d);
        return (long) wallets + rig.ledger.totalHeld();
    }

    /** Founder, manager and member of one Crew, each with a wallet of 500. */
    private static List<ServerPlayer> crewOfThree(GameTestHelper helper, Rig rig) {
        FakePlayer f = player(helper, "uc_founder", "farmer"), m = player(helper, "uc_manager", "smith"), x = player(helper, "uc_member", "gunsmith");
        Crew crew = rig.ledger.create(f.getUUID(), "uc_founder", "Test Crew");
        for (FakePlayer p : List.of(m, x)) rig.ledger.accept(rig.ledger.invite(f.getUUID(), p.getUUID(), p.getGameProfile().getName(), "uc_founder").id(), p.getUUID(), p.getGameProfile().getName());
        rig.ledger.setRole(f.getUUID(), "uc_founder", m.getUUID(), Role.MANAGER);
        for (FakePlayer p : List.of(f, m, x)) rig.wallet.balances.put(p.getUUID(), 500d);
        check(crew.members.size() == 3, "setup");
        return List.of(f, m, x);
    }

    @GameTest(template = "empty")
    public static void bankDepositWithdrawAndRefusals(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            List<ServerPlayer> p = crewOfThree(helper, rig);
            ServerPlayer founder = p.get(0), manager = p.get(1), member = p.get(2);
            Crew crew = rig.ledger.crewOf(founder.getUUID());
            long start = total(rig, p);
            CrewService.deposit(founder, id(), 120);
            CrewService.deposit(member, id(), 80);
            check(crew.bank == 200, "bank after two deposits: " + crew.bank);
            check(rig.wallet.balances.get(founder.getUUID()) == 380d && rig.wallet.balances.get(member.getUUID()) == 420d, "wallets after deposits");
            check(crew.log.peekFirst().kind().equals("DEPOSIT") && crew.log.peekFirst().amount() == 80, "the history does not show the deposit");
            check(total(rig, p) == start, "credits were created or destroyed by deposits");

            // refusals: nothing changes
            UUID replay = id();
            CrewService.deposit(manager, replay, 10);
            rejected(() -> CrewService.deposit(manager, replay, 10), "a replayed transfer id");
            check(crew.bank == 210 && rig.wallet.balances.get(manager.getUUID()) == 490d, "a replayed deposit changed money");
            rejected(() -> CrewService.deposit(manager, id(), 0), "a zero deposit");
            rejected(() -> CrewService.deposit(manager, id(), -5), "a negative deposit");
            rejected(() -> CrewService.deposit(manager, id(), CrewRules.MAX_TRANSFER + 1), "an oversized deposit");
            rejected(() -> CrewService.deposit(manager, id(), 491), "a deposit larger than the wallet");
            rejected(() -> CrewService.withdraw(member, id(), 5), "a member withdrawing without permission");
            rejected(() -> CrewService.withdraw(manager, id(), 211), "withdrawing more than the bank holds");
            ServerPlayer stranger = player(helper, "uc_stranger", "technician");
            rig.wallet.balances.put(stranger.getUUID(), 50d);
            rejected(() -> CrewService.deposit(stranger, id(), 5), "a non-member depositing");
            rejected(() -> CrewService.withdraw(stranger, id(), 5), "a non-member withdrawing");
            check(crew.bank == 210 && rig.wallet.balances.get(stranger.getUUID()) == 50d, "refusals moved money");

            // withdrawing
            CrewService.withdraw(manager, id(), 60);
            check(crew.bank == 150 && rig.wallet.balances.get(manager.getUUID()) == 550d, "withdrawal");
            // the founder lets members withdraw; two members cannot together take more than the bank holds
            rig.ledger.setPermission(founder.getUUID(), "f", Role.MEMBER, Perm.WITHDRAW, true);
            CrewService.withdraw(member, id(), 100);
            rejected(() -> CrewService.withdraw(manager, id(), 100), "a second withdrawal that would overdraw the bank");
            CrewService.withdraw(manager, id(), 50);
            check(crew.bank == 0, "bank not empty");
            rejected(() -> CrewService.withdraw(founder, id(), 1), "withdrawing from an empty bank");
            check(total(rig, p) == start, "conservation after the series");

            // a restart keeps the balance and the history
            CrewService.deposit(founder, id(), 77);
            rig.restart();
            Crew back = rig.ledger.crewOf(founder.getUUID());
            check(back.bank == 77 && back.log.peekFirst().kind().equals("DEPOSIT"), "bank or history lost across a restart");
            // transfer ids are remembered across the restart too
            UUID before = id();
            CrewService.deposit(founder, before, 3);
            rig.restart();
            rejected(() -> CrewService.deposit(founder, before, 3), "a transfer id replayed after a restart");
        }
        helper.succeed();
    }

    /** What a crash at each point of a transfer leaves, and what recovery then decides. */
    @GameTest(template = "empty")
    public static void crashAtEveryPointRecoversWithoutLossOrDuplication(GameTestHelper helper) throws Exception {
        record Case(String stage, boolean walletSurvived, boolean settled) {}
        List<Case> cases = List.of(
            new Case("after_begin", false, false),
            new Case("after_wallet", false, false),   // the wallet change was only in memory and is lost with the process
            new Case("after_wallet", true, true),     // the player file happened to be written
            new Case("after_save", true, true));
        for (boolean deposit : new boolean[]{true, false}) {
            for (Case c : cases) {
                try (Rig rig = new Rig(helper)) {
                    List<ServerPlayer> p = crewOfThree(helper, rig);
                    ServerPlayer founder = p.get(0);
                    Crew crew = rig.ledger.crewOf(founder.getUUID());
                    if (!deposit) { CrewService.deposit(p.get(1), id(), 300); }
                    long bankBefore = crew.bank;
                    double walletBefore = rig.wallet.balances.get(founder.getUUID());
                    long start = total(rig, p);
                    CrewService.crashAt = c.stage();
                    try {
                        if (deposit) CrewService.deposit(founder, id(), 100); else CrewService.withdraw(founder, id(), 100);
                        throw new AssertionError("the simulated crash did not happen");
                    } catch (CrewService.SimulatedCrash expected) { /* the process "died" here */ }
                    CrewService.crashAt = "";
                    if (!c.walletSurvived()) rig.wallet.balances.put(founder.getUUID(), walletBefore);
                    // the restart: read the file, then recover at server start (the player is not connected)
                    rig.restart();
                    check(rig.ledger.pendingFor(founder.getUUID()) != null, (deposit ? "deposit" : "withdraw") + " " + c + ": the prepare record was not durable");
                    rejected(() -> CrewService.deposit(founder, id(), 1), "a new transfer while one is unresolved");
                    // another member is unaffected by the stuck one
                    int decided = CrewService.recover(rig.server, rig.wallet);
                    check(decided == 1, "recovery decided " + decided + " transfers for " + c);
                    check(rig.ledger.pendingFor(founder.getUUID()) == null, "the transfer is still pending after recovery " + c);
                    Crew back = rig.ledger.crewOf(founder.getUUID());
                    long expectedBank = c.settled() ? (deposit ? bankBefore + 100 : bankBefore - 100) : bankBefore;
                    String where = (deposit ? "deposit " : "withdrawal ") + c;
                    check(back.bank == expectedBank, where + ": bank " + back.bank + " expected " + expectedBank);
                    double expectedWallet = c.settled() ? walletBefore + (deposit ? -100 : 100) : walletBefore;
                    check(rig.wallet.balances.get(founder.getUUID()) == expectedWallet, where + ": wallet " + rig.wallet.balances.get(founder.getUUID()) + " expected " + expectedWallet);
                    check(total(rig, p) == start, where + ": credits created or destroyed");
                    // and the same recovery run twice changes nothing
                    check(CrewService.recover(rig.server, rig.wallet) == 0, where + ": recovery is not idempotent");
                    // the player can transfer again
                    CrewService.deposit(founder, id(), 1);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadableOrAmbiguousWalletBlocksOnlyThatPlayer(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            List<ServerPlayer> p = crewOfThree(helper, rig);
            ServerPlayer founder = p.get(0), manager = p.get(1);
            Crew crew = rig.ledger.crewOf(founder.getUUID());
            CrewService.crashAt = "after_save";
            try { CrewService.deposit(founder, id(), 40); } catch (CrewService.SimulatedCrash expected) { }
            CrewService.crashAt = "";
            rig.wallet.balances.put(founder.getUUID(), 123d); // matches neither the recorded before (500) nor after (460)
            rig.restart();
            check(CrewService.recover(rig.server, rig.wallet) == 0, "an ambiguous transfer was guessed at");
            check(rig.ledger.pendingFor(founder.getUUID()) != null && rig.ledger.crewOf(founder.getUUID()).bank == 0, "the ambiguous transfer was changed");
            rejected(() -> CrewService.deposit(founder, id(), 1), "the stuck player transferring");
            CrewService.deposit(manager, id(), 25); // everybody else carries on
            check(rig.ledger.crewOf(founder.getUUID()).bank == 25, "a stuck member blocked the whole Crew");
            rejected(() -> rig.ledger.disband(founder.getUUID(), "f"), "disbanding with a transfer in flight");
            rejected(() -> rig.ledger.leave(manager.getUUID(), "m"), "leaving with a transfer in flight");
            // an operator who restores the wallet lets recovery finish
            rig.wallet.balances.put(founder.getUUID(), 460d);
            check(CrewService.recover(rig.server, rig.wallet) == 1 && rig.ledger.crewOf(founder.getUUID()).bank == 65, "the operator's fix was not honoured");
        }
        helper.succeed();
    }

    /** Seeded random operations with crashes and restarts: the sum of wallets, banks and owed payouts must never change. */
    @GameTest(template = "empty")
    public static void randomisedOperationsConserveEveryCredit(GameTestHelper helper) throws Exception {
        for (long seed : new long[]{7, 19, 20261018}) {
            try (Rig rig = new Rig(helper)) {
                Random random = new Random(seed);
                List<ServerPlayer> all = new ArrayList<>(crewOfThree(helper, rig));
                ServerPlayer outsider = player(helper, "uc_outsider", "vintner");
                rig.wallet.balances.put(outsider.getUUID(), 500d);
                all.add(outsider);
                long start = total(rig, all);
                String[] stages = {"after_begin", "after_wallet", "after_save"};
                int crashes = 0;
                for (int step = 0; step < 400; step++) {
                    ServerPlayer who = all.get(random.nextInt(all.size()));
                    long amount = 1 + random.nextInt(160);
                    boolean crash = random.nextInt(9) == 0;
                    try {
                        if (crash) CrewService.crashAt = stages[random.nextInt(stages.length)];
                        switch (random.nextInt(8)) {
                            case 0, 1, 2 -> CrewService.deposit(who, id(), amount);
                            case 3, 4 -> CrewService.withdraw(who, id(), amount);
                            case 5 -> CrewService.claimOwed(who, id());
                            case 6 -> { Crew c = rig.ledger.crewOf(who.getUUID()); if (c != null && random.nextInt(6) == 0) CrewService.payOut(who); }
                            default -> {
                                Crew c = rig.ledger.crewOf(who.getUUID());
                                if (c == null && random.nextBoolean()) rig.ledger.create(who.getUUID(), who.getGameProfile().getName(), "Crew " + step);
                                else if (c != null && random.nextInt(25) == 0) rig.ledger.disband(who.getUUID(), "x");
                            }
                        }
                    } catch (IllegalStateException refused) {
                        // a refusal is fine; it must not have moved money
                    } catch (CrewService.SimulatedCrash died) {
                        crashes++;
                        // the process died: whatever the wallet held in memory may or may not have reached the disk
                        double lost = 0;
                        Double saved = rig.wallet.balances.get(who.getUUID());
                        CrewLedger.Pending pending = rig.ledger.reloaded().pendingFor(who.getUUID());
                        if (pending != null && random.nextBoolean() && !died.getMessage().endsWith("after_save")) saved = pending.before();
                        rig.wallet.balances.put(who.getUUID(), saved);
                        rig.restart();
                        CrewService.crashAt = "";
                        CrewService.recover(rig.server, rig.wallet);
                    } finally {
                        CrewService.crashAt = "";
                    }
                    check(rig.ledger.pendingFor(who.getUUID()) == null || crash, "seed " + seed + " step " + step + ": a transfer was left unresolved without a crash");
                    check(total(rig, all) == start, "seed " + seed + " step " + step + ": credits created or destroyed (" + total(rig, all) + " vs " + start + ")");
                    if (step % 50 == 49) { rig.restart(); check(total(rig, all) == start, "seed " + seed + ": a restart changed the total"); }
                }
                check(crashes > 5, "the random run did not exercise crashes (" + crashes + ")");
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ modes, disband, payout

    @GameTest(template = "empty")
    public static void financeModesMoveNoMoneyAndNeedConsent(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            List<ServerPlayer> p = crewOfThree(helper, rig);
            ServerPlayer founder = p.get(0), manager = p.get(1), member = p.get(2);
            Crew crew = rig.ledger.crewOf(founder.getUUID());
            check(crew.mode == Mode.BANK && crew.effectiveMode() == Mode.BANK, "the recommended default is a Crew bank");
            CrewService.deposit(founder, id(), 90);
            long start = total(rig, p);
            rejected(() -> rig.ledger.setMode(manager.getUUID(), "m", Mode.SHARED), "a manager changing the finance mode");
            rejected(() -> rig.ledger.setMode(founder.getUUID(), "f", Mode.SEPARATE), "separate money while the bank holds credits");
            rig.ledger.setMode(founder.getUUID(), "f", Mode.SHARED);
            check(crew.mode == Mode.SHARED && crew.effectiveMode() == Mode.BANK, "shared finances took effect without every member agreeing");
            check(total(rig, p) == start && crew.bank == 90, "choosing a mode moved money");
            rig.ledger.setConsent(manager.getUUID(), "m", true);
            check(crew.effectiveMode() == Mode.BANK, "two of three agreeing was enough");
            rig.ledger.setConsent(member.getUUID(), "x", true);
            check(crew.effectiveMode() == Mode.SHARED, "unanimous consent did not take effect");
            rig.ledger.setConsent(member.getUUID(), "x", false);
            check(crew.effectiveMode() == Mode.BANK, "withdrawing consent did not take effect");
            rig.ledger.setConsent(member.getUUID(), "x", true);
            // a new member must agree before income moves
            ServerPlayer late = player(helper, "uc_late", "technician");
            rig.ledger.accept(rig.ledger.invite(founder.getUUID(), late.getUUID(), "uc_late", "f").id(), late.getUUID(), "uc_late");
            check(crew.effectiveMode() == Mode.BANK, "a new member was swept into shared finances");
            rig.ledger.leave(late.getUUID(), "uc_late");
            check(crew.effectiveMode() == Mode.SHARED, "leaving did not restore the agreement");
            // pay out, then separate money
            long paid = CrewService.payOut(founder);
            check(paid == 90 && crew.bank == 0, "pay-out");
            check(rig.ledger.owedTo(founder.getUUID()) == 0 && rig.wallet.balances.get(founder.getUUID()) == 410d + 30d, "the founder's share was not paid into the wallet: " + rig.wallet.balances.get(founder.getUUID()));
            CrewService.claimOwed(manager, id());
            CrewService.claimOwed(member, id());
            check(rig.wallet.balances.get(manager.getUUID()) == 530d && rig.wallet.balances.get(member.getUUID()) == 530d, "members did not receive their equal shares");
            check(total(rig, p) == start && rig.ledger.totalHeld() == 0, "pay-out changed the total: " + total(rig, p) + " vs " + start);
            rig.ledger.setMode(founder.getUUID(), "f", Mode.SEPARATE);
            rejected(() -> CrewService.deposit(founder, id(), 5), "depositing when money is kept separate");
            rejected(() -> CrewService.withdraw(founder, id(), 5), "withdrawing when money is kept separate");
            rig.ledger.setMode(founder.getUUID(), "f", Mode.BANK);
            CrewService.deposit(founder, id(), 5);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disbandPaysEveryMemberOnceAndKeepsHomes(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper)) {
            List<ServerPlayer> p = crewOfThree(helper, rig);
            ServerPlayer founder = p.get(0), manager = p.get(1), member = p.get(2);
            Crew crew = rig.ledger.crewOf(founder.getUUID());
            CrewService.deposit(founder, id(), 100);
            CrewService.deposit(manager, id(), 101);
            long start = total(rig, p);
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            rig.ledger.registerResidence("test:flat", "Test Flat", "flat", "minecraft:overworld", "test", base, base.offset(4, 3, 4), 10, 5);
            rig.ledger.leaseForCrewFromBank(id(), founder.getUUID(), "f", "test:flat");
            check(crew.bank == 196 && rig.ledger.residence("test:flat").ownerType == OwnerType.CREW, "lease from the bank");
            rig.wallet.balances.put(founder.getUUID(), rig.wallet.balances.get(founder.getUUID()));
            long inBank = crew.bank;
            rejected(() -> CrewService.disband(manager), "a manager disbanding the Crew");
            long held = CrewService.disband(founder);
            check(held == inBank, "disband returned " + held);
            check(rig.ledger.crew(crew.id) == null && rig.ledger.crewOf(founder.getUUID()) == null && rig.ledger.crewOf(member.getUUID()) == null, "the Crew or its memberships survived");
            Residence home = rig.ledger.residence("test:flat");
            check(home.ownerType == OwnerType.PLAYER && home.owner.equals(founder.getUUID()), "a Crew home was lost or orphaned by disbanding");
            // 196 = 3 x 65 + 1: the remainder goes to the founder. (Fake players are not on the player list, so nothing was claimed automatically.)
            check(rig.ledger.owedTo(founder.getUUID()) == 66 && rig.ledger.owedTo(manager.getUUID()) == 65 && rig.ledger.owedTo(member.getUUID()) == 65, "the shares are wrong");
            check(rig.ledger.totalHeld() == 196, "owed payouts are not the whole bank");
            for (ServerPlayer q : p) CrewService.claimOwed(q, id());
            double wallets = 0;
            for (ServerPlayer q : p) wallets += rig.wallet.balances.get(q.getUUID());
            check((long) wallets == start - 5, "disband changed the credits in circulation: " + wallets + " vs " + start + " less the 5 spent on the lease");
            check(rig.ledger.totalHeld() == 0, "credits are still held after every claim");
            rejected(() -> CrewService.claimOwed(manager, id()), "claiming a payout twice");
            // the members are free to found or join other Crews and the old name is free
            rig.ledger.create(member.getUUID(), "uc_member", "Test Crew");
        }
        helper.succeed();
    }

    /** Fully shared finances, end to end with the real wallet: work-order pay goes into the bank, an exchange purchase comes out of it, only while everyone agrees. */
    @GameTest(template = "empty")
    public static void fullySharedFinancesSweepWorkOrderPayAndPayExchangePurchasesFromTheBank(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Rig rig = new Rig(helper)) {
            CrewService.wallet = rig.previousWallet; // the live provider
            FakePlayer a = player(helper, "uc_sha", "farmer"), b = player(helper, "uc_shb", "smith");
            Crew crew = rig.ledger.create(a.getUUID(), "uc_sha", "Shared Crew");
            rig.ledger.accept(rig.ledger.invite(a.getUUID(), b.getUUID(), "uc_shb", "uc_sha").id(), b.getUUID(), "uc_shb");
            QShopEconomy.applyBalance(a, 0); QShopEconomy.applyBalance(b, 10);
            Path journal = rig.server.getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/shared-" + UUID.randomUUID() + ".dat");
            WorkOrderLedger orders = new WorkOrderLedger(journal);
            // 1. the recommended default: pay goes to the wallet, the bank is untouched
            UUID harvest = orders.accept(a.getUUID(), WorkOrderCatalog.INSTANCE.find("underground_city:harvest"));
            a.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 16));
            WorkOrderService.complete(a, orders, harvest);
            check(QShopEconomy.balance(a) == 24 && crew.bank == 0, "a Crew bank changed the pay in the default mode: wallet " + QShopEconomy.balance(a) + " bank " + crew.bank);
            // 2. proposed but not agreed by everyone: still nothing moves
            rig.ledger.setMode(a.getUUID(), "uc_sha", Mode.SHARED);
            UUID bread = orders.accept(a.getUUID(), WorkOrderCatalog.INSTANCE.find("underground_city:bread"));
            a.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 8));
            WorkOrderService.complete(a, orders, bread);
            check(QShopEconomy.balance(a) == 60 && crew.bank == 0 && crew.effectiveMode() == Mode.BANK, "an unagreed proposal swept income");
            // 3. everyone agrees: the next delivery's pay goes into the bank
            rig.ledger.setConsent(b.getUUID(), "uc_shb", true);
            check(crew.effectiveMode() == Mode.SHARED, "unanimous consent did not put shared finances in force");
            UUID tools = orders.accept(b.getUUID(), WorkOrderCatalog.INSTANCE.find("underground_city:stone_tools"));
            for (int slot = 0; slot < 3; slot++) { var pick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE); pick.setDamageValue(0); b.getInventory().setItem(slot, pick); }
            WorkOrderService.complete(b, orders, tools);
            check(QShopEconomy.balance(b) == 10 && crew.bank == 24, "pay was not swept into the bank: wallet " + QShopEconomy.balance(b) + " bank " + crew.bank);
            check(CitizenState.get(rig.server).find(b.getUUID()).orElseThrow().careerXp() == 125, "career XP is personal and must still be awarded to the one who delivered");
            // 4. an exchange purchase by the other member is paid from the bank first
            BlockPos pos = helper.absolutePos(new BlockPos(0, 2, 0));
            var original = CityRegions.INSTANCE.all();
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("ironhaven", helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
            try {
                RegionalStock parts = RegionalCatalog.INSTANCE.find("underground_city:machined_parts");
                int price = RegionalMarket.unitPrice(parts, CitizenState.get(rig.server).find(a.getUUID()).orElseThrow());
                long start = (long) (QShopEconomy.balance(a) + QShopEconomy.balance(b)) + crew.bank;
                RegionalMarket.purchase(a, pos, orders, CitizenState.get(rig.server).find(a.getUUID()).orElseThrow(), "underground_city:machined_parts", 1, id());
                check(a.getInventory().countItem(UndergroundCity.item("machined_parts")) == 1, "the purchase did not deliver the goods");
                check(QShopEconomy.balance(a) == 60 && crew.bank == 24 - price, "the bank did not pay: wallet " + QShopEconomy.balance(a) + " bank " + crew.bank + " price " + price);
                check((long) (QShopEconomy.balance(a) + QShopEconomy.balance(b)) + crew.bank == start - price, "credits were created or destroyed beyond the price");
                // the bank holds less than the next price: it pays what it has and the wallet pays the rest
                long left = crew.bank;
                RegionalMarket.purchase(a, pos, orders, CitizenState.get(rig.server).find(a.getUUID()).orElseThrow(), "underground_city:machined_parts", 1, id());
                check(crew.bank == 0 && QShopEconomy.balance(a) == 60 - (price - left), "the wallet did not make up the difference");
                // Q Shop's own shop screens are not routed: they spend the player's own wallet
                var buy = com.qshop.api.QShopAddonApi.buy(b, new net.minecraftforge.items.wrapper.InvWrapper(b.getInventory()), "uc_starter_supply", 0, 0, 1);
                check(buy.isSuccess() && QShopEconomy.balance(b) == 2 && crew.bank == 0, "Q Shop's shop must keep using the personal wallet");
            } finally { CityRegions.INSTANCE.replaceForTest(original); }
            // 5. withdrawing consent stops the routing at once
            rig.ledger.setConsent(b.getUUID(), "uc_shb", false);
            check(crew.effectiveMode() == Mode.BANK, "withdrawing consent did not take effect");
            Files.deleteIfExists(journal);
        }
        helper.succeed();
    }


    /**
     * Law stays individual (D-051, crews.md section 1): one member's underground hand-off earns that member trust and risk, and changes nothing for the other member, the Crew's
     * bank, its history or its roster. A Crew never inherits a member's heat, searches or guilt, and a shared bank is not evidence.
     */
    @GameTest(template = "empty")
    public static void aMembersHeatTrustAndGuiltNeverReachTheCrewOrTheOtherMembers(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Rig rig = new Rig(helper)) {
            FakePlayer dealer = player(helper, "uc_dealer", "farmer"), friend = player(helper, "uc_friend", "farmer");
            Crew crew = rig.ledger.create(dealer.getUUID(), "uc_dealer", "Quiet Crew");
            rig.ledger.accept(rig.ledger.invite(dealer.getUUID(), friend.getUUID(), "uc_friend", "uc_dealer").id(), friend.getUUID(), "uc_friend");
            CitizenState state = CitizenState.get(rig.server);
            state.addCareerXp(dealer.getUUID(), 125 * 64);
            for (String skill : List.of("farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.underground_trade")) state.unlockSkill(dealer.getUUID(), skill);
            QShopEconomy.applyBalance(dealer, 0); QShopEconomy.applyBalance(friend, 50);
            Path journal = rig.server.getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/law-" + UUID.randomUUID() + ".dat");
            WorkOrderLedger orders = new WorkOrderLedger(journal);
            BlockPos pos = helper.absolutePos(new BlockPos(0, 2, 0));
            var original = CityRegions.INSTANCE.all();
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("blackwater", helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
            try {
                WorkOrder order = WorkOrderCatalog.INSTANCE.find("underground_city:quiet_bales");
                check(order != null && order.underground(), "the underground contract exists");
                int historyBefore = crew.log.size();
                WorkOrderService.accept(dealer, orders, state.find(dealer.getUUID()).orElseThrow(), order.id());
                UUID nonce = orders.player(dealer.getUUID()).getUUID("order");
                dealer.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(UndergroundCity.item("sable_leaf_bale"), 6));
                WorkOrderService.complete(dealer, orders, nonce, pos);
                check(orders.trust(dealer.getUUID(), "marrow") == 1, "the member who delivered earned the buyer's trust");
                check(orders.trust(friend.getUUID(), "marrow") == 0, "the other member did not inherit the trust");
                for (String city : List.of("valewood", "ironhaven", "blackwater")) {
                    check(orders.heat(friend.getUUID(), city) == 0, "the other member inherited heat in " + city);
                }
                check(QShopEconomy.balance(friend) == 50 && crew.bank == 0 && crew.log.size() == historyBefore && crew.members.size() == 2,
                    "the Crew's bank, history or roster changed because a member made an underground hand-off");
                check(state.find(friend.getUUID()).orElseThrow().careerXp() == 0, "the other member's career XP is untouched");
            } finally { CityRegions.INSTANCE.replaceForTest(original); }
            Files.deleteIfExists(journal);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realQShopTransfers(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (Rig rig = new Rig(helper)) {
            CrewService.wallet = rig.previousWallet; // the live provider
            FakePlayer a = player(helper, "uc_qa", "farmer"), b = player(helper, "uc_qb", "smith");
            Crew crew = rig.ledger.create(a.getUUID(), "uc_qa", "Real Money Crew");
            rig.ledger.accept(rig.ledger.invite(a.getUUID(), b.getUUID(), "uc_qb", "uc_qa").id(), b.getUUID(), "uc_qb");
            QShopEconomy.applyBalance(a, 200); QShopEconomy.applyBalance(b, 50);
            CrewService.deposit(a, id(), 75);
            CrewService.deposit(b, id(), 20);
            check(QShopEconomy.balance(a) == 125 && QShopEconomy.balance(b) == 30 && crew.bank == 95, "Q Shop wallets and the bank after deposits");
            rejected(() -> CrewService.withdraw(b, id(), 1), "a member withdrawing without permission, with the real wallet");
            CrewService.withdraw(a, id(), 45);
            check(QShopEconomy.balance(a) == 170 && crew.bank == 50, "Q Shop wallet and the bank after a withdrawal");
            rejected(() -> CrewService.deposit(b, id(), 31), "a deposit larger than the real wallet");
            check(QShopEconomy.balance(a) + QShopEconomy.balance(b) + crew.bank == 250, "credits were created or destroyed with the live wallet");
            // the provider's offline read answers for a player who has no saved file by returning null or a balance, never throwing
            Double unknown = QShopEconomy.balanceOffline(rig.server, UUID.randomUUID());
            check(unknown == null || unknown >= 0, "offline read of an unknown player");
            // the wallet survives an entity save and load
            var saved = a.serializeNBT();
            FakePlayer restored = player(helper, "uc_qa2", "farmer");
            restored.deserializeNBT(saved);
            check(QShopEconomy.balance(restored) == 170, "wallet lost in an entity save and load");
        }
        helper.succeed();
    }
}
