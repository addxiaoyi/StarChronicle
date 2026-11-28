package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.util.*;
import java.util.function.Function;

/** Keeps the installed capital ledger, including its original authority and event identities. */
public final class CapitalHistoryCoordinator {
    private enum Mode { LEGACY, JOURNAL, CONFLICT }
    private static final String LEGACY_NAMESPACE=CommittedSourceCollector.Source.CAPITAL.id;
    private static final String NAMESPACE=StarCoreCapitalHistory.NAMESPACE;
    private final RuleEngine engine;
    private final CommittedHistoryJournal journal;
    private final StarCoreCapitalHistory history;
    private final CommittedSourceCollector collector;
    private final Mode mode;

    public CapitalHistoryCoordinator(RuleEngine engine,StarCoreBridge bridge,CommittedHistoryJournal journal) {
        this(engine,bridge::service,journal);
    }
    CapitalHistoryCoordinator(RuleEngine engine,Function<String,Object> services,CommittedHistoryJournal journal) {
        this.engine=Objects.requireNonNull(engine);this.journal=Objects.requireNonNull(journal);
        history=new StarCoreCapitalHistory(()->services.apply("module.nation.NationService"),journal);
        collector=new CommittedSourceCollector(services);
        boolean legacy=engine.committedSourceCursor(LEGACY_NAMESPACE).isPresent();
        boolean current=journal.cursor(NAMESPACE).isPresent();
        mode=legacy?(current?Mode.CONFLICT:Mode.LEGACY):Mode.JOURNAL;
    }

    /** Safe on observation callbacks: database access stays on the state worker. */
    public boolean usesCommittedHistory() {
        return mode!=Mode.JOURNAL||history.usesCommittedHistory();
    }
    public int poll(Collection<PlayerContext> contexts)throws ReflectiveOperationException {
        var legacy=engine.committedSourceCursor(LEGACY_NAMESPACE);
        var current=journal.cursor(NAMESPACE);
        if(mode==Mode.CONFLICT||mode==Mode.LEGACY&&(legacy.isEmpty()||current.isPresent())
                ||mode==Mode.JOURNAL&&legacy.isPresent())
            throw new IllegalStateException("Conflicting or removed capital ledger; restore matching backups without deleting history");
        if(mode==Mode.LEGACY) {
            var page=collector.collect(CommittedSourceCollector.Source.CAPITAL,legacy);
            if(page.isEmpty())throw new IllegalStateException("Pinned capital source unavailable");
            return engine.acceptCommittedSource(page.get(),contexts);
        }
        history.scan();
        int delivered=0;
        for(var pending:journal.pending(NAMESPACE,CommittedHistoryJournal.PAGE_SIZE)) {
            var event=pending.event();
            engine.acceptCommittedHistory(pending,contexts.stream().filter(ctx->event.globalAudience()
                    ||event.participants().contains(ctx.playerId())||!ctx.nationId().isBlank()&&event.nations().contains(ctx.nationId())).toList());
            if(!journal.acknowledge(event.id()))throw new IllegalStateException("Capital history acknowledgement lost");
            delivered++;
        }
        return delivered;
    }
}
