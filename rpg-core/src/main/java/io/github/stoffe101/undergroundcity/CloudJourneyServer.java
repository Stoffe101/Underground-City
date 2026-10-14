package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.util.List;

/** Explicitly gated disposable cloud integration fixture. Never enabled in normal play. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID)
public final class CloudJourneyServer {
    private static final int PHASE=Integer.getInteger("uc.journey.phase",0);
    private static boolean initialized,finished;
    private CloudJourneyServer() {}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(PHASE==0 || finished || event.phase!=TickEvent.Phase.END) return;
        var server=event.getServer();
        var folder=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        if(!folder.getFileName().toString().equals("run-journey-server")) throw new IllegalStateException("Journey fixture requires its dedicated disposable directory");
        List<ServerPlayer> players=server.getPlayerList().getPlayers();
        if(players.size()!=2) return;
        if(players.stream().anyMatch(p->!List.of("UC_Farmer","UC_Smith").contains(p.getGameProfile().getName()))) {
            throw new IllegalStateException("Unexpected player in isolated test fixture");
        }
        try {
            if(PHASE==1 && !initialized) {
                initialized=true;
                var level=server.overworld(); BlockPos spawn=level.getSharedSpawnPos();
                BlockPos pc=new BlockPos(spawn.getX(),level.getHeight(Heightmap.Types.MOTION_BLOCKING,spawn.getX(),spawn.getZ())+1,spawn.getZ());
                for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) level.setBlockAndUpdate(pc.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pc,UndergroundCity.TERMINAL.get().defaultBlockState());
                for(ServerPlayer player:players) {
                    boolean farmer=player.getGameProfile().getName().equals("UC_Farmer");
                    if(!CitizenState.get(server).register(player.getUUID(),farmer?"valewood":"ironhaven",farmer?"farmer":"smith")) throw new IllegalStateException("Phase 1 requires fresh identities");
                    player.teleportTo(level,pc.getX()+(farmer?1.5:-1.5),pc.getY(),pc.getZ()+.5,0,0);
                    player.setInvulnerable(true);player.getInventory().clearContent();
                    player.getInventory().setItem(0,new ItemStack(farmer?Items.WHEAT:Items.STONE_PICKAXE,farmer?16:3));
                    player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,8));
                    player.inventoryMenu.broadcastChanges();
                    TerminalNetwork.open(player,pc);
                }
                System.out.println("[UC-JOURNEY] Two real clients ready for ordinary terminal/shop mouse actions");
            }
            for(ServerPlayer player:players) {
                var citizen=CitizenState.get(server).find(player.getUUID()).orElseThrow();
                if(QShopEconomy.balance(player)!=28 || citizen.careerXp()!=125
                        || player.getInventory().countItem(Items.WHEAT_SEEDS)!=4) return;
                if(citizen.careerLevel()!=2 || player.getInventory().countItem(Items.OAK_LOG)!=0
                        || WorkOrderLedger.get(server).player(player.getUUID()).getInt("completed")!=1) {
                    throw new IllegalStateException("Player state failed conservation/restart check");
                }
            }
            server.getPlayerList().saveAll();server.overworld().getDataStorage().save();
            WorkOrderLedger.get(server).persist();
            Files.writeString(folder.resolve("journey-phase"+PHASE+".json"),
                "{\"phase\":"+PHASE+",\"connected_clients\":2,\"each_balance\":28,\"each_career_xp\":125,\"each_seed_count\":4,\"each_receipt_count\":1,\"status\":\"passed\"}\n");
            System.out.println("[UC-JOURNEY] PHASE "+PHASE+" PASSED with two real connected clients");
            finished=true;server.halt(false);
        } catch(Exception error) {
            finished=true;com.mojang.logging.LogUtils.getLogger().error("Cloud journey failed",error);
            try {Files.writeString(folder.resolve("journey-failure.txt"),error.toString());} catch(java.io.IOException ignored) {}
            for(ServerPlayer player:players) player.connection.disconnect(Component.literal("Cloud test failed"));
            server.halt(false);
        }
    }
}
