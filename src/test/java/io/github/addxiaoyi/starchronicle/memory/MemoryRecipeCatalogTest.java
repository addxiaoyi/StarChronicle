package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemoryRecipeCatalogTest {
    @TempDir Path directory;
    static MemoryRecipeCatalog.Recipe recipe(String id,String version,boolean enabled,List<String> sources,List<String> types,Map<String,String> facts) {
        return new MemoryRecipeCatalog.Recipe(id,version,enabled,sources,types,facts,"old-road","旅途档案","真实地区的到访记录");
    }
    static MemoryRecipeCatalog.Recipe road(){return recipe("road","1",true,List.of("Trusted"),List.of("DISCOVERED"),Map.of("region.id","old-road"));}
    private Path write(String name,String text)throws Exception{return Files.writeString(directory.resolve(name),text);}
    @Test void emptyOrMissingCatalogIsOptInOnly()throws Exception {
        assertTrue(MemoryRecipeCatalog.load(directory.resolve("missing.yml")).recipes().isEmpty());
        assertTrue(MemoryRecipeCatalog.load(write("empty.yml","recipes: []")).recipes().isEmpty());
        assertTrue(MemoryRecipeCatalog.load(write("empty.json","{\"recipes\":[]}")).recipes().isEmpty());
        assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write("null.yml","recipes: null")));
    }
    @Test void unknownDuplicateAndTrailingFieldsAreRejectedForYamlAndJson()throws Exception {
        for(String name:List.of("catalog.yml","catalog.json")) {
            for(String content:List.of("{\"recipes\":[],\"command\":\"op\"}","{\"recipes\":[],\"recipes\":[]}","{\"recipes\":[]} {\"recipes\":[]}"))
                assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write(name,content)));
        }
        assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write("duplicate.yml","recipes: []\nrecipes: []\n")));
    }
    @Test void boundedIdentityLocalityAndExactValuesAreMandatory() {
        assertThrows(IllegalArgumentException.class,()->recipe("../road","1",true,List.of("Trusted"),List.of("DISCOVERED"),Map.of("region.id","old-road")));
        assertThrows(IllegalArgumentException.class,()->recipe("road","1",true,List.of("*"),List.of("DISCOVERED"),Map.of("region.id","old-road")));
        assertThrows(IllegalArgumentException.class,()->recipe("road","1",true,List.of("Trusted","Trusted"),List.of("DISCOVERED"),Map.of("region.id","old-road")));
        assertThrows(IllegalArgumentException.class,()->recipe("road","1",true,List.of("Trusted"),List.of("DISCOVERED"),Map.of("war.id","only-war")));
        assertThrows(IllegalArgumentException.class,()->MemoryRecipeCatalog.localId("old.memory.city"));
        assertThrows(IllegalArgumentException.class,()->recipe("road","1",true,List.of("Trusted"),List.of("DISCOVERED"),Map.of("region.id","different-place")));
        assertThrows(IllegalArgumentException.class,()->new MemoryRecipeCatalog(List.of(road(),road())));
        assertThrows(IllegalArgumentException.class,()->new MemoryRecipeCatalog(Collections.nCopies(65,road())));
    }
    @Test void exactCanonicalSourceAndLocalityFactsCannotBeGuessed() {
        WorldEvent valid=new WorldEvent("source","DISCOVERED",Instant.EPOCH,Set.of(),Set.of(),Map.of("region.id","old-road"),"Trusted","Committed evidence");
        assertTrue(road().matches(valid));
        assertFalse(road().matches(new WorldEvent("other",valid.type(),valid.occurredAt(),Set.of(),Set.of(),Map.of(),valid.source(),valid.evidence())));
        assertFalse(road().matches(new WorldEvent("other",valid.type(),valid.occurredAt(),Set.of(),Set.of(),valid.facts(),"AnotherAuthority",valid.evidence())));
        assertFalse(road().matches(new WorldEvent("other","OTHER",valid.occurredAt(),Set.of(),Set.of(),valid.facts(),valid.source(),valid.evidence())));
        assertFalse(recipe("road","1",false,road().sources(),road().eventTypes(),road().requiredFacts()).matches(valid));
    }
    @Test void operationalEnableSwitchAndSetOrderDoNotRewriteImmutableContent() {
        var first=recipe("road","1",false,List.of("Trusted","Other"),List.of("A","B"),Map.of("region.id","old-road"));
        var enabled=recipe("road","1",true,List.of("Other","Trusted"),List.of("B","A"),Map.of("region.id","old-road"));
        assertEquals(first.canonical(),enabled.canonical());assertEquals(MemoryRecipeLedger.fingerprint(first),MemoryRecipeLedger.fingerprint(enabled));
        assertThrows(UnsupportedOperationException.class,()->first.requiredFacts().put("region.id","changed"));
    }
    @Test void oversizedWrongExtensionAndScalarCoercionFailClosed()throws Exception {
        assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write("large.yml","x".repeat(262145))));
        assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write("catalog.txt","recipes: []")));
        String content=io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json(new MemoryRecipeCatalog(List.of(road()))).replace("\"enabled\":true","\"enabled\":\"true\"");
        assertThrows(Exception.class,()->MemoryRecipeCatalog.load(write("coercion.json",content)));
    }
}
