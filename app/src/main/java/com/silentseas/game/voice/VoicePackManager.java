package com.silentseas.game.voice;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoicePackManager {
    public interface Listener { void onStatus(JSONObject status); }

    public static final String VARIANT = "FP16";
    public static final int DISPLAY_MB = 200;
    private static final String PACK_NAME = "supertonic3_fp16_v1";
    private static final String BASE = "https://huggingface.co/Kyumdroid/supertonic-3-quant/resolve/main/";
    private static final long MIN_FREE_BYTES = 320L * 1024L * 1024L;

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private volatile double progress = 0;
    private volatile long bytes = 0;
    private volatile String error;

    private static final class PackFile {
        final String path;
        final long expected;
        PackFile(String path, long expected) { this.path = path; this.expected = expected; }
        String url() { return BASE + path + "?download=true"; }
    }

    private final List<PackFile> files = new ArrayList<>();

    public VoicePackManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        files.add(new PackFile("fp16/onnx/duration_predictor.onnx", 2_060_000L));
        files.add(new PackFile("fp16/onnx/text_encoder.onnx", 18_600_000L));
        files.add(new PackFile("fp16/onnx/vector_estimator.onnx", 129_000_000L));
        files.add(new PackFile("fp16/onnx/vocoder.onnx", 50_800_000L));
        files.add(new PackFile("fp16/onnx/tts.json", 8_000L));
        files.add(new PackFile("fp16/onnx/unicode_indexer.json", 278_000L));
        for (int i = 1; i <= 5; i++) files.add(new PackFile("voice_styles/M" + i + ".json", 400_000L));
        files.add(new PackFile("LICENSE", 4_000L));
    }

    public File packRoot() { return new File(context.getFilesDir(), PACK_NAME); }
    private File workRoot() { return new File(context.getFilesDir(), PACK_NAME + ".download"); }

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
                && good(new File(root, "voice_styles/M5.json"), 50_000L);
    }

    public void startDownload() {
        if (!downloading.compareAndSet(false, true)) return;
        error = null;
        progress = 0;
        bytes = 0;
        emit();
        new Thread(() -> {
            try {
                long free = context.getFilesDir().getUsableSpace();
                if (free > 0 && free < MIN_FREE_BYTES) throw new IllegalStateException("저장공간이 부족합니다. 약 320MB 이상 여유 공간이 필요합니다.");
                File work = workRoot();
                if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("음성팩 폴더를 만들 수 없습니다.");
                long totalExpected = 0;
                for (PackFile pf : files) totalExpected += pf.expected;
                long completed = existingBytes(work);
                bytes = completed;
                progress = Math.min(0.98, completed / (double) Math.max(1, totalExpected));
                emit();

                for (PackFile pf : files) {
                    File dst = mappedFile(work, pf.path);
                    if (good(dst, Math.max(100, (long)(pf.expected * 0.90)))) continue;
                    downloadOne(pf, dst, totalExpected);
                }
                File finalRoot = packRoot();
                deleteRecursive(finalRoot);
                if (!work.renameTo(finalRoot)) {
                    copyTree(work, finalRoot);
                    deleteRecursive(work);
                }
                if (!isReady()) throw new IllegalStateException("다운로드한 음성팩 검증에 실패했습니다.");
                progress = 1;
                error = null;
            } catch (Exception e) {
                error = friendly(e);
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
        progress = 0;
        bytes = 0;
        error = null;
        emit();
    }

    private void downloadOne(PackFile pf, File dst, long totalExpected) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("폴더 생성 실패");
        File part = new File(dst.getAbsolutePath() + ".part");
        long existing = part.isFile() ? part.length() : 0;
        HttpURLConnection c = openFollowingRedirects(pf.url(), existing);
        int code = c.getResponseCode();
        boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
        if (!append && existing > 0) { part.delete(); existing = 0; }
        if (code < 200 || code >= 300) throw new IllegalStateException("음성팩 HTTP " + code);

        long baseBefore = Math.max(0, existingBytes(workRoot()) - existing);
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part, append), 64 * 1024)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long local = existing;
            long lastEmit = 0;
            while ((n = in.read(buf)) >= 0) {
                if (!downloading.get()) throw new InterruptedException("download canceled");
                out.write(buf, 0, n);
                local += n;
                bytes = baseBefore + local;
                long now = System.currentTimeMillis();
                if (now - lastEmit > 250) {
                    progress = Math.min(0.98, bytes / (double) Math.max(1, totalExpected));
                    emit();
                    lastEmit = now;
                }
            }
        } finally { c.disconnect(); }
        if (part.length() < 100) throw new IllegalStateException("음성팩 파일이 비어 있습니다: " + pf.path);
        if (dst.exists() && !dst.delete()) throw new IllegalStateException("기존 파일 삭제 실패");
        if (!part.renameTo(dst)) throw new IllegalStateException("음성팩 파일 확정 실패");
        bytes = existingBytes(workRoot());
        progress = Math.min(0.98, bytes / (double) Math.max(1, totalExpected));
        emit();
    }

    private HttpURLConnection openFollowingRedirects(String url, long rangeStart) throws Exception {
        URL u = new URL(url);
        for (int redirect = 0; redirect < 6; redirect++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(45_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "ETERNAL-PATROL-VoicePack/6.24.2");
            c.setRequestProperty("Accept", "application/octet-stream,*/*");
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

    private File mappedFile(File root, String remotePath) {
        String p = remotePath;
        if (p.startsWith("fp16/")) p = p.substring("fp16/".length());
        return new File(root, p);
    }

    private long existingBytes(File root) {
        if (root == null || !root.exists()) return 0;
        if (root.isFile()) return root.length();
        long sum = 0;
        File[] kids = root.listFiles();
        if (kids != null) for (File k : kids) sum += existingBytes(k);
        return sum;
    }

    private static boolean good(File f, long min) { return f.isFile() && f.length() >= min; }

    private void emit() {
        if (listener != null) listener.onStatus(status());
    }

    private static String friendly(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    private static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IllegalStateException("폴더 복사 실패");
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("폴더 생성 실패");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }
}
