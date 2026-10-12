package com.huaneng.shanyin.hnexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import com.huaneng.shanyin.hnexam.service.QuestionExcelService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuestionExcelServiceTest {
    @Test
    void generatedTemplateCanBeImportedAndMapsCourseAndAnswer() {
        QuestionRepository repository = mock(QuestionRepository.class);
        when(repository.save(any(Question.class))).thenAnswer(invocation -> invocation.getArgument(0));
        QuestionExcelService service = new QuestionExcelService(repository, new ObjectMapper());
        byte[] template = service.template();

        var result = service.importWorkbook(new MockMultipartFile("file", "题库.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", template));

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.success()).isEqualTo(3);
        var captor = org.mockito.ArgumentCaptor.forClass(Question.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Question::getType)
                .containsExactly(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.FILL_BLANK);
        assertThat(captor.getAllValues().get(0).getCourseCode()).isEqualTo("流体力学");
        assertThat(captor.getAllValues().get(0).getChapter()).isEqualTo("第一章 流体静力学");
        assertThat(captor.getAllValues().get(0).getAnswer()).isEqualTo("垂直作用面");
        assertThat(captor.getAllValues().get(1).getAnswer()).isEqualTo("润滑油位,联轴器防护罩,进出口阀门状态,设备周边环境");
    }

    @Test
    void rejectsNonXlsxFiles() {
        QuestionExcelService service = new QuestionExcelService(mock(QuestionRepository.class), new ObjectMapper());
        var file = new MockMultipartFile("file", "题库.xls", "application/vnd.ms-excel", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.importWorkbook(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
    }
}
