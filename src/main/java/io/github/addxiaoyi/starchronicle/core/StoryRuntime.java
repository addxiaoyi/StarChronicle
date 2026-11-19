package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import java.util.*;

/** One immutable publication of executable current content and its read-only version evidence. */
public record StoryRuntime(List<StoryDefinition> enabledDefinitions,DefinitionCatalog catalog) {
    public StoryRuntime {enabledDefinitions=List.copyOf(enabledDefinitions);Objects.requireNonNull(catalog);}
}
