package io.github.addxiaoyi.starchronicle.history;

import java.util.*;

/** A complete read of one explicitly bounded authority scope. Missing services must report
 * complete=false, never a successful empty scan. allowEmpty is an explicit completeness assertion. */
public record ObservationSnapshot(String namespace, String source, Kind kind, boolean complete,
                                  boolean allowEmpty, Map<String,Entry> entries, String evidence) {
    public enum Kind { NATIONS, CAPITALS, TECHNOLOGIES }
    public record Entry(String nationId, String value, Map<String,String> facts) {
        public Entry {
            bounded(nationId,"nationId",256); bounded(value,"value",1024);
            facts=Map.copyOf(facts==null?Map.of():facts);
            if(facts.size()>32)throw new IllegalArgumentException("Too many snapshot facts");
            facts.forEach((key,v)->{bounded(key,"fact key",128);bounded(v,"fact value",1024);});
        }
    }
    public ObservationSnapshot {
        bounded(namespace,"namespace",96);bounded(source,"source",256);Objects.requireNonNull(kind);
        bounded(evidence,"evidence",1024);entries=Map.copyOf(entries==null?Map.of():entries);
        if(entries.size()>100000)throw new IllegalArgumentException("Snapshot exceeds entry limit");
        entries.forEach((key,value)->{bounded(key,"entry key",256);Objects.requireNonNull(value);});
    }
    public static ObservationSnapshot unavailable(String namespace,String source,Kind kind,String reason) {
        return new ObservationSnapshot(namespace,source,kind,false,false,Map.of(),reason);
    }
    private static void bounded(String value,String label,int limit) {
        if(value==null||value.isBlank()||value.length()>limit)throw new IllegalArgumentException("Invalid snapshot "+label);
    }
}
