package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player business v1 (eleventh pass, D-208): a counter a player sets up in trade premises they work in (D-169), stocked with goods they made or bought, from which the city's own street
 * people buy while it is open. Nothing here is a second wallet: the till is credits Core owes the business (the work-order journal holds them with the stock), and they reach Q Shop's wallet
 * only through the journal's prepared settlement when somebody who may run the business collects them, once.
 *
 * <p>Earnings are after real expenses: every sale pays the city its sales tax ({@link #TAX}, more than any discount a buyer holds) before the till sees it, the premises cost their rent or upkeep on the household's active clock as
 * any home does, and the goods were made or bought first. There is no passive income: a counter sells only what was put on it, at most {@link #SALES_PER_HOUR} an hour of active play, only
 * while it is open, in the premises' good standing and in a shop's hours, and only to a street person who walked in to it, which happens only while somebody is in the city. A unit sells at
 * the price the city's shops charge for it (the lowest), never more, so buying from a shop to sell on always loses the tax.
 */
public final class PlayerBusiness {
    /** The city's sales tax on every sale: more than every discount a buyer can hold together ({@link SkillNode#MAX_TOTAL_DISCOUNT}), so buying from a shop to sell on always loses. */
    static final double TAX = 0.12;
    /** Street people buy everyday goods: nothing dearer than this a unit (a pump assembly is a works' order, not a passer-by's purchase). */
    static final int CUSTOMER_MAX_PRICE = 60;
    /** The goods a counter holds in all. */
    static final int CAPACITY = 64;
    /** At most this many sales an hour of active play: its best hour stays under the best legal hour at the level premises are taken (economy_model.py). */
    static final int SALES_PER_HOUR = 8;
    /** Street people come from this far to an open counter. */
    static final int REACH = 40;

    private PlayerBusiness() {}

    // ------------------------------------------------------------------------------------------------ what a counter sells and at what price

    /** The lowest price a city's shop charges for this item, or -1 when no shop sells it (then no counter takes it). */
    static long referencePrice(net.minecraft.world.item.Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        long best = -1;
        for (RegionalStock line : RegionalCatalog.INSTANCE.all())
            if (line.item().equals(id) && line.unitPrice() > 0 && (best < 0 || line.unitPrice() < best)) best = line.unitPrice();
        return best;
    }

    /** A stack a counter takes: plain goods (undamaged, no tag but a workshop grade, so nothing stolen, named or enchanted) that a city's shop also sells, at a street person's price. */
    static boolean sellable(ItemStack stack) {
        if (stack.isEmpty() || stack.isDamaged()) return false;
        CompoundTag tag = stack.getTag();
        if (tag != null && !tag.getAllKeys().isEmpty() && !tag.getAllKeys().equals(java.util.Set.of(Workshops.QUALITY))) return false;
        if (!stack.serializeNBT().getCompound("ForgeCaps").isEmpty()) return false;
        long price = referencePrice(stack.getItem());
        return price > MIN_TAX && price <= CUSTOMER_MAX_PRICE;
    }

    /** A line's key on the counter: the item and its workshop grade. */
    static String key(ItemStack stack) { return ForgeRegistries.ITEMS.getKey(stack.getItem()) + "|" + Workshops.grade(stack); }

    static ItemStack stackOf(String key, int count) {
        String[] parts = key.split("\\|");
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(parts[0]));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, count);
        int grade = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        if (grade > 0) stack.getOrCreateTag().putInt(Workshops.QUALITY, grade);
        return stack;
    }

    /** What a line sells for: the city's price times the grade's weight, rounded; -1 when it no longer sells. */
    static long price(String key) {
        ItemStack stack = stackOf(key, 1);
        long base = stack.isEmpty() ? -1 : referencePrice(stack.getItem());
        return base < 0 ? -1 : Math.max(1, Math.round(base * Workshops.GRADE_PRICE[Math.max(0, Math.min(Workshops.GRADE_PRICE.length - 1, Workshops.grade(stack)))]));
    }

    /** The least tax on a sale: a shop's discounted price is rounded, which favours a buyer of cheap goods by up to half a credit. */
    static final int MIN_TAX = 2;

    /** The city's tax on a sale, rounded up and at least {@link #MIN_TAX}, so even the cheapest goods lose when bought from a shop to sell on (economy_model.py checks every line). */
    static long tax(long gross) { return Math.max(MIN_TAX, (long) Math.ceil(gross * TAX - 1e-9)); }

    // ------------------------------------------------------------------------------------------------ where a business is

    /** The trade premises at {@code pos} that {@code player} may work in, in good standing; else null. */
    static CrewLedger.Residence premises(ServerLevel level, BlockPos pos, UUID player) { return Premises.tradeAt(level, pos, player); }

    /** Why {@code player} cannot run a business at {@code pos}, or null. */
    static String refusal(ServerLevel level, BlockPos pos, UUID player) {
        CrewLedger ledger = CrewService.ledger(level.getServer());
        CrewLedger.Residence home = ledger == null ? null : ledger.residenceAt(level.dimension().location().toString(), pos);
        if (home == null || !PropertyService.facilities(home).contains(Premises.TRADE))
            return "A counter does business only in trade premises: a commercial workshop or a warehouse, rented or bought on the Home page.";
        if (premises(level, pos, player) == null) return "Only somebody who may work in these premises, while their rent or upkeep is paid, runs a counter here.";
        return null;
    }

    /** The business whose counter stands at {@code pos}, or null. */
    static String businessAt(WorkOrderLedger orders, BlockPos pos) {
        for (String id : orders.businesses()) if (orders.business(id).getLong("pos") == pos.asLong() && orders.business(id).getBoolean("placed")) return id;
        return null;
    }

    /** True when somebody who may run it is due its takings or may use its counter: a holder with workstation rights, or its founder. */
    static boolean mayRun(ServerLevel level, CompoundTag business, UUID player) {
        if (business.hasUUID("founder") && business.getUUID("founder").equals(player)) return true;
        return premises(level, BlockPos.of(business.getLong("pos")), player) != null;
    }

    /** True when street people may buy here now: opened, set on its counter, stocked, in its premises' good standing (its founder still works there) and in a shop's hours. */
    static boolean open(ServerLevel level, CompoundTag business) {
        if (!business.getBoolean("open") || !business.getBoolean("placed") || business.getCompound("stock").isEmpty() || !business.hasUUID("founder")) return false;
        BlockPos pos = BlockPos.of(business.getLong("pos"));
        if (!Chunks.ready(level, pos) || !(level.getBlockState(pos).getBlock() instanceof Counter)) return false;
        if (premises(level, pos, business.getUUID("founder")) == null) return false;
        return BusinessHours.RULES.spanOf("", "shop").open(PopulationPlan.hourOf(level.getDayTime()));
    }

    /** The cell in front of a counter where a customer stands. */
    static BlockPos front(ServerLevel level, BlockPos counter) {
        BlockState state = level.getBlockState(counter);
        Direction facing = state.getBlock() instanceof Counter ? state.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
        return counter.relative(facing);
    }

    // ------------------------------------------------------------------------------------------------ setting up

    /** A counter was placed: it opens the business of the premises it stands in, or reports why it cannot (the block stays, and does nothing). */
    static void placed(ServerPlayer player, BlockPos pos) { placed(player, WorkOrderLedger.get(player.server), pos); }

    /** Returns the business's id, or null when the counter does nothing here. */
    static String placed(ServerPlayer player, WorkOrderLedger orders, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        String why = refusal(level, pos, player.getUUID());
        if (why != null) { player.sendSystemMessage(Component.literal(why)); return null; }
        CrewLedger.Residence home = premises(level, pos, player.getUUID());
        CompoundTag b = orders.business(home.id);
        if (b.getBoolean("placed") && b.getLong("pos") != pos.asLong() && level.getBlockState(BlockPos.of(b.getLong("pos"))).getBlock() instanceof Counter) {
            player.sendSystemMessage(Component.literal("These premises already have a counter, at " + BlockPos.of(b.getLong("pos")).toShortString() + "."));
            return null;
        }
        boolean fresh = !orders.hasBusiness(home.id);
        if (fresh) { b.putUUID("founder", player.getUUID()); b.putLong("since", orders.clock()); }
        b.putString("city", CityRegions.INSTANCE.cityAt(level, pos).orElse(""));
        b.putString("title", home.title == null ? "" : home.title);
        b.putLong("pos", pos.asLong());
        b.putBoolean("placed", true);
        orders.putBusiness(home.id, b);
        orders.persist();
        player.sendSystemMessage(Component.literal((fresh ? "Your business is set up in " : "The counter of your business is back in ") + (home.title == null ? "these premises" : home.title)
            + ". Use it with goods to stock it, sneak and use it to open or close, and use it empty-handed for the takings."));
        Notice.send(player, Notice.Kind.INFO, "Business counter", "Stock it, then open it: the city's people buy in a shop's hours.");
        return home.id;
    }

    /** A counter was taken away: its business closes. Its stock and its till stay in the journal (nothing is ever taken); a counter set up again in the premises finds them. */
    static void removed(ServerLevel level, BlockPos pos) { removed(WorkOrderLedger.get(level.getServer()), pos); }

    static void removed(WorkOrderLedger orders, BlockPos pos) {
        if (orders.faulted()) return;
        String id = businessAt(orders, pos);
        if (id == null) return;
        CompoundTag b = orders.business(id);
        b.putBoolean("placed", false);
        b.putBoolean("open", false);
        orders.putBusiness(id, b);
        orders.persist();
    }

    // ------------------------------------------------------------------------------------------------ using the counter

    static void use(ServerPlayer player, BlockPos pos, boolean sneaking) { use(player, WorkOrderLedger.get(player.server), pos, sneaking); }

    static void use(ServerPlayer player, WorkOrderLedger orders, BlockPos pos, boolean sneaking) {
        ServerLevel level = player.serverLevel();
        String id = businessAt(orders, pos);
        if (id == null) { player.sendSystemMessage(Component.literal(refusal(level, pos, player.getUUID()) == null ? "Pick the counter up and set it down again to open a business here." : refusal(level, pos, player.getUUID()))); return; }
        CompoundTag b = orders.business(id);
        if (!mayRun(level, b, player.getUUID())) { player.sendSystemMessage(Component.literal("This counter is somebody else's business.")); return; }
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            if (!sellable(held)) { player.sendSystemMessage(Component.literal("The counter sells everyday goods a city's shop also sells, at " + CUSTOMER_MAX_PRICE + " credits or less: nothing named, damaged or stolen.")); return; }
            int n = stock(player, orders, id, key(held), held.getCount());
            player.sendSystemMessage(Component.literal("Stocked " + n + " x " + held.getHoverName().getString() + " at " + price(key(held)) + " each (the city's price; " + Math.round(TAX * 100) + "% of it is sales tax)."));
            return;
        }
        if (sneaking) {
            boolean now = !b.getBoolean("open");
            b.putBoolean("open", now);
            orders.putBusiness(id, b);
            orders.persist();
            player.sendSystemMessage(Component.literal(now ? "Open for business. " + (open(level, orders.business(id)) ? "The city's people may come in." : whyShut(level, orders.business(id))) : "Closed. Nobody buys until you open again."));
            return;
        }
        for (String line : statement(level, orders, id)) player.sendSystemMessage(Component.literal(line));
        long paid = collect(player, orders, id);
        if (paid > 0) Notice.send(player, Notice.Kind.DELIVERED, "Takings", "+" + paid + " credits from your counter, after the city's sales tax.");
    }

    /** Why an opened business sells nothing now (for the owner). */
    static String whyShut(ServerLevel level, CompoundTag b) {
        if (b.getCompound("stock").isEmpty()) return "Nothing to sell: stock the counter.";
        if (premises(level, BlockPos.of(b.getLong("pos")), b.getUUID("founder")) == null) return "The premises are not in good standing (their rent or upkeep is owed), or the business's founder no longer works there.";
        BusinessHours.Span hours = BusinessHours.RULES.spanOf("", "shop");
        if (!hours.open(PopulationPlan.hourOf(level.getDayTime()))) return "Customers come in a shop's hours, " + hours.text() + ".";
        return "";
    }

    /** Puts up to {@code count} of the line the player holds onto the counter (prepared settlement); returns how many. */
    static int stock(ServerPlayer player, WorkOrderLedger orders, String id, String key, int count) {
        int room = CAPACITY - orders.business(id).getCompound("stock").getAllKeys().stream().mapToInt(k -> orders.business(id).getCompound("stock").getInt(k)).sum();
        if (room <= 0) throw new IllegalStateException("The counter is full (" + CAPACITY + " goods).");
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        int moved = 0;
        for (int slot = 0; slot < after.items.size() && moved < Math.min(room, count); slot++) {
            ItemStack s = after.items.get(slot);
            if (s.isEmpty() || !sellable(s) || !key(s).equals(key)) continue;
            int take = Math.min(s.getCount(), Math.min(room, count) - moved);
            s.shrink(take);
            moved += take;
        }
        if (moved == 0) return 0;
        int xp = CitizenState.get(player.server).find(player.getUUID()).map(c -> c.careerXp()).orElse(0);
        orders.prepareBusinessStock(player.getUUID(), id, Map.of(key, moved), CAPACITY, after.save(new ListTag()), QShopEconomy.balance(player), xp);
        WorkOrderService.recover(player, orders);
        return moved;
    }

    /** Gives back everything on the counter that fits in the player's inventory (prepared settlement); returns how many. */
    static int withdraw(ServerPlayer player, WorkOrderLedger orders, String id) {
        CompoundTag stock = orders.business(id).getCompound("stock");
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        Map<String, Integer> change = new LinkedHashMap<>();
        int moved = 0;
        for (String key : stock.getAllKeys()) {
            int units = stock.getInt(key);
            while (units > 0) {
                ItemStack stack = stackOf(key, Math.min(units, 64));
                if (stack.isEmpty()) break;
                int asked = stack.getCount();
                after.add(stack);
                int fitted = asked - stack.getCount();
                if (fitted <= 0) break;
                units -= fitted;
                moved += fitted;
                change.merge(key, -fitted, Integer::sum);
                if (fitted < asked) break;
            }
        }
        if (moved == 0) return 0;
        int xp = CitizenState.get(player.server).find(player.getUUID()).map(c -> c.careerXp()).orElse(0);
        orders.prepareBusinessStock(player.getUUID(), id, change, CAPACITY, after.save(new ListTag()), QShopEconomy.balance(player), xp);
        WorkOrderService.recover(player, orders);
        return moved;
    }

    /** Pays the till into the player's wallet once (prepared settlement); returns the credits. */
    static long collect(ServerPlayer player, WorkOrderLedger orders, String id) {
        if (orders.faulted() || !QShopEconomy.available()) return 0;
        long takings = orders.business(id).getLong("takings");
        if (takings <= 0 || orders.player(player.getUUID()).contains("pending")) return 0;
        double wallet = QShopEconomy.balance(player);
        if (wallet + takings > 1_000_000_000) throw new IllegalStateException("Wallet limit reached");
        int xp = CitizenState.get(player.server).find(player.getUUID()).map(c -> c.careerXp()).orElse(0);
        orders.prepareBusinessTakings(player.getUUID(), id, takings, player.getInventory().save(new ListTag()), wallet + takings, xp);
        WorkOrderService.recover(player, orders);
        player.sendSystemMessage(Component.literal("Collected " + takings + " credits from the counter."));
        return takings;
    }

    /** The statement since the takings were last collected: what sold, the tax, the till, what the premises cost, and what is on the counter. */
    static List<String> statement(ServerLevel level, WorkOrderLedger orders, String id) {
        CompoundTag b = orders.business(id);
        List<String> out = new ArrayList<>();
        out.add((b.getString("title").isEmpty() ? "Your business" : b.getString("title")) + (b.getBoolean("open") ? " (open)" : " (closed)") + ": since the takings were last collected, "
            + b.getInt("sold") + " sold for " + b.getLong("gross") + ", city sales tax " + b.getLong("tax") + ", in the till " + b.getLong("takings") + ".");
        CrewLedger crews = CrewService.ledger(level.getServer());
        CrewLedger.Residence home = crews == null ? null : crews.residence(id);
        if (home != null && home.charge > 0)
            out.add("The premises cost " + home.charge + " an hour of household time" + (home.arrears > 0 ? ", and " + home.arrears * home.charge + " is owed" : "") + "; that is paid from a wallet or the Crew bank, not from the till.");
        CompoundTag stock = b.getCompound("stock");
        if (stock.isEmpty()) out.add("The counter is empty.");
        else {
            List<String> lines = new ArrayList<>();
            for (String key : stock.getAllKeys()) lines.add(stock.getInt(key) + " x " + stackOf(key, 1).getHoverName().getString() + " at " + price(key));
            out.add("On the counter: " + String.join(", ", lines) + ".");
        }
        if (b.getBoolean("open") && !open(level, b)) out.add(whyShut(level, b));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the city's customers

    /** An open business near {@code from} that a street person may walk to, with the cell in front of its counter; null when none. */
    record Target(String id, BlockPos counter, BlockPos front) {}

    static Target nearestOpen(ServerLevel level, BlockPos from) { return nearestOpen(level, WorkOrderLedger.get(level.getServer()), from); }

    static Target nearestOpen(ServerLevel level, WorkOrderLedger orders, BlockPos from) {
        if (orders.faulted()) return null;
        Target best = null;
        double bestDistance = (double) REACH * REACH;
        for (String id : orders.businesses()) {
            CompoundTag b = orders.business(id);
            BlockPos counter = BlockPos.of(b.getLong("pos"));
            double d = counter.distSqr(from);
            if (d >= bestDistance || b.getInt("window_sales") >= SALES_PER_HOUR && orders.clock() - b.getLong("window_start") < 72_000 || !open(level, b)) continue;
            best = new Target(id, counter, front(level, counter));
            bestDistance = d;
        }
        return best;
    }

    /** A street person who reached the counter of business {@code id} buys one thing off it, if they are at it and it is still open. Returns whether they bought. */
    static boolean customerBuys(ServerLevel level, String id, BlockPos at) { return customerBuys(level, WorkOrderLedger.get(level.getServer()), id, at); }

    static boolean customerBuys(ServerLevel level, WorkOrderLedger orders, String id, BlockPos at) {
        if (orders.faulted() || !orders.hasBusiness(id)) return false;
        CompoundTag b = orders.business(id);
        if (!open(level, b) || BlockPos.of(b.getLong("pos")).distSqr(at) > 3.5 * 3.5) return false;
        CompoundTag stock = b.getCompound("stock");
        List<String> wanted = wanted(orders, b.getString("city"), stock);
        if (!wanted.isEmpty()) return sellKey(orders, id, wanted.get(level.random.nextInt(wanted.size())));
        return sell(orders, id, level.random.nextInt(Math.max(1, stock.getAllKeys().stream().mapToInt(stock::getInt).sum())));
    }

    /** Why a customer standing at {@code at} would not buy from business {@code id} now (for the population's log). */
    static String whyNot(ServerLevel level, String id, BlockPos at) {
        WorkOrderLedger orders = WorkOrderLedger.get(level.getServer());
        if (orders.faulted() || !orders.hasBusiness(id)) return "no such business";
        CompoundTag b = orders.business(id);
        if (!open(level, b)) return "shut: " + whyShut(level, b);
        double d = Math.sqrt(BlockPos.of(b.getLong("pos")).distSqr(at));
        if (d > 3.5) return "too far from the counter (" + Math.round(d * 10) / 10.0 + " blocks)";
        if (b.getInt("window_sales") >= SALES_PER_HOUR && orders.clock() - b.getLong("window_start") < 72_000) return "the hour's sales are made";
        return "the sale was refused";
    }

    /** The counter's goods that its city's own shops have run short of (D-201): a customer asks for those first, so a counter takes up the slack while a shelf is low. */
    static List<String> wanted(WorkOrderLedger orders, String city, CompoundTag stock) {
        List<String> keys = new ArrayList<>();
        if (city.isEmpty()) return keys;
        for (String key : stock.getAllKeys()) {
            if (stock.getInt(key) <= 0) continue;
            ResourceLocation item = ResourceLocation.parse(key.split("\\|")[0]);
            for (RegionalStock line : RegionalCatalog.INSTANCE.all())
                if (line.city().equals(city) && line.item().equals(item) && Shortages.isShort(line, orders.regionalUnits(line))) { keys.add(key); break; }
        }
        return keys;
    }

    /** Sells one unit, the {@code pick}-th of the counter's goods in order, at its price less tax. */
    static boolean sell(WorkOrderLedger orders, String id, int pick) {
        CompoundTag stock = orders.business(id).getCompound("stock");
        for (String key : new ArrayList<>(stock.getAllKeys())) {
            int units = stock.getInt(key);
            if (pick >= units) { pick -= units; continue; }
            return sellKey(orders, id, key);
        }
        return false;
    }

    /** Sells one unit of the line {@code key} at its price less tax. */
    static boolean sellKey(WorkOrderLedger orders, String id, String key) {
        long gross = price(key);
        if (gross < 1) return false;
        boolean sold = orders.businessSale(id, key, gross, tax(gross), SALES_PER_HOUR);
        if (sold) customerSales++;
        return sold;
    }

    /** Sales to the city's people since the server started (for the population status). */
    static long customerSales;

    // ------------------------------------------------------------------------------------------------ the command

    /** {@code /uc business}: the statement of each business the player may run, and {@code collect} or {@code withdraw} for the one they stand at. */
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("business")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                WorkOrderLedger orders = WorkOrderLedger.get(player.server);
                int n = 0;
                for (String id : orders.businesses()) {
                    if (!mayRun(player.serverLevel(), orders.business(id), player.getUUID())) continue;
                    for (String line : statement(player.serverLevel(), orders, id)) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    n++;
                }
                if (n == 0) ctx.getSource().sendSuccess(() -> Component.literal("You run no business. Set a Business Counter down in trade premises you work in."), false);
                return n;
            })
            .then(Commands.literal("collect").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                WorkOrderLedger orders = WorkOrderLedger.get(player.server);
                long paid = 0;
                for (String id : orders.businesses()) if (mayRun(player.serverLevel(), orders.business(id), player.getUUID())) paid += collect(player, orders, id);
                long total = paid;
                ctx.getSource().sendSuccess(() -> Component.literal(total > 0 ? "Collected " + total + " credits in all." : "No takings to collect."), false);
                return (int) Math.min(Integer.MAX_VALUE, paid);
            }))
            .then(Commands.literal("withdraw").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                WorkOrderLedger orders = WorkOrderLedger.get(player.server);
                int moved = 0;
                for (String id : orders.businesses()) if (mayRun(player.serverLevel(), orders.business(id), player.getUUID())) moved += withdraw(player, orders, id);
                int total = moved;
                ctx.getSource().sendSuccess(() -> Component.literal(total > 0 ? "Took " + total + " goods back from your counter." : "Nothing to take back, or no room for it."), false);
                return moved;
            }));
    }

    // ------------------------------------------------------------------------------------------------ the block

    /** The Business Counter: set down in trade premises it opens a business; used, it stocks, opens, closes and pays the takings. */
    public static final class Counter extends HorizontalDirectionalBlock {
        public Counter(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

        @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (!level.isClientSide && placer instanceof ServerPlayer player) {
                try { placed(player, pos); } catch (RuntimeException error) { player.sendSystemMessage(Component.literal(error.getMessage() == null ? "The counter could not be set up." : error.getMessage())); }
            }
        }

        @Override
        public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
            if (!level.isClientSide && !next.is(this) && level instanceof ServerLevel server) removed(server, pos);
            super.onRemove(state, level, pos, next, moving);
        }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp) {
                try { PlayerBusiness.use(sp, pos, sp.isShiftKeyDown()); }
                catch (IllegalStateException | IllegalArgumentException refused) { sp.sendSystemMessage(Component.literal(refused.getMessage())); }
            }
            return InteractionResult.CONSUME;
        }
    }
}
