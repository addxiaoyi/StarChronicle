package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.epic.*;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Read-only adapter for completed Chronicle quest gates backed by StarCore evidence. */
public final class StarCoreQuestGateEpicProvider implements EpicOperationProvider {
    private static final String PREFIX="starcore.quest.evidence.v1:";
    private final String providerId;
    private final String authorityDomain;
    private final Function<EpicOperation,CompletionStage<EpicObservation>> lookup;

    public StarCoreQuestGateEpicProvider(String providerId,
                                         Function<EpicOperation,CompletionStage<EpicObservation>> lookup) {
        if(providerId==null||!providerId.startsWith(PREFIX))throw new IllegalArgumentException("Quest evidence provider ID must include its storage authority");
        this.authorityDomain=UUID.fromString(providerId.substring(PREFIX.length())).toString();
        if(!providerId.equals(PREFIX+authorityDomain))throw new IllegalArgumentException("Noncanonical quest evidence provider ID");
        this.providerId=providerId;
        this.lookup=Objects.requireNonNull(lookup);
    }

    @Override public String providerId(){return providerId;}
    @Override public String authorityDomain(){return authorityDomain;}
    @Override public boolean supports(EpicOperation operation){return operation!=null&&operation.type()==EpicOperation.Type.QUEST_GATE;}
    @Override public CompletionStage<EpicObservation> apply(EpicOperation operation){return lookup(operation);}
    @Override public CompletionStage<EpicObservation> lookup(EpicOperation operation){
        Objects.requireNonNull(operation);
        if(operation.type()!=EpicOperation.Type.QUEST_GATE)return java.util.concurrent.CompletableFuture.completedFuture(EpicObservation.unknown("Quest-gate provider cannot handle this operation type"));
        return lookup.apply(operation);
    }
}
