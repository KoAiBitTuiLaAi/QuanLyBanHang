// Chrome exercises the actual receipt client with isolated HTTP fixtures; never writes business data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  let count = 0, release, hold = false, forbidden = false, wrongType = false;
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    await page.setContent('<div id="modal" hidden><p id="message"></p><div id="area"></div><button id="print">In</button><button id="pdf">PDF</button></div>');
    await page.addScriptTag({path:path.resolve(__dirname, '../frontend/js/debt-receipt-client.js')});
    await page.route('https://receipt.test/**', async route => {
      count++;
      if (hold) await new Promise(resolve => {release = resolve;});
      const pdf = route.request().url().endsWith('/pdf');
      const headers={'Access-Control-Allow-Origin':'*'};
      await route.fulfill({status:forbidden?403:200,headers,
        contentType: forbidden || wrongType ? 'application/json' : pdf ? 'application/pdf' : 'text/html; charset=utf-8',
        body:forbidden?JSON.stringify({message:'Quyền đã bị thu hồi'}):pdf?'%PDF-1.4 server receipt':'<!doctype html><html lang="vi"><body>PHIẾU THU PT-TEST Nguyễn Ánh 1.234,56 <script>parent.__xss=true</script></body></html>'});
    });
    await page.evaluate(() => {
      window.allowed = true; window.failures=[];
      window.client=DebtReceiptClient.createReceiptClient({
        apiFetch:url=>fetch('https://receipt.test'+url),canPrint:()=>window.allowed,
        area:document.querySelector('#area'),modal:document.querySelector('#modal'),message:document.querySelector('#message'),
        buttons:[document.querySelector('#print'),document.querySelector('#pdf')],onError:m=>failures.push(m),
      });
      document.querySelector('#print').onclick=()=>client.print();
      document.querySelector('#pdf').onclick=()=>client.download();
    });
    await page.evaluate(()=>client.open(1));
    assert.match(await page.frameLocator('iframe').locator('body').innerText(),/Nguyễn Ánh 1.234,56/);
    assert.equal(await page.evaluate(()=>window.__xss),undefined);
    assert.equal(await page.locator('iframe').getAttribute('sandbox'),'allow-same-origin allow-modals');
    const download = page.waitForEvent('download'); await page.locator('#pdf').click();
    const file=await download; assert.equal(file.suggestedFilename(),'phieu-thu-1.pdf');
    assert.match(fs.readFileSync(await file.path(),'utf8'),/^%PDF/);
    // Stale content must disappear immediately on close and cannot return from a delayed response.
    hold = true;
    const pending=page.evaluate(()=>client.open(2));
    await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('Đang tải'));
    await new Promise(resolve=>setTimeout(resolve,100));
    await page.evaluate(()=>client.close()); hold=false; release(); await pending;
    assert.equal(await page.locator('iframe').count(),0);
    assert.equal(await page.locator('#modal').isVisible(),false);
    await page.evaluate(()=>client.open(1));
    // Permission removal blocks the next backend request even when the preview was already loaded.
    forbidden=true; await page.evaluate(()=>client.print());
    assert.equal(await page.locator('iframe').count(),0);
    assert.match(await page.locator('#message').innerText(),/thu hồi/);
    forbidden=false;wrongType=true;
    const noDownload=()=>{throw Error('Wrong MIME unexpectedly downloaded');}; page.on('download',noDownload);
    await page.evaluate(()=>client.download());page.off('download',noDownload);
    assert.match(await page.locator('#message').innerText(),/định dạng/);wrongType=false;
    const before=count;
    await page.evaluate(()=>{allowed=false;client.close();return client.open(1);});
    assert.equal(count,before);assert.equal(await page.locator('#print').isDisabled(),true);
    await page.evaluate(()=>{allowed=true;return client.open(1);});
    // Only one request for repeated download clicks while in flight.
    hold=true;const previous=count;
    const first=page.evaluate(()=>client.download());
    await page.waitForTimeout(100);await page.evaluate(()=>client.download());
    assert.equal(count,previous+1);hold=false;release();await first;
    await page.evaluate(()=>client.close());
    console.log('PASS S7-09 Chrome: Unicode, sandbox/XSS, server PDF, delayed close, revocation, MIME, local denial, duplicate prevention');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
