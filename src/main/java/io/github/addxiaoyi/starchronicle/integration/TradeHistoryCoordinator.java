package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.util.*;
import java.util.function.Function;

/** Keeps an installed source format, cursor and contribution identity on exactly one reader. */
public final class TradeHistoryCoordinator {
    private enum Mode { LEGACY, JOURNAL, CONFLICT }
    private static final String NAMESPACE=StarCoreTradeHistory.NAMESPACE;
    private final RuleEngine engine;
    private final CommittedHistoryJournal journal;
    private final StarCoreTradeHistory history;
    private final CommittedSourceCollector collector;
    private final Mode mode;

    public TradeHistoryCoordinator(RuleEngine engine,StarCoreBridge bridge,CommittedHistoryJournal journal) {
        this(engine,bridge::service,journal);
    }
    TradeHistoryCoordinator(RuleEngine engine,Function<String,Object> services,CommittedHistoryJournal journal) {
        this.engine=Objects.requireNonNull(engine);this.journal=Objects.requireNonNull(journal);
        history=new StarCoreTradeHistory(()->services.apply("module.resource.TradeHistoryService"),journal);
        collector=new CommittedSourceCollector(services);
        boolean legacy=engine.committedSourceCursor(NAMESPACE).isPresent();
        boolean current=journal.cursor(NAMESPACE).isPresent();
        mode=legacy?(current?Mode.CONFLICT:Mode.LEGACY):Mode.JOURNAL;
    }

    /** Called on Chronicle's state worker; one bounded page per source avoids starvation. */
    public int poll(Collection<PlayerContext> contexts)throws ReflectiveOperationException {
        var legacy=engine.committedSourceCursor(NAMESPACE);
        var current=journal.cursor(NAMESPACE);
        if(mode==Mode.CONFLICT||mode==Mode.LEGACY&&(legacy.isEmpty()||current.isPresent())
                ||mode==Mode.JOURNAL&&legacy.isPresent())
            throw new IllegalStateException("Conflicting or removed trade ledger; restore matching backups without deleting history");
        if(mode==Mode.LEGACY) {
            var page=collector.collect(CommittedSourceCollector.Source.TRADE,legacy);
            if(page.isEmpty())throw new IllegalStateException("Pinned trade source unavailable");
            return engine.acceptCommittedSource(page.get(),contexts);
        }
        history.scan();
        int delivered=0;
        for(var pending:journal.pending(NAMESPACE,CommittedHistoryJournal.PAGE_SIZE)) {
            var event=pending.event();
            engine.acceptCommittedHistory(pending,contexts.stream().filter(ctx->event.globalAudience()
                    ||event.participants().contains(ctx.playerId())||!ctx.nationId().isBlank()&&event.nations().contains(ctx.nationId())).toList());
            if(!journal.acknowledge(event.id()))throw new IllegalStateException("Trade history acknowledgement lost");
            delivered++;
        }
        return delivered;
    }
}
