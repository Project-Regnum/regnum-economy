package dev.regnum.economy.mixin;

import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.trade.Trades;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void regnumEconomy$cancelTrade(DamageSource source, CallbackInfo ci) {
        Trades.cancelFor((ServerPlayer) (Object) this);
    }

    @Inject(method = "die", at = @At("TAIL"))
    private void regnumEconomy$rewardKiller(DamageSource source, CallbackInfo ci) {
        CoinBalances.rewardKiller((ServerPlayer) (Object) this);
    }
}
