package dev.regnum.economy.money;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record BalancePayload(long amount) implements CustomPacketPayload {
    public static final Type<BalancePayload> TYPE = new Type<>(RegnumEconomy.id("balance"));
    private static final StreamCodec<RegistryFriendlyByteBuf, BalancePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_LONG, BalancePayload::amount, BalancePayload::new);

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    static void sendTo(ServerPlayer player) {
        if (AuthAccess.permits(player)) {
            send(player, CoinBalances.get(player));
        }
    }

    static void hideFrom(ServerPlayer player) {
        send(player, -1);
    }

    private static void send(ServerPlayer player, long amount) {
        if (player.connection != null && ServerPlayNetworking.canSend(player, TYPE)) {
            ServerPlayNetworking.send(player, new BalancePayload(amount));
        }
    }

    @Override
    public Type<BalancePayload> type() {
        return TYPE;
    }
}
