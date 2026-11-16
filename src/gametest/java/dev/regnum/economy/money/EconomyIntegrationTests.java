package dev.regnum.economy.money;

import dev.regnum.economy.integration.NationsAccess;
import dev.sakhno.regnum.nations.api.v1.*;
import dev.sakhno.regnum.nations.block.*;
import dev.sakhno.regnum.nations.kingdom.KingdomService;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

public final class EconomyIntegrationTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,batch="economy_integration")
    public void nationsOwnershipAndFinancialPolicyAreResolvedThroughPublicApi(GameTestHelper h) throws Exception {
        var owner=dev.regnum.economy.TestPlayers.create(h); var outsider=dev.regnum.economy.TestPlayers.create(h);
        var level=h.getLevel(); var pos=new BlockPos(8100008,64,8100008); owner.setPos(Vec3.atCenterOf(pos));
        level.setBlockAndUpdate(pos,ModBlocks.CITY_OBELISK.defaultBlockState());
        var service=KingdomService.get(level.getServer()); var block=(CityObeliskBlockEntity)level.getBlockEntity(pos);
        service.cities().select(owner,pos); var city=service.cities().found(owner,block.getObeliskId(),"Economy Integration",2);
        var ref=EntityRef.city(city.id()); var api=NationsApi.get(level.getServer());
        h.assertTrue(NationsAccess.installed()&&NationsAccess.cityAt(level,pos).id().equals(city.id())
                &&api.claims(city.id()).value().orElseThrow().size()==1,"Economy resolves the exact Nations city and claims");
        h.assertTrue(NationsAccess.permits(owner,ref,EconomyPermission.ECONOMY_WITHDRAW_TREASURY)
                &&!NationsAccess.permits(outsider,ref,EconomyPermission.ECONOMY_WITHDRAW_TREASURY),"City leadership grants financial authority only to its owner");
        service.citizenship().invite(owner,outsider); service.citizenship().accept(outsider,city.id());
        h.assertTrue(!NationsAccess.permits(outsider,ref,EconomyPermission.ECONOMY_VIEW_TREASURY),"Citizens receive no financial defaults");
        h.assertTrue(api.setRolePermissions(owner,ref,"CITIZEN",Set.of(EconomyPermission.ECONOMY_VIEW_TREASURY)).valid(),"Owner can explicitly authorize viewing");
        h.assertTrue(NationsAccess.permits(outsider,ref,EconomyPermission.ECONOMY_VIEW_TREASURY)
                &&!NationsAccess.permits(outsider,ref,EconomyPermission.ECONOMY_WITHDRAW_TREASURY),"Policy grants only the selected capability");
        service.citizenship().kick(owner,outsider.getUUID());
        h.assertTrue(!NationsAccess.permits(outsider,ref,EconomyPermission.ECONOMY_VIEW_TREASURY),"Removal immediately revokes Economy access"); h.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,batch="economy_integration")
    public void transfersRollbackRejectOverflowAndPersistBalances(GameTestHelper h) throws Exception {
        var from=dev.regnum.economy.TestPlayers.create(h); var to=dev.regnum.economy.TestPlayers.create(h);
        try (var operation=WalletPersistence.begin(from,to)) { CoinBalances.set(from,1000); CoinBalances.set(to,100); h.assertTrue(operation.commit(),"Initial balances commit durably"); }
        try (var operation=WalletPersistence.begin(from,to)) { CoinBalances.set(from,1); CoinBalances.set(to,1099); }
        h.assertTrue(CoinBalances.get(from)==1000&&CoinBalances.get(to)==100,"Uncommitted operation restores both wallets");
        h.assertTrue(CoinBalances.transfer(from,to,250)==CoinBalances.TransferResult.SUCCESS
                &&CoinBalances.get(from)==750&&CoinBalances.get(to)==350,"Successful transfer conserves copper");
        h.assertTrue(CoinBalances.transfer(from,to,751)==CoinBalances.TransferResult.INSUFFICIENT_FUNDS
                &&CoinBalances.transfer(from,from,1)==CoinBalances.TransferResult.SAME_PLAYER
                &&CoinBalances.get(from)==750&&CoinBalances.get(to)==350,"Rejected transfers preserve both balances");
        try (var operation=WalletPersistence.begin(to)) { CoinBalances.set(to,Long.MAX_VALUE); h.assertTrue(operation.commit(),"Maximum balance persists"); }
        h.assertTrue(CoinBalances.transfer(from,to,1)==CoinBalances.TransferResult.RECIPIENT_LIMIT&&CoinBalances.get(from)==750&&CoinBalances.get(to)==Long.MAX_VALUE,"Overflow cannot debit sender");
        var disk=h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("playerdata").resolve(from.getStringUUID()+".dat");
        var saved=net.minecraft.nbt.NbtIo.readCompressed(disk,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var restored=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),from.getGameProfile(),net.minecraft.server.level.ClientInformation.createDefault());
        // PlayerList caches advancements by UUID; the replacement must also have a listener.
        dev.regnum.economy.TestPlayers.connect(restored);
        h.assertTrue(restored.getAdvancements()==from.getAdvancements(),"Same-UUID reload uses the server’s cached advancement tracker");
        restored.load(saved); h.assertTrue(CoinBalances.get(restored)==750,"Committed player file restores the same wallet attachment"); h.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,batch="economy_integration")
    public void conversionConservesValueAndFullInventoryFailurePreservesWallet(GameTestHelper h) throws Exception {
        var player=dev.regnum.economy.TestPlayers.create(h);
        player.containerMenu=WalletMenu.type().create(7,player.getInventory());
        player.getInventory().setItem(0,new ItemStack(Denomination.GOLD.item(),2));
        h.assertTrue(CoinConversion.deposit(player,0,false)==CoinConversion.Result.DEPOSITED
                &&CoinBalances.get(player)==20000&&player.getInventory().getItem(0).isEmpty(),"Deposit consumes physical coins for their exact value");
        h.assertTrue(CoinConversion.withdraw(player,101)==CoinConversion.Result.WITHDRAWN&&CoinBalances.get(player)==19899,"Withdrawal debits the exact delivered copper");
        for (int i=0;i<player.getInventory().items.size();i++) player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        var before=player.getInventory().save(new net.minecraft.nbt.ListTag());
        h.assertTrue(CoinConversion.withdraw(player,1)==CoinConversion.Result.SPACE&&CoinBalances.get(player)==19899
                &&before.equals(player.getInventory().save(new net.minecraft.nbt.ListTag())),"Full inventory rejects without changing money or items");
        player.containerMenu=player.inventoryMenu;
        h.assertTrue(CoinConversion.withdraw(player,1)==CoinConversion.Result.INVALID&&CoinBalances.get(player)==19899,"Conversion requires its authorized wallet menu"); h.succeed();
    }
}
