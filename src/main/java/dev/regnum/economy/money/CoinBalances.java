package dev.regnum.economy.money;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CoinBalances {
    private record LegacyBalance(int copper, int silver, int gold) {
        private static final Codec<Integer> COUNT = Codec.intRange(0, Integer.MAX_VALUE);
        private static final Codec<LegacyBalance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                COUNT.fieldOf("copper").forGetter(LegacyBalance::copper),
                COUNT.fieldOf("silver").forGetter(LegacyBalance::silver),
                COUNT.fieldOf("gold").forGetter(LegacyBalance::gold)
        ).apply(instance, LegacyBalance::new));

        private long value() {
            return copper + (long) silver * Denomination.SILVER.copperValue()
                    + (long) gold * Denomination.GOLD.copperValue();
        }
    }

    private static final Codec<Long> NONNEGATIVE_BALANCE = Codec.LONG.validate(value -> value >= 0
            ? DataResult.success(value) : DataResult.error(() -> "Coin balance cannot be negative"));

    // Keep the attachment ID and read the old pouch format so existing worlds retain their balances.
    private static final AttachmentType<Long> BALANCE = AttachmentRegistry.<Long>builder()
            .persistent(Codec.withAlternative(NONNEGATIVE_BALANCE, LegacyBalance.CODEC, LegacyBalance::value))
            .copyOnDeath()
            .buildAndRegister(RegnumEconomy.id("pouch"));

    private static final GameRules.Key<GameRules.IntegerValue> KILL_REWARD_PERCENT = GameRuleRegistry.register(
            "regnumEconomyDeathDropPercent", GameRules.Category.DROPS, GameRuleFactory.createIntRule(10, 0, 100));

    private static final Map<UUID, ServerPlayer> SYNCED = new HashMap<>();

    public enum TransferResult {
        SUCCESS,
        SAME_PLAYER,
        INSUFFICIENT_FUNDS,
        RECIPIENT_LIMIT
    }

    private CoinBalances() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncAfterLogin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SYNCED.remove(handler.player.getUUID(), handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    syncAfterLogin(player);
                }
            }
        });
    }

    private static void syncAfterLogin(ServerPlayer player) {
        if (!AuthAccess.permits(player)) {
            if (SYNCED.remove(player.getUUID()) != null) {
                BalancePayload.hideFrom(player);
            }
            return;
        }
        if (SYNCED.put(player.getUUID(), player) == player) {
            return;
        }
        var inventory = player.getInventory();
        long balance = get(player);
        long redeemed = redeemLegacyStacks(inventory.items, Long.MAX_VALUE - balance);
        redeemed += redeemLegacyStacks(inventory.offhand, Long.MAX_VALUE - balance - redeemed);
        if (redeemed > 0) {
            player.setAttached(BALANCE, balance + redeemed);
            inventory.setChanged();
        }
        BalancePayload.sendTo(player);
    }

    private static long redeemLegacyStacks(Iterable<ItemStack> stacks, long capacity) {
        long redeemed = 0;
        for (ItemStack stack : stacks) {
            Denomination coin = Denomination.of(stack);
            if (coin != null) {
                long value = (long) coin.copperValue() * stack.getCount();
                if (value <= capacity - redeemed) {
                    redeemed += value;
                    stack.setCount(0);
                }
            }
        }
        return redeemed;
    }

    public static long get(ServerPlayer player) {
        return player.getAttachedOrElse(BALANCE, 0L);
    }

    public static boolean credit(ServerPlayer player, long amount) {
        long balance = get(player);
        if (amount < 1 || amount > Long.MAX_VALUE - balance) {
            return false;
        }
        player.setAttached(BALANCE, balance + amount);
        BalancePayload.sendTo(player);
        return true;
    }

    public static boolean debit(ServerPlayer player, long amount) {
        long balance = get(player);
        if (amount < 1 || amount > balance) {
            return false;
        }
        player.setAttached(BALANCE, balance - amount);
        BalancePayload.sendTo(player);
        return true;
    }

    public static TransferResult transfer(ServerPlayer from, ServerPlayer to, long amount) {
        if (from.getUUID().equals(to.getUUID())) {
            return TransferResult.SAME_PLAYER;
        }
        long available = get(from);
        if (amount < 1 || amount > available) {
            return TransferResult.INSUFFICIENT_FUNDS;
        }
        long recipientBalance = get(to);
        if (amount > Long.MAX_VALUE - recipientBalance) {
            return TransferResult.RECIPIENT_LIMIT;
        }

        from.setAttached(BALANCE, available - amount);
        to.setAttached(BALANCE, recipientBalance + amount);
        BalancePayload.sendTo(from);
        BalancePayload.sendTo(to);
        return TransferResult.SUCCESS;
    }

    public static boolean exchange(ServerPlayer first, long firstOffer, ServerPlayer second, long secondOffer) {
        long firstBalance = get(first);
        long secondBalance = get(second);
        if (first == second || firstOffer < 0 || secondOffer < 0
                || firstOffer > firstBalance || secondOffer > secondBalance) {
            return false;
        }
        long firstRemaining = firstBalance - firstOffer;
        long secondRemaining = secondBalance - secondOffer;
        if (secondOffer > Long.MAX_VALUE - firstRemaining || firstOffer > Long.MAX_VALUE - secondRemaining) {
            return false;
        }

        if (firstOffer != secondOffer) {
            first.setAttached(BALANCE, firstRemaining + secondOffer);
            second.setAttached(BALANCE, secondRemaining + firstOffer);
            BalancePayload.sendTo(first);
            BalancePayload.sendTo(second);
        }
        return true;
    }

    public static boolean redeemLegacyStack(ServerPlayer player, ItemStack stack) {
        Denomination coin = Denomination.of(stack);
        if (coin == null || !AuthAccess.permits(player) || !credit(player, (long) coin.copperValue() * stack.getCount())) {
            return false;
        }

        stack.setCount(0);
        return true;
    }

    public static void rewardKiller(ServerPlayer victim) {
        if (!(victim.getKillCredit() instanceof ServerPlayer killer) || killer == victim
                || !AuthAccess.permits(victim) || !AuthAccess.permits(killer)) {
            return;
        }

        int percent = victim.level().getGameRules().getInt(KILL_REWARD_PERCENT);
        long balance = get(victim);
        long reward = balance / 100 * percent + balance % 100 * percent / 100;
        reward = Math.min(reward, Long.MAX_VALUE - get(killer));
        if (reward > 0 && transfer(victim, killer, reward) == TransferResult.SUCCESS) {
            killer.sendSystemMessage(Component.translatable("message.regnum-economy.kill.reward", reward, victim.getDisplayName()));
            victim.sendSystemMessage(Component.translatable("message.regnum-economy.kill.loss", reward, killer.getDisplayName()));
        }
    }
}
