package com.tidymail.job;

import com.tidymail.service.CalendarLabelRefreshService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Paced, read-only Calendar checks, independent of paid labeling and mail sync. */
@Component
public class CalendarLabelWorker {
    private final CalendarLabelRefreshService service;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> {
        var thread=new Thread(r,"calendar-labels");thread.setDaemon(true);return thread;
    });
    @Value("${app.calendar-labels.worker.enabled:true}") private boolean enabled;
    public CalendarLabelWorker(CalendarLabelRefreshService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.calendar-labels.poll-ms:2000}",initialDelayString="${app.calendar-labels.startup-delay-ms:20000}")
    public void tick() {
        if(enabled && running.compareAndSet(false,true))executor.submit(() -> {
            try { service.runOnce(); } finally { running.set(false); }
        });
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
