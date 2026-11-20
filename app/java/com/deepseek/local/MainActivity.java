package com.deepseek.local;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * 主界面：用 WebView 装官方聊天页，页面从本地服务加载（同源，免跨域）。
 * 服务商配置存 SharedPreferences，朗读走 Android 系统 TTS。
 */
public class MainActivity extends android.app.Activity {

    private WebView web;
    private LocalServer server;
    private LocalServer.Config config = new LocalServer.Config();
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        loadConfig();

        setupRecognizer();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        }

        try {
            server = new LocalServer(this, config);
            server.start();
        } catch (Exception e) {
            // 端口占用等异常：仍然尝试打开页面，由页面提示错误
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient());

        // 网页里的语音输入会调 getUserMedia，WebView 默认会拒绝，
        // 这里接管权限请求：只放行麦克风，其它一律拒绝
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // 网页的「导入历史对话」用 <input type=file>，WebView 必须自己接管
                fileCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择文件"), 2001);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) wantsAudio = true;
                    }
                    if (wantsAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });
        // 名字用 NativeBridge：网页里的 window.dsBridge 由注入脚本用 JS 包装（返回 Promise）
        web.addJavascriptInterface(new Bridge(), "NativeBridge");

        web.loadUrl("http://127.0.0.1:" + LocalServer.PORT + "/");

        initTts();
    }

    // ---------------- 配置 ----------------

    private SharedPreferences prefs() {
        return getSharedPreferences("deepseek_local", MODE_PRIVATE);
    }

    private void loadConfig() {
        SharedPreferences p = prefs();
        config.baseUrl = p.getString("baseUrl", "");
        config.apiKey = p.getString("apiKey", "");
        config.model = p.getString("model", "");
        config.mode = p.getString("mode", "cloud");
    }

    private void saveConfig(String baseUrl, String apiKey, String model, String mode) {
        prefs().edit()
                .putString("baseUrl", baseUrl)
                .putString("apiKey", apiKey)
                .putString("model", model)
                .putString("mode", mode)
                .apply();
        config.baseUrl = baseUrl;
        config.apiKey = apiKey;
        config.model = model;
        config.mode = mode;
    }

    // ---------------- 朗读 ----------------

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                try {
                    tts.setLanguage(Locale.CHINA);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void speak(String text, String voiceName) {
        if (!ttsReady || text == null || text.trim().isEmpty()) return;
        try {
            // 只有一个音色，直接让系统用默认的，不做匹配
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ds-utterance");
        } catch (Exception ignored) {
        }
    }

    /** 系统语音识别：网页录音时同时开一份，松手后把结果当 ASR 文本返回 */
    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        mainHandler.post(() -> {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onError(int error) { sendSpeechResult(""); }
                @Override public void onResults(Bundle results) {
                    java.util.ArrayList<String> list =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    sendSpeechResult(list != null && !list.isEmpty() ? list.get(0) : "");
                }
            });
        });
    }

    private void sendSpeechResult(String text) {
        try {
            String js = "window.__dsSpeech && window.__dsSpeech(" + JSONObject.quote(text) + ")";
            web.post(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void startSpeech() {
        if (recognizer == null) {
            sendSpeechResult("");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendSpeechResult("");
            return;
        }
        mainHandler.post(() -> {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            try {
                recognizer.startListening(i);
            } catch (Exception e) {
                sendSpeechResult("");
            }
        });
    }

    @JavascriptInterface
    public void stopSpeech() {
        if (recognizer != null) mainHandler.post(() -> {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 2001) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 网页导出对话时把 zip 交过来保存：Android 10+ 走 MediaStore 落到「下载」 */
    @JavascriptInterface
    public String saveFile(String name, String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            String safe = name == null || name.isEmpty() ? "deepseek_data.zip" : name;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "";
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return "";
                os.write(bytes);
                os.close();
                return "下载/" + safe;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, safe);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(bytes);
            fos.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, String.valueOf(msg), Toast.LENGTH_LONG).show());
    }

    private String voicesJson() {
        // 安卓的 TTS 引擎通常只装了一个中文音色，列一堆名字只会让人以为能换。
        // 这里固定返回一项「TTS」，实际朗读走系统默认音色。
        JSONArray arr = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("name", "TTS");
            o.put("language", "zh-CN");
            o.put("gender", "");
            arr.put(o);
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    // ---------------- 给网页的桥接 ----------------

    private class Bridge {

        // 这些实现在 Activity 上，桥接对象里转发一层，网页才调得到
        @JavascriptInterface
        public void startSpeech() {
            MainActivity.this.startSpeech();
        }

        @JavascriptInterface
        public void stopSpeech() {
            MainActivity.this.stopSpeech();
        }

        @JavascriptInterface
        public String saveFile(String name, String base64) {
            return MainActivity.this.saveFile(name, base64);
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }
        @JavascriptInterface
        public String getProvider() {
            JSONObject o = new JSONObject();
            try {
                o.put("baseUrl", config.baseUrl);
                o.put("model", config.model);
                o.put("mode", config.mode);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public String saveProvider(String json) {
            try {
                JSONObject o = new JSONObject(json == null ? "{}" : json);
                saveConfig(
                        o.optString("baseUrl", ""),
                        o.optString("apiKey", ""),
                        o.optString("model", ""),
                        o.optString("mode", config.mode)
                );
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public String listVoices() {
            return voicesJson();
        }

        /** 网页拦下朗读请求后调用这里，直接用系统 TTS 播报 */
        @JavascriptInterface
        public void speakText(String text, String voice) {
            runOnUiThread(() -> speak(text, voice));
        }

        @JavascriptInterface
        public void stopSpeaking() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
        }
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {
        }
        try {
            if (server != null) server.stop();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
