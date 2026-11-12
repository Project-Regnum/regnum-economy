package dev.regnum.economy.money;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.regnum.economy.RegnumEconomy;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class CoinIssuance {
    private record Receipt(String actor, long time, long amount) {
        private static final Codec<Receipt> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("actor").forGetter(Receipt::actor),
                Codec.LONG.fieldOf("time").forGetter(Receipt::time),
                Codec.LONG.validate(amount -> amount > 0 ? DataResult.success(amount)
                        : DataResult.error(() -> "Invalid issuance amount")).fieldOf("amount").forGetter(Receipt::amount)
        ).apply(instance, Receipt::new));
    }

    private static final AttachmentType<List<Receipt>> RECEIPTS = AttachmentRegistry.<List<Receipt>>builder()
            .persistent(Receipt.CODEC.listOf()).copyOnDeath().buildAndRegister(RegnumEconomy.id("issuance"));
    private static final GameRules.Key<GameRules.BooleanValue> ENABLED = GameRuleRegistry.register(
            "regnumEconomyIssuanceEnabled", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(false));
    private static final GameRules.Key<GameRules.IntegerValue> PER_GRANT = GameRuleRegistry.register(
            "regnumEconomyIssuanceMaxGrant", GameRules.Category.MISC, GameRuleFactory.createIntRule(1_000_000, 0));
    private static final GameRules.Key<GameRules.IntegerValue> PER_DAY = GameRuleRegistry.register(
            "regnumEconomyIssuanceDailyPlayerLimit", GameRules.Category.MISC, GameRuleFactory.createIntRule(10_000_000, 0));

    private CoinIssuance() {
    }

    static void register() {
        // Eagerly register persistent receipts and issuance policy.
    }

    public static String issue(CommandSourceStack source, ServerPlayer player, long amount) {
        if (!source.hasPermission(2) || !player.getServer().isSameThread()
                || !WalletPersistence.available(player.getServer()) || !player.isAlive()
                || player.hasDisconnected() || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) {
            return "invalid";
        }
        var rules = player.getServer().getGameRules();
        if (!rules.getBoolean(ENABLED)) {
            return "issuance_disabled";
        }
        if (amount < 1 || amount > rules.getInt(PER_GRANT)) {
            return "issuance_limit";
        }
        List<Receipt> previous = player.getAttachedOrElse(RECEIPTS, List.of());
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        long issuedToday = 0;
        for (Receipt receipt : previous) {
            if (Math.floorDiv(receipt.time(), 86_400_000L) == day) {
                if (receipt.amount() > Long.MAX_VALUE - issuedToday) {
                    return "issuance_limit";
                }
                issuedToday += receipt.amount();
            }
        }
        if (amount > (long) rules.getInt(PER_DAY) - issuedToday) {
            return "issuance_limit";
        }
        long balance = CoinBalances.get(player);
        if (amount > Long.MAX_VALUE - balance) {
            return "capacity";
        }
        List<Receipt> receipts = new ArrayList<>(previous);
        String actor = source.getEntity() == null ? source.getTextName() : source.getEntity().getStringUUID();
        receipts.add(new Receipt(actor, System.currentTimeMillis(), amount));
        player.setAttached(RECEIPTS, List.copyOf(receipts));
        CoinBalances.set(player, balance + amount);
        if (!WalletPersistence.commit(player)) {
            player.setAttached(RECEIPTS, previous);
            CoinBalances.set(player, balance);
            return "save_failed";
        }
        BalancePayload.sendTo(player);
        LogUtils.getLogger().info("Currency issuance: actor={}, recipient={}, amount={}", actor, player.getUUID(), amount);
        return null;
    }
}
