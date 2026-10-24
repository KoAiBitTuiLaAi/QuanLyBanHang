package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CancelDealerReturnRequest;
import com.quanlybanhang.dto.request.ConfirmDealerReturnRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnRequest;
import com.quanlybanhang.dto.response.DealerReturnOptionsResponse;
import com.quanlybanhang.dto.response.DealerReturnResponse;
import com.quanlybanhang.dto.response.EligibleReturnInvoiceResponse;
import com.quanlybanhang.model.DealerReturnStatus;
import org.springframework.data.domain.Page;

import java.time.LocalDate;

/**
 * Service xử lý nghiệp vụ Lập phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 */
public interface DealerReturnService {

    DealerReturnOptionsResponse getOptions();

    Page<EligibleReturnInvoiceResponse> getEligibleInvoices(Long dealerId, String keyword, int page, int size);

    EligibleReturnInvoiceResponse getEligibleInvoiceById(Long invoiceId);

    DealerReturnResponse createReturn(CreateDealerReturnRequest request, String username);

    DealerReturnResponse confirmReturn(Long id, ConfirmDealerReturnRequest request, String username);

    DealerReturnResponse cancelReturn(Long id, CancelDealerReturnRequest request, String username);

    Page<DealerReturnResponse> search(
            Long dealerId,
            Long invoiceId,
            Long warehouseId,
            DealerReturnStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            int page,
            int size);

    DealerReturnResponse getById(Long id);
}

