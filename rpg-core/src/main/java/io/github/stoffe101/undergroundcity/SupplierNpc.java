package io.github.stoffe101.undergroundcity;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.UUID;

/** One real named supply clerk, reusing vanilla villager AI and the terminal backend. */
public final class SupplierNpc {
    private static final String KEY="underground_city_supply_clerk";
    static State state(net.minecraft.server.MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(State::load,State::new,KEY);
    }
    public static final class State extends SavedData {
        private UUID clerk;
        private String dimension;
        private BlockPos terminal;
        static State load(CompoundTag tag) {
            State state=new State();
            if(tag.hasUUID("clerk")) {
                state.clerk=tag.getUUID("clerk");state.dimension=tag.getString("dimension");
                state.terminal=BlockPos.of(tag.getLong("terminal"));
            }
            return state;
        }
        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version",1);
            if(clerk!=null) {
                tag.putUUID("clerk",clerk);tag.putString("dimension",dimension);tag.putLong("terminal",terminal.asLong());
            }
            return tag;
        }
    }
    static Villager spawn(ServerPlayer operator,BlockPos terminal,BlockPos position) {
        State state=state(operator.server);
        if(state.clerk!=null) throw new IllegalStateException("A supply clerk is already registered in this world");
        if(!operator.serverLevel().getBlockState(terminal).is(UndergroundCity.TERMINAL.get())
            || operator.distanceToSqr(terminal.getX()+.5,terminal.getY()+.5,terminal.getZ()+.5)>64) {
            throw new IllegalStateException("Look at a nearby placed Operations Terminal");
        }
        if(!operator.serverLevel().getBlockState(position).isAir()
            || !operator.serverLevel().getBlockState(position.above()).isAir()
            || !operator.serverLevel().getBlockState(position.below()).isFaceSturdy(operator.serverLevel(),position.below(),net.minecraft.core.Direction.UP)) {
            throw new IllegalStateException("Clerk needs two clear blocks above a solid floor beside the PC");
        }
        Villager clerk=EntityType.VILLAGER.create(operator.serverLevel());
        if(clerk==null) throw new IllegalStateException("Villager entity unavailable");
        clerk.setVillagerData(new VillagerData(VillagerType.PLAINS,VillagerProfession.LIBRARIAN,1));
        clerk.setCustomName(Component.literal("Morgan - Supply Clerk"));clerk.setCustomNameVisible(true);
        clerk.setPersistenceRequired();clerk.moveTo(position.getX()+.5,position.getY(),position.getZ()+.5,0,0);
        if(!operator.serverLevel().addFreshEntity(clerk)) throw new IllegalStateException("Clerk spawn rejected");
        state.clerk=clerk.getUUID();state.dimension=operator.serverLevel().dimension().location().toString();
        state.terminal=terminal.immutable();state.setDirty();
        operator.server.overworld().getDataStorage().save();
        return clerk;
    }
    /** Test seam: forget any registered clerk. */
    static void forgetForTest(net.minecraft.server.MinecraftServer server) {
        State state=state(server);state.clerk=null;state.dimension=null;state.terminal=null;state.setDirty();
    }
    static boolean owns(State state,Villager villager) { return villager.getUUID().equals(state.clerk); }
    /** A dead or discarded clerk must not permanently block replacement. Unloading is NOT removal. */
    static boolean releaseIfRemoved(net.minecraft.server.MinecraftServer server,Villager villager,
                                    net.minecraft.world.entity.Entity.RemovalReason reason) {
        State state=state(server);
        if(!owns(state,villager)) return false;
        if(reason!=net.minecraft.world.entity.Entity.RemovalReason.KILLED
            && reason!=net.minecraft.world.entity.Entity.RemovalReason.DISCARDED) return false;
        state.clerk=null;state.dimension=null;state.terminal=null;state.setDirty();
        server.overworld().getDataStorage().save();
        return true;
    }
    @SubscribeEvent public void removed(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if(event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager)) return;
        var removal=villager.getRemovalReason();
        if(removal==null || villager.getServer()==null) return;
        if(releaseIfRemoved(villager.getServer(),villager,removal)) {
            com.mojang.logging.LogUtils.getLogger().warn("Supply clerk {} was removed ({}); /uc_clerk may place a replacement",villager.getUUID(),removal);
        }
    }
    @SubscribeEvent public void interact(PlayerInteractEvent.EntityInteract event) {
        if(!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Villager villager)) return;
        State state=state(player.server);
        if(!owns(state,villager)) return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
        if(!player.serverLevel().dimension().location().toString().equals(state.dimension)
            || player.distanceToSqr(villager)>16 || !TerminalNetwork.authorizedForStarterShop(player,state.terminal)) {
            player.sendSystemMessage(Component.literal("Register citizenship and meet the clerk near the bound Operations Terminal."));
            return;
        }
        TerminalNetwork.open(player,state.terminal);
    }
    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_clerk").requires(source->source.hasPermission(2))
            .executes(ctx->{
                ServerPlayer player=ctx.getSource().getPlayerOrException();
                if(!(player.pick(8,0,false) instanceof BlockHitResult hit)) return 0;
                try {
                    spawn(player,hit.getBlockPos(),hit.getBlockPos().east());
                    ctx.getSource().sendSuccess(()->Component.literal("Morgan spawned. Provide a protected room, bed and lectern for vanilla routines."),true);
                    return 1;
                } catch(IllegalStateException error) {ctx.getSource().sendFailure(Component.literal(error.getMessage()));return 0;}
            }));
    }
}
