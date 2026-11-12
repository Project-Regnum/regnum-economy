package dev.regnum.economy.money;

import com.mojang.brigadier.arguments.LongArgumentType;
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
            dispatcher.register(literal("wallet").executes(context ->
                    WalletMenu.open(context.getSource().getPlayerOrException()) ? 1 : 0));

            dispatcher.register(literal("economy").requires(source -> source.hasPermission(2))
                    .then(literal("grant")
                            .then(argument("player", EntityArgument.player())
                                    .then(argument("amount", LongArgumentType.longArg(1))
                                            .executes(context -> grant(context.getSource(),
                                                    EntityArgument.getPlayer(context, "player"),
                                                    LongArgumentType.getLong(context, "amount")))))));
        });
    }

    private static int grant(CommandSourceStack source, ServerPlayer recipient, long amount) {
        String failure = CoinIssuance.issue(source, recipient, amount);
        if (failure != null) {
            source.sendFailure(Component.translatable("message.regnum-economy.wallet." + failure));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("message.regnum-economy.grant.sent", amount, recipient.getDisplayName()), false);
        if (source.getEntity() != recipient) {
            recipient.sendSystemMessage(Component.translatable("message.regnum-economy.grant.received", amount));
        }
        return 1;
    }
}
