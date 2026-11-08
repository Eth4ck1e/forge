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

    /**
     * learned-ai schema 5: spell_choice vectors grew from 70 to 87 columns (appended only). The cycle-3 model scores the
     * logged schema-4 vectors as Python did, and scores them identically when the 17 new columns are appended (any
     * values): the scorer feeds the model its exported width. Same for the shipped profile model (res/ai/learned).
     */
    @Test
    public void schema5VectorsScoreLikeSchema4OnCycle3() throws Exception {
        File model = new File(MODELS + "spell_choice_h1cycle3.onnx");
        File fixture = new File(MODELS + "spell_choice_h1cycle3.parity.json");
        File shipped = new File("../forge-gui/res/ai/learned/spell_choice.onnx");
        if (!model.isFile() || !fixture.isFile()) throw new SkipException("cycle-3 model or fixture not present: " + model.getAbsolutePath());
        AssertJUnit.assertEquals(70, Features.SPELL_LEGACY);
        AssertJUnit.assertEquals(Features.SPELL_LEGACY + Features.SPELL_EXTRA.length, Features.SPELL_CANDIDATE.length);
        Type t = new TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> recs = new Gson().fromJson(new FileReader(fixture), t);
        java.util.Random rnd = new java.util.Random(5);
        for (File f : shipped.isFile() ? new File[] {model, shipped} : new File[] {model}) {
            OnnxScorer scorer = new OnnxScorer(f.getPath());
            AssertJUnit.assertEquals(f.getName(), Features.SPELL_LEGACY, scorer.candidateWidth());
            double maxPy = 0, maxWide = 0; int n = 0;
            for (Map<String, Object> r : recs) {
                float[] x = floats((List<?>) r.get("x"));
                List<?> xaL = (List<?>) r.get("xa");
                float[][] xa = new float[xaL.size()][], wide = new float[xaL.size()][];
                for (int i = 0; i < xa.length; i++) {
                    xa[i] = floats((List<?>) xaL.get(i));
                    AssertJUnit.assertEquals(Features.SPELL_LEGACY, xa[i].length);
                    float[] e = new float[Features.SPELL_EXTRA.length];
                    for (int k = 0; k < e.length; k++) e[k] = rnd.nextInt(9) - 2;
                    wide[i] = Features.concat(xa[i], e);
                }
                float[] old = scorer.score(x, xa), got = scorer.score(x, wide);
                for (int i = 0; i < got.length; i++) {
                    maxWide = Math.max(maxWide, Math.abs(got[i] - old[i])); n++;
                    if (f == model) maxPy = Math.max(maxPy, Math.abs(old[i] - ((Number) ((List<?>) r.get("score")).get(i)).floatValue()));
                }
            }
            System.out.println("schema-5 parity " + scorer.id() + ": " + n + " scores, max |87-col - 70-col| = " + maxWide
                    + (f == model ? ", max |java - python| = " + maxPy : ""));
            AssertJUnit.assertEquals("appended columns changed a score", 0.0, maxWide);
            if (f == model) AssertJUnit.assertTrue("java vs python " + maxPy, maxPy < 1e-4);
        }
    }

    private static float[] floats(List<?> l) {
        float[] f = new float[l.size()];
        for (int i = 0; i < f.length; i++) f[i] = ((Number) l.get(i)).floatValue();
        return f;
    }
}
