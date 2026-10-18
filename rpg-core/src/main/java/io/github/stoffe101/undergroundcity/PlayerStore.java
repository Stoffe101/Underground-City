package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Makes a change to a player's Q Shop wallet durable before a Crew transaction is settled: vanilla logs player-save failures instead of
 * throwing them, so the saved file is read back and compared with what the server holds, then flushed to disk. (The same check the
 * work-order journal uses for deliveries; kept separate so that proven path is untouched.)
 */
final class PlayerStore {
    private PlayerStore() {}

    static void saveVerified(ServerPlayer player) {
        player.server.getPlayerList().saveAll();
        if (player instanceof FakePlayer) return; // a test double has no player file
        try {
            CompoundTag expected = player.saveWithoutId(new CompoundTag());
            File file = player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat").toFile();
            CompoundTag saved = NbtIo.readCompressed(file);
            if (!saved.getCompound("ForgeCaps").equals(expected.getCompound("ForgeCaps"))) {
                throw new IllegalStateException("Player wallet save verification failed");
            }
            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) { channel.force(true); }
            WorkOrderLedger.forceDirectory(file.toPath().getParent());
        } catch (IOException error) {
            throw new IllegalStateException("Player data was not durably saved", error);
        }
    }
}
