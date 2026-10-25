package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Client entry points for conversations (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientDialogue {
    private ClientDialogue() {}

    static void open(int entityId, String name, String subtitle, String city, String text, List<Dialogue.Choice> choices) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientRegistration.suppressedByHarness() && !Playtest.enabled()) return;
        // a reply to the same person continues the conversation: the screen it replaces must not tell the server it ended (the server would close the reply, D-178)
        if (mc.screen instanceof DialogueScreen d && d.entityId() == entityId) d.continued();
        mc.setScreen(new DialogueScreen(entityId, name, subtitle, city, text,
            choices.stream().map(c -> new DialogueScreen.Choice(c.id(), c.label(), c.enabled(), c.hint())).toList(), false));
    }

    static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen) mc.setScreen(null);
    }
}
