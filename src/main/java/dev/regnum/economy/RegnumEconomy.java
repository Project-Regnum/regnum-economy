package dev.regnum.economy;

import dev.regnum.economy.market.Markets;
import dev.regnum.economy.money.BalancePayload;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.CoinCommands;
import dev.regnum.economy.money.Denomination;
import dev.regnum.economy.trade.Trades;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;

public class RegnumEconomy implements ModInitializer {
    public static final String MOD_ID = "regnum-economy";

    @Override
    public void onInitialize() {
        Denomination.registerItems();
        BalancePayload.register();
        CoinBalances.register();
        CoinCommands.register();
        Trades.register();
        Markets.register();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
