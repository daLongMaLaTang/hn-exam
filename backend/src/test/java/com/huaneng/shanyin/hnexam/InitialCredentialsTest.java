package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.service.InitialCredentials;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InitialCredentialsTest {
    @Test void generatedCredentialsMeetPolicyAndDoNotRepeat() {
        var service=new InitialCredentials();var seen=new HashSet<String>();
        for(int i=0;i<1000;i++){
            var password=service.generate();assertThat(password).matches("(?=.*[A-Za-z])(?=.*[0-9]).{25}");
            assertThat(seen.add(password)).isTrue();
        }
    }
    @Test void workbookPreservesIdentifiersAndTreatsFormulaTextAsLiteral() throws Exception {
        var service=new InitialCredentials();
        var receipt=new InitialCredentials.Receipt(1L,"00123","=HYPERLINK(\"bad\")","A7!test-only-value");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(service.workbook(List.of(receipt)))))) {
            var row=book.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("00123");
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo(receipt.name());
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo(receipt.initialPassword());
        }
        assertThat(receipt.toString()).doesNotContain(receipt.initialPassword());
        assertThat(service.workbook(List.of())).isEmpty();
    }
}
