package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A workshop station in the world (D-163): one block class for every station of {@link Workshops}, its tier and whether it is at work shown on the block. A right click opens the station's screen;
 * the server starts a batch from what the player carries, runs it on the active clock (it does not move while nobody plays), hands over what it made (graded, where the recipe grades) and fits the
 * next tier from real parts. Breaking it gives back the station at its tier and whatever was in it.
 */
final class WorkshopBlock extends HorizontalDirectionalBlock implements EntityBlock {
    static final IntegerProperty TIER = IntegerProperty.create("tier", 1, 3);
    static final BooleanProperty WORKING = BooleanProperty.create("working");

    final String station;
    private final VoxelShape shape;

    WorkshopBlock(String station, VoxelShape shape, Properties properties) {
        super(properties);
        this.station = station;
        this.shape = shape;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TIER, 1).setValue(WORKING, false));
    }

    /** The shape of each station: benches and tables stand a little under a block, the cask lies low, the vat and the still fill the block. */
    static VoxelShape shapeOf(String station) {
        return switch (station) {
            case "fermentation_vat", "pot_still" -> Block.box(1, 0, 1, 15, 16, 15);
            case "oak_cask" -> Block.box(0, 0, 1, 16, 13, 15);
            case "mill" -> Block.box(1, 0, 1, 15, 12, 15);
            default -> Block.box(0, 0, 0, 16, 15, 16);
        };
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, TIER, WORKING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return shape; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Entity(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != UndergroundCity.WORKSHOP_ENTITY.get()) return null;
        return (lvl, pos, st, be) -> { if (lvl.getGameTime() % 40 == 13 && be instanceof Entity e) e.check((ServerLevel) lvl, st); };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof Entity e) WorkshopNetwork.open(sp, pos, e, "");
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // a station carried away keeps its tier (the item remembers it)
        if (!level.isClientSide && stack.hasTag() && stack.getTag().contains("uc_tier") && level.getBlockEntity(pos) instanceof Entity e) {
            e.tier = Math.max(1, Math.min(3, stack.getTag().getInt("uc_tier")));
            e.setChanged();
            level.setBlock(pos, state.setValue(TIER, e.tier), 3);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!next.is(this) && level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof Entity e) {
            // what was in it is the player's: dropped as a container's contents are, whatever the block-drops rule says
            for (ItemStack stack : e.contents(LockService.clock(server.getServer()))) net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            ItemStack self = new ItemStack(this);
            if (e.tier > 1) self.getOrCreateTag().putInt("uc_tier", e.tier);
            net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), self);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    /** What a station holds: its tier, the batch at work (its recipe, how many, the grade, since when and for how long) and what is ready to take. */
    static final class Entity extends BlockEntity {
        int tier = 1;
        String recipe = "";
        int batches, grade;
        long since, duration;
        final List<ItemStack> ready = new ArrayList<>();
        /** The recipe and batches of what is ready to take, kept when the station's own clock finishes a batch, so its career XP is paid when it is taken. */
        String readyRecipe = "";
        int readyBatches;
        /** What went in (given back if the station is broken before the batch is done). */
        final List<ItemStack> inputs = new ArrayList<>();
        /** Who started the batch at work, and whose work is ready to take (null in a station saved before D-180: anybody may take it, as before). */
        java.util.UUID startedBy, readyBy;
        /** The career XP of everything ready, summed batch by batch (a pile of fasteners topped with one gear set is paid as fasteners and a gear set, not as gear sets), and when it was ready. */
        int readyXp;
        long readySince;

        Entity(BlockPos pos, BlockState state) { super(UndergroundCity.WORKSHOP_ENTITY.get(), pos, state); }

        Workshops.Station station() { return getBlockState().getBlock() instanceof WorkshopBlock b ? Workshops.INSTANCE.station(b.station) : null; }

        boolean busy() { return !recipe.isEmpty(); }
        boolean done(long now) { return busy() && now - since >= duration; }
        long ticksLeft(long now) { return busy() ? Math.max(0, duration - (now - since)) : 0; }

        /** Everything in the station now: a finished batch's goods, or a running batch's inputs, and anything waiting to be taken. */
        List<ItemStack> contents(long now) {
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack s : ready) out.add(s.copy());
            if (busy()) {
                if (done(now)) out.addAll(made());
                else for (ItemStack s : inputs) out.add(s.copy());
            }
            return out;
        }

        /** The goods of the running batch, as made. */
        List<ItemStack> made() {
            Workshops.Station st = station();
            Workshops.Recipe r = st == null ? null : st.recipe(recipe);
            if (r == null) return List.of();
            Item item = ForgeRegistries.ITEMS.getValue(r.output());
            if (item == null) return List.of();
            List<ItemStack> out = new ArrayList<>();
            int total = r.count() * batches;
            while (total > 0) {
                int n = Math.min(total, item.getMaxStackSize());
                out.add(Workshops.graded(new ItemStack(item, n), grade));
                total -= n;
            }
            return out;
        }

        /** Moves a finished batch into what is ready to take, and shows the station idle again. */
        void check(ServerLevel level, BlockState state) {
            long now = LockService.clock(level.getServer());
            if (busy() && done(now)) finish();
            boolean working = busy();
            if (state.getValue(WORKING) != working || state.getValue(TIER) != tier) level.setBlock(worldPosition, state.setValue(WORKING, working).setValue(TIER, tier), 3);
        }

        void finish() {
            if (ready.isEmpty() || readyBy == null) readyBy = startedBy;
            startedBy = null;
            ready.addAll(made());
            Workshops.Station st = station();
            Workshops.Recipe done = st == null || recipe.isEmpty() ? null : st.recipe(recipe);
            if (done != null) readyXp += done.xp() * Math.max(1, batches);
            if (getLevel() instanceof ServerLevel sl) readySince = LockService.clock(sl.getServer());
            if (!recipe.isEmpty()) { readyRecipe = recipe; readyBatches += batches; }
            recipe = ""; batches = 0; grade = 0; since = 0; duration = 0;
            inputs.clear();
            setChanged();
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("tier", tier);
            tag.putString("recipe", recipe);
            tag.putInt("batches", batches);
            tag.putInt("grade", grade);
            tag.putLong("since", since);
            tag.putLong("duration", duration);
            ListTag r = new ListTag(), in = new ListTag();
            for (ItemStack s : ready) r.add(s.save(new CompoundTag()));
            for (ItemStack s : inputs) in.add(s.save(new CompoundTag()));
            tag.put("ready", r);
            tag.put("inputs", in);
            tag.putString("ready_recipe", readyRecipe);
            tag.putInt("ready_batches", readyBatches);
            if (startedBy != null) tag.putUUID("started_by", startedBy);
            if (readyBy != null) tag.putUUID("ready_by", readyBy);
            tag.putInt("ready_xp", readyXp);
            tag.putLong("ready_since", readySince);
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            tier = Math.max(1, Math.min(3, tag.contains("tier") ? tag.getInt("tier") : 1));
            recipe = tag.getString("recipe");
            batches = Math.max(0, Math.min(8, tag.getInt("batches")));
            grade = Math.max(0, Math.min(2, tag.getInt("grade")));
            since = tag.getLong("since");
            duration = Math.max(0, tag.getLong("duration"));
            ready.clear(); inputs.clear();
            readyRecipe = tag.getString("ready_recipe");
            readyBatches = Math.max(0, Math.min(64, tag.getInt("ready_batches")));
            startedBy = tag.hasUUID("started_by") ? tag.getUUID("started_by") : null;
            readyBy = tag.hasUUID("ready_by") ? tag.getUUID("ready_by") : null;
            readyXp = Math.max(0, tag.getInt("ready_xp"));
            readySince = Math.max(0, tag.getLong("ready_since"));
            for (Tag t : tag.getList("ready", Tag.TAG_COMPOUND)) { ItemStack s = ItemStack.of((CompoundTag) t); if (!s.isEmpty()) ready.add(s); }
            for (Tag t : tag.getList("inputs", Tag.TAG_COMPOUND)) { ItemStack s = ItemStack.of((CompoundTag) t); if (!s.isEmpty()) inputs.add(s); }
        }
    }

    // ------------------------------------------------------------------------------------------------ the work (server side)

    /** How many of an item the player carries (grades counted together). */
    static int carried(ServerPlayer player, ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return 0;
        int n = 0;
        for (ItemStack s : player.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Why the player may not start {@code r} at this station now, or null. */
    static String refusal(ServerPlayer player, Entity e, Workshops.Station st, Workshops.Recipe r, int batches) {
        if (!r.mod().isEmpty() && !net.minecraftforge.fml.ModList.get().isLoaded(r.mod())) return "Needs " + r.mod() + ", which is not installed.";
        if (e.tier < r.tier()) return "Needs a tier " + r.tier() + " " + st.title() + ".";
        if (!r.skill().isEmpty() && !has(CitizenState.get(player.server).find(player.getUUID()).orElse(null), r.skill())) return "Needs the skill " + skillTitle(r.skill()) + ".";
        int most = Premises.batch(st.tier(e.tier), e.getLevel() instanceof ServerLevel sl && Premises.tradeAt(sl, e.getBlockPos(), player.getUUID()) != null);
        if (batches < 1 || batches > most) return "This " + (most > st.tier(e.tier).batch() ? "tier, in trade premises, runs " : "tier runs ") + most + " at a time.";
        for (var in : r.inputs().entrySet()) if (carried(player, in.getKey()) < in.getValue() * batches) return "Not enough " + name(in.getKey()) + ".";
        return null;
    }

    static boolean has(CitizenState.Citizen c, String skill) { return c != null && SkillService.has(c, skill); }

    static String skillTitle(String id) { SkillNode n = SkillCatalog.INSTANCE.find(id); return n == null ? id : n.title(); }

    static String name(ResourceLocation id) { Item i = ForgeRegistries.ITEMS.getValue(id); return i == null ? id.toString() : i.getDescription().getString(); }

    /** Starts a batch: takes the inputs, decides the grade, and either makes it at once (an instant recipe) or sets it running. Returns what the player is told. */
    static String start(ServerPlayer player, ServerLevel level, Entity e, String recipeId, int batches) {
        Workshops.Station st = e.station();
        Workshops.Recipe r = st == null ? null : st.recipe(recipeId);
        if (r == null) return "There is no such recipe here.";
        if (e.busy()) return "The " + st.title() + " is still at work.";
        if (!e.ready.isEmpty() && !mayTake(player, e)) return "Somebody else's batch is waiting here to be taken; the " + st.title() + " is theirs until they take it (anybody's after two hours of play).";
        if (!e.ready.isEmpty() && e.readyBy != null && !e.readyBy.equals(player.getUUID())) return "A Crew mate's batch is ready here: take it first (the career XP stays theirs).";
        String why = refusal(player, e, st, r, batches);
        if (why != null) return why;
        // the grade of what goes in: the lowest grade among the stacks taken, so a Reserve cannot be made from Standard by mixing
        int inputGrade = 2;
        List<ItemStack> taken = new ArrayList<>();
        for (var in : r.inputs().entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(in.getKey());
            int need = in.getValue() * batches;
            for (ItemStack s : player.getInventory().items) {
                if (need <= 0) break;
                if (!s.is(item)) continue;
                int n = Math.min(need, s.getCount());
                inputGrade = Math.min(inputGrade, Workshops.grade(s));
                ItemStack part = s.copy();
                part.setCount(n);
                taken.add(part);
                s.shrink(n);
                need -= n;
            }
        }
        CitizenState.Citizen c = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        e.recipe = r.id();
        e.batches = batches;
        e.startedBy = player.getUUID();
        e.grade = Workshops.gradeFor(r, e.tier, skill -> has(c, skill), inputGrade);
        e.since = LockService.clock(level.getServer());
        e.duration = Premises.ticks(r, st.tier(e.tier), Premises.tradeAt(level, e.getBlockPos(), player.getUUID()) != null);   // trade premises work a quarter faster (D-169)
        e.inputs.clear();
        e.inputs.addAll(taken);
        e.setChanged();
        if (e.duration == 0) e.finish();
        e.check(level, level.getBlockState(e.getBlockPos()));
        player.getInventory().setChanged();
        return e.duration == 0 ? "Made " + r.count() * batches + " " + name(r.output()) + gradeWord(e.ready.isEmpty() ? 0 : Workshops.grade(e.ready.get(0))) + ". Take it."
            : "Started: " + r.title() + " x" + batches + ", " + minutes(e.duration) + " of play" + (r.graded() ? ", " + Workshops.GRADES[e.grade] : "") + ".";
    }

    private static String gradeWord(int grade) { return grade > 0 ? " (" + Workshops.GRADES[grade] + ")" : ""; }

    static String minutes(long ticks) {
        long s = (ticks + 19) / 20;
        return s < 90 ? s + " s" : (s + 59) / 60 + " min";
    }

    /** Hands over what is ready, and the career XP for it when the station is of the player's own trade. Returns what the player is told. */
    static String collect(ServerPlayer player, ServerLevel level, Entity e) {
        long now = LockService.clock(level.getServer());
        Workshops.Station st = e.station();
        if (e.busy() && e.done(now)) e.finish();
        // what is being taken: the recipe that made it, whether the player's collect or the station's own clock finished the batch (workshop run 3 paid no XP when the clock had)
        String recipeBefore = e.readyRecipe;
        int batchesBefore = e.readyBatches;
        if (e.ready.isEmpty()) return e.busy() ? "Still at work: " + minutes(e.ticksLeft(now)) + " left." : "Nothing to take.";
        // a batch is its maker's: only they or their Crew take it, and the career XP is the maker's (two players at one station, D-180)
        if (!mayTake(player, e)) return "This batch is somebody else's work: only they, or their Crew, can take it.";
        java.util.UUID maker = e.readyBy != null ? e.readyBy : player.getUUID();
        int xp = e.readyXp;
        e.readyBy = null;
        e.readyXp = 0;
        e.readySince = 0;
        int items = 0;
        for (ItemStack s : e.ready) { items += s.getCount(); ItemStack copy = s.copy(); if (!player.getInventory().add(copy)) player.drop(copy, false); }
        e.ready.clear();
        e.readyRecipe = "";
        e.readyBatches = 0;
        e.setChanged();
        e.check(level, level.getBlockState(e.getBlockPos()));
        // career XP for the work of the player's own trade (a batch is paid its recipe's XP once, when its goods are taken)
        if (st != null && !recipeBefore.isEmpty()) {
            Workshops.Recipe r = st.recipe(recipeBefore);
            CitizenState.Citizen c = CitizenState.get(player.server).find(maker).orElse(null);
            if (xp == 0 && r != null) xp = r.xp() * Math.max(1, batchesBefore);   // a station saved before the XP was summed per batch
            if (c != null && st.career().equals(c.career) && xp > 0) CitizenState.get(player.server).addCareerXp(maker, xp);
        }
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "workshop_made");
        return "Took " + items + " from the " + (st == null ? "station" : st.title()) + ".";
    }

    /** How long a finished batch waits for its maker before anybody may take it (active play): a station in a street is not held for a player who never comes back. */
    static final long ABANDONED_TICKS = 2 * 72_000L;

    /** True when {@code player} may take what is ready: it is theirs, nobody's (a station saved before D-180), a member of the maker's Crew's, or it has waited two hours of play. */
    static boolean mayTake(ServerPlayer player, Entity e) {
        if (e.readyBy == null || ownerOrCrew(player, e.readyBy)) return true;
        return e.readySince > 0 && LockService.clock(player.server) - e.readySince >= ABANDONED_TICKS;
    }

    /** True when {@code player} may take the station down: nothing of somebody else's is at work in it or waiting in it (breaking it hands over both, D-180). */
    static boolean mayBreak(ServerPlayer player, Entity e) {
        long now = LockService.clock(player.server);
        boolean batch = !e.busy() || e.startedBy == null || ownerOrCrew(player, e.startedBy) || now - e.since - e.duration >= ABANDONED_TICKS;
        return batch && (e.ready.isEmpty() || mayTake(player, e));
    }

    static boolean ownerOrCrew(ServerPlayer player, java.util.UUID owner) {
        if (owner.equals(player.getUUID())) return true;
        CrewLedger ledger = CrewService.ledger(player.server);
        CrewLedger.Crew mine = ledger == null ? null : ledger.crewOf(player.getUUID());
        return mine != null && mine == ledger.crewOf(owner);
    }

    /** Nobody takes another player's batch by breaking the station it is in (the review of D-180: the drop went to whoever broke it). */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
    static final class BreakGuard {
        private BreakGuard() {}
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void breaking(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
            if (!(event.getPlayer() instanceof ServerPlayer player) || player.isCreative() || !(event.getLevel().getBlockEntity(event.getPos()) instanceof Entity e) || mayBreak(player, e)) return;
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Somebody else's batch is in this station: only they, or their Crew, may take it down."), true);
        }
    }

    /** Fits the next tier from the parts the player carries. Returns what the player is told. */
    static String upgrade(ServerPlayer player, ServerLevel level, Entity e) {
        Workshops.Station st = e.station();
        if (st == null) return "This station is unknown.";
        if (e.tier >= st.tiers().size()) return "The " + st.title() + " is at its best tier.";
        Workshops.Tier next = st.tier(e.tier + 1);
        CitizenState.Citizen c = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (!next.skill().isEmpty() && !has(c, next.skill())) return "Fitting " + next.name() + " needs the skill " + skillTitle(next.skill()) + ".";
        for (var part : next.parts().entrySet()) if (carried(player, part.getKey()) < part.getValue()) return "Fitting " + next.name() + " needs " + part.getValue() + " " + name(part.getKey()) + ".";
        for (var part : next.parts().entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(part.getKey());
            int need = part.getValue();
            for (ItemStack s : player.getInventory().items) { if (need <= 0) break; if (s.is(item)) { int n = Math.min(need, s.getCount()); s.shrink(n); need -= n; } }
        }
        e.tier++;
        e.setChanged();
        e.check(level, level.getBlockState(e.getBlockPos()));
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "workshop_upgraded");
        return "Fitted " + next.name() + ": the " + st.title() + " is tier " + e.tier + " now (" + next.batch() + " at a time, " + Math.round(next.speed() * 100) + "% speed).";
    }

    static Map<String, Integer> view(Map<ResourceLocation, Integer> parts, ServerPlayer player) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (var p : parts.entrySet()) out.put(p.getKey().toString(), carried(player, p.getKey()));
        return out;
    }

    static Component say(String text) { return Component.literal(text); }
}
