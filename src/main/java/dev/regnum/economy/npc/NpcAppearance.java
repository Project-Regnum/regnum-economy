package dev.regnum.economy.npc;

import java.util.Locale;

public enum NpcAppearance {
    DEFAULT, MERCHANT, BANKER, OFFICIAL, GUARD;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NpcAppearance find(String id) {
        for (var appearance : values()) {
            if (appearance.id().equals(id)) return appearance;
        }
        return null;
    }
}
