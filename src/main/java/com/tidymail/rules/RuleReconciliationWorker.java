package com.tidymail.rules;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads native state; only a newly protected sender triggers a managed-rule pause. Never creates rules. */
@Component
public class RuleReconciliationWorker {
    private final AutomationRuleService rules;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"rule-reconciliation");t.setDaemon(true);return t;});
    @Value("${app.rules.reconciliation.enabled:true}") private boolean enabled;
    public RuleReconciliationWorker(AutomationRuleService rules){this.rules=rules;}
    @Scheduled(fixedDelayString="${app.rules.reconciliation.poll-ms:300000}",initialDelayString="${app.rules.reconciliation.startup-delay-ms:60000}")
    public void tick(){
        if(!enabled||!running.compareAndSet(false,true))return;
        try{executor.execute(()->{
            try{rules.reconcileDue();}catch(RuntimeException unavailable){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rule reconciliation deferred; provider state remains unconfirmed.");
            }finally{running.set(false);}
        });}catch(RejectedExecutionException stopped){running.set(false);if(!executor.isShutdown())throw stopped;}
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
