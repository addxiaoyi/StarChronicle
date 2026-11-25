package io.github.addxiaoyi.starchronicle.api.quest;

import java.time.Instant;
import java.util.UUID;

/** Trusted immutable admission binding. A provider must not reuse an older native run.
 * expectedNationId is Chronicle's original member snapshot, never a client assertion. */
public record QuestRequest(String bindingId, UUID playerId, String questId, Instant gateOpenedAt,
                           String expectedNationId, Mode mode) {
    public enum Mode { PLAYER, NATION }
}
