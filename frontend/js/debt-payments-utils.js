/**
 * debt-payments-utils.js
 * Tiện ích và hàm kiểm tra hợp lệ cho chức năng Ghi nhận thanh toán và đối trừ công nợ (S7-02, S7-09).
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory();
  } else {
    root.DebtPaymentsUtils = factory();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /**
   * Định dạng tiền tệ VND chuẩn Việt Nam.
   * Ví dụ: 1500000 -> "1.500.000 ₫"
   */
  function formatMoney(amount) {
    if (amount === null || amount === undefined || amount === '') {
      return '—';
    }
    const num = Number(amount);
    if (!Number.isFinite(num)) {
      return '—';
    }
    const parts = Math.round(num).toString().split('.');
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return parts.join('.') + ' ₫';
  }

  /**
   * Phân tích chuỗi số tiền người dùng nhập thành số thực.
   */
  function parseMoney(val) {
    if (val === null || val === undefined) return 0;
    if (typeof val === 'number') return Number.isFinite(val) ? val : 0;
    const cleaned = String(val).replace(/\./g, '').replace(/,/g, '.').replace(/[^\d.-]/g, '').trim();
    const num = parseFloat(cleaned);
    return Number.isFinite(num) ? num : 0;
  }

  /**
   * Định dạng ngày ISO YYYY-MM-DD -> DD/MM/YYYY
   */
  function formatDate(isoDate) {
    if (!isoDate) return '—';
    try {
      const parts = String(isoDate).split('T')[0].split('-');
      if (parts.length === 3) {
        return `${parts[2]}/${parts[1]}/${parts[0]}`;
      }
      return isoDate;
    } catch {
      return '—';
    }
  }

  /**
   * Định dạng ngày giờ ISO YYYY-MM-DDTHH:mm:ss -> DD/MM/YYYY HH:mm
   */
  function formatDateTime(isoDateTime) {
    if (!isoDateTime) return '—';
    try {
      const dt = String(isoDateTime).split('T');
      const dParts = dt[0].split('-');
      const dateStr = dParts.length === 3 ? `${dParts[2]}/${dParts[1]}/${dParts[0]}` : dt[0];
      if (dt.length > 1) {
        const timeParts = dt[1].split(':');
        return `${dateStr} ${timeParts[0]}:${timeParts[1]}`;
      }
      return dateStr;
    } catch {
      return '—';
    }
  }

  /**
   * Thông tin hiển thị cho hình thức thu tiền.
   */
  function getPaymentMethodInfo(method) {
    switch (method) {
      case 'TIEN_MAT':
        return { label: 'Tiền mặt', badgeClass: 'badge--cash' };
      case 'CHUYEN_KHOAN':
      case 'BANK_TRANSFER':
        return { label: 'Chuyển khoản', badgeClass: 'badge--transfer' };
      case 'KHAC':
      default:
        return { label: method || 'Khác', badgeClass: 'badge--other' };
    }
  }

  /**
   * Thông tin hiển thị cho loại phiếu thu.
   */
  function getPaymentTypeInfo(type) {
    if (type === 'ADJUSTMENT') {
      return { label: 'Bút toán điều chỉnh', badgeClass: 'badge--adjustment' };
    }
    return { label: 'Phiếu thu', badgeClass: 'badge--payment' };
  }

  /**
   * Thông tin hiển thị cho trạng thái thanh toán hoá đơn.
   */
  function getInvoicePaymentStatusInfo(status) {
    switch (status) {
      case 'PAID':
        return { label: 'Đã thanh toán', badgeClass: 'badge--paid' };
      case 'PARTIALLY_PAID':
        return { label: 'Thanh toán một phần', badgeClass: 'badge--partial' };
      case 'UNPAID':
      default:
        return { label: 'Chưa thanh toán', badgeClass: 'badge--unpaid' };
    }
  }

  /**
   * Kiểm tra hợp lệ dữ liệu ghi nhận thanh toán (S7-02).
   */
  function validatePaymentPayload(payload, currentRemainingDebt) {
    const errors = [];

    if (!payload || typeof payload !== 'object') {
      return { isValid: false, errors: ['Dữ liệu phiếu thu không hợp lệ.'] };
    }

    if (!payload.dealerId) {
      errors.push('Vui lòng chọn đại lý thanh toán.');
    }

    const amount = Number(payload.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      errors.push('Số tiền thu phải lớn hơn 0.');
    } else if (currentRemainingDebt !== null && currentRemainingDebt !== undefined) {
      const maxDebt = Number(currentRemainingDebt);
      if (Number.isFinite(maxDebt) && amount > maxDebt) {
        errors.push(`Số tiền thu (${formatMoney(amount)}) không được vượt quá tổng công nợ còn lại của đại lý (${formatMoney(maxDebt)}).`);
      }
    }

    if (!payload.paymentMethod || !['TIEN_MAT', 'CHUYEN_KHOAN', 'KHAC'].includes(payload.paymentMethod)) {
      errors.push('Vui lòng chọn hình thức thu tiền hợp lệ (Tiền mặt, Chuyển khoản hoặc Khác).');
    }

    if (!payload.paymentDate) {
      errors.push('Vui lòng chọn ngày thu tiền.');
    } else if (!/^\d{4}-\d{2}-\d{2}$/.test(payload.paymentDate)) {
      errors.push('Ngày thu tiền phải có định dạng YYYY-MM-DD.');
    }

    if (payload.note && String(payload.note).length > 500) {
      errors.push('Ghi chú không được vượt quá 500 ký tự.');
    }

    if (payload.referenceCode && String(payload.referenceCode).length > 100) {
      errors.push('Mã tham chiếu không được vượt quá 100 ký tự.');
    }

    if (payload.recordedBy && String(payload.recordedBy).length > 100) {
      errors.push('Tên người thu không được vượt quá 100 ký tự.');
    }

    // Nếu chọn đối trừ cụ thể (autoAllocate === false)
    if (payload.autoAllocate === false) {
      if (!Array.isArray(payload.allocations) || payload.allocations.length === 0) {
        errors.push('Vui lòng chỉ định ít nhất một hoá đơn để đối trừ khi tắt tự động FIFO.');
      } else {
        let totalAllocated = 0;
        for (let i = 0; i < payload.allocations.length; i++) {
          const item = payload.allocations[i];
          if (!item.orderId) {
            errors.push(`Dòng ${i + 1}: Thiếu mã hoá đơn/đơn hàng đối trừ.`);
          }
          const itemAmount = Number(item.amount);
          if (!Number.isFinite(itemAmount) || itemAmount <= 0) {
            errors.push(`Dòng ${i + 1}: Số tiền đối trừ phải lớn hơn 0.`);
          } else {
            if (item.orderRemainingAmount !== undefined && item.orderRemainingAmount !== null) {
              const maxItemDebt = Number(item.orderRemainingAmount);
              if (Number.isFinite(maxItemDebt) && itemAmount > maxItemDebt) {
                errors.push(`Dòng ${i + 1} (${item.orderCode || 'HĐ'}): Số tiền đối trừ (${formatMoney(itemAmount)}) vượt quá số nợ còn lại của hoá đơn (${formatMoney(maxItemDebt)}).`);
              }
            }
            totalAllocated += itemAmount;
          }
        }

        // Kiểm tra tổng tiền đối trừ phải khớp với số tiền phiếu thu
        if (Number.isFinite(amount) && amount > 0) {
          if (Math.abs(totalAllocated - amount) > 0.01) {
            errors.push(`Tổng số tiền đối trừ các hoá đơn (${formatMoney(totalAllocated)}) phải bằng chính xác số tiền phiếu thu (${formatMoney(amount)}). Chênh lệch: ${formatMoney(Math.abs(totalAllocated - amount))}.`);
          }
        }
      }
    }

    return {
      isValid: errors.length === 0,
      errors: errors
    };
  }

  /**
   * Kiểm tra hợp lệ dữ liệu lập bút toán điều chỉnh (S7-02).
   */
  function validateAdjustmentPayload(payload, originalPaymentAmount) {
    const errors = [];

    if (!payload || typeof payload !== 'object') {
      return { isValid: false, errors: ['Dữ liệu bút toán điều chỉnh không hợp lệ.'] };
    }

    if (!payload.reason || !String(payload.reason).trim()) {
      errors.push('Bắt buộc nhập lý do lập bút toán điều chỉnh.');
    } else if (String(payload.reason).trim().length > 500) {
      errors.push('Lý do điều chỉnh không được vượt quá 500 ký tự.');
    }

    const type = payload.adjustmentType || 'REVERSAL';
    if (!['REVERSAL', 'PARTIAL_REVERSAL'].includes(type)) {
      errors.push('Loại điều chỉnh không hợp lệ (chỉ chấp nhận REVERSAL hoặc PARTIAL_REVERSAL).');
    }

    if (type === 'PARTIAL_REVERSAL') {
      const adjAmount = Number(payload.adjustmentAmount);
      if (!Number.isFinite(adjAmount) || adjAmount <= 0) {
        errors.push('Số tiền điều chỉnh giảm phải lớn hơn 0.');
      } else if (originalPaymentAmount !== null && originalPaymentAmount !== undefined) {
        const orig = Number(originalPaymentAmount);
        if (Number.isFinite(orig) && adjAmount > orig) {
          errors.push(`Số tiền điều chỉnh giảm (${formatMoney(adjAmount)}) không được vượt quá số tiền phiếu thu gốc (${formatMoney(orig)}).`);
        }
      }
    }

    if (payload.adjustmentDate && !/^\d{4}-\d{2}-\d{2}$/.test(payload.adjustmentDate)) {
      errors.push('Ngày điều chỉnh phải có định dạng YYYY-MM-DD.');
    }

    if (payload.note && String(payload.note).length > 500) {
      errors.push('Ghi chú không được vượt quá 500 ký tự.');
    }

    if (payload.recordedBy && String(payload.recordedBy).length > 100) {
      errors.push('Người lập điều chỉnh không được vượt quá 100 ký tự.');
    }

    return {
      isValid: errors.length === 0,
      errors: errors
    };
  }

  /**
   * Kiểm tra quyền xem phiếu thu & công nợ đại lý theo Permission Matrix (không role fallback).
   */
  function canViewDebtPayments(user) {
    if (!user || typeof user !== 'object') return false;
    if (Array.isArray(user.roles) && user.roles.includes('ROLE_ADMIN')) return true;
    return Array.isArray(user.permissions) && user.permissions.includes('FINANCE_VIEW_DEBT');
  }

  /**
   * Kiểm tra quyền lập phiếu thu & lập bút toán điều chỉnh theo Permission Matrix (không role fallback).
   */
  function canManageDebtPayments(user) {
    if (!user || typeof user !== 'object') return false;
    if (Array.isArray(user.roles) && user.roles.includes('ROLE_ADMIN')) return true;
    return Array.isArray(user.permissions) && user.permissions.includes('FINANCE_MANAGE');
  }

  /**
   * Kiểm tra quyền in và tải PDF phiếu thu (S7-09).
   * Yêu cầu FINANCE_VIEW_DEBT hoặc FINANCE_MANAGE hoặc ROLE_ADMIN (không role fallback).
   */
  function canPrintReceipt(user) {
    if (!user || typeof user !== 'object') return false;
    if (Array.isArray(user.roles) && user.roles.includes('ROLE_ADMIN')) return true;
    if (!Array.isArray(user.permissions)) return false;
    return user.permissions.includes('FINANCE_VIEW_DEBT') || user.permissions.includes('FINANCE_MANAGE');
  }

  /**
   * Đọc số tiền thành chữ tiếng Việt chuẩn biểu mẫu kế toán Việt Nam (S7-09).
   * Ví dụ: 15000000 -> "Mười lăm triệu đồng chẵn."
   */
  function numberToVietnameseWords(amount) {
    if (amount === null || amount === undefined || amount === '') return '';
    const num = Math.round(Math.abs(Number(amount)));
    if (!Number.isFinite(num)) return '';
    if (num === 0) return 'Không đồng chẵn.';

    const digits = ['không', 'một', 'hai', 'ba', 'bốn', 'năm', 'sáu', 'bảy', 'tám', 'chín'];

    function readGroup(g, isLeading) {
      const h = Math.floor(g / 100);
      const t = Math.floor((g % 100) / 10);
      const u = g % 10;
      if (h === 0 && t === 0 && u === 0) return '';

      let out = '';
      if (!isLeading || h > 0) {
        out += digits[h] + ' trăm ';
      }
      if (t > 1) {
        out += digits[t] + ' mươi ';
        if (u === 1) out += 'mốt ';
        else if (u === 5) out += 'lăm ';
        else if (u > 0) out += digits[u] + ' ';
      } else if (t === 1) {
        out += 'mười ';
        if (u === 5) out += 'lăm ';
        else if (u > 0) out += digits[u] + ' ';
      } else if (t === 0) {
        if (u > 0) {
          if (!isLeading || h > 0) out += 'lẻ ' + digits[u] + ' ';
          else out += digits[u] + ' ';
        }
      }
      return out.trim();
    }

    const scales = ['', 'nghìn', 'triệu', 'tỷ', 'nghìn tỷ', 'triệu tỷ'];
    let temp = num;
    const groups = [];
    while (temp > 0) {
      groups.push(temp % 1000);
      temp = Math.floor(temp / 1000);
    }

    let words = '';
    for (let i = groups.length - 1; i >= 0; i--) {
      const g = groups[i];
      if (g > 0) {
        const isLeading = (i === groups.length - 1);
        const groupText = readGroup(g, isLeading);
        if (groupText) {
          words += (words ? ' ' : '') + groupText + (scales[i] ? ' ' + scales[i] : '');
        }
      }
    }

    words = words.trim();
    if (!words) return 'Không đồng chẵn.';
    const cap = words.charAt(0).toUpperCase() + words.slice(1);
    return `${cap} đồng chẵn.`;
  }

  /**
   * Loại bỏ dấu tiếng Việt phục vụ định dạng mã hoá PDF Type 1 (S7-09).
   */
  function removeVietnameseDiacritics(str) {
    if (!str) return '';
    return String(str)
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/đ/g, 'd')
      .replace(/Đ/g, 'D');
  }

  /**
   * Thoát ký tự text an toàn trong PDF stream (S7-09).
   */
  function escapePdfText(str) {
    if (!str) return '';
    return removeVietnameseDiacritics(str)
      .replace(/\\/g, '\\\\')
      .replace(/\(/g, '\\(')
      .replace(/\)/g, '\\)');
  }

  /**
   * Tạo Binary Blob PDF (chuẩn %PDF-1.4, Content-Type: application/pdf) cho phiếu thu (S7-09).
   */
  function generateReceiptPdfBlob(payment) {
    if (!payment) return null;

    const pCode = escapePdfText(payment.paymentCode || `PT-${payment.id || 'NEW'}`);
    const date = escapePdfText(formatDate(payment.paymentDate || payment.createdAt));
    const dealerName = escapePdfText(payment.dealerName || `Dai ly #${payment.dealerId || ''}`);
    const dealerCode = escapePdfText(payment.dealerCode ? ` (${payment.dealerCode})` : '');
    const amountStr = escapePdfText(formatMoney(payment.amount));
    const words = escapePdfText(numberToVietnameseWords(payment.amount));
    const methodInfo = getPaymentMethodInfo(payment.paymentMethod);
    const methodStr = escapePdfText(methodInfo.label);
    const recordedBy = escapePdfText(payment.recordedBy || 'Ke toan');
    const refCode = escapePdfText(payment.referenceCode || '-');
    const note = escapePdfText(payment.note || 'Thu tien cong no');

    const lines = [
      'BT /F1 13 Tf 50 790 Td (CONG TY CO PHAN SALESTOCK) Tj ET',
      'BT /F2 8.5 Tf 50 775 Td (He thong Quan ly Ban hang & Phan phoi SaleStock) Tj ET',
      'BT /F1 9 Tf 380 790 Td (Mau so 01 - TT) Tj ET',
      'BT /F2 8 Tf 380 775 Td (Ban hanh theo TT 200/2014/TT-BTC) Tj ET',
      '0.75 w 50 760 m 545 760 l S',
      'BT /F1 16 Tf 180 730 Td (PHIEU THU TIEN CONG NO) Tj ET',
      `BT /F2 9.5 Tf 210 712 Td (Ngay thu: ${date}) Tj ET`,
      `BT /F1 10 Tf 390 712 Td (Ma phieu: ${pCode}) Tj ET`,
      '0.5 w 50 698 m 545 698 l S',
      `BT /F1 10 Tf 50 675 Td (Dai ly nop tien:) Tj /F2 10 Tf 85 0 Td (${dealerName}${dealerCode}) Tj ET`,
      `BT /F1 10 Tf 50 655 Td (So tien thu:) Tj /F1 11 Tf 85 0 Td (${amountStr}) Tj ET`,
      `BT /F1 10 Tf 50 635 Td (Viet bang chu:) Tj /F2 9.5 Tf 85 0 Td (${words}) Tj ET`,
      `BT /F1 10 Tf 50 615 Td (Hinh thuc thu:) Tj /F2 10 Tf 85 0 Td (${methodStr}) Tj /F1 10 Tf 60 0 Td (Ma chung tu/UNC:) Tj /F2 10 Tf 90 0 Td (${refCode}) Tj ET`,
      `BT /F1 10 Tf 50 595 Td (Nguoi thu tien:) Tj /F2 10 Tf 85 0 Td (${recordedBy}) Tj ET`,
      `BT /F1 10 Tf 50 575 Td (Ly do nop tien:) Tj /F2 9.5 Tf 85 0 Td (${note}) Tj ET`,
      '0.5 w 50 558 m 545 558 l S',
    ];

    let currentY = 540;
    const allocations = Array.isArray(payment.allocations) ? payment.allocations : [];
    if (allocations.length > 0) {
      lines.push(`BT /F1 9.5 Tf 50 ${currentY} Td (Chi tiet doi tru hoa don:) Tj ET`);
      currentY -= 16;
      lines.push(`0.5 w 50 ${currentY + 11} m 545 ${currentY + 11} l S`);
      lines.push(`BT /F1 8.5 Tf 55 ${currentY} Td (STT) Tj 35 0 Td (Ma hoa don) Tj 130 0 Td (Tien can tru) Tj 130 0 Td (No con lai) Tj 100 0 Td (Trang thai) Tj ET`);
      currentY -= 5;
      lines.push(`0.5 w 50 ${currentY} m 545 ${currentY} l S`);
      currentY -= 13;

      allocations.slice(0, 8).forEach((alloc, idx) => {
        const orderCode = escapePdfText(alloc.orderCode || `HD-${alloc.orderId}`);
        const allocAmt = escapePdfText(formatMoney(alloc.allocatedAmount));
        const remAmt = escapePdfText(formatMoney(alloc.orderRemainingAmount));
        const statusText = escapePdfText(alloc.orderPaymentStatusDisplayName || alloc.orderPaymentStatus || 'Da doi tru');
        lines.push(`BT /F2 8 Tf 60 ${currentY} Td (${idx + 1}) Tj 30 0 Td (${orderCode}) Tj 130 0 Td (${allocAmt}) Tj 130 0 Td (${remAmt}) Tj 100 0 Td (${statusText}) Tj ET`);
        currentY -= 13;
      });
      lines.push(`0.5 w 50 ${currentY + 4} m 545 ${currentY + 4} l S`);
      currentY -= 14;
    } else {
      currentY -= 8;
    }

    lines.push(`BT /F2 9 Tf 375 ${currentY} Td (Ngay ... thang ... nam ...) Tj ET`);
    currentY -= 18;
    lines.push(`BT /F1 9.5 Tf 60 ${currentY} Td (Nguoi nop tien) Tj 120 0 Td (Nguoi thu tien) Tj 120 0 Td (Ke toan truong) Tj 110 0 Td (Giam doc) Tj ET`);
    currentY -= 12;
    lines.push(`BT /F2 8 Tf 62 ${currentY} Td ((Ky, ho ten)) Tj 122 0 Td ((Ky, ho ten)) Tj 122 0 Td ((Ky, ho ten)) Tj 112 0 Td ((Ky, dong dau)) Tj ET`);
    currentY -= 45;
    lines.push(`BT /F2 8.5 Tf 175 ${currentY} Td (${recordedBy}) Tj ET`);

    const streamContent = lines.join('\n');
    const streamLength = Buffer.byteLength ? Buffer.byteLength(streamContent, 'utf8') : streamContent.length;

    const objects = [];
    objects[1] = '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj';
    objects[2] = '2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj';
    objects[3] = '3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /MediaBox [0 0 595.28 841.89] /Contents 6 0 R >>\nendobj';
    objects[4] = '4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj';
    objects[5] = '5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj';
    objects[6] = `6 0 obj\n<< /Length ${streamLength} >>\nstream\n${streamContent}\nendstream\nendobj`;

    let pdfText = '%PDF-1.4\n';
    const offsets = [0];
    for (let i = 1; i <= 6; i++) {
      offsets[i] = pdfText.length;
      pdfText += objects[i] + '\n';
    }
    const startxref = pdfText.length;
    pdfText += 'xref\n0 7\n0000000000 65535 f \n';
    for (let i = 1; i <= 6; i++) {
      pdfText += String(offsets[i]).padStart(10, '0') + ' 00000 n \n';
    }
    pdfText += 'trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n' + startxref + '\n%%EOF\n';

    return new Blob([pdfText], { type: 'application/pdf' });
  }

  /**
   * Tạo HTML biểu mẫu phiếu thu chuẩn kế toán Việt Nam (S7-09).
   */
  function renderReceiptHtml(payment) {
    if (!payment) return '';

    const pCode = payment.paymentCode || `PT-${payment.id || 'NEW'}`;
    const pDate = formatDate(payment.paymentDate || payment.createdAt);
    const methodInfo = getPaymentMethodInfo(payment.paymentMethod);
    const words = numberToVietnameseWords(payment.amount);
    const isAdjustment = payment.paymentType === 'ADJUSTMENT';
    const allocations = Array.isArray(payment.allocations) ? payment.allocations : [];

    return `
      <div class="dp-receipt-sheet">
        <div class="dp-receipt-top">
          <div class="dp-receipt-company">
            <strong>CÔNG TY CỔ PHẦN SALESTOCK</strong>
            <div>Hệ thống Quản trị Bán hàng &amp; Phân phối</div>
            <div>Địa chỉ: Trụ sở chính Công ty SaleStock</div>
          </div>
          <div class="dp-receipt-form-meta">
            <strong>Mẫu số: 01 - TT</strong>
            <div>Ban hành theo Thông tư số 200/2014/TT-BTC</div>
            <div>Ngày 22/12/2014 của Bộ Tài chính</div>
          </div>
        </div>

        <div class="dp-receipt-title-box">
          <h1 class="dp-receipt-main-title">${isAdjustment ? 'PHIẾU ĐIỀU CHỈNH THU TIỀN CÔNG NỢ' : 'PHIẾU THU TIỀN CÔNG NỢ'}</h1>
          <div class="dp-receipt-sub-date">Ngày thu: <strong>${pDate}</strong></div>
          <div class="dp-receipt-code-badge">Số phiếu: <strong>${pCode}</strong></div>
        </div>

        <div class="dp-receipt-info-grid">
          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Họ tên người nộp / Đại lý:</span>
            <span class="dp-receipt-value"><strong>${payment.dealerName || '—'}</strong> ${payment.dealerCode ? `(${payment.dealerCode})` : ''}</span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Địa chỉ / Đơn vị:</span>
            <span class="dp-receipt-value">${payment.dealerAddress || 'Đại lý phân phối hệ thống'}</span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Lý do thu tiền:</span>
            <span class="dp-receipt-value">${payment.note || 'Thu tiền thanh toán công nợ đơn hàng phân phối'}</span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Số tiền thu:</span>
            <span class="dp-receipt-value dp-receipt-amount">
              ${formatMoney(payment.amount)}
            </span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Viết bằng chữ:</span>
            <span class="dp-receipt-value dp-receipt-words"><em>${words}</em></span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Hình thức thu tiền:</span>
            <span class="dp-receipt-value">
              <span class="badge ${methodInfo.badgeClass}">${methodInfo.label}</span>
              ${payment.referenceCode ? ` — Mã chứng từ / UNC: <strong>${payment.referenceCode}</strong>` : ''}
            </span>
          </div>

          <div class="dp-receipt-info-row">
            <span class="dp-receipt-label">Người lập phiếu / Người thu:</span>
            <span class="dp-receipt-value"><strong>${payment.recordedBy || 'Kế toán công nợ'}</strong></span>
          </div>

          ${isAdjustment && payment.originalPaymentCode ? `
            <div class="dp-receipt-info-row" style="color: #c2410c;">
              <span class="dp-receipt-label">Phiếu thu gốc điều chỉnh:</span>
              <span class="dp-receipt-value"><strong>${payment.originalPaymentCode}</strong> — Lý do: ${payment.adjustmentReason || '—'}</span>
            </div>
          ` : ''}
        </div>

        ${allocations.length > 0 ? `
          <div class="dp-receipt-alloc-section">
            <div class="dp-receipt-alloc-title">Chi tiết đối trừ vào hoá đơn / đơn hàng:</div>
            <table class="dp-receipt-table">
              <thead>
                <tr>
                  <th style="width: 45px; text-align: center;">STT</th>
                  <th>Mã hoá đơn / Đơn</th>
                  <th class="num">Tổng tiền HĐ</th>
                  <th class="num">Tiền cấn trừ</th>
                  <th class="num">Còn nợ lại</th>
                  <th style="text-align: center;">Trạng thái mới</th>
                </tr>
              </thead>
              <tbody>
                ${allocations.map((a, idx) => `
                  <tr>
                    <td style="text-align: center;">${idx + 1}</td>
                    <td><strong>${a.orderCode || `HĐ-${a.orderId}`}</strong></td>
                    <td class="num">${formatMoney(a.orderTotalAmount)}</td>
                    <td class="num" style="font-weight: 700; color: #15803d;">${formatMoney(a.allocatedAmount)}</td>
                    <td class="num">${formatMoney(a.orderRemainingAmount)}</td>
                    <td style="text-align: center;">
                      <span class="badge ${getInvoicePaymentStatusInfo(a.orderPaymentStatus).badgeClass}">
                        ${a.orderPaymentStatusDisplayName || getInvoicePaymentStatusInfo(a.orderPaymentStatus).label}
                      </span>
                    </td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          </div>
        ` : ''}

        <div class="dp-receipt-footer-notes">
          <div>* Đã nhận đủ số tiền: <em>${words}</em></div>
          <div>* Phiếu thu được lập thành 02 liên: Liên 1 lưu tại đơn vị, Liên 2 giao cho đại lý nộp tiền.</div>
        </div>

        <div class="dp-receipt-signatures">
          <div class="dp-receipt-sig-block">
            <strong>Người nộp tiền</strong>
            <div class="dp-receipt-sig-hint">(Ký, ghi rõ họ tên)</div>
            <div class="dp-receipt-sig-space"></div>
            <div class="dp-receipt-sig-name">${payment.dealerName || ''}</div>
          </div>

          <div class="dp-receipt-sig-block">
            <strong>Người thu tiền</strong>
            <div class="dp-receipt-sig-hint">(Ký, ghi rõ họ tên)</div>
            <div class="dp-receipt-sig-space"></div>
            <div class="dp-receipt-sig-name">${payment.recordedBy || 'Kế toán công nợ'}</div>
          </div>

          <div class="dp-receipt-sig-block">
            <strong>Kế toán trưởng</strong>
            <div class="dp-receipt-sig-hint">(Ký, ghi rõ họ tên)</div>
            <div class="dp-receipt-sig-space"></div>
            <div class="dp-receipt-sig-name"></div>
          </div>

          <div class="dp-receipt-sig-block">
            <strong>Thủ trưởng đơn vị</strong>
            <div class="dp-receipt-sig-hint">(Ký, đóng dấu)</div>
            <div class="dp-receipt-sig-space"></div>
            <div class="dp-receipt-sig-name"></div>
          </div>
        </div>
      </div>
    `;
  }

  return {
    formatMoney: formatMoney,
    parseMoney: parseMoney,
    formatDate: formatDate,
    formatDateTime: formatDateTime,
    getPaymentMethodInfo: getPaymentMethodInfo,
    getPaymentTypeInfo: getPaymentTypeInfo,
    getInvoicePaymentStatusInfo: getInvoicePaymentStatusInfo,
    validatePaymentPayload: validatePaymentPayload,
    validateAdjustmentPayload: validateAdjustmentPayload,
    canViewDebtPayments: canViewDebtPayments,
    canManageDebtPayments: canManageDebtPayments,
    canPrintReceipt: canPrintReceipt,
    numberToVietnameseWords: numberToVietnameseWords,
    removeVietnameseDiacritics: removeVietnameseDiacritics,
    generateReceiptPdfBlob: generateReceiptPdfBlob,
    renderReceiptHtml: renderReceiptHtml
  };
});
