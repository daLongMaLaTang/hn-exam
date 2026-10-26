package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Database admission is atomic across instances; no DB transaction spans the network call. */
@Service
public class AiUsageService {
    private final AiUsageBucketRepository buckets;
    private final AiCallLeaseRepository leases;
    private final TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");
    @Value("${app.ai.limits.max-concurrent:8}") private int maxConcurrent;
    @Value("${app.ai.limits.user-max-concurrent:1}") private int userMaxConcurrent;
    @Value("${app.ai.limits.daily-calls:20000}") private long dailyCalls;
    @Value("${app.ai.limits.user-daily-calls:200}") private long userDailyCalls;
    @Value("${app.ai.limits.max-input-chars:16000}") private int maxInputChars;
    @Value("${app.ai.limits.max-output-tokens:1024}") private int maxOutputTokens;
    @Value("${app.ai.limits.timeout-seconds:60}") private int timeoutSeconds;

    public AiUsageService(AiUsageBucketRepository buckets, AiCallLeaseRepository leases, PlatformTransactionManager manager) {
        this.buckets=buckets; this.leases=leases;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    @PostConstruct public void initialize() {
        if (maxConcurrent<1 || userMaxConcurrent<1 || dailyCalls<1 || userDailyCalls<1
                || maxInputChars<1 || maxOutputTokens<1 || maxOutputTokens>8192 || timeoutSeconds<1 || timeoutSeconds>120)
            throw new IllegalArgumentException("AI调用限额配置无效");
        try {
            tx.executeWithoutResult(s -> {
                if (!buckets.existsById("LOCK")) buckets.saveAndFlush(new AiUsageBucket("LOCK",null));
            });
        } catch (DataIntegrityViolationException concurrentStartup) {
            // Another instance may have inserted the mutex first; all other failures remain fatal.
            if (!buckets.existsById("LOCK")) throw concurrentStartup;
        }
    }

    public int maxOutputTokens() { return maxOutputTokens; }
    public int timeoutSeconds(int configured) { return Math.min(timeoutSeconds, Math.max(1, configured)); }
    public void validateInput(SubjectiveGradingProvider.Request r) {
        if (r.userId()==null || r.userId()<=0) throw new IllegalArgumentException("AI调用缺少学员标识");
        long length=(long)Objects.toString(r.stem(),"").length()+Objects.toString(r.referenceAnswer(),"").length()
                +Objects.toString(r.candidateAnswer(),"").length();
        if (length>maxInputChars) throw new AiCallLimitException("题目或作答超过AI处理长度，请人工复核");
    }

    private record Admission(String leaseId, String rejection, boolean retryable) {}
    public String acquire(Long userId, int requestTimeoutSeconds) {
        if (userId==null || userId<=0) throw new IllegalArgumentException("AI调用缺少学员标识");
        Admission result=tx.execute(s -> {
            lock();
            Instant now=clock.instant();
            expire(now);
            LocalDate day=now.atZone(DAY_ZONE).toLocalDate();
            var global=bucket(day,null); var user=bucket(day,userId);
            String rejection=null;
            boolean retryable=false;
            if (global.getAdmitted()>=dailyCalls) rejection="今日AI总调用额度已用完，请人工复核";
            else if (user.getAdmitted()>=userDailyCalls) rejection="该学员今日AI调用额度已用完，请人工复核";
            else if (leases.count()>=maxConcurrent || leases.countByUserId(userId)>=userMaxConcurrent) {
                rejection="AI正在处理其他作答，请稍后重试或人工复核";
                retryable=true;
            }
            if (rejection!=null) {
                global.setRejected(global.getRejected()+1); user.setRejected(user.getRejected()+1);
                return new Admission(null,rejection,retryable);
            }
            global.setAdmitted(global.getAdmitted()+1); user.setAdmitted(user.getAdmitted()+1);
            var lease=new AiCallLease(); lease.setId(UUID.randomUUID().toString());
            lease.setUsageDate(day); lease.setUserId(userId);
            // Grace period covers normal transport cancellation and completion bookkeeping.
            lease.setExpiresAt(now.plusSeconds(timeoutSeconds(requestTimeoutSeconds)+30L));
            leases.save(lease);
            return new Admission(lease.getId(),null,false);
        });
        if (result.rejection()!=null) throw new AiCallLimitException(result.rejection(),result.retryable());
        return result.leaseId();
    }

    public enum Outcome { SUCCEEDED, FAILED, TIMED_OUT }
    public void complete(String leaseId, Outcome outcome, long durationMillis, Long promptTokens, Long completionTokens) {
        tx.executeWithoutResult(s -> {
            lock();
            leases.findById(leaseId).ifPresent(lease -> {
                for (var b:List.of(bucket(lease.getUsageDate(),null),bucket(lease.getUsageDate(),lease.getUserId()))) {
                    if (outcome==Outcome.SUCCEEDED) b.setSucceeded(b.getSucceeded()+1);
                    else if (outcome==Outcome.TIMED_OUT) b.setTimedOut(b.getTimedOut()+1);
                    else b.setFailed(b.getFailed()+1);
                    b.setDurationMillis(b.getDurationMillis()+Math.max(0,durationMillis));
                    if (promptTokens==null || completionTokens==null) b.setMissingUsage(b.getMissingUsage()+1);
                    else { b.setPromptTokens(b.getPromptTokens()+Math.max(0,promptTokens)); b.setCompletionTokens(b.getCompletionTokens()+Math.max(0,completionTokens)); }
                }
                leases.delete(lease);
            });
        });
    }

    public Map<String,Object> today() {
        return tx.execute(s -> {
            lock(); expire(clock.instant());
            LocalDate day=clock.instant().atZone(DAY_ZONE).toLocalDate(); var b=bucket(day,null);
            var result=new LinkedHashMap<String,Object>();
            result.put("date",day.toString()); result.put("timezone",DAY_ZONE.toString());
            result.put("admitted",b.getAdmitted()); result.put("succeeded",b.getSucceeded());
            result.put("failed",b.getFailed()); result.put("timedOut",b.getTimedOut());
            result.put("abandoned",b.getAbandoned()); result.put("rejected",b.getRejected());
            result.put("promptTokens",b.getPromptTokens()); result.put("completionTokens",b.getCompletionTokens());
            result.put("missingUsage",b.getMissingUsage()); result.put("durationMillis",b.getDurationMillis());
            result.put("active",leases.count()); result.put("remainingCalls",Math.max(0,dailyCalls-b.getAdmitted()));
            result.put("limits",Map.of("maxConcurrent",maxConcurrent,"userMaxConcurrent",userMaxConcurrent,
                    "dailyCalls",dailyCalls,"userDailyCalls",userDailyCalls,"maxInputChars",maxInputChars,
                    "maxOutputTokens",maxOutputTokens,"timeoutSeconds",timeoutSeconds));
            return result;
        });
    }

    @Scheduled(fixedDelayString="${app.ai.limits.cleanup-ms:3600000}")
    public void cleanup() {
        tx.executeWithoutResult(s -> {
            lock(); expire(clock.instant());
            buckets.deleteBefore(clock.instant().atZone(DAY_ZONE).toLocalDate().minusDays(90));
        });
    }
    private void lock() { buckets.lockLedger().orElseThrow(() -> new IllegalStateException("AI用量账本不可用")); }
    private AiUsageBucket bucket(LocalDate day, Long userId) {
        String id=day+":"+(userId==null?"GLOBAL":"USER:"+userId);
        return buckets.findById(id).orElseGet(() -> buckets.save(new AiUsageBucket(id,day)));
    }
    private void expire(Instant now) {
        for (var lease:leases.findByExpiresAtLessThanEqual(now)) {
            for (var b:List.of(bucket(lease.getUsageDate(),null),bucket(lease.getUsageDate(),lease.getUserId()))) {
                b.setAbandoned(b.getAbandoned()+1); b.setMissingUsage(b.getMissingUsage()+1);
            }
            leases.delete(lease);
        }
        leases.flush();
    }
}
