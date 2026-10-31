package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service managing YouTube Data API v3 feeds (Recomendados, Inscrições, Histórico).
 * Implements in-memory and disk caching for instant UI response on onResume.
 */
public final class YouTubeService implements YouTubeAuth.AuthListener {
    private static final String TAG = "YouTubeService";

    public static final int TAB_RECOMMENDED = 0;
    public static final int TAB_SUBSCRIPTIONS = 1;
    public static final int TAB_HISTORY = 2;

    private static final String API_BASE = "https://www.googleapis.com/youtube/v3";
    private static final String INNERTUBE_BROWSE = "https://www.youtube.com/youtubei/v1/browse";
    private static final String PREFS_CACHE = "youtube_service_cache";
    private static final long STALE_MS = 5 * 60_000L; // 5 minutes cache

    public static class YouTubeVideo {
        public final String id;
        public final String title;
        public final String channelTitle;
        public final String duration;
        public final String thumbnailUrl;
        public volatile Bitmap thumbnail;
        public volatile BitmapShader shader;

        public YouTubeVideo(String id, String title, String channelTitle, String duration, String thumbnailUrl) {
            this.id = id;
            this.title = title != null ? title : "";
            this.channelTitle = channelTitle != null ? channelTitle : "";
            this.duration = duration != null ? duration : "";
            this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
        }

        public void setThumbnail(Bitmap bmp) {
            this.thumbnail = bmp;
            if (bmp != null) {
                this.shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            } else {
                this.shader = null;
            }
        }
    }

    public interface FeedListener {
        void onFeedUpdated();
    }

    private static YouTubeService instance;
    private final Context context;
    private final YouTubeAuth auth;
    private final SharedPreferences cachePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<FeedListener> listeners = new ArrayList<>();

    // In-memory feed cache for instant rendering
    private final List<YouTubeVideo>[] tabFeeds = new List[3];
    private final Map<String, Bitmap> thumbCache = new ConcurrentHashMap<>();
    private final Set<String> loadingThumbs = Collections.synchronizedSet(new HashSet<>());

    private long lastFetchTime = 0;
    private volatile boolean isFetching = false;

    public static synchronized YouTubeService get(Context context) {
        if (instance == null) {
            instance = new YouTubeService(context.getApplicationContext());
        }
        return instance;
    }

    private YouTubeService(Context context) {
        this.context = context;
        this.auth = YouTubeAuth.get(context);
        this.cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);

        for (int i = 0; i < 3; i++) {
            tabFeeds[i] = new ArrayList<>();
        }

        auth.addListener(this);
        loadDiskCache();
    }

    public void addListener(FeedListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(FeedListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public List<YouTubeVideo> getVideos(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= 3) return Collections.emptyList();
        synchronized (tabFeeds[tabIndex]) {
            return new ArrayList<>(tabFeeds[tabIndex]);
        }
    }

    public List<Recs.Pick> getPicksForTab(int tabIndex) {
        if (!auth.isLoggedIn()) {
            Recs.Pick pairing = new Recs.Pick();
            pairing.isPairingCard = true;
            YouTubeAuth.PairingState ps = auth.getPairingState();
            if (ps != null) {
                pairing.userCode = ps.userCode;
                pairing.verificationUrl = ps.verificationUrl;
                pairing.meta = ps.error;
            } else {
                pairing.userCode = "";
                pairing.verificationUrl = "https://www.google.com/device";
            }
            pairing.title = Ui.tr("Connect YouTube");
            pairing.channelTitle = Ui.tr("Access on your phone or PC:");
            return Collections.singletonList(pairing);
        }

        List<YouTubeVideo> videos = getVideos(tabIndex);
        List<Recs.Pick> picks = new ArrayList<>();
        for (YouTubeVideo v : videos) {
            Recs.Pick p = new Recs.Pick();
            p.id = v.id.hashCode();
            p.videoId = v.id;
            p.title = v.title;
            p.channelTitle = v.channelTitle;
            p.duration = v.duration;
            p.meta = v.channelTitle + (!v.duration.isEmpty() ? " · " + v.duration : "");
            p.pkg = "com.google.android.youtube.tv";
            p.link = "vnd.youtube:" + v.id;
            p.app = "youtube";
            p.thumbnail = v.thumbnail;
            p.thumbnailShader = v.shader;
            p.detailed = true;
            picks.add(p);
        }
        return picks;
    }

    /**
     * Revalidates feeds asynchronously on lifecycle events (e.g. onResume).
     * Does NOT block the UI thread.
     */
    public void refreshIfStale(boolean force, FeedListener callback) {
        if (callback != null) addListener(callback);

        if (!auth.isLoggedIn()) {
            auth.startPairing(null);
            notifyUpdated();
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastFetchTime < STALE_MS) && hasAnyVideos()) {
            notifyUpdated();
            return;
        }

        if (isFetching) return;
        isFetching = true;

        executor.execute(() -> {
            try {
                String token = auth.getValidAccessToken();
                if (token == null) {
                    isFetching = false;
                    return;
                }

                // 1. Fetch Recommended (InnerTube FEwhat_to_watch with fallback to Data API)
                List<YouTubeVideo> recs = fetchBrowse("FEwhat_to_watch", token);
                if (recs.isEmpty()) recs = fetchRecommended(token);

                // 2. Fetch Subscriptions (InnerTube FEsubscriptions with fallback to Data API)
                List<YouTubeVideo> subs = fetchBrowse("FEsubscriptions", token);
                if (subs.isEmpty()) subs = fetchSubscriptions(token);

                // 3. Fetch History / Liked (InnerTube FEhistory with fallback to Data API)
                List<YouTubeVideo> hist = fetchBrowse("FEhistory", token);
                if (hist.isEmpty()) hist = fetchHistory(token);

                synchronized (tabFeeds) {
                    if (!recs.isEmpty()) {
                        tabFeeds[TAB_RECOMMENDED].clear();
                        tabFeeds[TAB_RECOMMENDED].addAll(recs);
                    }
                    if (!subs.isEmpty()) {
                        tabFeeds[TAB_SUBSCRIPTIONS].clear();
                        tabFeeds[TAB_SUBSCRIPTIONS].addAll(subs);
                    }
                    if (!hist.isEmpty()) {
                        tabFeeds[TAB_HISTORY].clear();
                        tabFeeds[TAB_HISTORY].addAll(hist);
                    }
                }

                lastFetchTime = System.currentTimeMillis();
                saveDiskCache();

                // Prefetch thumbnails
                for (int i = 0; i < 3; i++) {
                    for (YouTubeVideo v : tabFeeds[i]) {
                        loadThumbnail(v);
                    }
                }

                notifyUpdated();
            } catch (Exception e) {
                Log.e(TAG, "Failed refreshing YouTube feeds: " + e.getMessage());
            } finally {
                isFetching = false;
            }
        });
    }

    private boolean hasAnyVideos() {
        for (int i = 0; i < 3; i++) {
            if (!tabFeeds[i].isEmpty()) return true;
        }
        return false;
    }

    public void loadThumbnail(YouTubeVideo video) {
        if (video == null || video.id == null || video.id.isEmpty()) return;

        Bitmap cached = thumbCache.get(video.id);
        if (cached != null) {
            video.setThumbnail(cached);
            return;
        }

        File cacheDir = new File(context.getCacheDir(), "yt_thumbs_v2");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File thumbFile = new File(cacheDir, video.id + ".jpg");

        if (thumbFile.exists() && thumbFile.length() > 0) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), opts);
            if (bmp != null) {
                bmp = fixThumbnailAspect(bmp);
                thumbCache.put(video.id, bmp);
                video.setThumbnail(bmp);
                notifyUpdated();
                return;
            }
        }

        if (!loadingThumbs.add(video.id)) return;

        executor.execute(() -> {
            try {
                // Priority order: 16:9 HD maxresdefault -> 16:9 hq720 -> provided URL -> hqdefault fallback
                String[] candidates = new String[] {
                        "https://i.ytimg.com/vi/" + video.id + "/maxresdefault.jpg",
                        "https://i.ytimg.com/vi/" + video.id + "/hq720.jpg",
                        video.thumbnailUrl,
                        "https://i.ytimg.com/vi/" + video.id + "/hqdefault.jpg"
                };

                boolean success = false;
                for (String urlStr : candidates) {
                    if (urlStr == null || urlStr.isEmpty()) continue;
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(6000);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) {
                            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(thumbFile)) {
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = in.read(buf)) != -1) {
                                    out.write(buf, 0, r);
                                }
                            }
                            conn.disconnect();
                            success = true;
                            break;
                        }
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }

                if (success && thumbFile.exists() && thumbFile.length() > 0) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        bmp = fixThumbnailAspect(bmp);
                        thumbCache.put(video.id, bmp);
                        video.setThumbnail(bmp);
                        notifyUpdated();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error downloading thumb for " + video.id + ": " + e.getMessage());
            } finally {
                loadingThumbs.remove(video.id);
            }
        });
    }

    private static Bitmap fixThumbnailAspect(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return bmp;

        // If it's a 4:3 legacy thumbnail (e.g. hqdefault 480x360), YouTube burned letterbox bars
        // on top & bottom (12.5% each). Crop the middle 16:9 slice (480x270) to eliminate bars.
        float aspect = (float) w / (float) h;
        if (Math.abs(aspect - (4f / 3f)) < 0.06f) {
            int cropY = Math.round(h * 0.125f);
            int cropH = Math.round(h * 0.75f);
            if (cropY + cropH <= h) {
                try {
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, cropY, w, cropH);
                    if (cropped != bmp) bmp.recycle();
                    return cropped;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed cropping letterbox: " + t.getMessage());
                }
            }
        }
        return bmp;
    }

    private List<YouTubeVideo> fetchBrowse(String browseId, String token) {
        List<YouTubeVideo> list = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "TVHTML5");
            client.put("clientVersion", "7.20240101.00.00");
            client.put("hl", "pt");
            client.put("gl", "BR");
            context.put("client", client);
            body.put("context", context);
            body.put("browseId", browseId);

            HttpURLConnection conn = (HttpURLConnection) new URL(INNERTUBE_BROWSE).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (PlayStation; PlayStation 4/10.01) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/12.0 Safari/605.1.15");

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respStr = readAll(is);
            conn.disconnect();

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(respStr);
                extractTiles(json, list);
                Log.d(TAG, "InnerTube " + browseId + " fetched " + list.size() + " videos");
            } else {
                Log.w(TAG, "InnerTube " + browseId + " returned HTTP " + code + ": " + respStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in fetchBrowse " + browseId + ": " + e.getMessage());
        }
        return list;
    }

    private void extractTiles(Object obj, List<YouTubeVideo> out) {
        if (obj instanceof JSONObject) {
            JSONObject jobj = (JSONObject) obj;
            if (jobj.has("tileRenderer")) {
                YouTubeVideo v = parseTileRenderer(jobj.optJSONObject("tileRenderer"));
                if (v != null && out.size() < 25) {
                    boolean exists = false;
                    for (YouTubeVideo ex : out) {
                        if (ex.id.equals(v.id)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) out.add(v);
                }
            }
            Iterator<String> keys = jobj.keys();
            while (keys.hasNext()) {
                extractTiles(jobj.opt(keys.next()), out);
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                extractTiles(arr.opt(i), out);
            }
        }
    }

    private YouTubeVideo parseTileRenderer(JSONObject tr) {
        if (tr == null) return null;
        String vid = tr.optString("contentId", "");
        if (vid.isEmpty()) {
            JSONObject osc = tr.optJSONObject("onSelectCommand");
            JSONObject we = osc != null ? osc.optJSONObject("watchEndpoint") : null;
            if (we != null) vid = we.optString("videoId", "");
        }
        if (vid.isEmpty()) return null;

        JSONObject meta = tr.optJSONObject("metadata");
        JSONObject tileMeta = meta != null ? meta.optJSONObject("tileMetadataRenderer") : null;

        String title = "";
        if (tileMeta != null) {
            JSONObject tObj = tileMeta.optJSONObject("title");
            if (tObj != null) {
                title = tObj.optString("simpleText", "");
                if (title.isEmpty() && tObj.has("runs")) {
                    JSONArray runs = tObj.optJSONArray("runs");
                    if (runs != null && runs.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < runs.length(); i++) {
                            JSONObject r = runs.optJSONObject(i);
                            if (r != null) sb.append(r.optString("text", ""));
                        }
                        title = sb.toString();
                    }
                }
            }
        }

        String channel = "";
        if (tileMeta != null && tileMeta.has("lines")) {
            JSONArray lines = tileMeta.optJSONArray("lines");
            if (lines != null && lines.length() > 0) {
                JSONObject l0 = lines.optJSONObject(0);
                JSONObject lineR = l0 != null ? l0.optJSONObject("lineRenderer") : null;
                JSONArray items = lineR != null ? lineR.optJSONArray("items") : null;
                if (items != null && items.length() > 0) {
                    JSONObject item0 = items.optJSONObject(0);
                    JSONObject lineItem = item0 != null ? item0.optJSONObject("lineItemRenderer") : null;
                    JSONObject textObj = lineItem != null ? lineItem.optJSONObject("text") : null;
                    if (textObj != null) {
                        channel = textObj.optString("simpleText", "");
                        if (channel.isEmpty() && textObj.has("runs")) {
                            JSONArray runs = textObj.optJSONArray("runs");
                            if (runs != null && runs.length() > 0) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < runs.length(); i++) {
                                    JSONObject r = runs.optJSONObject(i);
                                    if (r != null) sb.append(r.optString("text", ""));
                                }
                                channel = sb.toString();
                            }
                        }
                    }
                }
            }
        }

        JSONObject header = tr.optJSONObject("header");
        JSONObject tileHeader = header != null ? header.optJSONObject("tileHeaderRenderer") : null;

        String thumbUrl = "";
        String duration = "";

        if (tileHeader != null) {
            JSONObject thumbObj = tileHeader.optJSONObject("thumbnail");
            JSONArray thumbs = thumbObj != null ? thumbObj.optJSONArray("thumbnails") : null;
            if (thumbs != null && thumbs.length() > 0) {
                JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
                if (last != null) thumbUrl = last.optString("url", "");
            }

            JSONArray overlays = tileHeader.optJSONArray("thumbnailOverlays");
            if (overlays != null) {
                for (int i = 0; i < overlays.length(); i++) {
                    JSONObject o = overlays.optJSONObject(i);
                    JSONObject timeStatus = o != null ? o.optJSONObject("thumbnailOverlayTimeStatusRenderer") : null;
                    if (timeStatus != null) {
                        JSONObject t = timeStatus.optJSONObject("text");
                        if (t != null) {
                            duration = t.optString("simpleText", "");
                            if (duration.isEmpty() && t.has("runs")) {
                                JSONArray runs = t.optJSONArray("runs");
                                if (runs != null && runs.length() > 0) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int r = 0; r < runs.length(); r++) {
                                        JSONObject ro = runs.optJSONObject(r);
                                        if (ro != null) sb.append(ro.optString("text", ""));
                                    }
                                    duration = sb.toString();
                                }
                            }
                        }
                    }
                }
            }
        }
        if (thumbUrl.isEmpty()) {
            thumbUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
        }

        return new YouTubeVideo(vid, title, channel, duration, thumbUrl);
    }

    private List<YouTubeVideo> fetchRecommended(String token) {
        List<YouTubeVideo> list = new ArrayList<>();
        try {
            String url = API_BASE + "/videos?part=snippet,contentDetails&chart=mostPopular&regionCode=BR&maxResults=10";
            String jsonStr = getWithAuth(url, token);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray items = json.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    YouTubeVideo v = parseVideoItem(item);
                    if (v != null) list.add(v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in fetchRecommended: " + e.getMessage());
        }
        return list;
    }

    private List<YouTubeVideo> fetchSubscriptions(String token) {
        List<YouTubeVideo> list = new ArrayList<>();
        try {
            // First attempt: /activities?mine=true (Uploads from subscriptions)
            String actUrl = API_BASE + "/activities?part=snippet,contentDetails&mine=true&maxResults=15";
            String jsonStr = getWithAuth(actUrl, token);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray items = json.optJSONArray("items");

            List<String> videoIds = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    JSONObject contentDetails = item.optJSONObject("contentDetails");
                    if (contentDetails != null) {
                        JSONObject upload = contentDetails.optJSONObject("upload");
                        if (upload != null && upload.has("videoId")) {
                            videoIds.add(upload.getString("videoId"));
                        }
                    }
                }
            }

            // Fallback: Query recent uploads from subscriptions channels if activities is empty
            if (videoIds.isEmpty()) {
                String subsUrl = API_BASE + "/subscriptions?part=snippet&mine=true&maxResults=6";
                String subsResp = getWithAuth(subsUrl, token);
                JSONObject subsJson = new JSONObject(subsResp);
                JSONArray subItems = subsJson.optJSONArray("items");
                if (subItems != null) {
                    for (int s = 0; s < subItems.length() && videoIds.size() < 10; s++) {
                        JSONObject snippet = subItems.getJSONObject(s).optJSONObject("snippet");
                        if (snippet != null) {
                            JSONObject resId = snippet.optJSONObject("resourceId");
                            if (resId != null && resId.has("channelId")) {
                                String channelId = resId.getString("channelId");
                                if (channelId.startsWith("UC")) {
                                    String uploadsPlaylist = "UU" + channelId.substring(2);
                                    String plUrl = API_BASE + "/playlistItems?part=contentDetails&playlistId=" + uploadsPlaylist + "&maxResults=2";
                                    try {
                                        String plResp = getWithAuth(plUrl, token);
                                        JSONObject plJson = new JSONObject(plResp);
                                        JSONArray plItems = plJson.optJSONArray("items");
                                        if (plItems != null) {
                                            for (int p = 0; p < plItems.length(); p++) {
                                                String vid = plItems.getJSONObject(p).getJSONObject("contentDetails").getString("videoId");
                                                if (!videoIds.contains(vid)) videoIds.add(vid);
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
            }

            if (!videoIds.isEmpty()) {
                list.addAll(fetchVideoDetails(videoIds, token));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in fetchSubscriptions: " + e.getMessage());
        }
        return list;
    }

    private List<YouTubeVideo> fetchHistory(String token) {
        List<YouTubeVideo> list = new ArrayList<>();
        try {
            // In API v3, user history is private, but "Liked Videos" (playlistId=LL) and "Watch Later" (WL)
            // represent the authenticated user's curated history/reproduction queue.
            String plUrl = API_BASE + "/playlistItems?part=contentDetails&playlistId=LL&maxResults=10";
            String jsonStr = getWithAuth(plUrl, token);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray items = json.optJSONArray("items");

            List<String> videoIds = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject cd = items.getJSONObject(i).optJSONObject("contentDetails");
                    if (cd != null && cd.has("videoId")) {
                        videoIds.add(cd.getString("videoId"));
                    }
                }
            }

            if (!videoIds.isEmpty()) {
                list.addAll(fetchVideoDetails(videoIds, token));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in fetchHistory: " + e.getMessage());
        }
        return list;
    }

    private List<YouTubeVideo> fetchVideoDetails(List<String> videoIds, String token) {
        List<YouTubeVideo> list = new ArrayList<>();
        if (videoIds.isEmpty()) return list;

        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < videoIds.size() && i < 20; i++) {
                if (i > 0) sb.append(",");
                sb.append(videoIds.get(i));
            }
            String url = API_BASE + "/videos?part=snippet,contentDetails&id=" + sb.toString();
            String jsonStr = getWithAuth(url, token);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray items = json.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    YouTubeVideo v = parseVideoItem(items.getJSONObject(i));
                    if (v != null) list.add(v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching video details: " + e.getMessage());
        }
        return list;
    }

    private static YouTubeVideo parseVideoItem(JSONObject item) {
        try {
            String id = item.optString("id");
            if (id.isEmpty()) {
                JSONObject idObj = item.optJSONObject("id");
                if (idObj != null) id = idObj.optString("videoId");
            }
            if (id.isEmpty()) return null;

            JSONObject snippet = item.optJSONObject("snippet");
            String title = snippet != null ? snippet.optString("title", "") : "";
            String channel = snippet != null ? snippet.optString("channelTitle", "") : "";

            String thumbUrl = "";
            if (snippet != null) {
                JSONObject thumbs = snippet.optJSONObject("thumbnails");
                if (thumbs != null) {
                    if (thumbs.has("maxres")) thumbUrl = thumbs.getJSONObject("maxres").optString("url");
                    else if (thumbs.has("high")) thumbUrl = thumbs.getJSONObject("high").optString("url");
                    else if (thumbs.has("medium")) thumbUrl = thumbs.getJSONObject("medium").optString("url");
                    else if (thumbs.has("default")) thumbUrl = thumbs.getJSONObject("default").optString("url");
                }
            }

            String duration = "";
            JSONObject contentDetails = item.optJSONObject("contentDetails");
            if (contentDetails != null && contentDetails.has("duration")) {
                duration = formatDuration(contentDetails.getString("duration"));
            }

            return new YouTubeVideo(id, title, channel, duration, thumbUrl);
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatDuration(String iso) {
        if (iso == null || !iso.startsWith("PT")) return "";
        try {
            int hours = 0, mins = 0, secs = 0;
            String s = iso.substring(2);
            int hIdx = s.indexOf('H');
            if (hIdx >= 0) {
                hours = Integer.parseInt(s.substring(0, hIdx));
                s = s.substring(hIdx + 1);
            }
            int mIdx = s.indexOf('M');
            if (mIdx >= 0) {
                mins = Integer.parseInt(s.substring(0, mIdx));
                s = s.substring(mIdx + 1);
            }
            int sIdx = s.indexOf('S');
            if (sIdx >= 0) {
                secs = Integer.parseInt(s.substring(0, sIdx));
            }
            if (hours > 0) {
                return String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs);
            } else {
                return String.format(Locale.ROOT, "%d:%02d", mins, secs);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String getWithAuth(String endpoint, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String result = readAll(is);
        conn.disconnect();

        if (code >= 400) {
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

    private void loadDiskCache() {
        for (int t = 0; t < 3; t++) {
            String json = cachePrefs.getString("tab_" + t, null);
            if (json != null) {
                try {
                    JSONArray arr = new JSONArray(json);
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        YouTubeVideo v = new YouTubeVideo(
                                o.getString("id"),
                                o.optString("title", ""),
                                o.optString("channel", ""),
                                o.optString("duration", ""),
                                o.optString("thumb", "")
                        );
                        loadThumbnail(v);
                        list.add(v);
                    }
                    synchronized (tabFeeds) {
                        tabFeeds[t].clear();
                        tabFeeds[t].addAll(list);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error loading disk cache for tab " + t + ": " + e.getMessage());
                }
            }
        }
    }

    private void saveDiskCache() {
        for (int t = 0; t < 3; t++) {
            try {
                JSONArray arr = new JSONArray();
                synchronized (tabFeeds) {
                    for (YouTubeVideo v : tabFeeds[t]) {
                        JSONObject o = new JSONObject();
                        o.put("id", v.id);
                        o.put("title", v.title);
                        o.put("channel", v.channelTitle);
                        o.put("duration", v.duration);
                        o.put("thumb", v.thumbnailUrl);
                        arr.put(o);
                    }
                }
                cachePrefs.edit().putString("tab_" + t, arr.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    private void notifyUpdated() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (FeedListener l : listeners) l.onFeedUpdated();
            }
        });
    }

    // --- YouTubeAuth.AuthListener implementation ---

    @Override
    public void onPairingUpdated(YouTubeAuth.PairingState state) {
        notifyUpdated();
    }

    @Override
    public void onAuthSuccess() {
        refreshIfStale(true, null);
    }

    @Override
    public void onAuthError(String error) {
        notifyUpdated();
    }

    @Override
    public void onDisconnected() {
        for (int i = 0; i < 3; i++) {
            tabFeeds[i].clear();
            cachePrefs.edit().remove("tab_" + i).apply();
        }
        notifyUpdated();
    }
}
