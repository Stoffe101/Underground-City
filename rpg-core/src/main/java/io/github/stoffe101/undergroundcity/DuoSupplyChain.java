package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * The two-player supply chain of the tenth pass ({@link DuoSessionServer}, after the burglary): the Valewood Farmer and the Blackwater Vintner found a Crew; the Farmer harvests real
 * crop blocks (sweet berries with a right click, beetroot broken by hand), sets down the Mill from their kit with a right click and mills the beetroot into sugar; the Farmer travels to
 * Blackwater and throws the berries and sugar to the Vintner, who picks them up; the Vintner sets down the Fermentation Vat from their kit, starts a batch of berry wine, waits out its six
 * minutes of play, takes the three bottles and sells them over the club bar's counter; the Vintner pays a share into the Crew bank and the Farmer takes their cut out. Every number the
 * restart has to keep is written to the report, and phase 2 checks it.
 *
 * <p>Each action is the server's own path for what a client sends (a right click is {@code useItemOn}, a break is {@code destroyBlock}, a pick-up is {@code playerTouch}). Scripted, each
 * logged {@code SCRIPTED}: the crops set down fully grown, the vanilla crafting, the travel between the cities, the time of day, the move onto the bar's floor, and opening the counter as
 * speaking to the barkeeper does. The Vintner's starter kit could have made this one batch alone (sixteen berries, four sugar): the run counts the goods from hand to hand rather than
 * proving the Vintner needed them.
 */
final class DuoSupplyChain {
    static final String CREW = "Berry Run", LINE = UndergroundCity.MOD_ID + ":shops/bar_wine_blackwater";
    static final int BUSHES = 4, BEETS = 4, BERRIES = 8, SUGAR = 2, DEPOSIT = 30, CUT = 20;

    @FunctionalInterface interface Check { void check(String what, boolean ok, String detail); }

    private int step, ticks;
    private BlockPos farm, mill, vat;
    private CityBuildings.Building club;
    private int berriesBefore, sugarBefore, beetBefore, xpBefore;

    private static void scripted(String what) { System.out.println("[UC-DUO] SCRIPTED: " + what); }

    /** One tick of the chain; true when it has run to its end. */
    boolean tick(MinecraftServer server, ServerPlayer vale, ServerPlayer black, JsonObject report, Check check) {
        ServerLevel level = server.overworld();
        ticks++;
        switch (step) {
            case 0 -> { crew(server, vale, black, check); farm(level, vale, check); next(); }
            case 1 -> { if (ticks > 30) { gather(level, vale, check); next(); } }
            case 2 -> { if (ticks > 20 * 90) throw new IllegalStateException("the Mill did not finish in ninety seconds");
                        if (!level.getBlockState(mill).getValue(WorkshopBlock.WORKING)) { milled(level, vale, check); next(); } }
            case 3 -> { handOver(level, vale, black); next(); }
            case 4 -> { if (ticks > 60) { received(level, vale, black, check); ferment(level, black, check); next(); } }
            case 5 -> { if (ticks > 20 * 60 * 9) throw new IllegalStateException("the vat did not finish in nine minutes");
                        if (!level.getBlockState(vat).getValue(WorkshopBlock.WORKING)) { bottled(level, black, check); next(); } }
            case 6 -> { toTheBar(level, black, check); next(); }
            case 7 -> { TownspersonEntity keeper = keeper(level);
                        if (keeper == null) { if (ticks > 20 * 90) throw new IllegalStateException("no barkeeper came to the club's counter in ninety seconds"); return false; }
                        sell(level, black, keeper, report, check); next(); }
            case 8 -> { share(server, vale, black, report, check); return true; }
            default -> { return true; }
        }
        return false;
    }

    private void next() { step++; ticks = 0; }

    // ------------------------------------------------------------------------------------------------ the Crew

    private static void crew(MinecraftServer server, ServerPlayer vale, ServerPlayer black, Check check) {
        CrewService.create(vale, CREW);
        CrewService.invite(vale, DuoSessionServer.BLACK);
        CrewLedger ledger = CrewService.ledger(server);
        var invites = ledger.invitesFor(black.getUUID());
        check.check("the Vintner is invited to the Farmer's Crew", invites.size() == 1, invites.size() + " invitations");
        CrewService.accept(black, invites.get(0).id());
        CrewLedger.Crew a = ledger.crewOf(vale.getUUID()), b = ledger.crewOf(black.getUUID());
        check.check("the Farmer and the Vintner are one Crew", a != null && a == b && a.name.equals(CREW), a == null ? "no Crew" : a.name + ", " + a.members.size() + " members");
    }

    // ------------------------------------------------------------------------------------------------ the Farmer's work

    private void farm(ServerLevel level, ServerPlayer vale, Check check) {
        // a row of open soil near the Farmer's welcome that no building owns, with standing room along its south side: four bushes, four beds of beetroot and a place for the Mill
        farm = openRow(level, vale.blockPosition(), BUSHES + BEETS + 1);
        check.check("open soil near the Farmer's welcome that no building owns", farm != null, farm == null ? "none within 64 blocks" : "row from " + farm.toShortString());
        vale.teleportTo(level, farm.getX() + 4.5, farm.getY(), farm.getZ() + 1.5, 180, 30);
        scripted("the Farmer was put beside the row at " + farm.south().toShortString() + "; four sweet berry bushes were set down fully grown (they take about a day of the world's time)");
        for (int i = 0; i < BUSHES; i++) level.setBlock(farm.east(i), Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3), 3);
        // the beds are tilled with the kit's hoe by a right click, then sown with beetroot that has grown (scripted)
        hold(vale, Items.WOODEN_HOE);
        for (int i = 0; i < BEETS; i++) {
            BlockPos soil = farm.east(BUSHES + i).below();
            vale.gameMode.useItemOn(vale, level, vale.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(soil).add(0, 0.5, 0), Direction.UP, soil, false));
        }
        int tilled = 0;
        for (int i = 0; i < BEETS; i++) if (level.getBlockState(farm.east(BUSHES + i).below()).is(Blocks.FARMLAND)) tilled++;
        check.check("the Farmer tills four beds with the hoe", tilled == BEETS, tilled + " of " + BEETS + " are farmland");
        scripted("beetroot was sown in the four beds and has grown (a crop takes a good part of a day)");
        for (int i = 0; i < BEETS; i++) level.setBlock(farm.east(BUSHES + i), Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, BeetrootBlock.MAX_AGE), 3);
        berriesBefore = count(vale, Items.SWEET_BERRIES);
        beetBefore = count(vale, Items.BEETROOT);
        // the harvest: a right click on each bush, each beetroot broken by hand in survival (the drops are the game's own loot)
        for (int i = 0; i < BUSHES; i++) {
            BlockPos bush = farm.east(i);
            vale.gameMode.useItemOn(vale, level, vale.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(bush), Direction.SOUTH, bush, false));
        }
        for (int i = 0; i < BEETS; i++) vale.gameMode.destroyBlock(farm.east(BUSHES + i));
    }

    private void gather(ServerLevel level, ServerPlayer vale, Check check) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(farm).expandTowards(BUSHES + BEETS, 1, 0).inflate(3))) item.playerTouch(vale);
        int berries = count(vale, Items.SWEET_BERRIES) - berriesBefore, beet = count(vale, Items.BEETROOT) - beetBefore;
        check.check("the Farmer harvests berries and beetroot into their own inventory", berries >= BERRIES && beet >= BEETS, berries + " sweet berries, " + beet + " beetroot");
        // the Mill from the kit, set down on the last cell of the row with a right click
        mill = farm.east(BUSHES + BEETS);
        hold(vale, ForgeRegistries.ITEMS.getValue(new ResourceLocation(UndergroundCity.MOD_ID, "mill")));
        vale.gameMode.useItemOn(vale, level, vale.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(mill.below()).add(0, 0.5, 0), Direction.UP, mill.below(), false));
        check.check("the Farmer sets down the Mill from the kit", level.getBlockEntity(mill) instanceof WorkshopBlock.Entity, String.valueOf(level.getBlockState(mill)));
        sugarBefore = count(vale, Items.SUGAR);
        xpBefore = careerXp(vale);
        String said = WorkshopBlock.start(vale, level, (WorkshopBlock.Entity) level.getBlockEntity(mill), "beet_sugar", 1);
        check.check("the Farmer starts the Mill on four beetroot", said.startsWith("Started") && count(vale, Items.BEETROOT) - beetBefore == beet - BEETS, said);
    }

    private void milled(ServerLevel level, ServerPlayer vale, Check check) {
        String said = WorkshopBlock.collect(vale, level, (WorkshopBlock.Entity) level.getBlockEntity(mill));
        int sugar = count(vale, Items.SUGAR) - sugarBefore, xp = careerXp(vale) - xpBefore;
        check.check("the Farmer takes three sugar from the Mill and the Farmer's XP for it", sugar == 3 && xp == 8, said + " (" + sugar + " sugar, +" + xp + " Farmer XP)");
    }

    // ------------------------------------------------------------------------------------------------ from hand to hand

    private void handOver(ServerLevel level, ServerPlayer vale, ServerPlayer black) {
        scripted("the Farmer travelled to Blackwater with the goods (the road takes minutes; the session moves them) and stands two blocks from the Vintner, facing them");
        Vec3 at = black.position();
        vale.teleportTo(level, at.x, at.y, at.z - 2, 0, 0);
        berriesBefore = count(black, Items.SWEET_BERRIES);
        sugarBefore = count(black, Items.SUGAR);
        give(vale, Items.SWEET_BERRIES, BERRIES);
        give(vale, Items.SUGAR, SUGAR);
    }

    /** Throws {@code n} of an item out of the player's inventory, as dropping a stack with Q does. */
    private static void give(ServerPlayer from, Item item, int n) {
        Inventory inv = from.getInventory();
        for (int slot = 0; slot < inv.items.size() && n > 0; slot++) {
            if (!inv.items.get(slot).is(item)) continue;
            ItemStack thrown = inv.removeItem(slot, Math.min(n, inv.items.get(slot).getCount()));
            n -= thrown.getCount();
            from.drop(thrown, false, true);
        }
    }

    private void received(ServerLevel level, ServerPlayer vale, ServerPlayer black, Check check) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, black.getBoundingBox().inflate(6))) item.playerTouch(black);
        int berries = count(black, Items.SWEET_BERRIES) - berriesBefore, sugar = count(black, Items.SUGAR) - sugarBefore;
        check.check("the Vintner picks up what the Farmer threw", berries == BERRIES && sugar == SUGAR, berries + " sweet berries, " + sugar + " sugar");
    }

    // ------------------------------------------------------------------------------------------------ the Vintner's work

    private void ferment(ServerLevel level, ServerPlayer black, Check check) {
        BlockPos row = openRow(level, black.blockPosition(), 1);
        check.check("open ground near the Vintner that no building owns", row != null, row == null ? "none within 64 blocks" : row.toShortString());
        vat = row;
        black.teleportTo(level, vat.getX() + 0.5, vat.getY(), vat.getZ() + 1.5, 180, 30);
        scripted("the Vintner stepped to the open ground at " + vat.south().toShortString());
        hold(black, ForgeRegistries.ITEMS.getValue(new ResourceLocation(UndergroundCity.MOD_ID, "fermentation_vat")));
        black.gameMode.useItemOn(black, level, black.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(vat.below()).add(0, 0.5, 0), Direction.UP, vat.below(), false));
        check.check("the Vintner sets down the Fermentation Vat from the kit", level.getBlockEntity(vat) instanceof WorkshopBlock.Entity, String.valueOf(level.getBlockState(vat)));
        int berries = count(black, Items.SWEET_BERRIES), sugar = count(black, Items.SUGAR), bottles = count(black, Items.GLASS_BOTTLE);
        xpBefore = careerXp(black);
        String said = WorkshopBlock.start(black, level, (WorkshopBlock.Entity) level.getBlockEntity(vat), "berry_wine", 1);
        boolean took = berries - count(black, Items.SWEET_BERRIES) == 8 && sugar - count(black, Items.SUGAR) == 2 && bottles - count(black, Items.GLASS_BOTTLE) == 3;
        check.check("the Vintner starts a batch of berry wine, which takes eight berries, two sugar and three bottles", said.startsWith("Started") && took, said);
    }

    private void bottled(ServerLevel level, ServerPlayer black, Check check) {
        int before = count(black, wine());
        String said = WorkshopBlock.collect(black, level, (WorkshopBlock.Entity) level.getBlockEntity(vat));
        int bottles = count(black, wine()) - before, xp = careerXp(black) - xpBefore;
        check.check("after six minutes of play the Vintner takes three bottles and the Vintner's XP for them", bottles == 3 && xp == 40, said + " (+" + xp + " Vintner XP)");
    }

    // ------------------------------------------------------------------------------------------------ the sale

    private void toTheBar(ServerLevel level, ServerPlayer black, Check check) {
        level.setDayTime(15000);
        scripted("the time of day was set to 21:00 (the club's bar keeps the club's hours, 18:00 to 03:00)");
        int cx = black.blockPosition().getX() >> 4, cz = black.blockPosition().getZ() >> 4;
        for (int r = 0; r <= 16 && club == null; r++) for (int dx = -r; dx <= r && club == null; dx++) for (int dz = -r; dz <= r && club == null; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            var b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).orElse(null);
            if (b != null && b.type().equals(UndergroundCity.MOD_ID + ":bw_club") && ShopCounters.openNow(level, b) && ShopSupply.tillRefusal(level, b) == null) club = b;
        }
        check.check("an open club with a working till near the Vintner", club != null, club == null ? "none within 16 chunks" : "chunk " + club.chunkX() + "," + club.chunkZ());
        level.getChunk(club.chunkX(), club.chunkZ());
        BlockPos floor = null;
        for (long cell : StaffZones.of(level, club).publicCells()) {
            BlockPos p = BlockPos.of(cell);
            if (StaffZones.walkable(level, p) && (floor == null || p.distSqr(centre(club)) < floor.distSqr(centre(club)))) floor = p;
        }
        check.check("the club has a public floor", floor != null, floor == null ? "no public cell" : floor.toShortString());
        black.teleportTo(level, floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, 0, 0);
        scripted("the Vintner was put on the club's public floor at " + floor.toShortString() + " (workshop run 3 walked in through a pub's door with the real keys)");
    }

    private TownspersonEntity keeper(ServerLevel level) {
        BlockPos at = centre(club);
        for (TownspersonEntity body : level.getEntitiesOfClass(TownspersonEntity.class, new AABB(at).inflate(24))) {
            if (body.homeKey() == club.key() && ShopCounters.shopOf(body).isPresent()) return body;
        }
        return null;
    }

    private void sell(ServerLevel level, ServerPlayer black, TownspersonEntity keeper, JsonObject report, Check check) {
        // to the public cell nearest the barkeeper, then the counter opens as speaking to them does
        BlockPos near = null;
        for (long cell : StaffZones.of(level, club).publicCells()) {
            BlockPos p = BlockPos.of(cell);
            if (StaffZones.walkable(level, p) && (near == null || p.distSqr(keeper.blockPosition()) < near.distSqr(keeper.blockPosition()))) near = p;
        }
        black.teleportTo(level, near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0, 0);
        ShopCounters.open(black, keeper);
        scripted("the Vintner stepped up to the counter and the counter opened as speaking to the barkeeper does (\"What do you sell?\")");
        BlockPos counter = keeper.blockPosition();
        check.check("the Vintner is at the bar's open counter", ShopCounters.authorized(black, counter), "barkeeper at " + counter.toShortString() + ", Vintner at " + black.blockPosition().toShortString());
        WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
        RegionalStock stock = RegionalCatalog.INSTANCE.find(LINE);
        CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(black.getUUID()).orElseThrow();
        int shelf = ledger.regionalUnits(stock), xp = citizen.careerXp(), wine = count(black, wine());
        double wallet = QShopEconomy.balance(black);
        ShopSupply.supply(black, counter, ledger, citizen, LINE, 3, UUID.randomUUID());
        double paid = QShopEconomy.balance(black) - wallet;
        int shelfAfter = ledger.regionalUnits(stock), xpGained = careerXp(black) - xp, sold = wine - count(black, wine());
        check.check("the bar buys the three bottles for Q Shop credits and puts them on its shelf", sold == 3 && paid >= 45 && shelfAfter == shelf + 3 && xpGained == 18,
            sold + " sold for " + paid + " credits, shelf " + shelf + " -> " + shelfAfter + ", +" + xpGained + " Vintner XP");
        report.addProperty("chain_sale_credits", paid);
        report.addProperty("chain_bar_shelf_after_sale", shelfAfter);
    }

    // ------------------------------------------------------------------------------------------------ the Farmer's cut

    private void share(MinecraftServer server, ServerPlayer vale, ServerPlayer black, JsonObject report, Check check) {
        double v = QShopEconomy.balance(vale), b = QShopEconomy.balance(black);
        CrewService.deposit(black, UUID.randomUUID(), DEPOSIT);
        CrewService.withdraw(vale, UUID.randomUUID(), CUT);
        CrewLedger.Crew crew = CrewService.ledger(server).crewOf(vale.getUUID());
        check.check("the Vintner pays " + DEPOSIT + " into the Crew bank and the Farmer takes a cut of " + CUT,
            Math.abs(QShopEconomy.balance(black) - (b - DEPOSIT)) < 0.01 && Math.abs(QShopEconomy.balance(vale) - (v + CUT)) < 0.01 && crew.bank == DEPOSIT - CUT,
            "Vintner " + b + " -> " + QShopEconomy.balance(black) + ", Farmer " + v + " -> " + QShopEconomy.balance(vale) + ", bank " + crew.bank);
        // what the restart must keep
        report.addProperty("chain_crew", crew.name);
        report.addProperty("chain_bank", crew.bank);
        report.addProperty("chain_farmer_credits", QShopEconomy.balance(vale));
        report.addProperty("chain_vintner_credits", QShopEconomy.balance(black));
        report.addProperty("chain_farmer_xp", careerXp(vale));
        report.addProperty("chain_vintner_xp", careerXp(black));
        report.addProperty("chain_farmer_sweet_berries", count(vale, Items.SWEET_BERRIES));
        report.addProperty("chain_vintner_sweet_berries", count(black, Items.SWEET_BERRIES));
        report.addProperty("chain_vintner_berry_wine", count(black, wine()));
        report.addProperty("chain_mill", mill.asLong());
        report.addProperty("chain_vat", vat.asLong());
        // the Farmer goes home, so that the house the burglary broke is loaded when its wait is over in phase 2
        BlockPos home = CityArrival.placed(server, vale.getUUID()).orElseThrow().pos();
        vale.teleportTo(server.overworld(), home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
        scripted("the Farmer travelled home to their Valewood welcome");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static BlockPos centre(CityBuildings.Building b) { return new BlockPos(b.chunkX() * 16 + 8, b.streetY() + 1, b.chunkZ() * 16 + 8); }

    static Item wine() { return ForgeRegistries.ITEMS.getValue(new ResourceLocation(UndergroundCity.MOD_ID, "berry_wine")); }

    static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    static int careerXp(ServerPlayer player) { return CitizenState.get(player.server).find(player.getUUID()).map(CitizenState.Citizen::careerXp).orElse(-1); }

    /** Puts a carried item in the main hand: its hotbar slot is selected, or it is swapped into the selected slot (the client is told which slot is held). */
    private static void hold(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.items.size() && slot < 0; i++) if (inv.items.get(i).is(item)) slot = i;
        if (slot < 0) throw new IllegalStateException(player.getGameProfile().getName() + " carries no " + item);
        if (slot < Inventory.getSelectionSize()) inv.selected = slot;
        else {
            ItemStack held = inv.items.get(inv.selected);
            inv.items.set(inv.selected, inv.items.get(slot));
            inv.items.set(slot, held);
            player.inventoryMenu.broadcastChanges();
        }
        player.connection.send(new ClientboundSetCarriedItemPacket(inv.selected));
    }

    /** A row of {@code n} cells along x near {@code from}, in loaded chunks that no building owns: each free for two blocks over soil, with standing room along its south side. */
    static BlockPos openRow(ServerLevel level, BlockPos from, int n) {
        for (int r = 2; r <= 64; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos c = from.offset(dx, dy, dz);
                if (fits(level, c, n)) return c.immutable();
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, BlockPos c, int n) {
        for (int i = 0; i < n; i++) {
            BlockPos cell = c.east(i), front = cell.south();
            if (!level.isLoaded(cell) || !level.isLoaded(front)) return false;
            BlockState ground = level.getBlockState(cell.below());
            if (!level.getBlockState(cell).isAir() || !level.getBlockState(cell.above()).isAir() || !(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT))) return false;
            if (BuildingGuard.protectedAt(level, cell).isPresent() || BuildingGuard.protectedAt(level, cell.below()).isPresent() || BuildingGuard.protectedAt(level, front).isPresent()) return false;
            if (!CityArrival.standable(level, front)) return false;
        }
        return true;
    }
}
