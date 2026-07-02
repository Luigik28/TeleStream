package org.telegram.tv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async team logo loader backed by TheSportsDB v1 API.
 *
 * Call {@link #load} from the main thread; callbacks are delivered on the main thread.
 * Call {@link #shutdown} in onDestroy to release the thread pool.
 */
public final class TeamLogoLoader {

    // null value = fetch in-flight; prevents duplicate network requests for the same key
    private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final float density;

    public TeamLogoLoader(float density) {
        this.density = density;
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Loads the team badge into {@code iv}. Must be called on the main thread.
     * Shows an initials badge immediately as a placeholder while the network fetch runs.
     */
    public void load(String teamName, ImageView iv) {
        String normalized = normalize(teamName);
        String key = normalized.toLowerCase(Locale.ROOT);

        if (cache.containsKey(key)) {
            Bitmap cached = cache.get(key);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                // Previous fetch failed — show initials permanently
                setInitialsBadge(iv, teamName);
            }
            return;
        }

        setInitialsBadge(iv, teamName);  // immediate placeholder
        cache.put(key, null);            // sentinel — fetch is now in-flight

        executor.execute(() -> fetchAndCache(normalized, key, teamName, iv));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ─── Internals ─────────────────────────────────────────────────────────────

    private void fetchAndCache(String normalized, String cacheKey, String displayName, ImageView iv) {
        try {
            String encoded = URLEncoder.encode(normalized, "UTF-8");
            HttpURLConnection conn = openConn(
                "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=" + encoded);
            if (conn.getResponseCode() != 200) return;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject json = new JSONObject(sb.toString());
            JSONArray teams = json.optJSONArray("teams");
            if (teams == null || teams.length() == 0) {
                android.util.Log.d("TeamLogoLoader", "no match for [" + normalized + "]");
                return;
            }

            String badgeUrl = teams.getJSONObject(0).optString("strTeamBadge");
            if (badgeUrl == null || badgeUrl.isEmpty()) return;

            HttpURLConnection imgConn = openConn(badgeUrl);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(imgConn.getInputStream(), null, opts);

            if (bmp != null) {
                cache.put(cacheKey, bmp);
                AndroidUtilities.runOnUIThread(() -> iv.setImageBitmap(bmp));
                android.util.Log.d("TeamLogoLoader", "loaded [" + normalized + "]");
            }
        } catch (Exception e) {
            android.util.Log.w("TeamLogoLoader", "failed [" + normalized + "]: " + e.getMessage());
        }
    }

    private HttpURLConnection openConn(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TeleStream/1.0 Android");
        return conn;
    }

    /** Draws a colored circle with the team's initials. Always called on the main thread. */
    private void setInitialsBadge(ImageView iv, String teamName) {
        String[] words = teamName.trim().split("\\s+");
        String initials = words.length >= 2
            ? ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase(Locale.ROOT)
            : teamName.substring(0, Math.min(2, teamName.length())).toUpperCase(Locale.ROOT);

        int size = dp(44);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(0xFF1A3A5F);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);

        paint.setColor(0xFF4FC3F7);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(size * 0.36f);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(initials, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);

        iv.setImageBitmap(bmp);
    }

    private String normalize(String name) {
        String key = name.toLowerCase(Locale.ROOT).trim();
        String mapped = TEAM_NAME_MAP.get(key);
        return mapped != null ? mapped : name;
    }

    private int dp(int dp) {
        return (int) (dp * density + 0.5f);
    }

    // ─── Team name normalization map (Italian bot names → TheSportsDB canonical) ──

    private static final Map<String, String> TEAM_NAME_MAP = new HashMap<>();
    static {
        // Serie A
        TEAM_NAME_MAP.put("inter",              "Internazionale");
        TEAM_NAME_MAP.put("internazionale",     "Internazionale");
        TEAM_NAME_MAP.put("milan",              "AC Milan");
        TEAM_NAME_MAP.put("ac milan",           "AC Milan");
        TEAM_NAME_MAP.put("roma",               "AS Roma");
        TEAM_NAME_MAP.put("as roma",            "AS Roma");
        TEAM_NAME_MAP.put("juve",               "Juventus");
        TEAM_NAME_MAP.put("juventus",           "Juventus");
        TEAM_NAME_MAP.put("napoli",             "Napoli");
        TEAM_NAME_MAP.put("ssc napoli",         "Napoli");
        TEAM_NAME_MAP.put("lazio",              "Lazio");
        TEAM_NAME_MAP.put("ss lazio",           "Lazio");
        TEAM_NAME_MAP.put("atalanta",           "Atalanta");
        TEAM_NAME_MAP.put("fiorentina",         "Fiorentina");
        TEAM_NAME_MAP.put("bologna",            "Bologna");
        TEAM_NAME_MAP.put("torino",             "Torino");
        TEAM_NAME_MAP.put("udinese",            "Udinese");
        TEAM_NAME_MAP.put("genoa",              "Genoa");
        TEAM_NAME_MAP.put("sampdoria",          "Sampdoria");
        TEAM_NAME_MAP.put("sassuolo",           "Sassuolo");
        TEAM_NAME_MAP.put("lecce",              "Lecce");
        TEAM_NAME_MAP.put("cagliari",           "Cagliari");
        TEAM_NAME_MAP.put("verona",             "Hellas Verona");
        TEAM_NAME_MAP.put("hellas verona",      "Hellas Verona");
        TEAM_NAME_MAP.put("empoli",             "Empoli");
        TEAM_NAME_MAP.put("monza",              "Monza");
        TEAM_NAME_MAP.put("frosinone",          "Frosinone");
        TEAM_NAME_MAP.put("venezia",            "Venezia");
        TEAM_NAME_MAP.put("parma",              "Parma");
        TEAM_NAME_MAP.put("como",               "Como");
        // European clubs
        TEAM_NAME_MAP.put("barcellona",         "Barcelona");
        TEAM_NAME_MAP.put("barca",              "Barcelona");
        TEAM_NAME_MAP.put("real madrid",        "Real Madrid");
        TEAM_NAME_MAP.put("atletico",           "Atletico Madrid");
        TEAM_NAME_MAP.put("atletico madrid",    "Atletico Madrid");
        TEAM_NAME_MAP.put("psg",                "Paris Saint-Germain");
        TEAM_NAME_MAP.put("paris",              "Paris Saint-Germain");
        TEAM_NAME_MAP.put("man city",           "Manchester City");
        TEAM_NAME_MAP.put("manchester city",    "Manchester City");
        TEAM_NAME_MAP.put("man utd",            "Manchester United");
        TEAM_NAME_MAP.put("manchester united",  "Manchester United");
        TEAM_NAME_MAP.put("liverpool",          "Liverpool");
        TEAM_NAME_MAP.put("chelsea",            "Chelsea");
        TEAM_NAME_MAP.put("arsenal",            "Arsenal");
        TEAM_NAME_MAP.put("tottenham",          "Tottenham Hotspur");
        TEAM_NAME_MAP.put("spurs",              "Tottenham Hotspur");
        TEAM_NAME_MAP.put("bayern",             "Bayern Munich");
        TEAM_NAME_MAP.put("bayern monaco",      "Bayern Munich");
        TEAM_NAME_MAP.put("dortmund",           "Borussia Dortmund");
        TEAM_NAME_MAP.put("borussia dortmund",  "Borussia Dortmund");
        TEAM_NAME_MAP.put("porto",              "Porto");
        TEAM_NAME_MAP.put("benfica",            "Benfica");
        TEAM_NAME_MAP.put("ajax",               "Ajax");
        TEAM_NAME_MAP.put("psv",                "PSV Eindhoven");
        // ── Nazionali Mondiali 2026 ────────────────────────────────────────────
        // Europa
        TEAM_NAME_MAP.put("italia",             "Italy");
        TEAM_NAME_MAP.put("italy",              "Italy");
        TEAM_NAME_MAP.put("francia",            "France");
        TEAM_NAME_MAP.put("france",             "France");
        TEAM_NAME_MAP.put("germania",           "Germany");
        TEAM_NAME_MAP.put("germany",            "Germany");
        TEAM_NAME_MAP.put("inghilterra",        "England");
        TEAM_NAME_MAP.put("england",            "England");
        TEAM_NAME_MAP.put("spagna",             "Spain");
        TEAM_NAME_MAP.put("spain",              "Spain");
        TEAM_NAME_MAP.put("portogallo",         "Portugal");
        TEAM_NAME_MAP.put("portugal",           "Portugal");
        TEAM_NAME_MAP.put("olanda",             "Netherlands");
        TEAM_NAME_MAP.put("paesi bassi",        "Netherlands");
        TEAM_NAME_MAP.put("netherlands",        "Netherlands");
        TEAM_NAME_MAP.put("belgio",             "Belgium");
        TEAM_NAME_MAP.put("belgium",            "Belgium");
        TEAM_NAME_MAP.put("svizzera",           "Switzerland");
        TEAM_NAME_MAP.put("switzerland",        "Switzerland");
        TEAM_NAME_MAP.put("croazia",            "Croatia");
        TEAM_NAME_MAP.put("croatia",            "Croatia");
        TEAM_NAME_MAP.put("serbia",             "Serbia");
        TEAM_NAME_MAP.put("austria",            "Austria");
        TEAM_NAME_MAP.put("danimarca",          "Denmark");
        TEAM_NAME_MAP.put("denmark",            "Denmark");
        TEAM_NAME_MAP.put("polonia",            "Poland");
        TEAM_NAME_MAP.put("poland",             "Poland");
        TEAM_NAME_MAP.put("ucraina",            "Ukraine");
        TEAM_NAME_MAP.put("ukraine",            "Ukraine");
        TEAM_NAME_MAP.put("turchia",            "Turkey");
        TEAM_NAME_MAP.put("turkey",             "Turkey");
        TEAM_NAME_MAP.put("scozia",             "Scotland");
        TEAM_NAME_MAP.put("scotland",           "Scotland");
        TEAM_NAME_MAP.put("galles",             "Wales");
        TEAM_NAME_MAP.put("wales",              "Wales");
        TEAM_NAME_MAP.put("ungheria",           "Hungary");
        TEAM_NAME_MAP.put("hungary",            "Hungary");
        TEAM_NAME_MAP.put("rep. ceca",          "Czech Republic");
        TEAM_NAME_MAP.put("repubblica ceca",    "Czech Republic");
        TEAM_NAME_MAP.put("czech republic",     "Czech Republic");
        TEAM_NAME_MAP.put("slovacchia",         "Slovakia");
        TEAM_NAME_MAP.put("slovakia",           "Slovakia");
        TEAM_NAME_MAP.put("slovenia",           "Slovenia");
        TEAM_NAME_MAP.put("albania",            "Albania");
        TEAM_NAME_MAP.put("georgia",            "Georgia");
        TEAM_NAME_MAP.put("romania",            "Romania");
        // Sud America
        TEAM_NAME_MAP.put("brasile",            "Brazil");
        TEAM_NAME_MAP.put("brazil",             "Brazil");
        TEAM_NAME_MAP.put("argentina",          "Argentina");
        TEAM_NAME_MAP.put("uruguay",            "Uruguay");
        TEAM_NAME_MAP.put("colombia",           "Colombia");
        TEAM_NAME_MAP.put("ecuador",            "Ecuador");
        TEAM_NAME_MAP.put("venezuela",          "Venezuela");
        TEAM_NAME_MAP.put("paraguay",           "Paraguay");
        TEAM_NAME_MAP.put("chile",              "Chile");
        TEAM_NAME_MAP.put("perù",               "Peru");
        TEAM_NAME_MAP.put("peru",               "Peru");
        TEAM_NAME_MAP.put("bolivia",            "Bolivia");
        // Nord/Centro America e Caraibi
        TEAM_NAME_MAP.put("usa",                "USA");
        TEAM_NAME_MAP.put("stati uniti",        "USA");
        TEAM_NAME_MAP.put("canada",             "Canada");
        TEAM_NAME_MAP.put("messico",            "Mexico");
        TEAM_NAME_MAP.put("mexico",             "Mexico");
        TEAM_NAME_MAP.put("panama",             "Panama");
        TEAM_NAME_MAP.put("costa rica",         "Costa Rica");
        TEAM_NAME_MAP.put("honduras",           "Honduras");
        TEAM_NAME_MAP.put("giamaica",           "Jamaica");
        TEAM_NAME_MAP.put("jamaica",            "Jamaica");
        // Africa
        TEAM_NAME_MAP.put("marocco",            "Morocco");
        TEAM_NAME_MAP.put("morocco",            "Morocco");
        TEAM_NAME_MAP.put("senegal",            "Senegal");
        TEAM_NAME_MAP.put("nigeria",            "Nigeria");
        TEAM_NAME_MAP.put("camerun",            "Cameroon");
        TEAM_NAME_MAP.put("cameroon",           "Cameroon");
        TEAM_NAME_MAP.put("ghana",              "Ghana");
        TEAM_NAME_MAP.put("costa d'avorio",     "Ivory Coast");
        TEAM_NAME_MAP.put("costa d avorio",     "Ivory Coast");
        TEAM_NAME_MAP.put("ivory coast",        "Ivory Coast");
        TEAM_NAME_MAP.put("egitto",             "Egypt");
        TEAM_NAME_MAP.put("egypt",              "Egypt");
        TEAM_NAME_MAP.put("sud africa",         "South Africa");
        TEAM_NAME_MAP.put("south africa",       "South Africa");
        TEAM_NAME_MAP.put("mali",               "Mali");
        TEAM_NAME_MAP.put("guinea",             "Guinea");
        TEAM_NAME_MAP.put("tanzania",           "Tanzania");
        TEAM_NAME_MAP.put("angola",             "Angola");
        TEAM_NAME_MAP.put("mozambico",          "Mozambique");
        TEAM_NAME_MAP.put("mozambique",         "Mozambique");
        TEAM_NAME_MAP.put("zimbabwe",           "Zimbabwe");
        TEAM_NAME_MAP.put("benin",              "Benin");
        TEAM_NAME_MAP.put("zambia",             "Zambia");
        TEAM_NAME_MAP.put("gabon",              "Gabon");
        TEAM_NAME_MAP.put("tunisia",            "Tunisia");
        TEAM_NAME_MAP.put("algeria",            "Algeria");
        // Asia e Oceania
        TEAM_NAME_MAP.put("giappone",           "Japan");
        TEAM_NAME_MAP.put("japan",              "Japan");
        TEAM_NAME_MAP.put("corea del sud",      "South Korea");
        TEAM_NAME_MAP.put("corea",              "South Korea");
        TEAM_NAME_MAP.put("south korea",        "South Korea");
        TEAM_NAME_MAP.put("australia",          "Australia");
        TEAM_NAME_MAP.put("arabia saudita",     "Saudi Arabia");
        TEAM_NAME_MAP.put("saudi arabia",       "Saudi Arabia");
        TEAM_NAME_MAP.put("iran",               "Iran");
        TEAM_NAME_MAP.put("uzbekistan",         "Uzbekistan");
        TEAM_NAME_MAP.put("indonesia",          "Indonesia");
        TEAM_NAME_MAP.put("giordania",          "Jordan");
        TEAM_NAME_MAP.put("jordan",             "Jordan");
        TEAM_NAME_MAP.put("bahrain",            "Bahrain");
        TEAM_NAME_MAP.put("iraq",               "Iraq");
        TEAM_NAME_MAP.put("qatar",              "Qatar");
        TEAM_NAME_MAP.put("nuova zelanda",      "New Zealand");
        TEAM_NAME_MAP.put("new zealand",        "New Zealand");
    }
}
