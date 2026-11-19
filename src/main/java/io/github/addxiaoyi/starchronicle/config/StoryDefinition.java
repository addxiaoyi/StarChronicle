package io.github.addxiaoyi.starchronicle.config;
import java.util.*;

public record StoryDefinition(String id, String version, String title, Scope scope, List<String> triggers,
                              Condition condition, String start, long cooldownSeconds, Map<String,Node> nodes) {
    public StoryDefinition {
        triggers=triggers==null?null:List.copyOf(triggers);
        nodes=nodes==null?null:Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
    public enum Scope { PLAYER, NATION, WORLD }
    public record Node(String text, String script, String quest, int quorum, Map<String,Choice> choices,
                       List<Effect> effects, boolean terminal) {
        public Node { choices=choices==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(choices)); effects=effects==null?List.of():List.copyOf(effects); quorum=Math.max(1,quorum); }
    }
    public record Choice(String label, String next, Condition condition, String permission, List<Effect> effects) {
        public Choice { effects=effects==null?List.of():List.copyOf(effects); }
    }
    public record Effect(String id, String type, String target, String value, String permission) {}
}
