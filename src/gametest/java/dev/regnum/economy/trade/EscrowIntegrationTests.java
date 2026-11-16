package dev.regnum.economy.trade;

import dev.regnum.economy.money.WalletPersistence;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;

public final class EscrowIntegrationTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,batch="economy_integration")
    public void escrowPersistsRollsBackAndReturnsExactlyOnce(GameTestHelper h) throws Exception {
        var player=dev.regnum.economy.TestPlayers.create(h); var offer=List.of(new ItemStack(Items.DIAMOND,3));
        try (var operation=WalletPersistence.begin(player)) { TradeEscrow.save(player,offer); h.assertTrue(operation.commit(),"Escrow receipt commits before release"); }
        try (var operation=WalletPersistence.begin(player)) { TradeEscrow.save(player,List.of()); }
        h.assertTrue(TradeEscrow.covers(player,offer,ItemStack.EMPTY),"Rollback restores the escrow receipt");
        var file=h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("playerdata").resolve(player.getStringUUID()+".dat");
        var data=net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap()); player.load(data);
        h.assertTrue(TradeEscrow.covers(player,offer,ItemStack.EMPTY),"Escrow receipt survives committed player-file reload");
        for (int i=0;i<player.getInventory().items.size();i++) player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        h.assertTrue(TradeEscrow.restore(player)==TradeEscrow.RestoreResult.NOTHING_RESTORED
                &&TradeEscrow.covers(player,offer,ItemStack.EMPTY),"Full inventory preserves the pending claim");
        player.getInventory().setItem(0,ItemStack.EMPTY);
        h.assertTrue(TradeEscrow.restore(player)==TradeEscrow.RestoreResult.COMPLETE
                &&player.getInventory().countItem(Items.DIAMOND)==3,"Available space returns exactly the escrowed items");
        h.assertTrue(TradeEscrow.restore(player)==TradeEscrow.RestoreResult.COMPLETE
                &&player.getInventory().countItem(Items.DIAMOND)==3,"Repeated recovery cannot duplicate items"); h.succeed();
    }
}
