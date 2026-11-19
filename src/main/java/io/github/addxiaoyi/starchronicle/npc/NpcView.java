package io.github.addxiaoyi.starchronicle.npc;

import java.util.Map;

/** Read-only NPC projection. This is display data, not an instruction or mutation capability. */
public record NpcView(String id,String name,String background,String faction,String position,int growth,
                      int reputation,int trust,int hostility,String dialogueId,String dialogue,Map<String,String> facts) {
    public NpcView {facts=Map.copyOf(facts);}
}
