package com.huaneng.shanyin.hnexam.controller;
import com.huaneng.shanyin.hnexam.service.ProfileExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/profiles") @RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')")
public class ProfileImportController {
    private final ProfileExcelService excel;
    @PostMapping(value="/import-excel",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileExcelService.Result upload(@RequestPart("file") MultipartFile file){return excel.importWorkbook(file);}
    @GetMapping("/excel-template")
    public ResponseEntity<byte[]> template(){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=profiles.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(excel.template());}
}
