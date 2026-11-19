package io.github.addxiaoyi.starchronicle.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Frozen definition payload protocols, independent of record reflection and future fields.
 * V1 is the released schema-4 shape; V2 adds an explicit voteUnit after nodes. Both retain
 * record-field order, sort node/choice map keys, preserve list order and write null fields.
 * A stored fingerprint is checked over its exact UTF-8 payload BEFORE parsing/defaulting. */
public final class DefinitionCanonicalCodec {
    public static final int V1=1,V2=2,CURRENT=V2;
    public static final String V1_TO_V2="V1_TO_V2_ADD_PLAYER_VOTE_UNIT";
    private static final ObjectMapper PARSER=new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ROOT_V1=Set.of("id","version","title","scope","triggers","condition","start","cooldownSeconds","nodes");
    private static final Set<String> ROOT_V2=Set.of("id","version","title","scope","triggers","condition","start","cooldownSeconds","nodes","voteUnit");
    private DefinitionCanonicalCodec(){}

    public static String encodeV1(StoryDefinition definition){return encode(definition,V1);}
    public static String encodeV2(StoryDefinition definition){return encode(definition,V2);}
    public static String fingerprintV2(StoryDefinition definition){return sha256(encodeV2(definition));}
    public static String encode(StoryDefinition definition,int format) {
        supported(format);Objects.requireNonNull(definition,"definition");
        ConfigLoader.validate(definition,ignored->true,ignored->true);
        if(format==V1&&definition.voteUnit()!=VoteUnit.PLAYER)throw new IllegalArgumentException("V1 cannot represent national voting");
        try {
            StringWriter output=new StringWriter();
            try(JsonGenerator g=PARSER.getFactory().createGenerator(output)) {
                g.writeStartObject();text(g,"id",definition.id());text(g,"version",definition.version());text(g,"title",definition.title());
                text(g,"scope",definition.scope().name());g.writeFieldName("triggers");g.writeStartArray();for(String trigger:definition.triggers())g.writeString(trigger);g.writeEndArray();
                g.writeFieldName("condition");condition(g,definition.condition());text(g,"start",definition.start());g.writeNumberField("cooldownSeconds",definition.cooldownSeconds());
                g.writeObjectFieldStart("nodes");
                for(var entry:new TreeMap<>(definition.nodes()).entrySet()) {
                    Node n=entry.getValue();g.writeObjectFieldStart(entry.getKey());text(g,"text",n.text());text(g,"script",n.script());text(g,"quest",n.quest());g.writeNumberField("quorum",n.quorum());
                    g.writeObjectFieldStart("choices");for(var choice:new TreeMap<>(n.choices()).entrySet()) {
                        Choice c=choice.getValue();g.writeObjectFieldStart(choice.getKey());text(g,"label",c.label());text(g,"next",c.next());g.writeFieldName("condition");condition(g,c.condition());text(g,"permission",c.permission());effects(g,c.effects());g.writeEndObject();
                    }g.writeEndObject();effects(g,n.effects());g.writeBooleanField("terminal",n.terminal());g.writeEndObject();
                }g.writeEndObject();if(format==V2)text(g,"voteUnit",definition.voteUnit().name());g.writeEndObject();
            }
            return output.toString();
        }catch(IOException impossible){throw new IllegalStateException("Cannot encode definition",impossible);}
    }

    public static StoryDefinition verifyAndDecode(int format,String payload,String fingerprint) {
        supported(format);Objects.requireNonNull(payload,"payload");
        if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||!sha256(payload).equals(fingerprint))throw new IllegalStateException("Definition raw payload fingerprint mismatch");
        StoryDefinition definition=decode(format,payload);
        if(format==V2&&!payload.equals(encodeV2(definition)))throw new IllegalStateException("Noncanonical V2 definition payload");
        return definition;
    }

    /** For un-hashed legacy instance/trigger snapshots only. Catalog callers must use
     * verifyAndDecode; this method deliberately has no knowledge of current DTO defaults. */
    public static StoryDefinition decode(int format,String payload) {
        supported(format);Objects.requireNonNull(payload,"payload");
        try {
            JsonNode root=PARSER.readTree(payload);fields(root,format==V1?ROOT_V1:ROOT_V2,"definition");
            Scope scope=enumeration(root,"scope",Scope.class,null);VoteUnit unit=format==V1?VoteUnit.PLAYER:enumeration(root,"voteUnit",VoteUnit.class,null);
            if(format==V2&&unit==null)throw new IllegalArgumentException("V2 payload requires explicit voteUnit");
            JsonNode nodes=root.get("nodes");if(nodes==null||!nodes.isObject()||nodes.size()>256)throw new IllegalArgumentException("Invalid definition nodes");
            Map<String,Node> parsedNodes=new LinkedHashMap<>();
            for(var iterator=nodes.fields();iterator.hasNext();) {
                var entry=iterator.next();JsonNode n=entry.getValue();fields(n,Set.of("text","script","quest","quorum","choices","effects","terminal"),"node");
                Map<String,Choice> choices=new LinkedHashMap<>();JsonNode cs=n.get("choices");
                if(present(cs)) {
                    if(!cs.isObject()||cs.size()>36)throw new IllegalArgumentException("Invalid node choices");
                    for(var ci=cs.fields();ci.hasNext();) {
                        var ce=ci.next();JsonNode c=ce.getValue();fields(c,Set.of("label","next","condition","permission","effects"),"choice");
                        choices.put(ce.getKey(),new Choice(string(c,"label"),string(c,"next"),parseCondition(c.get("condition"),0),string(c,"permission"),parseEffects(c.get("effects"))));
                    }
                }
                long quorum=number(n,"quorum");if(quorum<Integer.MIN_VALUE||quorum>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid node quorum");
                parsedNodes.put(entry.getKey(),new Node(string(n,"text"),string(n,"script"),string(n,"quest"),(int)quorum,choices,parseEffects(n.get("effects")),bool(n,"terminal")));
            }
            JsonNode triggers=root.get("triggers");List<String> parsedTriggers=null;
            if(present(triggers)){if(!triggers.isArray())throw new IllegalArgumentException("Invalid triggers");parsedTriggers=new ArrayList<>();for(JsonNode trigger:triggers){if(!trigger.isTextual())throw new IllegalArgumentException("Invalid trigger");parsedTriggers.add(trigger.textValue());}}
            StoryDefinition result=new StoryDefinition(string(root,"id"),string(root,"version"),string(root,"title"),scope,parsedTriggers,parseCondition(root.get("condition"),0),string(root,"start"),number(root,"cooldownSeconds"),parsedNodes,unit);
            ConfigLoader.validate(result,ignored->true,ignored->true);return result;
        }catch(IOException|RuntimeException error){throw new IllegalStateException("Invalid canonical definition V"+format,error);}
    }

    /** Extract a definition without reserializing an entire instance or changing its bytes. */
    static String legacyDefinitionFromInstance(String payload) {
        try {JsonNode instance=PARSER.readTree(payload);if(instance==null||!instance.isObject()||!instance.has("definition"))throw new IllegalArgumentException("Missing instance definition");return PARSER.writeValueAsString(instance.get("definition"));}
        catch(IOException error){throw new IllegalStateException("Invalid legacy instance JSON",error);}
    }
    public static String sha256(String payload) {
        Objects.requireNonNull(payload,"payload");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void supported(int format){if(format!=V1&&format!=V2)throw new IllegalArgumentException("Unsupported definition canonical version: "+format);}
    private static void text(JsonGenerator g,String field,String value)throws IOException {g.writeFieldName(field);if(value==null)g.writeNull();else g.writeString(value);}
    private static void effects(JsonGenerator g,List<Effect> effects)throws IOException {
        g.writeArrayFieldStart("effects");for(Effect e:effects){g.writeStartObject();text(g,"id",e.id());text(g,"type",e.type());text(g,"target",e.target());text(g,"value",e.value());text(g,"permission",e.permission());g.writeEndObject();}g.writeEndArray();
    }
    private static void condition(JsonGenerator g,Condition c)throws IOException {
        if(c==null){g.writeNull();return;}g.writeStartObject();conditionList(g,"all",c.all());conditionList(g,"any",c.any());g.writeFieldName("not");condition(g,c.not());text(g,"fact",c.fact());text(g,"op",c.op());text(g,"value",c.value());g.writeEndObject();
    }
    private static void conditionList(JsonGenerator g,String name,List<Condition> values)throws IOException {g.writeFieldName(name);if(values==null){g.writeNull();return;}g.writeStartArray();for(Condition value:values)condition(g,value);g.writeEndArray();}
    private static List<Effect> parseEffects(JsonNode values) {
        if(!present(values))return List.of();if(!values.isArray())throw new IllegalArgumentException("Invalid effects");List<Effect> result=new ArrayList<>();
        for(JsonNode e:values){fields(e,Set.of("id","type","target","value","permission"),"effect");result.add(new Effect(string(e,"id"),string(e,"type"),string(e,"target"),string(e,"value"),string(e,"permission")));}return result;
    }
    private static Condition parseCondition(JsonNode c,int depth) {
        if(!present(c))return null;if(depth>16)throw new IllegalArgumentException("Condition too deep");fields(c,Set.of("all","any","not","fact","op","value"),"condition");
        return new Condition(parseConditions(c.get("all"),depth),parseConditions(c.get("any"),depth),parseCondition(c.get("not"),depth+1),string(c,"fact"),string(c,"op"),string(c,"value"));
    }
    private static List<Condition> parseConditions(JsonNode values,int depth) {
        if(!present(values))return null;if(!values.isArray()||values.size()>64)throw new IllegalArgumentException("Invalid condition group");List<Condition> result=new ArrayList<>();for(JsonNode value:values)result.add(parseCondition(value,depth+1));return result;
    }
    private static void fields(JsonNode object,Set<String> allowed,String type) {
        if(object==null||!object.isObject())throw new IllegalArgumentException("Expected "+type+" object");for(var names=object.fieldNames();names.hasNext();){String name=names.next();if(!allowed.contains(name))throw new IllegalArgumentException("Unknown "+type+" field: "+name);}
    }
    private static boolean present(JsonNode node){return node!=null&&!node.isNull();}
    private static String string(JsonNode object,String name){JsonNode value=object.get(name);if(!present(value))return null;if(!value.isTextual())throw new IllegalArgumentException("Expected text: "+name);return value.textValue();}
    private static long number(JsonNode object,String name){JsonNode value=object.get(name);if(!present(value))return 0;if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException("Expected integer: "+name);return value.longValue();}
    private static boolean bool(JsonNode object,String name){JsonNode value=object.get(name);if(!present(value))return false;if(!value.isBoolean())throw new IllegalArgumentException("Expected boolean: "+name);return value.booleanValue();}
    private static <E extends Enum<E>>E enumeration(JsonNode object,String name,Class<E> type,E fallback){String value=string(object,name);return value==null?fallback:Enum.valueOf(type,value);}
}
