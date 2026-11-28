package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.util.*;
import java.util.function.Function;

/** Preserves an installed technology ledger; one source must never feed both event formats. */
public final class TechnologyHistoryCoordinator {
    private enum Mode { LEGACY, JOURNAL, CONFLICT }
    private final RuleEngine engine;
    private final CommittedHistoryJournal journal;
    private final StarCoreTechnologyHistory history;
    private final CommittedSourceCollector collector;
    private final Mode mode;

    public TechnologyHistoryCoordinator(RuleEngine engine, StarCoreBridge bridge, CommittedHistoryJournal journal) {
        this(engine, bridge::service, journal);
    }
    TechnologyHistoryCoordinator(RuleEngine engine, Function<String,Object> services, CommittedHistoryJournal journal) {
        this.engine=Objects.requireNonNull(engine);this.journal=Objects.requireNonNull(journal);
        history=new StarCoreTechnologyHistory(()->services.apply("module.technology.TechnologyService"),journal);
        collector=new CommittedSourceCollector(services);
        boolean legacy=engine.committedSourceCursor(StarCoreTechnologyHistory.NAMESPACE).isPresent();
        boolean current=journal.cursor(StarCoreTechnologyHistory.NAMESPACE).isPresent();
        mode=legacy?(current?Mode.CONFLICT:Mode.LEGACY):Mode.JOURNAL;
    }
    /** No Chronicle database access on event/observation callbacks. */
    public boolean usesCommittedHistory() {
        return mode!=Mode.JOURNAL || history.usesCommittedHistory();
    }
    /** Called on the state worker; original rows, formats and activation semantics remain intact. */
    public int poll(Collection<PlayerContext> contexts) throws ReflectiveOperationException {
        var legacy=engine.committedSourceCursor(StarCoreTechnologyHistory.NAMESPACE);
        var current=journal.cursor(StarCoreTechnologyHistory.NAMESPACE);
        if(mode==Mode.CONFLICT || mode==Mode.LEGACY&&(legacy.isEmpty()||current.isPresent())
                || mode==Mode.JOURNAL&&legacy.isPresent())
            throw new IllegalStateException("Conflicting or removed technology ledger; restore matching backups without deleting history");
        if(mode==Mode.LEGACY) {
            var page=collector.collect(CommittedSourceCollector.Source.TECHNOLOGY,legacy);
            if(page.isEmpty())throw new IllegalStateException("Pinned technology source unavailable");
            return engine.acceptCommittedSource(page.get(),contexts);
        }
        history.scan();
        int delivered=0;
        for(var pending:journal.pending(StarCoreTechnologyHistory.NAMESPACE,CommittedHistoryJournal.PAGE_SIZE)) {
            WorldEvent event=pending.event();
            if(pending.activate())engine.accept(event,contexts.stream().filter(ctx->event.globalAudience()
                    ||event.participants().contains(ctx.playerId())||!ctx.nationId().isBlank()&&event.nations().contains(ctx.nationId())).toList());
            else engine.archiveCommittedHistory(event);
            if(!journal.acknowledge(event.id()))throw new IllegalStateException("Technology history acknowledgement lost");
            delivered++;
        }
        return delivered;
    }
}
