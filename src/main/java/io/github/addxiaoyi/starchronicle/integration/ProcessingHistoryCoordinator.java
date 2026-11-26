package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.util.*;
import java.util.function.Supplier;

/** Delivers one bounded page of the UUID processing protocol on Chronicle's state worker.
 * The older string-ID production protocol remains independent: it owns another authority,
 * cursor and operation namespace, and the native writer never emits both for one run. */
public final class ProcessingHistoryCoordinator {
    private final RuleEngine engine;
    private final CommittedHistoryJournal journal;
    private final StarCoreProcessingHistory history;

    public ProcessingHistoryCoordinator(RuleEngine engine, StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(engine, () -> bridge.service("module.resource.ProcessingHistoryService"), journal);
    }
    ProcessingHistoryCoordinator(RuleEngine engine, Supplier<Object> source, CommittedHistoryJournal journal) {
        this.engine = Objects.requireNonNull(engine);
        this.journal = Objects.requireNonNull(journal);
        history = new StarCoreProcessingHistory(source, journal);
    }
    public int poll(Collection<PlayerContext> contexts) {
        history.scan();
        int delivered = 0;
        for (var pending : journal.pending(StarCoreProcessingHistory.NAMESPACE, CommittedHistoryJournal.PAGE_SIZE)) {
            var event = pending.event();
            engine.acceptCommittedHistory(pending, contexts.stream().filter(ctx -> event.globalAudience()
                    || event.participants().contains(ctx.playerId())
                    || !ctx.nationId().isBlank() && event.nations().contains(ctx.nationId())).toList());
            if (!journal.acknowledge(event.id())) throw new IllegalStateException("Processing history acknowledgement lost");
            delivered++;
        }
        return delivered;
    }
}
