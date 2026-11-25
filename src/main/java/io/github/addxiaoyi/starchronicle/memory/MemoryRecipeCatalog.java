package io.github.addxiaoyi.starchronicle.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded data-only recipes. Exact canonical source facts are required; no command/effect interpreter. */
public record MemoryRecipeCatalog(List<Recipe> recipes) {
    public record Recipe(String id,String version,boolean enabled,List<String> sources,List<String> eventTypes,
                         Map<String,String> requiredFacts,String regionId,String title,String text) {
        public Recipe {
            localId(id);if(version==null||!version.matches("[A-Za-z0-9_.-]{1,40}"))fail("Invalid memory recipe version");
            localId(regionId);plain(title,128);plain(text,2048);
            sources=bounded(sources,256,"sources");eventTypes=bounded(eventTypes,128,"eventTypes");
            if(requiredFacts==null||requiredFacts.isEmpty()||requiredFacts.size()>32)fail("Memory recipes require 1..32 exact source facts");
            requiredFacts=Collections.unmodifiableMap(new TreeMap<>(requiredFacts));
            for(var entry:requiredFacts.entrySet()) {
                if(entry.getKey()==null||!entry.getKey().matches("[A-Za-z0-9_.-]{1,128}"))fail("Invalid source fact key");
                plain(entry.getValue(),1024);
            }
            if(!requiredFacts.containsKey("region.id")&&!requiredFacts.containsKey("city.id"))fail("Memory recipe requires explicit region.id or city.id evidence");
            if(requiredFacts.containsKey("region.id")&&!regionId.equals(requiredFacts.get("region.id")))fail("Required region.id must equal the fixed memory regionId");
        }
        public boolean matches(WorldEvent event) {
            return enabled&&sources.contains(event.source())&&eventTypes.contains(event.type())
                &&(!event.facts().containsKey("region.id")||regionId.equals(event.facts().get("region.id")))
                &&requiredFacts.entrySet().stream().allMatch(entry->entry.getValue().equals(event.facts().get(entry.getKey())));
        }
        /** Enabled is an operational switch; immutable content and identity exclude that switch. */
        public String canonical() {
            Map<String,Object> value=new TreeMap<>();value.put("id",id);value.put("version",version);value.put("sources",new TreeSet<>(sources));
            value.put("eventTypes",new TreeSet<>(eventTypes));value.put("requiredFacts",requiredFacts);value.put("regionId",regionId);value.put("title",title);value.put("text",text);
            return SqliteStore.json(value);
        }
    }
    public MemoryRecipeCatalog {
        if(recipes==null||recipes.size()>64)fail("Memory catalog requires 0..64 recipes");
        recipes=List.copyOf(recipes);Set<String> ids=new HashSet<>();
        for(var recipe:recipes)if(recipe==null||!ids.add(recipe.id()))fail("Null or duplicate memory recipe ID");
    }
    public static MemoryRecipeCatalog empty(){return new MemoryRecipeCatalog(List.of());}
    public static MemoryRecipeCatalog load(Path file)throws IOException {
        if(!Files.exists(file))return empty();
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file)||Files.size(file)>262_144)throw new IOException("Memory recipe catalog must be a regular file of at most 256 KiB");
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".json")&&!name.endsWith(".yml")&&!name.endsWith(".yaml"))throw new IOException("Memory catalog requires YAML or JSON");
        ObjectMapper mapper=name.endsWith(".json")?new ObjectMapper():new ObjectMapper(new YAMLFactory());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        MemoryRecipeCatalog result=mapper.readValue(file.toFile(),MemoryRecipeCatalog.class);
        if(result==null)throw new IOException("Memory catalog cannot be null");return result;
    }
    public static void localId(String id){if(id==null||!id.matches("[a-z0-9][a-z0-9_.-]{0,39}")||Arrays.asList(id.split("\\.",-1)).contains("memory"))fail("Invalid bounded memory region/recipe ID or reserved memory path segment");}
    private static List<String> bounded(List<String> values,int max,String name) {
        if(values==null||values.isEmpty()||values.size()>16)fail("Memory "+name+" requires 1..16 exact values");
        Set<String> unique=new LinkedHashSet<>();for(String value:values){plain(value,max);if(value.indexOf('*')>=0||!unique.add(value))fail("Wildcard or duplicate memory "+name);}
        return List.copyOf(unique);
    }
    private static void plain(String text,int limit){if(text==null||text.isBlank()||text.length()>limit||text.indexOf(0)>=0)fail("Invalid memory recipe text");}
    private static void fail(String message){throw new IllegalArgumentException(message);}
}
