package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.repository.ExamAttemptRepository;
import com.huaneng.shanyin.hnexam.service.ExamExpiryScheduler;
import com.huaneng.shanyin.hnexam.service.ExamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExamExpirySchedulerTest {
    @Test void failureDoesNotBlockLaterPapersAndNextSweepRetriesIt() {
        var repository = mock(ExamAttemptRepository.class);
        var service = mock(ExamService.class);
        var scheduler = scheduler(repository, service);
        when(repository.findExpiryCandidateIds(any(), eq(0L), any())).thenReturn(List.of(1L, 2L));
        when(repository.findExpiryCandidateIds(any(), eq(2L), any())).thenReturn(List.of(3L));
        doThrow(new IllegalStateException("simulated lock failure")).doNothing().when(service).expireAttempt(1L);
        scheduler.autoSubmitExpired();
        verify(service).expireAttempt(2L);
        verify(service).expireAttempt(3L);
        scheduler.autoSubmitExpired();
        verify(service, times(2)).expireAttempt(1L);
    }

    @Test void boundedSweepResumesInsteadOfRepeatingFirstPage() {
        var repository = mock(ExamAttemptRepository.class);
        var service = mock(ExamService.class);
        var scheduler = scheduler(repository, service);
        ReflectionTestUtils.setField(scheduler, "maxBatches", 1);
        when(repository.findExpiryCandidateIds(any(), eq(0L), any())).thenReturn(List.of(1L, 2L));
        when(repository.findExpiryCandidateIds(any(), eq(2L), any())).thenReturn(List.of(3L));
        scheduler.autoSubmitExpired();
        verify(service, never()).expireAttempt(3L);
        scheduler.autoSubmitExpired();
        verify(service).expireAttempt(3L);
        verify(service, times(1)).expireAttempt(1L);
    }

    private ExamExpiryScheduler scheduler(ExamAttemptRepository repository, ExamService service) {
        var scheduler = new ExamExpiryScheduler(repository, service);
        ReflectionTestUtils.setField(scheduler, "batchSize", 2);
        ReflectionTestUtils.setField(scheduler, "maxBatches", 20);
        ReflectionTestUtils.setField(scheduler, "runBudgetMs", 5000L);
        return scheduler;
    }
}
