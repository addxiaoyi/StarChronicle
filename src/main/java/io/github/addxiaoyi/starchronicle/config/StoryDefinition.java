package io.github.addxiaoyi.starchronicle.config;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

public record StoryDefinition(String id, String version, String title, Scope scope, List<String> triggers,
                              Condition condition, String start, long cooldownSeconds, Map<String,Node> nodes,
                              @JsonInclude(value=JsonInclude.Include.CUSTOM,valueFilter=PlayerVoteUnitFilter.class) VoteUnit voteUnit) {
    public StoryDefinition {
        triggers=triggers==null?null:List.copyOf(triggers);
        nodes=nodes==null?null:Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        voteUnit=voteUnit==null?VoteUnit.PLAYER:voteUnit;
    }
    /** Existing callers and saved definitions retain individual-player voting. */
    public StoryDefinition(String id,String version,String title,Scope scope,List<String> triggers,
                           Condition condition,String start,long cooldownSeconds,Map<String,Node> nodes) {
        this(id,version,title,scope,triggers,condition,start,cooldownSeconds,nodes,VoteUnit.PLAYER);
    }
    public enum Scope { PLAYER, NATION, WORLD }
    public enum VoteUnit { PLAYER, NATION }
    /** Generic snapshot JSON keeps the released PLAYER shape. Catalog identity uses its own
     * versioned codec and always writes the V2 voteUnit explicitly. */
    public static final class PlayerVoteUnitFilter {
        @Override public boolean equals(Object value){return value==VoteUnit.PLAYER;}
        @Override public int hashCode(){return VoteUnit.PLAYER.hashCode();}
    }
    public record Node(String text, String script, String quest, int quorum, Map<String,Choice> choices,
                       List<Effect> effects, boolean terminal) {
        public Node { choices=choices==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(choices)); effects=effects==null?List.of():List.copyOf(effects); quorum=Math.max(1,quorum); }
    }
    public record Choice(String label, String next, Condition condition, String permission, List<Effect> effects) {
        public Choice { effects=effects==null?List.of():List.copyOf(effects); }
    }
    public record Effect(String id, String type, String target, String value, String permission) {}
}
