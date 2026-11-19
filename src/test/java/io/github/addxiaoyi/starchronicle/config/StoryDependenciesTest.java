package io.github.addxiaoyi.starchronicle.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.github.addxiaoyi.starchronicle.config.StoryDefinition.Scope.*;
import static org.junit.jupiter.api.Assertions.*;

class StoryDependenciesTest {
    @TempDir Path directory;
    private static Condition fact(String fact,String op,String value){return new Condition(null,null,null,fact,op,value);}
    private static Condition past(String story){return fact("past.player.choice."+story+"/1/decision","eq","accept");}
    private static Condition existsPast(String story){return fact("past.player.choice."+story+"/1/decision","exists",null);}
    private static Condition not(Condition condition){return new Condition(null,null,condition,null,null,null);}
    private static Condition all(Condition...conditions){return new Condition(List.of(conditions),null,null,null,null,null);}
    private static Condition any(Condition...conditions){return new Condition(null,List.of(conditions),null,null,null,null);}
    private static void validateDirectory(StoryDefinition...definitions){
        for(var definition:definitions)ConfigLoader.validate(definition,id->true,id->true);
        ConfigLoader.validateAll(List.of(definitions));
    }
    private static StoryDefinition.Effect history(String type){return new StoryDefinition.Effect("record","history",type,"Recorded story result",null);}
    private static StoryDefinition story(String id,StoryDefinition.Scope scope,Condition condition){
        return story(id,scope,condition,List.of("EXTERNAL_"+id),List.of(),List.of(),null,0);
    }
    private static StoryDefinition story(String id,StoryDefinition.Scope scope,Condition condition,List<String> triggers,
                                         List<StoryDefinition.Effect> nodeEffects,List<StoryDefinition.Effect> choiceEffects,
                                         Condition choiceCondition,long cooldown){
        var choices=new LinkedHashMap<String,StoryDefinition.Choice>();
        choices.put("accept",new StoryDefinition.Choice("Accept","end",choiceCondition,null,choiceEffects));
        choices.put("reject",new StoryDefinition.Choice("Reject","end",null,null,List.of()));
        return new StoryDefinition(id,"1",id,scope,triggers,condition,"decision",cooldown,Map.of(
                "decision",new StoryDefinition.Node("Decision",null,null,2,choices,nodeEffects,false),
                "end",new StoryDefinition.Node("Done",null,null,1,Map.of(),List.of(),true)));
    }
    private static StoryDefinition choiceConditions(StoryDefinition source,Condition accept,Condition reject){
        Map<String,StoryDefinition.Node> nodes=new LinkedHashMap<>(source.nodes());var old=nodes.get("decision");
        var choices=new LinkedHashMap<String,StoryDefinition.Choice>();
        for(String id:List.of("accept","reject")) {
            var choice=old.choices().get(id);
            choices.put(id,new StoryDefinition.Choice(choice.label(),choice.next(),id.equals("accept")?accept:reject,choice.permission(),choice.effects()));
        }
        nodes.put("decision",new StoryDefinition.Node(old.text(),old.script(),old.quest(),old.quorum(),choices,old.effects(),false));
        return new StoryDefinition(source.id(),source.version(),source.title(),source.scope(),source.triggers(),source.condition(),source.start(),source.cooldownSeconds(),nodes);
    }
    private static StoryDefinition laterPaths(String id,Condition left,Condition right){
        return new StoryDefinition(id,"1",id,PLAYER,List.of("EXTERNAL_"+id),null,"decision",0,Map.of(
                "decision",new StoryDefinition.Node("Choose route",null,null,1,Map.of("accept",new StoryDefinition.Choice("Left","left",null,null,List.of()),"reject",new StoryDefinition.Choice("Right","right",null,null,List.of())),List.of(),false),
                "left",new StoryDefinition.Node("Left",null,null,1,Map.of("finish",new StoryDefinition.Choice("Finish","end",left,null,List.of())),List.of(),false),
                "right",new StoryDefinition.Node("Right",null,null,1,Map.of("finish",new StoryDefinition.Choice("Finish","end",right,null,List.of())),List.of(),false),
                "end",new StoryDefinition.Node("Done",null,null,1,Map.of(),List.of(),true)));
    }

    @Test void exactNamespacesPreserveDotsAndReturnTypedSlashSeparatedIds(){
        var player=StoryDependencies.parse("past.player.choice.ancient.empire/v1.2/council.vote").orElseThrow();
        assertEquals(new StoryDependencies.Reference(StoryDependencies.Kind.PLAYER_CHOICE,"ancient.empire","v1.2","council.vote"),player);
        assertEquals(StoryDependencies.Kind.WORLD_RESOLUTION,StoryDependencies.parse("past.resolution.world.empire/1/vote").orElseThrow().kind());
        assertEquals(StoryDependencies.Kind.NATION_RESOLUTION,StoryDependencies.parse("past.resolution.nation.empire/1/vote").orElseThrow().kind());
    }

    @Test void unrelatedAndLegacyFactsAreNotPastReferences(){
        assertTrue(StoryDependencies.parse(null).isEmpty());
        for(String value:List.of("player.role","choice.decision","story.choice.decision","history.EMPIRE_FOUNDED","history.world.EMPIRE_FOUNDED"))
            assertTrue(StoryDependencies.parse(value).isEmpty(),value);
    }

    @ParameterizedTest @ValueSource(strings={
            "past.player.choice.","past.player.choice.a","past.player.choice.a/1","past.player.choice.a/1/node/extra",
            "past.player.choice./1/node","past.player.choice.a//node","past.player.choice.a/1/",
            "past.player.choice.a /1/node","past.player.choice.a/ 1/node","past.player.choice.a/1/no de",
            "past.player.choice.a/1/node?","past.player.choice.a/1/node\\other","past.player.choice.a:1:node",
            "past.player.choices.a/1/node","past.resolution.player.a/1/node","past.resolution.WORLD.a/1/node",
            "past.resolution.world.a/1/node/","past.unknown.a/1/node"})
    void malformedReservedFactsAreRejected(String value){assertThrows(IllegalArgumentException.class,()->StoryDependencies.parse(value));}

    @Test void stableIdBoundsApplyToEveryReferenceSegment(){
        String tooLong="x".repeat(81);
        for(String suffix:List.of(tooLong+"/1/node","a/"+tooLong+"/node","a/1/"+tooLong))
            assertThrows(IllegalArgumentException.class,()->StoryDependencies.parse("past.player.choice."+suffix));
    }

    @Test void personalVotesMayReferenceEveryOriginalStoryScopeAndResolutionsUseOriginalScope(){
        var world=story("world-source",WORLD,null);var nation=story("nation-source",NATION,null);var player=story("player-source",PLAYER,null);
        var definitions=List.of(world,nation,player,
                story("from-world",PLAYER,past(world.id())),story("from-nation",WORLD,past(nation.id())),story("from-player",NATION,past(player.id())),
                story("world-resolution-consumer",NATION,fact("past.resolution.world.world-source/1/decision","eq","accept")),
                story("nation-resolution-consumer",WORLD,fact("past.resolution.nation.nation-source/1/decision","ne","reject")));
        assertDoesNotThrow(()->ConfigLoader.validateAll(definitions));
    }

    @Test void missingStoryVersionNodeAndComparisonChoiceAreRejected(){
        var source=story("source",PLAYER,null);
        for(String reference:List.of("missing/1/decision","source/2/decision","source/1/missing"))
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,story("consumer",PLAYER,fact("past.player.choice."+reference,"eq","accept")))));
        for(String op:List.of("eq","ne"))
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,story("consumer",PLAYER,fact("past.player.choice.source/1/decision",op,"unknown-choice")))));
    }

    @Test void existsValidatesItsNodeButDoesNotInterpretItsOptionalValueAsAChoice(){
        var source=story("source",WORLD,null);
        assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(source,story("consumer",PLAYER,fact("past.resolution.world.source/1/end","exists","not-a-choice")))));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,story("consumer",PLAYER,fact("past.resolution.world.source/1/deleted","exists",null)))));
    }

    @Test void resolutionNamespaceMustMatchTheReferencedStoryNotTheConsumer(){
        for(var original:List.of(PLAYER,NATION))
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(story("source",original,null),story("consumer",WORLD,fact("past.resolution.world.source/1/decision","eq","accept")))));
        for(var original:List.of(PLAYER,WORLD))
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(story("source",original,null),story("consumer",NATION,fact("past.resolution.nation.source/1/decision","eq","accept")))));
    }

    @Test void nestedCompositeAndChoiceConditionsAreIncluded(){
        var source=story("source",PLAYER,null);
        var invalid=fact("past.player.choice.source/1/decision","eq","missing");
        var nested=new Condition(List.of(new Condition(null,List.of(new Condition(null,null,invalid,null,null,null)),null,null,null,null)),null,null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,story("consumer",PLAYER,nested))));
        var choiceConsumer=story("consumer",PLAYER,null,List.of("E"),List.of(),List.of(),invalid,0);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,choiceConsumer)));
    }

    @Test void pastReferencesOnlySupportEqNeOrExistsAndSingleValidationDoesNotResolveTheDirectory(){
        for(String op:List.of("gt","gte","lt","lte","in"))
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validate(story("consumer",PLAYER,fact("past.player.choice.retired/1/decision",op,"1")),id->true,id->true));
        var pinned=story("pinned",PLAYER,past("retired"));
        assertDoesNotThrow(()->ConfigLoader.validate(pinned,id->true,id->true));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(pinned)));
    }

    @Test void invalidPastSyntaxIsAlsoRejectedWhenValidatingOnePinnedDefinition(){
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validate(story("pinned",PLAYER,fact("past.player.choice.retired.1.decision","eq","accept")),id->true,id->true));
    }

    @Test void legacyCurrentInstanceChoiceConditionsKeepTheirOriginalLooseLookupBehavior(){
        for(String key:List.of("choice.not-yet-defined","story.choice.not-yet-defined")) {
            var definition=story("legacy",PLAYER,fact(key,"eq","not-a-current-choice"));
            assertDoesNotThrow(()->ConfigLoader.validate(definition,id->true,id->true));
            assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(definition)));
        }
    }

    @Test void directAndIndirectPastDependenciesAreRejectedWithAConcreteCyclePath(){
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(story("self",PLAYER,past("self")))));
        var failure=assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(story("beta",PLAYER,past("alpha")),story("alpha",PLAYER,past("beta")))));
        assertEquals("Cyclic story dependency: alpha -> beta -> alpha",failure.getMessage());
    }

    @Test void notExistsCanBootstrapOwnPastButPositiveAndDoubleNegativeExistsStillRequireCompletion(){
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,not(existsPast("self")))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,existsPast("self"))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(not(existsPast("self"))))));
    }

    @Test void ownPastInAnyWithAnIndependentFallbackIsNotAHardPrerequisite(){
        Condition independent=fact("player.role","eq","scholar");
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,any(past("self"),independent))));
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,any(independent,past("self")))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,all(past("self"),independent))));
    }

    @Test void optionalChoiceBranchMayReadOwnPastWhileAllBranchesNeedingItAreRejected(){
        var template=story("self",PLAYER,null);
        assertDoesNotThrow(()->validateDirectory(choiceConditions(template,past("self"),null)));
        assertDoesNotThrow(()->validateDirectory(choiceConditions(template,null,past("self"))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(choiceConditions(template,past("self"),past("self"))));
    }

    @Test void allChoiceBranchesNeedingTheOtherStoryCreateATrueCompletionCycle(){
        var alpha=story("alpha",PLAYER,null);var beta=story("beta",PLAYER,past("alpha"));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(choiceConditions(alpha,past("beta"),past("beta")),beta));
        assertDoesNotThrow(()->validateDirectory(choiceConditions(alpha,past("beta"),null),beta));
    }

    @Test void unavoidableDependenciesAtLaterDagNodesAreIncludedButAnIndependentTerminalRouteBreaksThem(){
        var beta=story("beta",PLAYER,past("alpha"));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(laterPaths("alpha",past("beta"),past("beta")),beta));
        assertDoesNotThrow(()->validateDirectory(laterPaths("alpha",past("beta"),null),beta));
    }

    @Test void negatedOrdinaryComparisonsStillRequireKnownPastInEitherTruthPolarity(){
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(past("self")))));
        Condition different=fact("past.player.choice.self/1/decision","ne","reject");
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,different)));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(different))));
    }

    @Test void negatedAllUsesIntersectionAndNegatedAnyUsesUnionOfFalseRequirements(){
        Condition independent=fact("player.role","eq","scholar");
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,not(all(past("self"),independent)))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(any(past("self"),independent)))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(all(past("self"),past("self"))))));
    }

    @Test void absenceAndUnknownComparisonStayDistinctInsideNegatedComposites(){
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,not(all(existsPast("self"),past("self"))))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("self",PLAYER,not(any(existsPast("self"),past("self"))))));
        assertDoesNotThrow(()->validateDirectory(story("self",PLAYER,not(any(existsPast("self"),existsPast("self"))))));
    }

    @Test void anyRetainsOnlyAStoryRequiredByEveryAlternative(){
        Condition independent=fact("player.role","eq","scholar");var beta=story("beta",PLAYER,past("alpha"));var gamma=story("gamma",PLAYER,null);
        Condition common=any(all(past("beta"),independent),all(past("beta"),past("gamma")));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(story("alpha",PLAYER,common),beta,gamma));
        assertDoesNotThrow(()->validateDirectory(story("alpha",PLAYER,any(past("beta"),past("gamma"))),beta,gamma));
    }

    @Test void optionalOrAbsenceReferencesStillValidateStoryVersionNodeAndChoice(){
        var source=story("source",PLAYER,null);Condition independent=fact("player.role","eq","scholar");
        assertThrows(IllegalArgumentException.class,()->validateDirectory(source,story("consumer",PLAYER,any(past("missing"),independent))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(source,story("consumer",PLAYER,not(fact("past.player.choice.source/2/decision","exists",null)))));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(source,choiceConditions(story("consumer",PLAYER,null),fact("past.player.choice.source/1/removed","eq","accept"),null)));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(source,choiceConditions(story("consumer",PLAYER,null),fact("past.player.choice.source/1/decision","eq","removed-choice"),null)));
    }

    @Test void conservativeHistoryEdgesRejectOnlyTheMandatoryMixedPastFeedbackCycle(){
        var alpha=story("alpha",PLAYER,null,List.of("EXTERNAL"),List.of(history("A_DONE")),List.of(),null,0);
        var beta=story("beta",WORLD,null,List.of("A_DONE"),List.of(),List.of(),null,0);
        assertDoesNotThrow(()->validateDirectory(choiceConditions(alpha,past("beta"),null),beta));
        assertThrows(IllegalArgumentException.class,()->validateDirectory(choiceConditions(alpha,past("beta"),past("beta")),beta));
        assertDoesNotThrow(()->validateDirectory(story("alpha",PLAYER,not(existsPast("beta")),List.of("EXTERNAL"),List.of(history("A_DONE")),List.of(),null,0),beta));
    }

    @Test void selfProducingHistoryAtANodeOrChoiceIsRejectedEvenWithCooldown(){
        for(boolean atChoice:List.of(false,true)) {
            var effects=List.of(history("LOOP"));
            var definition=story("self",PLAYER,null,List.of("LOOP"),atChoice?List.of():effects,atChoice?effects:List.of(),null,86_400);
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(definition)));
        }
    }

    @Test void indirectHistoryProductionCycleIsRejected(){
        var a=story("alpha",PLAYER,null,List.of("B_DONE"),List.of(history("A_DONE")),List.of(),null,0);
        var b=story("beta",NATION,null,List.of("A_DONE"),List.of(),List.of(history("B_DONE")),null,0);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(a,b)));
    }

    @Test void historyTriggerAndPastPrerequisiteInTheSameDirectionAreOneValidDependency(){
        var a=story("alpha",WORLD,null,List.of("EXTERNAL"),List.of(history("A_DONE")),List.of(),null,0);
        var b=story("beta",PLAYER,past("alpha"),List.of("A_DONE"),List.of(),List.of(),null,0);
        assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(b,a)));
    }

    @Test void mixedPastAndHistoryEdgesCannotHideAnIndirectCycle(){
        var a=story("alpha",PLAYER,past("beta"),List.of("EXTERNAL"),List.of(history("A_DONE")),List.of(),null,0);
        var b=story("beta",WORLD,null,List.of("A_DONE"),List.of(),List.of(),null,0);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(a,b)));
    }

    @Test void ordinaryHistoricalCountsAndSharedExternalTriggersAreNotHardDependencies(){
        var a=story("alpha",PLAYER,fact("history.B_DONE","gte","1"),List.of("EXTERNAL"),List.of(history("A_DONE")),List.of(),null,0);
        var b=story("beta",PLAYER,fact("history.player.A_DONE","gte","1"),List.of("EXTERNAL"),List.of(history("B_DONE")),List.of(),null,0);
        assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(a,b)));
        assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(story("self",PLAYER,fact("history.SELF_DONE","gte","1"),List.of("EXTERNAL"),List.of(history("SELF_DONE")),List.of(),null,0))));
    }

    @Test void loadResolvesForwardReferencesOnlyAfterReadingTheEntireDirectory()throws Exception{
        ObjectMapper mapper=new ObjectMapper();
        Files.writeString(directory.resolve("a-consumer.json"),mapper.writeValueAsString(story("consumer",PLAYER,past("source"))));
        Files.writeString(directory.resolve("z-source.json"),mapper.writeValueAsString(story("source",WORLD,null)));
        assertEquals(2,ConfigLoader.load(directory,id->false,id->false).size());
        Files.delete(directory.resolve("z-source.json"));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(directory,id->false,id->false));
    }

    @Test void fullDirectoryPassRejectsDuplicateIdsAndEmptyInput(){
        var source=story("source",PLAYER,null);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(source,source)));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of()));
    }

    @Test void longAcyclicStoryChainsDoNotUseTheJavaCallStack(){
        List<StoryDefinition> definitions=new ArrayList<>();
        for(int i=0;i<2_000;i++)definitions.add(story("story-"+i,PLAYER,i==0?null:past("story-"+(i-1))));
        assertDoesNotThrow(()->ConfigLoader.validateAll(definitions));
    }

    @Test void allMainStoriesSequelAndEightFateStoriesRemainValidTogether()throws Exception{
        for(String name:List.of("last-heir","siege-embers","silk-road-scholar","heir-legacy","fate-soldier","fate-general","fate-merchant","fate-scholar","fate-explorer","fate-diplomat","fate-mayor","fate-monarch"))
            try(var stream=getClass().getResourceAsStream("/stories/"+name+".yml")){
                assertNotNull(stream,name);Files.copy(stream,directory.resolve(name+".yml"));
            }
        assertEquals(12,ConfigLoader.load(directory,id->false,id->false).size());
    }
}
