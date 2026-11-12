package dev.regnum.economy.mixin;

import dev.regnum.economy.market.Markets;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(SavedData.class)
abstract class SavedDataMixin {
    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("HEAD"), cancellable = true)
    private void regnumEconomy$preserveRecoveryJournal(File file, HolderLookup.Provider registries, CallbackInfo callback) {
        if (Markets.blocksSave(this)) callback.cancel();
    }
}
