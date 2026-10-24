package com.quanlybanhang.service;

import com.quanlybanhang.model.InvoiceSequence;
import com.quanlybanhang.repository.InvoiceSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Service cấp số hóa đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc (S7-01).
 *
 * Tiêu chí:
 * - Dùng row-level lock (PESSIMISTIC_WRITE) trên bảng invoice_sequences.
 * - Chạy trong cùng transaction với nghiệp vụ phát hành hóa đơn (Propagation.MANDATORY).
 * - Nếu transaction bị lỗi hoặc rollback, số thứ tự cũng bị rollback, đảm bảo không có khoảng hở (không nhảy cóc).
 * - Format dạng số 8 chữ số Ả Rập: 00000001, 00000002...
 */
@Service
@RequiredArgsConstructor
public class InvoiceSequenceService {

    public static final String DEFAULT_SEQUENCE_KEY = "SALES_INVOICE";
    public static final String DEFAULT_PREFIX = "HD-";
    public static final int DEFAULT_PADDING_LENGTH = 8;

    private final InvoiceSequenceRepository invoiceSequenceRepository;

    /**
     * Sinh số hóa đơn tiếp theo trong một transaction đang hoạt động.
     *
     * @param sequenceKey khóa dãy số (ví dụ: SALES_INVOICE)
     * @param prefix      tiền tố mã hóa đơn (ví dụ: HD-)
     * @param padding     độ dài chữ số (ví dụ: 8 cho 00000001)
     * @return DTO chứa invoiceNumber và invoiceCode
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NextNumberResult nextSequentialNumber(String sequenceKey, String prefix, int padding) {
        InvoiceSequence sequence = invoiceSequenceRepository.findBySequenceKeyForUpdate(sequenceKey)
                .orElseGet(() -> {
                    InvoiceSequence newSeq = InvoiceSequence.builder()
                            .sequenceKey(sequenceKey)
                            .currentValue(0L)
                            .prefix(prefix)
                            .paddingLength(padding)
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return invoiceSequenceRepository.save(newSeq);
                });

        long nextValue = sequence.getCurrentValue() + 1;
        sequence.setCurrentValue(nextValue);
        sequence.setUpdatedAt(LocalDateTime.now());
        invoiceSequenceRepository.save(sequence);

        String numberStr = String.format("%0" + padding + "d", nextValue);
        String codeStr = (prefix != null ? prefix : "") + numberStr;

        return new NextNumberResult(numberStr, codeStr, nextValue);
    }

    public record NextNumberResult(String invoiceNumber, String invoiceCode, long rawValue) {}
}

