package com.huaneng.shanyin.hnexam.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ShortAnswerWorker {
    private final ShortAnswerAutomationService jobs;
    private final boolean enabled;
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"short-answer-worker");t.setDaemon(true);return t;});
    private final AtomicInteger running=new AtomicInteger();
    public ShortAnswerWorker(ShortAnswerAutomationService jobs,@Value("${app.ai.short-automation-enabled:false}") boolean enabled) {this.jobs=jobs;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${app.ai.short-worker-delay-ms:2000}")
    public void tick() {
        if(!enabled) return;
        for(int acquired=0;acquired<2 && running.get()<2;acquired++) {
            var id=jobs.claim();if(id==null) return;
            running.incrementAndGet();
            try {executor.submit(()->{try {jobs.process(id);}finally {running.decrementAndGet();}});}
            catch(RejectedExecutionException stopped) {running.decrementAndGet();return;}
        }
    }
    @PreDestroy public void close() {executor.shutdownNow();}
}
