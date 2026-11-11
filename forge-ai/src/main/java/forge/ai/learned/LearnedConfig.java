package forge.ai.learned;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * learned-ai D46 (friends' builds): {@code -Dlearned.config=<learned-play.properties>} is read once at GUI startup;
 * each key sets the matching system property unless that property was given with -D (the flag wins). Keys:
 * seatProfiles, seatDecks, humanDeck (slot 1's deck), gamesRequested, playerId, config (the config label sent with
 * uploads), uploadUrl, uploadToken, learnlogDir (-> learned.learnlog; a relative path resolves against the jar's
 * directory). A relative config path also resolves against the jar's directory when not found from the cwd.
 * Missing / unreadable file: one console line, nothing set.
 */
public final class LearnedConfig {
    private LearnedConfig() {}

    /** config file key -> system property */
    public static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("seatProfiles", "learned.seatProfiles");
        KEYS.put("seatDecks", "learned.seatDecks");
        KEYS.put("humanDeck", "learned.humanDeck");
        KEYS.put("gamesRequested", "learned.gamesRequested");
        KEYS.put("playerId", "learned.playerId");
        KEYS.put("config", "learned.configId");
        KEYS.put("uploadUrl", "learned.uploadUrl");
        KEYS.put("uploadToken", "learned.uploadToken");
        KEYS.put("learnlogDir", "learned.learnlog");
    }

    private static boolean applied;

    /** reads -Dlearned.config once and applies it; safe to call repeatedly */
    public static synchronized void apply() {
        if (applied) return;
        applied = true;
        String path = System.getProperty("learned.config");
        if (path == null || path.trim().isEmpty()) return;
        File f = resolve(path.trim(), jarDir());
        if (!f.isFile()) f = new File(path.trim());
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f.toPath())) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            System.out.println("SIMNOTE learned config " + f.getAbsolutePath() + " not read (" + e.getClass().getSimpleName() + "); continuing without it");
            return;
        }
        Map<String, String> set = applyTo(p, System.getProperties(), jarDir());
        String up = System.getProperty("learned.uploadUrl"), ll = System.getProperty("learned.learnlog");
        if (up != null && !up.trim().isEmpty() && (ll == null || ll.trim().isEmpty())) {   // uploads need live files
            String dflt = resolve("live", jarDir() != null ? jarDir() : new File(System.getProperty("user.home"), "forge-ai")).getPath();
            System.setProperty("learned.learnlog", dflt);
            set.put("learned.learnlog", dflt);
        }
        System.out.println("SIMNOTE learned config " + f.getAbsolutePath() + " applied " + set.keySet()
                + (set.containsKey("learned.uploadToken") ? " (token hidden)" : ""));
    }

    /**
     * Puts every known key of {@code file} into {@code target} unless {@code target} already has its property;
     * returns what was set (property -> value). learnlogDir is resolved against {@code baseDir}.
     */
    public static Map<String, String> applyTo(Properties file, Properties target, File baseDir) {
        Map<String, String> set = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : KEYS.entrySet()) {
            String v = file.getProperty(e.getKey());
            if (v == null || v.trim().isEmpty()) continue;
            String prop = e.getValue();
            String cur = target.getProperty(prop);
            if (cur != null && !cur.trim().isEmpty()) continue;   // -D wins
            v = v.trim();
            if ("learnlogDir".equals(e.getKey())) v = resolve(v, baseDir).getPath();
            target.setProperty(prop, v);
            set.put(prop, v);
        }
        return set;
    }

    /** {@code ~/x} -> home; relative -> against base (when base is known); absolute unchanged */
    public static File resolve(String path, File base) {
        if (path.startsWith("~")) return new File(System.getProperty("user.home") + path.substring(1));
        File f = new File(path);
        if (f.isAbsolute() || base == null) return f;
        return new File(base, path);
    }

    /** directory of the running jar (null when running from classes) */
    public static File jarDir() {
        try {
            File j = new File(LearnedConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return j.isFile() ? j.getParentFile() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** fork build id: -Dlearned.build, else the 11-hex sha in the jar's file name (forge-<sha11>.jar), else "unknown" */
    public static String build() {
        String b = System.getProperty("learned.build");
        if (b != null && !b.trim().isEmpty()) return b.trim();
        try {
            File j = new File(LearnedConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9a-f]{11})").matcher(j.getName());
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "unknown";
    }
}
