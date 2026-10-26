package com.quanlybanhang.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EligibleDealerResponse {
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private String dealerTaxCode;
    private String dealerPhone;
    private String dealerAddress;
    private int uninvoicedDeliveryCount;
}

