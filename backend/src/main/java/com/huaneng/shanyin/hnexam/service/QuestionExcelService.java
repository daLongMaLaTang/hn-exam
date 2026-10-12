package com.huaneng.shanyin.hnexam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QuestionExcelService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final List<String> HEADERS = List.of("课程", "章节", "题型", "题干", "选项A", "选项B", "选项C", "选项D", "选项E", "选项F", "答案", "解析", "难度", "状态", "来源");
    private final QuestionRepository questions;
    private final ObjectMapper mapper;

    public record ImportFailure(int row, String reason) {}
    public record ImportResult(int total, int success, int failed, List<ImportFailure> failures) {}

    // Word templates use the same validation and answer normalization as Excel.
    public ImportResult importRecords(List<Map<String,String>> records, String filename) {
        if (records.isEmpty() || records.size()>MAX_ROWS) throw new IllegalArgumentException("请提供1至2000道题目");
        List<ImportFailure> failures=new ArrayList<>(); int success=0;
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet(); var columns=new HashMap<String,Integer>();
            for(int i=0;i<HEADERS.size();i++)columns.put(HEADERS.get(i),i);
            for(int i=0;i<records.size();i++) {
                var row=sheet.createRow(i);
                for(var entry:columns.entrySet())row.createCell(entry.getValue()).setCellValue(records.get(i).getOrDefault(entry.getKey(),""));
                try { questions.save(parseRow(row,columns,new DataFormatter(Locale.CHINA),filename));success++; }
                catch(Exception e){failures.add(new ImportFailure(i+1,readable(e)));}
            }
        }catch(java.io.IOException e){throw new IllegalStateException("Word 题库处理失败");}
        return new ImportResult(records.size(),success,records.size()-success,failures);
    }

    public ImportResult importWorkbook(MultipartFile file) {
        validateFile(file);
        List<ImportFailure> failures = new ArrayList<>();
        int total = 0;
        int success = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("题库导入");
            if (sheet == null) sheet = workbook.getSheetAt(0);
            Map<String, Integer> columns = readHeaders(sheet.getRow(sheet.getFirstRowNum()));
            requireHeaders(columns);
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int lastRow = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + MAX_ROWS);
            for (int rowNumber = sheet.getFirstRowNum() + 1; rowNumber <= lastRow; rowNumber++) {
                Row row = sheet.getRow(rowNumber);
                if (row == null || isBlank(row, formatter)) continue;
                total++;
                try {
                    Question question = parseRow(row, columns, formatter, file.getOriginalFilename());
                    questions.save(question);
                    success++;
                } catch (Exception exception) {
                    failures.add(new ImportFailure(rowNumber + 1, readable(exception)));
                }
            }
            if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) {
                failures.add(new ImportFailure(MAX_ROWS + 2, "单次最多导入 2000 道题，其余行未处理"));
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 文件无法读取，请使用系统提供的 .xlsx 模板");
        }
        if (total == 0) throw new IllegalArgumentException("Excel 中没有可导入的题目");
        return new ImportResult(total, success, total - success, failures.stream().limit(100).toList());
    }

    public byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("题库导入");
            Sheet help = workbook.createSheet("填写说明");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont); header.setAlignment(HorizontalAlignment.CENTER);
            Row title = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) { Cell cell = title.createCell(index); cell.setCellValue(HEADERS.get(index)); cell.setCellStyle(header); }
            List<Object[]> examples = List.of(
                    new Object[]{"流体力学", "第一章 流体静力学", "单选题", "流体静压强的方向是？", "垂直作用面", "平行作用面", "沿流动方向", "不确定", "", "", "A", "静止流体的压强方向垂直于作用面。", 2, "待审核", "培训教材"},
                    new Object[]{"泵与风机", "第二章 泵的运行", "多选题", "泵启动前需要检查哪些项目？", "润滑油位", "联轴器防护罩", "进出口阀门状态", "设备周边环境", "", "", "A,B,C,D", "按运行规程逐项完成启动前检查。", 2, "待审核", "培训教材"},
                    new Object[]{"安全规程", "第一章 通用规定", "填空题", "进入生产现场必须正确佩戴____。", "", "", "", "", "", "", "安全帽", "填写标准答案或可接受答案。", 1, "待审核", "培训教材"}
            );
            for (int rowIndex = 0; rowIndex < examples.size(); rowIndex++) {
                Row sample = sheet.createRow(rowIndex + 1);
                Object[] example = examples.get(rowIndex);
                for (int index = 0; index < example.length; index++) { if (example[index] instanceof Number n) sample.createCell(index).setCellValue(n.doubleValue()); else sample.createCell(index).setCellValue(String.valueOf(example[index])); }
            }
            int[] widths = {16,24,14,52,24,24,24,24,24,24,18,42,10,12,20};
            for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
            sheet.createFreezePane(0, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, HEADERS.size() - 1));
            addListValidation(sheet, 1, 2000, 2, new String[]{"单选题", "多选题", "判断题", "填空题", "简答题", "计算题", "案例题", "大题"});
            addListValidation(sheet, 1, 2000, 13, new String[]{"待审核", "已启用"});
            Row helpTitle = help.createRow(0); helpTitle.createCell(0).setCellValue("题库 Excel 导入说明"); helpTitle.getCell(0).setCellStyle(header);
            String[] notes = {"1. 课程和章节必填，例如：流体力学 / 第一章 流体静力学。系统会按课程和章节分类题库。", "2. 题型支持：单选题、多选题、判断题、填空题、简答题、计算题、案例题、大题。", "3. 选择题至少填写选项A、选项B；答案可填写 A 或完整选项文本。", "4. 多选题答案填写 A,B,C 或用逗号分隔完整选项文本。", "5. 判断题答案填写：正确/错误。", "6. 主观题的答案列填写参考答案或评分要点，考试交卷后由超级管理员人工判卷。", "7. 难度为 1～5；状态为空时默认为待审核。", "8. 单次最多 2000 道、文件最大 5MB。错误行会单独提示，正确行仍会导入。"};
            for (int index = 0; index < notes.length; index++) help.createRow(index + 2).createCell(0).setCellValue(notes[index]);
            help.setColumnWidth(0, 100 * 256); workbook.write(output); return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException("Excel 模板生成失败"); }
    }

    private Question parseRow(Row row, Map<String, Integer> columns, DataFormatter formatter, String filename) throws Exception {
        String course = value(row, columns, formatter, "课程");
        String chapter = value(row, columns, formatter, "章节");
        String typeText = value(row, columns, formatter, "题型");
        String stem = value(row, columns, formatter, "题干");
        String answer = value(row, columns, formatter, "答案");
        if (course.isBlank() || typeText.isBlank() || stem.isBlank() || answer.isBlank()) throw new IllegalArgumentException("课程、题型、题干和答案均为必填项");
        if (course.length() > 64) throw new IllegalArgumentException("课程名称不能超过 64 个字");
        QuestionType type = parseType(typeText);
        List<String> options = new ArrayList<>();
        for (String name : List.of("选项A", "选项B", "选项C", "选项D", "选项E", "选项F")) { String option = value(row, columns, formatter, name); if (!option.isBlank()) options.add(option); }
        if (type == QuestionType.SINGLE_CHOICE || type == QuestionType.MULTIPLE_CHOICE) {
            if (options.size() < 2) throw new IllegalArgumentException("选择题至少需要选项A和选项B");
            answer = normalizeChoiceAnswer(answer, options, type == QuestionType.MULTIPLE_CHOICE);
        }
        if (type == QuestionType.TRUE_FALSE) answer = normalizeTrueFalse(answer);
        if (type == QuestionType.FILL_BLANK) FillBlankGrading.validateReference(answer);
        int difficulty = parseDifficulty(value(row, columns, formatter, "难度"));
        Question question = new Question();
        question.setCourseCode(course.trim()); question.setChapter(chapter.isBlank() ? "未分章" : chapter.trim()); question.setType(type); question.setStem(stem.trim()); question.setAnswer(answer);
        question.setOptionsJson(options.isEmpty() ? null : mapper.writeValueAsString(options));
        question.setExplanation(value(row, columns, formatter, "解析")); question.setDifficulty(difficulty);
        question.setStatus(parseStatus(value(row, columns, formatter, "状态")));
        String source = value(row, columns, formatter, "来源"); if (source.length() > 200) throw new IllegalArgumentException("来源不能超过 200 个字"); question.setSource(source.isBlank() ? filename : source);
        return question;
    }

    private String normalizeChoiceAnswer(String raw, List<String> options, boolean multiple) {
        List<String> parts = Arrays.stream(raw.split("[,，;；|、\\s]+")) .map(String::trim).filter(s -> !s.isBlank()).toList();
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.matches("(?i)[A-F]")) { int index = Character.toUpperCase(part.charAt(0)) - 'A'; if (index >= options.size()) throw new IllegalArgumentException("答案引用了不存在的选项 " + part); normalized.add(options.get(index)); }
            else if (options.contains(part)) normalized.add(part); else throw new IllegalArgumentException("答案必须填写选项字母或完整选项文本");
        }
        if (!multiple && normalized.size() != 1) throw new IllegalArgumentException("单选题只能有一个答案");
        LinkedHashSet<String> unique = new LinkedHashSet<>(normalized);
        if (multiple && unique.size() < 2) throw new IllegalArgumentException("多选题至少需要两个不同答案");
        return String.join(",", unique);
    }

    private String normalizeTrueFalse(String value) {
        if (Set.of("正确", "对", "TRUE", "√").contains(value.trim().toUpperCase(Locale.ROOT))) return "正确";
        if (Set.of("错误", "错", "FALSE", "×").contains(value.trim().toUpperCase(Locale.ROOT))) return "错误";
        throw new IllegalArgumentException("判断题答案只能填写正确或错误");
    }
    private QuestionType parseType(String value) { return switch (value.trim().toUpperCase(Locale.ROOT)) { case "单选题", "单选", "SINGLE_CHOICE" -> QuestionType.SINGLE_CHOICE; case "多选题", "多选", "MULTIPLE_CHOICE" -> QuestionType.MULTIPLE_CHOICE; case "判断题", "判断", "TRUE_FALSE" -> QuestionType.TRUE_FALSE; case "填空题", "填空", "FILL_BLANK" -> QuestionType.FILL_BLANK; case "简答题", "简答", "SHORT_ANSWER" -> QuestionType.SHORT_ANSWER; case "计算题", "计算", "CALCULATION" -> QuestionType.CALCULATION; case "案例题", "案例分析题", "IMAGE_CASE" -> QuestionType.IMAGE_CASE; case "大题", "论述题", "综合题", "ESSAY" -> QuestionType.ESSAY; default -> throw new IllegalArgumentException("不支持的题型：" + value); }; }
    private QuestionStatus parseStatus(String value) { return switch (value.trim().toUpperCase(Locale.ROOT)) { case "", "待审核", "PENDING_REVIEW" -> QuestionStatus.PENDING_REVIEW; case "已启用", "启用", "ACTIVE" -> QuestionStatus.ACTIVE; default -> throw new IllegalArgumentException("状态只能填写待审核或已启用"); }; }
    private int parseDifficulty(String value) { if (value.isBlank()) return 2; try { int result = Integer.parseInt(value.replace(".0", "")); if (result < 1 || result > 5) throw new NumberFormatException(); return result; } catch (NumberFormatException exception) { throw new IllegalArgumentException("难度必须是 1～5 的整数"); } }
    private Map<String, Integer> readHeaders(Row row) { if (row == null) throw new IllegalArgumentException("Excel 第一行必须是字段名称"); Map<String, Integer> result = new HashMap<>(); for (Cell cell : row) result.put(cell.getStringCellValue().trim(), cell.getColumnIndex()); return result; }
    private void requireHeaders(Map<String, Integer> columns) { for (String name : List.of("课程", "题型", "题干", "答案")) if (!columns.containsKey(name)) throw new IllegalArgumentException("Excel 缺少“" + name + "”列，请使用系统模板"); }
    private String value(Row row, Map<String, Integer> columns, DataFormatter formatter, String name) { Integer index = columns.get(name); if (index == null) return ""; Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL); return cell == null ? "" : formatter.formatCellValue(cell).trim(); }
    private boolean isBlank(Row row, DataFormatter formatter) { for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) return false; return true; }
    private String readable(Exception exception) { String message = exception.getMessage(); return message == null || message.isBlank() ? "数据格式不正确" : message; }
    private void validateFile(MultipartFile file) { if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件"); String name = Optional.ofNullable(file.getOriginalFilename()).orElse(""); if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("仅支持 .xlsx 格式，请使用系统模板"); if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("Excel 文件不能超过 5MB"); }
    private void addListValidation(Sheet sheet, int firstRow, int lastRow, int column, String[] values) { DataValidationHelper helper = sheet.getDataValidationHelper(); DataValidationConstraint constraint = helper.createExplicitListConstraint(values); DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(firstRow, lastRow, column, column)); validation.setShowErrorBox(true); sheet.addValidationData(validation); }
}
