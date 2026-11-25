package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Adapts the existing receipt-backed national treasury provider to epic operation IDs. */
public final class StarCoreTreasuryEpicProvider implements EpicOperationProvider {
    private final AidProvider provider;

    public StarCoreTreasuryEpicProvider(AidProvider provider) { this.provider=Objects.requireNonNull(provider); }

    @Override public String providerId() { return provider.providerId(); }
    @Override public String authorityDomain() { return provider.authorityDomain(); }
    @Override public boolean supports(EpicOperation operation) {
        try { request(operation);return true; } catch(RuntimeException invalid) { return false; }
    }

    @Override public CompletionStage<EpicObservation> apply(EpicOperation operation) {
        return apply(operation,()->true);
    }
    @Override public CompletionStage<EpicObservation> apply(EpicOperation operation,java.util.function.BooleanSupplier lease) {
        AidRequest request=request(operation);
        if(!lease.getAsBoolean())return java.util.concurrent.CompletableFuture.completedFuture(EpicObservation.unknown("Treasury epic lease expired"));
        CompletionStage<AidObservation> call=provider instanceof StarCoreTreasuryAidProvider nativeProvider
                ? nativeProvider.submit(request,lease) : provider.submit(request);
        return call.thenApply(result->map(operation,request,result));
    }

    @Override public CompletionStage<EpicObservation> lookup(EpicOperation operation) {
        AidRequest request=request(operation);
        return provider.lookup(request).thenApply(result->map(operation,request,result));
    }

    private static AidRequest request(EpicOperation operation) {
        Objects.requireNonNull(operation);
        if(operation.type()!=EpicOperation.Type.TREASURY_AID||!AidRequest.PERMISSION.equals(operation.permission()))
            throw new IllegalArgumentException("Treasury epic operation has an invalid type or permission");
        return new AidRequest(operation.operationId(),operation.instanceId(),operation.sourceEventId(),
                operation.campaignId(),AidRequest.OPERATION,operation.definitionFingerprint(),operation.actorId(),
                operation.nationId(),operation.target(),new BigDecimal(operation.nativeReference()),operation.requestedAt());
    }

    private EpicObservation map(EpicOperation operation,AidRequest request,AidObservation observation) {
        if(observation==null)return EpicObservation.unknown("Treasury provider returned no observation");
        return switch(observation.status()) {
            case ABSENT -> new EpicObservation(EpicObservation.Status.ABSENT,null,observation.detail());
            case REJECTED -> new EpicObservation(EpicObservation.Status.REJECTED,null,observation.detail());
            case UNKNOWN -> EpicObservation.unknown(observation.detail());
            case COMMITTED -> {
                AidReceipt nativeReceipt=observation.receipt();
                if(nativeReceipt==null||!nativeReceipt.matches(request,providerId(),authorityDomain()))
                    yield EpicObservation.unknown("Treasury receipt did not match the pinned aid intent");
                EpicReceipt receipt=new EpicReceipt(providerId(),authorityDomain(),operation.operationId(),
                        operation.campaignId(),operation.instanceId(),operation.sourceEventId(),operation.phaseId(),
                        operation.type(),operation.actorId(),operation.nationId(),operation.target(),operation.nativeReference(),
                        operation.definitionFingerprint(),operation.permission(),nativeReceipt.transactionId(),
                        nativeReceipt.appliedAt(),"Committed StarCore treasury receipt: "+nativeReceipt.description());
                yield new EpicObservation(EpicObservation.Status.COMMITTED,receipt,"Committed treasury transfer receipt");
            }
        };
    }
}
