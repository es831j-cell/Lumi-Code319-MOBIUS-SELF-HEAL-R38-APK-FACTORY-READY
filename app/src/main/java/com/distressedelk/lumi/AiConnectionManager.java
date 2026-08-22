package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Non-blocking online-brain health manager.
 * Lumi always boots locally first. This manager checks the configured online provider beside the
 * main runtime, records a precise connection state, and retries with bounded exponential backoff.
 */
final class AiConnectionManager {
    private static final long[] RETRY_MS = {15_000L, 60_000L, 300_000L};
    private static final int MAX_AUTO_RETRIES = 3;
    private static final long STARTUP_FRESH_MS = 5L * 60L * 1000L;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryIndex = 0;
    private int generation = 0;
    private volatile boolean checkInFlight = false;
    private Runnable stateListener;

    AiConnectionManager(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    void start() {
        final int g = ++generation;
        long checked = prefs.getLong("ai_connection_checked_at", 0L);
        String state = prefs.getString("ai_connection_state", "UNKNOWN");
        // Code264: boot locally and quietly. A recent provider result remains usable evidence;
        // startup never needs to hammer the network just to let Lumi converse.
        if (checked > 0L && System.currentTimeMillis() - checked < STARTUP_FRESH_MS
                && !"UNKNOWN".equals(state) && !"CHECKING".equals(state)) {
            notifyListener();
            return;
        }
        handler.postDelayed(() -> runCheck(g), 1500L);
    }

    /** Force an immediate fresh provider check after configuration changes or a user status request. */
    void refreshNow() {
        final int g = ++generation;
        checkInFlight = false;
        retryIndex = 0;
        handler.removeCallbacksAndMessages(null);
        prefs.edit().putLong("ai_connection_next_retry_at", 0L).apply();
        handler.post(() -> runCheck(g));
    }

    /** Refresh only when the last check is old, avoiding noisy probes every foreground transition. */
    void refreshIfStale(long maxAgeMs) {
        if (checkInFlight) return;
        long checked = prefs.getLong("ai_connection_checked_at", 0L);
        if (checked <= 0L || System.currentTimeMillis() - checked > Math.max(60_000L, maxAgeMs)) refreshNow();
    }

    void stop() {
        generation++;
        checkInFlight = false;
        handler.removeCallbacksAndMessages(null);
    }

    void noteSuccess(String provider) {
        retryIndex = 0;
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString("ai_last_used_provider", provider == null ? "" : provider)
                .putLong("ai_last_used_at", now)
                .putString("ai_inference_state", "SUCCEEDED")
                .putString("ai_inference_provider", provider == null ? "" : provider)
                .putString("ai_inference_detail", "Last online AI inference request succeeded.")
                .putLong("ai_inference_checked_at", now)
                .remove("ai_inference_error")
                .apply();
        notifyListener();
    }

    void noteFailure(String provider, String rawError) {
        Classified c = classify(rawError);
        long now = System.currentTimeMillis();
        String raw = safeRaw(rawError);
        prefs.edit()
                .putString("ai_last_used_provider", provider == null ? "" : provider)
                .putLong("ai_last_used_at", now)
                .putString("ai_inference_state", "FAILED")
                .putString("ai_inference_provider", provider == null ? "" : provider)
                .putString("ai_inference_detail", c.message)
                .putString("ai_inference_error", raw)
                .putLong("ai_inference_checked_at", now)
                .apply();
        // Code269: a real-model failure must not overwrite provider reachability.
        notifyListener();
    }

    private void runCheck(int g) {
        if (g != generation || checkInFlight) return;
        final Provider p = selectedProvider();
        if (p == null) {
            retryIndex = 0;
            writeState("LOCAL_ONLY", "local", providerConfigurationSummary(prefs) + " Lumi is available locally.", 0L);
            return;
        }
        if (!networkAvailable()) {
            writeState("OFFLINE", p.name, "Phone has no usable internet connection. Lumi remains fully available locally.", 0L);
            scheduleRetry();
            return;
        }
        checkInFlight = true;
        writeState("CHECKING", p.name, "Quiet background availability check in progress…", 0L);
        new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                if ("openai".equals(p.name)) checkOpenAi(p.secret);
                else checkRemote(p.url);
                long latency = System.currentTimeMillis() - started;
                handler.post(() -> {
                    checkInFlight = false;
                    if (g != generation) return;
                    retryIndex = 0;
                    prefs.edit().putLong("ai_connection_next_retry_at", 0L).apply();
                    writeState("CONNECTED", p.name, "Background availability check passed. Online AI is ready if the router needs it.", latency);
                });
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - started;
                Classified c = classify(e);
                handler.post(() -> {
                    checkInFlight = false;
                    if (g != generation) return;
                    writeState(c.state, p.name, c.message, latency);
                    if (c.retryable) scheduleRetry();
                });
            }
        }, "LumiAiConnectionPreflight").start();
    }

    private void scheduleRetry() {
        if (retryIndex >= MAX_AUTO_RETRIES) {
            prefs.edit().putLong("ai_connection_next_retry_at", 0L)
                    .putBoolean("ai_connection_auto_retry_paused", true).apply();
            notifyListener();
            return;
        }
        int i = Math.min(retryIndex, RETRY_MS.length - 1);
        long delay = RETRY_MS[i];
        retryIndex++;
        prefs.edit().putLong("ai_connection_next_retry_at", System.currentTimeMillis() + delay)
                .putBoolean("ai_connection_auto_retry_paused", false).apply();
        final int g = generation;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> runCheck(g), delay);
    }

    private void notifyListener() {
        Runnable listener = stateListener;
        if (listener != null) handler.post(listener);
    }

    private Provider selectedProvider() {
        String openAiKey = SecretStore.get(prefs, "openai_api_key").trim();
        String remoteUrl = prefs.getString("opensource_url", "").trim();
        String provider = prefs.getString("ai_provider", "open_source").trim().toLowerCase(Locale.US);

        if ("openai".equals(provider) && !openAiKey.isEmpty()) return new Provider("openai", "", openAiKey);
        if (("open_source".equals(provider) || "hybrid".equals(provider)) && !remoteUrl.isEmpty() && !remoteUrl.contains("192.168.1.100:11434"))
            return new Provider("remote-booster", remoteUrl, "");
        if (!openAiKey.isEmpty()) {
            if (!"openai".equals(provider)) prefs.edit().putString("ai_provider","openai").apply();
            return new Provider("openai", "", openAiKey);
        }
        if (!remoteUrl.isEmpty() && !remoteUrl.contains("192.168.1.100:11434")) {
            if (!"open_source".equals(provider) && !"hybrid".equals(provider))
                prefs.edit().putString("ai_provider","open_source").apply();
            return new Provider("remote-booster", remoteUrl, "");
        }
        return null;
    }

    private boolean networkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void checkOpenAi(String key) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/models").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6_000);
            c.setReadTimeout(9_000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("User-Agent", "Lumi-AI-Connection-Manager/1");
            int code = c.getResponseCode();
            drain(c, code);
            if (code == 401 || code == 403) throw new ProviderHttpException(code, "OpenAI authentication was rejected.");
            if (code == 429) throw new ProviderHttpException(code, "OpenAI is rate limiting this connection.");
            if (code >= 500) throw new ProviderHttpException(code, "OpenAI service returned HTTP " + code + ".");
            if (code < 200 || code >= 300) throw new ProviderHttpException(code, "OpenAI returned HTTP " + code + ".");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void checkRemote(String endpoint) throws Exception {
        URL u = new URL(endpoint);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6_000);
            c.setReadTimeout(9_000);
            c.setRequestProperty("User-Agent", "Lumi-AI-Connection-Manager/1");
            String token = SecretStore.get(prefs, "opensource_api_key").trim();
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            drain(c, code);
            // 404/405 still proves the host/TLS path is reachable; the chat endpoint is normally POST-only.
            if (code == 401 || code == 403) throw new ProviderHttpException(code, "Remote AI authentication was rejected.");
            if (code == 429) throw new ProviderHttpException(code, "Remote AI is rate limiting this connection.");
            if (code >= 500) throw new ProviderHttpException(code, "Remote AI service returned HTTP " + code + ".");
            if (code >= 400 && code != 404 && code != 405) throw new ProviderHttpException(code, "Remote AI endpoint returned HTTP " + code + ".");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void drain(HttpURLConnection c, int code) {
        try (InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) return;
            byte[] b = new byte[512];
            while (in.read(b) > 0) { /* drain */ }
        } catch (Exception ignored) {}
    }

    private Classified classify(Throwable t) {
        if (t instanceof SocketTimeoutException) return new Classified("TIMEOUT", "Online AI timed out. Lumi is staying local and will retry automatically.", true);
        if (t instanceof UnknownHostException) return new Classified("DNS_ERROR", "The AI host name could not be reached. Lumi is staying local and will retry automatically.", true);
        if (t instanceof ProviderHttpException) {
            int code = ((ProviderHttpException) t).code;
            if (code == 401 || code == 403) return new Classified("AUTH_REQUIRED", "AI authentication needs attention. Lumi is staying local; reconnect the provider in Integration Center.", false);
            if (code == 429) return new Classified("RATE_LIMITED", "The online AI is temporarily rate limited. Lumi is staying local and will retry automatically.", true);
            if (code >= 500) return new Classified("SERVICE_ERROR", "The online AI service is temporarily unavailable. Lumi is staying local and will retry automatically.", true);
            return new Classified("ENDPOINT_ERROR", safeMessage(t), false);
        }
        String m = safeMessage(t).toLowerCase(Locale.US);
        if (m.contains("ssl") || m.contains("certificate") || m.contains("handshake"))
            return new Classified("TLS_ERROR", "Secure connection to the AI provider failed. Check the endpoint certificate or network.", false);
        if (m.contains("refused") || m.contains("failed to connect") || m.contains("network is unreachable"))
            return new Classified("OFFLINE", "The AI provider could not be reached. Lumi is staying local and will retry automatically.", true);
        if (m.contains("401") || m.contains("403") || m.contains("authentication") || m.contains("unauthorized"))
            return new Classified("AUTH_REQUIRED", "AI authentication needs attention. Lumi is staying local; reconnect the provider in Integration Center.", false);
        return new Classified("CONNECTION_ERROR", "Online AI connection failed: " + safeMessage(t) + ". Lumi is staying local and will retry.", true);
    }

    private Classified classify(String raw) {
        return classify(new Exception(raw == null ? "Unknown connection error" : raw));
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? "Unknown connection error" : t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t == null ? "Unknown connection error" : t.getClass().getSimpleName();
        m = m.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+", "Bearer [redacted]");
        if (m.length() > 240) m = m.substring(0, 240);
        return m.trim();
    }

    private static String safeRaw(String raw) {
        String m = raw == null ? "Unknown inference error" : raw;
        m = m.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+", "Bearer [redacted]");
        m = m.replace('\n',' ').replace('\r',' ').trim();
        if (m.length() > 500) m = m.substring(0, 500);
        return m;
    }

    private void writeState(String state, String provider, String detail, long latencyMs) {
        boolean configured = selectedProvider() != null;
        boolean available = "CONNECTED".equals(state);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor=prefs.edit()
                .putBoolean("ai_online_configured", configured)
                .putBoolean("ai_online_available", available)
                .putString("ai_connection_state", state)
                .putString("ai_connection_provider", provider == null ? "" : provider)
                .putString("ai_connection_detail", detail == null ? "" : detail)
                .putLong("ai_connection_checked_at", now)
                .putLong("ai_connection_latency_ms", latencyMs)
                .putString("ai_reachability_state", state)
                .putString("ai_reachability_provider", provider == null ? "" : provider)
                .putString("ai_reachability_detail", detail == null ? "" : detail)
                .putLong("ai_reachability_checked_at", now)
                .putLong("ai_reachability_latency_ms", latencyMs);
        // Code260: persist only the fact that OpenAI has been successfully verified.
        // This contains no credential and lets Talk use the same authoritative state as the card.
        if ("CONNECTED".equals(state) && "openai".equals(provider)) {
            editor.putBoolean("openai_route_verified", true).putString("ai_provider", "openai");
        }
        editor.apply();
        notifyListener();
    }



    static String providerConfigurationSummary(SharedPreferences prefs) {
        String key = SecretStore.get(prefs, "openai_api_key").trim();
        String url = prefs.getString("opensource_url", "").trim();
        String selected = prefs.getString("ai_provider", "").trim();
        boolean oldPrototypeUrl = url.contains("192.168.1.100:11434");
        if (oldPrototypeUrl) {
            prefs.edit().remove("opensource_url").remove("opensource_model").apply();
            url = "";
        }
        if (!key.isEmpty()) return "OpenAI credential is stored securely on this device.";
        if (!url.isEmpty()) return "A remote AI endpoint is configured.";
        if (!selected.isEmpty()) return "Provider preference is " + selected + ", but no usable credential or endpoint is stored.";
        return "No online AI credential or endpoint is stored on this device.";
    }

    static String spokenSummary(SharedPreferences prefs) {
        String reach = prefs.getString("ai_reachability_state", prefs.getString("ai_connection_state", "UNKNOWN"));
        String provider = prefs.getString("ai_reachability_provider", prefs.getString("ai_connection_provider", "local"));
        String inference = prefs.getString("ai_inference_state", "NEVER_TESTED");
        String error = prefs.getString("ai_inference_error", "").trim();

        if ("CONNECTED".equals(reach)) {
            if ("FAILED".equals(inference)) return "My OpenAI provider is reachable, but my last real AI request failed. " + inferenceFailureSpoken(error) + " I stayed on my local brain.";
            if ("SUCCEEDED".equals(inference)) return "My stronger AI connection is online through " + provider + ", and my last real AI request succeeded.";
            return "My stronger AI provider is reachable through " + provider + ". I have not recorded a successful real AI reply yet.";
        }
        if ("CHECKING".equals(reach)) return "I'm checking whether my stronger AI provider is reachable now.";
        if ("LOCAL_ONLY".equals(reach)) return "I'm running locally right now because no online AI provider is configured.";
        if ("AUTH_REQUIRED".equals(reach)) return "My stronger AI is configured, but its credential was rejected. I'm staying local.";
        if ("UNKNOWN".equals(reach)) return "I don't have a fresh online AI reachability result yet. I'm checking it now.";
        return "My stronger AI provider is currently " + reach.toLowerCase(Locale.US).replace('_',' ') + ". I'm staying local.";
    }

    private static String inferenceFailureSpoken(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.US);
        if (e.contains("429") || e.contains("quota") || e.contains("credit")) return "The last request was rejected for quota, credits, or rate limits.";
        if (e.contains("model") && (e.contains("not found") || e.contains("does not exist") || e.contains("unsupported"))) return "The configured model name was rejected.";
        if (e.contains("401") || e.contains("403") || e.contains("unauthorized") || e.contains("authentication")) return "The last request was rejected by authentication.";
        if (e.contains("400") || e.contains("invalid")) return "The provider rejected the last request payload.";
        return "The last inference request returned an error.";
    }

    static String summary(SharedPreferences prefs) {
        String reach = prefs.getString("ai_reachability_state", prefs.getString("ai_connection_state", "UNKNOWN"));
        String provider = prefs.getString("ai_reachability_provider", prefs.getString("ai_connection_provider", "local"));
        String reachDetail = prefs.getString("ai_reachability_detail", prefs.getString("ai_connection_detail", "Not checked yet."));
        long latency = prefs.getLong("ai_reachability_latency_ms", prefs.getLong("ai_connection_latency_ms", 0L));
        boolean configured = prefs.getBoolean("ai_online_configured", false);
        String inference = prefs.getString("ai_inference_state", "NEVER_TESTED");
        String inferenceProvider = prefs.getString("ai_inference_provider", "");
        String inferenceDetail = prefs.getString("ai_inference_detail", "");
        String inferenceError = prefs.getString("ai_inference_error", "");
        long inferenceAt = prefs.getLong("ai_inference_checked_at", 0L);

        StringBuilder out = new StringBuilder();
        out.append("CREDENTIAL: ").append(configured ? "CONFIGURED" : "NOT CONFIGURED");
        out.append("\nREACHABILITY: ").append(reach).append(" • ").append(provider);
        if (latency > 0) out.append(" • ").append(latency).append(" ms");
        out.append("\n").append(reachDetail == null ? "" : reachDetail);
        out.append("\n\nLAST INFERENCE: ").append(inference);
        if (!inferenceProvider.isEmpty()) out.append(" • ").append(inferenceProvider);
        if (inferenceAt > 0L) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
            out.append(" • ").append(f.format(new java.util.Date(inferenceAt)));
        }
        if (!inferenceDetail.isEmpty()) out.append("\n").append(inferenceDetail);
        if ("FAILED".equals(inference) && !inferenceError.isEmpty()) out.append("\nERROR: ").append(inferenceError);
        long retryAt = prefs.getLong("ai_connection_next_retry_at", 0L);
        boolean paused = prefs.getBoolean("ai_connection_auto_retry_paused", false);
        if (retryAt > System.currentTimeMillis()) out.append("\nBackground reachability retry queued.");
        else if (paused) out.append("\nAutomatic reachability retry paused after bounded attempts.");
        return out.toString();
    }

    private static final class Provider {
        final String name, url, secret;
        Provider(String name, String url, String secret) { this.name = name; this.url = url; this.secret = secret; }
    }
    private static final class Classified {
        final String state, message; final boolean retryable;
        Classified(String state, String message, boolean retryable) { this.state = state; this.message = message; this.retryable = retryable; }
    }
    private static final class ProviderHttpException extends Exception {
        final int code;
        ProviderHttpException(int code, String message) { super(message); this.code = code; }
    }
}
