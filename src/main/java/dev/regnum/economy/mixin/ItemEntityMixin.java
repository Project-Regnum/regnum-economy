package dev.regnum.economy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.Denomination;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean regnumEconomy$redeemLegacyCoin(Inventory inventory, ItemStack stack, Operation<Boolean> original) {
        if (inventory.player instanceof ServerPlayer player && Denomination.of(stack) != null) {
            return CoinBalances.redeemLegacyStack(player, stack);
        }
        return original.call(inventory, stack);
    }
}
