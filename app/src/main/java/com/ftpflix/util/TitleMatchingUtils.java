package com.ftpflix.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalization and relevance matching helpers used by the h5ai scrape engine.
 */
public final class TitleMatchingUtils {

    private static final Pattern RESOLUTION_PATTERN =
            Pattern.compile("(?i)\\b(2160p|4k|1080p|720p|480p|hdrip|bluray|web-dl)\\b");

    private static final Pattern NOISE_PATTERN =
            Pattern.compile("(?i)\\b(2160p|1440p|1080p|720p|480p|x264|x265|h\\.?264|h\\.?265|hevc|av1|10bit|bluray|bdrip|brrip|web[- ]?dl|webrip|hdrip|dvdrip|hdtv|remux|aac|ac3|dts|ddp?5\\.1|atmos|proper|repack|extended|yify|yts|rarbg|ettv|19\\d{2}|20\\d{2}|mp4|mkv|avi|webm|mov|m4v)\\b");

    private static final Pattern LEADING_ARTICLES_PATTERN =
            Pattern.compile("(?i)^\\b(the|a|an)\\b\\s*");

    private static final Pattern SANITIZATION_PATTERN =
            Pattern.compile("[._\\-\\+\\[\\]\\(\\):',!]");

    private static final Set<String> STOP_WORDS = new HashSet<>();
    static {
        for (String w : new String[]{"the", "a", "an", "and", "or", "of", "in", "on", "at", "by", "with", "for", "is", "it", "to", "from"}) {
            STOP_WORDS.add(w);
        }
    }

    private static final Pattern FTP_JUNK_PHRASES = Pattern.compile(
            "(?i)\\b("
                    + "english\\s*(&|and)?\\s*foreign(\\s+(dubbed\\s+)?(movies|tv\\s+series|series|shows?))?"
                    + "|foreign\\s+language\\s+movies"
                    + "|(hindi|tamil|telugu|bengali|bangla|english|chinese|korean|japanese|spanish|turkish|urdu|thai)\\s+(dubbed|movies|tv\\s+series|series|shows?)"
                    + "|web\\s+(series|shows?)"
                    + "|(tv|mini)\\s+series"
                    + "|complete\\s+series"
                    + "|dual\\s+audio|multi\\s+audio"
                    + "|(hin|eng|kor|spa|chi|tam|tel|jap)\\+(hin|eng|kor|spa|chi|tam|tel|jap)"
                    + ")\\b");

    private static final Set<String> FTP_JUNK_TOKENS = new HashSet<>(Arrays.asList(
            "hd", "4k", "2k", "bluray", "bdrip", "brrip", "dvdrip", "dvdscr", "dvd",
            "webrip", "webdl", "web", "hdtv", "hdrip", "hdts", "hdcam", "camrip", "pdvd",
            "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "av1", "remux",
            "aac", "ac3", "ddp", "dd", "eac3", "dts", "atmos", "truehd", "6ch", "2ch",
            "10bit", "8bit", "esub", "esubs", "msub", "msubs", "subbed", "subs",
            "untouched", "premux", "remastered", "upscaled", "uncut",
            "imax", "hdr", "hdr10", "dolby", "vision",
            "org", "original", "official", "dual", "audio",
            "dubbed", "others", "movie", "movies", "film", "films", "episode",
            "episodes", "complete", "anime", "series", "show", "shows", "tv",
            "animation", "cartoon", "cartoons", "documentary", "documentaries",
            "wrestling", "wwe", "award", "awards",
            "yify", "yts", "rarbg", "ettv", "ingot", "nodlabs",
            "bengali", "bangla", "tamil", "telugu", "malayalam", "punjabi", "marathi",
            "bhojpuri", "mandarin", "cantonese", "turkish", "urdu", "thai", "korean",
            "japanese", "chinese", "spanish", "french", "german", "italian", "foreign",
            "english", "hindi", "season",
            "mp4", "mkv", "avi", "webm", "m4v", "flv", "wmv", "ts"));

    // Only resolutions and years are junk; bare numbers ("Jailer 2") and roman numerals are meaningful title parts.
    private static final Pattern JUNK_TOKEN_PATTERN =
            Pattern.compile("^(\\d{3,4}p|\\d{4}|dd\\d{1,2}|eac\\d)$");

    // "Rocky II" == "Rocky 2"
    private static final Map<String, String> ROMAN = new HashMap<>();
    static {
        String[] rn = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii"};
        for (int i = 0; i < rn.length; i++) {
            ROMAN.put(rn[i], String.valueOf(i + 1));
        }
    }

    private TitleMatchingUtils() {
    }

    public static String extractResolution(String fileName) {
        Matcher matcher = RESOLUTION_PATTERN.matcher(fileName);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return "720P";
    }

    public static String cleanTitle(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input.replaceAll("(?i)\\s*&\\s*", " and ");
        s = SANITIZATION_PATTERN.matcher(s).replaceAll(" ");
        s = NOISE_PATTERN.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        s = LEADING_ARTICLES_PATTERN.matcher(s).replaceFirst("").trim();
        return s;
    }

    private static String norm(String token) {
        String r = ROMAN.get(token);
        return r != null ? r : token;
    }

    private static boolean isJunkToken(String t) {
        return FTP_JUNK_TOKENS.contains(t)
                || JUNK_TOKEN_PATTERN.matcher(t).matches()
                || JUNK_TOKEN_PATTERN.matcher(norm(t)).matches();
    }

    public static Set<String> extractMeaningfulTokens(String input) {
        Set<String> tokens = new HashSet<>();
        String clean = cleanTitle(input);
        for (String w : clean.split("\\s+")) {
            String trimmed = w.trim();
            if (!trimmed.isEmpty() && !STOP_WORDS.contains(trimmed)) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    /** Punctuation-only cleanup — keeps years and short titles ("1917", "It"). */
    private static Set<String> rawTokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isEmpty()) return out;
        String s = text.replaceAll("(?i)\\s*&\\s*", " and ");
        s = SANITIZATION_PATTERN.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        for (String w : s.split(" ")) {
            if (!w.isEmpty()) out.add(norm(w));
        }
        return out;
    }

    /** rawTokens plus junk filtering, used as a candidate-side last resort. */
    private static Set<String> rawFilteredTokens(String text, Set<String> queryNorm) {
        Set<String> out = new HashSet<>();
        for (String w : rawTokens(text)) {
            if (isJunkToken(w) && !queryNorm.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    /** Candidate tokens, junk-stripped (query words preserved), roman-normalized. */
    private static Set<String> candidateTokens(String candidateText, String contextText, Set<String> queryNorm) {
        String combined = ((candidateText == null ? "" : candidateText) + " "
                + (contextText == null ? "" : contextText)).trim();
        if (combined.isEmpty()) {
            return new HashSet<>();
        }
        String s = cleanTitle(combined);
        s = FTP_JUNK_PHRASES.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        Set<String> out = new HashSet<>();
        for (String w : s.split(" ")) {
            String t = w.trim();
            if (t.isEmpty() || STOP_WORDS.contains(t)) {
                continue;
            }
            String nw = norm(t);
            if (isJunkToken(t) && !queryNorm.contains(nw) && !queryNorm.contains(t)) {
                continue;
            }
            out.add(nw);
        }
        return out;
    }

    /** Fallback when junk filtering empties the candidate: keeps stop words. */
    private static Set<String> fallbackTokens(String text, Set<String> queryNorm) {
        String s = cleanTitle(text);
        s = FTP_JUNK_PHRASES.matcher(s).replaceAll(" ");
        Set<String> out = new HashSet<>();
        for (String w : s.split("\\s+")) {
            String t = w.trim();
            if (t.isEmpty()) {
                continue;
            }
            String nw = norm(t);
            if (isJunkToken(t) && !queryNorm.contains(nw) && !queryNorm.contains(t)) {
                continue;
            }
            out.add(nw);
        }
        return out;
    }

    /** Prefix pairing needs substantial overlap: "Up"≠"Upgrade", "Slap"≠"Slapface". */
    private static boolean prefixCompatible(String a, String b) {
        if (a.length() < 4 || b.length() < 4) return false;
        if (!a.startsWith(b) && !b.startsWith(a)) return false;
        int max = Math.max(a.length(), b.length());
        int min = Math.min(a.length(), b.length());
        return (double) min / max >= 0.7;
    }

    public static int matchQuality(String queryTitle, String candidateText, String contextText) {
        Set<String> q = extractMeaningfulTokens(queryTitle);
        q.remove("s");
        if (q.isEmpty()) {
            q = rawTokens(queryTitle);
        }
        Set<String> qN = new HashSet<>();
        for (String t : q) qN.add(norm(t));

        // Match the file/dir name alone; the parent folder is a fallback for names that carry no title tokens of their own ("video.mkv", years).
        Set<String> d = candidateTokens(candidateText, "", qN);
        d.remove("s");
        if (d.isEmpty() && contextText != null && !contextText.isEmpty()) {
            d = candidateTokens(candidateText, contextText, qN);
            d.remove("s");
        }
        if (d.isEmpty()) {
            d = fallbackTokens(candidateText + " " + contextText, qN);
        }
        if (d.isEmpty()) {
            d = rawFilteredTokens(candidateText + " " + contextText, qN);
        }
        if (qN.isEmpty() || d.isEmpty()) {
            return 0;
        }
        if (d.equals(qN)) {
            return 2;
        }

        Set<String> qLeft = new HashSet<>(qN);
        Set<String> dLeft = new HashSet<>(d);
        qLeft.removeAll(d);
        dLeft.removeAll(qN);
        Iterator<String> qIt = qLeft.iterator();
        while (qIt.hasNext()) {
            String m = qIt.next();
            String pair = null;
            for (String e : dLeft) {
                if (prefixCompatible(m, e)) {
                    pair = e;
                    break;
                }
            }
            if (pair == null) {
                return 0;
            }
            qIt.remove();
            dLeft.remove(pair);
        }
        // Consume possessive/glued leftovers ("Mandys" vs "Mandy")
        Iterator<String> dIt = dLeft.iterator();
        while (dIt.hasNext()) {
            String e = dIt.next();
            for (String m : qN) {
                if (prefixCompatible(e, m)) {
                    dIt.remove();
                    break;
                }
            }
        }
        return dLeft.isEmpty() ? 1 : 0;
    }

    public static double calculateSimilarityScore(String queryTitle, String fileName) {
        String cleanQuery = cleanTitle(queryTitle);
        String cleanFileName = cleanTitle(fileName);

        if (cleanQuery.isEmpty() || cleanFileName.isEmpty()) {
            return 0.0;
        }

        if (cleanFileName.contains(cleanQuery) || cleanQuery.contains(cleanFileName)) {
            return 0.95;
        }

        Set<String> queryTokens = extractMeaningfulTokens(queryTitle);
        Set<String> fileTokens = extractMeaningfulTokens(fileName);

        if (!queryTokens.isEmpty() && !fileTokens.isEmpty()) {
            int overlap = 0;
            for (String qt : queryTokens) {
                if (fileTokens.contains(qt)) {
                    overlap++;
                }
            }
            double overlapRatio = (double) overlap / queryTokens.size();
            if (overlapRatio >= 0.75) {
                return 0.90;
            }
            if (overlapRatio >= 0.50) {
                return 0.70;
            }
            if (queryTokens.size() >= 2 && overlapRatio < 0.40) {
                return 0.20;
            }
        }

        int distance = computeLevenshteinDistance(cleanQuery, cleanFileName);
        int maxLength = Math.max(cleanQuery.length(), cleanFileName.length());
        if (maxLength == 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - ((double) distance / maxLength));
    }

    private static int computeLevenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }
}
