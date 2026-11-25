package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import java.util.*;

/** Detached page from a durable native journal. A baseline is evidence, never a new achievement. */
public record CommittedSourcePage(String source, String domain, long after, List<Entry> entries) {
    public CommittedSourcePage {
        ContributionRequest.token(source,"source",128);
        if(!UUID.fromString(domain).toString().equals(domain))throw new IllegalArgumentException("Noncanonical source domain");
        entries=List.copyOf(entries);
        if(after<0||entries.size()>100)throw new IllegalArgumentException("Invalid committed source page");
        long expected=after;
        for(var entry:entries)if(entry.sequence()!=Math.addExact(expected++,1))throw new IllegalArgumentException("Noncontiguous committed source page");
    }
    public record Entry(long sequence,String operationId,WorldEvent event,boolean baseline,Trade trade) {
        public Entry {
            if(sequence<1)throw new IllegalArgumentException("Positive source sequence required");
            if(operationId==null||operationId.isBlank()||operationId.length()>4096)throw new IllegalArgumentException("Invalid source operation ID");Objects.requireNonNull(event);
        }
    }
    public record Trade(String recordId,String termsHash,String status,List<ContributionRequest> contributions) {
        public Trade {
            ContributionRequest.token(recordId,"trade record",256);
            if(termsHash==null||!termsHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Missing immutable trade terms hash");
            if(!Set.of("COMPLETED","REFUNDED","DISPUTED","CANCELLED").contains(status))throw new IllegalArgumentException("Unknown trade status");
            contributions=List.copyOf(contributions);
            if(contributions.size()>4)throw new IllegalArgumentException("Too many trade contribution projections");
        }
    }
}
