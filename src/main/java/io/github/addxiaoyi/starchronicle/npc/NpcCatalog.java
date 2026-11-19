package io.github.addxiaoyi.starchronicle.npc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.addxiaoyi.starchronicle.config.Condition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Immutable, strictly validated logical NPC definitions. No third-party entity is created here. */
public record NpcCatalog(Map<String, Profile> profiles) {
    public record Binding(String provider, String externalId) {}
    public record Promotion(String id, int threshold, String position) {}
    public record Dialogue(String id, int priority, Condition condition, String text) {}
    /** Relationship deltas affect evidenced event participants; growth/position affect the NPC globally. */
    public record Reaction(String id, List<String> events, Condition condition, int reputation,
                           int trust, int hostility, int growth, String position) {
        public Reaction { events=events==null?List.of():List.copyOf(events); }
    }
    public record Profile(String name, String background, String faction, String initialPosition,
                          List<Promotion> growth, List<Binding> bindings,
                          List<Dialogue> dialogues, List<Reaction> reactions) {
        public Profile {
            growth=growth==null?List.of():List.copyOf(growth);
            bindings=bindings==null?List.of():List.copyOf(bindings);
            dialogues=dialogues==null?List.of():List.copyOf(dialogues);
            reactions=reactions==null?List.of():List.copyOf(reactions);
        }
    }
    public NpcCatalog {
        if(profiles==null || profiles.isEmpty() || profiles.size()>256)fail("NPC catalog requires 1..256 profiles");
        profiles=Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        validate(profiles);
    }
    public static NpcCatalog of(Map<String,Profile> profiles) {return new NpcCatalog(profiles);}
    public static NpcCatalog load(Path file) throws IOException {
        if(!Files.isRegularFile(file) || Files.size(file)>1_048_576)throw new IOException("NPC catalog missing or larger than 1 MiB: "+file);
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".json")&&!name.endsWith(".yml")&&!name.endsWith(".yaml"))throw new IOException("NPC catalog must be YAML or JSON");
        ObjectMapper mapper=name.endsWith(".json")?new ObjectMapper():new ObjectMapper(new YAMLFactory());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return mapper.readValue(file.toFile(),NpcCatalog.class);
    }
    public Profile require(String id) {
        Profile profile=profiles.get(id);
        if(profile==null)throw new IllegalArgumentException("Unknown logical NPC: "+id);
        return profile;
    }
    public Optional<String> resolveExternal(String provider,String externalId) {
        String normalized=provider(provider);
        if(externalId==null)return Optional.empty();
        for(var entry:profiles.entrySet())for(var binding:entry.getValue().bindings())
            if(provider(binding.provider()).equals(normalized) && binding.externalId().equals(externalId))return Optional.of(entry.getKey());
        return Optional.empty();
    }
    /** Accept a stable hook key such as fancynpcs:UUID without treating arbitrary IDs as profiles. */
    public Optional<String> resolveExternal(String hookKey) {
        if(hookKey==null)return Optional.empty();
        int colon=hookKey.indexOf(':');
        return colon<1?Optional.empty():resolveExternal(hookKey.substring(0,colon),hookKey.substring(colon+1));
    }
    public String positionAt(String npcId,int growth) {
        Profile profile=require(npcId);String position=profile.initialPosition();int threshold=-1;
        for(var promotion:profile.growth())if(promotion.threshold()<=growth && promotion.threshold()>threshold) {
            threshold=promotion.threshold();position=promotion.position();
        }
        return position;
    }
    public Set<String> positions(String npcId) {
        Profile profile=require(npcId);Set<String> positions=new LinkedHashSet<>();positions.add(profile.initialPosition());
        profile.growth().forEach(promotion->positions.add(promotion.position()));return Collections.unmodifiableSet(positions);
    }
    /** Can be called by a content validator before enabling an NPC effect. */
    public void validateEffect(String type,String npcId,String value) {
        require(npcId);
        if("position".equals(type)) {
            if(!positions(npcId).contains(value))fail("Undeclared NPC position: "+npcId+" / "+value);
            return;
        }
        if(!Set.of("reputation","trust","hostility","growth").contains(type))fail("Unsupported NPC effect: "+type);
        try {long delta=Long.parseLong(value);if(delta < -1_000_000L || delta > 1_000_000L)fail("NPC effect delta exceeds 1000000");}
        catch(NumberFormatException ex){fail("NPC effect must be an integer delta");}
    }
    public static String provider(String value) {
        if(value==null)return "";
        return value.toLowerCase(Locale.ROOT);
    }
    private static void validate(Map<String,Profile> profiles) {
        Set<String> externalBindings=new HashSet<>();
        for(var entry:profiles.entrySet()) {
            id(entry.getKey());Profile profile=Objects.requireNonNull(entry.getValue(),"Null NPC profile");
            text(profile.name(),128);text(profile.background(),4096);id(profile.faction());id(profile.initialPosition());
            if(profile.growth().size()>64||profile.bindings().size()>32||profile.dialogues().size()>128||profile.reactions().size()>128)fail("NPC profile collection exceeds limit");
            Set<String> promotionIds=new HashSet<>();Set<Integer> thresholds=new HashSet<>();Set<String> positions=new HashSet<>();positions.add(profile.initialPosition());
            for(var promotion:profile.growth()) {
                Objects.requireNonNull(promotion,"Null NPC promotion");id(promotion.id());id(promotion.position());
                if(!promotionIds.add(promotion.id())||!thresholds.add(promotion.threshold())||promotion.threshold()<0||promotion.threshold()>1_000_000)fail("Invalid/duplicate NPC promotion");
                positions.add(promotion.position());
            }
            for(var binding:profile.bindings()) {
                Objects.requireNonNull(binding,"Null NPC binding");String provider=provider(binding.provider());
                if(!Set.of("fancynpcs","citizens","adyeshach").contains(provider))fail("Unsupported NPC provider: "+binding.provider());
                text(binding.externalId(),256);
                if(!externalBindings.add(provider+"\u0000"+binding.externalId()))fail("External NPC is bound to multiple logical profiles");
            }
            Set<String> dialogueIds=new HashSet<>();
            for(var dialogue:profile.dialogues()) {
                Objects.requireNonNull(dialogue,"Null dialogue");id(dialogue.id());text(dialogue.text(),4096);
                if(!dialogueIds.add(dialogue.id())||Math.abs((long)dialogue.priority())>10000)fail("Invalid/duplicate NPC dialogue");
                condition(dialogue.condition(),0);
            }
            Set<String> reactionIds=new HashSet<>();
            for(var reaction:profile.reactions()) {
                Objects.requireNonNull(reaction,"Null reaction");id(reaction.id());
                if(!reactionIds.add(reaction.id())||reaction.events().isEmpty()||reaction.events().size()>64)fail("Invalid/duplicate NPC reaction");
                for(String event:reaction.events())text(event,128);
                if(Math.abs((long)reaction.reputation())>1000||Math.abs((long)reaction.trust())>1000||Math.abs((long)reaction.hostility())>1000||Math.abs((long)reaction.growth())>1_000_000)fail("NPC reaction delta exceeds limit");
                if(reaction.position()!=null&&!positions.contains(reaction.position()))fail("Reaction refers to undeclared NPC position");
                condition(reaction.condition(),0);
            }
        }
    }
    private static void condition(Condition condition,int depth) {
        if(condition==null)return;
        if(depth>16)fail("NPC condition exceeds depth 16");
        int variants=(condition.all()!=null?1:0)+(condition.any()!=null?1:0)+(condition.not()!=null?1:0)+(condition.fact()!=null?1:0);
        if(variants!=1)fail("NPC condition requires exactly one expression");
        if(condition.fact()!=null) {
            text(condition.fact(),128);
            if(!Set.of("eq","ne","exists","gte","lte","gt","lt").contains(Objects.toString(condition.op(),"")))fail("Unknown NPC condition operator");
            if(!"exists".equals(condition.op()))text(condition.value(),4096);
        } else {
            if(condition.op()!=null||condition.value()!=null)fail("Composite NPC condition cannot contain comparison fields");
            List<Condition> children=condition.all()!=null?condition.all():condition.any()!=null?condition.any():List.of(condition.not());
            if(children.isEmpty()||children.size()>64)fail("NPC condition group requires 1..64 children");
            for(Condition child:children){Objects.requireNonNull(child,"Null NPC condition");condition(child,depth+1);}
        }
    }
    private static void id(String id){if(id==null||!id.matches("[A-Za-z0-9_.-]{1,80}"))fail("Invalid NPC stable ID: "+id);}
    private static void text(String text,int max){if(text==null||text.isBlank()||text.length()>max)fail("Missing or oversized NPC text");}
    private static void fail(String message){throw new IllegalArgumentException(message);}
}
