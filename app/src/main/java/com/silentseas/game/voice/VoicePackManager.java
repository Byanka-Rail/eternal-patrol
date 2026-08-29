package com.silentseas.game.voice;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Optional Supertonic 3 FP16 voice-pack manager.
 *
 * Runtime clients download only from the ETERNAL PATROL GitHub Release.  The
 * upstream model provenance remains documented inside the pack and in the game
 * third-party notices.  A small release manifest supplies the pack URL, exact
 * byte length and SHA-256 so the APK never depends on an upstream third-party
 * host at play time.
 */
public final class VoicePackManager {
    public interface Listener { void onStatus(JSONObject status); }

    public static final String VARIANT = "FP16";
    public static final int DISPLAY_MB = 200;

    private static final String STORAGE_NAME = "supertonic3_fp16_v1"; // keep path so legacy pack can be replaced atomically
    private static final String EXPECTED_PACK_ID = "supertonic3_fp16_ep_v2";
    private static final String OFFICIAL_REVISION = "724fb5abbf5502583fb520898d45929e62f02c0b";
    private static final String RELEASE_TAG = "voicepack-v2";
    private static final String RELEASE_BASE =
            "https://github.com/Byanka-Rail/eternal-patrol/releases/download/" + RELEASE_TAG + "/";
    private static final String MANIFEST_URL = RELEASE_BASE + "ETERNAL_PATROL_SUPERTONIC3_FP16_V2.json";
    private static final String ALLOWED_PACK_PREFIX = RELEASE_BASE;
    private static final long MIN_FREE_BYTES = 430L * 1024L * 1024L;

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private volatile double progress = 0;
    private volatile long bytes = 0;
    private volatile String error;
    private volatile String phase = "idle";
    private volatile String source = "ETERNAL PATROL GitHub Release";

    public VoicePackManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public File packRoot() { return new File(context.getFilesDir(), STORAGE_NAME); }
    private File workRoot() { return new File(context.getFilesDir(), STORAGE_NAME + ".download"); }
    private File zipPart() { return new File(context.getFilesDir(), STORAGE_NAME + ".zip.part"); }
    private File zipReady() { return new File(context.getFilesDir(), STORAGE_NAME + ".zip"); }

    public synchronized JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("available", true);
            o.put("ready", isReady());
            o.put("downloading", downloading.get());
            o.put("progress", progress);
            o.put("bytes", bytes);
            o.put("error", error == null ? JSONObject.NULL : error);
            o.put("variant", VARIANT);
            o.put("packMB", DISPLAY_MB);
            o.put("packPhase", phase);
            o.put("source", source);
            o.put("releaseTag", RELEASE_TAG);
        } catch (Exception ignored) {}
        return o;
    }

    public boolean isReady() {
        File root = packRoot();
        return good(new File(root, "onnx/duration_predictor.onnx"), 1_000_000L)
                && good(new File(root, "onnx/text_encoder.onnx"), 10_000_000L)
                && good(new File(root, "onnx/vector_estimator.onnx"), 80_000_000L)
                && good(new File(root, "onnx/vocoder.onnx"), 30_000_000L)
                && good(new File(root, "onnx/tts.json"), 100L)
                && good(new File(root, "onnx/unicode_indexer.json"), 50_000L)
                && good(new File(root, "voice_styles/M1.json"), 50_000L)
                && good(new File(root, "voice_styles/M2.json"), 50_000L)
                && good(new File(root, "voice_styles/M3.json"), 50_000L)
                && good(new File(root, "voice_styles/M4.json"), 50_000L)
                && good(new File(root, "voice_styles/M5.json"), 50_000L)
                && good(new File(root, "LICENSE"), 500L)
                && good(new File(root, "THIRD_PARTY_NOTICES.txt"), 100L)
                && good(new File(root, "CONVERSION_NOTES.txt"), 100L)
                && validPackInfo(root);
    }

    private static boolean validPackInfo(File root) {
        File infoFile = new File(root, "PACK_INFO.json");
        if (!good(infoFile, 100L)) return false;
        try (FileInputStream in = new FileInputStream(infoFile)) {
            byte[] raw = readLimited(in, 32 * 1024);
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            return EXPECTED_PACK_ID.equals(o.optString("pack", ""))
                    && "Supertone/supertonic-3".equals(o.optString("originalModel", ""))
                    && OFFICIAL_REVISION.equals(o.optString("officialRevision", ""))
                    && "ETERNAL PATROL GitHub Actions".equals(o.optString("convertedBy", ""))
                    && "FP16".equalsIgnoreCase(o.optString("variant", ""));
        } catch (Exception e) {
            return false;
        }
    }

    public void startDownload() {
        if (!downloading.compareAndSet(false, true)) return;
        error = null;
        progress = 0;
        bytes = 0;
        phase = "manifest";
        emit();

        new Thread(() -> {
            try {
                long free = context.getFilesDir().getUsableSpace();
                if (free > 0 && free < MIN_FREE_BYTES) {
                    throw new IllegalStateException("저장공간이 부족합니다. 약 430MB 이상 여유 공간이 필요합니다.");
                }

                PackManifest manifest = fetchManifest();
                if (!manifest.url.startsWith(ALLOWED_PACK_PREFIX)) {
                    throw new IllegalStateException("허용되지 않은 음성팩 배포 주소입니다.");
                }
                if (manifest.bytes < 120L * 1024L * 1024L || manifest.bytes > 320L * 1024L * 1024L) {
                    throw new IllegalStateException("음성팩 크기 정보가 예상 범위를 벗어났습니다.");
                }
                if (!manifest.sha256.matches("(?i)[0-9a-f]{64}")) {
                    throw new IllegalStateException("음성팩 SHA-256 정보가 올바르지 않습니다.");
                }

                phase = "download";
                emit();
                downloadZip(manifest);

                phase = "verify";
                emit();
                String actual = sha256(zipReady());
                if (!actual.equalsIgnoreCase(manifest.sha256)) {
                    zipReady().delete();
                    throw new IllegalStateException("음성팩 SHA-256 검증에 실패했습니다.");
                }

                phase = "install";
                emit();
                File work = workRoot();
                deleteRecursive(work);
                if (!work.mkdirs()) throw new IllegalStateException("음성팩 설치 폴더를 만들 수 없습니다.");
                unzipSafe(zipReady(), work);
                if (!isReadyAt(work)) throw new IllegalStateException("압축 해제된 음성팩 검증에 실패했습니다.");

                File finalRoot = packRoot();
                deleteRecursive(finalRoot);
                if (!work.renameTo(finalRoot)) {
                    copyTree(work, finalRoot);
                    deleteRecursive(work);
                }
                if (!isReady()) throw new IllegalStateException("설치된 음성팩 검증에 실패했습니다.");

                zipReady().delete();
                zipPart().delete();
                progress = 1.0;
                bytes = manifest.bytes;
                error = null;
                phase = "ready";
            } catch (Exception e) {
                error = friendly(e);
                phase = "error";
            } finally {
                downloading.set(false);
                emit();
            }
        }, "EP-voice-pack").start();
    }

    public void removePack() {
        downloading.set(false);
        deleteRecursive(packRoot());
        deleteRecursive(workRoot());
        zipPart().delete();
        zipReady().delete();
        progress = 0;
        bytes = 0;
        error = null;
        phase = "idle";
        emit();
    }

    private PackManifest fetchManifest() throws Exception {
        HttpURLConnection c = openFollowingRedirects(MANIFEST_URL, 0);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("음성팩 목록 HTTP " + code);
            byte[] raw = readLimited(c.getInputStream(), 64 * 1024);
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            int schema = o.optInt("schema", 0);
            String pack = o.optString("pack", "");
            String url = o.optString("url", "");
            String hash = o.optString("sha256", "").trim();
            long len = o.optLong("bytes", 0L);
            if (schema != 2 || !EXPECTED_PACK_ID.equals(pack) || url.isEmpty() || hash.isEmpty() || len <= 0) {
                throw new IllegalStateException("음성팩 목록 형식이 올바르지 않습니다.");
            }
            return new PackManifest(url, hash, len);
        } finally {
            c.disconnect();
        }
    }

    private void downloadZip(PackManifest manifest) throws Exception {
        File part = zipPart();
        File ready = zipReady();
        ready.delete();
        long existing = part.isFile() ? part.length() : 0;
        if (existing > manifest.bytes) {
            part.delete();
            existing = 0;
        }

        HttpURLConnection c = openFollowingRedirects(manifest.url, existing);
        try {
            int code = c.getResponseCode();
            boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            if (!append && existing > 0) {
                part.delete();
                existing = 0;
            }
            if (code < 200 || code >= 300) throw new IllegalStateException("음성팩 HTTP " + code);

            try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part, append), 128 * 1024)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                long local = existing;
                long lastEmit = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (!downloading.get()) throw new InterruptedException("download canceled");
                    out.write(buf, 0, n);
                    local += n;
                    bytes = local;
                    long now = System.currentTimeMillis();
                    if (now - lastEmit > 250) {
                        progress = Math.min(0.96, local / (double) Math.max(1L, manifest.bytes));
                        emit();
                        lastEmit = now;
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        long got = part.length();
        long tolerance = Math.max(4096L, manifest.bytes / 1000L);
        if (Math.abs(got - manifest.bytes) > tolerance) {
            throw new IllegalStateException("음성팩 다운로드 크기가 일치하지 않습니다.");
        }
        if (!part.renameTo(ready)) throw new IllegalStateException("음성팩 다운로드 파일을 확정할 수 없습니다.");
        progress = 0.97;
        bytes = got;
        emit();
    }

    private HttpURLConnection openFollowingRedirects(String url, long rangeStart) throws Exception {
        URL u = new URL(url);
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "ETERNAL-PATROL-VoicePack/6.24.6");
            c.setRequestProperty("Accept", "application/octet-stream,application/json,*/*");
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IllegalStateException("리디렉션 주소 없음");
                u = new URL(u, loc);
                continue;
            }
            return c;
        }
        throw new IllegalStateException("리디렉션이 너무 많습니다.");
    }

    private static byte[] readLimited(InputStream src, int max) throws Exception {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = src.read(buf)) >= 0) {
            total += n;
            if (total > max) throw new IllegalStateException("음성팩 목록이 너무 큽니다.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void unzipSafe(File zip, File root) throws Exception {
        String rootPath = root.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 128 * 1024))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zin.getNextEntry()) != null) {
                if (++count > 64) throw new IllegalStateException("음성팩 파일 수가 비정상적입니다.");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IllegalStateException("안전하지 않은 음성팩 경로입니다.");
                }
                File out = new File(root, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(rootPath)) throw new IllegalStateException("안전하지 않은 음성팩 경로입니다.");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("음성팩 폴더 생성 실패");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("음성팩 폴더 생성 실패");
                    try (BufferedOutputStream dst = new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = zin.read(buf)) >= 0) dst.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f), 128 * 1024)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static boolean isReadyAt(File root) {
        return good(new File(root, "onnx/duration_predictor.onnx"), 1_000_000L)
                && good(new File(root, "onnx/text_encoder.onnx"), 10_000_000L)
                && good(new File(root, "onnx/vector_estimator.onnx"), 80_000_000L)
                && good(new File(root, "onnx/vocoder.onnx"), 30_000_000L)
                && good(new File(root, "onnx/tts.json"), 100L)
                && good(new File(root, "onnx/unicode_indexer.json"), 50_000L)
                && good(new File(root, "voice_styles/M1.json"), 50_000L)
                && good(new File(root, "voice_styles/M5.json"), 50_000L)
                && good(new File(root, "LICENSE"), 500L)
                && good(new File(root, "THIRD_PARTY_NOTICES.txt"), 100L)
                && good(new File(root, "CONVERSION_NOTES.txt"), 100L)
                && validPackInfo(root);
    }

    private static boolean good(File f, long min) { return f.isFile() && f.length() >= min; }

    private void emit() {
        if (listener != null) listener.onStatus(status());
    }

    private static String friendly(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        s = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IllegalStateException("음성팩 폴더 복사 실패");
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("음성팩 폴더 복사 실패");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src), 128 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 128 * 1024)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    private static final class PackManifest {
        final String url;
        final String sha256;
        final long bytes;
        PackManifest(String url, String sha256, long bytes) {
            this.url = url;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }
}
