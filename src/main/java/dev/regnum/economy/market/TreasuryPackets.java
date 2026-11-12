package dev.regnum.economy.market;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.WalletPersistence;
import dev.sakhno.regnum.nations.api.v1.ApiResult;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TreasuryPackets {
    public static final int OPEN = 0, DEPOSIT = 1, WITHDRAW = 2, CLOSE = 3;
    private static final UUID NONE = new UUID(0, 0);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final ServerPlayer player;
        final UUID screen;
        final UUID token = UUID.randomUUID();
        final EntityRef entity;
        long revision;
        long lastRequest;
        long accessed = System.nanoTime();
        String lastResult = "ready";

        Session(ServerPlayer player, Request request) {
            this.player = player;
            screen = request.screen();
            entity = request.entity();
            lastRequest = request.request();
        }

        boolean matches(ServerPlayer actor, Request request) {
            return player == actor && screen.equals(request.screen()) && token.equals(request.session())
                    && entity.equals(request.entity());
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
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.values().removeIf(session -> session.player.getServer() == server));
    }

    /** Political events invalidate advisory contexts and tokens; they never transfer account funds. */
    public static void invalidate(MinecraftServer server) {
        for (Iterator<Session> iterator = SESSIONS.values().iterator(); iterator.hasNext();) {
            Session session = iterator.next();
            if (session.player.getServer() != server) continue;
            iterator.remove();
            send(session.player, new State(session.screen, 0, NONE, -1, -1, -1, false, false, "stale_context"));
        }
    }

    private static void handle(ServerPlayer player, Request request) {
        if (request.request() < 1) return;
        Session session = SESSIONS.get(player.getUUID());
        if (request.action() == CLOSE) {
            if (session != null && session.matches(player, request)) SESSIONS.remove(player.getUUID());
            return;
        }
        if (!player.isAlive() || player.isSpectator() || player.hasDisconnected() || !AuthAccess.permits(player)
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            reject(player, request, "permission");
            return;
        }
        var api = NationsApi.get(player.getServer());
        var authority = api.authorize(player, request.entity(), EconomyPermission.ECONOMY_VIEW_TREASURY);
        if (!authority.valid()) {
            SESSIONS.remove(player.getUUID());
            reject(player, request, failure(authority.status()));
            return;
        }
        MarketData data = MarketData.get(player.getServer());
        if (!data.available() || !WalletPersistence.available(player.getServer())) {
            reject(player, request, "unavailable");
            return;
        }
        if (request.action() == OPEN) {
            session = new Session(player, request);
            SESSIONS.put(player.getUUID(), session);
        } else {
            if (session == null || !session.matches(player, request)
                    || System.nanoTime() - session.accessed > TimeUnit.SECONDS.toNanos(60)) {
                reject(player, request, "session_mismatch");
                return;
            }
            if (request.request() <= session.lastRequest) {
                reply(player, request.request(), session,
                        request.request() == session.lastRequest ? session.lastResult : "stale");
                return;
            }
            session.lastRequest = request.request();
            if (request.revision() != session.revision || session.revision == Long.MAX_VALUE) {
                session.lastResult = "stale";
                reply(player, request.request(), session, session.lastResult);
                return;
            }
            session.revision++;
            EconomyPermission permission = request.action() == DEPOSIT ? EconomyPermission.ECONOMY_DEPOSIT_TREASURY
                    : EconomyPermission.ECONOMY_WITHDRAW_TREASURY;
            if ((request.action() != DEPOSIT && request.action() != WITHDRAW) || request.amount() < 1
                    || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
                session.lastResult = "invalid";
            } else if (!api.authorize(player, session.entity, permission).valid()) {
                session.lastResult = "permission";
            } else {
                Session target = session;
                long amount = request.amount();
                String[] outcome = {"save_failed"};
                int completed = data.transaction(player, () -> {
                    var treasury = data.treasury(target.entity);
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
                session.lastResult = completed == 0 && (outcome[0].equals("deposited") || outcome[0].equals("withdrawn"))
                        ? "save_failed" : outcome[0];
            }
        }
        session.accessed = System.nanoTime();
        reply(player, request.request(), session, session.lastResult);
    }

    private static String failure(ApiResult.Status status) {
        return switch (status) {
            case MISSING -> "missing_entity";
            case UNAVAILABLE -> "unavailable";
            case INVALID -> "invalid";
            default -> "permission";
        };
    }

    private static void reply(ServerPlayer player, long request, Session session, String result) {
        var api = NationsApi.get(player.getServer());
        var authority = api.authorize(player, session.entity, EconomyPermission.ECONOMY_VIEW_TREASURY);
        if (!AuthAccess.permits(player) || !authority.valid()) {
            SESSIONS.remove(player.getUUID());
            send(player, new State(session.screen, request, NONE, -1, -1, -1, false, false,
                    authority.valid() ? "permission" : failure(authority.status())));
            return;
        }
        MarketData data = MarketData.get(player.getServer());
        if (!data.available()) {
            send(player, new State(session.screen, request, NONE, -1, -1, -1, false, false, "unavailable"));
            return;
        }
        var treasury = data.treasuries.get(session.entity);
        send(player, new State(session.screen, request, session.token, session.revision,
                treasury == null ? 0 : treasury.coins, CoinBalances.get(player),
                api.authorize(player, session.entity, EconomyPermission.ECONOMY_DEPOSIT_TREASURY).valid(),
                api.authorize(player, session.entity, EconomyPermission.ECONOMY_WITHDRAW_TREASURY).valid(), result));
    }

    private static void reject(ServerPlayer player, Request request, String result) {
        send(player, new State(request.screen(), request.request(), NONE, -1, -1, -1, false, false, result));
    }

    private static void send(ServerPlayer player, State state) {
        if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, State.TYPE)) ServerPlayNetworking.send(player, state);
    }

    public record Request(UUID screen, long request, UUID session, long revision, int action, EntityRef entity, long amount)
            implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(RegnumEconomy.id("treasury_request_v2"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.screen); buf.writeVarLong(value.request); buf.writeUUID(value.session); buf.writeVarLong(value.revision);
            buf.writeVarInt(value.action); buf.writeBoolean(value.entity.scope() == EntityRef.Scope.KINGDOM);
            buf.writeUUID(value.entity.id()); buf.writeVarLong(value.amount);
        }, buf -> new Request(buf.readUUID(), buf.readVarLong(), buf.readUUID(), buf.readVarLong(), buf.readVarInt(),
                new EntityRef(buf.readBoolean() ? EntityRef.Scope.KINGDOM : EntityRef.Scope.CITY, buf.readUUID()), buf.readVarLong()));

        @Override public Type<Request> type() { return TYPE; }
    }

    public record State(UUID screen, long request, UUID session, long revision, long balance, long wallet,
                        boolean deposit, boolean withdraw, String result) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(RegnumEconomy.id("treasury_state_v2"));
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.screen); buf.writeVarLong(value.request); buf.writeUUID(value.session); buf.writeVarLong(value.revision);
            buf.writeVarLong(value.balance); buf.writeVarLong(value.wallet); buf.writeBoolean(value.deposit);
            buf.writeBoolean(value.withdraw); buf.writeUtf(value.result, 32);
        }, buf -> new State(buf.readUUID(), buf.readVarLong(), buf.readUUID(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf(32)));

        @Override public Type<State> type() { return TYPE; }
    }
}
