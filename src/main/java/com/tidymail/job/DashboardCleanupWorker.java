package com.tidymail.job;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.*;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.DashboardJobService;
import com.tidymail.service.DashboardJobService.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Single bounded worker, independent of the scheduler and HTTP requests. PostgreSQL owns the queue. */
@Component
public class DashboardCleanupWorker {
    private final DashboardJobService jobs;
    private final UserAccountRepository accounts;
    private final Map<String,MailProvider> providers;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"dashboard-cleanup");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    public DashboardCleanupWorker(DashboardJobService jobs,UserAccountRepository accounts,Map<String,MailProvider> providers) {
        this.jobs=jobs;this.accounts=accounts;this.providers=providers;
    }
    @Scheduled(fixedDelayString="${app.cleanup.poll-ms:1000}",initialDelayString="${app.cleanup.startup-delay-ms:10000}")
    public void tick() {
        if(running.compareAndSet(false,true))executor.submit(()->{
            try{runOnce();}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("Dashboard cleanup worker deferred after {}",error.getClass().getSimpleName());}
            finally{running.set(false);}
        });
    }
    @PreDestroy public void close() {executor.shutdownNow();}

    public void runOnce() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Provider I/O cannot run inside a database transaction");
        var claim=jobs.claim();if(claim==null)return;
        UserAccount account;MailProvider provider;MailboxSyncProvider reader;
        try {
            account=accounts.findById(claim.account()).orElseThrow();
            provider=providers.get("gmailMailService");
            if(!account.getProvider().equals("google")||!(provider instanceof MailboxSyncProvider))throw new IllegalStateException("Gmail metadata provider unavailable");
            reader=(MailboxSyncProvider)provider;
        } catch(RuntimeException error){jobs.defer(claim);return;}
        if(claim.verification()) {
            jobs.complete(claim,null,claim.targets(),observe(reader,account,claim.targets()));return;
        }
        Map<String,MailMessage> before;
        try{before=read(reader,account,claim.targets());}catch(RuntimeException error){jobs.defer(claim);return;}
        var dispatch=jobs.dispatch(claim,before);if(dispatch==null)return;
        try{provider.markReadOnce(account,dispatch.targets().stream().map(Target::providerId).toList());}
        catch(RuntimeException ambiguous){/* Read-only reconciliation also runs after uncertain sends. Never retry mutations. */}
        jobs.complete(claim,dispatch.batch(),dispatch.targets(),observe(reader,account,dispatch.targets()));
    }
    private Map<String,MailMessage> observe(MailboxSyncProvider reader,UserAccount account,List<Target> targets) {
        try{return read(reader,account,targets);}catch(RuntimeException error){return Map.of();}
    }
    private Map<String,MailMessage> read(MailboxSyncProvider reader,UserAccount account,List<Target> targets) {
        var ids=targets.stream().map(Target::providerId).toList();
        var messages=reader.getCurrentMetadata(account,ids);
        if(messages==null)throw new IllegalStateException("Missing metadata response");
        Map<String,MailMessage> result=new HashMap<>();
        for(var message:messages) {
            if(message==null||!ids.contains(message.id())||message.labelIds()==null||result.put(message.id(),message)!=null)
                throw new IllegalStateException("Invalid metadata response");
        }
        return result;
    }
}
