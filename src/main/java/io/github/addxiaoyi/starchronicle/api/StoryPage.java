package io.github.addxiaoyi.starchronicle.api;

import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import java.util.List;

/** Live, read-only page. nextCursor is null only at the end; a filtered page may be empty.
 * Concurrent membership/instance changes are rechecked on every page and every action. */
public record StoryPage(List<StoryInstance> instances,String nextCursor) {
    public StoryPage {instances=List.copyOf(instances);}
}
