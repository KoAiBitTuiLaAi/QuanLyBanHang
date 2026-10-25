/* S8-09 production adapter. The backend owns aging calculations and risk ranking. */
const DebtAgingApi = Object.freeze({
  available: true,
  async options({ signal } = {}) {
    return this.request('/reports/debt-aging/options', { signal });
  },
  async report(filters, { signal } = {}) {
    const query = new URLSearchParams();
    query.set('asOfDate', filters.asOfDate);
    if (filters.employeeId !== null) query.set('employeeId', String(filters.employeeId));
    if (filters.unassignedOnly) query.set('unassignedOnly', 'true');
    return this.request(`/reports/debt-aging?${query}`, { signal });
  },
  async request(path, { signal } = {}) {
    const response = await Auth.apiFetch(path, {
      method: 'GET',
      signal,
      headers: { Accept: 'application/json' },
    });
    let body;
    try {
      body = await response.json();
    } catch {
      body = null;
    }
    if (!response.ok || body?.success !== true || !Object.hasOwn(body, 'data')) {
      throw new Error(body?.message || `Không tải được báo cáo công nợ (HTTP ${response.status}).`);
    }
    return body.data;
  },
});
