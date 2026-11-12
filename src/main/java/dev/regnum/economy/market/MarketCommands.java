package dev.regnum.economy.market;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class MarketCommands {
    private MarketCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                literal("market").executes(ctx -> Markets.list(player(ctx), 1, false))
                        .then(literal("list").executes(ctx -> Markets.list(player(ctx), 1, false))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> Markets.list(player(ctx), integer(ctx, "page"), false))))
                        .then(literal("mine").executes(ctx -> Markets.list(player(ctx), 1, true))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> Markets.list(player(ctx), integer(ctx, "page"), true))))
                        .then(literal("register").executes(ctx -> Markets.registerMarket(player(ctx), null))
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> Markets.registerMarket(player(ctx), StringArgumentType.getString(ctx, "name")))))
                        .then(literal("close").executes(ctx -> Markets.closeMarket(player(ctx))))
                        .then(literal("sell").then(argument("quantity", IntegerArgumentType.integer(1, MarketData.MAX_QUANTITY))
                                .then(argument("price", LongArgumentType.longArg(1)).executes(ctx -> {
                                    ServerPlayer seller = player(ctx);
                                    return Markets.post(seller, MarketData.Kind.SELL, seller.getMainHandItem(),
                                            integer(ctx, "quantity"), LongArgumentType.getLong(ctx, "price"));
                                }))))
                        .then(literal("order").then(argument("item", ItemArgument.item(registries))
                                .then(argument("quantity", IntegerArgumentType.integer(1, MarketData.MAX_QUANTITY))
                                        .then(argument("price", LongArgumentType.longArg(1)).executes(ctx ->
                                                Markets.post(player(ctx), MarketData.Kind.BUY,
                                                        ItemArgument.getItem(ctx, "item").createItemStack(1, false),
                                                        integer(ctx, "quantity"), LongArgumentType.getLong(ctx, "price")))))))
                        .then(fill("buy", MarketData.Kind.SELL))
                        .then(fill("deliver", MarketData.Kind.BUY))
                        .then(literal("cancel").then(argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> Markets.cancel(player(ctx), integer(ctx, "id")))))
                        .then(literal("claim").executes(ctx -> Markets.claim(player(ctx))))
                        .then(literal("tax").then(argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> Markets.treasury(player(ctx), integer(ctx, "percent"), 0))))
                        .then(literal("treasury").executes(ctx -> Markets.treasury(player(ctx), null, 0))
                                .then(literal("withdraw").then(argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> Markets.treasury(player(ctx), null, LongArgumentType.getLong(ctx, "amount"))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> fill(String verb, MarketData.Kind kind) {
        return literal(verb).then(argument("id", IntegerArgumentType.integer(1))
                .then(argument("quantity", IntegerArgumentType.integer(1, MarketData.MAX_QUANTITY)).executes(ctx ->
                        Markets.fulfill(player(ctx), kind, integer(ctx, "id"), integer(ctx, "quantity")))));
    }

    private static int integer(CommandContext<CommandSourceStack> ctx, String key) {
        return IntegerArgumentType.getInteger(ctx, key);
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }
}
