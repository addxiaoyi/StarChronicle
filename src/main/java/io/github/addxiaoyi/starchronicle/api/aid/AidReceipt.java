package io.github.addxiaoyi.starchronicle.api.aid;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Committed native treasury evidence. Actor/campaign attribution belongs to Chronicle intent. */
public record AidReceipt(String issuer,String authorityDomain,String transactionId,String payerId,
                         String receiverId,BigDecimal amount,String category,String description,Instant appliedAt) {
    public AidReceipt {
        AidRequest.token(issuer,256);AidRequest.uuid(authorityDomain);AidRequest.token(transactionId,256);
        AidRequest.uuid(payerId);AidRequest.uuid(receiverId);AidRequest.amount(amount);
        Objects.requireNonNull(appliedAt);Objects.requireNonNull(description);
        if(!"OTHER".equals(category)||description.length()>1024)throw new IllegalArgumentException("Invalid native aid receipt");
    }
    public boolean matches(AidRequest request){return transactionId.equals(request.outcomeId())
        &&payerId.equals(request.payerId())&&receiverId.equals(request.receiverId())
        &&amount.equals(request.amount())&&category.equals("OTHER")
        &&description.equals(request.description())&&!appliedAt.isBefore(request.createdAt());}

    /** Provider/domain are transport identity, not fields supplied by a player.
     * Callers must pin and compare them separately before accepting this receipt. */
    public boolean matches(AidRequest request, String expectedIssuer, String expectedAuthorityDomain){
        return matches(request)&&issuer.equals(expectedIssuer)&&authorityDomain.equals(expectedAuthorityDomain);
    }
}
