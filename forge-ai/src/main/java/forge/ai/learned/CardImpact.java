package forge.ai.learned;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * learned-ai D22/D25: the card-impact table (forge-learned-ai {@code eval/card_impact.py} output) as the combat solver
 * uses it, identical to {@code train/combat_search.py load_impact} (d9230e6): threat_shrunk by card name for the entries
 * with {@code "creature": true}, their median (value bonus centre) and 90th percentile (protected-piece cut, numpy
 * linear interpolation). JSON: {name: {"creature": bool, "threat_shrunk": x, ...}, ...}; CSV: a header with
 * {@code name} and {@code threat_shrunk} columns (optional {@code creature} column, 1/true/0/false; absent = creature).
 */
public final class CardImpact {
    private final Map<String, Double> threat;
    private final double median, cut;
    private final String id;

    CardImpact(Map<String, Double> threat, String id) {
        this.threat = threat; this.id = id;
        double[] x = new double[threat.size()];
        int i = 0;
        for (double v : threat.values()) x[i++] = v;
        Arrays.sort(x);
        median = percentile(x, 50); cut = percentile(x, 90);
    }

    /** numpy.percentile, linear interpolation, on a sorted array */
    static double percentile(double[] s, double q) {
        if (s.length == 0) return 0;
        double pos = q / 100.0 * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    public Double threat(String name) { return threat.get(name); }
    public double median() { return median; }
    public double cut() { return cut; }
    public int size() { return threat.size(); }
    /** top decile: threat_shrunk >= the 90th percentile of the creature entries */
    public boolean isTop(String name) { Double t = threat.get(name); return t != null && t >= cut; }
    /** file name + '#' + first 8 hex of the sha256 of the bytes (as the model ids) */
    public String id() { return id; }

    public static CardImpact load(String path) throws IOException {
        File f = new File(path);
        byte[] bytes = Files.readAllBytes(f.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        String hash;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            hash = sb.toString();
        } catch (Exception e) { hash = "nohash"; }
        Map<String, Double> m = text.trim().startsWith("{") ? fromJson(text) : fromCsv(text);
        if (m.isEmpty()) throw new IOException("card impact table " + path + ": no creature entries with threat_shrunk");
        return new CardImpact(m, f.getName() + "#" + hash);
    }

    // ---- minimal JSON reader (objects, arrays, strings, numbers, booleans, null) --------------------------------
    @SuppressWarnings("unchecked")
    static Map<String, Double> fromJson(String text) throws IOException {
        Object root = new Json(text).value();
        if (!(root instanceof Map)) throw new IOException("card impact JSON: top level is not an object");
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            if (!Boolean.TRUE.equals(v.get("creature"))) continue;
            Object t = v.get("threat_shrunk");
            if (t instanceof Double) out.put(e.getKey(), (Double) t);
        }
        return out;
    }

    static Map<String, Double> fromCsv(String text) throws IOException {
        String[] lines = text.split("\r?\n");
        List<String> head = splitCsv(lines[0]);
        int ni = head.indexOf("name"), ti = head.indexOf("threat_shrunk"), ci = head.indexOf("creature");
        if (ni < 0 || ti < 0) throw new IOException("card impact CSV: needs name and threat_shrunk columns");
        Map<String, Double> out = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> r = splitCsv(lines[i]);
            if (r.size() <= Math.max(ni, ti)) continue;
            if (ci >= 0 && ci < r.size()) { String c = r.get(ci).trim().toLowerCase(); if (c.equals("0") || c.equals("false")) continue; }
            try { out.put(r.get(ni), Double.parseDouble(r.get(ti).trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(ch); }
            else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() throws IOException {
            ws();
            if (i >= s.length()) throw new IOException("JSON: unexpected end");
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new HashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = string(); ws(); expect(':'); m.put(k, value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("NaN", i)) { i += 3; return Double.NaN; }
            if (s.startsWith("Infinity", i)) { i += 8; return Double.POSITIVE_INFINITY; }
            if (s.startsWith("-Infinity", i)) { i += 9; return Double.NEGATIVE_INFINITY; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IOException("JSON: unexpected '" + c + "' at " + i);
            return Double.parseDouble(s.substring(st, i));
        }
        void expect(char c) throws IOException { if (i >= s.length() || s.charAt(i) != c) throw new IOException("JSON: expected '" + c + "' at " + i); i++; }
        String string() throws IOException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
            throw new IOException("JSON: unterminated string");
        }
    }
}
