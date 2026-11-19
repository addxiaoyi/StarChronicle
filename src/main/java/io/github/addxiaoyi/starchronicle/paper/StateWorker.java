package io.github.addxiaoyi.starchronicle.paper;

import java.util.concurrent.*;

/** A bounded serial worker whose storage close cannot be rejected by a full work queue. */
final class StateWorker extends ThreadPoolExecutor {
    private final Runnable closeStorage;
    StateWorker(int capacity,Runnable closeStorage) {
        super(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{Thread t=new Thread(r,"StarChronicle-state");t.setDaemon(true);return t;},new AbortPolicy());
        this.closeStorage=closeStorage;
    }
    @Override protected void terminated(){try{closeStorage.run();}finally{super.terminated();}}
}
