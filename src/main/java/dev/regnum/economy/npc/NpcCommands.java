package dev.regnum.economy.npc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.regnum.economy.market.NpcMerchants;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static dev.regnum.economy.npc.Npcs.text;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class NpcCommands {
    private NpcCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var npc = literal("npc");
            npc.then(literal("create").then(argument("role", ResourceLocationArgument.id())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(NpcRole.ids(), builder))
                    .then(argument("name", StringArgumentType.greedyString()).executes(NpcCommands::create))));
            npc.then(literal("edit").then(target().executes(ctx -> withNpc(ctx, (player, target) ->
                    NpcInteractions.edit(player, target) ? 1 : fail(player, "result.permission")))));
            npc.then(literal("remove").then(target().executes(ctx -> withNpc(ctx, NpcCommands::remove))));
            npc.then(literal("info").then(target().executes(ctx -> withNpc(ctx, NpcCommands::info))));
            var offerTerms = argument("quantity", IntegerArgumentType.integer(1, NpcMerchants.LOT_LIMIT))
                    .then(argument("ask", LongArgumentType.longArg(0))
                            .then(argument("bid", LongArgumentType.longArg(0)).executes(NpcCommands::offer)));
            npc.then(literal("offer").then(target().then(offerTerms)));
            npc.then(literal("unlist").then(target().then(slot().executes(ctx -> withNpc(ctx, (player, target) ->
                    result(player, NpcMerchants.removeOffer(player, target, integer(ctx, "slot"))))))));
            npc.then(literal("stock").then(literal("deposit").then(stock(true))).then(literal("collect").then(stock(false))));
            npc.then(literal("funds").then(literal("deposit").then(funds(true))).then(literal("collect").then(funds(false))));
            npc.then(literal("settle").then(argument("npc", UuidArgument.uuid()).executes(ctx -> {
                var player = ctx.getSource().getPlayerOrException();
                return result(player, NpcMerchants.settle(player, UuidArgument.getUuid(ctx, "npc")));
            })));
            dispatcher.register(literal("economy").then(npc));
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> target() {
        return argument("npc", UuidArgument.uuid()).suggests((ctx, builder) -> {
            var player = ctx.getSource().getPlayer();
            if (player == null) return builder.buildFuture();
            return SharedSuggestionProvider.suggest(player.serverLevel()
                    .getEntitiesOfClass(RegnumNpc.class, player.getBoundingBox().inflate(6), npc -> Npcs.nearby(player, npc))
                    .stream().map(RegnumNpc::getStringUUID), builder);
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> slot() {
        return argument("slot", IntegerArgumentType.integer(1, NpcMerchants.OFFER_LIMIT));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> stock(boolean deposit) {
        return target().then(slot().then(argument("amount", IntegerArgumentType.integer(1, NpcMerchants.STOCK_LIMIT))
                .executes(ctx -> withNpc(ctx, (player, npc) -> result(player, NpcMerchants.transferStock(player, npc,
                        integer(ctx, "slot"), integer(ctx, "amount"), deposit))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> funds(boolean deposit) {
        return target().then(argument("amount", LongArgumentType.longArg(1)).executes(ctx ->
                withNpc(ctx, (player, npc) -> result(player, NpcMerchants.transferFunds(player, npc, coins(ctx, "amount"), deposit)))));
    }

    private static int offer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return withNpc(context, (player, target) -> result(player, NpcMerchants.offer(player, target,
                integer(context, "quantity"), coins(context, "ask"), coins(context, "bid"))));
    }

    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var role = NpcRole.find(ResourceLocationArgument.getId(context, "role"));
        var npc = Npcs.create(player, role, StringArgumentType.getString(context, "name"));
        if (npc == null) return fail(player, "create_failed");
        player.sendSystemMessage(text("created", npc.getName(), copyId(npc)));
        return 1;
    }

    private static int remove(ServerPlayer player, RegnumNpc npc) {
        if (!Npcs.canRemove(player, npc)) return fail(player, "result.permission");
        if (npc.role().usesMerchantStock()) {
            var result = NpcMerchants.clearForRemoval(player, npc);
            if (result != NpcMerchants.Result.SUCCESS) return result(player, result);
        }
        npc.discard();
        player.sendSystemMessage(text("removed", npc.getName()));
        return 1;
    }

    private static int info(ServerPlayer player, RegnumNpc npc) {
        player.sendSystemMessage(text("info", npc.getName(), npc.roleLabel(), copyId(npc)));
        if (npc.role().usesMerchantStock() && Npcs.canManageStock(player, npc)) {
            var catalog = NpcMerchants.catalog(player, npc);
            if (!catalog.available()) return fail(player, "result.unavailable");
            player.sendSystemMessage(text("info.funds", Long.toString(catalog.funds())));
            for (int i = 0; i < catalog.goods().size(); i++) {
                var quote = catalog.goods().get(i);
                player.sendSystemMessage(text("info.goods", i + 1, quote.item().getHoverName(), quote.quantity(), quote.stock(),
                        Long.toString(quote.ask()), Long.toString(quote.bid())));
            }
        }
        return 1;
    }

    private static Component copyId(RegnumNpc npc) {
        return Component.literal(npc.getStringUUID()).withStyle(style -> style.withClickEvent(
                new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, npc.getStringUUID())));
    }

    private interface NpcCommand {
        int run(ServerPlayer player, RegnumNpc npc);
    }

    private static int withNpc(CommandContext<CommandSourceStack> context, NpcCommand command) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!(player.serverLevel().getEntity(UuidArgument.getUuid(context, "npc")) instanceof RegnumNpc npc)
                || !Npcs.nearby(player, npc)) return fail(player, "result.unavailable");
        return command.run(player, npc);
    }

    private static int fail(ServerPlayer player, String key) {
        player.sendSystemMessage(text(key));
        return 0;
    }

    private static int result(ServerPlayer player, NpcMerchants.Result result) {
        player.sendSystemMessage(result.message());
        return result == NpcMerchants.Result.SUCCESS ? 1 : 0;
    }

    private static int integer(CommandContext<CommandSourceStack> context, String name) {
        return IntegerArgumentType.getInteger(context, name);
    }

    private static long coins(CommandContext<CommandSourceStack> context, String name) {
        return LongArgumentType.getLong(context, name);
    }
}
