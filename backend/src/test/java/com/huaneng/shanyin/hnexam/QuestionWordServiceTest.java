package com.huaneng.shanyin.hnexam;
import com.huaneng.shanyin.hnexam.service.*;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;
class QuestionWordServiceTest {
    @Test void templateImportsAsPendingReviewWithCorrectAnswerMapping() {
        var repository=mock(QuestionRepository.class);when(repository.save(any(Question.class))).thenAnswer(x->x.getArgument(0));
        var service=new QuestionWordService(new QuestionExcelService(repository,new ObjectMapper()));
        var result=service.upload(new MockMultipartFile("file","题库.docx","application/octet-stream",service.template()));
        assertThat(result.success()).isEqualTo(1);assertThat(result.failed()).isZero();
        var captor=org.mockito.ArgumentCaptor.forClass(Question.class);verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAnswer()).isEqualTo("安全帽");assertThat(captor.getValue().getStatus()).isEqualTo(QuestionStatus.PENDING_REVIEW);
    }
    @Test void invalidQuestionIsReportedWithoutSavingAndArbitraryParagraphsAreRejected() throws Exception {
        var repository=mock(QuestionRepository.class);var service=new QuestionWordService(new QuestionExcelService(repository,new ObjectMapper()));
        byte[] bytes;
        try(var doc=new XWPFDocument(new ByteArrayInputStream(service.template()));var out=new ByteArrayOutputStream()) {
            var answer=doc.getTables().get(0).getRow(10).getCell(1);answer.removeParagraph(0);answer.addParagraph().createRun().setText("Z");
            doc.write(out);bytes=out.toByteArray();
        }
        var result=service.upload(new MockMultipartFile("file","题库.docx","application/octet-stream",bytes));
        assertThat(result.failed()).isEqualTo(1);verify(repository,never()).save(any());
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("任意排版");doc.write(out);
            assertThatThrownBy(()->service.upload(new MockMultipartFile("file","题库.docx","application/octet-stream",out.toByteArray()))).hasMessageContaining("模板");}
        assertThatThrownBy(()->service.upload(new MockMultipartFile("file","旧题库.doc","application/msword",new byte[]{1}))).hasMessageContaining(".docx");
    }
}
