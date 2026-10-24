const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const U = require('../frontend/js/cash-collection-utils.js');

test('money input preserves all BigDecimal digits, accepts Vietnamese decimal comma', () => {
  assert.equal(U.amount('99999999999999999.99'), '99999999999999999.99');
  assert.equal(U.amount('250000,50'), '250000.50');
  assert.equal(U.amount('0005.01'), '5.01');
  for (const bad of ['0','-1','1.001','1e3','1.000.000','1,000,000','Infinity','123456789012345678','']) assert.throws(() => U.amount(bad), bad);
});
test('response parsing and totals do not lose large monetary tokens', () => {
  const result = U.parse('{"amount":99999999999999999.99,"collected":9007199254740993.01,"pending":0,"message":"test"}');
  assert.equal(result.amount, '99999999999999999.99');
  assert.equal(U.cents(result.collected), 900719925474099301n);
  assert.equal(U.total([{amount:'9007199254740993.01'}, {amount:'0.99'}], 'amount'), '9.007.199.254.740.994 ₫');
  assert.equal(U.money('99999999999999999.99'), '99.999.999.999.999.999,99 ₫');
});
test('date boundaries, leap days, ids and image signatures are validated', () => {
  U.period('2024-01-01','2025-01-01');
  U.date('2024-02-29');
  for (const date of ['2026-02-29','2026-04-31','bad']) assert.throws(() => U.date(date));
  assert.throws(() => U.period('2026-10-24','2026-10-23'));
  assert.throws(() => U.period('2024-01-01','2025-01-02'));
  assert.throws(() => U.id('9007199254740993'));
  assert.throws(() => U.id('1 OR 1=1'));
  assert.equal(U.imageType(Uint8Array.from([137,80,78,71,13,10,26,10])), 'image/png');
  assert.equal(U.imageType(Uint8Array.from([255,216,255])), 'image/jpeg');
  assert.throws(() => U.imageType(new TextEncoder().encode('<svg>')));
  const png = Uint8Array.from([137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,2]);
  assert.deepEqual(U.imageDimensions(png), [1,2]);
  const jpeg = Uint8Array.from([255,216,255,192,0,7,8,0,10,0,20]);
  assert.deepEqual(U.imageDimensions(jpeg), [20,10]);
  assert.throws(() => U.imageDimensions(Uint8Array.from([255,216,255,217])));
  assert.equal(U.escape('<img onerror="x">'), '&lt;img onerror=&quot;x&quot;&gt;');
});
test('frontend uses authenticated multipart API and never stores business data in browser storage', () => {
  const js = fs.readFileSync(path.join(__dirname,'../frontend/js/cash-collections.js'),'utf8');
  assert.match(js, /new FormData/); assert.match(js, /type:'application\/json'/);
  assert.match(js, /Auth\.apiFetch/); assert.match(js, /URL\.revokeObjectURL/);
  assert.doesNotMatch(js, /localStorage|sessionStorage|innerHTML\s*=\s*row\./);
  const css = fs.readFileSync(path.join(__dirname,'../frontend/css/cash-collections.css'),'utf8');
  assert.doesNotMatch(css, /--app-content-|\.collections-page\s*\{[^}]*\b(?:max-width|margin|padding)\s*:/);
});
