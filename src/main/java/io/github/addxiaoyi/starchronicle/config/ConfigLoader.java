package io.github.addxiaoyi.starchronicle.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.addxiaoyi.starchronicle.core.DefinitionCatalog;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

public final class ConfigLoader {
    private ConfigLoader() {}
    public static List<StoryDefinition> load(Path directory, Predicate<String> scripts, Predicate<String> quests) throws IOException {
        return load(directory,scripts,quests,DefinitionCatalog.empty());
    }
    /** Resolve current and explicitly versioned retired references after every current file is read. */
    public static List<StoryDefinition> load(Path directory, Predicate<String> scripts, Predicate<String> quests,
                                              DefinitionCatalog catalog) throws IOException {
        List<StoryDefinition> result=loadWithoutDirectoryValidation(directory,scripts,quests);
        validateAll(result,catalog);
        return result;
    }
    /** Parse and locally validate current files without resolving cross-story references. Hosts
     * may bind their effective definitions before the catalog-aware directory validation pass. */
    public static List<StoryDefinition> loadWithoutDirectoryValidation(Path directory, Predicate<String> scripts,
                                                                       Predicate<String> quests) throws IOException {
        List<StoryDefinition> result=new ArrayList<>();
        if(!Files.isDirectory(directory)) throw new IOException("Missing stories directory: "+directory);
        try(var files=Files.list(directory)) {
            for(Path file:files.sorted().toList()) {
                String name=file.getFileName().toString();
                if(!name.endsWith(".yml")&&!name.endsWith(".yaml")&&!name.endsWith(".json")) continue;
                if(Files.size(file)>1_048_576) throw new IOException("Story larger than 1 MiB: "+name);
                ObjectMapper mapper=name.endsWith(".json")?new ObjectMapper():new ObjectMapper(new YAMLFactory());
                mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                StoryDefinition story=mapper.readValue(file.toFile(),StoryDefinition.class);
                validate(story,scripts,quests); result.add(story);
            }
        }
        return List.copyOf(result);
    }
    /** Validate references only after every current definition has been loaded. Single-story
     * validation remains usable for pinned historical definitions whose sources were retired. */
    public static void validateAll(Collection<StoryDefinition> definitions) {
        validateAll(definitions,DefinitionCatalog.empty());
    }
    public static void validateAll(Collection<StoryDefinition> definitions,DefinitionCatalog catalog) {
        StoryDependencies.validate(definitions,catalog);
    }
    public static void validate(StoryDefinition s, Predicate<String> scripts, Predicate<String> quests) {
        if(s==null) fail("Null story");
        id(s.id()); id(s.version()); text(s.title());if(s.title().length()>128)fail("Story title too long");
        if(s.scope()==null||s.triggers()==null||s.triggers().isEmpty()||s.cooldownSeconds()<0) fail("Invalid scope, triggers or cooldown: "+s.id());
        s.triggers().forEach(ConfigLoader::text);
        if(s.nodes()==null||s.nodes().isEmpty()||s.nodes().size()>256||!s.nodes().containsKey(s.start())) fail("Invalid start/nodes: "+s.id());
        condition(s.condition(),0);
        for(var entry:s.nodes().entrySet()) {
            id(entry.getKey()); var n=entry.getValue();
            if(n==null) fail("Null node"); text(n.text());
            if(n.quorum()>10000) fail("Quorum too large");
            if(n.terminal()&&!n.choices().isEmpty() || !n.terminal()&&n.choices().isEmpty()) fail("Terminal/choices mismatch: "+entry.getKey());
            if(n.choices().size()>36) fail("GUI supports at most 36 choices");
            if(present(n.script())&&!scripts.test(n.script())) fail("Missing StarCore script: "+n.script());
            if(present(n.quest())&&!quests.test(n.quest())) fail("Missing StarCore quest: "+n.quest());
            effects(n.effects(),scripts,quests);
            for(var c:n.choices().entrySet()) {
                id(c.getKey()); if(c.getValue()==null) fail("Null choice"); var v=c.getValue(); text(v.label());
                if(!s.nodes().containsKey(v.next())) fail("Unknown destination: "+v.next());
                condition(v.condition(),0); effects(v.effects(),scripts,quests);
            }
        }
        Set<String> visiting=new HashSet<>(),visited=new HashSet<>(); visit(s.start(),s,visiting,visited);
        if(visited.size()!=s.nodes().size()) fail("Unreachable nodes: "+s.id());
    }
    private static void visit(String id,StoryDefinition s,Set<String> visiting,Set<String> visited) {
        if(visiting.contains(id)) fail("Cyclic node dependency: "+id);
        if(visited.contains(id)) return;
        visiting.add(id);
        for(var choice:s.nodes().get(id).choices().values()) visit(choice.next(),s,visiting,visited);
        visiting.remove(id);visited.add(id);
    }
    private static void effects(List<StoryDefinition.Effect> effects,Predicate<String> scripts,Predicate<String> quests) {
        Set<String> ids=new HashSet<>();
        for(var e:effects) {
            if(e==null) fail("Null effect"); id(e.id()); if(!ids.add(e.id())) fail("Duplicate effect ID: "+e.id());
            text(e.type());text(e.target());text(e.value());
            switch(e.type()) {
                case "honor","reputation","trust","hostility","growth" -> { try { if(Math.abs((long)Integer.parseInt(e.value()))>100000) fail("Effect amount too large"); } catch(NumberFormatException ex){fail("Expected integer amount");} }
                case "position" -> id(e.value());
                case "title","memory" -> { }
                case "history" -> {if(e.target().length()>128)fail("History event type exceeds 128 characters");}
                case "narrative.start" -> {if(!scripts.test(e.target())) fail("Missing StarCore script: "+e.target());}
                case "quest.accept" -> {if(!quests.test(e.target())) fail("Missing StarCore quest: "+e.target());}
                case "authority" -> {if(!present(e.permission())||e.permission().contains("*")) fail("Authority effect requires explicit permission");}
                default -> fail("Invalid/unsafe effect: "+e.type());
            }
        }
    }
    private static void condition(Condition c,int depth) {
        if(c==null) return;
        if(depth>16) fail("Condition too deep");
        int variants=(c.all()!=null?1:0)+(c.any()!=null?1:0)+(c.not()!=null?1:0)+(c.fact()!=null?1:0);
        if(variants!=1) fail("Condition requires exactly one of all/any/not/fact");
        if(c.fact()!=null) {
            text(c.fact()); if(!Set.of("eq","ne","exists","gte","lte","gt","lt").contains(c.op()==null?"":c.op())) fail("Invalid condition operator");
            if(c.fact().startsWith("history.")||c.fact().startsWith("chronicle.history."))io.github.addxiaoyi.starchronicle.history.HistoricalFacts.accepts(c.fact());
            StoryDependencies.validateComparison(c);
            if(!"exists".equals(c.op())&&c.value()==null) fail("Missing comparison value");
            if(!"exists".equals(c.op())&&c.value().length()>4096) fail("Condition value too large");
            if(Set.of("gte","lte","gt","lt").contains(c.op()))try {
                var number=new java.math.BigDecimal(c.value());
                if(number.precision()>512||Math.abs((long)number.scale())>128)fail("Numeric condition exceeds limits");
            }catch(NumberFormatException e){fail("Invalid numeric condition");}
        } else {
            if(c.op()!=null||c.value()!=null) fail("Composite cannot contain comparison fields");
            List<Condition> list=c.all()!=null?c.all():c.any()!=null?c.any():List.of(c.not());
            if(list.isEmpty()||list.size()>64) fail("Empty or oversized condition group");
            for(var nested:list) {if(nested==null) fail("Null condition"); condition(nested,depth+1);}
        }
    }
    private static boolean present(String s){return s!=null&&!s.isBlank();}
    private static void id(String s){if(s==null||!s.matches("[A-Za-z0-9_.-]{1,80}")) fail("Invalid stable ID: "+s);}
    private static void text(String s){if(!present(s)||s.length()>4096) fail("Missing or oversized text");}
    private static void fail(String message){throw new IllegalArgumentException(message);}
}
