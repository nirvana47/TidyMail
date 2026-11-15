package com.tidymail.job;

import com.tidymail.service.DashboardClassificationService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DashboardClassificationWorker {
    @org.springframework.beans.factory.annotation.Value("${app.classification.worker.enabled:true}")
    private boolean enabled=true;
    @org.springframework.beans.factory.annotation.Value("${app.classification.benchmark.enabled:false}")
    private volatile boolean benchmarking;
    public void finishBenchmark(){benchmarking=false;}
    private final DashboardClassificationService service;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"jev-classification");t.setDaemon(true);return t;});
    public DashboardClassificationWorker(DashboardClassificationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.classification.poll-ms:1000}",initialDelayString="${app.classification.startup-delay-ms:15000}")
    public void tick() {
        if(!enabled || benchmarking || !running.compareAndSet(false,true))return;
        try {
            executor.execute(() -> {
                try {
                    for(int i=0;i<5 && !Thread.currentThread().isInterrupted();i++)if(!service.runOnce())break;
                } catch(RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Classification worker deferred diagnostic={}",
                        com.tidymail.util.FailureDiagnostics.failureDiagnostic(failure));
                } finally {running.set(false);}
            });
        } catch(RejectedExecutionException stopped) {
            running.set(false);
            if(!executor.isShutdown())throw stopped;
        }
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
