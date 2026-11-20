package com.deepseek.local;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置 HTTP 服务：既当静态资源服务器，也把网页用的 Anthropic 协议翻译成上游 OpenAI 协议。
 * 端口固定 8788，页面从 http://127.0.0.1:8788 打开，天然同源，不用处理跨域。
 */
public class LocalServer {

    public static final int PORT = 8788;

    /** 服务商配置，由设置页写入 */
    public static class Config {
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public String mode = "cloud"; // cloud | local
    }

    private final Context context;
    private final Config config;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public LocalServer(Context context, Config config) {
        this.context = context;
        this.config = config;
    }

    public void start() throws IOException {
        server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    final Socket socket = server.accept();
                    new Thread(() -> handle(socket)).start();
                } catch (IOException e) {
                    if (running) {
                        // 端口异常时退出循环，避免空转
                        break;
                    }
                }
            }
        }, "local-server");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------- 请求处理 ----------------

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(300000);
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1].split("\\?")[0];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
                }
            }

            if ("OPTIONS".equals(method)) {
                writeEmpty(out, 204);
                return;
            }

            if ("/models".equals(path)) {
                String model = config.model == null || config.model.isEmpty() ? "local-model" : config.model;
                JSONObject body = new JSONObject();
                JSONArray data = new JSONArray();
                data.put(new JSONObject().put("id", model).put("object", "model"));
                body.put("data", data);
                writeJson(out, 200, body.toString());
                return;
            }

            if ("/anthropic/v1/messages".equals(path) && "POST".equals(method)) {
                String body = readBody(in, headers);
                handleMessages(out, body);
                return;
            }

            if ("/v1/tools/tts".equals(path)) {
                // 朗读由网页里的注入脚本直接交给系统 TTS，这里不需要真的合成
                writeJson(out, 501, "{\"error\":{\"message\":\"tts handled by system\"}}");
                return;
            }

            if (path.startsWith("/v1/tools") || path.startsWith("/anthropic/v1/files")) {
                writeJson(out, 501, "{\"error\":{\"message\":\"not supported\"}}");
                return;
            }

            serveAsset(out, path);
        } catch (Exception e) {
            Log.e(TAG, "handle error: " + e, e);
        }
    }

    private String readBody(BufferedReader in, Map<String, String> headers) throws IOException {
        int len = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (len <= 0) return "{}";
        // 注意：Content-Length 是字节数，BufferedReader 读的是字符，中文会 3 字节 1 字符，
        // 必须按累计字节数判断，否则会一直等下去
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int totalBytes = 0;
        while (totalBytes < len) {
            int n = in.read(buf, 0, Math.min(buf.length, len - totalBytes));
            if (n < 0) break;
            String chunk = new String(buf, 0, n);
            totalBytes += chunk.getBytes(StandardCharsets.UTF_8).length;
            sb.append(chunk);
        }
        return sb.toString();
    }

    // ---------------- 静态资源 ----------------

    private void serveAsset(OutputStream out, String path) throws IOException {
        String file = path.equals("/") ? "index.html" : path.substring(1);
        String mime = mimeOf(file);
        try {
            InputStream is = context.getAssets().open("web/" + file);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            byte[] data = buf.toByteArray();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (IOException e) {
            // 单页应用：找不到的路径回 index.html
            if (!file.contains(".")) {
                serveAsset(out, "/");
                return;
            }
            writeJson(out, 404, "{\"error\":{\"message\":\"not found\"}}");
        }
    }

    private static String mimeOf(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".json")) return "application/json; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".woff2")) return "font/woff2";
        if (f.endsWith(".woff")) return "font/woff";
        if (f.endsWith(".ttf")) return "font/ttf";
        if (f.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private void writeJson(OutputStream out, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + data.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private void writeEmpty(OutputStream out, int code) throws IOException {
        String head = "HTTP/1.1 " + code + " No Content\r\nAccess-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---------------- 协议翻译 ----------------

    private static final String TAG = "DSLocal";

    private void handleMessages(OutputStream out, String bodyText) throws IOException {
        // 每个请求都要重置，否则块序号会跨请求累加，前端直接解析失败
        blockIndex = 0;
        stopReason = "end_turn";
        started = false;
        JSONObject body;
        try {
            body = new JSONObject(bodyText);
        } catch (Exception e) {
            writeJson(out, 400, "{\"error\":{\"message\":\"bad json\"}}");
            return;
        }

        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();

        SseWriter sse = new SseWriter(out);

        if (config.baseUrl == null || config.baseUrl.trim().isEmpty()) {
            sendStart(sse);
            sendText(sse, "还没配置服务商。点右上角设置，选择「云端 API」填写地址和 Key，或选择「本地模型」。");
            sendStop(sse);
            sse.flush();
            return;
        }

        sendStart(sse);

        // 联网搜索：代理自己搜，结果当资料塞给模型（跟电脑端一致）
        String searchCtx = null;
        if (wantsSearch(body)) {
            String raw = lastUserText(body);
            try {
                String q = rewriteQuery(raw);
                if (q.isEmpty()) q = WebSearch.cleanQuery(raw);
                java.util.List<WebSearch.Result> kept = WebSearch.filter(q, WebSearch.search(q));

                // 结果太少就换原始关键词再试一次
                if (kept.size() < 2) {
                    String q2 = WebSearch.cleanQuery(raw);
                    if (!q2.equals(q)) {
                        java.util.List<WebSearch.Result> alt = WebSearch.filter(q2, WebSearch.search(q2));
                        if (alt.size() > kept.size()) {
                            kept = alt;
                            q = q2;
                        }
                    }
                }
                if (!kept.isEmpty()) {
                    searchCtx = WebSearch.format(kept, q);
                    sendSearchCards(sse, q, kept);
                }
            } catch (Exception e) {
                Log.e(TAG, "search failed: " + e, e);
            }
        }

        String url = config.baseUrl.trim();
        if (!url.startsWith("http")) url = "http://" + url;
        if (!url.endsWith("/chat/completions")) url = url.replaceAll("/+$", "") + "/chat/completions";

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (config.apiKey != null && !config.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }
            String payload = buildPayload(body, searchCtx).toString();
            // 必须关闭输出流，否则 getResponseCode() 会一直等请求体结束
            java.io.OutputStream bodyOut = conn.getOutputStream();
            bodyOut.write(payload.getBytes(StandardCharsets.UTF_8));
            bodyOut.close();

            int code = conn.getResponseCode();
            Log.d(TAG, "messages: upstream code=" + code);
            if (code != 200) {
                InputStream es = conn.getErrorStream();
                String err = es == null ? "" : readAll(es);
                sendText(sse, "\n\n⚠️ 上游返回 " + code + "：" + trim(err, 300));
                sendStop(sse);
                sse.flush();
                return;
            }

            streamUpstream(conn.getInputStream(), sse);
        } catch (Exception e) {
            Log.e(TAG, "messages: error " + e, e);
            sendText(sse, "\n\n⚠️ 连不上服务商：" + e.getMessage());
        }

        sendStop(sse);
        sse.flush();
    }

    /** Anthropic 请求体 → OpenAI 请求体 */
    private JSONObject buildPayload(JSONObject body, String searchCtx) {
        boolean localMode = "local".equals(config.mode);
        JSONArray out = new JSONArray();

        if (!localMode) {
            String system = extractSystem(body);
            JSONObject sys = new JSONObject();
            try {
                sys.put("role", "system");
                sys.put("content", (system.isEmpty() ? "" : system + "\n\n") + GUARD);
                out.put(sys);
            } catch (Exception ignored) {
            }
        }

        JSONArray src = body.optJSONArray("messages");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject m = src.optJSONObject(i);
                if (m == null) continue;
                appendMessage(out, m.optString("role", "user"), m.opt("content"));
            }
        }

        // 资料拼在最后一条用户消息末尾：顺序反了模型会当成聊天记录去续写
        if (searchCtx != null && !searchCtx.isEmpty()) {
            for (int i = out.length() - 1; i >= 0; i--) {
                JSONObject m = out.optJSONObject(i);
                if (m == null || !"user".equals(m.optString("role"))) continue;
                try {
                    m.put("content", m.optString("content", "")
                            + "\n\n（以下是刚刚联网搜索到的资料，只作为回答依据；"
                            + "资料里确实没提到的，就说明「资料未提及」并给出你掌握的相关信息，不要编造；"
                            + "用到哪条就在句末标注 [1][2]）\n" + searchCtx);
                } catch (Exception ignored) {
                }
                break;
            }
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("model", config.model == null || config.model.isEmpty() ? "local-model" : config.model);
            payload.put("messages", out);
            payload.put("stream", true);
            payload.put("temperature", 0.7);
            JSONArray tools = convertTools(body.optJSONArray("tools"));
            if (tools != null && tools.length() > 0 && !localMode) {
                payload.put("tools", tools);
                payload.put("tool_choice", "auto");
            }
        } catch (Exception ignored) {
        }
        return payload;
    }

    /** 让模型把口语问题改写成搜索词（与电脑端同一策略，小模型也能用） */
    private String rewriteQuery(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        try {
            String base = config.baseUrl == null ? "" : config.baseUrl.trim();
            if (!base.startsWith("http")) base = "http://" + base;
            String url = base.endsWith("/chat/completions") ? base
                    : base.replaceAll("/+$", "") + "/chat/completions";

            String sys = "你是搜索词改写器。把用户的问题改写成 2-4 个关键词，用空格分隔，例如「GTA6 预载」。"
                    + "去掉「那/请问/帮我/了吗/是什么」这类语气词和疑问结构，只留实体和关键动作；"
                    + "不要加引号、不要解释。";

            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            msgs.put(new JSONObject().put("role", "user")
                    .put("content", t.length() > 300 ? t.substring(0, 300) : t));

            JSONObject p = new JSONObject();
            p.put("model", config.model == null || config.model.isEmpty() ? "local-model" : config.model);
            p.put("messages", msgs);
            p.put("stream", false);
            p.put("temperature", 0);
            p.put("max_tokens", 60);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (config.apiKey != null && !config.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }
            java.io.OutputStream os = conn.getOutputStream();
            os.write(p.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            if (conn.getResponseCode() != 200) return "";

            String resp = readAll(conn.getInputStream());
            JSONObject j = new JSONObject(resp);
            JSONArray ch = j.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return "";
            JSONObject msg = ch.optJSONObject(0).optJSONObject("message");
            if (msg == null) return "";
            String out = msg.optString("content", "");
            // 小模型会把思考过程一起吐出来，先剥掉
            out = out.replaceAll("(?is)<think[^>]*>.*?</think>", " ");
            if (out.contains("</think>")) out = out.substring(out.lastIndexOf("</think>") + 8);
            out = out.replaceAll("(?is)<[^>]+>", " ");
            out = out.replaceAll("[\"'`\n\r]", " ").replaceAll("\\s+", " ").trim();
            if (out.length() > 40) out = out.substring(0, 40);
            // 不像搜索词就用规则清洗兜底，别拿一整段话去搜
            if (out.length() > 24 || out.contains("用户") || out.contains("搜索词")
                    || out.contains("改写") || out.contains("关键词")) {
                return "";
            }
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** 请求里是否带了搜索工具 */
    private boolean wantsSearch(JSONObject body) {
        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) return false;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("type", "") + " " + t.optString("name", "");
            if (id.toLowerCase(Locale.ROOT).contains("search")) return true;
        }
        return false;
    }

    /** 最后一条用户消息的文本，作为搜索词 */
    private String lastUserText(JSONObject body) {
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs == null) return "";
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null || !"user".equals(m.optString("role"))) continue;
            String t = blocksToText(m.opt("content"));
            if (!t.trim().isEmpty() && !isContextNoise(t)) return t.trim();
        }
        return "";
    }

    /** 发「已搜索」卡片，界面会在回答上方显示搜索过程 */
    private void sendSearchCards(SseWriter sse, String query, java.util.List<WebSearch.Result> results) {
        String toolId = "srvtoolu_" + System.currentTimeMillis();
        try {
            JSONObject input = new JSONObject();
            JSONArray queries = new JSONArray();
            queries.put(query);
            input.put("action", "search");
            input.put("queries", queries);

            JSONObject b1 = new JSONObject();
            b1.put("type", "server_tool_use");
            b1.put("id", toolId);
            b1.put("kind", "search");
            b1.put("name", "deepseek_web_search");
            b1.put("input", input);
            sse.emit("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":" + blockIndex
                    + ",\"content_block\":" + b1 + "}\n\n");
            sendBlockStop(sse);

            JSONArray content = new JSONArray();
            for (WebSearch.Result r : results) {
                JSONObject item = new JSONObject();
                item.put("type", "web_search_result");
                item.put("url", r.url);
                item.put("title", r.title);
                item.put("snippet", r.snippet);
                item.put("site_name", JSONObject.NULL);
                item.put("site_icon", JSONObject.NULL);
                item.put("page_age", JSONObject.NULL);
                content.put(item);
            }
            JSONObject b2 = new JSONObject();
            b2.put("type", "web_search_tool_result");
            b2.put("tool_use_id", toolId);
            b2.put("kind", "search");
            b2.put("content", content);
            sse.emit("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":" + blockIndex
                    + ",\"content_block\":" + b2 + "}\n\n");
            sendBlockStop(sse);
        } catch (Exception e) {
            Log.e(TAG, "search cards failed: " + e, e);
        }
    }

    /** 一条 Anthropic 消息 → 若干 OpenAI 消息（文本 / 工具调用 / 工具结果） */
    private void appendMessage(JSONArray out, String role, Object content) {
        boolean assistant = "assistant".equals(role);
        StringBuilder text = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        JSONArray toolResults = new JSONArray();

        if (content instanceof String) {
            text.append((String) content);
        } else if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String type = b.optString("type");
                if ("text".equals(type)) {
                    text.append(b.optString("text", ""));
                } else if ("tool_use".equals(type)) {
                    try {
                        JSONObject fn = new JSONObject();
                        fn.put("name", b.optString("name", ""));
                        JSONObject input = b.optJSONObject("input");
                        fn.put("arguments", input == null ? "{}" : input.toString());
                        JSONObject tc = new JSONObject();
                        tc.put("id", b.optString("id", "call_" + System.nanoTime()));
                        tc.put("type", "function");
                        tc.put("function", fn);
                        toolCalls.put(tc);
                    } catch (Exception ignored) {
                    }
                } else if ("tool_result".equals(type)) {
                    toolResults.put(b);
                }
            }
        }

        String t = text.toString().trim();
        if (isContextNoise(t)) t = "";

        if (assistant) {
            if (t.isEmpty() && toolCalls.length() == 0) return;
            try {
                JSONObject item = new JSONObject();
                item.put("role", "assistant");
                item.put("content", t);
                if (toolCalls.length() > 0) item.put("tool_calls", toolCalls);
                out.put(item);
            } catch (Exception ignored) {
            }
            return;
        }

        if (!t.isEmpty()) {
            try {
                JSONObject item = new JSONObject();
                item.put("role", "user");
                item.put("content", t);
                out.put(item);
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i < toolResults.length(); i++) {
            JSONObject r = toolResults.optJSONObject(i);
            if (r == null) continue;
            try {
                JSONObject item = new JSONObject();
                item.put("role", "tool");
                item.put("tool_call_id", r.optString("tool_use_id", ""));
                item.put("content", blocksToText(r.opt("content")));
                out.put(item);
            } catch (Exception ignored) {
            }
        }
    }

    /** Anthropic tools → OpenAI tools */
    private JSONArray convertTools(JSONArray tools) {
        if (tools == null || tools.length() == 0) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            try {
                JSONObject fn = new JSONObject();
                fn.put("name", t.optString("name", ""));
                fn.put("description", t.optString("description", ""));
                JSONObject schema = t.optJSONObject("input_schema");
                fn.put("parameters", schema == null ? new JSONObject().put("type", "object") : schema);
                JSONObject item = new JSONObject();
                item.put("type", "function");
                item.put("function", fn);
                out.put(item);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 判断是否是前端塞进来的环境信息（日期/平台/语言），不是用户真说的话 */
    private boolean isContextNoise(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 80) return false;
        boolean hasDate = t.matches(".*\\d{4}-\\d{1,2}-\\d{1,2}.*");
        if (!hasDate) return false;
        return t.contains("Web") || t.contains("web") || t.contains("中文")
                || t.contains("Android") || t.contains("iOS") || t.contains("星期");
    }

    private String extractSystem(JSONObject body) {
        Object sys = body.opt("system");
        if (sys instanceof String) return (String) sys;
        return blocksToText(sys);
    }

    private String blocksToText(Object content) {
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return "";
        StringBuilder sb = new StringBuilder();
        JSONArray arr = (JSONArray) content;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            if ("text".equals(b.optString("type"))) {
                sb.append(b.optString("text", ""));
            }
        }
        return sb.toString();
    }

    /** 读上游 SSE，翻译成 Anthropic 事件（含思考、正文、工具调用） */
    private void streamUpstream(InputStream is, SseWriter sse) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        boolean thinkingOpen = false;
        boolean textOpen = false;
        boolean usedTool = false;
        java.util.Set<Integer> toolOpen = new java.util.HashSet<>();

        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (!t.startsWith("data:")) continue;
            String data = t.substring(5).trim();
            if (data.isEmpty()) continue;
            // 上游发完 [DONE] 不一定关连接，收到就收尾，否则会一直等 EOF
            if ("[DONE]".equals(data)) break;

            JSONObject j;
            try {
                j = new JSONObject(data);
            } catch (Exception e) {
                continue;
            }
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) continue;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) continue;

            // ---- 工具调用：OpenAI 的 tool_calls → Anthropic 的 tool_use ----
            JSONArray tcs = delta.optJSONArray("tool_calls");
            if (tcs != null && tcs.length() > 0) {
                if (thinkingOpen) {
                    sendBlockStop(sse);
                    thinkingOpen = false;
                }
                if (textOpen) {
                    sendBlockStop(sse);
                    textOpen = false;
                }
                for (int i = 0; i < tcs.length(); i++) {
                    JSONObject tc = tcs.optJSONObject(i);
                    if (tc == null) continue;
                    int idx = tc.optInt("index", 0);
                    JSONObject fn = tc.optJSONObject("function");
                    if (!toolOpen.contains(idx)) {
                        String id = tc.optString("id", "");
                        if (id.isEmpty()) id = "call_" + System.currentTimeMillis() + "_" + idx;
                        String name = fn == null ? "" : fn.optString("name", "");
                        sendToolStart(sse, id, name);
                        toolOpen.add(idx);
                        usedTool = true;
                    }
                    if (fn != null) {
                        String args = fn.optString("arguments", "");
                        if (!args.isEmpty()) sendToolDelta(sse, args);
                    }
                }
                continue;
            }

            // ---- 思考 ----
            String think = delta.optString("reasoning_content", "");
            if (think.isEmpty()) think = delta.optString("reasoning", "");
            if (!think.isEmpty() && !textOpen) {
                if (!thinkingOpen) {
                    sendBlockStart(sse, "thinking");
                    thinkingOpen = true;
                }
                sendThinking(sse, think);
            }

            // ---- 正文 ----
            String piece = delta.optString("content", "");
            if (!piece.isEmpty()) {
                if (!textOpen) {
                    if (thinkingOpen) {
                        sendBlockStop(sse);
                        thinkingOpen = false;
                    }
                    sendBlockStart(sse, "text");
                    textOpen = true;
                }
                sendText(sse, piece);
            }
        }

        if (thinkingOpen) sendBlockStop(sse);
        if (textOpen) sendBlockStop(sse);
        for (int i = 0; i < toolOpen.size(); i++) sendBlockStop(sse);
        if (!textOpen && toolOpen.isEmpty()) {
            sendBlockStart(sse, "text");
            sendBlockStop(sse);
        }
        stopReason = usedTool ? "tool_use" : "end_turn";
    }

    // ---------------- Anthropic SSE 事件 ----------------

    /** 直接写字节并立即 flush，避免任何缓冲层 */
    private static final class SseWriter {
        private final OutputStream out;

        SseWriter(OutputStream out) {
            this.out = out;
        }

        void emit(String text) {
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
            }
        }

        void flush() {
            try {
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private static final String GUARD = "回答要求：只输出对用户问题的直接回答；不要输出「用户:」「助手:」这类角色标记。";
    private int blockIndex = 0;
    private String stopReason = "end_turn";
    private boolean started = false;

    private void sendStart(SseWriter sse) {
        if (started) return;
        started = true;
        JSONObject msg = new JSONObject();
        try {
            msg.put("id", "msg_" + System.currentTimeMillis());
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.put("model", config.model == null ? "" : config.model);
            msg.put("content", new JSONArray());
            msg.put("stop_reason", JSONObject.NULL);
            msg.put("usage", new JSONObject().put("input_tokens", 0).put("output_tokens", 0));
        } catch (Exception ignored) {
        }
        sse.emit("event: message_start\ndata: {\"type\":\"message_start\",\"message\":" + msg + "}\n\n");
        sse.flush();
    }

    private void sendBlockStart(SseWriter sse, String type) {
        JSONObject block = new JSONObject();
        try {
            if ("thinking".equals(type)) {
                block.put("type", "thinking").put("thinking", "");
            } else {
                block.put("type", "text").put("text", "");
            }
        } catch (Exception ignored) {
        }
        sse.emit("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":" + blockIndex
                + ",\"content_block\":" + block + "}\n\n");
        sse.flush();
    }

    private void sendBlockStop(SseWriter sse) {
        sse.emit("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":" + blockIndex + "}\n\n");
        blockIndex++;
        sse.flush();
    }

    private void sendThinking(SseWriter sse, String text) {
        JSONObject d = new JSONObject();
        try {
            d.put("type", "thinking_delta").put("thinking", text);
        } catch (Exception ignored) {
        }
        sse.emit("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":" + blockIndex
                + ",\"delta\":" + d + "}\n\n");
        sse.flush();
    }

    private void sendText(SseWriter sse, String text) {
        JSONObject d = new JSONObject();
        try {
            d.put("type", "text_delta").put("text", text);
        } catch (Exception ignored) {
        }
        sse.emit("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":" + blockIndex
                + ",\"delta\":" + d + "}\n\n");
        sse.flush();
    }

    private void sendToolStart(SseWriter sse, String id, String name) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "tool_use");
            block.put("id", id);
            block.put("name", name);
            block.put("input", new JSONObject());
        } catch (Exception ignored) {
        }
        sse.emit("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":" + blockIndex
                + ",\"content_block\":" + block + "}\n\n");
    }

    private void sendToolDelta(SseWriter sse, String partialJson) {
        JSONObject d = new JSONObject();
        try {
            d.put("type", "input_json_delta");
            d.put("partial_json", partialJson);
        } catch (Exception ignored) {
        }
        sse.emit("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":" + blockIndex
                + ",\"delta\":" + d + "}\n\n");
    }

    private void sendStop(SseWriter sse) {
        // 块已经在 streamUpstream 里关过了，这里再关会多发一个 content_block_stop，
        // 前端收到索引对不上会直接丢弃整段回复（表现为空白气泡）
        sse.emit("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\","
                + "\"stop_sequence\":null},\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}\n\n");
        sse.emit("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
        sse.flush();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) > 0) bos.write(tmp, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
