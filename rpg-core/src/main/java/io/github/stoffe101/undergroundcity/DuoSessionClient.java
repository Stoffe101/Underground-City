package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;

/**
 * A client of the two-player session of the ninth pass ({@link DuoSessionServer}): on its first login it answers the real first-login screen (the city its name says, a Farmer or a Smith) by
 * clicking the screen's own cards and its confirm button, the clicks given to the screen's own mouse handler at the cards' centres (not through the operating system: the single-player city-start
 * runs use real input); later the second player, who is not an operator, types {@code /uc admin info} and keeps what the server answers. Everything else (where the server puts them, the
 * commands, the burglary) is the server's to check.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
final class DuoSessionClient {
    private DuoSessionClient() {}

    private static int ticks, registrationStep;
    private static long registeredAt = -1;
    private static boolean commandSent, written;
    private static final JsonArray HEARD = new JsonArray();

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        ticks++;
        String name = mc.getUser().getName();
        // what the client sees, now and then: the first run of this session never reached the screen branch and said nothing (October 26)
        if (ticks == 1 || ticks % 200 == 0) System.out.println("[UC-DUO] client " + name + " tick " + ticks + ": screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ", clicks so far " + registrationStep);
        if (mc.screen instanceof RegistrationScreen screen) {
            if (ticks % 30 != 0) return;
            int city = name.equals(DuoSessionServer.VALE) ? 1 : 2, career = name.equals(DuoSessionServer.VALE) ? 0 : 2;
            int[] at = switch (screen.step()) { case 0 -> screen.cardCentre(city); case 1 -> screen.cardCentre(career); default -> screen.confirmCentre(); };
            System.out.println("[UC-DUO] client " + name + " clicks step " + screen.step() + " at " + at[0] + "," + at[1]);
            screen.mouseClicked(at[0], at[1], 0);
            screen.mouseReleased(at[0], at[1], 0);
            registrationStep++;
            return;
        }
        if (registrationStep > 0 && registeredAt < 0) registeredAt = ticks;
        // a while after landing, the player who is not an operator tries the operator's command
        if (name.equals(DuoSessionServer.BLACK) && registeredAt >= 0 && !commandSent && ticks - registeredAt > 400) {
            commandSent = true;
            System.out.println("[UC-DUO] client " + name + " types /uc admin info " + DuoSessionServer.VALE);
            mc.player.connection.sendCommand("uc admin info " + DuoSessionServer.VALE);
        }
        if (commandSent && !written && ticks - registeredAt > 600) {
            written = true;
            JsonObject report = new JsonObject();
            report.addProperty("player", name);
            report.add("heard_after_command", HEARD);
            try { Files.writeString(mc.gameDirectory.toPath().resolve("duo-client-phase" + DuoSessionServer.PHASE + ".json"), report + "\n"); }
            catch (java.io.IOException error) { System.out.println("[UC-DUO] could not write the client report: " + error); }
        }
    }

    @SubscribeEvent
    public static void heard(ClientChatReceivedEvent event) {
        if (!DuoSessionServer.ENABLED || !commandSent || written) return;
        String text = event.getMessage().getString();
        HEARD.add(text);
        System.out.println("[UC-DUO] client heard: " + text);
    }
}
