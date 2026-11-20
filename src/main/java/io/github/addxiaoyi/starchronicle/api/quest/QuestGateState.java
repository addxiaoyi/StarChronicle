package io.github.addxiaoyi.starchronicle.api.quest;

import io.github.addxiaoyi.starchronicle.core.QuestGateLedger;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only task-gate presentation. Visibility is filtered by the authoritative service before projection. */
public record QuestGateState(String instanceId, String nodeId, boolean ready,
                             List<String> blockedEffectIds, List<GateSummary> gates) {
    public QuestGateState {
        Objects.requireNonNull(instanceId,"instanceId");Objects.requireNonNull(nodeId,"nodeId");
        blockedEffectIds=List.copyOf(blockedEffectIds);gates=List.copyOf(gates);
        if(ready&&!blockedEffectIds.isEmpty())throw new IllegalArgumentException("A ready task view cannot contain blocked effects");
    }

    /** The caller must first remove personal and country gates the viewer is not authorized to inspect. */
    public static QuestGateState from(String instanceId,String nodeId,QuestGateLedger.View view) {
        Objects.requireNonNull(view,"view");
        List<GateSummary> summaries=view.gates().stream().map(gate->{
            if(!Objects.equals(instanceId,gate.instanceId())||!Objects.equals(nodeId,gate.nodeId()))
                throw new IllegalArgumentException("Task gate does not belong to the projected instance/node");
            return new GateSummary(gate.id(),gate.playerId(),gate.effectId(),gate.questId(),gate.mode(),
                    gate.expectedNationId(),gate.status(),gate.providerId(),
                    gate.acceptedRun()==null?"":gate.acceptedRun().runId(),
                    gate.completion()==null?"":gate.completion().completionId(),gate.message());
        }).sorted(Comparator.comparing(GateSummary::effectId).thenComparing(g->g.playerId().toString())
                .thenComparing(GateSummary::gateId)).toList();
        return new QuestGateState(instanceId,nodeId,view.ready(),view.blockedEffectIds(),summaries);
    }

    /** IDs and human-readable status only: no native evidence, fingerprints or mutable quest objects. */
    public record GateSummary(String gateId, UUID playerId, String effectId, String questId,
                              QuestRequest.Mode mode, String nationId, QuestObservation.Status status,
                              String providerId, String runId, String completionId, String message) {
        public GateSummary {
            Objects.requireNonNull(gateId,"gateId");Objects.requireNonNull(playerId,"playerId");
            Objects.requireNonNull(effectId,"effectId");Objects.requireNonNull(questId,"questId");
            Objects.requireNonNull(mode,"mode");Objects.requireNonNull(status,"status");
            nationId=Objects.toString(nationId,"");providerId=Objects.toString(providerId,"");
            runId=Objects.toString(runId,"");completionId=Objects.toString(completionId,"");
            message=Objects.toString(message,"");
        }
    }
}
