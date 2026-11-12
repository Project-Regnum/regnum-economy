package dev.regnum.economy.client;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.market.TreasuryPackets;
import dev.sakhno.regnum.nations.api.client.v1.EconomyPanel;
import dev.sakhno.regnum.nations.api.client.v1.EconomyUi;
import dev.sakhno.regnum.nations.api.client.v1.NationsEconomyClient;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

import java.util.Set;

public final class NationsEconomyIntegration implements NationsEconomyClient {
    @Override
    public void initialize() {
        var provider = RegnumEconomy.id("economy");
        for (EntityRef.Scope scope : EntityRef.Scope.values()) {
            String name = scope == EntityRef.Scope.CITY ? "city" : "kingdom";
            EconomyUi.register(provider, new EconomyPanel(RegnumEconomy.id(name + "_treasury"),
                    EconomyPanel.Category.TREASURY, Component.translatable("screen.regnum-economy.treasury." + name),
                    Set.of(scope), Set.of(EconomyPermission.ECONOMY_VIEW_TREASURY), TreasuryScreen::new));
        }
        ClientPlayNetworking.registerGlobalReceiver(TreasuryPackets.State.TYPE, (payload, context) -> {
            if (context.client().screen instanceof TreasuryScreen screen) {
                screen.accept(payload);
            }
        });
    }
}
