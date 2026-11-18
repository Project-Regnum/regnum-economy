package dev.regnum.economy.mixin;

import dev.regnum.economy.npc.RegnumNpc;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
abstract class MobInteractionMixin {
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void regnumEconomy$interactWithNpc(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        // Vanilla processes name tags and spawn eggs before the Mob interaction hook.
        if ((Object) this instanceof RegnumNpc npc) {
            callback.setReturnValue(npc.interactWithNpc(player, hand));
        }
    }
}
