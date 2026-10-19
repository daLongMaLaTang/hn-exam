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

    @Test void restoresMultilineUnmarkedAndGluedShortAnswers() throws Exception {
        Path file=tempDir.resolve("完整简答.docx");
        try(var document=new XWPFDocument()) {
            paragraph(document,"四、简答题");
            paragraph(document,"1、操作清单有哪些？");
            paragraph(document,"答案：（1）核对名称；");
            paragraph(document,"（2）检查位置；");
            paragraph(document,"（3）完成登记。2、有哪些制度？");
            paragraph(document,"工作票制度。");paragraph(document,"工作许可制度。");
            paragraph(document,"3、设备距离表？");
            var table=document.createTable(3,2);
            table.getRow(0).getCell(0).setText("电压等级");table.getRow(0).getCell(1).setText("距离");
            table.getRow(1).getCell(0).setText("20、35");table.getRow(1).getCell(1).setText("1.00");
            table.getRow(2).getCell(0).setText("60、110");table.getRow(2).getCell(1).setText("1.50");
            paragraph(document,"4、其他要求？答：第一项");paragraph(document,"第二项");
            try(var out=Files.newOutputStream(file)){document.write(out);}
        }
        var parsed=new QuestionBankDocxImporter(mock(QuestionRepository.class),new ObjectMapper()).parse(file);
        assertThat(parsed).hasSize(4);
        assertThat(parsed.get(0).getAnswer()).contains("核对名称","检查位置","完成登记").doesNotContain("有哪些制度");
        assertThat(parsed.get(1).getAnswer()).contains("工作票制度","工作许可制度");
        assertThat(parsed.get(2).getAnswer()).contains("20、35","1.00","60、110","1.50");
        assertThat(parsed.get(3).getAnswer()).contains("第一项","第二项");
    }

    @Test void repairOnlyUpdatesUneditedLegacyAnswersAndPreservesDisabledState() throws Exception {
        Path file=tempDir.resolve("修复.docx");
        try(var document=new XWPFDocument()) {
            paragraph(document,"四、简答题");
            for(int i=1;i<=4;i++){paragraph(document,i+"、检查内容？答案：首项");paragraph(document,"续项");}
            try(var out=Files.newOutputStream(file)){document.write(out);}
        }
        var repo=mock(QuestionRepository.class);
        var importer=new QuestionBankDocxImporter(repo,new ObjectMapper());
        var existing=importer.parseLegacy(file);
        existing.get(1).setAnswer("人工修订答案");
        existing.get(2).setDeleted(true);
        existing.get(3).setStatus(com.huaneng.shanyin.hnexam.domain.QuestionStatus.DISABLED);
        org.mockito.Mockito.when(repo.findBySourceStartingWith(org.mockito.ArgumentMatchers.anyString())).thenReturn(existing);
        org.springframework.test.util.ReflectionTestUtils.setField(importer,"configuredPath",file.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(importer,"repairShortAnswers",true);
        importer.importIfNeeded();
        assertThat(existing.get(0).getAnswer()).isEqualTo("首项\n续项");
        assertThat(existing.get(1).getAnswer()).isEqualTo("人工修订答案");
        assertThat(existing.get(2).getAnswer()).isEqualTo("首项");
        assertThat(existing.get(3).getAnswer()).isEqualTo("首项\n续项");
        assertThat(existing.get(3).getStatus()).isEqualTo(com.huaneng.shanyin.hnexam.domain.QuestionStatus.DISABLED);
    }

    @Test void localSourceContainsAll64ShortQuestionsAndCompleteListsWhenAvailable() throws Exception {
        Path source=Path.of("E:/xwechat_files/wxid_rvriaxpboxy022_cc7c/msg/file/2026-09/华能山阴发电有限责任公司安规考试题库.docx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source));
        var parsed=new QuestionBankDocxImporter(mock(QuestionRepository.class),new ObjectMapper()).parse(source).stream().filter(q->q.getType()==QuestionType.SHORT_ANSWER).toList();
        assertThat(parsed).hasSize(64);
        assertThat(parsed.get(5).getAnswer()).contains("（1）","（12）");
        assertThat(parsed.get(32).getAnswer()).contains("（1）","（9）");
        assertThat(parsed.get(14).getAnswer()).doesNotContain("工作班成员的安全责任");
        assertThat(parsed.get(15).getStem()).contains("工作班成员的安全责任");
        assertThat(parsed.get(33).getAnswer()).doesNotContain("五防");
        assertThat(parsed.get(34).getStem()).contains("五防");
        assertThat(parsed.get(53).getAnswer()).contains("停电","验电","接地","遮栏");
    }
}
