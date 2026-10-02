const {chromium}=require('playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'.replaceAll('\\\\','\\')});
 try{
  const page=await browser.newPage({viewport:{width:1050,height:850}});
  await page.setContent('<style>:root{--text:#ddd;--text-muted:#aaa;--line:#3a3a3a;--surface-hover:#242424}body{background:#181818;color:#ddd;font-family:Arial,sans-serif;padding:32px}main{max-width:760px;margin:auto}</style><main class="runtime-message-text markdown-body"></main>');
  await page.addStyleTag({path:path.join(__dirname,'runtime.css')});
  for(const file of ['node_modules/marked/lib/marked.umd.js','node_modules/dompurify/dist/purify.min.js','chat-markdown.js'])await page.addScriptTag({path:path.join(__dirname,file)});
  const markdown='## 北京今天（10月2日）\n\n> 正在检索项目知识库 → 找到 3 个片段 → 正在整理回答\n\n**生活指数**：无需带伞，适宜洗车。\n\n| 项目 | 数值 |\n| --- | --- |\n| 天气 | 晴 |\n| 当前气温 | 23 °C |\n\n- 默认优先检索本地资料\n- 明确询问最新信息时搜索网页\n\n```java\nSystem.out.println("Hello");\n```\n\n[官方文档](https://docs.oracle.com/)\n\n[参考资料](knowledge://11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222)';
  await page.evaluate(text=>{document.querySelector('main').innerHTML=DongranMarkdown.render(text,row=>`<button class="knowledge-citation" data-cite-document="${row.id}" data-cite-chunk="${row.chunkId}">${row.name}</button>`);},markdown);
  assert.equal(await page.locator('h2').textContent(),'北京今天（10月2日）');assert.equal(await page.locator('table tbody tr').count(),2);assert.equal(await page.locator('blockquote').count(),1);assert.equal(await page.locator('strong').textContent(),'生活指数');assert.equal(await page.locator('pre code').count(),1);assert.equal(await page.locator('button[data-cite-document]').count(),1);assert.equal(await page.locator('a').getAttribute('rel'),'noopener noreferrer');
  await page.screenshot({path:path.join(__dirname,'../.runtime/markdown-answer.png')});
  await page.evaluate(()=>{document.querySelector('main').innerHTML=DongranMarkdown.render('<script>window.pwned=1</script>\n<img src=x onerror="window.pwned=1">\n\n[bad](javascript:alert(1))\n\n![remote](https://example.com/tracker)\n\n<iframe src="https://example.com"></iframe>');});
  assert.equal(await page.locator('main script,main img,main iframe,main [onerror],main a[href^="javascript:"]').count(),0);assert.equal(await page.evaluate(()=>window.pwned),undefined);
  for(const partial of ['**正在','| 项目 | 数值 |\n|---|---|\n|天气|','```java\n<unfinished'])await page.evaluate(text=>{document.querySelector('main').innerHTML=DongranMarkdown.render(text);},partial);
  console.log('PASS: headings, quotes, GFM tables, emphasis, lists, code, citation attributes, safe links, XSS filtering and incomplete streaming Markdown.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
