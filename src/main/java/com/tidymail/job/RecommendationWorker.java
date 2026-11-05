package com.tidymail.job;

import com.tidymail.service.RecommendationProcessingService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Yields between bounded batches; dashboard reads never start this work. */
@Component
public class RecommendationWorker {
    private final RecommendationProcessingService service;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"local-recommendations");t.setDaemon(true);return t;});
    @Value("${app.recommendations.worker.enabled:true}") private boolean enabled;
    public RecommendationWorker(RecommendationProcessingService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.recommendations.poll-ms:1000}",initialDelayString="${app.recommendations.startup-delay-ms:15000}")
    public void tick() {
        if(enabled && running.compareAndSet(false,true)) executor.submit(()->{
            try { service.runOnce(); } finally { running.set(false); }
        });
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
