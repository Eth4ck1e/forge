package forge.ai.learned;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * learned-ai M3.2 / M3.3: the Java ONNX scorer reproduces the Python onnxruntime scores on a fixture of logged
 * decisions, and a 3-candidate call stays under the 200 µs budget. Skipped when the model repo is not checked out
 * next to the fork.
 */
public class OnnxScorerTest {
    private static final String MODELS = "../../forge-learned-ai/models/";

    @Test
    public void matchesPythonScoresAndMeetsLatencyBudget() throws Exception {
        File model = new File(MODELS + "attack_target_v1.onnx");
        File fixture = new File(MODELS + "attack_target_v1.parity.json");
        if (!model.isFile() || !fixture.isFile()) {
            throw new SkipException("model or parity fixture not present: " + model.getAbsolutePath());
        }
        Type t = new TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> recs = new Gson().fromJson(new FileReader(fixture), t);
        OnnxScorer scorer = new OnnxScorer(model.getPath());
        double maxDiff = 0; int n = 0;
        for (Map<String, Object> r : recs) {
            float[] x = floats((List<?>) r.get("x"));
            List<?> xaL = (List<?>) r.get("xa");
            float[][] xa = new float[xaL.size()][];
            for (int i = 0; i < xa.length; i++) xa[i] = floats((List<?>) xaL.get(i));
            float[] expect = floats((List<?>) r.get("score"));
            float[] got = scorer.score(x, xa);
            AssertJUnit.assertEquals(expect.length, got.length);
            for (int i = 0; i < got.length; i++) { maxDiff = Math.max(maxDiff, Math.abs(got[i] - expect[i])); n++; }
        }
        System.out.println("onnx parity: " + n + " scores, max |java - python| = " + maxDiff);
        AssertJUnit.assertTrue("java vs python onnx scores differ by " + maxDiff, maxDiff < 1e-4);

        // latency: warm up, then time 3-candidate calls
        float[] x = floats((List<?>) recs.get(0).get("x"));
        float[][] xa = new float[3][]; for (int i = 0; i < 3; i++) xa[i] = floats((List<?>) ((List<?>) recs.get(0).get("xa")).get(0));
        for (int i = 0; i < 500; i++) scorer.score(x, xa);
        long t0 = System.nanoTime(); int calls = 5000;
        for (int i = 0; i < calls; i++) scorer.score(x, xa);
        double micros = (System.nanoTime() - t0) / 1000.0 / calls;
        System.out.println("onnx latency: " + micros + " us per 3-candidate call");
        AssertJUnit.assertTrue("latency " + micros + " us exceeds the 200 us budget", micros < 200);
    }

    private static float[] floats(List<?> l) {
        float[] f = new float[l.size()];
        for (int i = 0; i < f.length; i++) f[i] = ((Number) l.get(i)).floatValue();
        return f;
    }
}
