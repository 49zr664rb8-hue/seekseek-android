package com.deepseek.local;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索：爬 Bing 结果页。
 * 与电脑端 proxy.js 同一套思路——代理自己搜，把结果当资料塞给模型，
 * 这样任何模型（包括不支持工具调用的端侧小模型）都能用。
 */
public class WebSearch {

    public static class Result {
        public String title = "";
        public String url = "";
        public String snippet = "";
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0 Safari/537.36";

    /** 把口语化的提问压成搜索词：「今天有什么AI新闻」→「AI新闻」 */
    public static String cleanQuery(String text) {
        if (text == null) return "";
        String q = text.replaceAll("\\s+", " ").trim();
        // 去掉时间词和疑问词，这些让搜索引擎抓不到重点
        q = q.replaceAll("(今天|今日|现在|目前|当前|最近|最新|这几天|近期|有什么|有哪些|是什么|怎么样|如何|多少|多少钱|吗|呢|啊|呀|帮我|帮忙|请问|一下|我想知道|想知道|查一下|搜一下)", " ");
        q = q.replaceAll("[（(][^）)]*[）)]", " ");
        q = q.replaceAll("^[\\s，,。、]+", "");
        q = q.replaceAll("[？?！!。，,、~～\\s]+$", "");
        q = q.replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) q = text.trim();
        return q.length() > 60 ? q.substring(0, 60) : q;
    }

    public static List<Result> search(String query) {
        List<Result> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        String q = URLEncoder.encode(query.trim());
        String html = fetch("https://cn.bing.com/search?q=" + q + "&count=8&setlang=zh-CN");
        if (html == null || html.length() < 500) {
            html = fetch("https://www.bing.com/search?q=" + q + "&count=8");
        }
        if (html == null) return out;
        return parse(html);
    }

    private static String fetch(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 Bing 结果页：每条结果在 <li class="b_algo"> 里 */
    private static List<Result> parse(String html) {
        List<Result> list = new ArrayList<>();
        String[] blocks = html.split("<li class=\"b_algo\"");
        for (int i = 1; i < blocks.length && list.size() < 8; i++) {
            String b = blocks[i];
            if (b.length() > 6000) b = b.substring(0, 6000);

            Matcher mLink = Pattern.compile("<h2[^>]*>\\s*<a[^>]+href=\"(http[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(b);
            if (!mLink.find()) continue;
            Result r = new Result();
            r.url = mLink.group(1);
            r.title = clean(mLink.group(2));

            Matcher mSnip = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(b);
            if (mSnip.find()) {
                r.snippet = clean(mSnip.group(1));
            }
            if (r.snippet.isEmpty()) {
                Matcher mCap = Pattern.compile("class=\"b_caption\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(b);
                if (mCap.find()) r.snippet = clean(mCap.group(1));
            }
            if (r.title.isEmpty() || r.url.isEmpty()) continue;
            list.add(r);
        }
        return list;
    }

    private static String clean(String s) {
        String t = s.replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("&#?[a-zA-Z0-9]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    /** 相关性：英文词 + 中文二元组，命中太少就丢掉（挡掉词典页、门户首页、广告） */
    public static boolean isRelevant(String query, Result r) {
        String hay = (r.title + " " + r.snippet).toLowerCase();
        String q = query == null ? "" : query.toLowerCase();

        List<String> latin = new ArrayList<>();
        Matcher lm = Pattern.compile("[a-z0-9]{2,}").matcher(q);
        while (lm.find()) latin.add(lm.group());

        String cn = q.replaceAll("[^\u4e00-\u9fa5]", "");
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i + 1 < cn.length(); i++) bigrams.add(cn.substring(i, i + 2));

        if (latin.isEmpty() && bigrams.isEmpty()) return true;

        int latinHit = 0;
        for (String t : latin) if (hay.contains(t)) latinHit++;
        int cnHit = 0;
        for (String t : bigrams) if (hay.contains(t)) cnHit++;

        // 有英文实体（AI/GPT 这类）时命中一个就够；纯中文要求命中两个词组
        if (!latin.isEmpty()) return latinHit >= 1 || cnHit >= 2;
        return cnHit >= Math.max(1, Math.min(2, bigrams.size()));
    }

    public static List<Result> filter(String query, List<Result> results) {
        List<Result> out = new ArrayList<>();
        for (Result r : results) {
            if (isRelevant(query, r)) out.add(r);
        }
        return out;
    }

    /** 拼成给模型看的资料块 */
    public static String format(List<Result> results, String query) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append("[").append(i + 1).append("] ").append(r.title).append("\n")
                    .append(r.snippet).append("\n来源：").append(r.url).append("\n\n");
        }
        return sb.toString();
    }
}
