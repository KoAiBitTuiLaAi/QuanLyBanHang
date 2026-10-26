package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DealerDebtStatementResponse;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.awt.Color;
import java.io.*;
import java.math.BigDecimal;
import java.text.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class DealerDebtStatementExportService {
    private static final String[] HEADERS={"Ngày phát sinh","Số chứng từ","Diễn giải","Phát sinh tăng","Phát sinh giảm","Số dư lũy kế"};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    public byte[] excel(DealerDebtStatementResponse report) throws IOException {
        SXSSFWorkbook workbook=new SXSSFWorkbook(100);workbook.setCompressTempFiles(true);
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("Chi tiết công nợ");
            var bold=workbook.createFont();bold.setBold(true);
            var header=workbook.createCellStyle();header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var money=workbook.createCellStyle();money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));
            sheet.createRow(0).createCell(0).setCellValue("SỔ CHI TIẾT CÔNG NỢ");
            sheet.createRow(1).createCell(0).setCellValue(report.dealerCode()+" - "+report.dealerName());
            sheet.createRow(2).createCell(0).setCellValue("Từ "+DATE.format(report.from())+" đến "+DATE.format(report.to())+" | Đơn vị: VND");
            var headings=sheet.createRow(4);
            for(int i=0;i<HEADERS.length;i++){var c=headings.createCell(i);c.setCellValue(HEADERS[i]);c.setCellStyle(header);sheet.setColumnWidth(i,(i==2?52:i==1?28:24)*256);}
            int n=5;var opening=sheet.createRow(n++);opening.createCell(2).setCellValue("Số dư đầu kỳ");amount(opening.createCell(5),report.openingBalance(),money);
            for(var entry:report.entries()) {
                var row=sheet.createRow(n++);row.createCell(0).setCellValue(TIME.format(entry.occurredAt()));
                row.createCell(1,CellType.STRING).setCellValue(entry.documentCode());row.createCell(2,CellType.STRING).setCellValue(entry.description());
                amount(row.createCell(3),entry.increase(),money);amount(row.createCell(4),entry.decrease(),money);amount(row.createCell(5),entry.balance(),money);
            }
            var total=sheet.createRow(n);total.createCell(2).setCellValue("Tổng phát sinh / Số dư cuối kỳ");
            amount(total.createCell(3),report.totalIncrease(),money);amount(total.createCell(4),report.totalDecrease(),money);amount(total.createCell(5),report.closingBalance(),money);
            sheet.createFreezePane(0,5);sheet.setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(4,4,-1,-1));
            workbook.write(out);return out.toByteArray();
        }finally{try{workbook.dispose();}finally{workbook.close();}}
    }
    private void amount(org.apache.poi.ss.usermodel.Cell cell,BigDecimal value,CellStyle style) {
        // Excel has only 15 significant decimal digits. Preserve larger financial values as exact text.
        if(value.stripTrailingZeros().precision()>15)cell.setCellValue(value.toPlainString());
        else{cell.setCellValue(value.doubleValue());cell.setCellStyle(style);}
    }
    public byte[] pdf(DealerDebtStatementResponse report) throws Exception {
        byte[] fontBytes;
        try(var input=new ClassPathResource("fonts/arial.ttf").getInputStream()){fontBytes=input.readAllBytes();}
        BaseFont base=BaseFont.createFont("arial.ttf",BaseFont.IDENTITY_H,BaseFont.EMBEDDED,false,fontBytes,null);
        var normal=new com.lowagie.text.Font(base,8);var strong=new com.lowagie.text.Font(base,9,com.lowagie.text.Font.BOLD);
        var title=new com.lowagie.text.Font(base,16,com.lowagie.text.Font.BOLD);
        var format=new DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Document document=new Document(PageSize.A4.rotate(),30,30,30,35);
            PdfWriter writer=PdfWriter.getInstance(document,out);
            writer.setPageEvent(new PdfPageEventHelper(){
                @Override public void onEndPage(PdfWriter w,Document d){
                    ColumnText.showTextAligned(w.getDirectContent(),Element.ALIGN_RIGHT,new Phrase("Trang "+w.getPageNumber(),normal),d.right(),18,0);
                }
            });
            document.open();
            try {
                document.add(new Paragraph("SỔ CHI TIẾT CÔNG NỢ",title));
                document.add(new Paragraph(report.dealerCode()+" - "+report.dealerName(),strong));
                var period=new Paragraph("Từ "+DATE.format(report.from())+" đến "+DATE.format(report.to())+" | Đơn vị: VND",normal);period.setSpacingAfter(10);document.add(period);
                PdfPTable table=new PdfPTable(new float[]{15,21,34,15,15,17});table.setWidthPercentage(100);table.setHeaderRows(1);table.setSplitLate(false);
                for(String h:HEADERS)add(table,h,strong,true,false);
                add(table,"",normal,false,false);add(table,"",normal,false,false);add(table,"Số dư đầu kỳ",strong,false,false);
                add(table,"",normal,false,true);add(table,"",normal,false,true);add(table,format.format(report.openingBalance()),strong,false,true);
                for(var entry:report.entries()) {
                    add(table,TIME.format(entry.occurredAt()),normal,false,false);add(table,entry.documentCode(),normal,false,false);add(table,entry.description(),normal,false,false);
                    add(table,format.format(entry.increase()),normal,false,true);add(table,format.format(entry.decrease()),normal,false,true);add(table,format.format(entry.balance()),normal,false,true);
                }
                add(table,"",normal,false,false);add(table,"",normal,false,false);add(table,"Tổng phát sinh / Số dư cuối kỳ",strong,false,false);
                add(table,format.format(report.totalIncrease()),strong,false,true);add(table,format.format(report.totalDecrease()),strong,false,true);add(table,format.format(report.closingBalance()),strong,false,true);
                document.add(table);
                var note=new Paragraph("Số dư âm thể hiện đại lý đã thanh toán dư. Sổ gồm các chứng từ hiện có trong hệ thống tại thời điểm xuất.",normal);note.setSpacingBefore(10);document.add(note);
            }finally{document.close();}
            return out.toByteArray();
        }
    }
    private void add(PdfPTable table,String value,com.lowagie.text.Font font,boolean header,boolean right) {
        var cell=new PdfPCell(new Phrase(value==null?"":value,font));cell.setPadding(5);cell.setBorderColor(new Color(205,210,215));
        if(header)cell.setBackgroundColor(new Color(233,238,244));if(right)cell.setHorizontalAlignment(Element.ALIGN_RIGHT);table.addCell(cell);
    }
    public String filename(DealerDebtStatementResponse report,String extension) {
        String code=report.dealerCode().replaceAll("[^\\p{L}\\p{N}._-]","_");
        return "So-cong-no_"+code+"_"+report.from()+"_"+report.to()+"."+extension;
    }
}