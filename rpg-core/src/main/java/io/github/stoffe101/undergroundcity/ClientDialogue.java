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
        mc.setScreen(new DialogueScreen(entityId, name, subtitle, city, text,
            choices.stream().map(c -> new DialogueScreen.Choice(c.id(), c.label(), c.enabled(), c.hint())).toList(), false));
    }

    static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen) mc.setScreen(null);
    }
}
