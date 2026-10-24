package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a player IS, as far as the server can know (D-090). Everything Underground City keeps per person (citizenship and career, the Q Shop wallet, Crew
 * membership and roles, the Crew bank, homes and private containers, owed payouts) is keyed by the account UUID, never by the name. On a server that
 * authenticates ({@code online-mode=true}) that UUID is the Mojang account, so nobody can be somebody else. On a server that does not
 * ({@code online-mode=false}, the developer launch) a UUID is derived from whatever name the client claims, so anybody can connect as anybody and inherit
 * that person's Crew, bank and homes. This class says so, in the log, to the Crew members and to an operator, and counts the identities that were made
 * the offline way so a move to an authenticated server is not a surprise. It changes no data: a registered identity is never migrated or renamed here.
 */
public final class IdentityAudit {
    private static final Logger LOG = LogUtils.getLogger();

    /** What the server knows about the identities it keeps. {@code offline*} counts accounts whose UUID is the one an unauthenticated server derives from their name. */
    public record Report(boolean authenticated, int citizens, int offlineCitizens, int crewMembers, int offlineCrewMembers, List<String> offlineNames) {}

    /** The UUID an unauthenticated server gives a player called {@code name} (Minecraft's own rule: the version 3 UUID of "OfflinePlayer:name"). */
    public static UUID offlineUuid(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }

    /** Is {@code id} the UUID an unauthenticated server derives from {@code name}? A Mojang account UUID is random (version 4) and never is. */
    public static boolean looksOffline(UUID id, String name) { return id != null && name != null && !name.isEmpty() && offlineUuid(name).equals(id); }

    public static Report audit(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        int citizens = 0, offlineCitizens = 0, members = 0, offlineMembers = 0;
        CitizenState state = CitizenState.get(server);
        for (UUID id : state.ids()) {
            citizens++;
            Optional<com.mojang.authlib.GameProfile> known = server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(id);
            if (known.isPresent() && looksOffline(id, known.get().getName())) { offlineCitizens++; names.add(known.get().getName()); }
        }
        CrewLedger ledger = CrewService.ledger(server);
        if (ledger != null) {
            for (CrewLedger.Crew crew : ledger.allCrews()) {
                for (CrewLedger.Member member : crew.members.values()) {
                    members++;
                    if (looksOffline(member.id, member.name)) { offlineMembers++; if (!names.contains(member.name)) names.add(member.name); }
                }
            }
        }
        return new Report(server.usesAuthentication(), citizens, offlineCitizens, members, offlineMembers, List.copyOf(names));
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        try {
            Report report = audit(event.getServer());
            if (report.authenticated()) {
                LOG.info("[UC] identity: online-mode=true. Accounts are verified by Mojang; Crews, wallets, homes and progress belong to the account UUID, not to a name.");
                if (report.offlineCitizens() + report.offlineCrewMembers() > 0) {
                    LOG.warn("[UC] identity: {} registered citizens and {} Crew members have identities derived from a name by an unauthenticated server ({}). They will NOT match the real accounts of those players, and nothing is migrated automatically. See docs/technical/identity.md before letting anyone play on this world.",
                        report.offlineCitizens(), report.offlineCrewMembers(), String.join(", ", report.offlineNames().stream().limit(5).toList()));
                }
            } else {
                LOG.warn("[UC] identity: online-mode=false. This server does NOT verify accounts: anyone who connects under a Crew member's name becomes that member (their Crew, bank, homes, wallet and progress). Use this only on a closed network among people who trust each other, never on a server others can reach. See docs/technical/identity.md.");
            }
        } catch (RuntimeException error) {
            LOG.error("[UC] identity audit failed", error);
        }
    }

    /** A Crew member on a server that does not verify accounts is told once per login, in the game, because the Crew bank is real credit. */
    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer || player.server.usesAuthentication()) return;
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger != null && ledger.crewOf(player.getUUID()) != null) {
            Notice.send(player, Notice.Kind.WARN, "Accounts are not verified here", "Anyone could join as you and use your Crew bank. Play among friends only.");
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_identity").requires(source -> source.hasPermission(2)).executes(ctx -> {
            Report report = audit(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal("Accounts are " + (report.authenticated() ? "verified by Mojang (online-mode=true)." : "NOT verified (online-mode=false): anyone can join as anyone.")), false);
            ctx.getSource().sendSuccess(() -> Component.literal(report.citizens() + " citizens (" + report.offlineCitizens() + " with a name-derived offline UUID), " + report.crewMembers() + " Crew members ("
                + report.offlineCrewMembers() + " offline-derived)."), false);
            if (!report.offlineNames().isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("Offline-derived: " + String.join(", ", report.offlineNames())), false);
            if (report.authenticated() && report.offlineCitizens() + report.offlineCrewMembers() > 0) {
                ctx.getSource().sendSuccess(() -> Component.literal("These will not match their owners' real accounts. Nothing is migrated automatically (docs/technical/identity.md)."), false);
            }
            return 1;
        }));
    }
}
