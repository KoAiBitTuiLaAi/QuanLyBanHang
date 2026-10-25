const {test} = require('node:test');
const assert = require('node:assert/strict');
const tracking = require('../frontend/js/dealer-delivery-tracking.js');

test('S6-09 consumes real backend timestamps and keeps partial delivery in SHIPPING',()=>{
 const times=['2026-10-21T08:00:00','2026-10-21T09:00:00','2026-10-21T10:00:00','2026-10-21T11:00:00','2026-10-21T12:00:00'];
 const full=tracking.model({status:'DELIVERED',approvedAt:times[0],pickingAt:times[1],exportedAt:times[2],shippingAt:times[3],deliveredAt:times[4]});
 assert.deepEqual(full.steps.map(s=>s.at?.value),times);
 const partial=tracking.model({status:'SHIPPING',shippingAt:times[3],deliveredAt:times[4]});
 assert.equal(partial.steps[4].at,null);assert.equal(partial.steps[3].state,'current');
});

test('S6-09: five milestones in order; export never implies shipping or preceding milestones', () => {
  const data = tracking.model({status:'EXPORTED',backordered:true});
  assert.deepEqual(data.steps.map(s=>s.label), ['Đã duyệt','Đang soạn','Đã xuất','Đang giao','Đã giao']);
  assert.deepEqual(data.steps.map(s=>s.state), ['unknown','unknown','current','unknown','unknown']);
  assert.equal(data.backordered,true);
  assert.match(tracking.render({status:'EXPORTED',backordered:true}), /Đơn còn nợ hàng/);
});
test('S6-09: approvedAt is not approval evidence for draft/rejected/returned/cancelled', () => {
  for(const status of ['DRAFT','PENDING_APPROVAL','REJECTED','RETURNED','CANCELLED']) {
    assert.equal(tracking.model({status,approvedAt:'2026-10-23T10:12:00',recordedAt:'2026-10-23T10:12:00'}).steps[0].state,'unknown');
  }
  assert.equal(tracking.model({status:'APPROVED',recordedAt:'2026-10-23T10:12:00'}).steps[0].at,null);
});
test('S6-09: explicit approval history is retained, rejection history ignored', () => {
  const data=tracking.model({status:'CANCELLED',approvalHistory:[
    {action:'APPROVED',createdAt:'2026-10-23T09:00:00'},
    {action:'REJECTED',timestamp:'2026-10-23T11:00:00'}]});
  assert.equal(data.steps[0].at.value,'2026-10-23T09:00:00');
  assert.equal(data.stopped,true);
});
test('S6-09: real delivery date remains date-only, no invented time or preceding completion', () => {
  const data=tracking.model({status:'DELIVERED',actualDeliveryDate:'2026-10-23'});
  assert.deepEqual(data.steps.map(s=>s.state),['unknown','unknown','unknown','unknown','current']);
  assert.equal(data.steps[4].at.dateOnly,true);
  assert.doesNotMatch(data.steps[4].at.label,/00:00/);
  for(const status of ['EXPORTED','CANCELLED','PENDING_APPROVAL']) {
    assert.equal(tracking.model({status,actualDeliveryDate:'2026-10-23'}).steps[4].state,'unknown');
  }
});
test('S6-09: only explicit shipping status marks shipping current', () => {
  assert.equal(tracking.model({status:'SHIPPING'}).steps[3].state,'current');
  assert.equal(tracking.model({status:'SHIPPED'}).steps[3].state,'unknown');
});
test('S6-09: invalid or missing quantities differ from explicit zero and preserve decimals', () => {
  for(const value of [null,undefined,'',' ','bad',Infinity,true,false,[],{},-1]) assert.equal(tracking.quantity(value),'Chưa cập nhật');
  assert.equal(tracking.quantity(0),'0');
  assert.equal(tracking.quantity(2.5),'2,5');
});
test('S6-09: invalid timestamp, hostile status and unsolicited tracking fields cannot forge milestones', () => {
  assert.equal(tracking.timestamp('not-a-date'),null);
  assert.equal(tracking.timestamp(true),null);
  assert.equal(tracking.model(null).steps.length,5);
  assert.equal(tracking.model({approvalHistory:[null]}).steps[0].at,null);
  const html=tracking.render({status:'<img src=x onerror=alert(1)>',approvedAt:'<script>',
    steps:[{key:'shipping',completed:true,timestamp:'2026-10-23'}],history:[{status:'DELIVERED'}]});
  assert.doesNotMatch(html,/<img|<script|onerror=/);
  assert.equal((html.match(/data-delivery-milestone=/g)||[]).length,5);
  assert.equal((html.match(/__step--unknown/g)||[]).length,5);
});
