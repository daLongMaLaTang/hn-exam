package com.huaneng.shanyin.hnexam.service;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;
import java.util.*;

@Service @RequiredArgsConstructor
public class QuestionWordService {
    private final QuestionExcelService excel;
    private static final List<String> FIELDS=List.of("课程","章节","题型","题干","选项A","选项B","选项C","选项D","选项E","选项F","答案","解析","难度","来源");
    public QuestionExcelService.ImportResult upload(MultipartFile file) {
        if(file.isEmpty()||file.getSize()>5*1024*1024||!Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT).endsWith(".docx"))
            throw new IllegalArgumentException("请上传5MB以内的 Word .docx 模板；旧版 .doc 请先另存为 .docx");
        List<Map<String,String>> records=new ArrayList<>();
        try(var doc=new XWPFDocument(file.getInputStream())) {
            if(!doc.getAllPictures().isEmpty())throw new IllegalArgumentException("暂不支持图片题或扫描件，请使用纯文字 Word 模板");
            if(doc.getDocument().xmlText().contains(":oMath"))throw new IllegalArgumentException("暂不支持 Word 公式对象，请转为纯文字后导入");
            if(doc.getTables().isEmpty())throw new IllegalArgumentException("未找到题目表格，请使用系统 Word 模板，每题一个两列表格");
            if(doc.getTables().size()>2000)throw new IllegalArgumentException("每次最多导入2000道题");
            for(var table:doc.getTables()) {
                Map<String,String> record=new LinkedHashMap<>();
                for(var row:table.getRows()) {
                    if(row.getTableCells().size()!=2)throw new IllegalArgumentException("第 "+(records.size()+1)+" 个表格不是两列，请使用模板");
                    String key=row.getCell(0).getText().trim();
                    if(!FIELDS.contains(key))throw new IllegalArgumentException("第 "+(records.size()+1)+" 题包含未知字段："+key);
                    if(record.putIfAbsent(key,row.getCell(1).getText().trim())!=null)throw new IllegalArgumentException("第 "+(records.size()+1)+" 题字段重复："+key);
                }
                record.put("状态","待审核");records.add(record);
            }
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Word 文件无法读取，请使用系统 .docx 模板");}
        return excel.importRecords(records,file.getOriginalFilename());
    }
    public byte[] template() {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var title=doc.createParagraph().createRun();title.setText("题库 Word 导入模板");title.setBold(true);title.setFontSize(20);
            String[] notes={"每道题使用一个两列表格，可复制下方表格添加更多题；请替换示例内容。","课程、题型、题干、答案必填。题型支持：单选题、多选题、判断题、填空题、简答题、计算题、案例题、大题。","选择题答案填 A 或 A,B；判断题填正确/错误；填空题参考答案每空一行。","仅支持纯文字 .docx，不识别扫描件、图片、公式对象或任意排版。单次最多2000题、5MB。导入后均为待审核。"};
            for(String note:notes){var p=doc.createParagraph();p.setSpacingAfter(100);p.createRun().setText(note);}
            var table=doc.createTable(FIELDS.size(),2);table.setWidth("100%");
            var sample=Map.of("课程","安全规程","章节","第一章","题型","单选题","题干","【示例，请替换】进入生产现场应佩戴什么？","选项A","安全帽","选项B","普通帽子","答案","A","难度","2");
            for(int i=0;i<FIELDS.size();i++){table.getRow(i).getCell(0).setText(FIELDS.get(i));table.getRow(i).getCell(0).setColor("EAF2F7");table.getRow(i).getCell(1).setText(sample.getOrDefault(FIELDS.get(i),""));}
            doc.write(out);return out.toByteArray();
        }catch(Exception e){throw new IllegalStateException("Word 模板生成失败");}
    }
}
