package dev.regnum.economy.npc;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.integration.NationsAccess;
import dev.sakhno.regnum.nations.api.v1.ApiResult;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import dev.sakhno.regnum.nations.api.v1.EntitySnapshot;
import dev.sakhno.regnum.nations.api.v1.NationsApi;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class Npcs {
    public static final EntityType<RegnumNpc> TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            RegnumEconomy.id("npc"), EntityType.Builder.of(RegnumNpc::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).clientTrackingRange(8).noSummon().build(null));

    private Npcs() { }

    public static void register() {
        FabricDefaultAttributeRegistry.register(TYPE, RegnumNpc.createMobAttributes());
        NpcInteractions.register();
        NpcCommands.register();
    }

    public static boolean canAct(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.hasDisconnected() && AuthAccess.permits(player)
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    static boolean nearby(ServerPlayer player, RegnumNpc npc) {
        return canAct(player) && player.level() == npc.level() && npc.isAlive()
                && player.serverLevel().getEntity(npc.getUUID()) == npc
                && player.distanceToSqr(npc) <= 36 && player.hasLineOfSight(npc);
    }

    public static boolean canInteract(ServerPlayer player, RegnumNpc npc) {
        return nearby(player, npc) && npc.active() && npc.proprietor() != null && inOriginalTerritory(player, npc);
    }

    static boolean canManage(ServerPlayer player, RegnumNpc npc) {
        return canRemove(player, npc) && inOriginalTerritory(player, npc);
    }

    static boolean canRemove(ServerPlayer player, RegnumNpc npc) {
        return nearby(player, npc) && npc.proprietor() != null && (npc.city() == null
                ? npc.proprietor().equals(player.getUUID()) || player.hasPermissions(3)
                : NationsAccess.permits(player, npc.city(), EconomyPermission.ECONOMY_MANAGE_MARKET));
    }

    public static boolean canManageStock(ServerPlayer player, RegnumNpc npc) {
        return canRemove(player, npc) && (npc.city() == null
                ? npc.proprietor().equals(player.getUUID())
                : NationsAccess.permits(player, npc.city(), EconomyPermission.ECONOMY_WITHDRAW_TREASURY));
    }

    public static boolean inOriginalTerritory(ServerPlayer player, RegnumNpc npc) {
        var territory = NationsApi.get(player.server).territoryAt(player.level().dimension(), npc.blockPosition());
        return npc.city() == null ? territory.status() == ApiResult.Status.MISSING
                : territory.value().map(city -> city.ref().equals(EntityRef.city(npc.city()))).orElse(false);
    }

    static EntitySnapshot city(ServerPlayer player, RegnumNpc npc) {
        if (npc.city() == null) return null;
        return NationsApi.get(player.server).territoryAt(player.level().dimension(), npc.blockPosition()).value()
                .filter(city -> city.ref().equals(EntityRef.city(npc.city()))).orElse(null);
    }

    static RegnumNpc create(ServerPlayer player, NpcRole role, String name) {
        if (!canAct(player) || role == null || !RegnumNpc.validText(name, RegnumNpc.NAME_LIMIT) || name.isBlank()) return null;
        var level = player.serverLevel();
        var territory = NationsApi.get(player.server).territoryAt(level.dimension(), player.blockPosition());
        var city = territory.value().orElse(null);
        if (city == null ? territory.status() != ApiResult.Status.MISSING || !player.hasPermissions(2)
                : city.ref().scope() != EntityRef.Scope.CITY
                    || !NationsAccess.permits(player, city.ref(), EconomyPermission.ECONOMY_CREATE_MARKET)) return null;
        var npc = TYPE.create(level);
        if (npc == null) return null;
        npc.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0);
        if (level.isOutsideBuildHeight(npc.blockPosition()) || !level.getWorldBorder().isWithinBounds(npc.blockPosition())
                || !level.noCollision(npc, npc.getBoundingBox()) || level.containsAnyLiquid(npc.getBoundingBox())
                || !level.getEntitiesOfClass(RegnumNpc.class, npc.getBoundingBox()).isEmpty()) return null;
        npc.configure(player.getUUID(), city == null ? null : city.ref().id(), role, name);
        return level.addFreshEntity(npc) ? npc : null;
    }

    public static Component text(String key, Object... arguments) {
        return Component.translatable("npc.regnum-economy." + key, arguments);
    }
}
