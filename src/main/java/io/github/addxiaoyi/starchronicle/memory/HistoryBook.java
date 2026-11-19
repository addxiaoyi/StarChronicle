package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.util.*;

/** Literal text only. Books quote recorded provenance, never AI-generated historical claims. */
public final class HistoryBook {
    private HistoryBook() {}
    public static List<String> pages(List<WorldEvent> events) {
        if(events.size()>1) throw new IllegalArgumentException("One evidenced event per volume");
        List<String> result=new ArrayList<>();
        result.add("世界真实历史\n\n本卷由已提交的事件档案生成。时间基准、来源与证据均保留。\n\n完整事实与参与者请查阅 /chronicle history。");
        for(WorldEvent event:events) {
            append(result,"事件 ID\n"+event.id());
            append(result,event.type()+"\n"+event.occurredAt()+"\n时间基准: "+event.facts().getOrDefault("time_basis","SOURCE")+"\n来源: "+event.source()+"\n国家数: "+event.nations().size()+"\n参与者数: "+event.participants().size());
            append(result,"证据\n"+event.evidence());
        }
        if(events.isEmpty())result.add("此范围暂无已提交历史。后续世界事件将自动归档。");
        if(result.size()>100)throw new IllegalArgumentException("History volume exceeds book limit; select fewer events");
        return List.copyOf(result);
    }
    private static void append(List<String> pages,String text) {
        // Safe at Unicode code-point boundaries; conservative line width for the vanilla reader.
        List<String> lines=new ArrayList<>();
        for(String line:text.split("\\R",-1)){
            int[] points=line.codePoints().toArray();
            if(points.length==0)lines.add("");
            for(int i=0;i<points.length;i+=17)lines.add(new String(points,i,Math.min(17,points.length-i)));
        }
        for(int i=0;i<lines.size();i+=11)pages.add(String.join("\n",lines.subList(i,Math.min(i+11,lines.size()))));
    }
}
