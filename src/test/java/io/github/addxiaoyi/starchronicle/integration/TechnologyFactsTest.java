package io.github.addxiaoyi.starchronicle.integration;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnologyFactsTest {
    private static TechnologyFacts.Era era(String id){return switch(id){case "stone_age"->new TechnologyFacts.Era(id,0);case "iron_age"->new TechnologyFacts.Era(id,1);case "industrial_age"->new TechnologyFacts.Era(id,2);case "information_age"->new TechnologyFacts.Era(id,3);default->null;};}
    private static Map<String,String> read(Definitions d){return TechnologyFacts.read(d,"native-nation-id",TechnologyFactsTest::era);}
    @Test void greatestActualEraOrderWinsRatherThanCountOrLexicalTechnologyOrder() {
        Definitions d=new Definitions(Map.of("zzz-stone","stone_age","aaa-industry","industrial_age","mmm-iron","iron_age"));
        assertEquals(Map.of("tech.stage","2","tech.era","industrial_age"),read(d));assertEquals(3,d.reads);
    }
    @Test void stoneEraZeroIsRealWhenDefinitionActuallySpecifiesIt() {
        assertEquals("0",read(new Definitions(Map.of("fire","stone_age"))).get("tech.stage"));
    }
    @Test void noUnlocksOrNoNativeCapabilityDoNotInventEraZero() {
        assertTrue(read(new Definitions(Map.of())).isEmpty());StarCoreBridge bridge=mock(StarCoreBridge.class);
        assertTrue(TechnologyFacts.current(bridge,"id").isEmpty());assertTrue(TechnologyFacts.current(null,"id").isEmpty());
    }
    @Test void unknownEraOrMissingDefinitionCannotPublishAnIncorrectLowerMaximum() {
        assertTrue(read(new Definitions(Map.of("iron","iron_age","future","unknown_era"))).isEmpty());
        Definitions missing=new Definitions(Map.of("iron","iron_age"));missing.keys=List.of("iron","definition-lost");assertTrue(read(missing).isEmpty());
    }
    @Test void failedPartialReadOmitsFactsInsteadOfPublishingPartialStage() {
        Definitions d=new Definitions(Map.of("iron","iron_age"));d.failure=true;assertTrue(read(d).isEmpty());
    }
    @Test void currentStateCanMoveBackAfterAuthoritativeRevocation() {
        Definitions d=new Definitions(Map.of("iron","iron_age","industry","industrial_age"));assertEquals("2",read(d).get("tech.stage"));
        d.keys=List.of("iron");assertEquals("1",read(d).get("tech.stage"));
    }
    @Test void publishedFactsAreImmutableAndEveryReadUsesNativeMutationMonitor() {
        Map<String,String> facts=read(new Definitions(Map.of("written-law","iron_age")));
        assertThrows(UnsupportedOperationException.class,()->facts.put("tech.stage","999"));
    }
    public record Definition(String era){}
    public static final class Definitions {
        final Map<String,String> definitions;Collection<String> keys;int reads;boolean failure;
        Definitions(Map<String,String> definitions){this.definitions=definitions;keys=new ArrayList<>(definitions.keySet());}
        public Collection<String> unlockedTechnologies(String nation){assertTrue(Thread.holdsLock(this));return keys;}
        public Optional<Definition> getDefinition(String id){assertTrue(Thread.holdsLock(this));reads++;if(failure)throw new IllegalStateException("snapshot unavailable");return Optional.ofNullable(definitions.get(id)).map(Definition::new);}
    }
}
