package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Native delivery/tax receipt with explicitly weaker external wallet acknowledgement evidence. */
public record PlayerTradeEvidence(UUID domain,long sequence,UUID offerId,UUID seller,UUID buyer,
        String sellerNation,String buyerNation,UUID resourceDomain,UUID treasuryDomain,String provider,
        String resource,long amount,BigDecimal unitPrice,long feeMinor,long tariffMinor,
        Instant createdAt,Instant expiresAt,Instant acceptedAt,Instant settledAt) {
    public static final String SOURCE="starcore.player-trade.settlements.v1";
    public static final String ISSUER="StarCore.PlayerTradeRecoveryService";

    public PlayerTradeEvidence {
        Objects.requireNonNull(domain);Objects.requireNonNull(offerId);Objects.requireNonNull(seller);Objects.requireNonNull(buyer);
        Objects.requireNonNull(resourceDomain);Objects.requireNonNull(treasuryDomain);Objects.requireNonNull(unitPrice);
        NationTradeEvidence.canonicalUuid(sellerNation);NationTradeEvidence.canonicalUuid(buyerNation);
        unitPrice=unitPrice.stripTrailingZeros();
        if(sequence<1||seller.equals(buyer)||provider==null||provider.isBlank()||provider.length()>512
                ||resource==null||!resource.matches("[a-z0-9_.:-]{1,64}")||amount<1||amount>1_000_000_000_000L
                ||unitPrice.signum()<=0||unitPrice.compareTo(new BigDecimal("1000000000"))>0||feeMinor<0||tariffMinor<0)
            throw new IllegalArgumentException("Invalid player trade terms");
        BigDecimal gross=unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2,RoundingMode.HALF_UP);
        if(gross.signum()<=0||gross.compareTo(new BigDecimal("1000000000000"))>0
                ||BigDecimal.valueOf(Math.addExact(feeMinor,tariffMinor),2).compareTo(gross)>0
                ||(sellerNation.equals(buyerNation)&&tariffMinor!=0))throw new IllegalArgumentException("Invalid player trade amounts");
        for(Instant time:List.of(createdAt,expiresAt,acceptedAt,settledAt))
            if(time.toEpochMilli()<0||!time.equals(Instant.ofEpochMilli(time.toEpochMilli())))throw new IllegalArgumentException("Invalid player trade timestamp");
        if(!expiresAt.isAfter(createdAt)||expiresAt.isAfter(createdAt.plusSeconds(604800))
                ||acceptedAt.isBefore(createdAt)||!acceptedAt.isBefore(expiresAt)||settledAt.isBefore(acceptedAt))
            throw new IllegalArgumentException("Invalid player trade timing");
    }
    public BigDecimal gross(){return unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2,RoundingMode.HALF_UP);}
    public BigDecimal total(){return gross().add(BigDecimal.valueOf(Math.addExact(feeMinor,tariffMinor),2));}
    public String operationId(){return "player-trade:"+domain+":"+offerId;}
    public WorldEvent event(){
        var f=new TreeMap<String,String>();
        f.put("title","玩家贸易已结算");f.put("time_basis","COMMITTED_SOURCE");
        f.put("source.authority_domain",domain.toString());f.put("source.sequence",Long.toString(sequence));
        f.put("trade.offer_id",offerId.toString());f.put("trade.seller_player",seller.toString());f.put("trade.buyer_player",buyer.toString());
        f.put("trade.seller_nation",sellerNation);f.put("trade.buyer_nation",buyerNation);
        f.put("trade.resource_domain",resourceDomain.toString());f.put("trade.treasury_domain",treasuryDomain.toString());
        f.put("trade.provider",provider);f.put("trade.resource",resource);f.put("trade.amount",Long.toString(amount));
        f.put("trade.unit_price",unitPrice.toPlainString());f.put("trade.gross",gross().toPlainString());f.put("trade.total",total().toPlainString());
        f.put("trade.fee_minor",Long.toString(feeMinor));f.put("trade.tariff_minor",Long.toString(tariffMinor));
        f.put("trade.created_at",createdAt.toString());f.put("trade.expires_at",expiresAt.toString());f.put("trade.accepted_at",acceptedAt.toString());
        f.put("trade.asset_atomicity","NATIVE_DELIVERY_AND_TAXES");f.put("trade.wallet_evidence","PROVIDER_ACKNOWLEDGED");
        f.put("trade.inventory","NATIONAL_STOCKPILE");
        return new WorldEvent("starcore:player-trade:"+domain+":"+sequence,"PLAYER_TRADE_SETTLED",settledAt,
                Set.of(seller,buyer),new HashSet<>(List.of(sellerNation,buyerNation)),f,ISSUER,
                "Native player trade settlement; domain="+domain+"; sequence="+sequence+"; offer="+offerId
                +"; provider="+provider+"; original actors and frozen terms; national inventory escrow delivered with fee/tariff treasury credits in one SQL commit;"
                +" wallet calls acknowledged by provider, not a durable external payment receipt or a joint wallet SQL transaction");
    }
    public static PlayerTradeEvidence fromEvent(WorldEvent event){
        var f=event.facts();
        var p=new PlayerTradeEvidence(id(f,"source.authority_domain"),number(f,"source.sequence"),id(f,"trade.offer_id"),
                id(f,"trade.seller_player"),id(f,"trade.buyer_player"),f.get("trade.seller_nation"),f.get("trade.buyer_nation"),
                id(f,"trade.resource_domain"),id(f,"trade.treasury_domain"),f.get("trade.provider"),f.get("trade.resource"),
                number(f,"trade.amount"),new BigDecimal(f.get("trade.unit_price")),number(f,"trade.fee_minor"),number(f,"trade.tariff_minor"),
                Instant.parse(f.get("trade.created_at")),Instant.parse(f.get("trade.expires_at")),Instant.parse(f.get("trade.accepted_at")),event.occurredAt());
        if(!p.event().equals(event))throw new IllegalArgumentException("Player trade event differs from canonical source");
        return p;
    }
    private static UUID id(Map<String,String> f,String key){return NationTradeEvidence.canonicalUuid(f.get(key));}
    private static long number(Map<String,String> f,String key){String s=f.get(key);long n=Long.parseLong(s);if(!Long.toString(n).equals(s))throw new IllegalArgumentException("Invalid player trade integer");return n;}
}
