package io.github.addxiaoyi.starchronicle.api;
import java.util.UUID;

/** Durable outbox item. outcomeId is a stable idempotency key, not an authorization token. */
public record Delivery(String outcomeId, String instanceId, String type, String target, String value,
                       UUID playerId, String nationId, String permission) {}
