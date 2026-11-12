package dev.regnum.economy.integration;

import dev.regnum.economy.RegnumEconomy;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import dev.sakhno.regnum.nations.api.v1.NationsApi;
import dev.sakhno.regnum.nations.api.v1.NationsIntegration;
import dev.sakhno.regnum.nations.api.v1.NationsIntegrations;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Public API v1 from Nations PR #20; no persistence or implementation-class access. */
public final class NationsAccess implements NationsIntegration {
    public record City(UUID id, UUID owner, String name) {
    }

    @Override
    public void initialize() {
        // Nations invokes this before a server exists; acquire NationsApi only inside server actions.
        NationsIntegrations.registerEconomy(RegnumEconomy.id("economy"));
    }

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("regnum-nations");
    }

    public static City cityAt(ServerLevel level, BlockPos pos) {
        return NationsApi.get(level.getServer()).territoryAt(level.dimension(), pos).value()
                .filter(entity -> entity.ref().scope() == EntityRef.Scope.CITY)
                .map(entity -> new City(entity.ref().id(), entity.owner(), entity.name())).orElse(null);
    }

    public static boolean permits(ServerPlayer player, UUID city, EconomyPermission permission) {
        return permits(player, EntityRef.city(city), permission);
    }

    public static boolean permits(ServerPlayer player, EntityRef entity, EconomyPermission permission) {
        return AuthAccess.permits(player) && NationsApi.get(player.getServer()).authorize(player, entity, permission).valid();
    }
}
