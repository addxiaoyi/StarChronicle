package io.github.addxiaoyi.starchronicle.memory;

import java.time.Instant;

/** Read-only registration of an evidenced history source; it grants no construction or mutation authority. */
public record MemoryEntry(String id,String sourceEventId,String recipeId,String recipeVersion,String definitionHash,
                          String regionId,String title,String text,Instant sourceOccurredAt,Instant recordedAt,String signalEventId) {}
