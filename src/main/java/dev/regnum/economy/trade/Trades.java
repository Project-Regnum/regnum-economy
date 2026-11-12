package dev.regnum.economy.trade;

import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class Trades {
    private static final double MAX_DISTANCE_SQUARED = 8 * 8;
    private static final int REQUEST_SECONDS = 60;
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(REQUEST_SECONDS);
    private static final String MESSAGE = "message.regnum-economy.trade.";
    private static final Map<ServerPlayer, Request> REQUESTS = new IdentityHashMap<>();
    private static final Map<ServerPlayer, TradeSession> ACTIVE = new IdentityHashMap<>();

    private record Request(ServerPlayer recipient, long expiresAt) {
    }

    private Trades() {
    }

    public static void register() {
        TradeEscrow.register();
        TradeMenu.register();
        TradePackets.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("trade").then(argument("player", EntityArgument.player())
                        .executes(context -> {
                            var source = context.getSource();
                            return request(source, source.getPlayerOrException(), EntityArgument.getPlayer(context, "player"));
                        })));
            dispatcher.register(literal("accept").then(literal("trade")
                    .executes(context -> accept(context.getSource(), context.getSource().getPlayerOrException(), null))
                    .then(argument("player", EntityArgument.player()).executes(context ->
                            accept(context.getSource(), context.getSource().getPlayerOrException(),
                                    EntityArgument.getPlayer(context, "player"))))));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cancelFor(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ACTIVE.isEmpty()) {
                for (TradeSession session : new HashSet<>(ACTIVE.values())) {
                    session.tick();
                }
            }
            if (server.getTickCount() % 20 == 0) {
                expireRequests();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.isAlive() && !ACTIVE.containsKey(player)) {
                        TradeEscrow.restore(player);
                    }
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            REQUESTS.clear();
            for (TradeSession session : new HashSet<>(ACTIVE.values())) {
                session.cancel(null);
            }
        });
    }

    private static int request(CommandSourceStack source, ServerPlayer first, ServerPlayer second) {
        String blocked = blockedReason(first, second);
        if (blocked != null) {
            return fail(source, blocked);
        }
        expireRequests();
        if (REQUESTS.containsKey(first)) {
            return fail(source, "request_pending");
        }
        REQUESTS.put(first, new Request(second, System.nanoTime() + REQUEST_NANOS));
        source.sendSuccess(() -> Component.translatable(MESSAGE + "request_sent", second.getDisplayName(), REQUEST_SECONDS), false);
        String command = "/accept trade " + first.getGameProfile().getName();
        Component accept = Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
        second.sendSystemMessage(Component.translatable(MESSAGE + "request_received", first.getDisplayName(), REQUEST_SECONDS, accept));
        return 1;
    }

    private static int accept(CommandSourceStack source, ServerPlayer second, ServerPlayer first) {
        expireRequests();
        if (first == null) {
            for (var entry : REQUESTS.entrySet()) {
                if (entry.getValue().recipient() == second) {
                    if (first != null) {
                        return fail(source, "request_choose");
                    }
                    first = entry.getKey();
                }
            }
        }
        Request request = REQUESTS.get(first);
        if (request == null || request.recipient() != second) {
            return fail(source, "request_missing");
        }
        String blocked = blockedReason(first, second);
        if (blocked != null) {
            return fail(source, blocked);
        }
        if (!TradeEscrow.restore(first) || !TradeEscrow.restore(second)) {
            return fail(source, "refund_pending");
        }
        REQUESTS.remove(first);
        var session = new TradeSession(first, second);
        ACTIVE.put(first, session);
        ACTIVE.put(second, session);
        if (!session.open()) {
            return 0;
        }
        discardRequests(first);
        discardRequests(second);
        return 1;
    }

    private static String blockedReason(ServerPlayer first, ServerPlayer second) {
        if (first == second) {
            return "self";
        }
        if (!bothAuthenticated(first, second)) {
            return "login";
        }
        if (!inRange(first, second)) {
            return "distance";
        }
        if (ACTIVE.containsKey(first) || ACTIVE.containsKey(second)
                || first.containerMenu != first.inventoryMenu || second.containerMenu != second.inventoryMenu) {
            return "busy";
        }
        return null;
    }

    private static void expireRequests() {
        long now = System.nanoTime();
        REQUESTS.entrySet().removeIf(entry -> {
            Request request = entry.getValue();
            if (now - request.expiresAt() < 0) {
                return false;
            }
            ServerPlayer first = entry.getKey();
            ServerPlayer second = request.recipient();
            first.sendSystemMessage(Component.translatable(MESSAGE + "request_expired", second.getDisplayName()));
            second.sendSystemMessage(Component.translatable(MESSAGE + "request_expired", first.getDisplayName()));
            return true;
        });
    }

    private static void discardRequests(ServerPlayer player) {
        REQUESTS.remove(player);
        REQUESTS.values().removeIf(request -> request.recipient() == player);
    }

    private static int fail(CommandSourceStack source, String key) {
        source.sendFailure(Component.translatable(MESSAGE + key));
        return 0;
    }

    static boolean bothAuthenticated(ServerPlayer first, ServerPlayer second) {
        return AuthAccess.permits(first) && AuthAccess.permits(second);
    }

    static boolean inRange(ServerPlayer first, ServerPlayer second) {
        return first.isAlive() && second.isAlive() && !first.isSpectator() && !second.isSpectator()
                && first.level() == second.level() && first.distanceToSqr(second) <= MAX_DISTANCE_SQUARED;
    }

    public static void cancelFor(ServerPlayer player) {
        discardRequests(player);
        var session = ACTIVE.get(player);
        if (session != null) {
            session.cancel(null);
        }
    }

    static void forget(TradeSession session) {
        ACTIVE.remove(session.first.player, session);
        ACTIVE.remove(session.second.player, session);
    }
}
