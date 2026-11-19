package io.github.addxiaoyi.starchronicle.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.addxiaoyi.starchronicle.core.DefinitionCatalog;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.addxiaoyi.starchronicle.config.StoryDefinition.Scope.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retired versions are obtained from a real persisted catalog, not an invented in-memory map. */
class ArchivedStoryDependenciesTest {
    @TempDir Path directory;
    private int nextDatabase;

    @Test void retiredA1CurrentA2AndBReferencingA1FormAnAcyclicVersionedChain() {
        StoryDefinition retired=story("a","1",WORLD,null);
        DefinitionCatalog catalog=archive(retired);
        StoryDefinition b=story("b","1",PLAYER,past("a","1"));
        StoryDefinition a2=story("a","2",WORLD,past("b","1"));
        assertEquals(retired,catalog.require("a","1"));
        assertDoesNotThrow(()->validate(catalog,a2,b));
        assertDoesNotThrow(()->validate(catalog,b,a2));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(a2,b)));
    }

    @Test void aCurrentVersionCanRequireItsOwnRetiredVersionButNotItsOwnUnfinishedVersion() {
        DefinitionCatalog catalog=archive(story("a","1",PLAYER,null));
        assertDoesNotThrow(()->validate(catalog,story("a","2",PLAYER,past("a","1"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,story("a","2",PLAYER,past("a","2"))));
    }

    @Test void retiredProgramsDependenciesAndEventTriggersAreNotExpandedOrRevalidatedAgainstInstalledContent() throws Exception {
        StoryDefinition retired=programs(reconfigure(story("a","1",WORLD,past("removed-source","7")),List.of("B_DONE"),List.of(history("A_DONE"))),"removed.script","removed.quest");
        DefinitionCatalog catalog=archive(retired);
        StoryDefinition b=reconfigure(story("b","1",PLAYER,past("a","1")),List.of("A_DONE"),List.of(history("B_DONE")));
        Path stories=directory.resolve("current");Files.createDirectories(stories);write(stories,"b.json",b);
        AtomicInteger scripts=new AtomicInteger(),quests=new AtomicInteger();
        assertEquals(List.of(b),ConfigLoader.load(stories,id->{scripts.incrementAndGet();return false;},id->{quests.incrementAndGet();return false;},catalog));
        assertEquals(0,scripts.get());assertEquals(0,quests.get());
        assertEquals("removed.script",catalog.require("a","1").nodes().get("decision").script());
        assertEquals("removed.quest",catalog.require("a","1").nodes().get("decision").quest());
    }

    @Test void referenceScopeNodeAndChoiceComeFromTheExactRetiredDefinitionNotTheCurrentVersion() {
        StoryDefinition old=story("a","1",WORLD,null);
        DefinitionCatalog catalog=archive(old);
        StoryDefinition current=renamed(story("a","2",NATION,null),"new-decision","approve");
        StoryDefinition good=story("consumer","1",PLAYER,fact("past.resolution.world.a/1/decision","eq","accept"));
        assertDoesNotThrow(()->validate(catalog,current,good));
        for(Condition invalid:List.of(
                fact("past.resolution.nation.a/1/decision","eq","accept"),
                fact("past.player.choice.a/1/new-decision","eq","approve"),
                fact("past.player.choice.a/1/decision","eq","approve"),
                fact("past.player.choice.a/2/decision","eq","accept"),
                fact("past.resolution.world.a/2/new-decision","eq","approve"))) {
            assertThrows(IllegalArgumentException.class,()->validate(catalog,current,story("consumer","1",PLAYER,invalid)));
        }
        assertDoesNotThrow(()->validate(catalog,current,story("consumer","1",PLAYER,fact("past.resolution.nation.a/2/new-decision","eq","approve"))));
    }

    @Test void unknownRetiredVersionNeverFallsBackToAnotherArchivedOrCurrentVersion() {
        DefinitionCatalog catalog=archive(story("a","1",WORLD,null));
        StoryDefinition current=story("a","3",WORLD,null);
        for(String version:List.of("0","2","4"))
            assertThrows(IllegalArgumentException.class,()->validate(catalog,current,story("b","1",PLAYER,past("a",version))));
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),story("b","1",PLAYER,past("a","1"))));
    }

    @Test void archivedExistsStillRequiresAnExistingNodeButDoesNotTreatItsValueAsAChoice() {
        DefinitionCatalog catalog=archive(story("a","1",WORLD,null));
        assertDoesNotThrow(()->validate(catalog,story("b","1",PLAYER,fact("past.resolution.world.a/1/end","exists","not-a-choice"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,story("b","1",PLAYER,fact("past.resolution.world.a/1/deleted","exists",null))));
        for(String operator:List.of("eq","ne"))
            assertThrows(IllegalArgumentException.class,()->validate(catalog,story("b","1",PLAYER,fact("past.player.choice.a/1/decision",operator,"deleted-choice"))));
    }

    @Test void optionalAndNegatedRetiredReferencesStillValidateEveryExactTarget() {
        DefinitionCatalog catalog=archive(story("a","1",WORLD,null));
        Condition independent=fact("player.role","eq","scholar");
        for(Condition invalid:List.of(
                any(past("missing","1"),independent),
                not(fact("past.player.choice.a/9/decision","exists",null)),
                any(fact("past.player.choice.a/1/deleted","eq","accept"),independent),
                not(fact("past.resolution.nation.a/1/decision","exists",null))))
            assertThrows(IllegalArgumentException.class,()->validate(catalog,story("b","1",PLAYER,invalid)));
        StoryDefinition choiceOnly=choiceConditions(story("b","1",PLAYER,null),fact("past.player.choice.a/1/decision","eq","missing-choice"),null);
        assertThrows(IllegalArgumentException.class,()->validate(catalog,choiceOnly));
    }

    @Test void anyIntersectionsDistinguishDifferentVersionsOfTheSameStory() {
        DefinitionCatalog catalog=archive(story("a","1",PLAYER,null));
        assertDoesNotThrow(()->validate(catalog,story("a","2",PLAYER,any(past("a","1"),past("a","2")))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,story("a","2",PLAYER,all(past("a","1"),past("a","2")))));
        assertDoesNotThrow(()->validate(catalog,story("a","2",PLAYER,not(fact("past.player.choice.a/2/decision","exists",null)))));
    }

    @Test void optionalCompletionRoutesRetainTheirVersionedRequirements() {
        DefinitionCatalog catalog=archive(story("a","1",PLAYER,null));
        StoryDefinition template=story("a","2",PLAYER,null);
        assertDoesNotThrow(()->validate(catalog,choiceConditions(template,past("a","1"),past("a","2"))));
        assertDoesNotThrow(()->validate(catalog,choiceConditions(template,past("a","2"),null)));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,choiceConditions(template,past("a","2"),past("a","2"))));
    }

    @Test void currentSameKeyMustMatchTheDurableDefinitionBeforeTakingLocalPrecedence() {
        StoryDefinition accepted=story("a","1",WORLD,null);
        DefinitionCatalog catalog=archive(accepted);
        StoryDefinition consumer=story("b","1",PLAYER,past("a","1"));
        assertDoesNotThrow(()->validate(catalog,accepted,consumer));
        StoryDefinition changed=new StoryDefinition(accepted.id(),accepted.version(),"Changed without version bump",accepted.scope(),accepted.triggers(),accepted.condition(),accepted.start(),accepted.cooldownSeconds(),accepted.nodes());
        assertThrows(IllegalArgumentException.class,()->validate(catalog,changed,consumer));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,changed));
    }

    @Test void ambiguousArchivedKeyCannotBeHiddenByAnIdenticalCurrentCandidateOrAnOptionalReference() {
        StoryDefinition first=story("a","1",WORLD,null);
        StoryDefinition second=programs(first,"other.script",null);
        DefinitionCatalog catalog=archive(first,second);
        assertThrows(IllegalArgumentException.class,()->validate(catalog,first,story("b","1",PLAYER,past("a","1"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,story("b","1",PLAYER,any(past("a","1"),fact("player.role","eq","scholar")))));
        assertDoesNotThrow(()->validate(catalog,story("a","2",WORLD,null)));
    }

    @Test void currentDirectoryStillEnablesOnlyOneVersionPerStoryId() {
        StoryDefinition a1=story("a","1",WORLD,null),a2=story("a","2",WORLD,null);
        DefinitionCatalog catalog=archive(a1);
        assertThrows(IllegalArgumentException.class,()->validate(catalog,a1,a2));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,a2,a2));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(),catalog));
    }

    @Test void twoPhaseLoaderDefersDirectoryResolutionButKeepsCurrentFileValidationAndLegacyEntryPointsStrict() throws Exception {
        DefinitionCatalog catalog=archive(story("a","1",WORLD,null));
        StoryDefinition consumer=story("b","1",PLAYER,past("a","1"));
        Path stories=directory.resolve("current");Files.createDirectories(stories);write(stories,"b.json",consumer);
        List<StoryDefinition> parsed=ConfigLoader.loadWithoutDirectoryValidation(stories,id->false,id->false);
        assertEquals(List.of(consumer),parsed);
        assertThrows(UnsupportedOperationException.class,()->parsed.add(consumer));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(stories,id->false,id->false));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(parsed));
        assertDoesNotThrow(()->ConfigLoader.validateAll(parsed,catalog));
        assertEquals(parsed,ConfigLoader.load(stories,id->false,id->false,catalog));
        write(stories,"b.json",programs(consumer,"uninstalled.current.script",null));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.loadWithoutDirectoryValidation(stories,id->false,id->false));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(stories,id->false,id->false,catalog));
        Files.writeString(stories.resolve("b.json"),"{\"id\":\"b\",\"unknown-field\":true}");
        assertThrows(Exception.class,()->ConfigLoader.loadWithoutDirectoryValidation(stories,id->true,id->true));
    }

    @Test void currentHistoryFeedbackAndVersionedMandatoryCyclesRemainRejected() {
        DefinitionCatalog catalog=archive(story("a","1",WORLD,null));
        StoryDefinition a2=reconfigure(story("a","2",WORLD,past("b","1")),List.of("EXTERNAL"),List.of(history("A_DONE")));
        StoryDefinition b=reconfigure(story("b","1",PLAYER,null),List.of("A_DONE"),List.of());
        assertThrows(IllegalArgumentException.class,()->validate(catalog,a2,b));
        StoryDefinition currentCycle=story("b","1",PLAYER,past("a","2"));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,story("a","2",WORLD,past("b","1")),currentCycle));
    }

    private DefinitionCatalog archive(StoryDefinition...definitions) {
        try(var store=new SqliteStore(directory.resolve("catalog-"+(nextDatabase++)+".db"))) {
            return store.transaction(connection->{
                DefinitionCatalog.createSchema(connection);
                DefinitionCatalog.archive(connection,List.of(definitions),"CONFIG_TEST persisted accepted definition");
                return DefinitionCatalog.read(connection);
            });
        }
    }
    private static void validate(DefinitionCatalog catalog,StoryDefinition...current) {
        for(StoryDefinition definition:current)ConfigLoader.validate(definition,id->false,id->false);
        ConfigLoader.validateAll(List.of(current),catalog);
    }
    private static void write(Path directory,String name,StoryDefinition definition) throws Exception {new ObjectMapper().writeValue(directory.resolve(name).toFile(),definition);}
    private static StoryDefinition story(String id,String version,StoryDefinition.Scope scope,Condition condition) {
        Map<String,StoryDefinition.Choice> choices=new LinkedHashMap<>();
        choices.put("accept",new StoryDefinition.Choice("Accept","end",null,null,List.of()));
        choices.put("reject",new StoryDefinition.Choice("Reject","end",null,null,List.of()));
        return new StoryDefinition(id,version,id,scope,List.of("EXTERNAL_"+id),condition,"decision",0,Map.of("decision",new StoryDefinition.Node("Decision",null,null,1,choices,List.of(),false),"end",new StoryDefinition.Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    private static StoryDefinition programs(StoryDefinition source,String script,String quest) {
        Map<String,StoryDefinition.Node> nodes=new LinkedHashMap<>(source.nodes());var old=nodes.get(source.start());
        nodes.put(source.start(),new StoryDefinition.Node(old.text(),script,quest,old.quorum(),old.choices(),old.effects(),old.terminal()));
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),source.triggers(),source.condition(),source.start(),source.cooldownSeconds(),nodes);
    }
    private static StoryDefinition reconfigure(StoryDefinition source,List<String> triggers,List<StoryDefinition.Effect> effects) {
        Map<String,StoryDefinition.Node> nodes=new LinkedHashMap<>(source.nodes());var old=nodes.get("end");
        nodes.put("end",new StoryDefinition.Node(old.text(),old.script(),old.quest(),old.quorum(),old.choices(),effects,true));
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),triggers,source.condition(),source.start(),source.cooldownSeconds(),nodes);
    }
    private static StoryDefinition renamed(StoryDefinition source,String node,String choice) {
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),source.triggers(),source.condition(),node,source.cooldownSeconds(),Map.of(node,new StoryDefinition.Node("Current decision",null,null,1,Map.of(choice,new StoryDefinition.Choice("Approve","end",null,null,List.of())),List.of(),false),"end",source.nodes().get("end")));
    }
    private static StoryDefinition choiceConditions(StoryDefinition source,Condition accept,Condition reject) {
        var old=source.nodes().get("decision");Map<String,StoryDefinition.Choice> choices=new LinkedHashMap<>();
        for(String id:List.of("accept","reject")){var choice=old.choices().get(id);choices.put(id,new StoryDefinition.Choice(choice.label(),choice.next(),id.equals("accept")?accept:reject,choice.permission(),choice.effects()));}
        Map<String,StoryDefinition.Node> nodes=new LinkedHashMap<>(source.nodes());nodes.put("decision",new StoryDefinition.Node(old.text(),old.script(),old.quest(),old.quorum(),choices,old.effects(),false));
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),source.triggers(),source.condition(),source.start(),source.cooldownSeconds(),nodes);
    }
    private static StoryDefinition.Effect history(String type){return new StoryDefinition.Effect("record","history",type,"Recorded result",null);}
    private static Condition fact(String fact,String operator,String value){return new Condition(null,null,null,fact,operator,value);}
    private static Condition past(String id,String version){return fact("past.player.choice."+id+"/"+version+"/decision","eq","accept");}
    private static Condition all(Condition...conditions){return new Condition(List.of(conditions),null,null,null,null,null);}
    private static Condition any(Condition...conditions){return new Condition(null,List.of(conditions),null,null,null,null);}
    private static Condition not(Condition condition){return new Condition(null,null,condition,null,null,null);}
}
