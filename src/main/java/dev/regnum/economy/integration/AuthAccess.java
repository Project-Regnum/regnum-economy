package dev.regnum.economy.integration;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public final class AuthAccess {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("regnum-auth");
    private static final Method AUTHENTICATED = findMethod();
    private static boolean reportedFailure;

    private AuthAccess() {
    }

    private static Method findMethod() {
        if (INSTALLED) {
            try {
                Class<?> manager = Class.forName("zooller.regnum.AuthManager", false, AuthAccess.class.getClassLoader());
                return manager.getMethod("isAuthenticated", ServerPlayer.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.error("Regnum Auth is installed but Economy could not connect to it", e);
            }
        }
        return null;
    }

    public static boolean permits(ServerPlayer player) {
        if (!INSTALLED) {
            return true;
        }
        if (AUTHENTICATED == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(AUTHENTICATED.invoke(null, player));
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!reportedFailure) {
                reportedFailure = true;
                LOGGER.error("Regnum Auth could not verify an Economy action", e);
            }
            return false;
        }
    }
}
