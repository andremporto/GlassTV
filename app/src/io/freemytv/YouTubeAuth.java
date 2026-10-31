package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages OAuth 2.0 Device Authorization Grant (RFC 8628) for YouTube TV synchronization.
 * Handles device pairing codes, background polling, persistent single-login token storage
 * in SharedPreferences, automatic refresh token rotation, and account disconnects.
 */
public final class YouTubeAuth {
    private static final String TAG = "YouTubeAuth";

    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    // Standard YouTube on TV OAuth client credentials for RFC 8628 Device Flow
    private static final String DEFAULT_CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com";
    private static final String DEFAULT_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT";
    private static final String SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube";

    private static final String PREFS_NAME = "youtube_auth";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_ACCOUNT_NAME = "account_name";

    public interface AuthListener {
        void onPairingUpdated(PairingState state);
        void onAuthSuccess();
        void onAuthError(String error);
        void onDisconnected();
    }

    public static class PairingState {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final long expiresAt;
        public int interval;
        public volatile boolean approved;
        public volatile String error;

        public PairingState(String deviceCode, String userCode, String verificationUrl, long expiresAt, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl != null && !verificationUrl.isEmpty() ? verificationUrl : "https://www.google.com/device";
            this.expiresAt = expiresAt;
            this.interval = Math.max(5, interval);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    private static YouTubeAuth instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AuthListener> listeners = new ArrayList<>();

    private volatile PairingState currentPairingState;
    private volatile boolean isPolling = false;

    public static synchronized YouTubeAuth get(Context context) {
        if (instance == null) {
            instance = new YouTubeAuth(context.getApplicationContext());
        }
        return instance;
    }

    private YouTubeAuth(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addListener(AuthListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(AuthListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public String getClientId() {
        String customId = Config.YOUTUBE_CLIENT_ID;
        if (customId != null && !customId.trim().isEmpty()) {
            return customId.trim();
        }
        return DEFAULT_CLIENT_ID;
    }

    public String getClientSecret() {
        String customSecret = Config.YOUTUBE_CLIENT_SECRET;
        if (customSecret != null && !customSecret.trim().isEmpty()) {
            return customSecret.trim();
        }
        String customId = Config.YOUTUBE_CLIENT_ID;
        if (customId != null && !customId.trim().isEmpty()) {
            return ""; // custom client without secret
        }
        return DEFAULT_CLIENT_SECRET;
    }

    public boolean isLoggedIn() {
        String refresh = prefs.getString(KEY_REFRESH_TOKEN, null);
        return refresh != null && !refresh.trim().isEmpty();
    }

    public String getAccountName() {
        return prefs.getString(KEY_ACCOUNT_NAME, "YouTube TV");
    }

    public void setAccountName(String name) {
        prefs.edit().putString(KEY_ACCOUNT_NAME, name).apply();
    }

    public PairingState getPairingState() {
        if (currentPairingState != null && currentPairingState.isExpired()) {
            currentPairingState = null;
        }
        return currentPairingState;
    }

    /**
     * Obtains a valid Access Token synchronously.
     * Renews using the Refresh Token if expired. Safe to call from background threads.
     */
    public synchronized String getValidAccessToken() {
        if (!isLoggedIn()) return null;

        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        long now = System.currentTimeMillis();

        if (accessToken != null && now < expiresAt - 60_000L) {
            return accessToken;
        }

        // Need to refresh
        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        if (refreshToken == null) return null;

        try {
            String clientId = getClientId();
            String clientSecret = getClientSecret();

            StringBuilder body = new StringBuilder();
            body.append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"));
            if (!clientSecret.isEmpty()) {
                body.append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"));
            }
            body.append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"));
            body.append("&grant_type=refresh_token");

            String response = postForm(TOKEN_URL, body.toString());
            JSONObject json = new JSONObject(response);

            if (json.has("access_token")) {
                String newAccess = json.getString("access_token");
                int expiresIn = json.optInt("expires_in", 3600);
                long newExpiresAt = now + (expiresIn - 60) * 1000L;

                prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, newAccess)
                        .putLong(KEY_EXPIRES_AT, newExpiresAt)
                        .apply();
                return newAccess;
            } else if (json.has("error")) {
                String error = json.getString("error");
                if ("invalid_grant".equals(error)) {
                    Log.w(TAG, "Refresh token revoked or invalid. Clearing session.");
                    disconnect();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh YouTube access token: " + e.getMessage());
        }

        return null;
    }

    /**
     * Initiates the RFC 8628 Device Authorization Flow.
     */
    public void startPairing(AuthListener listener) {
        if (listener != null) addListener(listener);

        if (isLoggedIn()) {
            notifySuccess();
            return;
        }

        if (currentPairingState != null && !currentPairingState.isExpired() && currentPairingState.deviceCode != null) {
            notifyPairingUpdated(currentPairingState);
            if (!isPolling) startPollingThread(currentPairingState);
            return;
        }

        executor.execute(() -> {
            try {
                String clientId = getClientId();
                StringBuilder body = new StringBuilder();
                body.append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"));
                body.append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"));

                String resp = postForm(DEVICE_CODE_URL, body.toString());
                JSONObject json = new JSONObject(resp);

                String deviceCode = json.getString("device_code");
                String userCode = json.getString("user_code");
                String verificationUrl = json.optString("verification_url", "https://www.google.com/device");
                int expiresIn = json.optInt("expires_in", 1800);
                int interval = json.optInt("interval", 5);

                PairingState state = new PairingState(
                        deviceCode,
                        userCode,
                        verificationUrl,
                        System.currentTimeMillis() + expiresIn * 1000L,
                        interval
                );

                currentPairingState = state;
                notifyPairingUpdated(state);
                startPollingThread(state);

            } catch (Exception e) {
                Log.e(TAG, "Failed to start device flow: " + e.getMessage());
                PairingState errState = new PairingState(null, "", null, System.currentTimeMillis() + 60_000L, 5);
                errState.error = e.getMessage();
                currentPairingState = errState;
                notifyPairingUpdated(errState);
                notifyError("Não foi possível gerar o código: " + e.getMessage());
            }
        });
    }

    private void startPollingThread(PairingState state) {
        if (state == null || state.deviceCode == null) return;
        if (isPolling) return;
        isPolling = true;

        executor.execute(() -> {
            try {
                String clientId = getClientId();
                String clientSecret = getClientSecret();

                while (isPolling && currentPairingState == state && !state.approved && !state.isExpired()) {
                    try {
                        Thread.sleep(state.interval * 1000L);
                    } catch (InterruptedException ie) {
                        break;
                    }

                    if (!isPolling || currentPairingState != state) break;

                    try {
                        StringBuilder body = new StringBuilder();
                        body.append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"));
                        if (!clientSecret.isEmpty()) {
                            body.append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"));
                        }
                        body.append("&device_code=").append(URLEncoder.encode(state.deviceCode, "UTF-8"));
                        body.append("&grant_type=urn:ietf:params:oauth:grant-type:device_code");

                        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        byte[] outBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        conn.setFixedLengthStreamingMode(outBytes.length);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(outBytes);
                        }

                        int code = conn.getResponseCode();
                        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                        String responseStr = readAll(is);
                        conn.disconnect();

                        JSONObject json = new JSONObject(responseStr);

                        if (json.has("access_token")) {
                            String accessToken = json.getString("access_token");
                            String refreshToken = json.optString("refresh_token", "");
                            int expiresIn = json.optInt("expires_in", 3600);
                            long expiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L;

                            prefs.edit()
                                    .putString(KEY_ACCESS_TOKEN, accessToken)
                                    .putString(KEY_REFRESH_TOKEN, refreshToken)
                                    .putLong(KEY_EXPIRES_AT, expiresAt)
                                    .apply();

                            state.approved = true;
                            currentPairingState = null;
                            isPolling = false;
                            notifySuccess();
                            break;

                        } else if (json.has("error")) {
                            String error = json.getString("error");
                            if ("authorization_pending".equals(error)) {
                                // Keep polling
                                continue;
                            } else if ("slow_down".equals(error)) {
                                state.interval += 5;
                            } else if ("access_denied".equals(error) || "expired_token".equals(error)) {
                                state.error = error;
                                isPolling = false;
                                currentPairingState = null;
                                notifyError("Autorização cancelada ou expirada.");
                                break;
                            }
                        }
                    } catch (Exception pollEx) {
                        Log.w(TAG, "Polling exception: " + pollEx.getMessage());
                    }
                }
            } finally {
                isPolling = false;
            }
        });
    }

    public void disconnect() {
        String token = prefs.getString(KEY_REFRESH_TOKEN, null);
        if (token == null) token = prefs.getString(KEY_ACCESS_TOKEN, null);

        prefs.edit().clear().apply();
        currentPairingState = null;
        isPolling = false;

        if (token != null) {
            final String tok = token;
            executor.execute(() -> {
                try {
                    postForm(REVOKE_URL, "token=" + URLEncoder.encode(tok, "UTF-8"));
                } catch (Exception ignored) {}
            });
        }

        notifyDisconnected();
    }

    private void notifyPairingUpdated(PairingState state) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (AuthListener l : listeners) l.onPairingUpdated(state);
            }
        });
    }

    private void notifySuccess() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (AuthListener l : listeners) l.onAuthSuccess();
            }
        });
    }

    private void notifyError(String err) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (AuthListener l : listeners) l.onAuthError(err);
            }
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (AuthListener l : listeners) l.onDisconnected();
            }
        });
    }

    private static String postForm(String endpoint, String urlEncodedBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        byte[] bytes = urlEncodedBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String result = readAll(is);
        conn.disconnect();

        if (code >= 400 && code != 428) {
            throw new Exception("HTTP " + code + ": " + result);
        }
        return result;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }
}
