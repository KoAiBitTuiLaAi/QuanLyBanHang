/** S3-07: thao tác giao dịch được lưu và kiểm quyền tại backend. */
const DealerTransactions = (() => {
  async function request(path, options = {}) {
    const response = await Auth.apiFetch(path, options);
    const result = await response.json().catch(() => null);
    if (!response.ok || result?.success !== true) {
      throw new Error(result?.message || 'Không thể xử lý giao dịch. Vui lòng thử lại.');
    }
    return result.data;
  }

  async function lock(dealerId, reason) {
    const normalizedReason = String(reason ?? '').trim();
    if (!normalizedReason) throw new Error('Vui lòng nhập lý do khoá giao dịch.');
    if (normalizedReason.length > 500) throw new Error('Lý do khoá tối đa 500 ký tự.');
    return request(`/dealers/${dealerId}/lock`, {
      method: 'POST', body: JSON.stringify({ reason: normalizedReason }),
    });
  }

  async function unlock(dealerId) {
    return request(`/dealers/${dealerId}/unlock`, { method: 'POST' });
  }

  async function getInProgressOrders(dealerId) {
    const rows = await request(`/dealers/${dealerId}/in-progress-orders`);
    if (!Array.isArray(rows)) throw new Error('Danh sách đơn hàng không hợp lệ.');
    return rows;
  }

  async function getOrdersForDealer(dealerId) {
    const rows = await request('/orders');
    if (!Array.isArray(rows)) throw new Error('Lịch sử đơn hàng không hợp lệ.');
    return rows.filter((order) => Number(order.dealerId) === Number(dealerId));
  }

  async function getOrder(orderId) {
    const order = await request(`/orders/${orderId}`);
    if (!order || !Array.isArray(order.items)) {
      throw new Error('Dữ liệu đơn hàng không hợp lệ.');
    }
    return order;
  }

  /** S4-09: copy only order inputs into the server-side draft. */
  async function copyOrderToDraft(orderId) {
    const order = await getOrder(orderId);
    if (!order.items.length) {
      throw new Error('Đơn hàng không có dòng hàng để sao chép.');
    }

    const draft = {
      dealerId: order.dealerId || null,
      deliveryPointId: order.deliveryPointId || null,
      deliveryDate: null,
      note: '',
      items: order.items
        .filter((item) => item.productId && Number(item.quantity) > 0)
        .map((item, index) => ({
          _key: `copy-${order.id}-${index}`,
          productId: item.productId,
          selectedUnitId: item.unit || null,
          qty: Number(item.quantity),
          // Do not carry the old price, floor price, or discount into the copy.
          basePrice: 0,
          unitPrice: 0,
          floorPrice: 0,
          manualPrice: false,
          discountAmount: 0,
        })),
      _seq: order.items.length,
    };

    if (!draft.items.length) {
      throw new Error('Đơn hàng không có dòng hàng hợp lệ để sao chép.');
    }

    await request('/orders/draft', {
      method: 'PUT',
      body: JSON.stringify(draft),
    });
    return order;
  }

  async function processOrder(orderId, action) {
    if (!['approve', 'reject'].includes(action)) throw new Error('Thao tác đơn hàng không hợp lệ.');
    return request(`/orders/${orderId}/${action}`, { method: 'PUT' });
  }

  async function cancelOrder(orderId, reason) {
    const normalizedReason = String(reason ?? '').trim();
    if (!normalizedReason) throw new Error('Vui lòng nhập lý do huỷ đơn hàng.');
    if (normalizedReason.length > 500) throw new Error('Lý do huỷ tối đa 500 ký tự.');
    return request(`/orders/${orderId}/cancel`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: normalizedReason }),
    });
  }

  return {
    lock,
    unlock,
    getInProgressOrders,
    getOrdersForDealer,
    getOrder,
    copyOrderToDraft,
    processOrder,
    cancelOrder,
  };
})();
