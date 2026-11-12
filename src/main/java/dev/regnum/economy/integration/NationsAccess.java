package dev.regnum.economy.integration;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;

public final class NationsAccess {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PACKAGE = "dev.sakhno.regnum.nations.kingdom.";
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("regnum-nations");
    private static final Bridge BRIDGE = connect();
    private static boolean reportedFailure;

    public record City(UUID id, UUID owner, String name) {
    }

    private record Bridge(Method service, Method data, Method position, Method claim, Method settlement,
                          Method owner, Method name, Method display) {
    }

    private NationsAccess() {
    }

    private static Bridge connect() {
        if (INSTALLED) {
            try {
                Class<?> service = load("KingdomService");
                Class<?> data = load("KingdomSavedData");
                Class<?> position = load("ClaimPos");
                Class<?> settlement = load("Settlement");
                return new Bridge(service.getMethod("get", MinecraftServer.class), service.getMethod("data"),
                        position.getMethod("at", ResourceKey.class, BlockPos.class), data.getMethod("claimOwner", position),
                        data.getMethod("settlement", UUID.class), settlement.getMethod("ownerId"),
                        settlement.getMethod("name"), load("KingdomName").getMethod("display"));
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.error("Regnum Nations is installed but Economy could not connect to it", e);
            }
        }
        return null;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(PACKAGE + name, false, NationsAccess.class.getClassLoader());
    }

    public static boolean installed() {
        return INSTALLED;
    }

    public static City cityAt(ServerLevel level, BlockPos pos) {
        if (BRIDGE == null) {
            return null;
        }
        try {
            Object service = BRIDGE.service.invoke(null, level.getServer());
            Object data = BRIDGE.data.invoke(service);
            Object claimPos = BRIDGE.position.invoke(null, level.dimension(), pos);
            UUID cityId = (UUID) BRIDGE.claim.invoke(data, claimPos);
            if (cityId != null) {
                Object city = BRIDGE.settlement.invoke(data, cityId);
                if (city != null) {
                    return new City(cityId, (UUID) BRIDGE.owner.invoke(city),
                            (String) BRIDGE.display.invoke(BRIDGE.name.invoke(city)));
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!reportedFailure) {
                reportedFailure = true;
                LOGGER.error("Regnum Nations could not resolve the city of a market", e);
            }
        }
        return null;
    }
}
