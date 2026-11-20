package io.github.addxiaoyi.starchronicle.config;

import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestGateConfigTest {
    final Effect gate=new Effect("ruins-run","quest.await","native:ruins","PLAYER","starchronicle.use");
    StoryDefinition story(Scope scope,List<Effect> entry,List<Effect> choice,List<Effect> ending,String shorthand){
        return new StoryDefinition("await-config","1","Await native task",scope,List.of("ROOT"),null,"work",0,
            Map.of("work",new Node("Complete the native task",null,shorthand,1,Map.of("continue",new Choice("Continue","done",null,null,choice)),entry,false),
                "done",new Node("Done",null,null,1,Map.of(),ending,true)));
    }
    void validate(StoryDefinition definition){ConfigLoader.validate(definition,id->true,id->id.equals("native:ruins"));}
    @Test void playerBarrierUsesExistingNativeQuestReferenceAndNormalGraph(){
        assertDoesNotThrow(()->validate(story(Scope.PLAYER,List.of(gate),List.of(),List.of(),null)));
    }
    @Test void nationBarrierAllowsAWorldStoryButNotAPersonalStory(){
        var nation=new Effect(gate.id(),gate.type(),gate.target(),"NATION",gate.permission());
        assertDoesNotThrow(()->validate(story(Scope.WORLD,List.of(nation),List.of(),List.of(),null)));
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(nation),List.of(),List.of(),null)));
    }
    @Test void terminalNodesCannotBeDeclaredCompleteAndWaitingAtOnce(){
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(),List.of(),List.of(gate),null)));
    }
    @Test void aChoiceCannotOpenTheBarrierAfterItsResolutionAlreadyPassed(){
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.WORLD,List.of(),List.of(gate),List.of(),null)));
    }
    @Test void aMissingNativeTaskStillFailsTheRealReferenceValidator(){
        var missing=new Effect("missing","quest.await","absent","PLAYER",null);
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(missing),List.of(),List.of(),null)));
    }
    @Test void modesAreExplicitAndDoNotSilentlyFallBackToCompletedEver(){
        for(String mode:List.of("completed-ever","READY","player","","true")){
            var wrong=new Effect("mode","quest.await","native:ruins",mode,null);
            assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(wrong),List.of(),List.of(),null)),mode);
        }
    }
    @Test void aTrackedBarrierCannotRaceAnUntrackedAssignmentForTheSameTask(){
        var accept=new Effect("old-accept","quest.accept","native:ruins","accept",null);
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(gate,accept),List.of(),List.of(),null)));
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(gate),List.of(),List.of(),"native:ruins")));
    }
    @Test void duplicateGateIdentitiesAndControlCharacterReferencesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->validate(story(Scope.PLAYER,List.of(gate,gate),List.of(),List.of(),null)));
        var malformed=new Effect("bad","quest.await","native:ruins\ncommand","PLAYER",null);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validate(story(Scope.PLAYER,List.of(malformed),List.of(),List.of(),null),id->true,id->true));
    }
    @Test void allThreeOptionalYamlJsonStoriesResolveOnlyTheirActualNativeTemplates()throws Exception {
        Set<String> tasks=Set.of("chronicle_heir_records","chronicle_siege_testimonies","chronicle_caravan_atlas");
        var stories=ConfigLoader.load(java.nio.file.Path.of("examples/quest-evidence"),id->false,tasks::contains);
        assertEquals(3,stories.size());assertEquals(Set.of("evidence-records","evidence-testimonies","evidence-atlas"),stories.stream().map(StoryDefinition::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(stories.stream().flatMap(story->story.nodes().values().stream()).flatMap(node->node.effects().stream()).filter(effect->effect.type().equals("quest.await")).allMatch(effect->tasks.contains(effect.target())));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(java.nio.file.Path.of("examples/quest-evidence"),id->false,id->false));
    }
}
