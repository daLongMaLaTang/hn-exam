package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:ai_usage;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"}, showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(AiUsageService.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AiUsageServiceTest {
    @DynamicPropertySource static void isolatedMysql(DynamicPropertyRegistry registry) {
        if (!Boolean.getBoolean("hnexam.ai-usage.mysql")) return;
        String password=System.getenv("HNEXAM_LOADTEST_PASSWORD");
        if (password==null || password.isBlank()) throw new IllegalStateException("缺少隔离测试库凭据");
        registry.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:3306/hn_exam_loadtest_20261011?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username",()->"hn_loadtest_20261011");
        registry.add("spring.datasource.password",()->password);
    }
    @Autowired AiUsageService usage;
    @Autowired AiUsageBucketRepository buckets;
    @Autowired AiCallLeaseRepository leases;
    @Autowired PlatformTransactionManager manager;
    private static final Instant NOW=Instant.parse("2026-10-13T15:59:00Z");

    @BeforeEach void reset() {
        leases.deleteAll(); buckets.deleteAll(); configure(usage); usage.initialize();
    }
    void configure(AiUsageService service) {
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(NOW,ZoneOffset.UTC));
        ReflectionTestUtils.setField(service,"maxConcurrent",2);
        ReflectionTestUtils.setField(service,"userMaxConcurrent",1);
        ReflectionTestUtils.setField(service,"dailyCalls",4L);
        ReflectionTestUtils.setField(service,"userDailyCalls",2L);
        ReflectionTestUtils.setField(service,"maxInputChars",16000);
        ReflectionTestUtils.setField(service,"maxOutputTokens",1024);
        ReflectionTestUtils.setField(service,"timeoutSeconds",60);
    }
    void complete(String lease) { usage.complete(lease,AiUsageService.Outcome.SUCCEEDED,50,100L,10L); }

    @Test void concurrentInstancesShareAdmissionAndDoNotOvershoot() throws Exception {
        var other=new AiUsageService(buckets,leases,manager); configure(other); other.initialize();
        var pool=Executors.newFixedThreadPool(12); var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (int i=0;i<12;i++) {
                long userId=i+1L; var instance=i%2==0?usage:other;
                results.add(pool.submit(() -> {
                    start.await();
                    try { instance.acquire(userId,60); return true; }
                    catch (AiCallLimitException expected) { return false; }
                }));
            }
            start.countDown(); int admitted=0;
            for (var result:results) if (result.get(20,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(2);
            assertThat(usage.today()).containsEntry("admitted",2L).containsEntry("active",2L).containsEntry("rejected",10L);
        } finally { pool.shutdownNow(); }
    }

    @Test void sameUserCannotOccupyTwoSlotsAndReleaseIsIdempotent() {
        String first=usage.acquire(1L,60);
        assertThatThrownBy(()->usage.acquire(1L,60)).isInstanceOfSatisfying(AiCallLimitException.class,e->assertThat(e.isRetryable()).isTrue());
        String second=usage.acquire(2L,60);
        complete(first); complete(first); complete(second);
        assertThat(usage.today()).containsEntry("active",0L).containsEntry("succeeded",2L)
                .containsEntry("promptTokens",200L).containsEntry("completionTokens",20L);
        complete(usage.acquire(1L,60));
        assertThatThrownBy(()->usage.acquire(1L,60)).hasMessageContaining("该学员今日")
                .isInstanceOfSatisfying(AiCallLimitException.class,e->assertThat(e.isRetryable()).isFalse());
    }

    @Test void dailyCapIsAtomicUnderConcurrentAdmission() throws Exception {
        ReflectionTestUtils.setField(usage,"maxConcurrent",20);
        var pool=Executors.newFixedThreadPool(12); var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (long i=1;i<=12;i++) {
                long userId=i;
                results.add(pool.submit(()->{
                    start.await();
                    try { usage.acquire(userId,60); return true; }
                    catch (AiCallLimitException expected) { return false; }
                }));
            }
            start.countDown(); int admitted=0;
            for (var result:results) if (result.get(20,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(4);
            assertThat(usage.today()).containsEntry("remainingCalls",0L).containsEntry("admitted",4L);
        } finally { pool.shutdownNow(); }
    }

    @Test void failedCallsConsumeBudgetAndRestartCannotResetIt() {
        for (long i=1;i<=4;i++) usage.complete(usage.acquire(i,60),AiUsageService.Outcome.FAILED,12,null,null);
        var restarted=new AiUsageService(buckets,leases,manager); configure(restarted); restarted.initialize();
        assertThatThrownBy(()->restarted.acquire(5L,60)).hasMessageContaining("总调用额度");
        assertThat(restarted.today()).containsEntry("admitted",4L).containsEntry("failed",4L)
                .containsEntry("remainingCalls",0L).containsEntry("missingUsage",4L);
    }

    @Test void midnightChangesQuotaButCompletionBelongsToAdmissionDay() {
        String lease=usage.acquire(1L,60);
        ReflectionTestUtils.setField(usage,"clock",Clock.fixed(NOW.plusSeconds(61),ZoneOffset.UTC));
        complete(lease);
        assertThat(usage.today()).containsEntry("date","2026-10-14").containsEntry("admitted",0L);
        var previous=buckets.findById("2026-10-13:GLOBAL").orElseThrow();
        assertThat(previous.getSucceeded()).isEqualTo(1);
        complete(usage.acquire(1L,60));
        assertThat(usage.today()).containsEntry("admitted",1L);
    }

    @Test void expiredLeaseRecoversCapacityWithoutRefundingOrFabricatingTokens() {
        String expired=usage.acquire(1L,60);
        ReflectionTestUtils.setField(usage,"clock",Clock.fixed(NOW.plusSeconds(91),ZoneOffset.UTC));
        String fresh=usage.acquire(1L,60);
        complete(expired);
        assertThat(leases.existsById(fresh)).isTrue();
        assertThat(buckets.findById("2026-10-13:GLOBAL").orElseThrow().getAbandoned()).isEqualTo(1);
        assertThat(buckets.findById("2026-10-13:GLOBAL").orElseThrow().getMissingUsage()).isEqualTo(1);
        complete(fresh);
    }

    @Test void inputLimitAndTimeoutAreBounded() {
        assertThat(usage.timeoutSeconds(600)).isEqualTo(60);
        var type=com.huaneng.shanyin.hnexam.domain.QuestionType.FILL_BLANK;
        assertThatThrownBy(()->usage.validateInput(new SubjectiveGradingProvider.Request(null,1L,type,"x".repeat(16001),"a","b",1,1L)))
                .isInstanceOf(AiCallLimitException.class);
        assertThatThrownBy(()->usage.validateInput(new SubjectiveGradingProvider.Request(null,1L,type,"x","a","b",1,null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(usage.today()).containsEntry("admitted",0L);
    }

    @Test void cleanupRetainsMutexAndRemovesOldDailyRows() {
        complete(usage.acquire(1L,60));
        ReflectionTestUtils.setField(usage,"clock",Clock.fixed(NOW.plus(Duration.ofDays(92)),ZoneOffset.UTC));
        usage.cleanup();
        assertThat(buckets.findAll()).extracting(b->b.getId()).containsExactly("LOCK");
    }
}
