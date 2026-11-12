package dev.regnum.economy.money;

import dev.regnum.economy.RegnumEconomy;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public final class WalletPackets {
    private WalletPackets() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.playC2S().register(Action.TYPE, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE, (payload, context) -> WalletMenu.open(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof WalletMenu menu) {
                menu.handle(context.player(), payload);
            }
        });
    }

    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(RegnumEconomy.id("wallet_open"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC =
                StreamCodec.of((buf, value) -> {}, buf -> new Open());

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    public record Action(int menuId, UUID session, long revision, int action, int slot, long amount)
            implements CustomPacketPayload {
        public static final int DEPOSIT_STACK = 0;
        public static final int DEPOSIT_ALL = 1;
        public static final int WITHDRAW = 2;
        public static final Type<Action> TYPE = new Type<>(RegnumEconomy.id("wallet_action"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.menuId);
            buf.writeUUID(value.session);
            buf.writeVarLong(value.revision);
            buf.writeVarInt(value.action);
            buf.writeVarInt(value.slot);
            buf.writeVarLong(value.amount);
        }, buf -> new Action(buf.readVarInt(), buf.readUUID(), buf.readVarLong(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarLong()));

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    public record State(int menuId, UUID session, long revision, long balance, int result)
            implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(RegnumEconomy.id("wallet_state"));
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.menuId);
            buf.writeUUID(value.session);
            buf.writeVarLong(value.revision);
            buf.writeVarLong(value.balance);
            buf.writeVarInt(value.result);
        }, buf -> new State(buf.readVarInt(), buf.readUUID(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt()));

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }
}
