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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async team logo loader backed by TheSportsDB v1 free API.
 *
 * Call {@link #load} from the main thread; callbacks are delivered on the main thread.
 * Call {@link #shutdown} in onDestroy to release the thread pool.
 */
public final class TeamLogoLoader {

    private static final String TAG = "TeamLogoLoader";

    private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();
    // tracks keys already requested (in-flight or completed/failed) to avoid duplicate fetches
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
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
        String key = teamName.toLowerCase(Locale.ROOT).trim();

        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        setInitialsBadge(iv, teamName);  // immediate placeholder

        if (!requested.add(key)) return; // already in-flight or previously attempted

        String normalized = normalize(teamName);
        executor.execute(() -> fetchFromSportsDb(normalized, key, teamName, iv));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ─── Network fetch ─────────────────────────────────────────────────────────

    private void fetchFromSportsDb(String normalized, String cacheKey, String displayName, ImageView iv) {
        try {
            String encoded = URLEncoder.encode(normalized, "UTF-8");
            String apiUrl = "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=" + encoded;
            android.util.Log.d(TAG, "fetching [" + normalized + "] → " + apiUrl);

            HttpURLConnection conn = openConn(apiUrl);
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w(TAG, "API HTTP " + code + " for [" + normalized + "]");
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            android.util.Log.d(TAG, "API response for [" + normalized + "]: " + sb.toString().substring(0, Math.min(200, sb.length())));

            JSONObject json = new JSONObject(sb.toString());
            JSONArray teams = json.optJSONArray("teams");
            if (teams == null || teams.length() == 0) {
                android.util.Log.w(TAG, "no match in API for [" + normalized + "]");
                return;
            }

            JSONObject team = teams.getJSONObject(0);
            String badgeUrl = team.optString("strTeamBadge");

            // Free API key "3" often omits strTeamBadge for national teams.
            // Fall back to the api-football.com CDN using idAPIfootball.
            if (badgeUrl == null || badgeUrl.isEmpty()) {
                String apiFootballId = team.optString("idAPIfootball");
                if (apiFootballId != null && !apiFootballId.isEmpty() && !apiFootballId.equals("0")) {
                    badgeUrl = "https://media.api-sports.io/football/teams/" + apiFootballId + ".png";
                    android.util.Log.d(TAG, "no strTeamBadge — trying api-football CDN: " + badgeUrl);
                } else {
                    android.util.Log.w(TAG, "no badge URL for [" + normalized + "]");
                    return;
                }
            }

            android.util.Log.d(TAG, "badge URL: " + badgeUrl);
            HttpURLConnection imgConn = openConn(badgeUrl);
            int imgCode = imgConn.getResponseCode();
            if (imgCode != 200) {
                android.util.Log.w(TAG, "badge image HTTP " + imgCode + " for [" + normalized + "]");
                return;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(imgConn.getInputStream(), null, opts);

            if (bmp != null) {
                cache.put(cacheKey, bmp);
                AndroidUtilities.runOnUIThread(() -> iv.setImageBitmap(bmp));
                android.util.Log.d(TAG, "loaded [" + normalized + "]");
            } else {
                android.util.Log.w(TAG, "bitmap decode returned null for [" + normalized + "]");
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "exception for [" + normalized + "]: "
                + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    private HttpURLConnection openConn(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TeleStream/1.0 Android");
        return conn;
    }

    // ─── Initials badge fallback ───────────────────────────────────────────────

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

    private int dp(int dp) {
        return (int) (dp * density + 0.5f);
    }

    // ─── Name normalization (Italian bot name → TheSportsDB canonical) ─────────

    private String normalize(String name) {
        String key = name.toLowerCase(Locale.ROOT).trim();
        String mapped = NAME_MAP.get(key);
        return mapped != null ? mapped : name;
    }

    // ─── Team name map ─────────────────────────────────────────────────────────

    private static final Map<String, String> NAME_MAP = new HashMap<>();
    static {
        // Serie A
        NAME_MAP.put("inter",              "Internazionale");
        NAME_MAP.put("internazionale",     "Internazionale");
        NAME_MAP.put("milan",              "AC Milan");
        NAME_MAP.put("ac milan",           "AC Milan");
        NAME_MAP.put("roma",               "AS Roma");
        NAME_MAP.put("as roma",            "AS Roma");
        NAME_MAP.put("juve",               "Juventus");
        NAME_MAP.put("juventus",           "Juventus");
        NAME_MAP.put("napoli",             "Napoli");
        NAME_MAP.put("ssc napoli",         "Napoli");
        NAME_MAP.put("lazio",              "Lazio");
        NAME_MAP.put("ss lazio",           "Lazio");
        NAME_MAP.put("atalanta",           "Atalanta");
        NAME_MAP.put("fiorentina",         "Fiorentina");
        NAME_MAP.put("bologna",            "Bologna");
        NAME_MAP.put("torino",             "Torino");
        NAME_MAP.put("udinese",            "Udinese");
        NAME_MAP.put("genoa",              "Genoa");
        NAME_MAP.put("sampdoria",          "Sampdoria");
        NAME_MAP.put("sassuolo",           "Sassuolo");
        NAME_MAP.put("lecce",              "Lecce");
        NAME_MAP.put("cagliari",           "Cagliari");
        NAME_MAP.put("verona",             "Hellas Verona");
        NAME_MAP.put("hellas verona",      "Hellas Verona");
        NAME_MAP.put("empoli",             "Empoli");
        NAME_MAP.put("monza",              "Monza");
        NAME_MAP.put("frosinone",          "Frosinone");
        NAME_MAP.put("venezia",            "Venezia");
        NAME_MAP.put("parma",              "Parma");
        NAME_MAP.put("como",               "Como");
        // European clubs
        NAME_MAP.put("barcellona",         "Barcelona");
        NAME_MAP.put("barca",              "Barcelona");
        NAME_MAP.put("real madrid",        "Real Madrid");
        NAME_MAP.put("atletico",           "Atletico Madrid");
        NAME_MAP.put("atletico madrid",    "Atletico Madrid");
        NAME_MAP.put("psg",                "Paris Saint-Germain");
        NAME_MAP.put("paris",              "Paris Saint-Germain");
        NAME_MAP.put("man city",           "Manchester City");
        NAME_MAP.put("manchester city",    "Manchester City");
        NAME_MAP.put("man utd",            "Manchester United");
        NAME_MAP.put("manchester united",  "Manchester United");
        NAME_MAP.put("liverpool",          "Liverpool");
        NAME_MAP.put("chelsea",            "Chelsea");
        NAME_MAP.put("arsenal",            "Arsenal");
        NAME_MAP.put("tottenham",          "Tottenham Hotspur");
        NAME_MAP.put("spurs",              "Tottenham Hotspur");
        NAME_MAP.put("bayern",             "Bayern Munich");
        NAME_MAP.put("bayern monaco",      "Bayern Munich");
        NAME_MAP.put("dortmund",           "Borussia Dortmund");
        NAME_MAP.put("borussia dortmund",  "Borussia Dortmund");
        NAME_MAP.put("porto",              "Porto");
        NAME_MAP.put("benfica",            "Benfica");
        NAME_MAP.put("ajax",               "Ajax");
        NAME_MAP.put("psv",                "PSV Eindhoven");
        // National teams — Europe
        NAME_MAP.put("italia",             "Italy");
        NAME_MAP.put("italy",              "Italy");
        NAME_MAP.put("francia",            "France");
        NAME_MAP.put("france",             "France");
        NAME_MAP.put("germania",           "Germany");
        NAME_MAP.put("germany",            "Germany");
        NAME_MAP.put("inghilterra",        "England");
        NAME_MAP.put("england",            "England");
        NAME_MAP.put("spagna",             "Spain");
        NAME_MAP.put("spain",              "Spain");
        NAME_MAP.put("portogallo",         "Portugal");
        NAME_MAP.put("portugal",           "Portugal");
        NAME_MAP.put("olanda",             "Netherlands");
        NAME_MAP.put("paesi bassi",        "Netherlands");
        NAME_MAP.put("netherlands",        "Netherlands");
        NAME_MAP.put("belgio",             "Belgium");
        NAME_MAP.put("belgium",            "Belgium");
        NAME_MAP.put("svizzera",           "Switzerland");
        NAME_MAP.put("switzerland",        "Switzerland");
        NAME_MAP.put("croazia",            "Croatia");
        NAME_MAP.put("croatia",            "Croatia");
        NAME_MAP.put("serbia",             "Serbia");
        NAME_MAP.put("austria",            "Austria");
        NAME_MAP.put("danimarca",          "Denmark");
        NAME_MAP.put("denmark",            "Denmark");
        NAME_MAP.put("polonia",            "Poland");
        NAME_MAP.put("poland",             "Poland");
        NAME_MAP.put("ucraina",            "Ukraine");
        NAME_MAP.put("ukraine",            "Ukraine");
        NAME_MAP.put("turchia",            "Turkey");
        NAME_MAP.put("turkey",             "Turkey");
        NAME_MAP.put("scozia",             "Scotland");
        NAME_MAP.put("scotland",           "Scotland");
        NAME_MAP.put("galles",             "Wales");
        NAME_MAP.put("wales",              "Wales");
        NAME_MAP.put("ungheria",           "Hungary");
        NAME_MAP.put("hungary",            "Hungary");
        NAME_MAP.put("rep. ceca",          "Czech Republic");
        NAME_MAP.put("repubblica ceca",    "Czech Republic");
        NAME_MAP.put("czech republic",     "Czech Republic");
        NAME_MAP.put("slovacchia",         "Slovakia");
        NAME_MAP.put("slovakia",           "Slovakia");
        NAME_MAP.put("slovenia",           "Slovenia");
        NAME_MAP.put("albania",            "Albania");
        NAME_MAP.put("georgia",            "Georgia");
        NAME_MAP.put("romania",            "Romania");
        // National teams — South America
        NAME_MAP.put("brasile",            "Brazil");
        NAME_MAP.put("brazil",             "Brazil");
        NAME_MAP.put("argentina",          "Argentina");
        NAME_MAP.put("uruguay",            "Uruguay");
        NAME_MAP.put("colombia",           "Colombia");
        NAME_MAP.put("ecuador",            "Ecuador");
        NAME_MAP.put("venezuela",          "Venezuela");
        NAME_MAP.put("paraguay",           "Paraguay");
        NAME_MAP.put("chile",              "Chile");
        NAME_MAP.put("perù",               "Peru");
        NAME_MAP.put("peru",               "Peru");
        NAME_MAP.put("bolivia",            "Bolivia");
        // National teams — North/Central America & Caribbean
        NAME_MAP.put("usa",                "USA");
        NAME_MAP.put("stati uniti",        "USA");
        NAME_MAP.put("canada",             "Canada");
        NAME_MAP.put("messico",            "Mexico");
        NAME_MAP.put("mexico",             "Mexico");
        NAME_MAP.put("panama",             "Panama");
        NAME_MAP.put("costa rica",         "Costa Rica");
        NAME_MAP.put("honduras",           "Honduras");
        NAME_MAP.put("giamaica",           "Jamaica");
        NAME_MAP.put("jamaica",            "Jamaica");
        // National teams — Africa
        NAME_MAP.put("marocco",            "Morocco");
        NAME_MAP.put("morocco",            "Morocco");
        NAME_MAP.put("senegal",            "Senegal");
        NAME_MAP.put("nigeria",            "Nigeria");
        NAME_MAP.put("camerun",            "Cameroon");
        NAME_MAP.put("cameroon",           "Cameroon");
        NAME_MAP.put("ghana",              "Ghana");
        NAME_MAP.put("costa d'avorio",     "Ivory Coast");
        NAME_MAP.put("costa d avorio",     "Ivory Coast");
        NAME_MAP.put("ivory coast",        "Ivory Coast");
        NAME_MAP.put("egitto",             "Egypt");
        NAME_MAP.put("egypt",              "Egypt");
        NAME_MAP.put("sud africa",         "South Africa");
        NAME_MAP.put("south africa",       "South Africa");
        NAME_MAP.put("mali",               "Mali");
        NAME_MAP.put("guinea",             "Guinea");
        NAME_MAP.put("tanzania",           "Tanzania");
        NAME_MAP.put("angola",             "Angola");
        NAME_MAP.put("mozambico",          "Mozambique");
        NAME_MAP.put("mozambique",         "Mozambique");
        NAME_MAP.put("zimbabwe",           "Zimbabwe");
        NAME_MAP.put("benin",              "Benin");
        NAME_MAP.put("zambia",             "Zambia");
        NAME_MAP.put("gabon",              "Gabon");
        NAME_MAP.put("tunisia",            "Tunisia");
        NAME_MAP.put("algeria",            "Algeria");
        // National teams — Asia & Oceania
        NAME_MAP.put("giappone",           "Japan");
        NAME_MAP.put("japan",              "Japan");
        NAME_MAP.put("corea del sud",      "South Korea");
        NAME_MAP.put("corea",              "South Korea");
        NAME_MAP.put("south korea",        "South Korea");
        NAME_MAP.put("australia",          "Australia");
        NAME_MAP.put("arabia saudita",     "Saudi Arabia");
        NAME_MAP.put("saudi arabia",       "Saudi Arabia");
        NAME_MAP.put("iran",               "Iran");
        NAME_MAP.put("uzbekistan",         "Uzbekistan");
        NAME_MAP.put("indonesia",          "Indonesia");
        NAME_MAP.put("giordania",          "Jordan");
        NAME_MAP.put("jordan",             "Jordan");
        NAME_MAP.put("bahrain",            "Bahrain");
        NAME_MAP.put("iraq",               "Iraq");
        NAME_MAP.put("qatar",              "Qatar");
        NAME_MAP.put("nuova zelanda",      "New Zealand");
        NAME_MAP.put("new zealand",        "New Zealand");
    }
}
