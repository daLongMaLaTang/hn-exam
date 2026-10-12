package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.dto.QuestionDtos;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import com.huaneng.shanyin.hnexam.service.QuestionExcelService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
public class QuestionController {
    public record BatchChapterRequest(@NotEmpty @Size(max = 1000) Set<Long> questionIds,
                                      @Size(max = 100) String chapter) {}
    private final QuestionRepository questionRepository;
    private final ObjectMapper mapper;
    private final QuestionExcelService excelService;
    private final com.huaneng.shanyin.hnexam.service.QuestionWordService wordService;
    @PostMapping(value="/import-word",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuestionExcelService.ImportResult importWord(@RequestPart("file") MultipartFile file){return wordService.upload(file);}
    @GetMapping("/word-template")
    public ResponseEntity<byte[]> wordTemplate(){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=questions.docx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).body(wordService.template());}
    @GetMapping public List<QuestionDtos.AdminView> list() { return questionRepository.findAll().stream().filter(q -> !q.isDeleted()).map(QuestionDtos.AdminView::from).toList(); }
    public record DeleteCourse(@jakarta.validation.constraints.NotBlank String courseCode) {}
    @DeleteMapping("/course") @Transactional
    @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')")
    public java.util.Map<String, Integer> deleteCourse(@Valid @RequestBody DeleteCourse request) {
        var bank = questionRepository.findAll().stream().filter(q -> !q.isDeleted() && q.getCourseCode().equals(request.courseCode())).toList();
        if (bank.isEmpty()) throw new IllegalArgumentException("该课程题库不存在或已删除");
        bank.forEach(q -> { q.setDeleted(true); q.setStatus(QuestionStatus.DISABLED); });
        return java.util.Map.of("deleted", bank.size());
    }
    @PostMapping public QuestionDtos.AdminView create(@Valid @RequestBody QuestionDtos.SaveRequest request) {
        Question question = new Question(); apply(question, request); return QuestionDtos.AdminView.from(questionRepository.save(question));
    }
    @PutMapping("/{id}") public QuestionDtos.AdminView update(@PathVariable Long id, @Valid @RequestBody QuestionDtos.SaveRequest request) {
        Question question = questionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("试题不存在"));
        apply(question, request); return QuestionDtos.AdminView.from(questionRepository.save(question));
    }
    @PatchMapping("/{id}/status") public QuestionDtos.AdminView status(@PathVariable Long id, @RequestParam QuestionStatus status) {
        Question question = questionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("试题不存在"));
        if (question.isDeleted()) throw new IllegalStateException("所属课程题库已删除");
        if (status == QuestionStatus.ACTIVE) validateForUse(question.getType(), question.getAnswer(), question.getOptionsJson());
        question.setStatus(status); return QuestionDtos.AdminView.from(questionRepository.save(question));
    }
    @PatchMapping("/batch/chapter") @Transactional
    public List<QuestionDtos.AdminView> batchChapter(@Valid @RequestBody BatchChapterRequest request) {
        if (request.chapter() == null || request.chapter().isBlank()) throw new IllegalArgumentException("请填写章节名称");
        List<Question> selected = questionRepository.findAllById(request.questionIds());
        if (selected.size() != request.questionIds().size()) throw new IllegalArgumentException("包含不存在的试题");
        if (selected.stream().anyMatch(Question::isDeleted)) throw new IllegalStateException("包含已删除题库的试题");
        String chapter = request.chapter().trim();
        selected.forEach(question -> question.setChapter(chapter));
        return questionRepository.saveAll(selected).stream().map(QuestionDtos.AdminView::from).toList();
    }
    @PostMapping(value = "/import-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuestionExcelService.ImportResult importExcel(@RequestPart("file") MultipartFile file) {
        return excelService.importWorkbook(file);
    }
    @GetMapping("/excel-template")
    public ResponseEntity<byte[]> excelTemplate() {
        String filename = URLEncoder.encode("题库导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelService.template());
    }
    private void apply(Question q, QuestionDtos.SaveRequest r) {
        if (q.isDeleted()) throw new IllegalStateException("所属课程题库已删除");
        validateForUse(r.type(), r.answer(), r.optionsJson());
        q.setType(r.type()); q.setStem(r.stem()); q.setAnswer(r.answer()); q.setOptionsJson(r.optionsJson());
        q.setExplanation(r.explanation()); q.setStatus(r.status() == null ? QuestionStatus.PENDING_REVIEW : r.status());
        q.setCourseCode(r.courseCode()); q.setChapter(r.chapter() == null || r.chapter().isBlank() ? "未分章" : r.chapter().trim());
        q.setSource(r.source()); q.setDifficulty(r.difficulty());
    }
    private void validateForUse(QuestionType type, String answer, String optionsJson) {
        if (type == QuestionType.FILL_BLANK) com.huaneng.shanyin.hnexam.service.FillBlankGrading.validateReference(answer);
        if (type == QuestionType.SINGLE_CHOICE || type == QuestionType.MULTIPLE_CHOICE) {
            List<String> options;
            try { options = mapper.readValue(optionsJson, new TypeReference<List<String>>() {}); }
            catch (Exception e) { throw new IllegalArgumentException("选择题选项格式不正确"); }
            if (options == null || options.size() < 2 || options.size() > 12 || options.stream().anyMatch(s -> s == null || s.isBlank())
                    || options.stream().distinct().count() != options.size()) throw new IllegalArgumentException("选择题需2至12个非空且不重复的选项");
            if (type == QuestionType.SINGLE_CHOICE && !options.contains(answer)) throw new IllegalArgumentException("单选题答案必须为一个完整选项");
            if (type == QuestionType.MULTIPLE_CHOICE) {
                var answers = java.util.Arrays.stream(answer.split("[,，;；|]")).map(String::trim).filter(s -> !s.isBlank()).toList();
                if (answers.isEmpty() || answers.stream().anyMatch(s -> !options.contains(s))) throw new IllegalArgumentException("多选题答案须为选项内容，并以逗号分隔");
            }
        }
        if (type == QuestionType.TRUE_FALSE && !java.util.Set.of("正确","错误","对","错","true","false","TRUE","FALSE","√","×").contains(answer.trim()))
            throw new IllegalArgumentException("判断题答案请输入正确或错误");
    }
}
