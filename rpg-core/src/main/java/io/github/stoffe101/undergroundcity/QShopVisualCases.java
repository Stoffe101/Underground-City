package io.github.stoffe101.undergroundcity;

import com.qshop.client.ShopScreen;
import com.qshop.net.ClientShopEntry;
import com.qshop.net.ClientTab;
import com.qshop.net.OpenShopPacket;
import com.qshop.currency.Currency;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.Map;

/** Pinned-provider UI fixtures only. No transaction or server success is simulated. */
public final class QShopVisualCases {
    private QShopVisualCases() {}
    static Screen shop(boolean funded) {
        ClientTab tab=new ClientTab(); tab.serverIndex=0; tab.name="Supply"; tab.uuid="uc_supply";
        tab.icon=new ItemStack(Items.PAPER);
        ClientShopEntry seeds=new ClientShopEntry(); seeds.serverIndex=0; seeds.uuid="uc_seeds";
        seeds.item=new ItemStack(Items.WHEAT_SEEDS,4); seeds.currencyId="credits"; seeds.price=8;
        seeds.displayName="Basic seeds (x4)"; seeds.globalLimit=32; seeds.playerLimit=16;
        ClientShopEntry bread=new ClientShopEntry(); bread.serverIndex=1; bread.uuid="uc_rations";
        bread.item=new ItemStack(Items.BREAD,4); bread.currencyId="credits"; bread.price=20;
        bread.displayName="Rations (x4)";
        tab.entries.add(seeds); tab.entries.add(bread);
        return new ShopScreen(new OpenShopPacket("uc_starter_supply","Supply",
            "credits",new ItemStack(Items.PAPER),List.of(tab),Map.of("credits",funded?72D:0D),
            List.of(new Currency("credits","Cr",0x55D3BC)),false));
    }
    static void openLowFundsModal(Screen screen) {
        // Normal public mouse event on the first real grid tile (pinned 1.8.4 layout).
        int x=(screen.width-302)/2+52+11+16;
        int y=(screen.height-200)/2+40+15;
        int before=screen.children().size();
        screen.mouseClicked(com.qshop.client.QShopScreenInput.toScaledCoordinate(x,screen.width),
            com.qshop.client.QShopScreenInput.toScaledCoordinate(y,screen.height),0);
        if(screen.children().size()<=before) throw new IllegalStateException("Q Shop trade modal did not open");
    }
}
