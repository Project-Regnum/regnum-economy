package dev.regnum.economy.money;

import com.mojang.brigadier.arguments.LongArgumentType;
import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CoinCommands {
    private CoinCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("pay")
                    .then(argument("player", EntityArgument.player())
                            .then(argument("amount", LongArgumentType.longArg(1))
                                    .executes(context -> pay(context.getSource(),
                                            EntityArgument.getPlayer(context, "player"),
                                            LongArgumentType.getLong(context, "amount"))))));

            dispatcher.register(literal("economy").requires(source -> source.hasPermission(2))
                    .then(literal("grant")
                            .then(argument("player", EntityArgument.player())
                                    .then(argument("amount", LongArgumentType.longArg(1))
                                            .executes(context -> grant(context.getSource(),
                                                    EntityArgument.getPlayer(context, "player"),
                                                    LongArgumentType.getLong(context, "amount")))))));
        });
    }

    private static int pay(CommandSourceStack source, ServerPlayer recipient, long amount)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        if (!AuthAccess.permits(sender) || !AuthAccess.permits(recipient)) {
            source.sendFailure(Component.translatable("message.regnum-economy.pay.login"));
            return 0;
        }
        var result = CoinBalances.transfer(sender, recipient, amount);
        if (result != CoinBalances.TransferResult.SUCCESS) {
            String key = switch (result) {
                case SAME_PLAYER -> "self";
                case INSUFFICIENT_FUNDS -> "insufficient";
                case RECIPIENT_LIMIT -> "recipient_limit";
                case SUCCESS -> throw new IllegalStateException("Successful payment cannot fail");
            };
            source.sendFailure(Component.translatable("message.regnum-economy.pay." + key));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("message.regnum-economy.pay.sent", amount, recipient.getDisplayName()), false);
        recipient.sendSystemMessage(Component.translatable("message.regnum-economy.pay.received", amount, sender.getDisplayName()));
        return 1;
    }

    private static int grant(CommandSourceStack source, ServerPlayer recipient, long amount) {
        if (!CoinBalances.credit(recipient, amount)) {
            source.sendFailure(Component.translatable("message.regnum-economy.pay.recipient_limit"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("message.regnum-economy.grant.sent", amount, recipient.getDisplayName()), false);
        if (source.getEntity() != recipient) {
            recipient.sendSystemMessage(Component.translatable("message.regnum-economy.grant.received", amount));
        }
        return 1;
    }
}
