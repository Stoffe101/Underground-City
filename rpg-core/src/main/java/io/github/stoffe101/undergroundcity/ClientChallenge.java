package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientChallenge {
    private ClientChallenge() {}

    public static void open(BlockPos pos, byte[] sequence, int kind, int perSymbol, int inputTicks) {
        Minecraft.getInstance().setScreen(new ChallengeScreen(pos, sequence, kind == 1 ? ChallengeScreen.Kind.ALARM : ChallengeScreen.Kind.TERMINAL, perSymbol, inputTicks));
    }
}
