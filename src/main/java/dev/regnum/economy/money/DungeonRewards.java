package dev.regnum.economy.money;

import com.mojang.serialization.Codec;
import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Trusted server integration: receipt, wallet and inventory share the wallet recovery journal. */
public final class DungeonRewards {
    private static final AttachmentType<List<String>> RECEIPTS=AttachmentRegistry.<List<String>>builder()
            .persistent(Codec.STRING.listOf()).copyOnDeath().buildAndRegister(RegnumEconomy.id("dungeon_receipts"));
    private DungeonRewards() {}
    public static void register() {
        WalletPersistence.registerPlayerState(player->{var before=player.getAttached(RECEIPTS);return ()->player.setAttached(RECEIPTS,before);});
    }
    public static boolean grant(ServerPlayer player,String receipt,long coins,List<ItemStack> loot) {
        if(!player.getServer().isSameThread()||!AuthAccess.permits(player)||!WalletPersistence.available(player.getServer())
                ||player.hasDisconnected()||!player.isAlive()||player.isSpectator()||coins<0||coins>100_000_000||receipt.length()>100
                ||loot.size()>16||player.containerMenu!=player.inventoryMenu||!player.containerMenu.getCarried().isEmpty())return false;
        var previous=player.getAttachedOrElse(RECEIPTS,List.of());if(previous.contains(receipt))return true;
        long balance=CoinBalances.get(player);if(coins>Long.MAX_VALUE-balance)return false;
        try(var operation=WalletPersistence.begin(player)) {
            // Fail atomically if inventory is full: no loose drops outside the journal.
            for(ItemStack stack:loot)if(!player.getInventory().add(stack.copy()))return false;
            var next=new ArrayList<>(previous);next.add(receipt);player.setAttached(RECEIPTS,List.copyOf(next));
            CoinBalances.set(player,balance+coins);return operation.commit();
        }
    }
}
