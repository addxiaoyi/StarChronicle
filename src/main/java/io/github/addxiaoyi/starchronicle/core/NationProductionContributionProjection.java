package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.history.NationProductionEvidence;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;

final class NationProductionContributionProjection {
    private NationProductionContributionProjection(){}
    static NationProductionEvidence requireSource(Connection c,WorldEvent event)throws SQLException{
        var proof=NationProductionEvidence.fromEvent(event);
        try(var s=c.prepareStatement("SELECT i.json,i.sequence,s.authority_domain,s.sequence FROM committed_history_ingress i JOIN committed_history_sources s ON s.source=i.source WHERE i.source=? AND i.event_id=?")){
            s.setString(1,NationProductionEvidence.SOURCE);s.setString(2,event.id());
            try(var r=s.executeQuery()){
                if(!r.next()||!decode(r.getString(1),WorldEvent.class).equals(event)||r.getLong(2)!=proof.sequence()
                        ||!r.getString(3).equals(proof.domain().toString())||r.getLong(4)<proof.sequence())
                    throw new SecurityException("Civilization contribution requires the exact persisted native production ingress");
            }
        }
        return proof;
    }
    static void record(Connection c,NationProductionEvidence proof,Instant now)throws SQLException{
        // A changed output set must not evade per-resource idempotency by using new suffixes.
        try(var s=c.prepareStatement("SELECT DISTINCT source_event_id FROM contributions WHERE operation_id LIKE ? AND type=?")){
            s.setString(1,proof.operationId()+":%");s.setString(2,ContributionType.CIVILIZATION.name());
            try(var r=s.executeQuery()){
                while(r.next())if(!proof.event().id().equals(r.getString(1)))
                    throw new IllegalStateException("Production run already belongs to another completion");
            }
        }
        Instant recorded=now.isBefore(proof.committedAt())?proof.committedAt():now;
        String evidence="Native national production; domain="+proof.domain()+"; sequence="+proof.sequence()+"; run="+proof.runId()
                +"; resource_domain="+proof.resourceDomain()+"; original_actor="+proof.actor()
                +"; national and player subjects are views of the same output, not additional production or rewards";
        for(var output:proof.outputs().entrySet())
            for(String subject:java.util.List.of("nation:"+proof.nation(),"player:"+proof.actor()))
                ContributionLedger.record(c,new ContributionRequest(proof.operationId()+":"+output.getKey(),ContributionType.CIVILIZATION,
                        subject,proof.event().id(),proof.actor(),proof.nation(),BigDecimal.valueOf(output.getValue()),"production.resource:"+output.getKey(),
                        proof.committedAt(),evidence),recorded);
    }
}
