package io.github.addxiaoyi.starchronicle.api;

import java.util.List;

/** Live evidence page in stored time/ID order. A cursor is an existing event ID in the
 * same scope, not an authorization token or a fixed-time snapshot. Null means the end. */
public record HistoryPage(List<WorldEvent> events,String nextCursor) {
    public HistoryPage {events=List.copyOf(events);}
}
