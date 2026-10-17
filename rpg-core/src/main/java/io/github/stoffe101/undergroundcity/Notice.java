package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side entry point for the small corner notices (toasts) that mark the moments worth marking: an order taken or delivered, a skill
 * learned, a search at a checkpoint, a rare find. The server decides what happened; the client only draws the words it is sent. A notice
 * never replaces the chat line, which stays as the record.
 */
public final class Notice {
    private Notice() {}

    /** The visual family of a notice; the client picks icon, colour and sound from it. */
    public enum Kind { ORDER, DELIVERED, SKILL, STEP, SEARCH, HEAT, LOOT, TRUST, INFO, WARN }

    public static void send(ServerPlayer player, Kind kind, String title, String body) {
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;
        TerminalNetwork.sendNotice(player, kind, title, body == null ? "" : body);
    }
}
