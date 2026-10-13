package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;

@Service @RequiredArgsConstructor
public class ProfileExcelService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final InitialCredentials credentials;
    private static final List<String> HEADERS = List.of("工号", "姓名", "手机号", "部门", "班组", "专业方向", "所学专业", "岗位", "培训开始日期");
    private static final int[] LIMITS = {32,80,32,100,100,100,100,100,10};
    public record Failure(int row, String reason) {}
    public record Result(int total, int created, int updated, int failed, List<Failure> failures, List<InitialCredentials.Receipt> credentials, String credentialFile) {
        @Override public String toString() { return "ImportResult[total="+total+",created="+created+",updated="+updated+",failed="+failed+",credentials=REDACTED]"; }
    }
    private record RowResult(InitialCredentials.Receipt credential) {}

    public Result importWorkbook(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024 || !Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new IllegalArgumentException("请上传5MB以内的 .xlsx 档案模板");
        int total=0, created=0, updated=0; List<Failure> failures=new ArrayList<>(); Set<String> seen=new HashSet<>();
        List<InitialCredentials.Receipt> issued=new ArrayList<>();
        try (var book = new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets()==0) throw new IllegalArgumentException("Excel 没有工作表");
            var sheet=book.getSheetAt(0); var formatter=new DataFormatter(Locale.CHINA); var header=sheet.getRow(0);
            Map<String,Integer> columns=new HashMap<>();
            if (header!=null) for (var c:header) columns.put(formatter.formatCellValue(c).trim(),c.getColumnIndex());
            if (!columns.keySet().containsAll(HEADERS)) throw new IllegalArgumentException("缺少档案字段，请下载系统模板填写");
            if (sheet.getLastRowNum()>2000) throw new IllegalArgumentException("每次最多导入2000行，请拆分文件");
            for (int rowIndex=1;rowIndex<=sheet.getLastRowNum();rowIndex++) {
                var row=sheet.getRow(rowIndex); if(row==null) continue;
                String[] values=HEADERS.stream().map(h->formatter.formatCellValue(row.getCell(columns.get(h))).trim()).toArray(String[]::new);
                if(Arrays.stream(values).allMatch(String::isBlank)) continue; total++;
                try {
                    for (int c=0;c<values.length;c++) if(values[c].length()>LIMITS[c]) throw new IllegalArgumentException(HEADERS.get(c)+"超出长度限制");
                    if(values[0].isBlank() || values[1].isBlank()) throw new IllegalArgumentException("工号、姓名必填");
                    if(values[0].equalsIgnoreCase("admin")) throw new IllegalArgumentException("不能通过导入修改 admin");
                    if(!seen.add(values[0])) throw new IllegalArgumentException("同一文件工号重复");
                    LocalDate date=values[8].isBlank()?null:LocalDate.parse(values[8]);
                    RowResult saved=transactions.execute(status->{
                        var user=users.findByEmployeeNo(values[0]).orElseGet(AppUser::new); boolean isNew=user.getId()==null;
                        if(!isNew && (user.getRoles().isEmpty() || user.getRoles().stream().anyMatch(r->!r.effectiveAuthority().equals("CANDIDATE"))))
                            throw new IllegalArgumentException("仅支持普通学员档案，管理账号请单独维护");
                        String password=isNew?credentials.generate():null;
                        if(isNew) { user.setEmployeeNo(values[0]); user.setPasswordHash(passwords.encode(password)); user.setMustChangePassword(true); user.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow()); }
                        user.setName(values[1]);
                        if(!values[2].isBlank())user.setPhone(values[2]); if(!values[3].isBlank())user.setDepartment(values[3]);
                        if(!values[4].isBlank())user.setTeam(values[4]); if(!values[5].isBlank())user.setProfessionalDirection(values[5]);
                        if(!values[6].isBlank())user.setEducationMajor(values[6]); if(!values[7].isBlank())user.setPosition(values[7]);
                        if(date!=null)user.setTrainingStartDate(date); users.saveAndFlush(user);
                        return new RowResult(isNew?new InitialCredentials.Receipt(user.getId(),user.getEmployeeNo(),user.getName(),password):null);
                    });
                    // Publish a receipt only after this row's transaction has committed.
                    if(saved.credential()!=null){created++;issued.add(saved.credential());}else updated++;
                } catch(java.time.format.DateTimeParseException e) { failures.add(new Failure(rowIndex+1,"日期请填写 yyyy-MM-dd，例如2026-10-08")); }
                catch(IllegalArgumentException e) { failures.add(new Failure(rowIndex+1,e.getMessage())); }
                catch(Exception e) { failures.add(new Failure(rowIndex+1,"该行保存失败，请检查工号是否重复后重试")); }
            }
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("无法读取 Excel，请使用系统 .xlsx 模板"); }
        if(total==0)throw new IllegalArgumentException("文件中没有学员资料");
        return new Result(total,created,updated,failures.size(),failures,List.copyOf(issued),credentials.workbook(issued));
    }

    public byte[] template() {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("学员档案");var header=sheet.createRow(0);
            var text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            for(int i=0;i<HEADERS.size();i++){header.createCell(i).setCellValue(HEADERS.get(i));sheet.setColumnWidth(i,22*256);sheet.setDefaultColumnStyle(i,text);}
            sheet.createFreezePane(0,1);
            var help=book.createSheet("填写说明");
            String[] notes={"工号、姓名必填；请在第一张表从第2行开始填写，不要修改表头。","工号作为唯一标识：不存在则创建普通学员，已存在则更新基本档案。","空白选填单元格保留原值；不会修改已有账号密码、权限、账号状态及考试记录。","专业方向用于按专业发布考试；培训开始日期格式为 yyyy-MM-dd。","新建账号使用独立随机初始密码，首次登录须修改；不支持导入管理账号。","导入完成后请立即下载新增账号密码清单，并分别交给对应学员；关闭或刷新页面后无法再次查看。","更新已有学员不会生成密码清单；密码遗失请联系系统管理员重置，重复导入不会重置密码。","单次最多2000行、5MB；成功行保存，失败行会显示行号，修正后可重新导入。"};
            for(int i=0;i<notes.length;i++)help.createRow(i).createCell(0).setCellValue(notes[i]);help.setColumnWidth(0,110*256);
            book.write(out);return out.toByteArray();
        }catch(Exception e){throw new IllegalStateException("模板生成失败");}
    }
}
