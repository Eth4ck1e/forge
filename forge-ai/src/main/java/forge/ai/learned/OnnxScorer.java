package forge.ai.learned;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * learned-ai M3.2: scores (state, candidate) pairs with an ONNX model exported by {@code train/attack_target.py}
 * (inputs {@code x[n,570]}, {@code a[n,9]}; output {@code score[n,1]}). One session per process; a call runs all
 * candidates of a decision as one batch. Synchronised because AI decisions can come from several game threads.
 */
public final class OnnxScorer implements LearnedHooks.Scorer {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String path;
    private final String id;
    private long calls, nanos;
    /** input widths the model was exported with (-1 = dynamic): longer vectors are cut to them (schema 5 appends columns) */
    private final int xWidth, aWidth;

    public OnnxScorer(String modelPath) throws OrtException {
        path = modelPath;
        id = idOf(modelPath);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);   // sim workers already saturate the cores; the model is tiny
        session = env.createSession(modelPath, opts);
        xWidth = width(session, "x");
        aWidth = width(session, "a");
    }

    private static int width(OrtSession s, String input) throws OrtException {
        ai.onnxruntime.NodeInfo ni = s.getInputInfo().get(input);
        if (ni == null || !(ni.getInfo() instanceof ai.onnxruntime.TensorInfo)) return -1;
        long[] shape = ((ai.onnxruntime.TensorInfo) ni.getInfo()).getShape();
        return shape.length == 2 && shape[1] > 0 ? (int) shape[1] : -1;
    }

    /** the candidate width the model reads (-1 = dynamic) */
    public int candidateWidth() { return aWidth; }

    @Override
    public synchronized float[] score(float[] x, float[][] xa) {
        int n = xa.length;
        // learned-ai schema 5: a model exported for a narrower vector reads its own leading columns (append-only schema)
        final int xw = xWidth > 0 && xWidth < x.length ? xWidth : x.length;
        final int aw = aWidth > 0 && aWidth < xa[0].length ? aWidth : xa[0].length;
        float[] xs = new float[n * xw];
        float[] as = new float[n * aw];
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, xs, i * xw, xw);
            System.arraycopy(xa[i], 0, as, i * aw, aw);
        }
        long t0 = System.nanoTime();
        try (OnnxTensor tx = OnnxTensor.createTensor(env, FloatBuffer.wrap(xs), new long[] {n, xw});
             OnnxTensor ta = OnnxTensor.createTensor(env, FloatBuffer.wrap(as), new long[] {n, aw})) {
            Map<String, OnnxTensor> in = new HashMap<>();
            in.put("x", tx); in.put("a", ta);
            try (OrtSession.Result r = session.run(in)) {
                float[][] out = (float[][]) r.get(0).getValue();
                float[] s = new float[n];
                for (int i = 0; i < n; i++) s[i] = out[i][0];
                return s;
            }
        } catch (OrtException e) {
            throw new RuntimeException("onnx scoring failed (" + path + "): " + e.getMessage(), e);
        } finally {
            calls++; nanos += System.nanoTime() - t0;
        }
    }

    /** Mean microseconds per scoring call so far (M3.2 budget: under 200 µs). */
    public double meanMicros() { return calls == 0 ? 0 : nanos / 1000.0 / calls; }
    public long calls() { return calls; }
    public String path() { return path; }
    /** D36: "file.onnx#<first 8 hex of sha256>": names the model in decision records and SIMNOTE / SIMMODEL lines. */
    @Override
    public String id() { return id; }

    static String idOf(String modelPath) {
        java.io.File f = new java.io.File(modelPath);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(java.nio.file.Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder(f.getName()).append('#');
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return f.getName();
        }
    }
}
