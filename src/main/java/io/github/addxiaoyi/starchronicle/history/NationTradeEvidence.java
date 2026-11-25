package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** A fully funded national sale; legacy TradeHistoryService rows cannot produce this evidence. */
public record NationTradeEvidence(UUID domain, long sequence, UUID offerId, UUID seller, UUID buyer,
                                 String sellerNation, String buyerNation, String resource, long amount,
                                 long unitPriceMinor, UUID resourceDomain, UUID treasuryDomain,
                                 long resourceSequence, Instant createdAt, Instant expiresAt, Instant committedAt) {
    public static final String SOURCE="starcore.nation-trade.settlements.v1";
    public static final String ISSUER="StarCore.NationTradeService";
    public NationTradeEvidence {
        Objects.requireNonNull(domain);Objects.requireNonNull(offerId);Objects.requireNonNull(seller);Objects.requireNonNull(buyer);
        Objects.requireNonNull(resourceDomain);Objects.requireNonNull(treasuryDomain);
        canonicalUuid(sellerNation);canonicalUuid(buyerNation);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(expiresAt);Objects.requireNonNull(committedAt);
        if(sequence<1||resourceSequence<1||seller.equals(buyer)||sellerNation.equals(buyerNation))throw new IllegalArgumentException("Invalid bilateral trade identity");
        if(resource==null||!resource.matches("[a-z0-9_.:-]{1,64}")||amount<1||amount>100_000_000L||unitPriceMinor<1||unitPriceMinor>100_000_000_000L
                ||BigDecimal.valueOf(unitPriceMinor,2).multiply(BigDecimal.valueOf(amount)).compareTo(new BigDecimal("1000000000000"))>0)
            throw new IllegalArgumentException("Invalid national trade amounts");
        if(createdAt.toEpochMilli()<0||!expiresAt.isAfter(createdAt)||expiresAt.isAfter(createdAt.plusSeconds(86_400))
                ||committedAt.isBefore(createdAt)||!committedAt.isBefore(expiresAt))throw new IllegalArgumentException("Invalid national trade timing");
        if(!createdAt.equals(Instant.ofEpochMilli(createdAt.toEpochMilli()))||!expiresAt.equals(Instant.ofEpochMilli(expiresAt.toEpochMilli()))
                ||!committedAt.equals(Instant.ofEpochMilli(committedAt.toEpochMilli())))throw new IllegalArgumentException("Trade timestamps must use persisted millisecond precision");
    }
    public BigDecimal totalValue(){return BigDecimal.valueOf(unitPriceMinor,2).multiply(BigDecimal.valueOf(amount));}
    public String operationId(){return "nation-trade:"+domain+":"+offerId;}
    public WorldEvent event(){
        Map<String,String> facts=new TreeMap<>();
        facts.put("title","国家贸易已成交");facts.put("time_basis","COMMITTED_SOURCE");
        facts.put("source.authority_domain",domain.toString());facts.put("source.sequence",Long.toString(sequence));
        facts.put("trade.offer_id",offerId.toString());facts.put("trade.seller_player",seller.toString());facts.put("trade.buyer_player",buyer.toString());
        facts.put("trade.seller_nation",sellerNation);facts.put("trade.buyer_nation",buyerNation);facts.put("trade.resource",resource);
        facts.put("trade.amount",Long.toString(amount));facts.put("trade.unit_price_minor",Long.toString(unitPriceMinor));facts.put("trade.total_value",totalValue().toPlainString());
        facts.put("trade.resource_domain",resourceDomain.toString());facts.put("trade.treasury_domain",treasuryDomain.toString());
        facts.put("trade.resource_sequence",Long.toString(resourceSequence));facts.put("trade.transaction_id","nation-trade:"+offerId);
        facts.put("trade.created_at",createdAt.toString());facts.put("trade.expires_at",expiresAt.toString());
        facts.put("trade.asset_atomicity","NATIVE_SQL_TRANSACTION");facts.put("trade.funding","NATION_TREASURY");
        return new WorldEvent("starcore:nation-trade:"+domain+":"+sequence,"NATIONAL_TRADE_COMMITTED",committedAt,
                Set.of(seller,buyer),Set.of(sellerNation,buyerNation),facts,ISSUER,
                "Native national sale; authority="+domain+"; sequence="+sequence+"; offer="+offerId
                        +"; resource_domain="+resourceDomain+"; resource_sequence="+resourceSequence+"; treasury_domain="+treasuryDomain
                        +"; both national inventories, national treasury transfer, consent terms and settlement journal share one SQL commit; no external wallet payment or player inventory delivery");
    }
    public static NationTradeEvidence fromEvent(WorldEvent event){
        var f=event.facts();
        var proof=new NationTradeEvidence(canonicalUuid(f.get("source.authority_domain")),number(f,"source.sequence"),canonicalUuid(f.get("trade.offer_id")),
                canonicalUuid(f.get("trade.seller_player")),canonicalUuid(f.get("trade.buyer_player")),f.get("trade.seller_nation"),f.get("trade.buyer_nation"),f.get("trade.resource"),
                number(f,"trade.amount"),number(f,"trade.unit_price_minor"),canonicalUuid(f.get("trade.resource_domain")),canonicalUuid(f.get("trade.treasury_domain")),
                number(f,"trade.resource_sequence"),Instant.parse(f.get("trade.created_at")),Instant.parse(f.get("trade.expires_at")),event.occurredAt());
        if(!proof.event().equals(event))throw new IllegalArgumentException("National trade event is not its canonical source projection");
        return proof;
    }
    public static UUID canonicalUuid(String id){if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Expected canonical UUID");return UUID.fromString(id);}
    private static long number(Map<String,String> facts,String key){String value=facts.get(key);long parsed=Long.parseLong(value);if(!Long.toString(parsed).equals(value))throw new IllegalArgumentException("Invalid trade integer");return parsed;}
}
