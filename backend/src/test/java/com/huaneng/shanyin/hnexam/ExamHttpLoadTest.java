package com.huaneng.shanyin.hnexam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Opt-in HTTP regression workload, never connects to the real project database.
 * 2000 active papers != 2000 simultaneous requests; transport concurrency is explicitly bounded.
 * Optional flags include password login, an isolated MySQL schema, a 2000-request read burst and diagnostic recovery.
 * Real browsers, external AI and cloud infrastructure are excluded.
 */
@EnabledIfSystemProperty(named = "hnexam.loadtest", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:http_load;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.bootstrap.question-bank-path=",
        "app.bootstrap.admin-password=LoadOnlyAdmin-2026", "app.redis.enabled=false",
        "app.jwt.secret=isolated-http-load-test-secret-at-least-32-bytes",
        "app.exams.expiry-check-ms=15000"})
class ExamHttpLoadTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
        if (Boolean.getBoolean("hnexam.loadtest.mysql")) {
            String password=System.getenv("HNEXAM_LOADTEST_PASSWORD");
            if(password==null || password.isBlank()) throw new IllegalStateException("Isolated load-test password is required");
            // Hardcoded disposable schema: never permit an arbitrary URL with create-drop.
            r.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:3306/hn_exam_loadtest_20261011?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
            r.add("spring.datasource.username",()->"hn_loadtest_20261011");
            r.add("spring.datasource.password",()->password);
        }
    }
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired jakarta.persistence.EntityManager entityManager;
    final Map<String,Map<String,AtomicInteger>> responseCounts=new ConcurrentHashMap<>();
    final Map<String,Double> phaseSeconds=new LinkedHashMap<>();
    final List<String> initialFailures=new ArrayList<>();
    final Map<String,AtomicInteger> burstTransportErrors=new ConcurrentHashMap<>();
    final ThreadLocal<Boolean> recovery=ThreadLocal.withInitial(()->false);
    @LocalServerPort int port;
    @Autowired ExamService exams;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired QuestionRepository questions;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    final Map<String, List<Long>> timings = new ConcurrentHashMap<>();

    @Test void twoThousandActivePapersWithFiftyQuestionsAndMinuteAutosave() throws Exception {
        int count = 2000, questionCount = 50, concurrency = 100;
        boolean loginIncluded=Boolean.getBoolean("hnexam.loadtest.login");
        String encodedPassword=loginIncluded ? passwordEncoder.encode("LoadOnly-20261011") : "login-excluded-from-this-test";
        var tokens = new ArrayList<String>();
        var items = new ArrayList<ExamDtos.QuestionItem>();
        tx.executeWithoutResult(s -> {
            var role = roles.findByCode("CANDIDATE").orElseThrow();
            for (int i = 0; i < count; i++) {
                var u = new AppUser(); u.setEmployeeNo("http-load-" + i); u.setName("隔离压测" + i);
                u.setPasswordHash(encodedPassword); u.setMustChangePassword(false); u.getRoles().add(role);
                tokens.add(auth.tokenFor(users.save(u)).accessToken());
            }
            for (int i = 0; i < questionCount; i++) {
                var q = new Question(); q.setCourseCode("HTTP_LOAD"); q.setType(QuestionType.SINGLE_CHOICE);
                q.setStatus(QuestionStatus.ACTIVE); q.setStem("隔离压测题 " + i + "：请根据安全操作规程选择正确答案。".repeat(4));
                q.setOptionsJson("[\"A.符合规程\",\"B.不符合规程\"]"); q.setAnswer("A"); q.setExplanation("测试说明");
                questions.save(q); items.add(new ExamDtos.QuestionItem(q.getId(), 2, i + 1));
            }
        });
        Long examId = exams.create(new ExamDtos.SaveRequest("2000人HTTP隔离压测", "HTTP_LOAD", List.of(), "", 90, 100, 60,
                null, null, false, 1, false, true, false, 20, items)).id();
        exams.publish(examId);
        long[] attempts = new long[count];
        var executor = Executors.newScheduledThreadPool(concurrency);
        var report = new LinkedHashMap<String, Object>();
        report.put("users", count); report.put("questionsPerPaper", questionCount);
        report.put("maxInFlightHttpRequests", concurrency); report.put("database", Boolean.getBoolean("hnexam.loadtest.mysql") ? "isolated MySQL hn_exam_loadtest_20261011; pool=30; Redis disabled" : "isolated in-memory H2; pool=30; Redis disabled");
        report.put("exclusions", "Real browsers, external AI, public network, full-duration exam; application and load generator share one JVM");
        report.put("passwordLoginIncluded",loginIncluded);
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            report.put("jdbcTransactionIsolation", connection.getTransactionIsolation());
            assertThat(connection.getTransactionIsolation()).isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        }
        long begin = System.nanoTime();
        try {
            if(loginIncluded) {
                var loginExecutor=Executors.newScheduledThreadPool(20);
                try { runPhase(loginExecutor,count,0,i->{
                    var result=request("login", "POST", "/api/auth/login", null,
                        json.writeValueAsString(Map.of("employeeNo","http-load-"+i,"password","LoadOnly-20261011")));
                    assertThat(result.path("accessToken").asText()).isNotBlank();
                    tokens.set(i,result.path("accessToken").asText());
                }); } finally { loginExecutor.shutdownNow(); }
                report.put("loginConcurrency",20);
                System.out.println("LOAD_PHASE password login complete: 2000 users, bcrypt cost 12");
            }
            runPhase(executor, count, 0, i -> {
                var result = request("start", "POST", "/api/candidate/exams/" + examId + "/start", tokens.get(i), "{}");
                attempts[i] = result.path("attemptId").asLong();
                assertThat(result.path("questions").size()).isEqualTo(questionCount);
            });
            assertThat(Arrays.stream(attempts).distinct().count()).isEqualTo(count);
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt where exam_id=? and status='IN_PROGRESS'", Long.class, examId)).isEqualTo(count);
            System.out.println("LOAD_PHASE start complete: 2000 active papers");

            // One complete minute, 2000 uploads evenly spread (~33/s), as in the new browser timer.
            runPhase(executor, count, 60000, i -> {
                var ack = request("save", "PUT", "/api/candidate/attempts/" + attempts[i] + "/answers", tokens.get(i), body(items, i, false));
                assertThat(ack.path("status").asText()).isEqualTo("IN_PROGRESS");
            });
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt_answer a join exam_attempt t on t.id=a.attempt_id where t.exam_id=?", Long.class, examId)).isEqualTo((long) count * questionCount);
            System.out.println("LOAD_PHASE autosave complete: 100000 persisted answers");

            if(Boolean.getBoolean("hnexam.loadtest.burst")) {
                long burstBegin=System.nanoTime(); var pending=new ArrayList<CompletableFuture<Void>>();
                for(int i=0;i<count;i++) {
                    var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/candidate/attempts/"+attempts[i]))
                        .timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+tokens.get(i)).GET().build();
                    long started=System.nanoTime();
                    pending.add(client.sendAsync(req,HttpResponse.BodyHandlers.discarding()).handle((res,err)->{
                        timings.computeIfAbsent("burst2000Read",key->Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime()-started);
                        responseCounts.computeIfAbsent("burst2000Read",key->new ConcurrentHashMap<>()).computeIfAbsent(err==null?String.valueOf(res.statusCode()):"transportError",key->new AtomicInteger()).incrementAndGet();
                        if(err!=null) {
                            Throwable cause=err;while(cause.getCause()!=null)cause=cause.getCause();
                            burstTransportErrors.computeIfAbsent(cause.getClass().getName(),key->new AtomicInteger()).incrementAndGet();
                        }
                        return null;
                    }));
                }
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(60,TimeUnit.SECONDS);
                phaseSeconds.put("burst2000Read",(System.nanoTime()-burstBegin)/1e9);
                System.out.println("LOAD_PHASE burst2000Read complete: "+responseCounts.get("burst2000Read"));
                if(responseCounts.get("burst2000Read").getOrDefault("200",new AtomicInteger()).get()!=count)
                    initialFailures.add("burst2000Read did not return HTTP 200 for all users; later independent phases still run");
            }
            runPhase(executor, count, 0, i -> {
                var grade = request("submit", "POST", "/api/candidate/attempts/" + attempts[i] + "/submit", tokens.get(i), body(items, i, true));
                assertThat(grade.path("status").asText()).isEqualTo("GRADED");
                assertThat(grade.path("score").asInt()).isEqualTo(i % 2 == 0 ? 98 : 2);
            });
            // Read back every paper via HTTP and verify every persisted answer after submission.
            runPhase(executor, count, 0, i -> {
                var review = request("review", "GET", "/api/candidate/attempts/" + attempts[i] + "/review", tokens.get(i), null);
                var actual = review.path("questions"); assertThat(actual.size()).isEqualTo(questionCount);
                for (int q = 0; q < questionCount; q++) {
                    String expected = (i % 2 == 0) == (q != 0) ? "A" : "B";
                    assertThat(actual.get(q).path("savedAnswer").asText()).isEqualTo(expected);
                }
            });
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt where exam_id=? and status='GRADED'", Long.class, examId)).isEqualTo(count);
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt_answer a join exam_attempt t on t.id=a.attempt_id where t.exam_id=?", Long.class, examId)).isEqualTo((long) count * questionCount);
            report.put("dataIntegrityBeforeExpiry",true);
            // Reopen only this isolated fixture to simulate 2000 abandoned papers expiring together.
            // The real scheduled bean must drain the backlog through its bounded, independent transactions.
            // Use the application's Instant mapping; raw JDBC Timestamp otherwise shifts MySQL DATETIME by the JVM time zone.
            tx.executeWithoutResult(status -> entityManager.createQuery("update ExamAttempt a set a.status=:state,a.deadline=:deadline,a.submittedAt=null,a.score=null,a.passed=null where a.exam.id=:exam")
                    .setParameter("state",AttemptStatus.IN_PROGRESS).setParameter("deadline",java.time.Instant.now().minusSeconds(1)).setParameter("exam",examId).executeUpdate());
            long expiryBegin = System.nanoTime();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
                    .until(() -> jdbc.queryForObject("select count(*) from exam_attempt where exam_id=? and status='GRADED'", Long.class, examId) == count);
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt where exam_id=? and score in (2,98)", Long.class, examId)).isEqualTo(count);
            assertThat(jdbc.queryForObject("select count(*) from exam_attempt_answer a join exam_attempt t on t.id=a.attempt_id where t.exam_id=?", Long.class, examId)).isEqualTo((long) count * questionCount);
            report.put("scheduledExpiry2000PapersSecondsIncludingPolling", (System.nanoTime() - expiryBegin) / 1_000_000_000.0);
            System.out.println("LOAD_PHASE scheduled expiry complete: 2000 papers, independent commits");
            report.put("passed", initialFailures.isEmpty());
            report.put("dataIntegrityAfterRecovery",true);
        } finally {
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
            report.putIfAbsent("passed", false);
            report.put("elapsedSeconds", (System.nanoTime() - begin) / 1_000_000_000.0);
            var latency = new TreeMap<String, Object>();
            timings.forEach((name, values) -> {
                var sorted = values.stream().sorted().toList();
                latency.put(name, Map.of("completedRequests", sorted.size(), "p50Millis", sorted.get((int)Math.ceil(sorted.size()*.50)-1)/1e6, "p99Millis", sorted.get((int)Math.ceil(sorted.size()*.99)-1)/1e6, "p95Millis", sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1_000_000.0,
                        "maxMillis", sorted.get(sorted.size() - 1) / 1_000_000.0));
            });
            report.put("latencyExcludesClientQueue", latency);
            report.put("initialFailurePhases",initialFailures); report.put("httpOutcomes",responseCounts); report.put("phaseWallSeconds",phaseSeconds);
            report.put("burstTransportErrorsByRootCause",burstTransportErrors);
            Files.createDirectories(Path.of("target"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of(Boolean.getBoolean("hnexam.loadtest.mysql") ? "target/mysql-load-report.json" : "target/http-load-report.json").toFile(), report);
            System.out.println("LOAD_REPORT " + json.writeValueAsString(report));
        }
        assertThat(initialFailures).as("First-attempt failures remain failures even if diagnostic retries recover the data").isEmpty();
    }

    String body(List<ExamDtos.QuestionItem> items, int user, boolean finalAnswer) throws Exception {
        Map<Long, String> answers = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) answers.put(items.get(i).questionId(),
                (user % 2 == 0) != (finalAnswer && i == 0) ? "A" : "B");
        return json.writeValueAsString(new ExamDtos.SubmitRequest(answers, 0));
    }

    JsonNode request(String phase, String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if(token!=null) builder.header("Authorization", "Bearer " + token);
        var request=builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        String measuredPhase=phase+(recovery.get()?"_recovery":"");
        long begin=System.nanoTime(); String outcome="transportError";
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());outcome=String.valueOf(response.statusCode());
            assertThat(response.statusCode()).as("%s HTTP status",phase).isEqualTo(200);
            return json.readTree(response.body());
        } finally {
            timings.computeIfAbsent(measuredPhase,key->Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime()-begin);
            responseCounts.computeIfAbsent(measuredPhase,key->new ConcurrentHashMap<>()).computeIfAbsent(outcome,key->new AtomicInteger()).incrementAndGet();
        }
    }

    void runPhase(ScheduledExecutorService executor,int count,long spreadMillis,Work work) throws Exception {
        var futures=new ArrayList<Future<?>>();long start=System.nanoTime();var before=new HashSet<>(timings.keySet());
        for(int i=0;i<count;i++){final int user=i;futures.add(executor.schedule(()->{work.run(user);return null;},i*spreadMillis/count,TimeUnit.MILLISECONDS));}
        Exception first=null;var failedUsers=new ArrayList<Integer>();
        for(int i=0;i<futures.size();i++)try{futures.get(i).get(300,TimeUnit.SECONDS);}catch(Exception e){failedUsers.add(i);if(first==null)first=e;}
        for(String name:timings.keySet())if(!before.contains(name))phaseSeconds.put(name,(System.nanoTime()-start)/1e9);
        if(first!=null) {
            if(!Boolean.getBoolean("hnexam.loadtest.recover"))throw first;
            initialFailures.add("phase failed for "+failedUsers.size()+" users; serial recovery follows");
            recovery.set(true);
            try{for(int i:failedUsers)work.run(i);}finally{recovery.remove();}
        }
    }

    interface Work { void run(int user) throws Exception; }
}
