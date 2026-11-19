package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreContentInstallerTest {
    @TempDir Path directory;StarCoreBridge bridge;Set<String> registered;CoreContentInstaller installer;
    @BeforeEach void setup() {
        bridge=mock(StarCoreBridge.class);registered=new HashSet<>();when(bridge.available()).thenReturn(true);
        when(bridge.hasScript(anyString())).thenAnswer(inv->registered.contains(inv.getArgument(0)));
        when(bridge.installScript(any())).thenAnswer(inv->{Path file=inv.getArgument(0);String id=new ObjectMapper(new YAMLFactory()).readTree(file.toFile()).path("id").asText();registered.add(id);return true;});
        installer=new CoreContentInstaller(bridge,directory);
    }
    StoryDefinition story(String id,String nativeScript,String nativeQuest) {
        return new StoryDefinition(id,"1","Sample",Scope.PLAYER,List.of("TEST"),null,"start",100,Map.of(
                "start",new Node("Intro",nativeScript,nativeQuest,2,Map.of("continue",new Choice("Continue","end",null,null,List.of())),List.of(),false),
                "end",new Node("End",null,null,1,Map.of(),List.of(new Effect("receipt","history","DONE","Recorded",null)),true)));
    }
    @Test void threeBundledFilesValidateAndRegisterThroughExistingPublicBridge()throws Exception {
        var report=installer.install();assertEquals(3,report.scenes().size());assertTrue(report.scenes().values().stream().allMatch("INSTALLED"::equals));assertTrue(report.quests().isEmpty());
        for(String id:CoreContentInstaller.sceneIds())CoreContentInstaller.validateScene(directory.resolve("starcore-scenes").resolve(id+".yml"),id);
        verify(bridge,times(3)).installScript(any());verify(bridge,never()).service("quest.QuestService");
    }
    @Test void bindTouchesOnlyKnownStartNodeAfterConfirmedOwnedRegistration() {
        StoryDefinition original=story("last-heir",null,null);installer.install();StoryDefinition bound=installer.bind(List.of(original)).getFirst();
        assertEquals("chronicle_last_heir",bound.nodes().get("start").script());assertNull(bound.nodes().get("start").quest());
        assertEquals(original.nodes().get("end"),bound.nodes().get("end"));assertEquals(original.nodes().get("start").choices(),bound.nodes().get("start").choices());
        assertEquals(original.nodes().get("start").text(),bound.nodes().get("start").text());assertEquals(original.nodes().get("start").quorum(),bound.nodes().get("start").quorum());
        assertEquals(original.condition(),bound.condition());assertEquals(original.cooldownSeconds(),bound.cooldownSeconds());assertNull(original.nodes().get("start").script());
    }
    @Test void standaloneAndFailedNativeRegistrationLeaveDefinitionsIdentical() {
        StoryDefinition original=story("last-heir",null,null);when(bridge.available()).thenReturn(false);installer.install();assertSame(original,installer.bind(List.of(original)).getFirst());
        when(bridge.available()).thenReturn(true);doReturn(false).when(bridge).installScript(any());installer.install();assertSame(original,installer.bind(List.of(original)).getFirst());
    }
    @Test void explicitYamlReferencesAndUnrelatedStoriesArePreserved() {
        installer.install();StoryDefinition explicit=story("last-heir","admin_scene","admin_quest"),unrelated=story("my-campaign",null,null);
        var bound=installer.bind(List.of(explicit,unrelated));assertSame(explicit,bound.get(0));assertSame(unrelated,bound.get(1));
    }
    @Test void externalSameIdContentIsNeverReplacedOrClaimed() {
        registered.addAll(CoreContentInstaller.sceneIds());var report=installer.install();assertTrue(report.scenes().values().stream().allMatch("FOREIGN_ID_PRESERVED"::equals));
        verify(bridge,never()).installScript(any());StoryDefinition original=story("last-heir",null,null);assertSame(original,installer.bind(List.of(original)).getFirst());
    }
    @Test void existingAdminEditedNativeFileIsNotOverwritten()throws Exception {
        installer.install();Path file=directory.resolve("starcore-scenes/chronicle_last_heir.yml");String original=Files.readString(file),edited=original.replace("旧都证词：继承人的请求","管理员保留的标题");
        assertNotEquals(original,edited);Files.writeString(file,edited);installer.install();assertEquals(edited,Files.readString(file));
    }
    @Test void mismatchedFileIdAndCommandEffectsAreRejectedBeforeRegistration()throws Exception {
        Path scenes=directory.resolve("starcore-scenes");Files.createDirectories(scenes);
        Path bad=scenes.resolve("chronicle_last_heir.yml");Files.writeString(bad,"id: chronicle_somebody_else\nstartNode: start\nnodes:\n  start: {dialogues: [text]}\n");
        assertTrue(installer.install().scenes().get("chronicle_last_heir").startsWith("REJECTED"));verify(bridge,never()).installScript(bad);
        Files.writeString(bad,"id: chronicle_last_heir\nstartNode: start\nnodes:\n  start:\n    effect: 'command:op attacker'\n    dialogues: [text]\n");
        assertThrows(IOException.class,()->CoreContentInstaller.validateScene(bad,"chronicle_last_heir"));
    }
    @Test void vanishedRegisteredCapabilityDoesNotLeaveBoundDanglingReference() {
        installer.install();registered.clear();StoryDefinition original=story("last-heir",null,null);assertSame(original,installer.bind(List.of(original)).getFirst());
    }
    @Test void optionalQuestRegistrationAndBindingBothRequireExplicitOptInAndNativeCapability() {
        assertTrue(installer.install().quests().isEmpty());var report=installer.install(true);assertEquals(3,report.quests().size());assertTrue(report.quests().values().stream().allMatch("UNAVAILABLE"::equals));
        StoryDefinition bound=installer.bind(List.of(story("last-heir",null,null)),true).getFirst();assertNull(bound.nodes().get("start").quest());
    }
    @Test void closeNeverUnregistersForeignQuestsAndPreventsFurtherRegistration() {
        installer.install();installer.close();verify(bridge,never()).service("quest.QuestService");assertThrows(IllegalStateException.class,installer::install);
        StoryDefinition original=story("last-heir",null,null);assertSame(original,installer.bind(List.of(original)).getFirst());
    }
}
