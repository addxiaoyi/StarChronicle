package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.aid.*;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class StarCoreTreasuryEpicProviderTest {
    private static final String DOMAIN="00000000-0000-0000-0000-000000000099";
    private static final String NATION="00000000-0000-0000-0000-000000000011";
    private static final String RELIEF="00000000-0000-0000-0000-000000000019";
    private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant REQUESTED=Instant.parse("2026-11-25T00:00:00Z");

    @Test void mapsOnlyTheMatchingNativeTreasuryReceipt() {
        AidProvider nativeProvider=new AidProvider() {
            @Override public String providerId(){return "starcore.treasury";}
            @Override public String authorityDomain(){return DOMAIN;}
            @Override public java.util.concurrent.CompletionStage<AidObservation> lookup(AidRequest request){return CompletableFuture.completedFuture(new AidObservation(AidObservation.Status.ABSENT,null,"absent"));}
            @Override public java.util.concurrent.CompletionStage<AidObservation> submit(AidRequest request){
                var receipt=new AidReceipt(providerId(),authorityDomain(),request.outcomeId(),request.payerId(),request.receiverId(),
                        request.amount(),"OTHER",request.description(),request.createdAt().plusSeconds(1));
                return CompletableFuture.completedFuture(new AidObservation(AidObservation.Status.COMMITTED,receipt,"committed"));
            }
        };
        var provider=new StarCoreTreasuryEpicProvider(nativeProvider);
        var operation=new EpicOperation("epic:treasury-operation","heir-strategy","instance-1","chronicle:signup",
                "heir-supplies",EpicOperation.Type.TREASURY_AID,UUID.fromString("00000000-0000-0000-0000-000000000001"),
                NATION,RELIEF,"10.00",HASH,AidRequest.PERMISSION,REQUESTED);

        var observation=provider.apply(operation).toCompletableFuture().join();
        var receipt=EpicContract.requireCommitted(operation,observation,provider.providerId(),provider.authorityDomain());
        assertEquals(operation.operationId(),receipt.operationId());
        assertEquals(operation.operationId(),receipt.nativeReceiptId());
        assertEquals(REQUESTED.plusSeconds(1),receipt.appliedAt());
    }
}
