# S4-09 frontend status

- Frontend: done. A user with `ORDER_CREATE` can copy an existing order into a new server-side draft.
- Copy behavior: dealer, delivery point, and quantities are copied; the old price, floor price, manual-price flag, and discount are not copied.
- Repricing: opening the copied draft reloads the current effective price list and recalculates current discounts.
- Backend: unchanged, as requested. The frontend uses the existing `GET /orders/{id}` and `PUT /orders/draft` APIs.
- Permission matrix: the copy action is shown and handled only when `Auth.hasPermission('ORDER_CREATE')` is true.
