package dev.regnum.economy.client;

import dev.regnum.economy.money.BalancePayload;
import dev.regnum.economy.money.WalletMenu;
import dev.regnum.economy.money.WalletPackets;
import dev.regnum.economy.trade.TradeMenu;
import dev.regnum.economy.trade.TradePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.MenuScreens;

public class RegnumEconomyClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(TradeMenu.type(), TradeScreen::new);
        MenuScreens.register(WalletMenu.type(), WalletScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(WalletPackets.State.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof WalletMenu menu) {
                menu.setState(payload);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(TradePackets.State.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof TradeMenu menu) {
                menu.setState(payload);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(BalancePayload.TYPE, (payload, context) ->
                BalanceSlot.setBalance(payload.amount()));
        ClientPlayConnectionEvents.INIT.register((handler, client) -> BalanceSlot.setBalance(-1));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BalanceSlot.setBalance(-1));
        ScreenEvents.AFTER_INIT.register(BalanceSlot::addToInventory);
    }
}
