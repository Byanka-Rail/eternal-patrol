package com.silentseas.game.voice;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Native bridge exposed to the bundled HTML as window.EternalVoice. */
public final class EternalVoiceBridge implements Closeable {
    private final Activity activity;
    private final WebView webView;
    private final VoicePackManager packManager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EP-Supertonic");
        t.setDaemon(true);
        return t;
    });
    private final ArrayDeque<SpeechTask> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicLong audioGeneration = new AtomicLong();
    private final Map<String, Long> recentText = new HashMap<>();

    private volatile SupertonicEngine engine;
    private volatile AudioTrack currentTrack;
    private volatile String runtimeError;
    private volatile boolean closed;

    public EternalVoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.packManager = new VoicePackManager(activity, status -> notifyHtml());
    }

    @JavascriptInterface public String getStatus() {
        return mergedStatus().toString();
    }

    @JavascriptInterface public void requestDownload() {
        if (closed || packManager.isReady()) { notifyHtml(); return; }
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle("고품질 승조원 음성팩")
                .setMessage("Supertonic 3 FP16 음성팩을 설치합니다. 약 200MB를 내려받고, 설치 중에는 추가 여유 공간이 필요합니다. 설치 후 합성은 기기 안에서 오프라인으로 동작합니다.\n\nWi-Fi 사용을 권장합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("다운로드", (d, w) -> {
                    runtimeError = null;
                    packManager.startDownload();
                }).show());
    }

    @JavascriptInterface public void removePack() {
        if (closed) return;
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle("승조원 음성팩 삭제")
                .setMessage("다운로드한 Supertonic 3 음성팩을 삭제합니다. 게임과 세이브에는 영향이 없습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    stop();
                    closeEngine();
                    packManager.removePack();
                    Toast.makeText(activity, "음성팩을 삭제했습니다.", Toast.LENGTH_SHORT).show();
                }).show());
    }

    @JavascriptInterface public void speak(String role, String voice, String text, String priority, double speed) {
        if (closed || !packManager.isReady()) return;
        String clean = normalizeText(text);
        if (clean.isEmpty()) return;
        String p = normalizePriority(priority);
        String v = normalizeVoice(voice);
        float s = (float) Math.max(0.72, Math.min(1.35, speed));

        synchronized (queue) {
            long now = System.currentTimeMillis();
            String dedupe = role + "|" + clean;
            Long last = recentText.get(dedupe);
            if (last != null && now - last < 2500) return;
            recentText.put(dedupe, now);
            recentText.entrySet().removeIf(e -> now - e.getValue() > 30_000);

            if ("P0".equals(p)) {
                queue.clear();
                interruptCurrent(false);
                queue.addFirst(new SpeechTask(role, v, clean, p, s));
            } else {
                while (queue.size() >= 6) queue.pollLast();
                queue.addLast(new SpeechTask(role, v, clean, p, s));
            }
        }
        startDrain();
    }

    @JavascriptInterface public void stop() {
        synchronized (queue) { queue.clear(); }
        interruptCurrent(true);
    }

    private void startDrain() {
        if (!draining.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                while (!closed) {
                    SpeechTask task;
                    synchronized (queue) { task = queue.pollFirst(); }
                    if (task == null) break;
                    try {
                        SupertonicEngine e = ensureEngine();
                        float[] wav = e.synthesizeKorean(task.text, task.voice, task.speed);
                        if (wav.length > 0) playBlocking(wav, e.getSampleRate());
                        runtimeError = null;
                    } catch (InterruptedException canceled) {
                        Thread.interrupted();
                    } catch (Throwable ex) {
                        runtimeError = friendly(ex);
                        notifyHtml();
                    }
                }
            } finally {
                draining.set(false);
                synchronized (queue) {
                    if (!queue.isEmpty() && !closed) startDrain();
                }
            }
        });
    }

    private synchronized SupertonicEngine ensureEngine() throws Exception {
        if (engine != null) return engine;
        if (!packManager.isReady()) throw new IllegalStateException("음성팩이 설치되지 않았습니다.");
        engine = new SupertonicEngine(packManager.packRoot());
        return engine;
    }

    private void playBlocking(float[] wav, int sampleRate) throws InterruptedException {
        final long generation = audioGeneration.get();
        short[] pcm = new short[wav.length];
        for (int i = 0; i < wav.length; i++) {
            float v = Math.max(-1f, Math.min(1f, wav[i]));
            pcm[i] = (short) Math.round(v * 32767f);
        }
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min, 16 * 1024);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        currentTrack = track;
        try {
            track.setVolume(1.0f);
            track.play();
            int offset = 0;
            while (offset < pcm.length && !closed) {
                if (generation != audioGeneration.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("playback canceled");
                int n = track.write(pcm, offset, Math.min(8192, pcm.length - offset), AudioTrack.WRITE_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioTrack write " + n);
                offset += n;
            }
            // WRITE_BLOCKING waits for buffer space, not for the speaker to finish the queued tail.
            while (!closed && track.getPlaybackHeadPosition() < pcm.length) {
                if (generation != audioGeneration.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("playback canceled");
                Thread.sleep(12L);
            }
        } finally {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.flush(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            if (currentTrack == track) currentTrack = null;
        }
    }

    private void interruptCurrent(boolean interruptWorker) {
        audioGeneration.incrementAndGet();
        SupertonicEngine e = engine;
        if (e != null) e.cancelAll();
        AudioTrack t = currentTrack;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.stop(); } catch (Exception ignored) {}
        }
        // The executor thread cooperatively sees engine cancellation at each inference step.
        // We avoid shutting down the executor so later reports can continue.
    }

    private JSONObject mergedStatus() {
        JSONObject o = packManager.status();
        try {
            if (runtimeError != null) o.put("error", runtimeError);
            o.put("engine", "Supertonic3-ONNX");
            o.put("voices", "M1,M2,M3,M4,M5");
        } catch (Exception ignored) {}
        return o;
    }

    private void notifyHtml() {
        if (closed) return;
        JSONObject s = mergedStatus();
        activity.runOnUiThread(() -> {
            if (webView == null) return;
            String js = "try{if(window.EPVoice&&EPVoice.nativeStatus)EPVoice.nativeStatus(" + s.toString() + ");}catch(e){}";
            webView.evaluateJavascript(js, null);
        });
    }

    public void onPageReady() { notifyHtml(); }

    private synchronized void closeEngine() {
        if (engine != null) {
            try { engine.close(); } catch (Exception ignored) {}
            engine = null;
        }
    }

    @Override public void close() {
        closed = true;
        stop();
        closeEngine();
        worker.shutdownNow();
    }

    private static String normalizeText(String text) {
        String s = text == null ? "" : text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 220) s = s.substring(0, 220);
        return s;
    }

    private static String normalizePriority(String p) {
        String x = p == null ? "P2" : p.toUpperCase(Locale.ROOT);
        return "P0".equals(x) || "P1".equals(x) ? x : "P2";
    }

    private static String normalizeVoice(String voice) {
        String v = voice == null ? "M3" : voice.toUpperCase(Locale.ROOT).trim();
        return switch (v) { case "M1", "M2", "M3", "M4", "M5" -> v; default -> "M3"; };
    }

    private static String friendly(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static final class SpeechTask {
        final String role, voice, text, priority;
        final float speed;
        SpeechTask(String role, String voice, String text, String priority, float speed) {
            this.role = role; this.voice = voice; this.text = text; this.priority = priority; this.speed = speed;
        }
    }
}
