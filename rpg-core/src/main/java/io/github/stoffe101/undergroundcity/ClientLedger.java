package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client entry point of the Public Ledger (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientLedger {
    private ClientLedger() {}

    static void open(LedgerData data) {
        if (ClientRegistration.suppressedByHarness() && !Playtest.enabled()) return;
        Minecraft.getInstance().setScreen(new LedgerScreen(data));
    }
}
