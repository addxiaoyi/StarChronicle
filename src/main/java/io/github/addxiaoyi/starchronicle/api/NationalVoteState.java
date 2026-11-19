package io.github.addxiaoyi.starchronicle.api;

import java.time.Instant;
import java.util.*;

/** Immutable presentation of recorded country ballots. Counts are the recorded projection;
 * country existence is authoritatively rechecked inside the final submission transaction.
 * A national ballot is not the representative's or all citizens' personal narrative choice. */
public record NationalVoteState(String instanceId,String nodeId,int quorum,String resolution,
                                boolean completed,String viewerNationId,long viewerRevision,
                                boolean representative,boolean canVote,boolean canWithdraw,
                                String message,Map<String,Long> counts,List<Ballot> ballots) {
    public NationalVoteState {counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts));ballots=List.copyOf(ballots);}
    public record Ballot(String nationId,long revision,String choiceId,String status,UUID actorId,
                         Instant checkedAt,String authorityEvidence) {}
}
