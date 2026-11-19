package io.github.addxiaoyi.starchronicle.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** File-level capabilities are separate from registration ownership and native receipts. */
class NativeRecoverySafetyTest {
    @TempDir Path directory;
    private Path source(String id)throws Exception{
        Path file=directory.resolve(id+".yml");
        try(var input=getClass().getResourceAsStream("/starcore-scenes/"+id+".yml")){assertNotNull(input);Files.copy(input,file);}
        return file;
    }
    private Path definition(String nodeEffect,String choiceEffect)throws Exception{
        Path file=directory.resolve("chronicle_last_heir.yml");
        Files.writeString(file,"""
                id: chronicle_last_heir
                title: Recovery safety contract
                startNode: start
                nodes:
                  start:
                    dialogues: [Read the actual native state]
                    effect: '%s'
                    choices:
                      continue:
                        text: Continue
                        targetNode: end
                        effect: '%s'
                  end:
                    dialogues: [Done]
                """.formatted(nodeEffect,choiceEffect));
        return file;
    }

    @ParameterizedTest @ValueSource(strings={"chronicle_last_heir","chronicle_siege_embers","chronicle_silk_road"})
    void allThreeBundledScenesAreAuditedForCollectionOnlyRecovery(String id)throws Exception{
        Path file=source(id);assertTrue(CoreContentInstaller.recoverySafe(file,id));
        String fingerprint=CoreContentInstaller.fingerprint(file);assertTrue(fingerprint.matches("[a-f0-9]{64}"));assertEquals(fingerprint,CoreContentInstaller.fingerprint(file));
    }

    @ParameterizedTest @ValueSource(strings={"add:chronicle_heir_score=1","increment:chronicle_heir_score","decrement:chronicle_heir_score","set:chronicle_heir_score=7"})
    void nonCollectionEffectsInNodeOrChoiceDisableAutomaticRecovery(String effect)throws Exception{
        assertFalse(CoreContentInstaller.recoverySafe(definition(effect,"flag:chronicle_heir_choice"),"chronicle_last_heir"));
        assertFalse(CoreContentInstaller.recoverySafe(definition("flag:chronicle_heir_node",effect),"chronicle_last_heir"));
    }

    @ParameterizedTest @ValueSource(strings={"flag:chronicle_siege_foreign","evidence:foreign_source","markEnding:chronicle_caravan_foreign","flag:chronicle_heir_valid;unflag:foreign_flag"})
    void foreignPrefixesInEitherEffectPositionCannotBeClaimedAsHeirRecoveryState(String effect)throws Exception{
        assertFalse(CoreContentInstaller.recoverySafe(definition(effect,""),"chronicle_last_heir"));
        assertFalse(CoreContentInstaller.recoverySafe(definition("",effect),"chronicle_last_heir"));
    }

    @Test void safeCollectionEffectsRemainBoundToTheirExactSourceFingerprint()throws Exception{
        Path file=definition("flag:chronicle_heir_seen;unflag:chronicle_heir_old;evidence:chronicle_heir_source","removeEvidence:chronicle_heir_obsolete;markEnding:chronicle_heir_read");
        assertTrue(CoreContentInstaller.recoverySafe(file,"chronicle_last_heir"));String before=CoreContentInstaller.fingerprint(file);
        Files.writeString(file,Files.readString(file)+"\n# Deliberate administrator content revision\n");
        assertTrue(CoreContentInstaller.recoverySafe(file,"chronicle_last_heir"));assertNotEquals(before,CoreContentInstaller.fingerprint(file));
        assertFalse(CoreContentInstaller.recoverySafe(file,"chronicle_unowned_scene"));
    }
}
