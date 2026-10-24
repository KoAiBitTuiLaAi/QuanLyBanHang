/* S8-09 integration boundary. No backend contract exists on this branch.
 * Replace these methods only after agreeing and implementing the real API.
 * Do not reuse dashboard/flagged-dealer data to fabricate aging or risk ranks.
 * Proposed shapes: docs/S8-09_FRONTEND_CONTRACT.md. Fixtures live in tests only.
 */
const DebtAgingApi = Object.freeze({
  available: false,
  async options() { throw new Error('Backend báo cáo tuổi nợ chưa được kết nối.'); },
  async report() { throw new Error('Backend báo cáo tuổi nợ chưa được kết nối.'); },
});
