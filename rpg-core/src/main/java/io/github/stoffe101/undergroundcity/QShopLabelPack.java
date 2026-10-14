package io.github.stoffe101.undergroundcity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Original text-only compatibility pack with explicit resource priority. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class QShopLabelPack {
    private QShopLabelPack() {}
    @SubscribeEvent public static void packs(AddPackFindersEvent event) {
        if(event.getPackType()!=PackType.CLIENT_RESOURCES || !ModList.get().isLoaded("qshop")) return;
        var root=ModList.get().getModFileById(UndergroundCity.MOD_ID).getFile().findResource("uc_qshop_labels");
        event.addRepositorySource(consumer->{
            Pack pack=Pack.readMetaAndCreate("underground_city:qshop_labels",Component.literal("Underground City shop labels"),
                true,id->new PathPackResources(id,root,true),PackType.CLIENT_RESOURCES,Pack.Position.TOP,PackSource.BUILT_IN);
            if(pack==null) throw new IllegalStateException("Original shop label resource pack is missing");
            consumer.accept(pack);
        });
    }
}
