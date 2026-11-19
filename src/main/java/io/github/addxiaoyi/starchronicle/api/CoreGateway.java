package io.github.addxiaoyi.starchronicle.api;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Calls into existing StarCore executors only; no command dispatch or direct nation writes. */
public interface CoreGateway {
    PlayerContext context(UUID playerId);
    boolean hasScript(String id);
    boolean hasQuest(String id);
    CompletionStage<DeliveryResult> deliver(Delivery delivery);
    enum DeliveryResult { APPLIED, REJECTED, UNKNOWN }
}
