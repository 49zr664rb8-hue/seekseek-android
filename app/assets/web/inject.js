/*
 * 页面注入脚本：在官方聊天页加载前执行
 * 1) 把 Android 的 NativeBridge 包装成网页期望的 window.dsBridge（返回 Promise、对象）
 * 2) 拦下朗读请求，改走系统 TTS
 * 3) 写入占位 API Key，避免卡在输入 Key 之前
 */
(function () {
  'use strict';

  var native = window.NativeBridge || null;

  function parse(value, fallback) {
    if (value === null || value === undefined) return fallback;
    if (typeof value === 'object') return value;
    try {
      return JSON.parse(String(value));
    } catch (e) {
      return fallback;
    }
  }

  // 网页用的桥：方法返回 Promise，值已解析成对象
  window.dsBridge = {
    getProvider: function () {
      if (!native) return Promise.resolve({});
      return Promise.resolve(parse(native.getProvider(), {}));
    },
    saveProvider: function (payload) {
      if (!native) return Promise.resolve({ ok: false });
      return Promise.resolve(parse(native.saveProvider(JSON.stringify(payload || {})), { ok: true }));
    },
    listVoices: function () {
      if (!native) return Promise.resolve([]);
      return Promise.resolve(parse(native.listVoices(), []));
    },
    speakText: function (text, voice) {
      if (native) native.speakText(String(text || ''), String(voice || ''));
    },
    stopSpeaking: function () {
      if (native) native.stopSpeaking();
    },
    toast: function (msg) {
      try { if (native && native.toast) native.toast(String(msg || '')); } catch (e) {}
    },
    preview: function () {
      /* 手机端不做代码块预览 */
    }
  };

  // 不再预置占位 Key：首次打开就让用户填服务商（地址 / 模型 / Key）

  // 语音输入：网页是「录完音上传到 /v1/tools/asr」，但安卓没有把音频文件转文字的公开接口，
  // 所以改成边说边用系统识别：按下麦克风时启动，松手时把识别结果直接当 ASR 返回。
  var speechPromise = null;
  var speechResolve = null;

  window.__dsSpeech = function (text) {
    if (speechResolve) {
      var done = speechResolve;
      speechResolve = null;
      speechPromise = null;
      done(text || '');
    }
  };

  function startSpeech() {
    if (speechPromise) return speechPromise;
    speechPromise = new Promise(function (resolve) {
      speechResolve = resolve;
      try {
        if (native && native.startSpeech) native.startSpeech();
        else resolve('');
      } catch (e) {
        resolve('');
      }
      // 兜底：20 秒还没结果就当没听清
      setTimeout(function () {
        if (speechResolve === resolve) {
          speechResolve = null;
          speechPromise = null;
          resolve('');
        }
      }, 20000);
    });
    return speechPromise;
  }

  if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    var originalGum = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = function (constraints) {
      startSpeech();
      return originalGum(constraints);
    };
  }

  // 导出对话：网页用 <a download> + blob 触发下载，WebView 不支持，
  // 这里改成读出 blob 交给原生保存到「下载」目录。
  try {
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try {
        var name = this.getAttribute && this.getAttribute('download');
        var href = this.href || '';
        if (name && href.indexOf('blob:') === 0) {
          fetch(href)
            .then(function (r) { return r.blob(); })
            .then(function (b) {
              var fr = new FileReader();
              fr.onload = function () {
                var b64 = String(fr.result).split(',')[1] || '';
                var where = native && native.saveFile ? native.saveFile(name, b64) : '';
                if (where && window.dsBridge && window.dsBridge.toast) {
                  window.dsBridge.toast('已保存到 ' + where);
                }
              };
              fr.readAsDataURL(b);
            })
            .catch(function () {});
          return;
        }
      } catch (e) {}
      return origAnchorClick.apply(this, arguments);
    };
  } catch (e) {}

  // 朗读请求 → 系统 TTS（不生成音频文件）
  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if (url.indexOf('/v1/tools/asr') !== -1) {
        return startSpeech().then(function (text) {
          return new Response(JSON.stringify({ text: text }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        });
      }
      if (url.indexOf('/v1/tools/tts') !== -1) {
        try {
          var body = init && init.body ? JSON.parse(init.body) : {};
          var text = body.text || '';
          var voice = body.voice_id || '';
          if (window.dsBridge && window.dsBridge.speakText) {
            window.dsBridge.speakText(text, voice);
          }
        } catch (e) {}
        return Promise.resolve(new Response(new Uint8Array(0), { status: 200 }));
      }
      return originalFetch.apply(this, arguments);
    };
  }
})();

/* 文件上传补丁：官方前端把附件 POST 到 /anthropic/v1/files，后端拿不到真实文件，
   这里在页面里直接接管：文本文件读成文本，图片读成 base64，
   发消息时把 document / image 块换成模型能直接吃的内容。 */
(function () {
  if (window.__dsFilePatch) return;
  window.__dsFilePatch = true;

  var store = {};

  function isImage(mime, name) {
    if (mime && /^image\//i.test(mime)) return true;
    return /\.(png|jpe?g|gif|webp|bmp)$/i.test(name || '');
  }

  function isTextLike(name, mime) {
    if (mime && /^text\//i.test(mime)) return true;
    if (/json|xml|csv|javascript/i.test(mime || '')) return true;
    return /\.(txt|md|markdown|json|csv|tsv|log|js|ts|jsx|tsx|py|java|c|h|cpp|cs|go|rs|rb|php|swift|kt|html|css|scss|xml|yml|yaml|ini|conf|cfg|sh|bat|sql)$/i.test(name || '');
  }

  function readAsDataURL(file) {
    return new Promise(function (resolve) {
      var fr = new FileReader();
      fr.onload = function () { resolve(String(fr.result || '')); };
      fr.onerror = function () { resolve(''); };
      fr.readAsDataURL(file);
    });
  }

  var origFetch = window.fetch;
  if (!origFetch) return;

  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var body = init && init.body;
    var looksLikeForm = body && typeof body.get === 'function' && typeof body.append === 'function';

    // ---- 上传：文本读文本，图片读 base64，其余只记名字 ----
    if (url.indexOf('/anthropic/v1/files') !== -1 && looksLikeForm) {
      var f = body.get('file');
      if (!f) return origFetch.apply(this, arguments);
      var id = 'file_' + Date.now() + '_' + Math.floor(Math.random() * 10000);
      var reading;
      if (isImage(f.type, f.name)) {
        reading = readAsDataURL(f).then(function (dataUrl) {
          store[id] = { kind: 'image', name: f.name, mime: f.type || 'image/png', dataUrl: dataUrl };
        });
      } else if (isTextLike(f.name, f.type) && typeof f.text === 'function') {
        reading = f.text().then(function (t) {
          store[id] = { kind: 'text', name: f.name, text: t };
        });
      } else {
        reading = Promise.resolve().then(function () {
          store[id] = { kind: 'other', name: f.name };
        });
      }
      return reading.then(function () {
        return new Response(JSON.stringify({
          id: id,
          type: 'file',
          filename: f.name,
          mime_type: f.type || 'application/octet-stream',
          size: f.size || 0,
          created_at: Math.floor(Date.now() / 1000)
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      });
    }

    // ---- 发送消息：把引用文件的块换成模型能直接用的内容 ----
    if (url.indexOf('/anthropic/v1/messages') !== -1 && typeof body === 'string') {
      try {
        var parsed = JSON.parse(body);
        if (parsed && Array.isArray(parsed.messages)) {
          parsed.messages.forEach(function (m) {
            if (!m || !Array.isArray(m.content)) return;
            var out = [];
            m.content.forEach(function (b) {
              var fid = b && b.source && b.source.type === 'file' ? b.source.file_id : null;
              var rec = fid ? store[fid] : null;
              if (!rec) {
                out.push(b);
                return;
              }
              if (rec.kind === 'image' && rec.dataUrl) {
                var comma = rec.dataUrl.indexOf(',');
                out.push({
                  type: 'image',
                  source: {
                    type: 'base64',
                    media_type: rec.mime,
                    data: comma >= 0 ? rec.dataUrl.slice(comma + 1) : ''
                  }
                });
                return;
              }
              if (rec.kind === 'text') {
                out.push({ type: 'text', text: '【文件 ' + rec.name + ' 的内容】\n' + rec.text });
                return;
              }
              out.push({ type: 'text', text: '【用户上传了文件 ' + rec.name + '，当前无法解析该格式（仅支持文本和图片）】' });
            });
            m.content = out;
          });
          init = Object.assign({}, init, { body: JSON.stringify(parsed) });
        }
      } catch (e) { /* 解析失败就原样发出去 */ }
    }

    return origFetch.call(this, input, init);
  };
})();
