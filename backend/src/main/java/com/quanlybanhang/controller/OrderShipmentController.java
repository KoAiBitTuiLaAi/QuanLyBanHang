package com.quanlybanhang.controller;

import com.quanlybanhang.dto.OrderShipmentDtos.Confirm;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.OrderShipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/warehouse/order-shipments") @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW','WAREHOUSE_EXPORT')")
public class OrderShipmentController {
    private final OrderShipmentService service;
    @GetMapping("/options") public ApiResponse options(){return ApiResponse.success("Bộ chọn xuất theo đơn",service.options());}
    @GetMapping public ApiResponse list(@RequestParam(required=false) Long warehouseId,
            @RequestParam(defaultValue="READY") String queue,@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success("Đơn xuất kho",service.search(warehouseId,queue,keyword,page,size));
    }
    @GetMapping("/{id}") public ApiResponse get(@PathVariable Long id){return ApiResponse.success("Chi tiết xuất kho",service.get(id));}
    @PostMapping("/{id}/confirm") @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse confirm(@PathVariable Long id,@Valid @RequestBody Confirm r){return ApiResponse.success("Đã xác nhận phiếu xuất",service.confirm(id,r));}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ApiResponse> status(org.springframework.web.server.ResponseStatusException ex){
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.error(ex.getReason()));
    }
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,org.springframework.dao.OptimisticLockingFailureException.class})
    public org.springframework.http.ResponseEntity<ApiResponse> conflict(){return org.springframework.http.ResponseEntity.status(409).body(ApiResponse.error("Dữ liệu vừa thay đổi. Tải lại để đối chiếu; không gửi lại phiếu mới."));}
}
