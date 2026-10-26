/** Presentation only: a browser deadline never releases database stock. */
const OrderReservation = (() => {
  function describe(order, now = new Date()) {
    const status = String(order?.status || order?.currentStatus || '').toUpperCase();
    if (order?.inventoryReserved === true) {
      const deadline = new Date(order.reservationExpiresAt || '').getTime();
      const overdue = Number.isFinite(deadline) && deadline <= new Date(now).getTime();
      return { held: true, overdue, label: overdue ? 'Quá hạn — chờ hệ thống nhả tồn' : 'Đang giữ chỗ tồn kho' };
    }
    if (status === 'EXPIRED') return { held: false, label: 'Đã hết hạn giữ chỗ — tồn đã được nhả' };
    if (status === 'PENDING_APPROVAL' || status === 'DRAFT') return { held: false, label: 'Chờ duyệt — chưa giữ chỗ' };
    if (['EXPORTED', 'SHIPPED', 'DELIVERED', 'CLOSED', 'COMPLETED'].includes(status)) return { held: false, label: 'Đã xuất kho — không còn giữ chỗ' };
    if (order?.inventoryReserved === false) return { held: false, label: 'Không còn giữ chỗ tồn kho' };
    return { held: false, label: 'Chưa có dữ liệu giữ chỗ từ máy chủ' };
  }
  return { describe };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = OrderReservation;
