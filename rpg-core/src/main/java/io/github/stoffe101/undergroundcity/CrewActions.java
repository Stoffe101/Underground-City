package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import io.github.stoffe101.undergroundcity.CrewNetwork.Act;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a Crew screen request into a service call. The packet carries an operation and a few bounded arguments; everything that matters
 * (who is a member, what a role may do, what a balance is) is looked up here, never read from the packet. A refusal is an
 * {@link IllegalStateException} with words for the player; it is shown in the screen's footer and changes nothing.
 */
final class CrewActions {
    private static final Logger LOG = LogUtils.getLogger();
    /** Disbanding needs two deliberate steps: arm it, then type the Crew's name. The arming lasts a minute. */
    private static final Map<UUID, Integer> ARMED = new HashMap<>();
    private static final int ARM_TICKS = 1200;

    private CrewActions() {}

    static boolean armed(ServerPlayer player) {
        Integer until = ARMED.get(player.getUUID());
        return until != null && player.server.getTickCount() < until;
    }

    static void perform(ServerPlayer player, Act act) {
        String note = "";
        try {
            note = run(player, act);
        } catch (IllegalStateException refused) {
            note = "!" + (refused.getMessage() == null ? "That did not work." : refused.getMessage());
        } catch (CrewService.SimulatedCrash crash) {
            throw crash;
        } catch (RuntimeException error) {
            LOG.error("Crew request {} from {} failed", act.op(), player.getGameProfile().getName(), error);
            note = "!Something went wrong and nothing was changed. The server log has the details.";
        }
        if (note.startsWith("!")) player.sendSystemMessage(Component.literal(note.substring(1)));
        CrewNetwork.sendView(player, false, note);
    }

    private static String run(ServerPlayer player, Act act) {
        switch (act.op()) {
            case REFRESH -> { }
            case CREATE -> { CrewService.create(player, act.text()); return "Crew founded."; }
            case INVITE -> { CrewService.invite(player, act.text()); return "Invitation sent to " + act.text() + "."; }
            case ACCEPT -> { CrewService.accept(player, act.target()); return "You joined the Crew."; }
            case DECLINE -> { CrewService.decline(player, act.target()); return "Invitation declined."; }
            case WITHDRAW_INVITE -> { CrewService.withdrawInvite(player, act.target()); return "Invitation withdrawn."; }
            case LEAVE -> { CrewService.leave(player); return "You left the Crew."; }
            case KICK -> { CrewService.kick(player, act.target()); return "Member removed."; }
            case SET_ROLE -> { CrewService.setRole(player, act.target(), act.flag() ? Role.MANAGER : Role.MEMBER); return "Role changed."; }
            case TRANSFER -> { CrewService.transfer(player, act.target()); return "Leadership handed over."; }
            case CLAIM_LEAD -> { CrewService.claimLeadership(player); return "You lead the Crew now."; }
            case RENAME -> { CrewService.rename(player, act.text()); return "Crew renamed."; }
            case PERMISSION -> {
                Perm[] perms = Perm.values();
                if (act.amount() < 0 || act.amount() >= perms.length) throw new IllegalStateException("Unknown permission.");
                CrewService.setPermission(player, act.flag2() ? Role.MANAGER : Role.MEMBER, perms[(int) act.amount()], act.flag());
            }
            case MODE -> {
                Mode[] modes = Mode.values();
                if (act.amount() < 0 || act.amount() >= modes.length) throw new IllegalStateException("Unknown money mode.");
                CrewService.setMode(player, modes[(int) act.amount()]);
                return modes[(int) act.amount()].title + (modes[(int) act.amount()] == Mode.SHARED ? " proposed. Members agree on the Money tab." : " chosen. No credits moved.");
            }
            case CONSENT -> { CrewService.setConsent(player, act.flag()); return act.flag() ? "You agreed to shared finances." : "You withdrew from shared finances."; }
            case PAY_OUT -> { long total = CrewService.payOut(player); return total + " credits split between the members."; }
            case DISBAND_ASK -> {
                var crew = CrewService.need(player.server).crewOf(player.getUUID());
                if (crew == null || crew.roleOf(player.getUUID()) != Role.FOUNDER) throw new IllegalStateException("Only the founder can disband the Crew.");
                ARMED.put(player.getUUID(), player.server.getTickCount() + ARM_TICKS);
                return "Type the Crew's name to confirm.";
            }
            case DISBAND -> {
                var crew = CrewService.need(player.server).crewOf(player.getUUID());
                if (!armed(player)) throw new IllegalStateException("Start the disband again; the confirmation timed out.");
                if (crew == null || !crew.name.equals(act.text())) throw new IllegalStateException("The name you typed does not match the Crew's name.");
                ARMED.remove(player.getUUID());
                long total = CrewService.disband(player);
                return "The Crew is disbanded." + (total > 0 ? " " + total + " credits were shared out." : "");
            }
            case DEPOSIT -> { CrewService.deposit(player, act.tx(), act.amount()); return act.amount() + " credits deposited."; }
            case WITHDRAW -> { CrewService.withdraw(player, act.tx(), act.amount()); return act.amount() + " credits withdrawn."; }
            case CLAIM_OWED -> { CrewService.claimOwed(player, act.tx()); return "Payout received."; }
            case LEASE -> { CrewService.lease(player, act.tx(), act.text(), act.flag(), act.flag2()); return "Home leased."; }
            case CLAIM_CONTAINER -> { CrewService.claimContainer(player, looking(player)); return "That container is private to you now."; }
            case RELEASE_CONTAINER -> { CrewService.releaseContainer(player, looking(player)); return "That container is shared again."; }
        }
        return "";
    }

    /** The block the player is looking at, within reach, from the position and turn the server last had from the client's movement packets (the head turn is not used: it only follows the body a tick later). */
    private static net.minecraft.core.BlockPos looking(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(), look = net.minecraft.world.phys.Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        BlockHitResult block = player.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(6.0)),
            net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.BLOCK) throw new IllegalStateException("Look at a chest or barrel in your home first.");
        return block.getBlockPos();
    }
}
