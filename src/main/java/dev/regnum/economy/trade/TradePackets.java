package dev.regnum.economy.trade;

import dev.regnum.economy.RegnumEconomy;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public final class TradePackets {
    private TradePackets() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Action.TYPE, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, context) -> {
            var player = context.player();
            if (player.containerMenu instanceof TradeMenu menu && menu.containerId == payload.menuId()) {
                menu.handle(player, payload);
            }
        });
    }

    public record Action(int menuId, UUID session, long revision, int action, long coins) implements CustomPacketPayload {
        public static final int OFFER_COINS = 0;
        public static final int CONFIRM = 1;
        public static final int CANCEL = 2;

        public static final Type<Action> TYPE = new Type<>(RegnumEconomy.id("trade_action_v2"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.menuId);
                    buf.writeUUID(value.session);
                    buf.writeVarLong(value.revision);
                    buf.writeVarInt(value.action);
                    buf.writeVarLong(value.coins);
                },
                buf -> new Action(buf.readVarInt(), buf.readUUID(), buf.readVarLong(), buf.readVarInt(), buf.readVarLong()));

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    public record State(int menuId, UUID session, long revision, long ownCoins, long otherCoins, long available,
                        boolean ownConfirmed, boolean otherConfirmed, int seconds, int issue) implements CustomPacketPayload {
        public static final int INVENTORY_SPACE = 1;
        public static final int COIN_BALANCE = 2;
        public static final int SAVE_FAILED = 3;

        public static final Type<State> TYPE = new Type<>(RegnumEconomy.id("trade_state_v2"));
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.menuId);
                    buf.writeUUID(value.session);
                    buf.writeVarLong(value.revision);
                    buf.writeVarLong(value.ownCoins);
                    buf.writeVarLong(value.otherCoins);
                    buf.writeVarLong(value.available);
                    buf.writeBoolean(value.ownConfirmed);
                    buf.writeBoolean(value.otherConfirmed);
                    buf.writeVarInt(value.seconds);
                    buf.writeVarInt(value.issue);
                },
                buf -> new State(buf.readVarInt(), buf.readUUID(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                        buf.readVarLong(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }
}
