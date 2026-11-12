package dev.regnum.economy.market;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.integration.NationsAccess;
import dev.regnum.economy.money.CoinBalances;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import dev.sakhno.regnum.nations.api.v1.NationsApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TreasuryPackets {
    public static final int OPEN = 0, DEPOSIT = 1, WITHDRAW = 2;
    private static final UUID NONE = new UUID(0, 0);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final ServerPlayer player;
        final UUID screen;
        final UUID token = UUID.randomUUID();
        final EntityRef entity;
        long revision;

        Session(ServerPlayer player, UUID screen, EntityRef entity) {
            this.player = player;
            this.screen = screen;
            this.entity = entity;
        }
    }

    private TreasuryPackets() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) ->
                context.server().execute(() -> handle(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
    }

    private static void handle(ServerPlayer player, Request request) {
        if (!player.isAlive() || player.isSpectator() || player.hasDisconnected() || !AuthAccess.permits(player)) {
            reply(player, request, null, -1, "permission");
            return;
        }
        var api = NationsApi.get(player.getServer());
        // Never treat the Nations client context, a historical founder or a parent government as authority.
        if (!api.authorize(player, request.entity(), EconomyPermission.ECONOMY_VIEW_TREASURY).valid()) {
            SESSIONS.remove(player.getUUID());
            reply(player, request, null, -1, "permission");
            return;
        }
        MarketData data = MarketData.get(player.getServer());
        if (!data.available()) {
            reply(player, request, null, -1, "unavailable");
            return;
        }
        Session session;
        String result = "ready";
        if (request.action() == OPEN) {
            session = new Session(player, request.screen(), request.entity());
            SESSIONS.put(player.getUUID(), session);
        } else {
            session = SESSIONS.get(player.getUUID());
            if (session == null || session.player != player || !session.screen.equals(request.screen())
                    || !session.token.equals(request.session()) || !session.entity.equals(request.entity())
                    || session.revision != request.revision() || session.revision == Long.MAX_VALUE) {
                return;
            }
            session.revision++;
            EconomyPermission permission = request.action() == DEPOSIT ? EconomyPermission.ECONOMY_DEPOSIT_TREASURY
                    : EconomyPermission.ECONOMY_WITHDRAW_TREASURY;
            if ((request.action() != DEPOSIT && request.action() != WITHDRAW) || request.amount() < 1
                    || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
                result = "invalid";
            } else if (!api.authorize(player, session.entity, permission).valid()) {
                result = "permission";
            } else {
                long amount = request.amount();
                String[] outcome = {"ready"};
                int completed = data.transaction(player, () -> {
                    var treasury = data.treasury(session.entity);
                    if (request.action() == DEPOSIT) {
                        if (amount > Long.MAX_VALUE - treasury.coins) outcome[0] = "capacity";
                        else if (!CoinBalances.debit(player, amount)) outcome[0] = "funds";
                        else { treasury.coins += amount; data.setDirty(); outcome[0] = "deposited"; return 1; }
                    } else {
                        if (amount > treasury.coins) outcome[0] = "funds";
                        else if (!CoinBalances.credit(player, amount)) outcome[0] = "capacity";
                        else { treasury.coins -= amount; data.setDirty(); outcome[0] = "withdrawn"; return 1; }
                    }
                    return 0;
                });
                result = completed == 0 && (outcome[0].equals("deposited") || outcome[0].equals("withdrawn"))
                        ? "save_failed" : outcome[0];
            }
        }
        var treasury = MarketData.get(player.getServer()).treasuries.get(request.entity());
        reply(player, request, session, treasury == null ? 0 : treasury.coins, result);
    }

    private static void reply(ServerPlayer player, Request request, Session session, long balance, String result) {
        if (ServerPlayNetworking.canSend(player, State.TYPE)) {
            boolean deposit = session != null && NationsAccess.permits(player, request.entity(), EconomyPermission.ECONOMY_DEPOSIT_TREASURY);
            boolean withdraw = session != null && NationsAccess.permits(player, request.entity(), EconomyPermission.ECONOMY_WITHDRAW_TREASURY);
            ServerPlayNetworking.send(player, new State(request.screen(), session == null ? NONE : session.token,
                    session == null ? -1 : session.revision, balance, AuthAccess.permits(player) ? CoinBalances.get(player) : -1,
                    deposit, withdraw, result));
        }
    }

    public record Request(UUID screen, UUID session, long revision, int action, EntityRef entity, long amount)
            implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(RegnumEconomy.id("treasury_request"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.screen); buf.writeUUID(value.session); buf.writeVarLong(value.revision);
            buf.writeVarInt(value.action); buf.writeBoolean(value.entity.scope() == EntityRef.Scope.KINGDOM);
            buf.writeUUID(value.entity.id()); buf.writeVarLong(value.amount);
        }, buf -> new Request(buf.readUUID(), buf.readUUID(), buf.readVarLong(), buf.readVarInt(),
                new EntityRef(buf.readBoolean() ? EntityRef.Scope.KINGDOM : EntityRef.Scope.CITY, buf.readUUID()), buf.readVarLong()));

        @Override public Type<Request> type() { return TYPE; }
    }

    public record State(UUID screen, UUID session, long revision, long balance, long wallet,
                        boolean deposit, boolean withdraw, String result) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(RegnumEconomy.id("treasury_state"));
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.screen); buf.writeUUID(value.session); buf.writeVarLong(value.revision);
            buf.writeVarLong(value.balance); buf.writeVarLong(value.wallet); buf.writeBoolean(value.deposit);
            buf.writeBoolean(value.withdraw); buf.writeUtf(value.result, 32);
        }, buf -> new State(buf.readUUID(), buf.readUUID(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf(32)));

        @Override public Type<State> type() { return TYPE; }
    }
}
