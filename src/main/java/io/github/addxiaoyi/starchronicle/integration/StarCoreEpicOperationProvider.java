package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.epic.EpicObservation;
import io.github.addxiaoyi.starchronicle.api.epic.EpicOperation;
import io.github.addxiaoyi.starchronicle.api.epic.EpicOperationProvider;
import io.github.addxiaoyi.starchronicle.api.epic.EpicReceipt;
import io.github.addxiaoyi.starchronicle.api.epic.EpicContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/**
 * Receipt-only adapter for the operation-keyed StarCore strategic services.
 *
 * <p>The adapter deliberately keeps the Chronicle operation ID separate from the native UUID
 * accepted by StarCore. The latter is a deterministic UUID derived from the former and is never
 * supplied by a player. Every mutating call first reads the durable native receipt; an absent
 * receipt is the only state in which an operation may be invoked. UNKNOWN is retained for
 * reconciliation and is never retried here.</p>
 *
 * <p>Quest gates are owned by {@code QuestGateDriver}/{@code QuestEvidenceProvider}; this class
 * refuses to manufacture a quest receipt. FINAL_BATTLE and DUNGEON_COMPLETION are read-only
 * evidence lookups because the native battle/dungeon services own completion.</p>
 */
public final class StarCoreEpicOperationProvider implements EpicOperationProvider {
    public static final String PROVIDER_ID = "starcore.epic";
    private static final String BASE = "dev.starcore.starcore.";
    /* Used only while StarCore is absent in a standalone test; no receipt is accepted in that mode. */
    private static final String UNAVAILABLE_DOMAIN = "00000000-0000-0000-0000-000000000000";

    private final StarCoreBridge bridge;
    private final String authorityDomain;
    private final String providerName;
    private final Set<EpicOperation.Type> supportedTypes;

    public StarCoreEpicOperationProvider(StarCoreBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge);
        this.providerName = PROVIDER_ID;
        this.supportedTypes = EnumSet.allOf(EpicOperation.Type.class);
        this.authorityDomain = discoverDomain();
    }

    /** Each native journal has its own persisted authority; never borrow another module's domain. */
    public StarCoreEpicOperationProvider(StarCoreBridge bridge, EpicOperation.Type type) {
        this.bridge = Objects.requireNonNull(bridge);
        this.supportedTypes = switch (type) {
            case DIPLOMACY_INVITE, DIPLOMACY_ACCEPT -> Set.of(EpicOperation.Type.DIPLOMACY_INVITE, EpicOperation.Type.DIPLOMACY_ACCEPT);
            case WAR_COMMIT, FINAL_BATTLE -> Set.of(EpicOperation.Type.WAR_COMMIT, EpicOperation.Type.FINAL_BATTLE);
            default -> Set.of(type);
        };
        this.providerName = PROVIDER_ID + "." + switch (type) {
            case DIPLOMACY_INVITE, DIPLOMACY_ACCEPT -> "diplomacy";
            case WAR_COMMIT, FINAL_BATTLE -> "war";
            default -> type.name().toLowerCase(Locale.ROOT);
        };
        String domain;
        try { domain = nativeDomain(service(type)); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { domain = UNAVAILABLE_DOMAIN; }
        this.authorityDomain = domain;
    }

    @Override public String providerId() { return providerName; }

    /** Each registered family pins its own persisted native authority domain. */
    @Override public String authorityDomain() { return authorityDomain; }
    public boolean available() { return !UNAVAILABLE_DOMAIN.equals(authorityDomain); }

    @Override public boolean supports(EpicOperation operation) {
        if (operation == null || !available() || !supportedTypes.contains(operation.type())) return false;
        String required = switch(operation.type()) {
            case RESOURCE_AID -> "starcore.resource";
            case DIPLOMACY_INVITE, DIPLOMACY_ACCEPT -> "starcore.nation.ally.add";
            case NATION_FOUNDING -> "starcore.nation.create";
            case WAR_COMMIT -> "starcore.nation.war.declare";
            default -> "starchronicle.use";
        };
        if(!required.equals(operation.permission()))return false;
        try {
            Object nativeService = service(operation.type()); ensureDomain(nativeService);
            switch (operation.type()) {
                case RESOURCE_AID -> { parseUuid(operation.target()); resources(operation.nativeReference()); }
                case DIPLOMACY_INVITE -> { parseUuid(operation.target()); if (!operation.nativeReference().equals("ALLIANCE_INVITE_V1")) return false; }
                case DIPLOMACY_ACCEPT -> { parseUuid(operation.target()); parseUuid(operation.nativeReference()); }
                case FINAL_BATTLE -> {
                    parseUuid(operation.target()); parseUuid(operation.nativeReference());
                    nativeService.getClass().getMethod("warCompletion", UUID.class);
                }
                case WAR_COMMIT -> { parseUuid(operation.target()); if (!operation.nativeReference().equals("WAR_DECLARATION_V1")) return false; }
                case NATION_FOUNDING -> { parseUuid(operation.target()); EpicContract.foundingName(operation.nativeReference()); }
                case DUNGEON_COMPLETION -> {
                    parseUuid(operation.nativeReference());
                    nativeService.getClass().getMethod("getCompletionRecord", UUID.class);
                }
                default -> { return false; }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException invalid) { return false; }
    }

    @Override public CompletionStage<EpicObservation> lookup(EpicOperation operation) {
        Objects.requireNonNull(operation);
        if (!supports(operation)) return CompletableFuture.completedFuture(EpicObservation.unknown("Native capability or operation contract is unavailable"));
        CompletableFuture<EpicObservation> result = new CompletableFuture<>();
        bridge.global(() -> {
            try { result.complete(lookupNow(operation)); }
            catch (Throwable error) { result.complete(EpicObservation.unknown("Native epic lookup failed: " + message(error))); }
        }, () -> result.complete(EpicObservation.unknown("StarCore global scheduler unavailable")));
        return result;
    }

    @Override public CompletionStage<EpicObservation> apply(EpicOperation operation) {
        return apply(operation,()->true);
    }

    @Override public CompletionStage<EpicObservation> apply(EpicOperation operation,java.util.function.BooleanSupplier lease) {
        Objects.requireNonNull(operation);
        if (operation.type() == EpicOperation.Type.DUNGEON_COMPLETION || operation.type() == EpicOperation.Type.FINAL_BATTLE) return lookup(operation);
        return lookup(operation).thenCompose(existing -> switch (existing.status()) {
            case COMMITTED, UNKNOWN, REJECTED -> CompletableFuture.completedFuture(existing);
            case ABSENT -> invokeOnce(operation,lease);
        });
    }

    private CompletionStage<EpicObservation> invokeOnce(EpicOperation operation,java.util.function.BooleanSupplier lease) {
        CompletableFuture<EpicObservation> result = new CompletableFuture<>();
        bridge.ownedPlayer(operation.actorId(), player -> {
            try {
                if(!lease.getAsBoolean())throw new IllegalStateException("Epic provider lease expired before invocation");
                if (player == null || !player.isOnline() || !operation.actorId().equals(player.getUniqueId())) {
                    result.complete(EpicObservation.unknown("Actor owner disappeared before native invocation"));
                    return;
                }
                bridge.refreshContext(player, Set.of("starchronicle.use", operation.permission()));
                if (!player.hasPermission("starchronicle.use") || !player.hasPermission(operation.permission())) {
                    result.complete(new EpicObservation(EpicObservation.Status.REJECTED, null, "Current actor lacks the pinned permission"));
                    return;
                }
                Object nations = bridge.service("module.nation.NationService");
                if (nations == null) throw new IllegalStateException("Native national authority is unavailable");
                synchronized (nations) {
                    if(!lease.getAsBoolean())throw new IllegalStateException("Epic provider lease expired before mutation");
                    Object current = unwrap(call(nations, "nationOf", operation.actorId()));
                    if (operation.type() == EpicOperation.Type.NATION_FOUNDING) {
                        if (current != null) throw new SecurityException("Founding requires the nominated actor to leave their current nation through native governance first");
                    } else {
                        if (current == null || !operation.nationId().equals(Objects.toString(call(current, "id")))
                                || !Boolean.TRUE.equals(call(nations, "isFounder", operation.actorId(), nationId(nations.getClass().getClassLoader(), operation.nationId()))))
                            throw new SecurityException("Current native national representative changed before invocation");
                    }
                    result.complete(invokeNative(operation, player));
                }
            } catch (Throwable error) {
                result.complete(EpicObservation.unknown("Native epic invocation is uncertain: " + message(error)));
            }
        }, () -> result.complete(EpicObservation.unknown("Actor owner scheduler retired before native invocation")));
        return result;
    }

    private EpicObservation lookupNow(EpicOperation operation) throws Exception {
        Object service = service(operation.type());
        if (service == null) return EpicObservation.unknown("Native service for " + operation.type() + " is unavailable");
        ensureDomain(service);
        if (operation.type() == EpicOperation.Type.QUEST_GATE) {
            return EpicObservation.unknown("Quest evidence is owned by QuestGateDriver; no synthetic epic receipt is allowed");
        }
        if (operation.type() == EpicOperation.Type.TREASURY_AID) {
            return EpicObservation.unknown("Use NationalAidService for treasury receipts; this adapter never fabricates money evidence");
        }
        Object raw;
        UUID nativeId = nativeId(operation);
        switch (operation.type()) {
            case RESOURCE_AID -> raw = unwrap(call(service, "transferReceipt", nativeId.toString()));
            case DIPLOMACY_INVITE -> raw = unwrap(call(service, "allianceInviteReceipt", nativeId));
            case DIPLOMACY_ACCEPT -> raw = unwrap(call(service, "allianceAcceptanceReceipt", nativeId));
            case NATION_FOUNDING -> raw = unwrap(call(service, "foundingReceipt", nativeId));
            case WAR_COMMIT -> raw = unwrap(call(service, "warDeclaration", nativeId));
            case DUNGEON_COMPLETION -> raw = dungeonReceipt(service, operation);
            case FINAL_BATTLE -> raw = endedWar(service, operation);
            default -> throw new IllegalArgumentException("Unsupported epic operation type: " + operation.type());
        }
        if (raw == null) return new EpicObservation(EpicObservation.Status.ABSENT, null, "No committed native receipt");
        return receipt(operation, service, raw);
    }

    private EpicObservation invokeNative(EpicOperation operation, Player player) throws Exception {
        Object service = service(operation.type());
        if (service == null) return EpicObservation.unknown("Native service is unavailable");
        ensureDomain(service);
        if (operation.type() == EpicOperation.Type.QUEST_GATE || operation.type() == EpicOperation.Type.TREASURY_AID
                || operation.type() == EpicOperation.Type.DUNGEON_COMPLETION || operation.type() == EpicOperation.Type.FINAL_BATTLE) {
            return EpicObservation.unknown("This operation is read-only or owned by another Chronicle provider");
        }
        UUID nativeId = nativeId(operation);
        Object actor = operation.actorId();
        Object raw;
        switch (operation.type()) {
            case RESOURCE_AID -> {
                ClassLoader loader = service.getClass().getClassLoader();
                Class<?> nationId = Class.forName(BASE + "module.nation.model.NationId", true, loader);
                Class<?> request = Class.forName(BASE + "module.resource.NationResourceTransactionService$TransferRequest", true, loader);
                Class<?> category = Class.forName(BASE + "module.resource.NationResourceTransactionService$Category", true, loader);
                Object payer = nationId.getMethod("fromString", String.class).invoke(null, operation.nationId());
                Object receiver = nationId.getMethod("fromString", String.class).invoke(null, operation.target());
                Object cat = Enum.valueOf(category.asSubclass(Enum.class), "OTHER");
                Object payload = request.getConstructor(String.class, nationId, nationId, Map.class, category, String.class)
                        .newInstance(nativeId.toString(), payer, receiver, resources(operation.nativeReference()), cat,
                                resourceDescription(operation));
                raw = call(service, "transfer", payload);
                String status = String.valueOf(call(raw, "status"));
                if (Set.of("REJECTED", "INSUFFICIENT_RESOURCES").contains(status)) {
                    return new EpicObservation(EpicObservation.Status.REJECTED, null, "Native resource result " + status);
                }
            }
            case DIPLOMACY_INVITE -> {
                Object inviter = nationId(service.getClass().getClassLoader(), operation.nationId());
                Object invited = nationId(service.getClass().getClassLoader(), operation.target());
                raw = unwrap(call(service, "sendAllianceInvite", nativeId, actor, inviter, invited));
                if (raw == null) return EpicObservation.unknown("Native diplomacy invocation returned no receipt");
            }
            case DIPLOMACY_ACCEPT -> {
                Object acceptor = nationId(service.getClass().getClassLoader(), operation.nationId());
                Object inviter = nationId(service.getClass().getClassLoader(), operation.target());
                UUID invitation = parseUuid(operation.nativeReference());
                raw = unwrap(call(service, "acceptAllianceInvite", nativeId, invitation, actor, acceptor, inviter));
                if (raw == null) return EpicObservation.unknown("Native diplomacy acceptance returned no receipt");
            }
            case NATION_FOUNDING -> {
                Object id = nationId(service.getClass().getClassLoader(), operation.target());
                raw = call(service, "foundNation", nativeId, id, actor, player.getName(), EpicContract.foundingName(operation.nativeReference()));
            }
            case WAR_COMMIT -> {
                Object aggressor = nationId(service.getClass().getClassLoader(), operation.nationId());
                Object defender = nationId(service.getClass().getClassLoader(), operation.target());
                raw = unwrap(call(service, "declareWar", nativeId, aggressor, aggressor, defender));
                if (raw == null) return EpicObservation.unknown("Native war declaration returned no receipt");
            }
            default -> throw new IllegalArgumentException("Unsupported mutating epic operation: " + operation.type());
        }
        EpicObservation canonical = lookupNow(operation);
        return canonical.status() == EpicObservation.Status.ABSENT
                ? EpicObservation.unknown("Native invocation has no durable receipt yet; reconcile only") : canonical;
    }

    /** Read the committed record directly; a mutable player-history projection is insufficient. */
    private static Object dungeonReceipt(Object service, EpicOperation operation) throws Exception {
        UUID expectedRecord = parseUuid(operation.nativeReference());
        Object record = unwrap(call(service, "getCompletionRecord", expectedRecord));
        if (record != null) {
            requireField(record, "recordId", expectedRecord);
            Object player = call(record, "playerId");
            Object nation = call(record, "nationId");
            Object dungeon = call(record, "dungeonId");
            Object success = call(record, "isSuccess");
            if (!operation.actorId().equals(player) || !operation.nationId().equals(Objects.toString(nation, ""))
                    || !operation.target().equals(dungeon) || !Boolean.TRUE.equals(success))
                throw new SecurityException("Dungeon completion does not match the pinned successful actor, nation and target");
            return record;
        }
        return null;
    }

    /** A declaration is not a conclusion; only a durable ended war is final evidence. */
    private static Object endedWar(Object service, EpicOperation operation) throws Exception {
        return unwrap(call(service, "warCompletion", parseUuid(operation.nativeReference())));
    }

    private EpicObservation receipt(EpicOperation operation, Object service, Object raw) throws Exception {
        ensureDomain(service);
        String domain = nativeDomain(service);
        String nativeReceipt;
        Instant applied;
        switch (operation.type()) {
            case RESOURCE_AID -> {
                requireField(raw, "transactionId", nativeId(operation));
                requireField(raw, "authorityDomainId", domain);
                requireField(raw, "payerNationId", operation.nationId());
                requireField(raw, "receiverNationId", operation.target());
                if (!resources(operation.nativeReference()).equals(call(raw, "resources"))) throw new SecurityException("Resource receipt quantities differ");
                requireField(raw, "category", "OTHER"); requireField(raw, "description", resourceDescription(operation));
                nativeReceipt = nativeId(operation).toString(); applied = timestamp(raw, "appliedAt");
            }
            case DIPLOMACY_INVITE, DIPLOMACY_ACCEPT -> {
                requireField(raw, "operationId", nativeId(operation)); requireField(raw, "actorId", operation.actorId());
                requireField(raw, "authorityDomainId", domain);
                boolean acceptance = operation.type() == EpicOperation.Type.DIPLOMACY_ACCEPT;
                requireField(raw, "inviter", acceptance ? operation.target() : operation.nationId());
                requireField(raw, acceptance ? "acceptor" : "invited", acceptance ? operation.nationId() : operation.target());
                if (acceptance) requireField(raw, "invitationOperationId", parseUuid(operation.nativeReference()));
                nativeReceipt = nativeId(operation).toString(); applied = timestamp(raw, "committedAt");
            }
            case NATION_FOUNDING -> {
                requireField(raw, "operationId", nativeId(operation)); requireField(raw, "founderId", operation.actorId());
                requireField(raw, "authorityDomainId", domain); requireField(raw, "nationId", operation.target());
                requireField(raw, "nationName", EpicContract.foundingName(operation.nativeReference()));
                nativeReceipt = nativeId(operation).toString(); applied = timestamp(raw, "committedAt");
            }
            case WAR_COMMIT -> {
                requireField(raw, "operationId", nativeId(operation)); requireField(raw, "actorId", operation.nationId());
                requireField(raw, "aggressor", operation.nationId()); requireField(raw, "defender", operation.target());
                nativeReceipt = parseUuid(Objects.toString(call(raw, "warId"))).toString(); applied = timestamp(raw, "declaredAt");
            }
            case FINAL_BATTLE -> {
                requireField(raw, "warId", parseUuid(operation.nativeReference())); requireField(raw, "aggressor", operation.nationId());
                requireField(raw, "defender", operation.target());
                requireField(raw, "authorityDomainId", domain);
                requireField(raw, "declarationOperationId", parseUuid(operation.nativeReference()));
                nativeReceipt = operation.nativeReference(); applied = timestamp(raw, "endedAt");
                if(applied.isBefore(timestamp(raw,"declaredAt")))throw new SecurityException("War completion predates its declaration");
            }
            case DUNGEON_COMPLETION -> { nativeReceipt = operation.nativeReference(); applied = timestamp(raw, "completedAt"); }
            default -> throw new IllegalArgumentException("No native evidence mapper for " + operation.type());
        }
        if (applied.isBefore(operation.requestedAt())) {
            return EpicObservation.unknown("Native receipt predates the pinned operation request");
        }
        EpicReceipt receipt = new EpicReceipt(providerId(), domain, operation.operationId(), operation.campaignId(),
                operation.instanceId(), operation.sourceEventId(), operation.phaseId(), operation.type(), operation.actorId(),
                operation.nationId(), operation.target(), operation.nativeReference(), operation.definitionFingerprint(),
                operation.permission(), nativeReceipt, applied,
                "Committed StarCore receipt type=" + operation.type() + " native=" + nativeReceipt + " domain=" + domain);
        return new EpicObservation(EpicObservation.Status.COMMITTED, receipt, "Committed native strategic receipt");
    }

    private Object service(EpicOperation.Type type) {
        return switch (type) {
            case RESOURCE_AID -> bridge.service("module.resource.NationResourceTransactionService");
            case DIPLOMACY_INVITE, DIPLOMACY_ACCEPT -> bridge.service("module.diplomacy.DiplomacyService");
            case NATION_FOUNDING -> bridge.service("module.nation.NationService");
            case WAR_COMMIT, FINAL_BATTLE -> bridge.service("module.war.WarService");
            case DUNGEON_COMPLETION -> bridge.service("module.dungeon.DungeonService");
            case QUEST_GATE, TREASURY_AID -> null;
        };
    }

    private String discoverDomain() {
        for (String type : List.of("module.resource.NationResourceTransactionService", "module.diplomacy.DiplomacyService",
                "module.nation.NationService", "module.war.WarService", "module.dungeon.DungeonService")) {
            Object service = bridge.service(type);
            if (service == null) continue;
            try { return canonicalDomain(nativeDomain(service)); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return UNAVAILABLE_DOMAIN;
    }

    private void ensureDomain(Object service) throws ReflectiveOperationException {
        String current = nativeDomain(service);
        if (UNAVAILABLE_DOMAIN.equals(authorityDomain) || !authorityDomain.equals(current))
            throw new IllegalStateException("StarCore strategic authority domain is unavailable or changed");
    }

    private static String nativeDomain(Object service) throws ReflectiveOperationException {
        Object value = call(service, "authorityDomainId");
        return canonicalDomain(Objects.toString(value, ""));
    }

    private static String canonicalDomain(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("Noncanonical authority domain");
        return value;
    }

    static UUID nativeId(EpicOperation operation) {
        return UUID.nameUUIDFromBytes(("starcore-epic:" + operation.operationId()).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseUuid(String value) { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical UUID required"); return id; }

    private static Object nationId(ClassLoader loader, String value) throws ReflectiveOperationException {
        Class<?> type = Class.forName(BASE + "module.nation.model.NationId", true, loader);
        return type.getMethod("fromString", String.class).invoke(null, value);
    }

    private static Map<String, Long> resources(String value) {
        return EpicContract.resourceAmounts(value);
    }

    private static void requireField(Object raw, String field, Object expected) throws ReflectiveOperationException {
        Object actual = call(raw, field);
        if (actual == null || !expected.toString().equals(actual.toString())) throw new SecurityException("Native receipt mismatch: " + field);
    }

    static String resourceDescription(EpicOperation operation) { return "StarChronicle epic " + operation.campaignId() + " " + operation.operationId(); }

    private static Instant timestamp(Object raw, String... methods) throws ReflectiveOperationException {
        for (String method : methods) {
            try { Object value = call(raw, method); if (value instanceof Instant i) return i; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new IllegalArgumentException("Native receipt has no committed timestamp");
    }

    private static Object unwrap(Object value) { return value instanceof Optional<?> option ? option.orElse(null) : value; }
    private static String message(Throwable error) { Throwable root = error; while (root.getCause() != null) root = root.getCause(); return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage(); }
}
