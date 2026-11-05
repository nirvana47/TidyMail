package com.tidymail.job;

import com.tidymail.service.LabelingPipeline;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LabelingWorker {
    private final LabelingPipeline pipeline;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"reviewed-labeling");t.setDaemon(true);return t;});
    private final ScheduledExecutorService explicit=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"explicit-labeling");t.setDaemon(true);return t;});
    private UUIDScope active;
    private record UUIDScope(long account,java.util.UUID id) {}
    @Value("${app.labeling.worker.enabled:false}") private boolean enabled;
    public LabelingWorker(LabelingPipeline pipeline){this.pipeline=pipeline;}
    /** Explicit user-approved work does not require enabling any global scheduled jobs. */
    public synchronized void startReviewed(long account,java.util.UUID scope,String hash) {
        var saved=pipeline.progress(account,scope);
        if(!saved.scope().hash().equals(hash))throw new IllegalArgumentException("Reviewed scope did not match");
        if(active!=null && active.equals(new UUIDScope(account,scope)) && !saved.paused())return;
        if(saved.spending()!=null && !saved.spending().canStart())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"The next request cannot be funded yet. Check the current allowance and outstanding requests before resuming.");
        if(active!=null && !active.equals(new UUIDScope(account,scope)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Another reviewed batch is running. Pause it before starting another.");
        if(!saved.authorized())pipeline.authorize(account,scope,hash);
        else if(saved.paused())pipeline.resume(account,scope,hash);
        if(active!=null)return;
        active=new UUIDScope(account,scope);explicit.execute(()->advance(account,scope));
    }
    public synchronized boolean active(long account,java.util.UUID scope) {return new UUIDScope(account,scope).equals(active);}
    private void advance(long account,java.util.UUID scope) {
        try {
            var saved=pipeline.progress(account,scope);
            if(saved.paused() || saved.work().stream().noneMatch(w->java.util.Set.of("PENDING","LEASED","DISPATCHED").contains(w.state()))) {
                finish(account,scope);return;
            }
            if(saved.spending()!=null && !saved.spending().canStart()){pipeline.pause(account,scope);finish(account,scope);return;}
            if(running.compareAndSet(false,true))try{pipeline.runOnce(scope);}finally{running.set(false);}
            explicit.schedule(()->advance(account,scope),1,TimeUnit.SECONDS);
        } catch(RuntimeException failure) {
            pipeline.pause(account,scope);finish(account,scope);
        }
    }
    private synchronized void finish(long account,java.util.UUID scope) {if(new UUIDScope(account,scope).equals(active))active=null;}
    @Scheduled(fixedDelayString="${app.labeling.poll-ms:1000}",initialDelayString="${app.labeling.startup-delay-ms:15000}")
    public void tick(){if(enabled && running.compareAndSet(false,true))executor.submit(()->{try{pipeline.runOnce();}finally{running.set(false);}});}
    @PreDestroy public void close(){executor.shutdownNow();explicit.shutdownNow();}
}
