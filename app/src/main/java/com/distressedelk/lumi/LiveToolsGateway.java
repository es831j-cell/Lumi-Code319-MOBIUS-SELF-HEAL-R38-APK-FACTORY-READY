package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Lumi Live Tools Gateway v3 (crash-proofing).
 *
 * Core code owns the safe network executor. ZIP updates may replace the registry at:
 *   files/lumi_updates/modules/skills/live-tools.json
 *
 * The registry can change provider order, endpoints, response paths and reply templates
 * without changing the APK. Only HTTPS providers on an explicit allow-list are executed.
 */
public final class LiveToolsGateway {
    public interface Callback {
        void onSuccess(Result result);
        void onFailure(Match match, String diagnostic);
    }

    public static final class Match {
        public final String toolId;
        public final String displayName;
        public final String argument;
        public final JSONObject tool;
        Match(String toolId, String displayName, String argument, JSONObject tool) {
            this.toolId = toolId;
            this.displayName = displayName;
            this.argument = argument;
            this.tool = tool;
        }
    }

    public static final class Result {
        public final Match match;
        public final String reply;
        public final String providerId;
        public final long retrievedAt;
        Result(Match match, String reply, String providerId, long retrievedAt) {
            this.match = match;
            this.reply = reply;
            this.providerId = providerId;
            this.retrievedAt = retrievedAt;
        }
    }

    private static final String OVERRIDE_PATH = "lumi_updates/modules/skills/live-tools.json";
    private static final String ASSET_NAME = "lumi-live-tools.json";
    private static final Pattern SAFE_TICKER = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private LiveToolsGateway() {}

    public static Match match(Context context, String query) {
        if (query == null || query.trim().isEmpty()) return null;
        try {
            JSONObject registry = loadRegistry(context);
            JSONArray tools = registry.optJSONArray("tools");
            if (tools == null) return null;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null || !tool.optBoolean("enabled", true)) continue;
                JSONArray patterns = tool.optJSONArray("patterns");
                if (patterns == null) continue;
                for (int p = 0; p < patterns.length(); p++) {
                    String regex = patterns.optString(p, "");
                    if (regex.isEmpty()) continue;
                    Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(query.trim());
                    if (!m.find()) continue;
                    String arg = m.groupCount() >= 1 ? m.group(1).trim() : "";
                    String extractor = tool.optString("extractor", "raw");
                    if ("ticker".equals(extractor)) {
                        arg = normalizeTicker(arg);
                        if (!SAFE_TICKER.matcher(arg).matches()) continue;
                    }
                    return new Match(tool.optString("id", "live-tool"),
                            tool.optString("name", "Live data"), arg, tool);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Code296 market symbol normalization.
     * Speech frequently supplies a company name ("Reddit") rather than an exchange ticker ("RDDT").
     * Keep a compact local alias table for common names and preserve explicit ticker symbols.
     */
    private static String normalizeTicker(String raw) {
        String x = raw == null ? "" : raw.replace("$", "").trim().toUpperCase(Locale.US);
        if (x.isEmpty()) return x;
        String key = x.replaceAll("[^A-Z0-9]", "");
        if ("REDDIT".equals(key)) return "RDDT";
        if ("APPLE".equals(key)) return "AAPL";
        if ("MICROSOFT".equals(key)) return "MSFT";
        if ("AMAZON".equals(key)) return "AMZN";
        if ("GOOGLE".equals(key) || "ALPHABET".equals(key)) return "GOOGL";
        if ("META".equals(key) || "FACEBOOK".equals(key)) return "META";
        if ("TESLA".equals(key)) return "TSLA";
        if ("NVIDIA".equals(key)) return "NVDA";
        if ("NETFLIX".equals(key)) return "NFLX";
        if ("WALMART".equals(key)) return "WMT";
        if ("DISNEY".equals(key)) return "DIS";
        if ("FORD".equals(key)) return "F";
        return x;
    }

    /**
     * Code292 weather bootstrap: when the user asks for weather without naming a place and
     * Android has no cached location fix, use wttr.in's coarse network location resolution.
     * This keeps weather on a dedicated live-data provider and avoids a model round-trip.
     */
    public static Match autoWeather(Context context, boolean forecast) {
        try {
            JSONObject registry=loadRegistry(context);
            JSONArray tools=registry.optJSONArray("tools");
            if(tools==null) return null;
            String wanted=forecast?"weather_forecast":"weather_current";
            for(int i=0;i<tools.length();i++){
                JSONObject original=tools.optJSONObject(i);
                if(original==null || !wanted.equals(original.optString("id"))) continue;
                JSONObject tool=new JSONObject(original.toString());
                JSONArray providers=tool.optJSONArray("providers");
                if(providers==null) return null;
                for(int p=0;p<providers.length();p++){
                    JSONObject provider=providers.optJSONObject(p);
                    if(provider==null) continue;
                    String id=provider.optString("id","");
                    if(id.startsWith("wttr-")){
                        provider.put("url","https://wttr.in/?format=j1");
                        if(forecast) provider.put("replyTemplate","Tomorrow near your current location: high {value}°F, low {exchange}°F, with {state}.");
                        else provider.put("replyTemplate","Current weather near your current location: {value}°F and {state}. Feels like {exchange}°F.");
                    }
                }
                return new Match(wanted,tool.optString("name","Weather"),"your current location",tool);
            }
        } catch(Throwable ignored) {}
        return null;
    }

    public static void execute(Context context, SharedPreferences prefs, Match match, Callback callback) {
        Thread worker = new Thread(() -> {
            StringBuilder failures = new StringBuilder();
            try {
                if (match == null || match.tool == null) {
                    safeFailure(callback, match, "invalid live-tool request");
                    return;
                }
                JSONObject registry = loadRegistry(context);
                Set<String> allowedHosts = allowedHosts(registry);
                JSONArray providers = match.tool.optJSONArray("providers");
                if (providers == null || providers.length() == 0)
                    throw new IllegalStateException("no providers configured");
                for (int i = 0; i < providers.length(); i++) {
                    JSONObject provider = providers.optJSONObject(i);
                    if (provider == null || !provider.optBoolean("enabled", true)) continue;
                    String providerId = provider.optString("id", "provider-" + i);
                    try {
                        String reply = null;
                        Throwable last = null;
                        for (int attempt = 1; attempt <= 2; attempt++) {
                            try {
                                reply = executeProvider(match, provider, allowedHosts);
                                last = null;
                                break;
                            } catch (Throwable transientFailure) {
                                last = transientFailure;
                                if (attempt < 2) {
                                    try { Thread.sleep(350L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                            }
                        }
                        if (reply == null && last != null) throw new IllegalStateException("retry exhausted: " + safeThrowable(last));
                        if (reply != null && !reply.trim().isEmpty()) {
                            if (prefs != null) {
                                try {
                                    prefs.edit()
                                            .putString("last_live_tool", match.toolId)
                                            .putString("last_live_provider", providerId)
                                            .putLong("last_live_tool_at", System.currentTimeMillis())
                                            .remove("last_live_tool_error")
                                            .apply();
                                } catch (Throwable ignored) {}
                            }
                            safeSuccess(callback, new Result(match, bounded(reply, 5000), providerId, System.currentTimeMillis()));
                            return;
                        }
                    } catch (Throwable t) {
                        appendFailure(failures, providerId, t);
                    }
                }
            } catch (Throwable t) {
                appendFailure(failures, "gateway", t);
            }
            String diagnostic = bounded(failures.toString(), 3500);
            if (prefs != null) {
                try {
                    prefs.edit()
                        .putString("last_live_tool_error", diagnostic)
                        .putLong("last_live_tool_error_at", System.currentTimeMillis())
                        .apply();
                } catch (Throwable ignored) {}
            }
            safeFailure(callback, match, diagnostic);
        }, "LumiLiveTool-" + (match == null ? "unknown" : match.toolId));
        worker.setUncaughtExceptionHandler((thread, throwable) -> {
            String diagnostic = "uncaught " + safeThrowable(throwable);
            if (prefs != null) {
                try {
                    prefs.edit()
                        .putString("last_live_tool_error", diagnostic)
                        .putLong("last_live_tool_error_at", System.currentTimeMillis())
                        .apply();
                } catch (Throwable ignored) {}
            }
            safeFailure(callback, match, diagnostic);
        });
        try {
            worker.start();
        } catch (Throwable t) {
            safeFailure(callback, match, "worker start failed: " + safeThrowable(t));
        }
    }

    private static void safeSuccess(Callback callback, Result result) {
        if (callback == null) return;
        try { callback.onSuccess(result); } catch (Throwable ignored) {}
    }

    private static void safeFailure(Callback callback, Match match, String diagnostic) {
        if (callback == null) return;
        try { callback.onFailure(match, bounded(diagnostic, 3500)); } catch (Throwable ignored) {}
    }

    private static void appendFailure(StringBuilder failures, String providerId, Throwable t) {
        try {
            if (failures.length() > 0) failures.append(" | ");
            failures.append(providerId).append(": ").append(safeThrowable(t));
            if (failures.length() > 3500) failures.setLength(3500);
        } catch (Throwable ignored) {}
    }

    private static String safeThrowable(Throwable t) {
        if (t == null) return "unknown failure";
        try {
            String name = t.getClass().getSimpleName();
            String message = t.getMessage();
            return bounded(name + (message == null || message.trim().isEmpty() ? "" : ": " + message), 800);
        } catch (Throwable ignored) { return "unreportable failure"; }
    }

    private static String executeProvider(Match match, JSONObject provider, Set<String> allowedHosts) throws Exception {
        String type = provider.optString("type", "json");
        String urlTemplate = provider.getString("url");
        String encoded = URLEncoder.encode(match.argument, "UTF-8");
        String urlText = urlTemplate.replace("{arg}", encoded).replace("{ticker}", encoded);
        URL url = new URL(urlText);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("HTTPS required");
        String host = url.getHost().toLowerCase(Locale.US);
        if (!allowedHosts.contains(host)) throw new SecurityException("host not allowed: " + host);

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(clamp(provider.optInt("connectTimeoutMs", 7000), 1500, 15000));
            c.setReadTimeout(clamp(provider.optInt("readTimeoutMs", 9000), 2000, 20000));
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", provider.optString("userAgent", "Mozilla/5.0 (Linux; Android 16) Lumi/3.6 LiveTools/3"));
            c.setRequestProperty("Accept", provider.optString("accept", "application/json,application/rss+xml,application/xml,text/xml,text/csv,text/plain;q=0.9,*/*;q=0.5"));
            JSONObject headers = provider.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = headers.optString(k, "");
                    if (!k.trim().isEmpty() && !v.contains("\r") && !v.contains("\n")) c.setRequestProperty(k, v);
                }
            }
            int code = c.getResponseCode();
            URL finalUrl = c.getURL();
            String finalHost = finalUrl == null ? host : finalUrl.getHost().toLowerCase(Locale.US);
            if (!allowedHosts.contains(finalHost)) throw new SecurityException("redirect host not allowed: " + finalHost);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            String raw = readAll(c.getInputStream());
            if (raw.trim().isEmpty()) throw new IllegalStateException("empty response");
            if ("json".equalsIgnoreCase(type)) return parseJsonReply(match, provider, raw);
            if ("csv".equalsIgnoreCase(type)) return parseCsvReply(match, provider, raw);
            if ("rss".equalsIgnoreCase(type) || "xml".equalsIgnoreCase(type)) return parseRssReply(match, provider, raw);
            throw new IllegalStateException("unsupported provider type " + type);
        } finally {
            c.disconnect();
        }
    }

    private static String parseJsonReply(Match match, JSONObject provider, String raw) throws Exception {
        String body = raw == null ? "" : raw.trim();
        if (body.startsWith("\uFEFF")) body = body.substring(1).trim();
        Object root = body.startsWith("[") ? new JSONArray(body) : new JSONObject(body);

        String itemsPath = provider.optString("itemsPath", "").trim();
        if (!itemsPath.isEmpty()) {
            Object itemsObj = resolvePath(root, itemsPath);
            if (!(itemsObj instanceof JSONArray)) throw new IllegalStateException("items path is not an array");
            JSONArray items = (JSONArray) itemsObj;
            int maxItems = clamp(provider.optInt("maxItems", 3), 1, 5);
            String valuePath = provider.optString("itemValuePath", "title");
            String sourcePath = provider.optString("itemSourcePath", "");
            String timePath = provider.optString("itemTimePath", "");
            StringBuilder list = new StringBuilder();
            int emitted = 0;
            for (int i = 0; i < items.length() && emitted < maxItems; i++) {
                Object item = items.opt(i);
                if (!(item instanceof JSONObject) && !(item instanceof JSONArray)) continue;
                String value = optionalPath(item, valuePath);
                if (value.trim().isEmpty()) continue;
                String source = optionalPath(item, sourcePath);
                String time = optionalPath(item, timePath);
                if (list.length() > 0) list.append(" ");
                emitted++;
                list.append(emitted).append(") ").append(cleanText(value));
                if (!source.trim().isEmpty()) list.append(" — ").append(cleanText(source));
                if (!time.trim().isEmpty() && emitted == 1) list.append(" (").append(cleanText(formatEpoch(time))).append(")");
                list.append(".");
            }
            if (emitted == 0) throw new IllegalStateException("no usable results");
            String template = provider.optString("replyTemplate", "Results for {arg}: {value}");
            return fill(template, match.argument, list.toString(), "", "", "", "");
        }

        Object value = resolvePath(root, provider.getString("valuePath"));
        if (value == null || value == JSONObject.NULL || String.valueOf(value).trim().isEmpty())
            throw new IllegalStateException("value missing");
        String valueText = cleanText(String.valueOf(value));
        String currency = optionalPath(root, provider.optString("currencyPath", ""));
        String state = cleanText(optionalPath(root, provider.optString("statePath", "")));
        String exchange = cleanText(optionalPath(root, provider.optString("exchangePath", "")));
        String epochText = optionalPath(root, provider.optString("timePath", ""));
        String time = formatEpoch(epochText);
        String template = provider.optString("replyTemplate", "{arg}: {value}");
        return fill(template, match.argument, valueText, currency, state, exchange, time);
    }


    private static String parseRssReply(Match match, JSONObject provider, String raw) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(raw));
        int maxItems = clamp(provider.optInt("maxItems", 3), 1, 5);
        StringBuilder out = new StringBuilder();
        String title = "", link = "", pubDate = "", source = "";
        boolean inItem = false;
        int count = 0;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT && count < maxItems) {
            if (event == XmlPullParser.START_TAG) {
                String n = parser.getName();
                if ("item".equalsIgnoreCase(n) || "entry".equalsIgnoreCase(n)) {
                    inItem = true; title = link = pubDate = source = "";
                } else if (inItem && "title".equalsIgnoreCase(n)) {
                    title = parser.nextText();
                } else if (inItem && ("pubDate".equalsIgnoreCase(n) || "published".equalsIgnoreCase(n) || "updated".equalsIgnoreCase(n))) {
                    pubDate = parser.nextText();
                } else if (inItem && "source".equalsIgnoreCase(n)) {
                    source = parser.nextText();
                } else if (inItem && "link".equalsIgnoreCase(n)) {
                    String href = parser.getAttributeValue(null, "href");
                    link = href != null ? href : parser.nextText();
                }
            } else if (event == XmlPullParser.END_TAG && ("item".equalsIgnoreCase(parser.getName()) || "entry".equalsIgnoreCase(parser.getName()))) {
                inItem = false;
                if (!title.trim().isEmpty()) {
                    if (out.length() > 0) out.append(" ");
                    count++;
                    out.append(count).append(") ").append(cleanText(title));
                    if (!source.trim().isEmpty()) out.append(" — ").append(cleanText(source));
                    if (!pubDate.trim().isEmpty() && count == 1) out.append(" (").append(cleanText(pubDate)).append(")");
                    out.append(".");
                }
            }
            event = parser.next();
        }
        if (count == 0) throw new IllegalStateException("RSS contained no items");
        String intro = provider.optString("replyTemplate", "Latest news I found about {arg}: {value}");
        return fill(intro, match.argument, out.toString(), "", "", "", "");
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        String x = bounded(s, 12000);
        try {
            x = x.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">");
            // Avoid regex-based HTML stripping here. Large or malformed provider text can
            // trigger deep regex recursion on Android. This small state machine is bounded.
            StringBuilder out = new StringBuilder(Math.min(x.length(), 12000));
            boolean inTag = false;
            for (int i = 0; i < x.length() && out.length() < 12000; i++) {
                char ch = x.charAt(i);
                if (ch == '<') { inTag = true; continue; }
                if (ch == '>' && inTag) { inTag = false; continue; }
                if (!inTag) out.append(ch);
            }
            x = out.toString();
            StringBuilder compact = new StringBuilder(x.length());
            boolean lastSpace = false;
            for (int i = 0; i < x.length(); i++) {
                char ch = x.charAt(i);
                boolean ws = Character.isWhitespace(ch);
                if (ws) {
                    if (!lastSpace) compact.append(' ');
                } else compact.append(ch);
                lastSpace = ws;
            }
            return bounded(compact.toString().trim(), 8000);
        } catch (Throwable ignored) {
            return bounded(x.trim(), 4000);
        }
    }

    private static String bounded(String s, int max) {
        if (s == null) return "";
        if (max < 0) max = 0;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String parseCsvReply(Match match, JSONObject provider, String raw) throws Exception {
        String[] lines = raw.trim().split("\\r?\\n");
        int row = provider.optInt("row", 1);
        int valueColumn = provider.optInt("valueColumn", 6);
        if (row < 0 || row >= lines.length) throw new IllegalStateException("CSV row missing");
        String[] cols = lines[row].split(",", -1);
        if (valueColumn < 0 || valueColumn >= cols.length) throw new IllegalStateException("CSV value missing");
        String value = stripCsv(cols[valueColumn]);
        if (value.isEmpty() || "N/D".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value))
            throw new IllegalStateException("quote unavailable");
        String date = col(cols, provider.optInt("dateColumn", -1));
        String time = col(cols, provider.optInt("timeColumn", -1));
        String when = (date + " " + time).trim();
        String template = provider.optString("replyTemplate", "{arg}: {value}");
        return fill(template, match.argument, value, provider.optString("currency", "USD"), "", provider.optString("exchange", ""), when);
    }

    private static String fill(String template, String arg, String value, String currency, String state, String exchange, String time) {
        String symbol = ("USD".equalsIgnoreCase(currency) || currency.isEmpty()) ? "$" : currency + " ";
        return template
                .replace("{arg}", arg)
                .replace("{value}", value)
                .replace("{currency}", currency)
                .replace("{money}", symbol + value)
                .replace("{state}", state)
                .replace("{exchange}", exchange)
                .replace("{time}", time)
                .replaceAll("\\s+([,.])", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static Object resolvePath(Object current, String path) throws Exception {
        if (path == null || path.trim().isEmpty()) return current;
        String[] parts = path.split("\\.");
        Object cur = current;
        for (String part : parts) {
            if (cur instanceof JSONObject) cur = ((JSONObject) cur).get(part);
            else if (cur instanceof JSONArray) cur = ((JSONArray) cur).get(Integer.parseInt(part));
            else throw new IllegalStateException("path stopped at " + part);
        }
        return cur;
    }

    private static String optionalPath(Object root, String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            Object v = resolvePath(root, path);
            return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
        } catch (Exception ignored) { return ""; }
    }

    private static String formatEpoch(String epochText) {
        if (epochText == null || epochText.trim().isEmpty()) return "";
        try {
            long seconds = Long.parseLong(epochText.trim());
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            return f.format(new Date(seconds * 1000L));
        } catch (Exception ignored) { return epochText; }
    }

    private static JSONObject loadRegistry(Context context) throws Exception {
        File override = new File(context.getFilesDir(), OVERRIDE_PATH);
        if (override.isFile()) {
            try (InputStream in = new FileInputStream(override)) {
                JSONObject parsed = new JSONObject(readAll(in));
                if (parsed.optInt("schema", 0) == 1) return parsed;
            } catch (Throwable ignored) {
                // A malformed or pathological ZIP override must not disable the built-in live tools.
            }
        }
        try (InputStream in = context.getAssets().open(ASSET_NAME)) {
            return new JSONObject(readAll(in));
        }
    }

    private static Set<String> allowedHosts(JSONObject registry) {
        Set<String> out = new HashSet<>();
        JSONArray arr = registry.optJSONArray("allowedHosts");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String h = arr.optString(i, "").trim().toLowerCase(Locale.US);
            if (!h.isEmpty()) out.add(h);
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (b.length() > 0) b.append('\n');
                b.append(line);
                if (b.length() > 2_000_000) throw new IllegalStateException("response too large");
            }
        }
        return b.toString();
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String stripCsv(String s) {
        String x = s == null ? "" : s.trim();
        if (x.length() >= 2 && x.startsWith("\"") && x.endsWith("\"")) x = x.substring(1, x.length() - 1);
        return x.replace("\"\"", "\"").trim();
    }
    private static String col(String[] cols, int i) { return i >= 0 && i < cols.length ? stripCsv(cols[i]) : ""; }
}
