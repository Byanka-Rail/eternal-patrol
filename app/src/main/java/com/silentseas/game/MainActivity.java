package com.silentseas.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.silentseas.game.voice.EternalVoiceBridge;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://silentseas.local";
    private static final String HOME_URL = ORIGIN + "/index.html";
    private static final String UPDATE_MANIFEST = "https://raw.githubusercontent.com/Byanka-Rail/eternal-patrol/main/update.json";
    private static final String ALLOWED_UPDATE_PREFIX = "https://raw.githubusercontent.com/Byanka-Rail/eternal-patrol/";
    private static final String BUNDLED_GAME_VERSION = "6.25.5";
    private static final int FALLBACK_VERSION_CODE = 62505;
    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int REQ_BACKUP = 401;
    private static final int REQ_RESTORE = 402;

    private WebView webView;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingBackupJson;
    private EternalVoiceBridge voiceBridge;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("eternal_patrol_native", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();
        cleanupStaleOverride();
        createWebView(savedInstanceState);
        main.postDelayed(() -> checkForUpdates(false), 1800);
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void createWebView(Bundle savedInstanceState) {
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(false);
        WebView.setWebContentsDebuggingEnabled(false);

        // Optional on-device crew voice. The page still works unchanged when no voice pack is installed.
        voiceBridge = new EternalVoiceBridge(this, webView);
        webView.addJavascriptInterface(voiceBridge, "EternalVoice");

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("https".equalsIgnoreCase(u.getScheme()) && "silentseas.local".equalsIgnoreCase(u.getHost())) {
                    String path = u.getPath();
                    if (path == null || "/".equals(path) || "/index.html".equals(path)) {
                        try {
                            return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(gameHtmlBytes()));
                        } catch (Exception e) {
                            return textResponse("게임 파일을 읽지 못했습니다: " + e.getMessage());
                        }
                    }
                    return textResponse("");
                }
                return textResponse("");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && "mailto".equalsIgnoreCase(u.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_SENDTO, u)); }
                    catch (ActivityNotFoundException ignored) { Toast.makeText(MainActivity.this, "메일 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                return !(u != null && "silentseas.local".equalsIgnoreCase(u.getHost()));
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (voiceBridge != null) voiceBridge.onPageReady();
            }
        });

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    private WebResourceResponse textResponse(String text) {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] gameHtmlBytes() throws Exception {
        File f = overrideFile();
        String ov = prefs.getString("overrideGameVersion", "");
        if (f.isFile() && compareVersion(ov, BUNDLED_GAME_VERSION) > 0) {
            return readAll(new FileInputStream(f), 16 * 1024 * 1024);
        }
        return readAll(getAssets().open("index.html"), 16 * 1024 * 1024);
    }

    private File overrideFile() { return new File(getFilesDir(), "game_override.html"); }

    private void cleanupStaleOverride() {
        String ov = prefs.getString("overrideGameVersion", "");
        if (compareVersion(ov, BUNDLED_GAME_VERSION) <= 0) {
            File f = overrideFile();
            if (f.exists()) f.delete();
            prefs.edit().remove("overrideGameVersion").apply();
        }
    }

    private String currentGameVersion() {
        String ov = prefs.getString("overrideGameVersion", "");
        return compareVersion(ov, BUNDLED_GAME_VERSION) > 0 ? ov : BUNDLED_GAME_VERSION;
    }

    private int compareVersion(String a, String b) {
        String[] aa = (a == null ? "" : a).split("[^0-9]+");
        String[] bb = (b == null ? "" : b).split("[^0-9]+");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length && !aa[i].isEmpty() ? safeInt(aa[i]) : 0;
            int y = i < bb.length && !bb[i].isEmpty() ? safeInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private int safeInt(String x) { try { return Integer.parseInt(x); } catch (Exception e) { return 0; } }

    private void checkForUpdates(boolean userInitiated) {
        long now = System.currentTimeMillis();
        if (!userInitiated && now - prefs.getLong("lastUpdateCheck", 0L) < UPDATE_INTERVAL_MS) return;
        prefs.edit().putLong("lastUpdateCheck", now).apply();
        new Thread(() -> {
            try {
                String manifestText = new String(download(UPDATE_MANIFEST, 512 * 1024), StandardCharsets.UTF_8);
                JSONObject m = new JSONObject(manifestText);
                String remote = m.optString("gameVersion", "").trim();
                String url = m.optString("url", m.optString("htmlUrl", "")).trim();
                String sha = m.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
                if (compareVersion(remote, currentGameVersion()) <= 0) {
                    if (userInitiated) main.post(() -> Toast.makeText(this, "현재 게임이 최신입니다.", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (!allowedUpdateUrl(url)) throw new IllegalStateException("허용되지 않은 업데이트 주소");
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle("ETERNAL PATROL 업데이트")
                        .setMessage("게임 데이터 " + remote + " 버전이 있습니다. 내려받을까요?")
                        .setNegativeButton("나중에", null)
                        .setPositiveButton("업데이트", (d, w) -> installHtmlUpdate(remote, url, sha))
                        .show());
            } catch (Exception e) {
                if (userInitiated) main.post(() -> Toast.makeText(this, "업데이트 확인 실패", Toast.LENGTH_SHORT).show());
            }
        }, "EP-update-check").start();
    }

    private boolean allowedUpdateUrl(String url) {
        return url != null && url.startsWith(ALLOWED_UPDATE_PREFIX) && url.startsWith("https://");
    }

    private void installHtmlUpdate(String version, String url, String sha256) {
        Toast.makeText(this, "업데이트를 내려받는 중입니다.", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] data = download(url, 16 * 1024 * 1024);
                String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (!head.contains("<html") || !head.contains("<script")) throw new IllegalStateException("HTML 검증 실패");
                if (!sha256.isEmpty() && !sha256.equals(hexSha256(data))) throw new IllegalStateException("SHA-256 불일치");
                File tmp = new File(getFilesDir(), "game_override.tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(data); }
                File dst = overrideFile();
                if (dst.exists() && !dst.delete()) throw new IllegalStateException("기존 업데이트 삭제 실패");
                if (!tmp.renameTo(dst)) throw new IllegalStateException("업데이트 설치 실패");
                prefs.edit().putString("overrideGameVersion", version).apply();
                main.post(() -> {
                    Toast.makeText(this, "업데이트 완료 · " + version, Toast.LENGTH_SHORT).show();
                    webView.loadUrl(HOME_URL);
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "업데이트 설치 실패", Toast.LENGTH_LONG).show());
            }
        }, "EP-update-install").start();
    }

    private byte[] download(String url, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ETERNAL-PATROL-Android/6.25.5");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (InputStream in = c.getInputStream()) { return readAll(in, limit); }
        finally { c.disconnect(); }
    }

    private byte[] readAll(InputStream in, int limit) throws Exception {
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = src.read(buf)) >= 0) {
                total += n; if (total > limit) throw new IllegalStateException("파일이 너무 큽니다.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String hexSha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private int installedAndroidVersionCode() {
        try {
            android.content.pm.PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return (int) Math.min(Integer.MAX_VALUE, p.getLongVersionCode());
            return p.versionCode;
        } catch (Exception e) { return FALLBACK_VERSION_CODE; }
    }

    private void saveGameNow() {
        if (webView == null) return;
        webView.evaluateJavascript("try{if(window.Campaign&&Campaign.save)Campaign.save();if(window.War&&War.save)War.save();}catch(e){}", null);
    }

    private void requestBackup() {
        saveGameNow();
        String js = "(function(){try{var b={format:'ETERNAL_PATROL_ANDROID_BACKUP',schema:2,gameVersion:'" + BUNDLED_GAME_VERSION + "',createdAt:new Date().toISOString(),localStorage:{}};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);b.localStorage[k]=localStorage.getItem(k);}return JSON.stringify(b);}catch(e){return JSON.stringify({error:String(e)});}})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String decoded = decodeJsString(value);
                JSONObject check = new JSONObject(decoded);
                if (check.has("error")) throw new IllegalStateException(check.optString("error"));
                pendingBackupJson = decoded;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date());
                i.putExtra(Intent.EXTRA_TITLE, "ETERNAL_PATROL_BACKUP_" + stamp + ".json");
                startActivityForResult(i, REQ_BACKUP);
            } catch (Exception e) { Toast.makeText(this, "백업 준비 실패", Toast.LENGTH_LONG).show(); }
        });
    }

    private String decodeJsString(String value) throws Exception {
        if (value == null || "null".equals(value)) throw new IllegalStateException("빈 결과");
        return new JSONArray("[" + value + "]").getString(0);
    }

    private void requestRestore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, REQ_RESTORE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_BACKUP && pendingBackupJson != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("출력 스트림 없음");
                out.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
                pendingBackupJson = null;
                Toast.makeText(this, "세이브 백업 완료", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "백업 저장 실패", Toast.LENGTH_LONG).show(); }
        } else if (requestCode == REQ_RESTORE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("입력 스트림 없음");
                String json = new String(readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(json);
                JSONObject ls = root.optJSONObject("localStorage");
                if (ls == null) throw new IllegalStateException("localStorage 없음");
                new AlertDialog.Builder(this)
                        .setTitle("세이브 복원")
                        .setMessage("현재 앱 저장을 백업 파일의 내용으로 교체합니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("복원", (d, w) -> applyRestore(json))
                        .show();
            } catch (Exception e) { Toast.makeText(this, "백업 파일을 읽을 수 없습니다.", Toast.LENGTH_LONG).show(); }
        }
    }

    private void applyRestore(String json) {
        String quoted = JSONObject.quote(json);
        String js = "(function(){try{var b=JSON.parse(" + quoted + ");var d=b.localStorage||{};localStorage.clear();Object.keys(d).forEach(function(k){if(typeof d[k]==='string')localStorage.setItem(k,d[k]);});return 'OK';}catch(e){return 'ERR:'+e;}})()";
        webView.evaluateJavascript(js, value -> {
            if (value != null && value.contains("OK")) {
                Toast.makeText(this, "세이브 복원 완료", Toast.LENGTH_SHORT).show();
                webView.loadUrl(HOME_URL);
            } else Toast.makeText(this, "세이브 복원 실패", Toast.LENGTH_LONG).show();
        });
    }

    private void showAppMenu() {
        final String[] items = {"게임 계속", "세이브 백업", "세이브 복원", "업데이트 확인", "게임 새로고침", "앱 종료"};
        new AlertDialog.Builder(this).setTitle("ETERNAL PATROL")
                .setItems(items, (d, which) -> {
                    if (which == 1) requestBackup();
                    else if (which == 2) requestRestore();
                    else if (which == 3) checkForUpdates(true);
                    else if (which == 4) webView.loadUrl(HOME_URL);
                    else if (which == 5) finish();
                }).show();
    }

    @Override public void onBackPressed() { showAppMenu(); }

    @Override protected void onPause() {
        saveGameNow();
        if (voiceBridge != null) voiceBridge.stop();
        if (webView != null) { webView.onPause(); webView.pauseTimers(); }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
        if (webView != null) { webView.onResume(); webView.resumeTimers(); }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (voiceBridge != null) { voiceBridge.close(); voiceBridge = null; }
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
