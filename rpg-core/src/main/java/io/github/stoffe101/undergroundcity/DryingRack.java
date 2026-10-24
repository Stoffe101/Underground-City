package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * The Drying Rack (D-160): the first processing step of the Farmer's controlled crop. Cannabis Flower hung on it (up to {@link #CAPACITY}) dries over {@link #DRY_TICKS} of <b>active play</b> (the
 * work-order ledger's clock, which does not run while nobody is online) into half as much Dried Cannabis, which its buyers pay more for. A right click hangs the flower in hand, says how long is left,
 * or takes the dried crop down. The rack is crafted from sticks and string and anybody may place one; what comes off it is controlled goods like the flower.
 */
final class DryingRack {
    private DryingRack() {}

    static final int CAPACITY = 16;
    /** Six minutes of active play. */
    static final long DRY_TICKS = 6 * 1200L;

    enum Load implements StringRepresentable {
        EMPTY, DRYING, DRY;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    static final EnumProperty<Load> LOAD = EnumProperty.create("load", Load.class);

    /** What drying {@code flower} yields: half, rounded down (two flower make one dried). */
    static int driedFrom(int flower) { return Math.max(0, flower) / 2; }

    static final class Rack extends HorizontalDirectionalBlock implements EntityBlock {
        private static final VoxelShape NS = Block.box(1, 0, 6.5, 15, 15, 9.5), EW = Block.box(6.5, 0, 1, 9.5, 15, 15);

        Rack(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LOAD, Load.EMPTY));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, LOAD); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
        @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return state.getValue(FACING).getAxis() == Direction.Axis.Z ? NS : EW; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Entity(pos, state); }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (level.isClientSide || type != UndergroundCity.DRYING_RACK_ENTITY.get()) return null;
            return (lvl, pos, st, be) -> { if (lvl.getGameTime() % 40 == 7 && be instanceof Entity rack) rack.check((ServerLevel) lvl, st); };
        }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof Entity rack)) return InteractionResult.CONSUME;
            sp.displayClientMessage(Component.literal(rack.use((ServerLevel) level, sp, state)), true);
            return InteractionResult.CONSUME;
        }

        @Override
        public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
            if (!next.is(this) && level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof Entity rack && rack.count > 0) {
                boolean dry = rack.dry(LockService.clock(server.getServer()));
                // what was hung on it is dropped as a container's contents are (whatever the block-drops rule says: it is the player's crop, not the rack's loot)
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(UndergroundCity.item(dry ? "dried_cannabis" : "cannabis_flower"), dry ? driedFrom(rack.count) : rack.count));
            }
            super.onRemove(state, level, pos, next, moved);
        }
    }

    /** What is on a rack: how many flower, and since when (the active clock). */
    static final class Entity extends BlockEntity {
        int count;
        long since;

        Entity(BlockPos pos, BlockState state) { super(UndergroundCity.DRYING_RACK_ENTITY.get(), pos, state); }

        boolean dry(long now) { return count > 0 && now - since >= DRY_TICKS; }

        /** A right click: hang the flower in hand, say how long is left, or take the dried crop down. Returns what the player is told. */
        String use(ServerLevel level, ServerPlayer player, BlockState state) {
            long now = LockService.clock(level.getServer());
            ItemStack held = player.getMainHandItem();
            if (count == 0) {
                if (!held.is(UndergroundCity.item("cannabis_flower"))) return "A drying rack: hang Cannabis Flower on it (two dry into one Dried Cannabis in six minutes of play).";
                int take = Math.min(CAPACITY, held.getCount());
                if (take < 2) return "Hang at least two flower: two dry into one.";
                take -= take % 2;
                held.shrink(take);
                count = take;
                since = now;
                setChanged();
                level.setBlock(worldPosition, state.setValue(LOAD, Load.DRYING), 3);
                return "Hung " + take + " Cannabis Flower to dry. Six minutes of play.";
            }
            if (!dry(now)) {
                long left = Math.max(1, (DRY_TICKS - (now - since) + 1199) / 1200);
                return count + " flower drying: about " + left + (left == 1 ? " minute" : " minutes") + " left.";
            }
            int made = driedFrom(count);
            ItemStack out = new ItemStack(UndergroundCity.item("dried_cannabis"), made);
            if (!player.getInventory().add(out)) player.drop(out, false);
            count = 0;
            since = 0;
            setChanged();
            level.setBlock(worldPosition, state.setValue(LOAD, Load.EMPTY), 3);
            CitizenState.get(level.getServer()).bumpJourney(player.getUUID(), "dried_cannabis");
            return "Took down " + made + " Dried Cannabis.";
        }

        /** Shows the crop as dry once it is. */
        void check(ServerLevel level, BlockState state) {
            if (state.getValue(LOAD) == Load.DRYING && dry(LockService.clock(level.getServer()))) level.setBlock(worldPosition, state.setValue(LOAD, Load.DRY), 3);
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("count", count);
            tag.putLong("since", since);
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            count = Math.max(0, Math.min(CAPACITY, tag.getInt("count")));
            since = tag.getLong("since");
        }
    }
}
