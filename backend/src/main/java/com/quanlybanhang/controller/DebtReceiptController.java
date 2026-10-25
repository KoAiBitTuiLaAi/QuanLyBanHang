package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/finance/debt-payments/{id}/receipt")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
public class DebtReceiptController {
    private final DebtReceiptService receipts;
    private final DebtReceiptRenderer renderer;

    @GetMapping
    public ResponseEntity<ApiResponse> receipt(@PathVariable Long id){
        return headers().body(ApiResponse.success("Phiếu thu từ khoản đã ghi nhận",receipts.receipt(id)));
    }
    @GetMapping(value="/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id,@RequestParam(defaultValue="true") boolean download) throws Exception {
        var receipt=receipts.receipt(id);
        return headers().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,
                (download?ContentDisposition.attachment():ContentDisposition.inline()).filename(filename(receipt.getPaymentCode(),"pdf"),StandardCharsets.UTF_8).build().toString())
                .body(renderer.pdf(receipt));
    }
    @GetMapping(value="/print",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> print(@PathVariable Long id){
        var receipt=receipts.receipt(id);
        return headers().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(filename(receipt.getPaymentCode(),"html"),StandardCharsets.UTF_8).build().toString())
                .body(renderer.html(receipt));
    }
    private ResponseEntity.BodyBuilder headers(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").header("X-Content-Type-Options","nosniff");}
    private String filename(String code,String extension){return "phieu-thu-"+code.replaceAll("[^A-Za-z0-9._-]","_")+"."+extension;}
}
