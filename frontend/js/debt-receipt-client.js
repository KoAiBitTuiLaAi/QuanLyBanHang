/* S7-09: authorized server documents only; no cached accounting payload or browser storage. */
(function (root) {
  'use strict';
  function createReceiptClient({ apiFetch, canPrint, area, modal, message, buttons, onError }) {
    let epoch = 0, currentId = null, busy = false, frame = null;
    const urls = new Set();
    function controls() { buttons.forEach(b => { if (b) b.disabled = busy || !currentId || !canPrint(); }); }
    function clearFrame() { frame?.remove(); frame = null; area.replaceChildren(); }
    function close() {
      epoch++; currentId = null; busy = false; clearFrame();
      urls.forEach(url => URL.revokeObjectURL(url)); urls.clear();
      modal.hidden = true; message.textContent = ''; controls();
    }
    function idOf(value) {
      const id = String(value ?? '');
      if (!/^[1-9]\d*$/.test(id)) throw Error('Mã khoản thu không hợp lệ.');
      return id;
    }
    async function response(id, kind) {
      if (!canPrint()) throw Error('Bạn không có quyền xem hoặc in phiếu thu.');
      const r = await apiFetch(`/finance/debt-payments/${id}/receipt/${kind}`);
      if (!r.ok) {
        const data = await r.json().catch(() => null);
        throw Error(data?.message || 'Không tải được chứng từ từ hệ thống.');
      }
      const type = r.headers.get('content-type') || '';
      if (!type.toLowerCase().startsWith(kind === 'pdf' ? 'application/pdf' : 'text/html')) throw Error('Hệ thống trả về định dạng chứng từ không hợp lệ.');
      return r;
    }
    async function preview(ticket, id) {
      const r = await response(id, 'print');
      const html = await r.text();
      if (ticket !== epoch || !canPrint()) return false;
      clearFrame();
      const child = document.createElement('iframe'); frame = child;
      child.title = 'Phiếu thu từ khoản đã ghi nhận';
      child.setAttribute('sandbox', 'allow-same-origin allow-modals');
      child.className = 'dp-receipt-frame';
      const loaded = new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(Error('Chứng từ tải quá lâu. Vui lòng thử lại.')), 15000);
        child.onload = () => { clearTimeout(timeout); resolve(); };
      });
      child.srcdoc = html; area.replaceChildren(child); await loaded;
      return ticket === epoch && canPrint() && frame === child;
    }
    function fail(error, ticket) {
      if (ticket !== epoch) return;
      clearFrame(); message.textContent = error.message; onError(error.message);
    }
    async function open(value) {
      close(); const ticket = epoch;
      try {
        currentId = idOf(value);
        if (!canPrint()) throw Error('Bạn không có quyền xem hoặc in phiếu thu.');
        busy = true; controls(); modal.hidden = false; message.textContent = 'Đang tải phiếu thu…';
        if (await preview(ticket, currentId)) message.textContent = '';
      } catch (error) { fail(error, ticket); }
      finally { if (ticket === epoch) { busy = false; controls(); } }
    }
    async function print() {
      if (busy || !currentId) return;
      const ticket = epoch, id = currentId;
      busy = true; controls(); message.textContent = 'Đang kiểm tra và tải bản in…';
      try {
        // Re-authorize each print, including when the preview was opened before permission revocation.
        if (await preview(ticket, id)) { message.textContent = ''; frame.contentWindow.focus(); frame.contentWindow.print(); }
      } catch (error) { fail(error, ticket); }
      finally { if (ticket === epoch) { busy = false; controls(); } }
    }
    async function download(value = currentId) {
      if (busy) return;
      const ticket = epoch; busy = true; controls();
      try {
        const id = idOf(value), r = await response(id, 'pdf'), blob = await r.blob();
        if (ticket !== epoch || !canPrint()) return;
        const url = URL.createObjectURL(blob); urls.add(url);
        const link = document.createElement('a'); link.href = url; link.download = `phieu-thu-${id}.pdf`;
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url); }, 1000);
      } catch (error) { fail(error, ticket); }
      finally { if (ticket === epoch) { busy = false; controls(); } }
    }
    controls();
    return { open, close, print, download };
  }
  if (typeof module === 'object' && module.exports) module.exports = { createReceiptClient };
  else root.DebtReceiptClient = { createReceiptClient };
})(typeof window === 'object' ? window : globalThis);
