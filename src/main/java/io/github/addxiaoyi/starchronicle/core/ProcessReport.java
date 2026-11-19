package io.github.addxiaoyi.starchronicle.core;
import java.util.List;
public record ProcessReport(boolean duplicate, List<String> instanceIds, String message) {
    public ProcessReport { instanceIds=List.copyOf(instanceIds); }
}
