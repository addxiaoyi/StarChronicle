package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionRequest;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.history.PlayerTradeEvidence;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;

final class PlayerTradeContributionProjection {
    private PlayerTradeContributionProjection(){}
    static PlayerTradeEvidence requireSource(Connection c,WorldEvent event)throws SQLException{
        var proof=PlayerTradeEvidence.fromEvent(event);
        try(var s=c.prepareStatement("SELECT i.json,i.sequence,s.authority_domain,s.sequence FROM committed_history_ingress i JOIN committed_history_sources s ON s.source=i.source WHERE i.source=? AND i.event_id=?")){
            s.setString(1,PlayerTradeEvidence.SOURCE);s.setString(2,event.id());
            try(var r=s.executeQuery()){
                if(!r.next()||!decode(r.getString(1),WorldEvent.class).equals(event)||r.getLong(2)!=proof.sequence()
                        ||!r.getString(3).equals(proof.domain().toString())||r.getLong(4)<proof.sequence())
                    throw new SecurityException("Player trade contribution requires exact persisted native settlement ingress");
            }
        }
        return proof;
    }
    static void record(Connection c,PlayerTradeEvidence proof,Instant now)throws SQLException{
        Instant recorded=now.isBefore(proof.settledAt())?proof.settledAt():now;
        side(c,proof,"SELLER",proof.seller(),proof.sellerNation(),BigDecimal.valueOf(proof.amount()),"trade.player-resource:"+proof.resource(),recorded);
        side(c,proof,"BUYER",proof.buyer(),proof.buyerNation(),proof.total(),"trade.wallet-acknowledged",recorded);
    }
    private static void side(Connection c,PlayerTradeEvidence p,String side,UUID actor,String nation,BigDecimal amount,String unit,Instant recorded)throws SQLException{
        String evidence="Native player trade settlement; domain="+p.domain()+"; sequence="+p.sequence()+"; offer="+p.offerId()+"; side="+side
                +"; resource_domain="+p.resourceDomain()+"; treasury_domain="+p.treasuryDomain()
                +"; wallet evidence is provider acknowledgement, not external payment durability; player/nation are views, not extra turnover or rewards";
        for(String subject:java.util.List.of("nation:"+nation,"player:"+actor))
            ContributionLedger.record(c,new ContributionRequest(p.operationId()+":"+side,ContributionType.TRADE,subject,p.event().id(),actor,nation,amount,unit,p.settledAt(),evidence),recorded);
    }
}
