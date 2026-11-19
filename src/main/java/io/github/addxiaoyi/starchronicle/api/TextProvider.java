package io.github.addxiaoyi.starchronicle.api;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Text-only optional AI boundary. No effects, commands, rewards, state or service handles. */
public interface TextProvider {
    CompletionStage<String> describe(String template, Map<String,String> publicFacts);
    static TextProvider templates() { return (template,facts) -> {
        String rendered=template;
        for(var e:facts.entrySet()) rendered=rendered.replace("{"+e.getKey()+"}",e.getValue());
        return CompletableFuture.completedFuture(rendered);
    }; }
}
