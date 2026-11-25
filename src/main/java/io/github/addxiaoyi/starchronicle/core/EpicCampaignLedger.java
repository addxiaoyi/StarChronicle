package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.epic.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/**
 * Durable orchestration ledger for a national epic. It owns intent, roster and
 * receipt projections; it never calls StarCore and never treats a request as a
 * completed operation. Native providers must call {@link #observe} with a
 * receipt before a phase can advance.
 */
public final class EpicCampaignLedger {
    public static final Set<String> STORY_FACTS = Set.of(
            "epic.preparations.ready", "epic.route.war.open", "epic.route.dungeon.open",
            "epic.final.war_committed", "epic.final.dungeon_committed", "epic.final.canonical");

    public EpicCampaignLedger(SqliteStore store) {
        Objects.requireNonNull(store).transaction(c -> { createSchema(c); return null; });
        this.store = store;
    }
    private final SqliteStore store;

    public static void createSchema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS epic_signups("+
                    "session_id TEXT PRIMARY KEY,plan_json TEXT NOT NULL,relief_nation_id TEXT NOT NULL,"+
                    "status TEXT NOT NULL CHECK(status IN ('OPEN','SEALED','CANCELLED')),revision INTEGER NOT NULL,"+
                    "sealed_event_id TEXT,cancellation_json TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS epic_signup_seats("+
                    "session_id TEXT NOT NULL,nation_id TEXT NOT NULL,representative_id TEXT NOT NULL,"+
                    "seat_revision INTEGER NOT NULL,status TEXT NOT NULL CHECK(status IN ('ACTIVE','WITHDRAWN')),"+
                    "enrolled_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(session_id,nation_id))");
            s.execute("CREATE INDEX IF NOT EXISTS epic_signup_status ON epic_signups(status,updated_at,session_id)");
            s.execute("CREATE TABLE IF NOT EXISTS epic_campaigns("+
                    "instance_id TEXT PRIMARY KEY,session_id TEXT NOT NULL UNIQUE,source_event_id TEXT NOT NULL,"+
                    "plan_json TEXT NOT NULL,relief_nation_id TEXT NOT NULL,status TEXT NOT NULL,"+
                    "phase_id TEXT NOT NULL,route TEXT,revision INTEGER NOT NULL,updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS epic_operations("+
                    "operation_id TEXT PRIMARY KEY,instance_id TEXT NOT NULL,nation_id TEXT NOT NULL,phase_id TEXT NOT NULL,"+
                    "status TEXT NOT NULL CHECK(status IN ('PREPARED','UNKNOWN','COMMITTED','REJECTED')),"+
                    "operation_json TEXT NOT NULL,receipt_json TEXT,detail TEXT NOT NULL,revision INTEGER NOT NULL,updated_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS epic_operation_campaign ON epic_operations(instance_id,phase_id,nation_id,status)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS epic_receipt_once ON epic_operations("+
                    "json_extract(receipt_json,'$.authorityDomain'),json_extract(receipt_json,'$.type'),"+
                    "json_extract(receipt_json,'$.nativeReceiptId')) WHERE status='COMMITTED'");
            s.execute("CREATE TABLE IF NOT EXISTS epic_operation_providers("+
                    "operation_id TEXT PRIMARY KEY REFERENCES epic_operations(operation_id),provider_id TEXT NOT NULL,"+
                    "authority_domain TEXT NOT NULL,pinned_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS epic_route_votes("+
                    "instance_id TEXT NOT NULL,nation_id TEXT NOT NULL,choice TEXT NOT NULL,revision INTEGER NOT NULL,"+
                    "cast_at TEXT NOT NULL,PRIMARY KEY(instance_id,nation_id))");
        }
        EpicContributionProjection.createSchema(c);
    }

    public EpicSignup open(EpicCampaignPlan plan, String sessionId, String reliefNationId, Instant now) {
        Objects.requireNonNull(plan); session(sessionId); nation(reliefNationId); Objects.requireNonNull(now);
        return store.transaction(c -> {
            if (findSignup(c, sessionId) != null) throw new IllegalStateException("Epic signup session already exists: " + sessionId);
            try (var s = c.prepareStatement("INSERT INTO epic_signups VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, sessionId); s.setString(2, json(plan)); s.setString(3, reliefNationId); s.setString(4, "OPEN");
                s.setLong(5, 0); s.setString(6, null); s.setString(7, null); s.setString(8, now.toString()); s.setString(9, now.toString()); s.executeUpdate();
            }
            return findSignup(c, sessionId);
        });
    }
    public EpicSignup openSignup(EpicCampaignPlan plan, String sessionId, String reliefNationId, Instant now) { return open(plan, sessionId, reliefNationId, now); }

    public EpicSignup signup(String sessionId) { session(sessionId); return store.transaction(c -> findSignup(c, sessionId)); }

    public EpicSignup enroll(String sessionId, String nationId, UUID representativeId, long expectedRevision, Instant now) {
        session(sessionId); nation(nationId); Objects.requireNonNull(representativeId); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicSignup signup = openSignup(c, sessionId); checkOpen(signup, expectedRevision);
            if (nationId.equals(signup.reliefNationId())) throw new IllegalArgumentException("Relief nation cannot enroll itself");
            EpicSignup.Seat previous = signup.seats().get(nationId);
            if (previous != null && previous.status() == EpicSignup.SeatStatus.ACTIVE) {
                if (!previous.representativeId().equals(representativeId)) throw new SecurityException("Nation already has another active representative");
                return signup;
            }
            long seatRevision = previous == null ? 0 : Math.addExact(previous.revision(), 1);
            if (previous == null) insertSeat(c, sessionId, nationId, representativeId, seatRevision, EpicSignup.SeatStatus.ACTIVE, now);
            else updateSeat(c, sessionId, nationId, representativeId, seatRevision, EpicSignup.SeatStatus.ACTIVE, now, previous.revision());
            return bumpSignup(c, signup, now);
        });
    }

    public EpicSignup withdraw(String sessionId, String nationId, UUID representativeId, long expectedSeatRevision, long expectedSignupRevision, Instant now) {
        session(sessionId); nation(nationId); Objects.requireNonNull(representativeId); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicSignup signup = openSignup(c, sessionId); checkOpen(signup, expectedSignupRevision);
            EpicSignup.Seat seat = signup.seats().get(nationId);
            if (seat == null || seat.status() != EpicSignup.SeatStatus.ACTIVE) throw new IllegalStateException("Nation has no active epic seat");
            if (!seat.representativeId().equals(representativeId)) throw new SecurityException("Representative does not own this seat");
            if (seat.revision() != expectedSeatRevision) throw new IllegalStateException("Seat revision changed; refresh before withdrawal");
            updateSeat(c, sessionId, nationId, representativeId, Math.addExact(seat.revision(), 1), EpicSignup.SeatStatus.WITHDRAWN, now, seat.revision());
            return bumpSignup(c, signup, now);
        });
    }

    public EpicSignup seal(String sessionId, long expectedRevision, UUID actorId, Instant now) {
        session(sessionId); Objects.requireNonNull(actorId); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicSignup signup = openSignup(c, sessionId);
            if (signup.status() == EpicSignup.Status.SEALED) return signup;
            checkOpen(signup, expectedRevision);
            if (signup.activeSeats().stream().noneMatch(s -> s.representativeId().equals(actorId))) throw new SecurityException("Only an enrolled representative may seal the roster");
            List<EpicSignup.Seat> active = signup.activeSeats();
            if (active.size() < 2) throw new IllegalStateException("At least two active nations are required");
            if (active.stream().anyMatch(s -> s.nationId().equals(signup.reliefNationId()))) throw new IllegalArgumentException("Relief nation cannot enroll itself");
            String eventId = "chronicle:epic-signup:" + RuleEngine.stable(signup.sessionId(), Long.toString(signup.revision()), signup.reliefNationId());
            try (var s = c.prepareStatement("UPDATE epic_signups SET status='SEALED',sealed_event_id=?,revision=revision+1,updated_at=? WHERE session_id=? AND status='OPEN' AND revision=?")) {
                s.setString(1, eventId); s.setString(2, now.toString()); s.setString(3, sessionId); s.setLong(4, expectedRevision);
                if (s.executeUpdate() != 1) throw new IllegalStateException("Signup changed before sealing");
            }
            return findSignup(c, sessionId);
        });
    }

    public EpicSignup cancel(String sessionId, PlayerContext actor, long expectedRevision, String reason, Instant now) {
        session(sessionId); Objects.requireNonNull(actor); Objects.requireNonNull(now);
        if (!actor.permitted("starchronicle.admin")) throw new SecurityException("Cancelling an epic signup requires administrator permission");
        return store.transaction(c -> {
            EpicSignup signup = openSignup(c, sessionId); checkOpen(signup, expectedRevision);
            EpicSignup.Cancellation cancellation = new EpicSignup.Cancellation(actor.playerId(), now, reason);
            try (var s = c.prepareStatement("UPDATE epic_signups SET status='CANCELLED',cancellation_json=?,revision=revision+1,updated_at=? WHERE session_id=? AND status='OPEN' AND revision=?")) {
                s.setString(1, json(cancellation)); s.setString(2, now.toString()); s.setString(3, sessionId); s.setLong(4, expectedRevision);
                if (s.executeUpdate() != 1) throw new IllegalStateException("Signup changed before cancellation");
            }
            return findSignup(c, sessionId);
        });
    }

    public EpicCampaignState start(String instanceId, String sessionId, String sourceEventId, Instant now) {
        id(instanceId, "instanceId"); session(sessionId); id(sourceEventId, "sourceEventId"); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicSignup signup = openSignup(c, sessionId);
            if (signup.status() != EpicSignup.Status.SEALED) throw new IllegalStateException("Campaign needs a sealed roster");
            EpicCampaignState old = findCampaign(c, instanceId);
            if (old != null) {
                if (!old.sessionId().equals(sessionId) || !campaignSourceEvent(c, instanceId).equals(sourceEventId))
                    throw new SecurityException("Campaign instance is already bound to another signup or source event");
                return old;
            }
            EpicPhasePolicy first = signup.plan().phases().getFirst();
            try (var s = c.prepareStatement("INSERT INTO epic_campaigns VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, instanceId); s.setString(2, sessionId); s.setString(3, sourceEventId); s.setString(4, json(signup.plan()));
                s.setString(5, signup.reliefNationId()); s.setString(6, (first.kind() == EpicPhasePolicy.Kind.FINAL_ROUTE
                        ? EpicCampaignState.Status.ROUTE_VOTING : EpicCampaignState.Status.PREPARING).name());
                s.setString(7, first.phaseId()); s.setString(8, null); s.setLong(9, 1); s.setString(10, now.toString()); s.executeUpdate();
            }
            return findCampaign(c, instanceId);
        });
    }

    /** Persist an idempotent intent. This method never calls a native provider. */
    public EpicOperationState prepare(EpicOperation operation) {
        return prepare(operation, null, null);
    }

    /** Persist an intent and its trusted provider/domain lease before any native call. */
    public EpicOperationState prepare(EpicOperation operation, String providerId, String authorityDomain) {
        Objects.requireNonNull(operation);
        if ((providerId == null) != (authorityDomain == null)) throw new IllegalArgumentException("Epic provider and authority domain must be pinned together");
        if (providerId != null) { id(providerId, "providerId"); nation(authorityDomain); }
        return store.transaction(c -> {
            EpicCampaignState campaign = campaign(c, operation.instanceId());
            EpicOperationState existing = findOperation(c, operation.operationId());
            if (existing != null) {
                if (!json(existing.operation()).equals(json(operation))) throw new IllegalStateException("Operation ID is bound to a different request");
                ProviderPin pinned = findProviderPin(c, operation.operationId());
                if (providerId != null && pinned == null && existing.status() == EpicOperationState.Status.PREPARED)
                    pinProvider(c, operation.operationId(), providerId, authorityDomain, operation.requestedAt());
                else if (providerId != null && !new ProviderPin(providerId, authorityDomain).equals(pinned))
                    throw new SecurityException("Epic operation is pinned to a different provider authority");
                return existing;
            }
            if (!campaign.plan().campaignId().equals(operation.campaignId())
                    || !campaignSourceEvent(c, operation.instanceId()).equals(operation.sourceEventId()))
                throw new SecurityException("Operation is not bound to this campaign's pinned source event");
            if (!operation.phaseId().equals(campaign.phaseId()))
                throw new IllegalStateException("Operation is not for the current campaign phase");
            boolean declared = false;
            if (campaign.status() == EpicCampaignState.Status.PREPARING) {
                for (EpicRequirement requirement : campaign.plan().phase(operation.phaseId()).requirements())
                    if (matchesPreparation(c, campaign, requirement, operation)) { declared = true; break; }
            } else if (campaign.status() == EpicCampaignState.Status.ROUTE_APPROVED
                    && operation.phaseId().equals(campaign.plan().finalRoute().phaseId())) {
                declared = campaign.plan().requirementsForRoute(campaign.route()).stream().anyMatch(r -> matches(r, operation, campaign));
            }
            if (!declared) throw new IllegalArgumentException("Operation is not declared by the current phase and approved route");
            if (!authorizedOperationNation(c, campaign, operation)) throw new SecurityException("Operation nation is not in the frozen roster or a committed restoration");
            insertOperation(c, operation, EpicOperationState.Status.PREPARED, null, "Native provider not yet called", 1, operation.requestedAt());
            if (providerId != null) pinProvider(c, operation.operationId(), providerId, authorityDomain, operation.requestedAt());
            EpicOperationState result=findOperation(c, operation.operationId());
            advanceCampaign(c,campaign.instanceId(),operation.requestedAt());
            return result;
        });
    }
    public EpicOperationState prepareOperation(EpicOperation operation) { return prepare(operation); }

    /** Commit the one allowed mutation attempt before crossing the native service boundary.
     * UNKNOWN survives crashes and is only eligible for read-only reconciliation. */
    public EpicOperationState claimAttempt(String operationId, long expectedRevision, Instant now) {
        id(operationId, "operationId"); Objects.requireNonNull(now);
        return store.transaction(c -> {
            if (findProviderPin(c, operationId) == null) throw new IllegalStateException("Native attempt requires a durable provider pin");
            EpicOperationState state=findOperation(c,operationId);
            EpicCampaignState campaign=campaign(c,state.operation().instanceId());
            if(!campaign.phaseId().equals(state.operation().phaseId())
                    ||campaign.status()!=EpicCampaignState.Status.PREPARING&&campaign.status()!=EpicCampaignState.Status.ROUTE_APPROVED)
                throw new IllegalStateException("Campaign no longer authorizes a native mutation in this phase");
            try (var s = c.prepareStatement("UPDATE epic_operations SET status='UNKNOWN',detail=?,revision=revision+1,updated_at=? WHERE operation_id=? AND status='PREPARED' AND revision=?")) {
                s.setString(1, "Native attempt reserved; reconcile only after interruption");
                s.setString(2, now.toString()); s.setString(3, operationId); s.setLong(4, expectedRevision);
                if (s.executeUpdate() != 1) throw new IllegalStateException("Epic operation was already attempted or its revision changed");
            }
            return findOperation(c, operationId);
        });
    }

    /** Store a provider observation. Only a matching committed receipt satisfies a phase. */
    public EpicOperationState observe(EpicOperation operation, EpicObservation observation, String providerId, String authorityDomain, Instant now) {
        return store.transaction(c -> observe(c, operation, observation, providerId, authorityDomain, now));
    }

    /** Shares the caller's transaction with history and contribution projections. */
    static EpicOperationState observe(Connection c, EpicOperation operation, EpicObservation observation,
                                      String providerId, String authorityDomain, Instant now) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalStateException("Epic observation requires a transaction");
        Objects.requireNonNull(operation); Objects.requireNonNull(observation); Objects.requireNonNull(providerId); Objects.requireNonNull(authorityDomain); Objects.requireNonNull(now);
        EpicOperationState existing = findOperation(c, operation.operationId());
        if (existing == null) throw new IllegalStateException("Operation must be prepared before observation");
        if (!json(existing.operation()).equals(json(operation))) throw new IllegalStateException("Observed operation differs from prepared request");
        ProviderPin pin = findProviderPin(c, operation.operationId());
        if (pin != null && (!pin.providerId().equals(providerId) || !pin.authorityDomain().equals(authorityDomain)))
            throw new SecurityException("Epic observation came from a provider outside the pinned authority");
        if (existing.status() == EpicOperationState.Status.COMMITTED) return existing;
        EpicOperationState.Status status; EpicReceipt receipt = null; String detail = observation.detail();
        if (observation.status() == EpicObservation.Status.COMMITTED) { receipt = EpicContract.requireCommitted(operation, observation, providerId, authorityDomain); status = EpicOperationState.Status.COMMITTED; }
        else if (observation.status() == EpicObservation.Status.REJECTED) status = EpicOperationState.Status.REJECTED;
        else status = EpicOperationState.Status.UNKNOWN;
        long revision = Math.addExact(existing.revision(), 1);
        try (var s = c.prepareStatement("UPDATE epic_operations SET status=?,receipt_json=?,detail=?,revision=?,updated_at=? WHERE operation_id=? AND revision=?")) {
            s.setString(1, status.name()); s.setString(2, receipt == null ? null : json(receipt)); s.setString(3, detail == null ? "" : detail); s.setLong(4, revision); s.setString(5, now.toString()); s.setString(6, operation.operationId()); s.setLong(7, existing.revision());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Operation changed before observation");
        }
        if(status==EpicOperationState.Status.COMMITTED)advanceCampaign(c,operation.instanceId(),now);
        return findOperation(c, operation.operationId());
    }
    public EpicOperationState commitOperation(EpicOperation operation, EpicObservation observation, String providerId, String authorityDomain, Instant now) { return observe(operation, observation, providerId, authorityDomain, now); }

    public EpicOperationState operation(String operationId) { id(operationId, "operationId"); return store.transaction(c -> findOperation(c, operationId)); }

    public record ProviderPin(String providerId, String authorityDomain) {}

    public ProviderPin providerPin(String operationId) {
        id(operationId, "operationId"); return store.transaction(c -> findProviderPin(c, operationId));
    }

    public boolean phaseReady(String instanceId, String phaseId, String nationId) {
        id(instanceId, "instanceId"); id(phaseId, "phaseId"); nation(nationId);
        return store.transaction(c -> phaseReady(c, campaign(c, instanceId), phaseId, nationId));
    }
    public EpicCampaignState routeVote(String instanceId, String nationId, long expectedCampaignRevision, String choice, Instant now) { return castRouteVote(instanceId, nationId, expectedCampaignRevision, choice, now); }

    public EpicCampaignState advanceToRouteVoting(String instanceId, Instant now) {
        id(instanceId, "instanceId"); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicCampaignState current = campaign(c, instanceId);
            if (current.status() == EpicCampaignState.Status.ROUTE_VOTING || current.status() == EpicCampaignState.Status.ROUTE_APPROVED || current.status() == EpicCampaignState.Status.COMPLETE) return current;
            for (EpicPhasePolicy p : current.plan().phases()) if (p.kind() == EpicPhasePolicy.Kind.PREPARATION)
                for (EpicSignup.Seat seat : activeSeats(c, current.sessionId())) if (!phaseReady(c, current, p.phaseId(), seat.nationId())) throw new IllegalStateException("Preparation phase is incomplete: " + p.phaseId() + "/" + seat.nationId());
            EpicPhasePolicy route = current.plan().finalRoute();
            updateCampaign(c, current, EpicCampaignState.Status.ROUTE_VOTING, route.phaseId(), current.route(), now);
            return findCampaign(c, instanceId);
        });
    }

    public EpicCampaignState castRouteVote(String instanceId, String nationId, long expectedCampaignRevision, String choice, Instant now) {
        id(instanceId, "instanceId"); nation(nationId); id(choice, "route"); Objects.requireNonNull(now);
        return store.transaction(c -> {
            EpicCampaignState current = campaign(c, instanceId);
            if (current.status() != EpicCampaignState.Status.ROUTE_VOTING) throw new IllegalStateException("Campaign is not accepting route votes");
            if (current.revision() != expectedCampaignRevision) throw new IllegalStateException("Campaign revision changed; refresh before voting");
            if (!activeNation(c, current.sessionId(), nationId)) throw new SecurityException("Nation is not in the frozen roster");
            if (!Set.of("WAR", "DUNGEON").contains(choice)) throw new IllegalArgumentException("Route must be WAR or DUNGEON");
            if (!current.plan().routeRequirements().isEmpty() && !current.plan().routeRequirements().containsKey(choice))
                throw new IllegalArgumentException("This route is not declared by the frozen campaign plan");
            EpicCampaignState.RouteVote old = current.routeVotes().get(nationId); long revision = old == null ? 1 : Math.addExact(old.revision(), 1);
            try (var s = c.prepareStatement("INSERT INTO epic_route_votes VALUES(?,?,?,?,?) ON CONFLICT(instance_id,nation_id) DO UPDATE SET choice=excluded.choice,revision=excluded.revision,cast_at=excluded.cast_at")) {
                s.setString(1, instanceId); s.setString(2, nationId); s.setString(3, choice); s.setLong(4, revision); s.setString(5, now.toString()); s.executeUpdate();
            }
            List<EpicSignup.Seat> seats = activeSeats(c, current.sessionId()); Map<String,Long> counts = new HashMap<>();
            for (EpicCampaignState.RouteVote vote : routeVotes(c, instanceId).values()) counts.merge(vote.choice(), 1L, Long::sum);
            String winner = counts.entrySet().stream().filter(e -> e.getValue() * 2 > seats.size()).map(Map.Entry::getKey).findFirst().orElse(null);
            EpicCampaignState.Status status = winner == null ? EpicCampaignState.Status.ROUTE_VOTING : EpicCampaignState.Status.ROUTE_APPROVED;
            updateCampaign(c, current, status, current.phaseId(), winner, now);
            return findCampaign(c, instanceId);
        });
    }

    public EpicCampaignState state(String instanceId) { id(instanceId, "instanceId"); return store.transaction(c -> campaign(c, instanceId)); }
    public EpicCampaignState status(String instanceId) { return state(instanceId); }

    /** Called inside the story transition transaction, never from external event facts. */
    public Map<String,String> settlementFacts(Connection c, String instanceId) throws SQLException {
        EpicCampaignState campaign = findCampaign(c, instanceId);
        if (campaign == null || campaign.status() != EpicCampaignState.Status.COMPLETE) return Map.of();
        String evidence = campaign.operations().values().stream()
                .filter(op -> op.status() == EpicOperationState.Status.COMMITTED)
                .map(op -> op.receipt().authorityDomain() + ":" + op.operation().type() + ":" + op.receipt().nativeReceiptId())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        return Map.of("epic.final.canonical", "true", "epic.campaign", campaign.plan().campaignId(),
                "epic.instance", instanceId, "epic.route", campaign.route(),
                "epic.receipts.digest", EpicContract.operationId(campaign.plan().campaignId(),instanceId,campaign.phaseId(),evidence,campaign.reliefNationId()));
    }

    /**
     * Derive story facts from the same transaction as the caller's story decision.
     * Facts are scoped to a frozen campaign seat and are never writable by story effects.
     */
    public Map<String, String> storyFacts(Connection c, String sourceEventId, String nationId) throws SQLException {
        EpicCampaignState campaign = null;
        try (var s = c.prepareStatement("SELECT instance_id FROM epic_campaigns WHERE source_event_id=? ORDER BY instance_id")) {
            s.setString(1, sourceEventId);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    campaign = findCampaign(c, r.getString(1));
                    if (r.next()) throw new IllegalStateException("Epic source event is bound to multiple campaigns");
                }
            }
        }
        if (campaign == null || !activeNation(c, campaign.sessionId(), nationId)) return Map.of(
                "epic.preparations.ready", "false", "epic.route.war.open", "false",
                "epic.route.dungeon.open", "false", "epic.final.war_committed", "false",
                "epic.final.dungeon_committed", "false", "epic.final.canonical", "false");
        EpicCampaignState current = campaign;
        boolean prepared = true;
        for (EpicPhasePolicy phase : current.plan().phases())
            if (phase.kind() == EpicPhasePolicy.Kind.PREPARATION
                    && !phaseReady(c, current, phase.phaseId(), nationId)) { prepared = false; break; }
        boolean approved = campaign.status() == EpicCampaignState.Status.ROUTE_APPROVED
                || campaign.status() == EpicCampaignState.Status.COMPLETE;
        boolean war = approved && "WAR".equals(campaign.route());
        boolean dungeon = approved && "DUNGEON".equals(campaign.route());
        boolean complete = campaign.status() == EpicCampaignState.Status.COMPLETE;
        return Map.of("epic.preparations.ready", Boolean.toString(prepared),
                "epic.route.war.open", Boolean.toString(war),
                "epic.route.dungeon.open", Boolean.toString(dungeon),
                "epic.final.war_committed", Boolean.toString(complete && war),
                "epic.final.dungeon_committed", Boolean.toString(complete && dungeon),
                "epic.final.canonical", Boolean.toString(complete));
    }

    private static boolean phaseReady(Connection c, EpicCampaignState campaign, String phaseId, String nationId) throws SQLException {
        if (!activeNation(c, campaign.sessionId(), nationId)) return false;
        EpicPhasePolicy phase = campaign.plan().phase(phaseId);
        for (EpicRequirement requirement : phase.requirements()) {
            boolean found = false;
            try (var s = c.prepareStatement("SELECT operation_json,status FROM epic_operations WHERE instance_id=? AND phase_id=? AND nation_id=? AND status='COMMITTED'")) {
                String operationNation = requirement.type() == EpicRequirement.Type.DIPLOMACY_ACCEPT
                        ? campaign.reliefNationId() : nationId;
                s.setString(1, campaign.instanceId()); s.setString(2, phaseId); s.setString(3, operationNation);
            try (var r = s.executeQuery()) {
                while (r.next()) if (matchesPreparation(c, campaign, requirement,
                        decode(r.getString(1), EpicOperation.class), nationId)) { found = true; break; }
            }
            }
            if (!found) return false;
        }
        return true;
    }

    private static void advanceCampaign(Connection c,String instanceId,Instant now)throws SQLException {
        EpicCampaignState campaign=findCampaign(c,instanceId);
        if (campaign == null) return;
        if (campaign.status() == EpicCampaignState.Status.ROUTE_APPROVED) {
            Set<EpicRequirement> requirements = campaign.plan().requirementsForRoute(campaign.route());
            if ("DUNGEON".equals(campaign.route())) {
                for (EpicSignup.Seat seat : activeSeats(c, campaign.sessionId()))
                    for (EpicRequirement requirement : requirements)
                        if (!hasCommittedRequirement(c, campaign, campaign.plan().finalRoute().phaseId(), requirement, seat.nationId())) return;
            } else for (EpicRequirement requirement : requirements)
                if (!hasCommittedRequirement(c, campaign, campaign.plan().finalRoute().phaseId(), requirement, null)) return;
            if (requirements.isEmpty()) return;
            updateCampaign(c, campaign, EpicCampaignState.Status.COMPLETE, campaign.phaseId(), campaign.route(), now);
            return;
        }
        if(campaign.status()!=EpicCampaignState.Status.PREPARING)return;
        List<EpicPhasePolicy> phases=campaign.plan().phases();int index=-1;
        for(int i=0;i<phases.size();i++)if(phases.get(i).phaseId().equals(campaign.phaseId())){index=i;break;}
        if(index<0||phases.get(index).kind()!=EpicPhasePolicy.Kind.PREPARATION)return;
        for(EpicSignup.Seat seat:activeSeats(c,campaign.sessionId()))if(!phaseReady(c,campaign,campaign.phaseId(),seat.nationId()))return;
        EpicPhasePolicy next=phases.get(index+1);
        EpicCampaignState.Status status=next.kind()==EpicPhasePolicy.Kind.FINAL_ROUTE?EpicCampaignState.Status.ROUTE_VOTING:EpicCampaignState.Status.PREPARING;
        updateCampaign(c,campaign,status,next.phaseId(),campaign.route(),now);
    }

    private static boolean hasCommittedRequirement(Connection c, EpicCampaignState campaign, String phaseId,
                                                   EpicRequirement requirement, String nationId) throws SQLException {
        String sql = "SELECT operation_json FROM epic_operations WHERE instance_id=? AND phase_id=? AND status='COMMITTED'"
                + (nationId == null ? "" : " AND nation_id=?");
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, campaign.instanceId()); s.setString(2, phaseId);
            if (nationId != null) s.setString(3, nationId);
            try (var r = s.executeQuery()) {
                while (r.next()) if (matches(requirement, decode(r.getString(1), EpicOperation.class), campaign)) return true;
            }
        }
        return false;
    }

    private static boolean matches(EpicRequirement requirement, EpicOperation operation) {
        if (!requirement.type().name().equals(operation.type().name())
                || (!requirement.target().equals("ANY") && !requirement.target().equals(operation.target()))) return false;
        if (requirement.nativeReference().equals("WAR_OUTCOME_V1")) return false;
        if (requirement.nativeReference().equals("NATION_FOUNDING_V1"))
            return operation.nativeReference().equals("NATION_FOUNDING_V1")
                    || operation.nativeReference().startsWith("NATION_FOUNDING_V1:");
        if (requirement.nativeReference().equals("ALLIANCE_ACCEPT_V1")) {
            try { return UUID.fromString(operation.nativeReference()).toString().equals(operation.nativeReference()); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        if (requirement.nativeReference().equals("DUNGEON_RUN_V1")) {
            try { return UUID.fromString(operation.nativeReference()).toString().equals(operation.nativeReference()); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        return requirement.nativeReference().equals(operation.nativeReference());
    }

    private static boolean matches(EpicRequirement requirement, EpicOperation operation,
                                   EpicCampaignState campaign) {
        if (requirement.nativeReference().equals("WAR_OUTCOME_V1")) {
            if (requirement.type() != EpicRequirement.Type.FINAL_BATTLE || operation.type() != EpicOperation.Type.FINAL_BATTLE
                    || (!requirement.target().equals("ANY") && !requirement.target().equals(operation.target()))) return false;
            return campaign.operations().values().stream().anyMatch(state ->
                    state.status() == EpicOperationState.Status.COMMITTED
                            && state.operation().type() == EpicOperation.Type.WAR_COMMIT
                            && state.operation().actorId().equals(operation.actorId())
                            && state.operation().nationId().equals(operation.nationId())
                            && state.operation().target().equals(operation.target())
                            && state.receipt().nativeReceiptId().equals(operation.nativeReference()));
        }
        if (operation.type() == EpicOperation.Type.WAR_COMMIT) {
            if (campaign.plan().requirementsForRoute(campaign.route()).stream()
                    .noneMatch(candidate -> candidate.type() == EpicRequirement.Type.NATION_FOUNDING)) return matches(requirement, operation);
            return matches(requirement, operation) && campaign.operations().values().stream().anyMatch(state ->
                    state.status() == EpicOperationState.Status.COMMITTED
                            && state.operation().type() == EpicOperation.Type.NATION_FOUNDING
                            && state.operation().target().equals(operation.nationId()));
        }
        return matches(requirement, operation);
    }

    private static boolean matchesPreparation(Connection c, EpicCampaignState campaign,
                                             EpicRequirement requirement, EpicOperation operation) throws SQLException {
        String seatNationId = operation.type() == EpicOperation.Type.DIPLOMACY_ACCEPT
                ? operation.target() : operation.nationId();
        return matchesPreparation(c, campaign, requirement, operation, seatNationId);
    }

    private static boolean matchesPreparation(Connection c, EpicCampaignState campaign,
                                             EpicRequirement requirement, EpicOperation operation,
                                             String seatNationId) throws SQLException {
        if (requirement.type() == EpicRequirement.Type.DIPLOMACY_ACCEPT
                && requirement.nativeReference().equals("ALLIANCE_ACCEPT_V1")) {
            if (operation.type() != EpicOperation.Type.DIPLOMACY_ACCEPT
                    || !campaign.reliefNationId().equals(operation.nationId())
                    || !seatNationId.equals(operation.target())
                    || !activeNation(c, campaign.sessionId(), seatNationId)) return false;
            return campaign.operations().values().stream().anyMatch(state ->
                    state.status() == EpicOperationState.Status.COMMITTED
                            && state.operation().type() == EpicOperation.Type.DIPLOMACY_INVITE
                            && state.operation().nationId().equals(seatNationId)
                            && state.operation().target().equals(campaign.reliefNationId())
                            && state.receipt().nativeReceiptId().equals(operation.nativeReference()));
        }
        if (operation.type() == EpicOperation.Type.DIPLOMACY_ACCEPT) return false;
        if (requirement.type() == EpicRequirement.Type.DIPLOMACY_ACCEPT) return false;
        EpicRequirement resolved = requirement.target().equals("RELIEF")
                ? new EpicRequirement(requirement.type(), campaign.reliefNationId(), requirement.nativeReference()) : requirement;
        return operation.nationId().equals(seatNationId) && matches(resolved, operation);
    }

    private static boolean authorizedOperationNation(Connection c, EpicCampaignState campaign,
                                                      EpicOperation operation) throws SQLException {
        if (activeNation(c, campaign.sessionId(), operation.nationId())) return true;
        if (operation.type() == EpicOperation.Type.DIPLOMACY_ACCEPT)
            return campaign.reliefNationId().equals(operation.nationId())
                    && activeNation(c, campaign.sessionId(), operation.target());
        if (operation.type() != EpicOperation.Type.WAR_COMMIT && operation.type() != EpicOperation.Type.FINAL_BATTLE) return false;
        return campaign.operations().values().stream().anyMatch(state ->
                state.status() == EpicOperationState.Status.COMMITTED
                        && state.operation().type() == EpicOperation.Type.NATION_FOUNDING
                        && state.operation().target().equals(operation.nationId())
                        && state.operation().actorId().equals(operation.actorId()));
    }

    private static EpicSignup openSignup(Connection c, String id) throws SQLException { EpicSignup s = findSignup(c, id); if (s == null) throw new IllegalArgumentException("Unknown epic signup: " + id); return s; }
    private static void checkOpen(EpicSignup s, long revision) { if (s.status() != EpicSignup.Status.OPEN) throw new IllegalStateException("Epic signup is not open"); if (s.revision() != revision) throw new IllegalStateException("Signup revision changed; refresh before mutation"); }
    private static EpicCampaignState campaign(Connection c, String id) throws SQLException { EpicCampaignState s = findCampaign(c, id); if (s == null) throw new IllegalArgumentException("Unknown epic campaign: " + id); return s; }
    private static boolean activeNation(Connection c, String session, String nation) throws SQLException { try (var s = c.prepareStatement("SELECT 1 FROM epic_signup_seats WHERE session_id=? AND nation_id=? AND status='ACTIVE'")) { s.setString(1, session); s.setString(2, nation); try (var r = s.executeQuery()) { return r.next(); } } }
    private static String campaignSourceEvent(Connection c, String instanceId) throws SQLException {
        try (var s = c.prepareStatement("SELECT source_event_id FROM epic_campaigns WHERE instance_id=?")) {
            s.setString(1, instanceId); try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown epic campaign: " + instanceId);
                return r.getString(1);
            }
        }
    }
    private static List<EpicSignup.Seat> activeSeats(Connection c, String session) throws SQLException { EpicSignup s = findSignup(c, session); return s == null ? List.of() : s.activeSeats(); }

    private static EpicSignup bumpSignup(Connection c, EpicSignup old, Instant now) throws SQLException { try (var s = c.prepareStatement("UPDATE epic_signups SET revision=revision+1,updated_at=? WHERE session_id=? AND revision=?")) { s.setString(1, now.toString()); s.setString(2, old.sessionId()); s.setLong(3, old.revision()); if (s.executeUpdate() != 1) throw new IllegalStateException("Signup changed during mutation"); } return findSignup(c, old.sessionId()); }
    private static void insertSeat(Connection c,String session,String nation,UUID rep,long rev,EpicSignup.SeatStatus status,Instant now)throws SQLException { try(var s=c.prepareStatement("INSERT INTO epic_signup_seats VALUES(?,?,?,?,?,?,?)")){s.setString(1,session);s.setString(2,nation);s.setString(3,rep.toString());s.setLong(4,rev);s.setString(5,status.name());s.setString(6,now.toString());s.setString(7,now.toString());s.executeUpdate();} }
    private static void updateSeat(Connection c,String session,String nation,UUID rep,long rev,EpicSignup.SeatStatus status,Instant now,long oldRev)throws SQLException { try(var s=c.prepareStatement("UPDATE epic_signup_seats SET representative_id=?,seat_revision=?,status=?,updated_at=? WHERE session_id=? AND nation_id=? AND seat_revision=?")){s.setString(1,rep.toString());s.setLong(2,rev);s.setString(3,status.name());s.setString(4,now.toString());s.setString(5,session);s.setString(6,nation);s.setLong(7,oldRev);if(s.executeUpdate()!=1)throw new IllegalStateException("Seat changed during mutation");} }
    private static void insertOperation(Connection c,EpicOperation op,EpicOperationState.Status status,EpicReceipt receipt,String detail,long rev,Instant now)throws SQLException { try(var s=c.prepareStatement("INSERT INTO epic_operations VALUES(?,?,?,?,?,?,?,?,?,?)")){s.setString(1,op.operationId());s.setString(2,op.instanceId());s.setString(3,op.nationId());s.setString(4,op.phaseId());s.setString(5,status.name());s.setString(6,json(op));s.setString(7,receipt==null?null:json(receipt));s.setString(8,detail);s.setLong(9,rev);s.setString(10,now.toString());s.executeUpdate();} }
    private static void pinProvider(Connection c,String operationId,String providerId,String authorityDomain,Instant now)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO epic_operation_providers VALUES(?,?,?,?)")) {
            s.setString(1,operationId);s.setString(2,providerId);s.setString(3,authorityDomain);s.setString(4,now.toString());s.executeUpdate();
        }
    }
    private static ProviderPin findProviderPin(Connection c,String operationId)throws SQLException {
        try(var s=c.prepareStatement("SELECT provider_id,authority_domain FROM epic_operation_providers WHERE operation_id=?")) {
            s.setString(1,operationId);try(var r=s.executeQuery()){return r.next()?new ProviderPin(r.getString(1),r.getString(2)):null;}
        }
    }
    private static void updateCampaign(Connection c,EpicCampaignState old,EpicCampaignState.Status status,String phase,String route,Instant now)throws SQLException { try(var s=c.prepareStatement("UPDATE epic_campaigns SET status=?,phase_id=?,route=?,revision=revision+1,updated_at=? WHERE instance_id=? AND revision=?")){s.setString(1,status.name());s.setString(2,phase);s.setString(3,route);s.setString(4,now.toString());s.setString(5,old.instanceId());s.setLong(6,old.revision());if(s.executeUpdate()!=1)throw new IllegalStateException("Campaign changed during mutation");} }

    private static EpicSignup findSignup(Connection c,String id)throws SQLException { try(var s=c.prepareStatement("SELECT * FROM epic_signups WHERE session_id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return null;Map<String,EpicSignup.Seat> seats=new TreeMap<>();try(var q=c.prepareStatement("SELECT * FROM epic_signup_seats WHERE session_id=?")){q.setString(1,id);try(var t=q.executeQuery()){while(t.next())seats.put(t.getString("nation_id"),new EpicSignup.Seat(t.getString("nation_id"),UUID.fromString(t.getString("representative_id")),t.getLong("seat_revision"),EpicSignup.SeatStatus.valueOf(t.getString("status")),Instant.parse(t.getString("enrolled_at")),Instant.parse(t.getString("updated_at"))));}}String cancel=r.getString("cancellation_json");return new EpicSignup(id,decode(r.getString("plan_json"),EpicCampaignPlan.class),r.getString("relief_nation_id"),EpicSignup.Status.valueOf(r.getString("status")),r.getLong("revision"),seats,r.getString("sealed_event_id"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")),cancel==null?null:decode(cancel,EpicSignup.Cancellation.class));}} }
    private static EpicOperationState findOperation(Connection c,String id)throws SQLException { try(var s=c.prepareStatement("SELECT * FROM epic_operations WHERE operation_id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return null;String receipt=r.getString("receipt_json");return new EpicOperationState(decode(r.getString("operation_json"),EpicOperation.class),EpicOperationState.Status.valueOf(r.getString("status")),receipt==null?null:decode(receipt,EpicReceipt.class),r.getString("detail"),r.getLong("revision"),Instant.parse(r.getString("updated_at")));}} }
    private static EpicCampaignState findCampaign(Connection c,String id)throws SQLException { try(var s=c.prepareStatement("SELECT * FROM epic_campaigns WHERE instance_id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return null;return new EpicCampaignState(id,r.getString("session_id"),decode(r.getString("plan_json"),EpicCampaignPlan.class),r.getString("relief_nation_id"),EpicCampaignState.Status.valueOf(r.getString("status")),r.getString("phase_id"),operations(c,id),routeVotes(c,id),r.getString("route"),r.getLong("revision"),Instant.parse(r.getString("updated_at")));}} }
    private static Map<String,EpicOperationState> operations(Connection c,String id)throws SQLException {Map<String,EpicOperationState> out=new TreeMap<>();try(var s=c.prepareStatement("SELECT operation_id FROM epic_operations WHERE instance_id=? ORDER BY operation_id")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())out.put(r.getString(1),findOperation(c,r.getString(1)));}}return out;}
    private static Map<String,EpicCampaignState.RouteVote> routeVotes(Connection c,String id)throws SQLException {Map<String,EpicCampaignState.RouteVote> out=new TreeMap<>();try(var s=c.prepareStatement("SELECT * FROM epic_route_votes WHERE instance_id=? ORDER BY nation_id")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())out.put(r.getString("nation_id"),new EpicCampaignState.RouteVote(r.getString("nation_id"),r.getString("choice"),r.getLong("revision"),Instant.parse(r.getString("cast_at"))));}}return out;}
    private static void session(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Invalid epic session");}
    private static void id(String value,String name){if(value==null||value.isBlank()||value.length()>256||!value.matches("[A-Za-z0-9_.:/-]+"))throw new IllegalArgumentException("Invalid epic "+name);}
    private static void nation(String value){if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Canonical nation UUID required");}
}
