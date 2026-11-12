package dev.regnum.economy.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("leftPos")
    int regnumEconomy$getLeftPos();

    @Accessor("topPos")
    int regnumEconomy$getTopPos();

    @Accessor("imageWidth")
    int regnumEconomy$getImageWidth();
}
