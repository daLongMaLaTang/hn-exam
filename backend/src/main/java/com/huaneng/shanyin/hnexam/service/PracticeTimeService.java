package com.huaneng.shanyin.hnexam.service;
import com.huaneng.shanyin.hnexam.repository.*; import com.huaneng.shanyin.hnexam.entity.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import lombok.RequiredArgsConstructor;
import java.time.*;
@Service @RequiredArgsConstructor @Transactional
public class PracticeTimeService {
    private final UserRepository users; private final PracticeDayRepository days; private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public record Time(long totalSeconds,long todaySeconds,boolean recording) {}
    public Time heartbeat(String employeeNo,String session,boolean visible,int idleSeconds) { return heartbeatAt(employeeNo,session,visible,idleSeconds,Instant.now()); }
    public Time heartbeatAt(String employeeNo,String session,boolean visible,int idleSeconds,Instant now) {
        if(session==null || session.length()>64 || session.isBlank() || idleSeconds<0 || idleSeconds>120) throw new IllegalArgumentException("练习活动参数不正确");
        AppUser u=users.findLockedByEmployeeNo(employeeNo).orElseThrow(); Instant prev=u.getPracticeHeartbeat();
        boolean owner=prev==null || session.equals(u.getPracticeSession()) || Duration.between(prev,now).getSeconds()>40 || !Boolean.TRUE.equals(u.getPracticeActive());
        if(owner) {
            long elapsed=prev==null ? 0 : Duration.between(prev,now).getSeconds();
            if(session.equals(u.getPracticeSession()) && Boolean.TRUE.equals(u.getPracticeActive()) && elapsed>0 && elapsed<=45) {
                // Idle reports stop at 120s; no retrospective gap credit after a suspended browser.
                Instant creditEnd=u.getPracticeActiveUntil()==null || u.getPracticeActiveUntil().isAfter(now) ? now : u.getPracticeActiveUntil();
                Instant cursor=prev;
                while(cursor.isBefore(creditEnd)) { LocalDate date=cursor.atZone(ZONE).toLocalDate(); Instant end=date.plusDays(1).atStartOfDay(ZONE).toInstant(); if(end.isAfter(creditEnd))end=creditEnd;
                    var day=days.findByUserIdAndDate(u.getId(),date).orElseGet(()->{var d=new PracticeDay();d.setUser(u);d.setDate(date);return d;}); day.setSeconds(day.getSeconds()+Duration.between(cursor,end).getSeconds());days.save(day);cursor=end; }
            }
            u.setPracticeSession(session);u.setPracticeHeartbeat(now);u.setPracticeActive(visible&&idleSeconds<120);
            u.setPracticeActiveUntil(now.plusSeconds(120-idleSeconds));
        }
        long total=days.findByUserIdOrderByDateDesc(u.getId()).stream().mapToLong(PracticeDay::getSeconds).sum();
        long today=days.findByUserIdAndDate(u.getId(),now.atZone(ZONE).toLocalDate()).map(PracticeDay::getSeconds).orElse(0L);
        return new Time(total,today,owner&&visible&&idleSeconds<120);
    }
}
