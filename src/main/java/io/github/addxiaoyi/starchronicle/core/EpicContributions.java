package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.epic.EpicOperation;
import java.math.BigDecimal;
import java.util.*;

/** Quantities are validated before native admission; only a later matched receipt records them. */
public final class EpicContributions {
    private EpicContributions() {}
    public static Map<String,BigDecimal> quantities(EpicOperation operation) {
        if(operation.type()==EpicOperation.Type.TREASURY_AID)
            return Map.of("treasury",ContributionRequest.amount(new BigDecimal(operation.nativeReference())));
        if(operation.type()!=EpicOperation.Type.RESOURCE_AID)return Map.of();
        String raw=operation.nativeReference();if(raw.startsWith("resource="))raw=raw.substring(9);
        Map<String,BigDecimal> result=new TreeMap<>();
        for(String part:raw.split(",",-1)) {
            String[] pair=part.split(":",-1);
            if(pair.length!=2)throw new IllegalArgumentException("Resource aid must use resource:amount");
            String unit="resource."+pair[0].trim().toLowerCase(Locale.ROOT);ContributionRequest.token(unit,"unit",32);
            BigDecimal value=ContributionRequest.amount(BigDecimal.valueOf(Long.parseLong(pair[1])));
            if(result.put(unit,value)!=null)throw new IllegalArgumentException("Duplicate aid resource");
        }
        return Collections.unmodifiableMap(result);
    }
}
