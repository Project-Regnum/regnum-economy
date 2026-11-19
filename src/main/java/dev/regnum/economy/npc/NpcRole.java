package dev.regnum.economy.npc;

import dev.regnum.economy.RegnumEconomy;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public record NpcRole(ResourceLocation id, String translation, Feature feature, NpcAppearance appearance) {
    private static final Map<ResourceLocation, NpcRole> ROLES = new LinkedHashMap<>();

    public static final NpcRole GENERIC = builtin("generic", Feature.DIALOGUE, NpcAppearance.DEFAULT);
    public static final NpcRole MERCHANT = builtin("merchant", Feature.MERCHANT, NpcAppearance.MERCHANT);
    public static final NpcRole BUYER = builtin("buyer", Feature.BUYER, NpcAppearance.MERCHANT);
    public static final NpcRole BANKER = builtin("banker", Feature.WALLET, NpcAppearance.BANKER);
    public static final NpcRole OFFICIAL = builtin("official", Feature.CITY, NpcAppearance.OFFICIAL);
    public static final NpcRole GUARD = builtin("guard", Feature.DIALOGUE, NpcAppearance.GUARD);

    public enum Feature { DIALOGUE, MERCHANT, WALLET, CITY, BUYER }

    public boolean usesMerchantStock() {
        return feature == Feature.MERCHANT || feature == Feature.BUYER;
    }

    public NpcRole {
        Objects.requireNonNull(id);
        Objects.requireNonNull(translation);
        Objects.requireNonNull(feature);
        Objects.requireNonNull(appearance);
    }

    /** Register during common mod initialization. */
    public static NpcRole register(NpcRole role) {
        if (ROLES.putIfAbsent(role.id(), role) != null) throw new IllegalArgumentException("Duplicate NPC role " + role.id());
        return role;
    }

    public static Set<ResourceLocation> ids() {
        return Set.copyOf(ROLES.keySet());
    }

    public static NpcRole find(ResourceLocation id) {
        return ROLES.get(id);
    }

    public Component label() {
        return Component.translatable(translation);
    }

    private static NpcRole builtin(String name, Feature feature, NpcAppearance appearance) {
        return register(new NpcRole(RegnumEconomy.id(name),
                "npc.regnum-economy.role." + name, feature, appearance));
    }
}
