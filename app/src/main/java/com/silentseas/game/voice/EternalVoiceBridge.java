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
    private volatile String phase = "idle";
    private volatile boolean speaking;
    private volatile long lastSynthesisMs;
    private volatile int lastSamples;
    private volatile String lastMood = "CALM";
    private volatile boolean closed;

    public EternalVoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.packManager = new VoicePackManager(activity, status -> notifyHtml());
    }

    @JavascriptInterface public String getStatus() {
        return mergedStatus().toString();
    }

    @JavascriptInterface public void clearError() {
        runtimeError = null;
        if (!speaking) phase = "idle";
        notifyHtml();
    }

    @JavascriptInterface public void requestDownload() {
        if (closed || packManager.isReady()) { notifyHtml(); return; }
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle("고품질 승조원 음성팩")
                .setMessage("Supertonic 3 FP32 음성팩을 설치합니다. 약 400MB를 ETERNAL PATROL GitHub Release에서 내려받고, 설치 전 SHA-256을 검증합니다. 기존 FP16 팩을 안전하게 유지한 채 새 팩을 검증하므로 설치 시작 시 약 900MB 이상의 여유 공간이 필요합니다. 새 팩 설치가 끝나면 기존 FP16 팩은 자동 정리됩니다. 설치 후 합성은 기기 안에서 오프라인으로 동작합니다.\n\nWi-Fi 사용을 권장합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("다운로드", (d, w) -> {
                    runtimeError = null;
                    phase = "downloading";
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
                    runtimeError = null;
                    phase = "idle";
                    Toast.makeText(activity, "음성팩을 삭제했습니다.", Toast.LENGTH_SHORT).show();
                }).show());
    }

    @JavascriptInterface public void speak(String role, String voice, String text, String priority, double speed) {
        // Backward-compatible entry point for older HTML updates.
        speakStyled(role, voice, text, priority, speed, 0.90, 90, "CALM");
    }

    @JavascriptInterface public void speakStyled(String role, String voice, String text, String priority,
                                                 double speed, double gain, int pauseMs, String mood) {
        if (closed || !packManager.isReady()) return;
        String clean = normalizeText(text);
        if (clean.isEmpty()) return;
        String p = normalizePriority(priority);
        String v = normalizeVoice(voice);
        String m = normalizeMood(mood);
        float s = (float) Math.max(0.72, Math.min(1.35, speed));
        float g = (float) Math.max(0.02, Math.min(1.0, gain));
        int pause = Math.max(0, Math.min(400, pauseMs));

        runtimeError = null;
        synchronized (queue) {
            long now = System.currentTimeMillis();
            String dedupe = role + "|" + clean;
            Long last = recentText.get(dedupe);
            if (last != null && now - last < 2500) return;
            recentText.put(dedupe, now);
            recentText.entrySet().removeIf(e -> now - e.getValue() > 30_000);

            SpeechTask task = new SpeechTask(role, v, clean, p, s, g, pause, m);
            if ("P0".equals(p)) {
                queue.clear();
                interruptCurrent(false);
                queue.addFirst(task);
            } else {
                while (queue.size() >= 6) queue.pollLast();
                queue.addLast(task);
            }
        }
        phase = engine == null ? "loading" : "queued";
        notifyHtml();
        startDrain();
    }

    @JavascriptInterface public void stop() {
        synchronized (queue) { queue.clear(); }
        interruptCurrent(true);
        speaking = false;
        if (runtimeError == null) phase = "idle";
        notifyHtml();
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
                        speaking = true;
                        phase = engine == null ? "loading" : "synthesizing";
                        notifyHtml();
                        long started = System.currentTimeMillis();
                        SupertonicEngine e = ensureEngine();
                        phase = "synthesizing";
                        notifyHtml();
                        float[] wav = e.synthesizeKorean(task.text, task.voice, task.speed);
                        lastSynthesisMs = Math.max(0, System.currentTimeMillis() - started);
                        lastSamples = wav.length;
                        if (wav.length <= 0) throw new IllegalStateException("합성 결과가 비어 있습니다.");
                        phase = "playing";
                        notifyHtml();
                        lastMood = task.mood;
                        playBlocking(shapeSpeech(wav, e.getSampleRate(), task.mood), e.getSampleRate(), task.gain);
                        if (task.pauseMs > 0) {
                            long until = System.currentTimeMillis() + task.pauseMs;
                            while (System.currentTimeMillis() < until) {
                                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("speech pause canceled");
                                Thread.sleep(Math.min(20L, Math.max(1L, until - System.currentTimeMillis())));
                            }
                        }
                        runtimeError = null;
                        phase = "idle";
                        speaking = false;
                        notifyHtml();
                    } catch (InterruptedException canceled) {
                        Thread.interrupted();
                        speaking = false;
                        if (runtimeError == null) phase = "idle";
                        notifyHtml();
                    } catch (Throwable ex) {
                        runtimeError = friendly(ex);
                        phase = "error";
                        speaking = false;
                        notifyHtml();
                        showErrorToast(runtimeError);
                    }
                }
            } finally {
                draining.set(false);
                speaking = false;
                synchronized (queue) {
                    if (!queue.isEmpty() && !closed) startDrain();
                    else if (runtimeError == null && !closed) {
                        phase = "idle";
                        notifyHtml();
                    }
                }
            }
        });
    }

    private synchronized SupertonicEngine ensureEngine() throws Exception {
        if (engine != null) return engine;
        if (!packManager.isReady()) throw new IllegalStateException("음성팩이 설치되지 않았습니다.");
        phase = "loading";
        notifyHtml();
        engine = new SupertonicEngine(packManager.packRoot());
        return engine;
    }

    /**
     * Keep the model voice recognizable while taking the sterile edge off raw TTS.
     * A light 80 Hz high-pass, ~7.4 kHz low-pass, soft compression and two tiny
     * early reflections approximate speech heard inside a steel compartment.
     */
    private static float[] shapeSpeech(float[] input, int sampleRate, String mood) {
        if (input == null || input.length == 0) return input;
        float[] out = new float[input.length];
        final float dt = 1f / Math.max(8000, sampleRate);
        final float rcHp = 1f / (2f * (float)Math.PI * 80f);
        final float aHp = rcHp / (rcHp + dt);
        final float rcLp = 1f / (2f * (float)Math.PI * 7400f);
        final float aLp = dt / (rcLp + dt);
        float prevX = 0f, hp = 0f, lp = 0f;
        for (int i = 0; i < input.length; i++) {
            float x = input[i];
            hp = aHp * (hp + x - prevX); prevX = x;
            lp += aLp * (hp - lp);
            float y = (float)Math.tanh(lp * 1.10f) / 1.10f;
            out[i] = y;
        }
        int d1 = Math.max(1, Math.round(sampleRate * 0.013f));
        int d2 = Math.max(1, Math.round(sampleRate * 0.029f));
        float r1 = "URGENT".equals(mood) ? 0.035f : 0.055f;
        float r2 = "URGENT".equals(mood) ? 0.018f : 0.032f;
        for (int i = 0; i < out.length; i++) {
            float y = out[i];
            if (i >= d1) y += out[i - d1] * r1;
            if (i >= d2) y += out[i - d2] * r2;
            out[i] = Math.max(-0.98f, Math.min(0.98f, y));
        }
        return out;
    }

    /**
     * Stream native float PCM directly to AudioTrack. This matches the model output
     * and avoids an unnecessary float->int16 conversion in the Android path.
     */
    private void playBlocking(float[] wav, int sampleRate, float gain) throws InterruptedException {
        final long generation = audioGeneration.get();
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (min <= 0) throw new IllegalStateException("AudioTrack 초기화 실패: " + min);
        int buffer = Math.max(min, 32 * 1024);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try { track.release(); } catch (Exception ignored) {}
            throw new IllegalStateException("AudioTrack이 초기화되지 않았습니다.");
        }
        currentTrack = track;
        try {
            track.setVolume(Math.max(0.02f, Math.min(1.0f, gain)));
            track.play();
            int offset = 0;
            while (offset < wav.length && !closed) {
                if (generation != audioGeneration.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("playback canceled");
                int count = Math.min(8192, wav.length - offset);
                int n = track.write(wav, offset, count, AudioTrack.WRITE_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioTrack write " + n);
                offset += n;
            }
            while (!closed && track.getPlaybackHeadPosition() < wav.length) {
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
    }

    private JSONObject mergedStatus() {
        JSONObject o = packManager.status();
        try {
            if (runtimeError != null) o.put("error", runtimeError);
            o.put("engine", "Supertonic3-ONNX");
            o.put("voices", "M1,M2,M3,M4,M5");
            o.put("phase", phase);
            o.put("speaking", speaking);
            o.put("engineLoaded", engine != null);
            o.put("lastSynthesisMs", lastSynthesisMs);
            o.put("lastSamples", lastSamples);
            o.put("lastMood", lastMood);
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

    private void showErrorToast(String error) {
        activity.runOnUiThread(() -> Toast.makeText(activity, "승조원 음성 오류: " + error, Toast.LENGTH_LONG).show());
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

    private static String normalizeMood(String mood) {
        String m = mood == null ? "CALM" : mood.toUpperCase(Locale.ROOT).trim();
        return switch (m) { case "CALM", "ALERT", "URGENT", "STRAINED" -> m; default -> "CALM"; };
    }

    private static String friendly(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        s = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private static final class SpeechTask {
        final String role, voice, text, priority, mood;
        final float speed, gain;
        final int pauseMs;
        SpeechTask(String role, String voice, String text, String priority, float speed, float gain, int pauseMs, String mood) {
            this.role = role; this.voice = voice; this.text = text; this.priority = priority;
            this.speed = speed; this.gain = gain; this.pauseMs = pauseMs; this.mood = mood;
        }
    }
}
