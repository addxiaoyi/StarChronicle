package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.TextProvider;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Optional text-only worker. Provider errors, slowness and invalid responses fall back to templates. */
public final class TextService implements AutoCloseable {
    private final ThreadPoolExecutor worker;
    private final long timeoutMillis;
    private final Semaphore inFlight=new Semaphore(32);
    private final AtomicBoolean closed=new AtomicBoolean();
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{([A-Za-z0-9_.:-]{1,80})}");
    public TextService(Duration timeout) {
        timeoutMillis=Objects.requireNonNull(timeout).toMillis();
        if(timeoutMillis<1||timeoutMillis>30_000)throw new IllegalArgumentException("Text timeout outside 1..30000 ms");
        worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),task->{Thread t=new Thread(task,"StarChronicle-text");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public CompletionStage<String> describe(TextProvider provider,String template,Map<String,String> publicFacts) {
        Objects.requireNonNull(template);Objects.requireNonNull(publicFacts);
        if(template.length()>4096||publicFacts.size()>64||!safeCharacters(template))throw new IllegalArgumentException("Invalid text request");
        Map<String,String> facts=new TreeMap<>();
        publicFacts.forEach((k,v)->{if(k==null||v==null||k.length()>80||v.length()>4096||!safeCharacters(v))throw new IllegalArgumentException("Invalid public fact");facts.put(k,v);});
        Map<String,String> immutable=Map.copyOf(facts);
        String fallback=render(template,immutable);
        if(provider==null||closed.get()||!inFlight.tryAcquire())return CompletableFuture.completedFuture(fallback);
        CompletableFuture<String> answer=new CompletableFuture<>();
        AtomicBoolean released=new AtomicBoolean();Runnable release=()->{if(released.compareAndSet(false,true))inFlight.release();};
        try{worker.execute(()->{
            if(answer.isDone()||closed.get()){release.run();answer.complete(fallback);return;}
            try {
                CompletionStage<String> result=provider.describe(template,immutable);
                if(result==null){release.run();answer.complete(fallback);return;}
                // Keep the slot until the provider really finishes. A timed-out never-ending
                // provider cannot create an unbounded number of underlying network requests.
                result.whenComplete((text,error)->{release.run();answer.complete(error==null&&valid(text)?text:fallback);});
            }catch(Throwable error){release.run();answer.complete(fallback);}
        });}catch(RejectedExecutionException busy){release.run();answer.complete(fallback);}
        return answer.completeOnTimeout(fallback,timeoutMillis,TimeUnit.MILLISECONDS);
    }
    private static boolean valid(String text){return text!=null&&!text.isBlank()&&text.length()<=8192&&safeCharacters(text);}
    private static boolean safeCharacters(String text){return text.codePoints().noneMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'&&c!='\r');}
    private static String render(String template,Map<String,String> facts){
        var matcher=PLACEHOLDER.matcher(template);StringBuilder result=new StringBuilder();int last=0;
        while(matcher.find()) {
            append(result,template.substring(last,matcher.start()));
            append(result,facts.getOrDefault(matcher.group(1),matcher.group()));last=matcher.end();
            if(result.length()>=8192)break;
        }
        if(result.length()<8192)append(result,template.substring(last));return result.toString();
    }
    private static void append(StringBuilder target,String value){int length=Math.min(value.length(),8192-target.length());if(length>0&&length<value.length()&&Character.isHighSurrogate(value.charAt(length-1)))length--;target.append(value,0,length);}
    @Override public void close(){closed.set(true);worker.shutdownNow();}
}
