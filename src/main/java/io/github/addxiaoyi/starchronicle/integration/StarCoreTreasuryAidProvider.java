package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.aid.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/**
 * Narrow StarCore treasury adapter for national aid. It uses only the public,
 * receipt-backed NationTransferService contract and the persisted Treasury
 * authorityDomainId. No balance writes, commands, personal economy, or budget
 * services are involved.
 */
public final class StarCoreTreasuryAidProvider implements AidProvider {
    public static final String PROVIDER_ID="starcore.treasury";
    private static final String BASE="dev.starcore.starcore.";
    private final StarCoreBridge bridge;
    private final String authorityDomain;

    public StarCoreTreasuryAidProvider(StarCoreBridge bridge) {
        this.bridge=Objects.requireNonNull(bridge);
        this.authorityDomain=readDomain();
    }

    @Override public String providerId(){return PROVIDER_ID;}
    @Override public String authorityDomain(){return authorityDomain;}

    @Override public CompletionStage<AidObservation> lookup(AidRequest request) {
        Objects.requireNonNull(request);
        CompletableFuture<AidObservation> result=new CompletableFuture<>();
        bridge.global(()->{
            try { ensureCurrentDomain(); result.complete(readReceipt(request)); }
            catch(Throwable error){ result.complete(AidObservation.unknown("Treasury receipt lookup failed: "+message(error))); }
        },()->result.complete(AidObservation.unknown("Global treasury lookup unavailable")));
        return result;
    }

    @Override public CompletionStage<AidObservation> submit(AidRequest request) {
        return submit(request,()->true);
    }
    CompletionStage<AidObservation> submit(AidRequest request,java.util.function.BooleanSupplier lease) {
        Objects.requireNonNull(request);
        return lookup(request).thenCompose(existing->switch(existing.status()) {
            case COMMITTED,UNKNOWN -> CompletableFuture.completedFuture(existing);
            case REJECTED -> CompletableFuture.completedFuture(existing);
            case ABSENT -> invokeOnce(request,lease);
        });
    }

    private CompletionStage<AidObservation> invokeOnce(AidRequest request,java.util.function.BooleanSupplier lease) {
        CompletableFuture<AidObservation> result=new CompletableFuture<>();
        bridge.ownedPlayer(request.actorId(),player->{
            try {
                if(!lease.getAsBoolean())throw new IllegalStateException("Treasury provider lease expired before invocation");
                ensureCurrentDomain();
                if(player==null||!player.isOnline()||!request.actorId().equals(player.getUniqueId())
                        ||!player.hasPermission("starchronicle.use")||!player.hasPermission(AidRequest.PERMISSION)) {
                    result.complete(new AidObservation(AidObservation.Status.REJECTED,null,"Current player lacks treasury authority"));return;
                }
                Access access=access();
                Object transfer;
                synchronized(access.nations) {
                    if(!lease.getAsBoolean())throw new IllegalStateException("Treasury provider lease expired before mutation");
                    Object payer=access.id(request.payerId()),receiver=access.id(request.receiverId());
                    if(empty(access.nations,"nationById",payer)||empty(access.nations,"nationById",receiver)
                            ||!request.payerId().equals(access.nationOf(player.getUniqueId()))
                            ||!Boolean.TRUE.equals(call(access.nations,"isFounder",player.getUniqueId(),payer))
                            ||!access.nativePermission(player)) {
                        result.complete(new AidObservation(AidObservation.Status.REJECTED,null,"Native nation membership or founder authority failed"));return;
                    }
                    Object category=Arrays.stream(access.category.getEnumConstants())
                            .filter(value->((Enum<?>)value).name().equals("OTHER"))
                            .findFirst().orElseThrow(()->new IllegalStateException("StarCore treasury has no OTHER category"));
                    Object payload=access.request.getConstructor(String.class,access.idType,access.idType,BigDecimal.class,access.category,String.class)
                            .newInstance(request.outcomeId(),payer,receiver,request.amount(),category,request.description());
                    transfer=call(access.transfers,"transfer",payload);
                }
                String nativeId=Objects.toString(call(transfer,"transactionId"),"");
                if(!request.outcomeId().equals(nativeId)) { result.complete(AidObservation.unknown("Native transfer returned a different transaction ID"));return; }
                AidObservation receipt=readReceipt(access,request);
                if(receipt.status()==AidObservation.Status.COMMITTED||receipt.status()==AidObservation.Status.UNKNOWN){result.complete(receipt);return;}
                String status=Objects.toString(call(transfer,"status"),"");
                result.complete(Set.of("REJECTED","INSUFFICIENT_FUNDS").contains(status)
                        ?new AidObservation(AidObservation.Status.REJECTED,null,"Native treasury result "+status)
                        :AidObservation.unknown("Native result "+status+" has no durable receipt"));
            } catch(Throwable error) { result.complete(AidObservation.unknown("Native treasury invocation failed: "+message(error))); }
        },()->result.complete(AidObservation.unknown("Player owner disappeared before treasury invocation")));
        return result;
    }

    private AidObservation readReceipt(AidRequest request)throws ReflectiveOperationException { return readReceipt(access(),request); }
    private AidObservation readReceipt(Access access,AidRequest request)throws ReflectiveOperationException {
        Object raw=unwrap(call(access.transfers,"transferReceipt",request.outcomeId()));
        if(raw==null)return new AidObservation(AidObservation.Status.ABSENT,null,"No committed native treasury receipt");
        return new AidObservation(AidObservation.Status.COMMITTED,receipt(access,raw),"Committed native treasury receipt");
    }
    private AidReceipt receipt(Access access,Object raw)throws ReflectiveOperationException {
        Class<?> type=raw.getClass();
        return new AidReceipt(PROVIDER_ID,authorityDomain,
                Objects.toString(call(raw,"transactionId")),
                nationText(call(raw,"payerId")), nationText(call(raw,"receiverId")),
                (BigDecimal)call(raw,"amount"),Objects.toString(call(raw,"category")),
                Objects.toString(call(raw,"description"),""),(Instant)call(raw,"appliedAt"));
    }
    private String readDomain() {
        try { Object treasury=bridge.service("module.treasury.TreasuryService");String value=Objects.toString(call(treasury,"authorityDomainId"),"");return AidRequest.uuid(value); }
        catch(ReflectiveOperationException|RuntimeException error){throw new IllegalStateException("StarCore treasury authority domain is unavailable; aid is disabled",error);}
    }
    private void ensureCurrentDomain() {
        String current=readDomain();
        if(!authorityDomain.equals(current))throw new IllegalStateException("StarCore treasury authority domain changed");
    }
    private Access access()throws ReflectiveOperationException {
        Object nations=bridge.service("module.nation.NationService"),transfers=bridge.service("module.treasury.NationTransferService");
        if(nations==null||transfers==null)throw new IllegalStateException("StarCore nation/treasury services unavailable");
        ClassLoader loader=Objects.requireNonNull(bridge.coreLoader(),"StarCore classloader unavailable");
        Class<?> id=Class.forName(BASE+"module.nation.model.NationId",true,loader),request=Class.forName(BASE+"module.treasury.NationTransferService$TransferRequest",true,loader),category=Class.forName(BASE+"module.treasury.NationTransferService$TransferCategory",true,loader);
        return new Access(nations,transfers,id,request,category);
    }
    private record Access(Object nations,Object transfers,Class<?> idType,Class<?> request,Class<?> category) {
        Object id(String value)throws ReflectiveOperationException{return idType.getMethod("fromString",String.class).invoke(null,value);}
        String nationOf(UUID actor)throws ReflectiveOperationException {Object nation=unwrap(call(nations,"nationOf",actor));return nation==null?"":Objects.toString(call(nation,"id"));}
        boolean nativePermission(Player player)throws ReflectiveOperationException {
            ClassLoader loader=nations.getClass().getClassLoader();
            Class<?> permissionType=Class.forName(BASE+"nation.permission.NationPermission",true,loader);
            Class<?> levelType=Class.forName(BASE+"nation.permission.PermissionLevel",true,loader);
            Class<?> checkerType=Class.forName(BASE+"nation.permission.NationPermissionChecker",true,loader);
            Object permission=permissionType.getMethod("valueOf",String.class).invoke(null,"BANK_WITHDRAW");
            Object leader=levelType.getMethod("valueOf",String.class).invoke(null,"LEADER");
            Object checker=checkerType.getConstructor().newInstance();
            return Boolean.TRUE.equals(call(checker,"hasPermission",player,permission,leader,null,true));
        }
    }
    private static boolean empty(Object target,String name,Object arg)throws ReflectiveOperationException{return unwrap(call(target,name,arg))==null;}
    private static Object unwrap(Object value){return value instanceof Optional<?> o?o.orElse(null):value;}
    private static String nationText(Object nationId)throws ReflectiveOperationException {
        if(nationId==null)throw new IllegalArgumentException("Native receipt omitted a nation ID");
        try { return Objects.toString(call(nationId,"value")); }
        catch(NoSuchMethodException ignored) { return Objects.toString(call(nationId,"uuid")); }
    }
    private static String message(Throwable error){Throwable root=error;while(root.getCause()!=null)root=root.getCause();return root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();}
}
