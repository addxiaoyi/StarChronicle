package io.github.addxiaoyi.starchronicle.api;

import java.util.List;

/** Immutable projection of an existing StarCore NarrativeEngine session, never a second interpreter. */
public record NativeScene(String scriptId,String nodeId,String title,String text,List<Choice> choices) {
    public NativeScene {choices=List.copyOf(choices);}
    /** index is the native engine's 1-based visible-choice index. */
    public record Choice(int index,String text) {}
}
