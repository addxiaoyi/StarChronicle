package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.resource.ResourceHistoryReceipt;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.util.*;

/** Resource changes are historical evidence; production and existing aid/trade contributions
 * require their own proven operation and must not be counted again from stockpile deltas. */
public final class ResourceHistoryProjection {
    public static final String NAMESPACE = "starcore.resources";
    private ResourceHistoryProjection() {}
    public static String eventId(ResourceHistoryReceipt receipt) {
        return "starcore:resource:" + receipt.authorityDomain() + ":" + receipt.operationId();
    }
    public static WorldEvent event(ResourceHistoryReceipt r) {
        Map<String,String> facts = new TreeMap<>();
        facts.put("title", "国家资源变动（原生提交）");
        facts.put("history.namespace", NAMESPACE); facts.put("history.authority", r.authorityDomain().toString());
        facts.put("history.sequence", Long.toString(r.sequence())); facts.put("history.operation", r.operationId().toString());
        facts.put("time_basis", "COMMITTED"); facts.put("resource.kind", r.kind().name());
        facts.put("resource.reference", r.reference()); facts.put("resource.category", r.category());
        facts.put("resource.description", r.description()); facts.put("resource.balance_count", Integer.toString(r.balances().size()));
        facts.put("contribution.omission", "BALANCE_CHANGE_IS_NOT_PRODUCTION_OR_NEW_AID_OR_TRADE");
        facts.put("resource.receipt_sha256", hash(SqliteStore.json(r)));
        Set<String> nations = new TreeSet<>(), types = new TreeSet<>();
        for (var b : r.balances()) { nations.add(b.nationId().toString()); types.add(b.resourceType()); }
        if (nations.size() == 1) facts.put("nation.id", nations.iterator().next());
        if (types.size() == 1) facts.put("resource.type", types.iterator().next());
        if (r.actorId() != null) facts.put("actor.id", r.actorId().toString());
        return new WorldEvent(eventId(r), "RESOURCE_BALANCE_CHANGED", r.committedAt(),
                r.actorId() == null ? Set.of() : Set.of(r.actorId()), nations, facts, "StarCore",
                "ResourceHistoryService.resourceChangesAfter; authority=" + r.authorityDomain() + "; sequence=" + r.sequence()
                + "; operation=" + r.operationId() + "; full before/after balances retained; actor only when supplied by native source");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
