package org.telegram.tv.ui;

import org.telegram.messenger.tv.R;

/** Maps sport category names (from the bot) to drawable icons and accent colors. */
public final class SportCategoryResolver {

    private SportCategoryResolver() {}

    // ─── Keyword tables ───────────────────────────────────────────────────────
    // Basketball is checked before soccer to prevent "EUROLEAGUE" matching "EURO" in the soccer list.

    private static final String[] KW_SOCCER = {
        "CALCIO", "SERIE A", "SERIE B", "SERIE C", "PREMIER LEAGUE", "PREMIER",
        "BUNDESLIGA", "LIGUE 1", "LIGUE1", "EREDIVISIE", "JUPILER",
        "CHAMPIONS LEAGUE", "CHAMPIONS", "UEFA", "EUROPA LEAGUE", "EUROPA",
        "CONFERENCE LEAGUE", "CONFERENCE",
        "MONDIALI", "WORLD CUP", "COPPA DEL MONDO",
        "NATIONS LEAGUE", "EURO 2", "EUROPEI",
        "COPPA ITALIA", "FA CUP", "COPA DEL REY", "DFB POKAL",
        "SUPERCOPPA ITALIANA", "COMMUNITY SHIELD",
        "FOOTBALL", "SOCCER", "LIGA "
    };
    private static final String[] KW_BASKETBALL = {
        "BASKET", "BASKETBALL", "PALLACANESTRO",
        "NBA", "FIBA", "EUROLEAGUE", "EUROCUP", "ACB", "WNBA",
        "NCB", "LBA", "LEGABASKET"
    };
    private static final String[] KW_TENNIS = {
        "TENNIS", "WIMBLEDON", "ROLAND GARROS", "ROLAND-GARROS",
        "US OPEN", "AUSTRALIAN OPEN",
        "ATP", "WTA", "GRAND SLAM", "SLAM",
        "DAVIS CUP", "DAVIS", "BILLIE JEAN", "LAVER CUP", "NITTO",
        "INTERNAZIONALI DI ROMA", "INTERNAZIONALI BNL"
    };
    private static final String[] KW_MOTORSPORT = {
        "FORMULA 1", "FORMULA1", "FORMULAONE", "F1",
        "MOTO GP", "MOTOGP", "MOTO2", "MOTO3",
        "SUPERBIKE", "SUPERCAR", "RALLY", "WRC",
        "INDYCAR", "NASCAR", "DAKAR", "ENDURANCE",
        "LE MANS", "24 ORE", "DTM", "FORMULA E"
    };
    private static final String[] KW_VOLLEYBALL = {
        "VOLLEY", "VOLLEYBALL", "PALLAVOLO",
        "FIVB", "CEV", "BEACH VOLLEY", "BEACHVOLLEY"
    };
    private static final String[] KW_RUGBY = {
        "RUGBY", "SIX NATIONS", "WORLD RUGBY",
        "PREMIERSHIP RUGBY", "TOP 14", "PRO14", "URC"
    };
    private static final String[] KW_ATHLETICS = {
        "ATLETICA", "LEGGERA", "MARATONA", "MARATHON",
        "SPRINT", "DECATHLON", "EPTATHLON", "TRIATHLON",
        "MARCIA", "MEZZOFONDO", "FONDO", "SALTO IN ALTO",
        "LANCIO", "GINNASTICA", "IAAF", "WORLD ATHLETICS",
        "DIAMOND LEAGUE", "OLIMPIA", "OLIMPIADI", "OLYMPIC"
    };
    private static final String[] KW_CYCLING = {
        "CICLISMO", "CYCLING",
        "TOUR DE FRANCE", "GIRO D'ITALIA", "GIRO D ITALIA",
        "VUELTA", "VUELTA A ESPANA",
        "STRADE BIANCHE", "ROUBAIX", "LIEGE", "MILAN-SANREMO", "MILAN SANREMO",
        "FIANDRE", "LOMBARDIA", "AMSTEL", "TIRRENO", "UCI"
    };
    private static final String[] KW_SWIMMING = {
        "NUOTO", "SWIMMING", "NATAZIONE",
        "TUFFI", "SINCRONIZZATO", "ACQUE LIBERE",
        "FINA", "WORLD AQUATICS"
    };
    private static final String[] KW_COMBAT = {
        "MMA", "UFC", "ONE FC", "ONE CHAMPIONSHIP",
        "PUGILATO", "BOXE", "BOXING",
        "LOTTA", "WRESTLING",
        "KARATE", "JUDO", "TAEKWONDO", "KICKBOXING", "MUAY THAI",
        "AIBA", "IBF", "WBC", "WBA", "WBO"
    };
    private static final String[] KW_HANDBALL = {
        "PALLAMANO", "HANDBALL", "EHF",
        "PALLANUOTO", "WATERPOLO", "WATER POLO", "POLO ACQUATICO",
        "LEN ", "SUPERLIGA HANDBALL"
    };

    private static boolean matchesAny(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    public static int iconRes(String category) {
        String up = category.toUpperCase();
        if (matchesAny(up, KW_BASKETBALL))  return R.drawable.ic_sport_basketball;
        if (matchesAny(up, KW_TENNIS))      return R.drawable.ic_sport_tennis;
        if (matchesAny(up, KW_MOTORSPORT))  return R.drawable.ic_sport_motorsport;
        if (matchesAny(up, KW_VOLLEYBALL))  return R.drawable.ic_sport_volleyball;
        if (matchesAny(up, KW_RUGBY))       return R.drawable.ic_sport_rugby;
        if (matchesAny(up, KW_CYCLING))     return R.drawable.ic_sport_cycling;
        if (matchesAny(up, KW_ATHLETICS))   return R.drawable.ic_sport_athletics;
        if (matchesAny(up, KW_SWIMMING))    return R.drawable.ic_sport_swimming;
        if (matchesAny(up, KW_COMBAT))      return R.drawable.ic_sport_combat;
        if (matchesAny(up, KW_HANDBALL))    return R.drawable.ic_sport_handball;
        if (matchesAny(up, KW_SOCCER))      return R.drawable.ic_sport_soccer;
        return R.drawable.ic_sport_trophy;
    }

    public static int color(String category) {
        String up = category.toUpperCase();
        if (matchesAny(up, KW_BASKETBALL))  return 0xFFC05000;
        if (matchesAny(up, KW_TENNIS))      return 0xFF2874A6;
        if (matchesAny(up, KW_MOTORSPORT))  return 0xFF8E1A0E;
        if (matchesAny(up, KW_VOLLEYBALL))  return 0xFF6A1E8A;
        if (matchesAny(up, KW_RUGBY))       return 0xFF4A235A;
        if (matchesAny(up, KW_CYCLING))     return 0xFF1E6B1E;
        if (matchesAny(up, KW_ATHLETICS))   return 0xFFB7770A;
        if (matchesAny(up, KW_SWIMMING))    return 0xFF0D7C7C;
        if (matchesAny(up, KW_COMBAT))      return 0xFF8B1A10;
        if (matchesAny(up, KW_HANDBALL))    return 0xFF1A5C6B;
        if (matchesAny(up, KW_SOCCER))      return 0xFF27AE60;
        return 0xFF4FC3F7;
    }
}
