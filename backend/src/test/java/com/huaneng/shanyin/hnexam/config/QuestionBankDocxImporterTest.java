package com.huaneng.shanyin.hnexam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QuestionBankDocxImporterTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesFillSingleAndMultipleChoiceQuestions() throws Exception {
        Path file = tempDir.resolve("题库.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "一、填空题");
            paragraph(document, "1、汽轮机的工作介质是____。答案：蒸汽");
            paragraph(document, "二、选择题");
            paragraph(document, "1、泵启动前应（ ）。A、检查 B、跳过检查 C、直接启动 答案：A");
            paragraph(document, "2、下列属于检查项目的是（ ）。A、油位 B、振动 C、温度 D、颜色 答案：A、B、C");
            paragraph(document, "3、应同时选择（ ）。A、安全 B、规范 C、快速 D、随意 答案：(BD)");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }

        QuestionBankDocxImporter importer = new QuestionBankDocxImporter(mock(QuestionRepository.class), new ObjectMapper());
        List<Question> questions = importer.parse(file);

        assertThat(questions).extracting(Question::getType)
                .containsExactly(QuestionType.FILL_BLANK, QuestionType.SINGLE_CHOICE,
                        QuestionType.MULTIPLE_CHOICE, QuestionType.MULTIPLE_CHOICE);
        assertThat(questions.get(2).getAnswer()).isEqualTo("油位,振动,温度");
        assertThat(questions.get(3).getAnswer()).isEqualTo("规范,随意");
    }

    @Test
    void preservesWordLineBreakSoTheFollowingQuestionIsNotLost() throws Exception {
        Path file = tempDir.resolve("换行题库.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "二、选择题");
            var paragraph = document.createParagraph();
            var run = paragraph.createRun();
            run.setText("1、第一题。A、甲 B、乙 答案：A");
            run.addBreak();
            run.setText("2、第二题。A、丙 B、丁 答案：B");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }

        QuestionBankDocxImporter importer = new QuestionBankDocxImporter(mock(QuestionRepository.class), new ObjectMapper());
        List<Question> questions = importer.parse(file);

        assertThat(questions).hasSize(2);
        assertThat(questions).extracting(Question::getAnswer).containsExactly("甲", "丁");
    }

    @Test
    void recognizesDedicatedMultipleChoiceSection() throws Exception {
        Path file = tempDir.resolve("多选题库.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "三、多项选择题");
            paragraph(document, "1、运行检查包括哪些项目？A、油位 B、振动 C、温度 D、颜色 答案：A,C");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }

        QuestionBankDocxImporter importer = new QuestionBankDocxImporter(mock(QuestionRepository.class), new ObjectMapper());
        List<Question> questions = importer.parse(file);

        assertThat(questions).singleElement().satisfies(question -> {
            assertThat(question.getType()).isEqualTo(QuestionType.MULTIPLE_CHOICE);
            assertThat(question.getAnswer()).isEqualTo("油位,温度");
        });
    }

    private void paragraph(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }
}
