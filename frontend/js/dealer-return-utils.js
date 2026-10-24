/* S8-01 frontend contract helpers. Quantities use four-decimal fixed precision. */
const DealerReturnUtils = (() => {
  const readPermissions = ['ORDER_VIEW','ORDER_CREATE','INVOICE_VIEW','WAREHOUSE_VIEW','WAREHOUSE_APPROVE','WAREHOUSE_IMPORT'];
  const sourcePermissions = readPermissions.filter(p => p !== 'WAREHOUSE_IMPORT');
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function id(value) {
    if (!/^[1-9]\d*$/.test(String(value)) || !Number.isSafeInteger(Number(value))) throw Error('Mã dữ liệu không hợp lệ.');
    return String(value);
  }
  function decimal(value, places = 4) {
    const text = String(value ?? '').trim().replace(',', '.');
    if (!new RegExp('^\\d{1,11}(?:\\.\\d{1,' + places + '})?$').test(text))
      throw Error(`Số lượng phải là số không âm, tối đa 11 chữ số nguyên và ${places} số thập phân; không dùng dấu phân cách hàng nghìn.`);
    const [whole, fraction = ''] = text.split('.');
    return {text:whole.replace(/^0+(?=\d)/, '') + (fraction ? '.' + fraction : ''), value:BigInt(whole) * 10n ** BigInt(places) + BigInt(fraction.padEnd(places, '0'))};
  }
  function quantity(value) {
    if (value == null || value === '') return 'Chưa có dữ liệu';
    const result = decimal(value), whole = result.value / 10000n, fraction = String(result.value % 10000n).padStart(4,'0').replace(/0+$/, '');
    return new Intl.NumberFormat('vi-VN').format(whole) + (fraction ? ',' + fraction : '');
  }
  function parse(text) {
    return JSON.parse(text.replace(/("(?:[A-Za-z]*Quantity|conversionFactor|unitPrice|totalAmount)"\s*:\s*)(-?\d+(?:\.\d+)?)(?=\s*[,}])/g, '$1"$2"'));
  }
  function date(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('Ngày không hợp lệ.');
    const parsed = new Date(value + 'T00:00:00Z');
    if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0,10) !== value) throw Error('Ngày không hợp lệ.');
    return value;
  }
  function period(from, to) {
    if (from) date(from); if (to) date(to);
    if (from && to && from > to) throw Error('Từ ngày không được sau đến ngày.');
  }
  const dateLabel = value => value ? String(value).slice(0,10).split('-').reverse().join('/') : '—';
  const timeLabel = value => value ? dateLabel(value) + ' ' + String(value).slice(11,19) : '—';
  function note(value, max = 500, required = false) {
    const text = String(value ?? '').trim();
    if ((required && !text) || text.length > max) throw Error(`Nội dung ${required ? 'bắt buộc, ' : ''}tối đa ${max} ký tự.`);
    return text;
  }
  function page(data) {
    if (!data || !Array.isArray(data.content) || !Number.isSafeInteger(data.totalPages) || data.totalPages < 0 || !Number.isSafeInteger(data.totalElements) || data.totalElements < 0)
      throw Error('Máy chủ trả danh sách phân trang không hợp lệ.');
    return data;
  }
  function options(data) {
    if (!data || !['warehouses','defectiveWarehouses','returnReasons','statuses'].every(key => Array.isArray(data[key])) || typeof data.canCreate !== 'boolean' || typeof data.canConfirm !== 'boolean')
      throw Error('Không tải được danh mục và quyền trả hàng hợp lệ. Bấm Tải lại để thử lại.');
    for (const key of ['warehouses','defectiveWarehouses']) for (const row of data[key]) id(row.id);
    if (data.returnReasons.some(row => !row.code || !row.label) || data.statuses.some(row => !row.code || !row.label)) throw Error('Danh mục trả hàng thiếu mã hoặc nhãn.');
    return data;
  }
  function source(data, expectedId) {
    if (!data || id(data.invoiceId) !== id(expectedId) || !['ISSUED','ADJUSTED'].includes(data.status) || !Array.isArray(data.items))
      throw Error('Hóa đơn gốc không hợp lệ hoặc đã bị hủy.');
    const ids = new Set();
    for (const row of data.items) {
      const key = id(row.invoiceItemId);
      if (ids.has(key) || typeof row.manageLot !== 'boolean') throw Error('Dòng hóa đơn gốc thiếu hoặc trùng định danh.');
      ids.add(key);
      for (const field of ['deliveredQuantity','previouslyReturnedQuantity','returnableQuantity']) decimal(row[field]);
      if (!Array.isArray(row.availableLots)) throw Error('Chưa có dữ liệu lô hợp lệ cho dòng hóa đơn.');
      for (const lot of row.availableLots) {
        note(lot.lotNumber,50,true);
        if (lot.expiryDate) date(lot.expiryDate);
        if (lot.manufacturingDate) date(lot.manufacturingDate);
      }
    }
    return data;
  }
  function detail(data, expectedId) {
    if (!data || id(data.id) !== id(expectedId) || !['PENDING','CONFIRMED','CANCELLED'].includes(data.status) || !Array.isArray(data.items) || !data.items.length) throw Error('Phiếu trả hàng không hợp lệ.');
    id(data.invoiceId);
    for (const row of data.items) {
      id(row.invoiceItemId);
      if (decimal(row.returnQuantity).value <= 0n || typeof row.defective !== 'boolean') throw Error('Dòng hàng trả thiếu số lượng hoặc tình trạng hợp lệ.');
    }
    return data;
  }
  function build(invoice, rows, catalog, warehouseId, headerNote) {
    source(invoice, invoice?.invoiceId);
    if (!rows.length) throw Error('Chọn ít nhất một dòng hàng trả.');
    const warehouse = catalog.warehouses.find(row => String(row.id) === String(warehouseId));
    if (!warehouse) throw Error('Chọn kho thường nhận hàng từ danh mục hiện có.');
    const totals = new Map();
    const items = rows.map((draft, index) => {
      const row = invoice.items.find(item => String(item.invoiceItemId) === String(draft.invoiceItemId));
      if (!row) throw Error(`Dòng trả ${index + 1} không thuộc hóa đơn gốc.`);
      const qty = decimal(draft.quantity);
      if (qty.value <= 0n) throw Error('Số lượng trả phải lớn hơn 0.');
      const previous = totals.get(String(row.invoiceItemId)) || 0n;
      const total = previous + qty.value;
      if (total > decimal(row.returnableQuantity).value) throw Error(`${row.productCode}: tổng các dòng trả vượt ${quantity(row.returnableQuantity)} ${row.unit || ''} còn được trả (đã gồm phiếu chờ xác nhận).`);
      totals.set(String(row.invoiceItemId), total);
      const reason = catalog.returnReasons.find(item => item.code === draft.reason);
      if (!reason) throw Error('Bắt buộc chọn lý do trả từ danh mục.');
      const defective = reason.defectiveDefault === true || draft.defective === true;
      const target = defective ? catalog.defectiveWarehouses.find(item => String(item.id) === String(draft.defectiveWarehouseId)) : warehouse;
      if (!target) throw Error('Hàng hỏng phải chọn kho hàng lỗi đã được khai báo.');
      const result = {invoiceItemId:Number(id(row.invoiceItemId)), returnQuantity:qty.text,
        returnReason:reason.code === 'OTHER' ? note(draft.otherReason,500,true) : reason.code,
        defective, targetWarehouseId:Number(id(target.id)), note:note(draft.note,255) || null};
      if (row.manageLot) {
        if (row.availableLots.length) {
          const lot = row.availableLots.find(item => item.lotNumber === draft.lotNumber);
          if (!lot) throw Error('Chọn đúng lô trả về trong danh sách lô của hóa đơn.');
          result.lotNumber = note(lot.lotNumber,50,true);
          result.expiryDate = lot.expiryDate ? date(lot.expiryDate) : date(draft.expiryDate);
          result.manufacturingDate = lot.manufacturingDate ? date(lot.manufacturingDate) : (draft.manufacturingDate ? date(draft.manufacturingDate) : null);
        } else {
          result.lotNumber = note(draft.lotNumber,50,true);
          result.expiryDate = date(draft.expiryDate);
          result.manufacturingDate = draft.manufacturingDate ? date(draft.manufacturingDate) : null;
        }
        if (result.manufacturingDate && result.expiryDate < result.manufacturingDate) throw Error('Hạn sử dụng không được trước ngày sản xuất.');
      }
      return result;
    });
    return {invoiceId:Number(id(invoice.invoiceId)), warehouseId:Number(id(warehouse.id)), note:note(headerNote) || null, items};
  }
  return {readPermissions, sourcePermissions, escape, id, decimal, quantity, parse, date, period, dateLabel, timeLabel, note, page, options, source, detail, build};
})();
if (typeof module !== 'undefined') module.exports = DealerReturnUtils;
