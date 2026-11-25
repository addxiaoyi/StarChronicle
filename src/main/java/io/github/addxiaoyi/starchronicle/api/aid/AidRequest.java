package io.github.addxiaoyi.starchronicle.api.aid;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable Chronicle authorization intent. The native receipt does not attest the actor. */
public record AidRequest(String outcomeId,String instanceId,String sourceEventId,String campaign,
                         String operation,String definitionFingerprint,UUID actorId,String payerId,
                         String receiverId,BigDecimal amount,Instant createdAt) {
    public static final String OPERATION="TREASURY_TRANSFER",PERMISSION="starcore.nation.bank.withdraw";
    public static final BigDecimal MAX_AMOUNT=new BigDecimal("1000000000.00");
    public AidRequest {
        token(outcomeId,256);token(instanceId,256);token(sourceEventId,256);campaign=campaign(campaign);
        if(!OPERATION.equals(operation))throw new IllegalArgumentException("Unsupported aid operation");
        if(definitionFingerprint==null||!definitionFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid aid definition fingerprint");
        java.util.Objects.requireNonNull(actorId);java.util.Objects.requireNonNull(createdAt);
        uuid(payerId);uuid(receiverId);if(payerId.equals(receiverId))throw new IllegalArgumentException("Aid requires distinct nations");
        amount=amount(amount);
    }
    public static String campaign(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid aid campaign");return value;}
    public static BigDecimal amount(BigDecimal value){
        if(value==null||value.scale()!=2||value.signum()<=0||value.compareTo(MAX_AMOUNT)>0)throw new IllegalArgumentException("Aid amount must have exactly two decimal places and be between 0.01 and 1000000000.00");
        return value;
    }
    public static String uuid(String value){if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Canonical nation/domain UUID required");return value;}
    public static String token(String value,int limit){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,"+limit+"}"))throw new IllegalArgumentException("Invalid aid stable ID");return value;}
    /** Description is part of the pinned request and checked even though Core's matches omits it. */
    public String description(){return "StarChronicle aid "+campaign+" outcome="+outcomeId+" definition="+definitionFingerprint;}
}
