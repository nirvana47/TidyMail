package com.tidymail.job;

import com.tidymail.service.DashboardClassificationService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DashboardClassificationWorker {
    private final DashboardClassificationService service;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"jev-classification");t.setDaemon(true);return t;});
    public DashboardClassificationWorker(DashboardClassificationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.classification.poll-ms:1000}",initialDelayString="${app.classification.startup-delay-ms:15000}")
    public void tick(){if(running.compareAndSet(false,true))executor.submit(()->{try{for(int i=0;i<5;i++)if(!service.runOnce())break;}catch(RuntimeException suppressed){/* No remote content or exception bodies in logs. Durable lease enables recovery. */}finally{running.set(false);}});}
    @PreDestroy public void close(){executor.shutdownNow();}
}
