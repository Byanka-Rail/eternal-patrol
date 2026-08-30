package com.silentseas.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/Byanka-Rail/eternal-patrol/main/update.json";
    private static final String ALLOWED_GAME_PREFIX = "https://raw.githubusercontent.com/Byanka-Rail/eternal-patrol/";
    private static final String BASE_ORIGIN = "https://app.eternal-patrol.local/";
    private static final String BUNDLED_VERSION = "6.25.5";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private Button updateButton;
    private File downloadedGame;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        downloadedGame = new File(getFilesDir(), "game/ETERNAL_PATROL.html");
        buildUi();
        loadGame();
        main.postDelayed(this::autoCheckIfDue, 1800);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateButton = new Button(this);
        updateButton.setText("업데이트 확인");
        updateButton.setTextSize(11f);
        updateButton.setAllCaps(false);
        updateButton.setAlpha(0.88f);
        updateButton.setPadding(dp(9), 0, dp(9), 0);
        updateButton.setOnClickListener(v -> checkForUpdate(true));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(112), dp(40));
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = dp(8);
        bp.rightMargin = dp(8);
        root.addView(updateButton, bp);
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                return !u.startsWith(BASE_ORIGIN);
            }
        });
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private void loadGame() {
        io.execute(() -> {
            try {
                String html;
                if (downloadedGame.isFile()) html = readAll(new FileInputStream(downloadedGame));
                else html = readAll(getAssets().open("ETERNAL_PATROL.html"));
                String finalHtml = html;
                main.post(() -> webView.loadDataWithBaseURL(BASE_ORIGIN, finalHtml, "text/html", "UTF-8", null));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "게임 로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String installedGameVersion() {
        String v = getPreferences(MODE_PRIVATE).getString("downloadedGameVersion", "");
        return v.isEmpty() ? BUNDLED_VERSION : v;
    }

    private void autoCheckIfDue() {
        long last = getPreferences(MODE_PRIVATE).getLong("lastAutoUpdateCheck", 0L);
        if (System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS) {
            getPreferences(MODE_PRIVATE).edit().putLong("lastAutoUpdateCheck", System.currentTimeMillis()).apply();
            checkForUpdate(false);
        }
    }

    private void checkForUpdate(boolean manual) {
        setUpdateBusy(true);
        io.execute(() -> {
            try {
                JSONObject m = new JSONObject(httpText(MANIFEST_URL));
                String remoteVersion = m.optString("gameVersion", "").trim();
                String gameUrl = m.optString("url", m.optString("gameUrl", "")).trim();
                String sha = m.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
                if (remoteVersion.isEmpty() || gameUrl.isEmpty()) throw new Exception("update.json 필드 누락");
                if (!gameUrl.startsWith(ALLOWED_GAME_PREFIX) || !gameUrl.endsWith("/ETERNAL_PATROL.html")) throw new Exception("허용되지 않은 게임 URL");
                String current = installedGameVersion();
                main.post(() -> {
                    setUpdateBusy(false);
                    if (compareVersions(remoteVersion, current) <= 0) {
                        if (manual) Toast.makeText(this, "최신 버전입니다 · " + current, Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                            .setTitle("ETERNAL PATROL 업데이트")
                            .setMessage(current + " → " + remoteVersion + "\n게임 HTML을 내려받아 교체합니다. 세이브 데이터는 유지됩니다.")
                            .setNegativeButton("나중에", null)
                            .setPositiveButton("업데이트", (d,w) -> downloadAndInstall(remoteVersion, gameUrl, sha))
                            .show();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    setUpdateBusy(false);
                    if (manual) new AlertDialog.Builder(this).setTitle("업데이트 확인 실패").setMessage(e.getMessage()).setPositiveButton("확인", null).show();
                });
            }
        });
    }

    private void downloadAndInstall(String version, String gameUrl, String expectedSha) {
        setUpdateBusy(true);
        io.execute(() -> {
            File dir = downloadedGame.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, "ETERNAL_PATROL.html.tmp");
            try {
                byte[] data = httpBytes(gameUrl);
                if (!expectedSha.isEmpty()) {
                    String actual = sha256(data);
                    if (!actual.equalsIgnoreCase(expectedSha)) throw new Exception("SHA-256 검증 실패\n예상: " + expectedSha + "\n실제: " + actual);
                }
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
                if (downloadedGame.exists() && !downloadedGame.delete()) throw new Exception("기존 게임 파일 교체 실패");
                if (!tmp.renameTo(downloadedGame)) throw new Exception("업데이트 파일 설치 실패");
                getPreferences(MODE_PRIVATE).edit().putString("downloadedGameVersion", version).apply();
                main.post(() -> {
                    setUpdateBusy(false);
                    Toast.makeText(this, "업데이트 완료 · " + version, Toast.LENGTH_SHORT).show();
                    loadGame();
                });
            } catch (Exception e) {
                tmp.delete();
                main.post(() -> {
                    setUpdateBusy(false);
                    new AlertDialog.Builder(this).setTitle("업데이트 실패").setMessage(e.getMessage()).setPositiveButton("확인", null).show();
                });
            }
        });
    }

    private void setUpdateBusy(boolean busy) {
        if (updateButton == null) return;
        updateButton.setEnabled(!busy);
        updateButton.setText(busy ? "확인 중…" : "업데이트 확인");
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("\\."); String[] bb = b.split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i=0;i<n;i++) {
            int x = i<aa.length ? parseInt(aa[i]) : 0;
            int y = i<bb.length ? parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x,y);
        }
        return 0;
    }
    private static int parseInt(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch(Exception e){ return 0; } }

    private static String httpText(String url) throws Exception { return new String(httpBytes(url), StandardCharsets.UTF_8); }
    private static byte[] httpBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(30000); c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "ETERNAL-PATROL-Android/6.25.5");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n=in.read(buf)) >= 0) out.write(buf,0,n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
    private static String readAll(InputStream in) throws Exception {
        try (InputStream x=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[32768]; int n; while((n=x.read(b))>=0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static String sha256(byte[] data) throws Exception {
        byte[] d=MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb=new StringBuilder(); for(byte x:d) sb.append(String.format(Locale.ROOT,"%02x",x)); return sb.toString();
    }

    @Override protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("try{if(window.Campaign&&Campaign.save)Campaign.save();if(window.War&&War.save)War.save();}catch(e){}", null);
            webView.onPause(); webView.pauseTimers();
        }
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if(webView!=null){webView.onResume();webView.resumeTimers();} }
    @Override protected void onDestroy() { io.shutdownNow(); if(webView!=null) webView.destroy(); super.onDestroy(); }
}
