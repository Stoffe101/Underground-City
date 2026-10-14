package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.wrapper.InvWrapper;
import com.qshop.api.QShopAddonApi;
import java.util.UUID;

@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkOrderGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Unsafe action accepted");
    }
    private static FakePlayer player(GameTestHelper helper, String career) {
        FakePlayer player=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"uc_"+career));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(),"valewood",career);
        return player;
    }

    @GameTest(template="empty")
    public static void journalReservationsAndRecovery(GameTestHelper helper) throws Exception {
        var path=helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("uc-test-journals/"+UUID.randomUUID()+".dat");
        WorkOrderLedger ledger=new WorkOrderLedger(path);
        WorkOrder job=WorkOrderCatalog.INSTANCE.find("underground_city:harvest");
        check(job!=null,"Datapack work orders did not load");
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        UUID nonce=ledger.accept(first,job);
        ledger.accept(second,job);
        check(ledger.available(job)==job.capacity()-2,"Players received duplicated employer demand");
        rejected(()->ledger.accept(first,job));
        ledger.cancel(second,ledger.player(second).getUUID("order"));
        check(ledger.available(job)==job.capacity()-1,"Cancellation did not restore demand");
        ListTag items=new ListTag();
        CompoundTag stack=new ItemStack(Items.BREAD,8).save(new CompoundTag()); stack.putByte("Slot",(byte)0); items.add(stack);
        ledger.prepare(first,nonce,items,24,125);
        WorkOrderLedger restarted=new WorkOrderLedger(path);
        check(restarted.player(first).getCompound("pending").getList("inventory",10).equals(items),"Pending exact inventory lost on disk reload");
        rejected(()->restarted.checkActive(first,nonce));
        restarted.settle(first);
        WorkOrderLedger settled=new WorkOrderLedger(path);
        check(settled.player(first).getInt("completed")==1,"Completion receipt not persisted");
        rejected(()->settled.checkActive(first,nonce));
        settled.advance(job.refillTicks()*100L);
        check(settled.available(job)==job.capacity(),"Demand did not replenish");
        UUID third=UUID.randomUUID(); settled.accept(third,job);
        check(settled.available(job)==job.capacity()-1,"Full stock banked an exploitable refill backlog");
        java.nio.file.Files.delete(path);
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void placedTerminalAuthorization(GameTestHelper helper) {
        FakePlayer player=player(helper,"technician");
        BlockPos pos=helper.absolutePos(new BlockPos(0,1,0));
        helper.getLevel().setBlockAndUpdate(pos,UndergroundCity.TERMINAL.get().defaultBlockState());
        player.moveTo(pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5);
        check(TerminalNetwork.authorizedForStarterShop(player,pos),"Nearby registered citizen rejected");
        check(!TerminalNetwork.authorizedForStarterShop(player,pos.above()),"Fake terminal accepted");
        player.moveTo(pos.getX()+20,pos.getY(),pos.getZ());
        check(!TerminalNetwork.authorizedForStarterShop(player,pos),"Remote terminal accepted");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void namedSupplierPersistence(GameTestHelper helper) {
        FakePlayer operator=player(helper,"technician");
        BlockPos pos=helper.absolutePos(new BlockPos(0,2,0));
        helper.getLevel().setBlockAndUpdate(pos,UndergroundCity.TERMINAL.get().defaultBlockState());
        helper.getLevel().setBlockAndUpdate(pos.east().below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(pos.east(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(pos.east().above(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        operator.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        var clerk=SupplierNpc.spawn(operator,pos,pos.east());
        var record=SupplierNpc.State.load(SupplierNpc.state(operator.server).save(new CompoundTag()));
        check(SupplierNpc.owns(record,clerk),"Named supplier UUID lost after NBT roundtrip");
        check(clerk.getCustomName()!=null && clerk.getCustomName().getString().contains("Morgan"),"Supplier lost its identity");
        rejected(()->SupplierNpc.spawn(operator,pos,pos.east()));
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void realFarmerProductionAndQShopTrades(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        FakePlayer player=player(helper,"farmer");
        QShopEconomy.applyBalance(player,0);
        WorkOrderLedger ledger=WorkOrderLedger.get(player.server);
        WorkOrder harvest=WorkOrderCatalog.INSTANCE.find("underground_city:harvest");
        UUID order=ledger.accept(player.getUUID(),harvest);
        rejected(()->WorkOrderService.complete(player,order));
        check(QShopEconomy.balance(player)==0,"Missing-goods attempt paid currency");
        player.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));
        WorkOrderService.complete(player,order);
        check(player.getInventory().getItem(0).isEmpty(),"Delivery did not consume wheat");
        check(QShopEconomy.balance(player)==24,"Wrong Q Shop reward");
        check(CitizenState.get(player.server).find(player.getUUID()).orElseThrow().careerLevel()==2,"Bread order not unlocked by real XP");
        rejected(()->WorkOrderService.complete(player,order));
        check(QShopEconomy.balance(player)==24,"Repeated turn-in duplicated credits");

        // Actual vanilla recipe, not a simulator or invented item.
        TransientCraftingContainer crafting=new TransientCraftingContainer(player.inventoryMenu,3,3);
        for(int i=0;i<3;i++) crafting.setItem(i,new ItemStack(Items.WHEAT));
        var recipe=helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,crafting,helper.getLevel()).orElseThrow();
        ItemStack bread=recipe.assemble(crafting,helper.getLevel().registryAccess());
        check(bread.is(Items.BREAD),"Vanilla production recipe did not produce bread");
        bread.setCount(8); player.getInventory().setItem(0,bread);
        UUID bakery=ledger.accept(player.getUUID(),WorkOrderCatalog.INSTANCE.find("underground_city:bread"));
        WorkOrderService.complete(player,bakery);
        check(QShopEconomy.balance(player)==60,"Second profession payment failed");
        check(player.getInventory().getItem(0).isEmpty(),"Crafted goods not consumed");

        // Exercise the provider's real buy and sell APIs with authored saved shop.
        var buy=QShopAddonApi.buy(player,new InvWrapper(player.getInventory()),"uc_starter_supply",0,0,1);
        check(buy.isSuccess(),"Actual Q Shop seed purchase failed: "+buy.getStatus()+" "+buy.getMessage());
        check(QShopEconomy.balance(player)==52,"Provider did not debit purchase");
        check(player.getInventory().countItem(Items.WHEAT_SEEDS)==4,"Provider did not grant actual seeds");
        player.getInventory().setItem(2,new ItemStack(Items.WHEAT,8));
        var sell=QShopAddonApi.sell(player,new InvWrapper(player.getInventory()),"uc_starter_supply",0,4,1);
        check(sell.isSuccess(),"Actual Q Shop wheat sale failed: "+sell.getStatus()+" "+sell.getMessage());
        check(QShopEconomy.balance(player)==64 && player.getInventory().countItem(Items.WHEAT)==0,"Provider currency/item conservation failed");
        CompoundTag saved=player.serializeNBT();
        FakePlayer restored=player(helper,"farmer");
        restored.deserializeNBT(saved);
        check(QShopEconomy.balance(restored)==64,"Provider wallet capability did not survive entity NBT restore");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void pendingSettlementReplayAndProtectedGoods(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        FakePlayer player=player(helper,"smith");
        WorkOrderLedger ledger=WorkOrderLedger.get(player.server);
        WorkOrder job=WorkOrderCatalog.INSTANCE.find("underground_city:stone_tools");
        UUID nonce=ledger.accept(player.getUUID(),job);
        ItemStack named=new ItemStack(Items.STONE_PICKAXE,3); named.setHoverName(net.minecraft.network.chat.Component.literal("Keepsake"));
        player.getInventory().setItem(0,named);
        rejected(()->WorkOrderService.complete(player,nonce));
        check(player.getInventory().getItem(0).hasCustomHoverName(),"Protected NBT equipment consumed");
        QShopEconomy.applyBalance(player,0);
        ledger.prepare(player.getUUID(),nonce,new ListTag(),24,125);
        // Crash after wallet changed but before XP/receipt persisted.
        QShopEconomy.applyBalance(player,24);
        WorkOrderService.recover(player);
        WorkOrderService.recover(player);
        check(QShopEconomy.balance(player)==24,"Recovery paid twice");
        check(CitizenState.get(player.server).find(player.getUUID()).orElseThrow().careerXp()==125,"Recovery lost/duplicated XP");
        check(ledger.player(player.getUUID()).getInt("completed")==1,"Recovery committed multiple receipts");
        helper.succeed();
    }
}
