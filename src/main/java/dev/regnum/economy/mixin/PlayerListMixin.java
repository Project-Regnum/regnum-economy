package dev.regnum.economy.mixin;

import dev.regnum.economy.money.WalletPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "load", at = @At("RETURN"), cancellable = true)
    private void regnumEconomy$recoverWallet(ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> callback) {
        callback.setReturnValue(WalletPersistence.recoverHost(player, callback.getReturnValue()));
    }
}
