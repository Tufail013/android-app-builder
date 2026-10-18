/* ===== templates ===== */
const H=(t,b)=>`<!DOCTYPE html>\n<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${t}</title></head>\n<body style="font-family:sans-serif;padding:16px">\n${b}\n</body></html>`;
const TPL={blank:{n:'Blank',h:H('My App','<h1>Hello</h1>')},
welcome:{n:'Welcome page',h:H('Welcome','<h1>Welcome</h1><p>Edit this page, then continue the wizard.</p><a href="#more">More</a><p id="more">Hidden section.</p>')},
counter:{n:'Counter app',h:H('Counter','<h1 id="n">0</h1><button onclick="n.textContent=+n.textContent+1" style="font-size:24px;padding:12px 24px">+1</button>')}};
