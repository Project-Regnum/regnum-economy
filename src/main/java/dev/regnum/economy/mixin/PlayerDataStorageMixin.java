package dev.regnum.economy.mixin;

import dev.regnum.economy.money.WalletPersistence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void regnumEconomy$preserveRecoveryJournal(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer && WalletPersistence.blocksPlayerSave(serverPlayer)) {
            callback.cancel();
        }
    }
}
