package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.history.NationTradeEvidence;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;

/** The durable native-source inbox, canonical event and contribution views must agree. */
final class NationTradeContributionProjection {
    private NationTradeContributionProjection(){}
    static NationTradeEvidence requireSource(Connection c,WorldEvent event)throws SQLException{
        NationTradeEvidence proof=NationTradeEvidence.fromEvent(event);
        try(var s=c.prepareStatement("SELECT i.json,i.sequence,s.authority_domain,s.sequence FROM committed_history_ingress i JOIN committed_history_sources s ON s.source=i.source WHERE i.source=? AND i.event_id=?")){
            s.setString(1,NationTradeEvidence.SOURCE);s.setString(2,event.id());
            try(var r=s.executeQuery()){
                if(!r.next()||!decode(r.getString(1),WorldEvent.class).equals(event)||r.getLong(2)!=proof.sequence()
                        ||!r.getString(3).equals(proof.domain().toString())||r.getLong(4)<proof.sequence())
                    throw new SecurityException("National trade contribution requires the exact persisted native-source ingress");
            }
        }
        return proof;
    }
    static void record(Connection c,NationTradeEvidence proof,Instant now)throws SQLException{
        Instant recorded=now.isBefore(proof.committedAt())?proof.committedAt():now;
        recordSide(c,proof,"SELLER",proof.seller(),proof.sellerNation(),BigDecimal.valueOf(proof.amount()),"trade.resource:"+proof.resource(),recorded);
        recordSide(c,proof,"BUYER",proof.buyer(),proof.buyerNation(),proof.totalValue(),"trade.treasury",recorded);
    }
    private static void recordSide(Connection c,NationTradeEvidence p,String side,UUID actor,String nation,BigDecimal amount,String unit,Instant recorded)throws SQLException{
        String evidence="Native national trade settlement; domain="+p.domain()+"; sequence="+p.sequence()+"; offer="+p.offerId()+"; side="+side
                +"; resource_domain="+p.resourceDomain()+"; resource_sequence="+p.resourceSequence()+"; treasury_domain="+p.treasuryDomain()
                +"; national and player subjects are views of the same delivery, not additional turnover or rewards";
        for(String subject:java.util.List.of("nation:"+nation,"player:"+actor))
            ContributionLedger.record(c,new ContributionRequest(p.operationId(),ContributionType.TRADE,subject,p.event().id(),actor,nation,amount,unit,p.committedAt(),evidence),recorded);
    }
}
