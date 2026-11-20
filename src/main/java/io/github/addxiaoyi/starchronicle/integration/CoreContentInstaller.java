package io.github.addxiaoyi.starchronicle.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.Node;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Installs only Chronicle-owned content through StarCore's real public APIs. No external
 * same-ID content or existing user file is overwritten. Native scenes are supplemental to the
 * Chronicle campaign GUI: upstream's base NarrativeEngine render hook is a no-op.
 *
 * <p>Custom native quests remain explicit opt-in only. The plugin entry point requires the
 * durable quest-evidence capability before enabling them; the original audited upstream lacks
 * correct late-template restoration. This installer never reinitializes the global QuestService
 * or edits its persistence data.</p> */
public final class CoreContentInstaller implements AutoCloseable {
    private static final List<Bundle> BUNDLES=List.of(
            new Bundle("last-heir","chronicle_last_heir","chronicle_heir_records","继承证据的抄录","为旧都会议制作一份可核查的证据抄本。","PAPER",3),
            new Bundle("siege-embers","chronicle_siege_embers","chronicle_siege_testimonies","幸存者的记录册","制作一本用于整理幸存者证词的空白书籍。","BOOK",1),
            new Bundle("silk-road-scholar","chronicle_silk_road","chronicle_caravan_atlas","古商路的地图册","制作一张空地图，准备开展真实的商路调查。","MAP",1));
    private final StarCoreBridge bridge;
    private final Path directory;
    private final Set<String> scenes=new HashSet<>();
    private final Map<String,OwnedQuest> quests=new HashMap<>();
    private boolean closed;

    public CoreContentInstaller(StarCoreBridge bridge,Path dataDirectory) {
        this.bridge=Objects.requireNonNull(bridge);directory=Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize().resolve("starcore-scenes");
    }
    public record Report(Map<String,String> scenes,Map<String,String> quests) {
        public Report {scenes=Map.copyOf(scenes);quests=Map.copyOf(quests);}
    }
    public static List<String> sceneIds(){return BUNDLES.stream().map(Bundle::scene).toList();}
    public static List<String> questIds(){return BUNDLES.stream().map(Bundle::quest).toList();}
    /** Automatic recovery is restricted to the three audited namespaces and idempotent
     * collection effects. General native scenes remain the upstream engine's responsibility. */
    public static boolean recoverySafe(Path file,String expected)throws IOException {
        String prefix=switch(expected){case "chronicle_last_heir"->"chronicle_heir_";case "chronicle_siege_embers"->"chronicle_siege_";case "chronicle_silk_road"->"chronicle_caravan_";default->null;};
        if(prefix==null)return false;validateScene(file,expected);
        JsonNode root=new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
        Set<String> fields=Set.of("id","title","version","description","author","category","startNode","nodes");
        var keys=root.fieldNames();while(keys.hasNext())if(!fields.contains(keys.next()))return false;
        var nodes=root.path("nodes").elements();while(nodes.hasNext()) {
            JsonNode node=nodes.next();if(!safeRecoveryEffect(node.path("effect").asText(""),prefix))return false;
            var choices=node.path("choices").elements();while(choices.hasNext())if(!safeRecoveryEffect(choices.next().path("effect").asText(""),prefix))return false;
        }
        return true;
    }
    private static boolean safeRecoveryEffect(String expression,String prefix) {
        for(String part:expression.split(";"))if(!part.isBlank()) {
            int colon=part.indexOf(':');if(colon<0)return false;
            String command=part.substring(0,colon).trim().toLowerCase(Locale.ROOT),key=part.substring(colon+1).trim();
            if(!Set.of("flag","unflag","evidence","removeevidence","markending").contains(command)||!key.startsWith(prefix)||!key.matches("[A-Za-z0-9_.:-]{1,128}"))return false;
        }
        return true;
    }
    public static String fingerprint(Path file)throws IOException {
        if(Files.size(file)>1_048_576)throw new IOException("Native scene exceeds fingerprint limit");
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /** Default installation deliberately excludes the upstream quest-restoration limitation. */
    public synchronized Report install(){return install(false);}
    public synchronized Report install(boolean optInNativeQuests) {
        if(closed)throw new IllegalStateException("Content installer closed");
        Map<String,String> sceneResults=new LinkedHashMap<>(),questResults=new LinkedHashMap<>();
        for(Bundle bundle:BUNDLES) {
            try {
                Files.createDirectories(directory);Path file=directory.resolve(bundle.scene()+".yml");
                if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))try(InputStream source=CoreContentInstaller.class.getResourceAsStream("/starcore-scenes/"+bundle.scene()+".yml")) {
                    if(source==null)throw new IOException("Missing bundled native scene");Files.copy(source,file);
                }
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Scene must be an ordinary owned file");
                validateScene(file,bundle.scene());
                if(!recoverySafe(file,bundle.scene()))throw new IOException("Owned supplemental scene contains effects outside its recoverable namespace");
                if(!bridge.available()){sceneResults.put(bundle.scene(),"UNAVAILABLE");continue;}
                if(!scenes.contains(bundle.scene())&&bridge.hasScript(bundle.scene())){sceneResults.put(bundle.scene(),"FOREIGN_ID_PRESERVED");continue;}
                if(bridge.installScript(file)&&bridge.hasScript(bundle.scene())) {scenes.add(bundle.scene());sceneResults.put(bundle.scene(),"INSTALLED");}
                else sceneResults.put(bundle.scene(),"REGISTRATION_FAILED");
            } catch(IOException | RuntimeException ex){sceneResults.put(bundle.scene(),"REJECTED:"+ex.getClass().getSimpleName());}
            if(optInNativeQuests)questResults.put(bundle.quest(),installQuest(bundle));
        }
        return new Report(sceneResults,questResults);
    }

    /** Adds native scene references to the three known sample start nodes only after successful
     * owned registration. Existing YAML references, other nodes and standalone stories survive. */
    public synchronized List<StoryDefinition> bind(List<StoryDefinition> stories){return bind(stories,false);}
    public synchronized List<StoryDefinition> bind(List<StoryDefinition> stories,boolean optInNativeQuests) {
        Objects.requireNonNull(stories);if(closed)return List.copyOf(stories);List<StoryDefinition> result=new ArrayList<>();
        for(StoryDefinition story:stories) {
            Bundle bundle=BUNDLES.stream().filter(b->b.story().equals(story.id())).findFirst().orElse(null);Node first=story.nodes().get(story.start());
            if(bundle==null||first==null){result.add(story);continue;}
            String script=first.script(),quest=first.quest();
            if(blank(script)&&scenes.contains(bundle.scene())&&bridge.hasScript(bundle.scene()))script=bundle.scene();
            if(optInNativeQuests&&blank(quest)&&quests.containsKey(bundle.quest())&&bridge.hasQuest(bundle.quest()))quest=bundle.quest();
            if(Objects.equals(script,first.script())&&Objects.equals(quest,first.quest())){result.add(story);continue;}
            Map<String,Node> nodes=new LinkedHashMap<>(story.nodes());nodes.put(story.start(),new Node(first.text(),script,quest,first.quorum(),first.choices(),first.effects(),first.terminal()));
            result.add(new StoryDefinition(story.id(),story.version(),story.title(),story.scope(),story.triggers(),story.condition(),story.start(),story.cooldownSeconds(),nodes,story.voteUnit()));
        }
        return List.copyOf(result);
    }

    private String installQuest(Bundle bundle) {
        Object service=bridge.service("quest.QuestService");
        if(!bridge.available()||service==null||!service.getClass().getName().equals("dev.starcore.starcore.quest.QuestService"))return "UNAVAILABLE";
        try {
            synchronized(service) {
                Object existing=call(service,"getQuest",bundle.quest());OwnedQuest previous=quests.get(bundle.quest());
                if(existing!=null)return previous!=null&&previous.service()==service&&previous.quest()==existing?"INSTALLED":"FOREIGN_ID_PRESERVED";
                Object quest=buildQuest(service.getClass().getClassLoader(),bundle);call(service,"registerQuest",quest);
                if(call(service,"getQuest",bundle.quest())!=quest)return "REGISTRATION_UNCONFIRMED";
                quests.put(bundle.quest(),new OwnedQuest(service,quest));return "INSTALLED_OPT_IN";
            }
        }catch(ReflectiveOperationException | RuntimeException ex){return "REJECTED:"+ex.getClass().getSimpleName();}
    }
    private static Object buildQuest(ClassLoader loader,Bundle bundle)throws ReflectiveOperationException {
        String base="dev.starcore.starcore.quest.";Class<?> type=Class.forName(base+"QuestType",true,loader),difficulty=Class.forName(base+"QuestDifficulty",true,loader);
        Object side=type.getMethod("valueOf",String.class).invoke(null,"SIDE"),easy=difficulty.getMethod("valueOf",String.class).invoke(null,"EASY");
        Object builder=Class.forName(base+"Quest$Builder",true,loader).getConstructor(String.class,String.class,String.class,type,difficulty).newInstance(bundle.quest(),bundle.name(),bundle.description(),side,easy);
        Class<?> objectiveType=Class.forName(base+"QuestObjective$ObjectiveType",true,loader);
        Object craft=objectiveType.getMethod("valueOf",String.class).invoke(null,"CRAFT_ITEM");
        Object objective=Class.forName(base+"QuestObjective",true,loader).getConstructor(String.class,objectiveType,String.class,int.class).newInstance(bundle.quest()+"_craft",craft,bundle.description(),bundle.amount());
        call(objective,"setTargetMaterial",Material.valueOf(bundle.material()));call(builder,"addObjective",objective);call(builder,"category","starchronicle");
        Object quest=call(builder,"build");
        if(!((Collection<?>)call(quest,"getStartCommands")).isEmpty()||!((Collection<?>)call(quest,"getCompleteCommands")).isEmpty()||!Boolean.FALSE.equals(call(call(quest,"getReward"),"hasRewards")))throw new IllegalStateException("Owned quests must contain no commands or rewards");
        return quest;
    }

    static void validateScene(Path file,String expected)throws IOException {
        if(Files.size(file)>1_048_576)throw new IOException("Native scene exceeds 1 MiB");
        ObjectMapper mapper=new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode root=mapper.readTree(file.toFile());
        if(root==null||!expected.equals(root.path("id").asText())||!expected.startsWith("chronicle_"))throw new IOException("Native scene ID mismatch");
        JsonNode nodes=root.path("nodes");String start=root.path("startNode").asText();if(!nodes.isObject()||nodes.isEmpty()||!nodes.has(start))throw new IOException("Missing native start node");
        var iterator=nodes.fields();while(iterator.hasNext()) {
            var node=iterator.next();JsonNode value=node.getValue();validateEffect(value.path("effect").asText(""));String next=value.path("nextNode").asText("");
            if(!next.isBlank()&&!nodes.has(next))throw new IOException("Missing native nextNode");
            var choices=value.path("choices").fields();while(choices.hasNext()) {
                JsonNode choice=choices.next().getValue();if(choice.path("text").asText().isBlank()||!nodes.has(choice.path("targetNode").asText()))throw new IOException("Invalid native choice");validateEffect(choice.path("effect").asText(""));
            }
            if(value.has("effects")||value.has("onEnd"))throw new IOException("Unsupported legacy native scene keys");
        }
    }
    private static void validateEffect(String expression)throws IOException {
        for(String part:expression.split(";"))if(!part.isBlank()) {
            int colon=part.indexOf(':');String command=colon<0?"":part.substring(0,colon).trim().toLowerCase(Locale.ROOT);
            if(!Set.of("set","add","flag","unflag","evidence","removeevidence","increment","decrement","markending").contains(command))throw new IOException("Unsafe/unsupported native scene effect");
        }
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(var entry:quests.entrySet())try {
            OwnedQuest owned=entry.getValue();synchronized(owned.service()) {if(call(owned.service(),"getQuest",entry.getKey())==owned.quest())call(owned.service(),"unregisterQuest",entry.getKey());}
        }catch(ReflectiveOperationException | RuntimeException ignored){/* Never unregister a replacement owned by another plugin. */}
        quests.clear();scenes.clear(); // StarCoreBridge unregisters only its own scene IDs at shutdown.
    }
    private record Bundle(String story,String scene,String quest,String name,String description,String material,int amount){}
    private record OwnedQuest(Object service,Object quest){}
}
