package com.silentseas.game.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Small Android-oriented Supertonic 3 ONNX runner.
 *
 * This is an independent integration written for ETERNAL PATROL against the public
 * Supertonic ONNX model contract. It intentionally keeps only the functionality the
 * game needs: Korean, preset voices, short reports, FP32 CPU inference and cancellation.
 */
public final class SupertonicEngine implements Closeable {
    public static final int DEFAULT_STEPS = 8;
    public static final float DEFAULT_PAUSE_SEC = 0.18f;

    private final OrtEnvironment env;
    private final OrtSession durationSession;
    private final OrtSession textEncoderSession;
    private final OrtSession vectorSession;
    private final OrtSession vocoderSession;
    private final long[] unicodeIndexer;
    private final File voiceDir;
    private final int sampleRate;
    private final int baseChunkSize;
    private final int chunkCompressFactor;
    private final int latentDim;
    private final Map<String, VoiceStyle> styleCache = new HashMap<>();
    private final AtomicLong cancelGeneration = new AtomicLong();

    public SupertonicEngine(File packRoot) throws Exception {
        File onnxDir = new File(packRoot, "onnx");
        voiceDir = new File(packRoot, "voice_styles");
        JSONObject cfg = new JSONObject(readText(new File(onnxDir, "tts.json")));
        JSONObject ae = cfg.getJSONObject("ae");
        JSONObject ttl = cfg.getJSONObject("ttl");
        sampleRate = ae.getInt("sample_rate");
        baseChunkSize = ae.getInt("base_chunk_size");
        chunkCompressFactor = ttl.getInt("chunk_compress_factor");
        latentDim = ttl.getInt("latent_dim");
        unicodeIndexer = readLongArray(new File(onnxDir, "unicode_indexer.json"));

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
        durationSession = env.createSession(new File(onnxDir, "duration_predictor.onnx").getAbsolutePath(), options);
        textEncoderSession = env.createSession(new File(onnxDir, "text_encoder.onnx").getAbsolutePath(), options);
        vectorSession = env.createSession(new File(onnxDir, "vector_estimator.onnx").getAbsolutePath(), options);
        vocoderSession = env.createSession(new File(onnxDir, "vocoder.onnx").getAbsolutePath(), options);
        options.close();
    }

    public int getSampleRate() { return sampleRate; }

    public long cancelAll() { return cancelGeneration.incrementAndGet(); }

    public float[] synthesizeKorean(String text, String voice, float speed) throws Exception {
        long generation = cancelGeneration.get();
        speed = Math.max(0.72f, Math.min(1.35f, speed));
        voice = normalizeVoice(voice);
        List<String> chunks = chunkKorean(cleanForSpeech(text), 120);
        if (chunks.isEmpty()) return new float[0];

        ArrayList<float[]> parts = new ArrayList<>();
        int totalSamples = 0;
        int pauseSamples = Math.max(0, Math.round(sampleRate * DEFAULT_PAUSE_SEC));
        for (int i = 0; i < chunks.size(); i++) {
            checkCanceled(generation);
            float[] part = inferOne(chunks.get(i), voice, speed, DEFAULT_STEPS, generation);
            parts.add(part);
            totalSamples += part.length;
            if (i + 1 < chunks.size()) totalSamples += pauseSamples;
        }
        float[] out = new float[totalSamples];
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            float[] p = parts.get(i);
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
            if (i + 1 < parts.size()) pos += pauseSamples;
        }
        return out;
    }

    private float[] inferOne(String text, String voice, float speed, int totalSteps, long generation) throws Exception {
        checkCanceled(generation);
        VoiceStyle style = getStyle(voice);
        ProcessedText pt = processKorean(text);
        OnnxTensor textIdsTensor = null;
        OnnxTensor textMaskTensor = null;
        OnnxTensor totalStepsTensor = null;
        OrtSession.Result durationResult = null;
        OrtSession.Result textEncoderResult = null;
        try {
            textIdsTensor = longTensor(pt.ids);
            textMaskTensor = floatTensor(pt.mask);

            Map<String, OnnxTensor> durationInputs = new HashMap<>();
            durationInputs.put("text_ids", textIdsTensor);
            durationInputs.put("style_dp", style.dp);
            durationInputs.put("text_mask", textMaskTensor);
            durationResult = durationSession.run(durationInputs);
            float durationSec = extractFirstFloat(durationResult.get(0).getValue()) / speed;
            durationSec = Math.max(0.08f, Math.min(30f, durationSec));

            Map<String, OnnxTensor> encoderInputs = new HashMap<>();
            encoderInputs.put("text_ids", textIdsTensor);
            encoderInputs.put("style_ttl", style.ttl);
            encoderInputs.put("text_mask", textMaskTensor);
            textEncoderResult = textEncoderSession.run(encoderInputs);
            OnnxTensor textEmbedding = (OnnxTensor) textEncoderResult.get(0);

            Latent latent = createNoisyLatent(durationSec);
            float[][][] x = latent.noise;
            float[][][] latentMask = latent.mask;
            totalStepsTensor = OnnxTensor.createTensor(env, new float[]{totalSteps});

            for (int step = 0; step < totalSteps; step++) {
                checkCanceled(generation);
                try (OnnxTensor currentStepTensor = OnnxTensor.createTensor(env, new float[]{step});
                     OnnxTensor noiseTensor = floatTensor(x);
                     OnnxTensor latentMaskTensor = floatTensor(latentMask);
                     OnnxTensor loopTextMask = floatTensor(pt.mask)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("noisy_latent", noiseTensor);
                    inputs.put("text_emb", textEmbedding);
                    inputs.put("style_ttl", style.ttl);
                    inputs.put("latent_mask", latentMaskTensor);
                    inputs.put("text_mask", loopTextMask);
                    inputs.put("current_step", currentStepTensor);
                    inputs.put("total_step", totalStepsTensor);
                    try (OrtSession.Result result = vectorSession.run(inputs)) {
                        x = (float[][][]) result.get(0).getValue();
                    }
                }
            }

            checkCanceled(generation);
            try (OnnxTensor latentTensor = floatTensor(x)) {
                Map<String, OnnxTensor> vocoderInputs = new HashMap<>();
                vocoderInputs.put("latent", latentTensor);
                try (OrtSession.Result result = vocoderSession.run(vocoderInputs)) {
                    Object value = result.get(0).getValue();
                    float[] wav = flattenWave(value);
                    int wanted = Math.max(1, Math.min(wav.length, Math.round(durationSec * sampleRate)));
                    if (wanted == wav.length) return wav;
                    float[] trimmed = new float[wanted];
                    System.arraycopy(wav, 0, trimmed, 0, wanted);
                    return trimmed;
                }
            }
        } finally {
            if (durationResult != null) durationResult.close();
            if (textEncoderResult != null) textEncoderResult.close();
            if (totalStepsTensor != null) totalStepsTensor.close();
            if (textMaskTensor != null) textMaskTensor.close();
            if (textIdsTensor != null) textIdsTensor.close();
        }
    }

    private VoiceStyle getStyle(String voice) throws Exception {
        VoiceStyle cached = styleCache.get(voice);
        if (cached != null) return cached;
        JSONObject root = new JSONObject(readText(new File(voiceDir, voice + ".json")));
        VoiceStyle style = new VoiceStyle(
                styleTensor(root.getJSONObject("style_ttl")),
                styleTensor(root.getJSONObject("style_dp")));
        styleCache.put(voice, style);
        return style;
    }

    private OnnxTensor styleTensor(JSONObject style) throws Exception {
        JSONArray dimsJson = style.getJSONArray("dims");
        if (dimsJson.length() != 3) throw new IllegalArgumentException("voice style dims");
        long d1 = dimsJson.getLong(1), d2 = dimsJson.getLong(2);
        JSONArray outer = style.getJSONArray("data");
        float[] flat = new float[(int) (d1 * d2)];
        int k = 0;
        for (int b = 0; b < outer.length(); b++) {
            JSONArray rows = outer.getJSONArray(b);
            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.getJSONArray(r);
                for (int c = 0; c < row.length(); c++) {
                    if (k >= flat.length) throw new IllegalArgumentException("voice style overflow");
                    flat[k++] = (float) row.getDouble(c);
                }
            }
        }
        if (k != flat.length) throw new IllegalArgumentException("voice style size mismatch");
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1, d1, d2});
    }

    private ProcessedText processKorean(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD);
        normalized = normalized
                .replace('–', '-').replace('‑', '-').replace('—', '-')
                .replace('_', ' ').replace('[', ' ').replace(']', ' ')
                .replace('|', ' ').replace('/', ' ').replace('#', ' ')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
        normalized = stripEmojiAndControls(normalized).replaceAll("\\s+", " ").trim();
        if (!normalized.matches(".*[.!?;:,'\"…。」』】〉》›»]$")) normalized += ".";
        normalized = "<ko>" + normalized + "</ko>";

        int[] cps = normalized.codePoints().toArray();
        long[][] ids = new long[1][cps.length];
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (cp < 0 || cp >= unicodeIndexer.length) throw new IllegalArgumentException("unsupported character U+" + Integer.toHexString(cp));
            ids[0][i] = unicodeIndexer[cp];
        }
        float[][][] mask = new float[1][1][cps.length];
        for (int i = 0; i < cps.length; i++) mask[0][0][i] = 1f;
        return new ProcessedText(ids, mask);
    }

    private Latent createNoisyLatent(float durationSec) {
        long wavLen = Math.max(1, (long) (durationSec * sampleRate));
        int chunkSize = baseChunkSize * chunkCompressFactor;
        int frames = (int) ((wavLen + chunkSize - 1) / chunkSize);
        int channels = latentDim * chunkCompressFactor;
        float[][][] noise = new float[1][channels][frames];
        Random rng = new Random();
        for (int c = 0; c < channels; c++) {
            for (int t = 0; t < frames; t++) {
                double u1 = Math.max(1e-12, rng.nextDouble());
                double u2 = rng.nextDouble();
                noise[0][c][t] = (float) (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2));
            }
        }
        float[][][] mask = new float[1][1][frames];
        for (int t = 0; t < frames; t++) mask[0][0][t] = 1f;
        return new Latent(noise, mask);
    }

    private OnnxTensor floatTensor(float[][][] a) throws OrtException {
        int n0 = a.length, n1 = a[0].length, n2 = a[0][0].length;
        float[] flat = new float[n0 * n1 * n2];
        int k = 0;
        for (float[][] p : a) for (float[] q : p) for (float v : q) flat[k++] = v;
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{n0, n1, n2});
    }

    private OnnxTensor longTensor(long[][] a) throws OrtException {
        int n0 = a.length, n1 = a[0].length;
        long[] flat = new long[n0 * n1];
        int k = 0;
        for (long[] row : a) for (long v : row) flat[k++] = v;
        return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), new long[]{n0, n1});
    }

    private static float extractFirstFloat(Object value) {
        if (value instanceof float[] a && a.length > 0) return a[0];
        if (value instanceof float[][] a && a.length > 0 && a[0].length > 0) return a[0][0];
        if (value instanceof float[][][] a && a.length > 0 && a[0].length > 0 && a[0][0].length > 0) return a[0][0][0];
        throw new IllegalArgumentException("unexpected duration output " + (value == null ? "null" : value.getClass()));
    }

    private static float[] flattenWave(Object value) {
        if (value instanceof float[] a) return a;
        if (value instanceof float[][] a) {
            int n = 0;
            for (float[] row : a) n += row.length;
            float[] out = new float[n];
            int p = 0;
            for (float[] row : a) { System.arraycopy(row, 0, out, p, row.length); p += row.length; }
            return out;
        }
        throw new IllegalArgumentException("unexpected vocoder output " + (value == null ? "null" : value.getClass()));
    }

    private static String cleanForSpeech(String text) {
        String s = text == null ? "" : text.trim();
        s = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 220) s = s.substring(0, 220);
        return s;
    }

    private static List<String> chunkKorean(String text, int maxChars) {
        ArrayList<String> out = new ArrayList<>();
        String rest = text == null ? "" : text.trim();
        while (!rest.isEmpty()) {
            if (rest.length() <= maxChars) { out.add(rest); break; }
            int cut = -1;
            int floor = Math.max(1, maxChars / 2);
            for (int i = Math.min(maxChars, rest.length() - 1); i >= floor; i--) {
                char ch = rest.charAt(i);
                if (ch == '.' || ch == '!' || ch == '?' || ch == ',' || ch == ' ' || ch == '。') { cut = i + 1; break; }
            }
            if (cut < 1) cut = maxChars;
            String part = rest.substring(0, cut).trim();
            if (!part.isEmpty()) out.add(part);
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    private static String stripEmojiAndControls(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean control = type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE;
            boolean emojiLike = (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF);
            if (!control && !emojiLike) b.appendCodePoint(cp);
        });
        return b.toString();
    }

    private static String normalizeVoice(String voice) {
        String v = voice == null ? "M3" : voice.toUpperCase(Locale.ROOT).trim();
        return switch (v) { case "M1", "M2", "M3", "M4", "M5" -> v; default -> "M3"; };
    }

    private void checkCanceled(long generation) throws InterruptedException {
        if (generation != cancelGeneration.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("speech canceled");
    }

    private static String readText(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long[] readLongArray(File f) throws Exception {
        JSONArray a = new JSONArray(readText(f));
        long[] out = new long[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getLong(i);
        return out;
    }

    @Override public synchronized void close() {
        cancelAll();
        for (VoiceStyle style : styleCache.values()) style.close();
        styleCache.clear();
        try { durationSession.close(); } catch (Exception ignored) {}
        try { textEncoderSession.close(); } catch (Exception ignored) {}
        try { vectorSession.close(); } catch (Exception ignored) {}
        try { vocoderSession.close(); } catch (Exception ignored) {}
    }

    private static final class ProcessedText {
        final long[][] ids; final float[][][] mask;
        ProcessedText(long[][] ids, float[][][] mask) { this.ids = ids; this.mask = mask; }
    }
    private static final class Latent {
        final float[][][] noise; final float[][][] mask;
        Latent(float[][][] noise, float[][][] mask) { this.noise = noise; this.mask = mask; }
    }
    private static final class VoiceStyle {
        final OnnxTensor ttl; final OnnxTensor dp;
        VoiceStyle(OnnxTensor ttl, OnnxTensor dp) { this.ttl = ttl; this.dp = dp; }
        void close() { try { ttl.close(); } catch (Exception ignored) {} try { dp.close(); } catch (Exception ignored) {} }
    }
}
