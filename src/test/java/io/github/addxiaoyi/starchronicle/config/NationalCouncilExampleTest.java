package io.github.addxiaoyi.starchronicle.config;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Validate the optional, complete example files without a server or native authority. */
class NationalCouncilExampleTest {
    private static final Path EXAMPLES=Path.of("examples","national-council");
    private Map<String,StoryDefinition> examples()throws Exception{
        Map<String,StoryDefinition> result=new TreeMap<>();for(var definition:ConfigLoader.load(EXAMPLES,id->false,id->false))result.put(definition.id(),definition);return result;
    }
    @Test void bothCompleteFilesLoadWithoutInventedNativeQuestOrScriptDependencies()throws Exception{
        var definitions=examples();assertEquals(Set.of("national-relief-council","national-relief-citizen-record"),definitions.keySet());assertEquals("1",definitions.get("national-relief-council").version());assertEquals("1",definitions.get("national-relief-citizen-record").version());
    }
    @Test void councilContainsTwoDistinctSharedNationalQuorums()throws Exception{
        StoryDefinition council=examples().get("national-relief-council");assertEquals(StoryDefinition.Scope.WORLD,council.scope());assertEquals(StoryDefinition.VoteUnit.NATION,council.voteUnit());assertEquals("mandate",council.start());
        var stages=council.nodes().entrySet().stream().filter(entry->!entry.getValue().terminal()).toList();assertEquals(Set.of("mandate","charter"),stages.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));assertTrue(stages.stream().allMatch(entry->entry.getValue().quorum()==2));assertTrue(council.nodes().get("mandate").choices().values().stream().allMatch(choice->choice.next().equals("charter")));
    }
    @Test void personalAftermathReadsBothExactNationalStagesAndKeepsPersonalVoteUnit()throws Exception{
        StoryDefinition aftermath=examples().get("national-relief-citizen-record");assertEquals(StoryDefinition.Scope.PLAYER,aftermath.scope());assertEquals(StoryDefinition.VoteUnit.PLAYER,aftermath.voteUnit());
        Set<StoryDependencies.Reference> references=new HashSet<>();for(Condition condition:aftermath.condition().all())references.add(StoryDependencies.parse(condition.fact()).orElseThrow());assertEquals(Set.of(new StoryDependencies.Reference(StoryDependencies.Kind.NATION_CHOICE,"national-relief-council","1","mandate"),new StoryDependencies.Reference(StoryDependencies.Kind.NATION_CHOICE,"national-relief-council","1","charter")),references);
        assertEquals(Set.of("public-ledger","regional-records"),aftermath.nodes().get("records").choices().values().stream().map(choice->choice.condition().value()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void examplesUseOnlyLocalNarrativeHistoryAndMemoryEffects()throws Exception{
        for(StoryDefinition definition:examples().values())for(var node:definition.nodes().values()){
            assertTrue(node.script()==null||node.script().isBlank());assertTrue(node.quest()==null||node.quest().isBlank());
            for(var effect:node.effects())assertTrue(Set.of("history","memory").contains(effect.type()));
            for(var choice:node.choices().values()){
                assertEquals("starchronicle.use",choice.permission());for(var effect:choice.effects())assertTrue(Set.of("history","memory").contains(effect.type()));
            }
        }
    }
    @Test void nationalReferencesCannotSilentlyPointToAPersonalVoteDefinition()throws Exception{
        var definitions=examples();var council=definitions.get("national-relief-council");var personal=new StoryDefinition(council.id(),council.version(),council.title(),council.scope(),council.triggers(),council.condition(),council.start(),council.cooldownSeconds(),council.nodes(),StoryDefinition.VoteUnit.PLAYER);
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(personal,definitions.get("national-relief-citizen-record"))));
    }
    @Test void installingOnlyTheCompanionFailsTheRealDirectoryValidator(@TempDir Path isolated)throws Exception{
        Files.copy(EXAMPLES.resolve("aftermath.yml"),isolated.resolve("aftermath.yml"));assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(isolated,id->false,id->false));
    }
}
