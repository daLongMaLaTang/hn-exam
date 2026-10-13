package com.huaneng.shanyin.hnexam.service;

import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Plaintext is returned only by the operation that creates/resets an account, never persisted. */
@Service
public class InitialCredentials {
    private final SecureRandom random=new SecureRandom();
    public String generate() {
        byte[] bytes=new byte[16]; random.nextBytes(bytes);
        return "A7!"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public record Receipt(Long userId, String employeeNo, String name, String initialPassword) {
        @Override public String toString() { return "Receipt[REDACTED]"; }
    }
    public String workbook(List<Receipt> receipts) {
        if(receipts.isEmpty()) return "";
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("初始密码");
            var text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            String[] headers={"工号","姓名","初始密码","使用说明"};
            var header=sheet.createRow(0);
            for(int i=0;i<headers.length;i++){header.createCell(i).setCellValue(headers[i]);sheet.setDefaultColumnStyle(i,text);sheet.setColumnWidth(i,(i==2?34:26)*256);}
            for(int i=0;i<receipts.size();i++) {
                var r=receipts.get(i);var row=sheet.createRow(i+1);
                String[] values={r.employeeNo(),r.name(),r.initialPassword(),"首次登录必须修改密码"};
                // String cells preserve leading zeros and never execute user input as formulas.
                for(int c=0;c<values.length;c++){var cell=row.createCell(c);cell.setCellStyle(text);cell.setCellValue(values[c]);}
            }
            sheet.createFreezePane(0,1);book.write(out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }catch(Exception e){throw new IllegalStateException("密码清单生成失败，请联系系统管理员重置未领取密码的账号");}
    }
}
