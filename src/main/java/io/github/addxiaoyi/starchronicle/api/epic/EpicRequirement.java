package io.github.addxiaoyi.starchronicle.api.epic;

import java.util.Objects;

/** One native, receipt-backed prerequisite for a campaign phase. */
public record EpicRequirement(Type type, String target, String nativeReference) {
    public enum Type {
        TREASURY_AID, RESOURCE_AID, QUEST_GATE,
        DIPLOMACY_INVITE, DIPLOMACY_ACCEPT,
        NATION_FOUNDING, WAR_COMMIT, FINAL_BATTLE, DUNGEON_COMPLETION
    }

    public EpicRequirement {
        Objects.requireNonNull(type, "type");
        token(target, "target", 256);
        token(nativeReference, "nativeReference", 256);
    }

    private static void token(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || !value.matches("[A-Za-z0-9_.:/-]+"))
            throw new IllegalArgumentException("Invalid epic requirement " + name);
    }
}
