package io.github.addxiaoyi.starchronicle.config;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConfigLoaderTest {
    @TempDir Path dir;
    String valid="""
        {"id":"sample","version":"1","title":"sample","scope":"PLAYER","triggers":["E"],"start":"a","nodes":{"a":{"text":"done","terminal":true}}}
        """;
    List<StoryDefinition> load(String json)throws Exception{Files.writeString(dir.resolve("story.json"),json);return ConfigLoader.load(dir,id->false,id->false);}
    @Test void jsonAndYamlAreRealDataFormats()throws Exception{assertEquals("sample",load(valid).getFirst().id());Files.delete(dir.resolve("story.json"));Files.writeString(dir.resolve("s.yml"),"id: yaml\nversion: '1'\ntitle: YAML\nscope: PLAYER\ntriggers: [E]\nstart: end\nnodes:\n  end:\n    text: Done\n    terminal: true\n");assertEquals("yaml",ConfigLoader.load(dir,x->true,x->true).getFirst().id());}
    @Test void unknownFieldsAreRejected(){assertThrows(Exception.class,()->load(valid.replace("\"scope\"","\"typo\":true,\"scope\"")));}
    @Test void missingQuestAndScriptAreRejected(){assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"terminal\":true,\"quest\":\"missing\"")));assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"terminal\":true,\"script\":\"missing\"")));}
    @Test void arbitraryCommandsAndAuthoritiesWithoutPermissionAreRejected(){for(String type:List.of("command","reward.give","authority"))assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"terminal\":true,\"effects\":[{\"id\":\"x\",\"type\":\""+type+"\",\"target\":\"a\",\"value\":\"b\"}]")));}
    @Test void cyclicAndDanglingNodeGraphsAreRejected(){assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"choices\":{\"loop\":{\"label\":\"loop\",\"next\":\"a\"}}")));assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"choices\":{\"x\":{\"label\":\"x\",\"next\":\"missing\"}}")));}
    @Test void invalidCompositeAndOperatorsAreRejected(){assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"start\":", "\"condition\":{\"all\":[]},\"start\":")));assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"start\":", "\"condition\":{\"fact\":\"a\",\"op\":\"exec\",\"value\":\"b\"},\"start\":")));}
    @Test void boundedNumericConditionsFailClosed(){assertFalse(new Condition(null,null,null,"nation.war","ne","true").test(Map.of()));assertFalse(new Condition(null,null,null,"gold","gte","5").test(Map.of("gold","NaN")));assertTrue(new Condition(List.of(new Condition(null,null,null,"role","eq","scholar"),new Condition(null,null,null,"tech","gte","2")),null,null,null,null,null).test(Map.of("role","scholar","tech","3")));}
    @Test void duplicateEffectAndUnreachableNodesAreRejected(){assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"terminal\":true","\"terminal\":true,\"effects\":[{\"id\":\"x\",\"type\":\"title\",\"target\":\"a\",\"value\":\"b\"},{\"id\":\"x\",\"type\":\"title\",\"target\":\"a\",\"value\":\"b\"}]")));assertThrows(IllegalArgumentException.class,()->load(valid.replace("\"a\":{","\"unreachable\":{\"text\":\"u\",\"terminal\":true},\"a\":{")));}
}
