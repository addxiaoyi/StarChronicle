package io.github.addxiaoyi.starchronicle.config;

import io.github.addxiaoyi.starchronicle.core.DefinitionCatalog;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

/** National stance is a versioned completion dependency, distinct from a shared resolution. */
class NationalChoiceDependenciesTest {
    @TempDir Path directory;
    int database;

    @Test void parsesOnlyTheExactNationalNamespaceAndPreservesStableIdDots() {
        assertEquals(new StoryDependencies.Reference(StoryDependencies.Kind.NATION_CHOICE,"old.empire","v2.1","council.vote"),
                StoryDependencies.parse("past.nation.choice.old.empire/v2.1/council.vote").orElseThrow());
        assertEquals(StoryDependencies.Kind.NATION_RESOLUTION,StoryDependencies.parse("past.resolution.nation.empire/1/vote").orElseThrow().kind());
        assertEquals(StoryDependencies.Kind.PLAYER_CHOICE,StoryDependencies.parse("past.player.choice.empire/1/vote").orElseThrow().kind());
    }

    @ParameterizedTest @ValueSource(strings={"past.nation.choice.","past.nation.choice.a/1","past.nation.choice.a/1/vote/yes",
            "past.nation.choice.a//vote","past.nation.choice.a/1/../vote","past.nation.choice.a/1/vote;DROP",
            "past.nation.choice.a/1/vote' OR '1'='1","past.national.choice.a/1/vote","past.nation.choice.a/1/vote\n",
            "past.nation.choice.a/1/vote?","past.nation.choice.a/1/ vote"})
    void rejectsMalformedOrInjectedReservedReferences(String reference) {
        assertThrows(IllegalArgumentException.class,()->StoryDependencies.parse(reference));
    }

    @Test void nationalChoiceAcceptsWorldAndNationVotingButNeverPlayerVotingOrPlayerScope() {
        for(Scope scope:List.of(Scope.WORLD,Scope.NATION)) {
            assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("source","1",scope,VoteUnit.NATION,null),consumer(national("source","1"))));
            assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),story("source","1",scope,VoteUnit.PLAYER,null),consumer(national("source","1"))));
        }
        assertThrows(IllegalArgumentException.class,()->StoryDependencies.validate(List.of(story("source","1",Scope.PLAYER,VoteUnit.NATION,null),consumer(national("source","1")))));
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),story("source","1",Scope.PLAYER,VoteUnit.PLAYER,null),consumer(national("source","1"))));
    }

    @Test void nationalActionCannotBeReferencedAsAnyIndividualsPersonalBallot() {
        for(Scope scope:List.of(Scope.WORLD,Scope.NATION)) {
            var target=story("source","1",scope,VoteUnit.NATION,null);
            assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),target,consumer(fact("past.player.choice.source/1/vote","eq","yes"))));
            assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),target,consumer(fact("past.player.choice.source/1/vote","exists",null))));
        }
        for(Scope scope:Scope.values())assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("source","1",scope,VoteUnit.PLAYER,null),consumer(fact("past.player.choice.source/1/vote","eq","yes"))));
    }

    @Test void collectiveResolutionNamespacesKeepTheirOriginalScopeMeaningForBothVoteUnits() {
        for(VoteUnit unit:VoteUnit.values()) {
            assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("source","1",Scope.WORLD,unit,null),consumer(fact("past.resolution.world.source/1/vote","eq","yes"))));
            assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("source","1",Scope.NATION,unit,null),consumer(fact("past.resolution.nation.source/1/vote","eq","yes"))));
            assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),story("source","1",Scope.WORLD,unit,null),consumer(fact("past.resolution.nation.source/1/vote","eq","yes"))));
        }
    }

    @Test void comparesOnlyExactExistingNodeChoiceAndVersionWithLimitedOperators() {
        var source=story("source","1",Scope.WORLD,VoteUnit.NATION,null);
        for(Condition condition:List.of(fact("past.nation.choice.source/2/vote","eq","yes"),
                fact("past.nation.choice.source/1/missing","exists",null),fact("past.nation.choice.source/1/vote","ne","missing"),
                fact("past.nation.choice.source/1/vote","gt","yes"),fact("past.nation.choice.source/1/vote","contains","yes")))
            assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),source,consumer(condition)));
        assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),source,consumer(fact("past.nation.choice.source/1/vote","exists",null))));
        assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),source,consumer(fact("past.nation.choice.source/1/vote","ne","no"))));
    }

    @Test void retiredExactVoteUnitAndChoicesWinOverDifferentCurrentVersionsWithoutExpandingOldDependencies() {
        var retired=story("source","1",Scope.WORLD,VoteUnit.NATION,fact("past.nation.choice.removed/9/vote","eq","yes"));
        var catalog=archive(retired);
        var current=story("source","2",Scope.WORLD,VoteUnit.PLAYER,null);
        assertDoesNotThrow(()->validate(catalog,current,consumer(national("source","1"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,current,consumer(national("source","2"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,current,consumer(fact("past.player.choice.source/1/vote","eq","yes"))));
        assertThrows(IllegalArgumentException.class,()->validate(catalog,current,consumer(national("source","3"))));
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),current,consumer(national("source","1"))));
    }

    @Test void versionedNationalCyclesAreRejectedButRetiredVersionDoesNotCreateFalseCycle() {
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),story("self","1",Scope.WORLD,VoteUnit.NATION,national("self","1"))));
        var alpha=story("alpha","1",Scope.WORLD,VoteUnit.NATION,national("beta","1"));
        var beta=story("beta","1",Scope.WORLD,VoteUnit.NATION,national("alpha","1"));
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),alpha,beta));
        var retired=story("self","1",Scope.WORLD,VoteUnit.NATION,national("removed","1"));
        assertDoesNotThrow(()->validate(archive(retired),story("self","2",Scope.WORLD,VoteUnit.NATION,national("self","1"))));
    }

    @Test void optionalAndNotExistsNationalReferencesUseExistingCompletionRulesAndStillValidateTargets() {
        var exists=fact("past.nation.choice.self/1/vote","exists",null);
        assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("self","1",Scope.WORLD,VoteUnit.NATION,new Condition(null,null,exists,null,null,null))));
        var optional=new Condition(null,List.of(national("self","1"),fact("player.role","eq","scholar")),null,null,null,null);
        assertDoesNotThrow(()->validate(DefinitionCatalog.empty(),story("self","1",Scope.WORLD,VoteUnit.NATION,optional)));
        var malformed=new Condition(null,List.of(national("missing","1"),fact("player.role","eq","scholar")),null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->validate(DefinitionCatalog.empty(),consumer(malformed)));
    }

    private DefinitionCatalog archive(StoryDefinition definition) {
        try(var store=new SqliteStore(directory.resolve("national-catalog-"+(database++)+".db"))) {
            return store.transaction(connection->{DefinitionCatalog.archive(connection,List.of(definition),"CONFIG_TEST exact retired national definition");return DefinitionCatalog.read(connection);});
        }
    }
    private static void validate(DefinitionCatalog catalog,StoryDefinition...definitions) {
        for(var definition:definitions)ConfigLoader.validate(definition,id->true,id->true);
        ConfigLoader.validateAll(List.of(definitions),catalog);
    }
    private static StoryDefinition consumer(Condition condition){return story("consumer","1",Scope.PLAYER,VoteUnit.PLAYER,condition);}
    private static Condition national(String story,String version){return fact("past.nation.choice."+story+"/"+version+"/vote","eq","yes");}
    private static Condition fact(String key,String operator,String value){return new Condition(null,null,null,key,operator,value);}
    private static StoryDefinition story(String id,String version,Scope scope,VoteUnit unit,Condition condition) {
        return new StoryDefinition(id,version,id,scope,List.of("EXTERNAL_"+id),condition,"vote",0,Map.of(
                "vote",new Node("Choose",null,null,1,Map.of("yes",new Choice("Yes","done",null,null,List.of()),"no",new Choice("No","done",null,null,List.of())),List.of(),false),
                "done",new Node("Done",null,null,1,Map.of(),List.of(),true)),unit);
    }
}
