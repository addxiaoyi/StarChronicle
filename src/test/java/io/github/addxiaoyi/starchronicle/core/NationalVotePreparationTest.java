package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary integration boundary: format/authority preparation must not enable player
 * counting for a national story before the actual national-seat ledger is wired in. */
class NationalVotePreparationTest {
    @TempDir Path directory;
    @Test void nationalUnitCannotAccidentallyResolveThroughTheExistingPersonalBallotPath(){
        UUID actor=UUID.fromString("00000000-0000-0000-0000-000000000092");Instant now=Instant.parse("2026-11-20T12:00:00Z");
        var story=new StoryDefinition("national-preparation","1","National preparation",Scope.WORLD,List.of("ROOT"),null,"vote",0,
                Map.of("vote",new Node("A nation must cast this vote",null,null,1,Map.of("yes",new Choice("Yes","end",null,null,List.of())),List.of(),false),
                        "end",new Node("End",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","10",null)),true)),StoryDefinition.VoteUnit.NATION);
        var context=new PlayerContext(actor,"river",Map.of("player.role","monarch","nation.rank","Founder"),Set.of("*","starchronicle.admin"));
        try(var store=new SqliteStore(directory.resolve("national-preparation.db"))){
            var engine=new RuleEngine(store,List.of(story),Clock.fixed(now,ZoneOffset.UTC));
            var source=new WorldEvent("preparation:source","ROOT",now,Set.of(actor),Set.of("river"),Map.of(),"NationalVotePreparationTest","Committed source");
            String id=engine.accept(source,List.of(context)).instanceIds().getFirst();
            assertThrows(SecurityException.class,()->engine.choose(id,actor,"vote","yes",context));
            var instance=engine.instances(actor).getFirst();assertFalse(instance.completed());assertTrue(instance.votes().isEmpty());assertTrue(instance.choices().isEmpty());
            assertTrue(engine.facts(actor).isEmpty());assertEquals(List.of(source),engine.history(null,null,0,10));
            long ballots=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM story_choice_log")){r.next();return r.getLong(1);}});
            assertEquals(0,ballots);
        }
    }
}
