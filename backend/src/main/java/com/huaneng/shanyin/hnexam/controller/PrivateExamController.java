package com.huaneng.shanyin.hnexam.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.ExamRepository;
import com.huaneng.shanyin.hnexam.repository.PersonalExamPaperRepository;
import com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/private-exams")
@RequiredArgsConstructor
public class PrivateExamController {
    private final PrivateAdministratorPolicy privateAdmins;
    private final ExamRepository exams;
    private final PersonalExamPaperRepository papers;
    private final ObjectMapper mapper;

    @GetMapping("/access")
    @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public Map<String,Boolean> access(Principal principal) {
        return Map.of("allowed", privateAdmins.isPrivate(principal.getName()));
    }

    @GetMapping(value="/html", produces="text/html;charset=UTF-8")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN') && @privateAdministratorPolicy.isPrivate(authentication.name)")
    @Transactional(readOnly=true)
    public ResponseEntity<String> html(@RequestParam(required=false) Long examId) {
        var selected = examId == null ? exams.findAll() : java.util.List.of(exams.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("考试不存在")));
        StringBuilder out = new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>已发布考试及答案</title><style>body{font:16px/1.8 sans-serif;max-width:960px;margin:auto;padding:24px;color:#233d50}section{border-top:2px solid #476779;margin-top:30px}article{border-bottom:1px solid #ddd;padding:16px 0;break-inside:avoid}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}img,object{max-width:100%}object{width:100%;height:700px}.answer{background:#eef7f3;padding:12px}</style><body><h1>已发布考试及答案</h1>");
        int count = 0;
        for (var exam : selected) {
            if (exam.getStatus() == ExamStatus.DRAFT) continue;
            count++;
            out.append("<section><h2>").append(escape(exam.getTitle())).append("</h2><p>")
                .append(escape(exam.getCourseCode())).append(" · 总分 ").append(exam.getTotalScore())
                .append(" · 及格分 ").append(exam.getPassScore()).append(" · 时长 ").append(exam.getDurationMinutes()).append(" 分钟</p><pre>")
                .append(escape(exam.getDescription())).append("</pre>");
            if (exam.isWrongQuestionPaper()) {
                for (var paper : papers.findByExamId(exam.getId())) {
                    out.append("<h3>个人试卷 · ").append(escape(paper.getUser().getName())).append(" · ")
                        .append(escape(paper.getUser().getEmployeeNo())).append("</h3>");
                    for (var q : paper.getQuestions()) appendQuestion(out, q.getQuestion(), q.getSnapshotType(), q.getSnapshotStem(), q.getSnapshotOptionsJson(), q.getSnapshotAnswer(), q.getSnapshotExplanation(), q.getPosition(), q.getPoints());
                }
            } else {
                for (var q : exam.getQuestions()) appendQuestion(out, q.getQuestion(), q.getSnapshotType(), q.getSnapshotStem(), q.getSnapshotOptionsJson(), q.getSnapshotAnswer(), q.getSnapshotExplanation(), q.getPosition(), q.getPoints());
            }
            out.append("</section>");
        }
        if (count == 0) out.append("<p>暂无已发布考试。</p>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(out.append("</body></html>").toString());
    }

    private void appendQuestion(StringBuilder out, Question original, QuestionType type, String stem, String options, String answer, String explanation, int position, int points) {
        if (type == null) {
            stem = original.getStem(); options = original.getOptionsJson(); answer = original.getAnswer(); explanation = original.getExplanation();
            out.append("<p>历史试卷未保存题目快照，以下为当前题库内容。</p>");
        }
        out.append("<article><h4>第 ").append(position).append(" 题 · ").append(points).append(" 分</h4><pre>").append(escape(stem)).append("</pre>");
        if (options != null && !options.isBlank()) {
            try {
                var value = mapper.readTree(options);
                if (value.isArray()) {
                    out.append("<ol type='A'>");
                    for (var option : value) out.append("<li>").append(escape(option.asText())).append("</li>");
                    out.append("</ol>");
                } else if (value.has("paper")) {
                    String data = value.get("paper").asText();
                    if (data.matches("data:(application/pdf|image/png|image/jpeg);base64,[A-Za-z0-9+/=]+")) {
                        if (data.startsWith("data:application/pdf")) out.append("<object type='application/pdf' data='").append(data).append("'></object>");
                        else out.append("<img alt='试题附件' src='").append(data).append("'>");
                        out.append("<p><a download='试题附件' href='").append(data).append("'>下载原始附件</a></p>");
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                out.append("<pre>").append(escape(options)).append("</pre>");
            }
        }
        out.append("<div class='answer'><strong>参考答案</strong><pre>").append(escape(answer))
            .append("</pre></div><pre>").append(escape(explanation)).append("</pre></article>");
    }
    private String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
}
