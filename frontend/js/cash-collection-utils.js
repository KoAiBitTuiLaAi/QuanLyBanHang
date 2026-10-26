/* Exact decimal money: never round financial input through Number. */
const CashCollectionUtils = (() => {
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function amount(value) {
    const text = String(value).trim().replace(',', '.');
    if (!/^\d{1,17}(?:\.\d{1,2})?$/.test(text) || cents(text) <= 0n)
      throw Error('Số tiền phải lớn hơn 0, tối đa 17 chữ số phần nguyên và 2 số thập phân; không dùng dấu phân cách hàng nghìn.');
    return text.replace(/^0+(?=\d)/, '');
  }
  function cents(value) {
    const text = String(value ?? '0');
    if (!/^-?\d+(?:\.\d{1,2})?$/.test(text)) throw Error('Số tiền từ hệ thống không hợp lệ.');
    const negative = text.startsWith('-'), [whole, fraction = ''] = text.replace(/^-/, '').split('.');
    const result = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    return negative ? -result : result;
  }
  function moneyFromCents(value) {
    const negative = value < 0n, absolute = negative ? -value : value;
    return `${negative ? '-' : ''}${new Intl.NumberFormat('vi-VN').format(absolute / 100n)}${absolute % 100n ? ',' + String(absolute % 100n).padStart(2, '0') : ''} ₫`;
  }
  const money = value => moneyFromCents(cents(value));
  const total = (rows, key) => moneyFromCents(rows.reduce((sum, row) => sum + cents(row[key]), 0n));
  function parse(text) {
    // Preserve BigDecimal numeric tokens before JSON.parse loses integer precision.
    return JSON.parse(text.replace(/("(?:amount|collected|received|pending)"\s*:\s*)(-?\d+(?:\.\d+)?)(?=\s*[,}])/g, '$1"$2"'));
  }
  function date(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('Vui lòng nhập ngày hợp lệ.');
    const result = new Date(value + 'T00:00:00Z');
    if (!Number.isFinite(result.getTime()) || result.toISOString().slice(0, 10) !== value) throw Error('Ngày không hợp lệ.');
    return result;
  }
  function period(from, to) {
    const days = (date(to) - date(from)) / 86400000;
    if (days < 0 || days > 366) throw Error('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc, khoảng ngày tối đa 366 ngày.');
  }
  const today = () => new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Ho_Chi_Minh', year:'numeric', month:'2-digit', day:'2-digit'}).format(new Date());
  const dateLabel = value => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—';
  function id(value) {
    if (!/^[1-9]\d*$/.test(String(value)) || !Number.isSafeInteger(Number(value))) throw Error('Mã không hợp lệ.');
    return String(value);
  }
  function imageType(bytes) {
    if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
    if ([137,80,78,71,13,10,26,10].every((v, i) => bytes[i] === v)) return 'image/png';
    throw Error('Biên lai phải là ảnh JPEG hoặc PNG hợp lệ.');
  }
  function imageDimensions(bytes) {
    if (imageType(bytes) === 'image/png' && bytes.length >= 24) {
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      return [view.getUint32(16), view.getUint32(20)];
    }
    // Read JPEG SOF before decoding a potentially enormous compressed image.
    let offset = 2;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset++] !== 255) break;
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 217 || marker === 218) break;
      if (marker === 1 || (marker >= 208 && marker <= 215)) continue;
      const size = bytes[offset] * 256 + bytes[offset + 1];
      if (size < 2 || offset + size > bytes.length) break;
      if ([192,193,194,195,197,198,199,201,202,203,205,206,207].includes(marker) && size >= 7)
        return [bytes[offset + 5] * 256 + bytes[offset + 6], bytes[offset + 3] * 256 + bytes[offset + 4]];
      offset += size;
    }
    throw Error('Không đọc được kích thước ảnh JPEG/PNG.');
  }
  return {escape, amount, cents, money, total, parse, date, period, today, dateLabel, id, imageType, imageDimensions};
})();
if (typeof module !== 'undefined') module.exports = CashCollectionUtils;
