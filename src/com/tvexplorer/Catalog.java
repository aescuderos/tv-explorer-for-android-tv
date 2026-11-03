package com.tvexplorer;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Downloads the public iptv-org API and keeps a compact local copy of playable channels. */
final class Catalog {
    static final String API = "https://iptv-org.github.io/api/";
    static final String UA = "TVExplorer/1.0";
    static final String TDT = "https://www.tdtchannels.com/lists/tv.json";
    static final String FREETV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";
    private static final int MAX_URLS = 6;

    static final class Ch {
        String id, name, country, logo;
        String[] cats = new String[0];
        String[] urls = new String[0];
    }

    interface Progress { void on(String msg); }

    static List<Ch> load(File cache) throws Exception {
        List<Ch> out = new ArrayList<Ch>();
        BufferedReader r = new BufferedReader(new FileReader(cache), 65536);
        String line;
        while ((line = r.readLine()) != null) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) continue;
            Ch c = new Ch();
            c.id = f[0]; c.name = f[1]; c.country = f[2];
            c.cats = f[3].isEmpty() ? new String[0] : f[3].split(",");
            c.logo = f[4];
            c.urls = f[5].split(" ");
            out.add(c);
        }
        r.close();
        return out;
    }

    static void refresh(File tmpDir, File cache, Progress p) throws Exception {
        File fc = new File(tmpDir, "channels.json"), fs = new File(tmpDir, "streams.json"), fl = new File(tmpDir, "logos.json");
        try {
            p.on("Descargando canales…");
            download(API + "channels.json", fc);
            p.on("Descargando emisiones…");
            download(API + "streams.json", fs);
            p.on("Descargando logos…");
            download(API + "logos.json", fl);
            p.on("Procesando…");
            Map<String, Ch> map = readChannels(fc);
            readStreams(fs, map);
            readLogos(fl, map);
            Map<String, Ch> index = new HashMap<String, Ch>();
            for (Ch c : map.values()) if (c.country != null) index.putIfAbsent(c.country + "|" + norm(c.name), c);
            try {
                p.on("Añadiendo TDTChannels…");
                File ft = new File(tmpDir, "tdt.json");
                try { download(TDT, ft); mergeTdt(ft, map, index); } finally { ft.delete(); }
            } catch (Exception e) { /* optional source */ }
            try {
                p.on("Añadiendo Free-TV…");
                File ff = new File(tmpDir, "free.m3u8");
                try { download(FREETV, ff); mergeFree(ff, map, index); } finally { ff.delete(); }
            } catch (Exception e) { /* optional source */ }
            List<Ch> list = new ArrayList<Ch>();
            for (Ch c : map.values()) if (c.urls.length > 0) list.add(c);
            if (list.size() < 100) throw new Exception("Catálogo vacío");
            File tmp = new File(cache.getPath() + ".tmp");
            BufferedWriter w = new BufferedWriter(new FileWriter(tmp), 65536);
            for (Ch c : list) {
                w.write(c.id + "\t" + c.name.replace('\t', ' ') + "\t" + (c.country == null ? "" : c.country) + "\t"
                        + join(c.cats, ",") + "\t" + (c.logo == null ? "" : c.logo) + "\t" + join(c.urls, " ") + "\n");
            }
            w.close();
            if (!tmp.renameTo(cache)) throw new Exception("No se pudo guardar el catálogo");
        } finally {
            fc.delete(); fs.delete(); fl.delete();
        }
    }

    private static String join(String[] a, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(sep); b.append(a[i]); }
        return b.toString();
    }

    static void download(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " " + url);
        InputStream in = c.getInputStream();
        OutputStream o = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close();
        in.close();
        c.disconnect();
    }

    private static JsonReader open(File f) throws Exception {
        return new JsonReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(f), 65536), "UTF-8"));
    }

    private static String str(JsonReader r) throws Exception {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        if (r.peek() == JsonToken.STRING) return r.nextString();
        r.skipValue();
        return null;
    }

    private static Map<String, Ch> readChannels(File f) throws Exception {
        Map<String, Ch> map = new HashMap<String, Ch>();
        JsonReader r = open(f);
        r.beginArray();
        while (r.hasNext()) {
            Ch c = new Ch();
            boolean nsfw = false, closed = false;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (k.equals("id")) c.id = str(r);
                else if (k.equals("name")) c.name = str(r);
                else if (k.equals("country")) c.country = str(r);
                else if (k.equals("is_nsfw")) nsfw = r.nextBoolean();
                else if (k.equals("closed")) closed = str(r) != null;
                else if (k.equals("categories") && r.peek() == JsonToken.BEGIN_ARRAY) {
                    List<String> cats = new ArrayList<String>();
                    r.beginArray();
                    while (r.hasNext()) { String s = str(r); if (s != null) cats.add(s); }
                    r.endArray();
                    c.cats = cats.toArray(new String[0]);
                } else r.skipValue();
            }
            r.endObject();
            boolean xxx = false;
            for (String s : c.cats) if (s.equals("xxx")) xxx = true;
            if (c.id != null && c.name != null && !nsfw && !closed && !xxx) map.put(c.id, c);
        }
        r.endArray();
        r.close();
        return map;
    }

    private static int qualityOf(String q) {
        if (q == null) return 0;
        try { return Math.min(1080, Integer.parseInt(q.replaceAll("[^0-9]", ""))); } catch (Exception e) { return 0; }
    }

    private static void readStreams(File f, Map<String, Ch> map) throws Exception {
        final Map<String, List<String[]>> found = new HashMap<String, List<String[]>>();
        JsonReader r = open(f);
        r.beginArray();
        while (r.hasNext()) {
            String ch = null, url = null, q = null, ua = null, ref = null;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (k.equals("channel")) ch = str(r);
                else if (k.equals("url")) url = str(r);
                else if (k.equals("quality")) q = str(r);
                else if (k.equals("user_agent")) ua = str(r);
                else if (k.equals("referrer")) ref = str(r);
                else r.skipValue();
            }
            r.endObject();
            if (ch == null || url == null || ua != null || ref != null || !url.startsWith("http") || url.indexOf(' ') >= 0) continue;
            if (!map.containsKey(ch)) continue;
            List<String[]> l = found.get(ch);
            if (l == null) { l = new ArrayList<String[]>(); found.put(ch, l); }
            if (l.size() < 12) l.add(new String[]{url, String.valueOf(qualityOf(q))});
        }
        r.endArray();
        r.close();
        for (Map.Entry<String, List<String[]>> e : found.entrySet()) {
            List<String[]> l = e.getValue();
            Collections.sort(l, new Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) { return Integer.parseInt(b[1]) - Integer.parseInt(a[1]); }
            });
            int n = Math.min(4, l.size());
            String[] urls = new String[n];
            for (int i = 0; i < n; i++) urls[i] = l.get(i)[0];
            map.get(e.getKey()).urls = urls;
        }
    }

    private static void readLogos(File f, Map<String, Ch> map) throws Exception {
        Map<String, Integer> score = new HashMap<String, Integer>();
        JsonReader r = open(f);
        r.beginArray();
        while (r.hasNext()) {
            String ch = null, feed = null, url = null, fmt = null;
            int w = 0;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (k.equals("channel")) ch = str(r);
                else if (k.equals("feed")) feed = str(r);
                else if (k.equals("url")) url = str(r);
                else if (k.equals("format")) fmt = str(r);
                else if (k.equals("width") && r.peek() == JsonToken.NUMBER) w = r.nextInt();
                else r.skipValue();
            }
            r.endObject();
            Ch c = ch == null ? null : map.get(ch);
            if (c == null || url == null || "SVG".equalsIgnoreCase(fmt) || url.endsWith(".svg")) continue;
            int s = (feed == null ? 100000 : 0) + Math.min(w, 1000);
            Integer old = score.get(ch);
            if (old == null || s > old) { score.put(ch, s); c.logo = url; }
        }
        r.endArray();
        r.close();
    }


    private static String norm(String n) {
        String s = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        boolean again = true;
        while (again) {
            again = false;
            for (String suf : new String[]{"hd", "sd", "geo", "fhd"}) {
                if (s.length() > suf.length() + 1 && s.endsWith(suf)) { s = s.substring(0, s.length() - suf.length()); again = true; }
            }
        }
        return s;
    }

    private static boolean usable(String u) {
        return u != null && u.startsWith("http") && u.indexOf('[') < 0 && u.indexOf(' ') < 0 && !u.contains("youtube.com") && !u.contains("youtu.be");
    }

    private static void add(Map<String, Ch> map, Map<String, Ch> index, String name, String country, String logo, String cat, List<String> urls) {
        if (name == null || name.trim().isEmpty() || country == null || country.isEmpty() || urls.isEmpty()) return;
        String key = country + "|" + norm(name);
        Ch c = index.get(key);
        if (c == null) {
            c = new Ch();
            c.id = "x:" + key;
            c.name = name.trim();
            c.country = country;
            c.cats = cat == null ? new String[0] : new String[]{cat};
            c.logo = logo == null || logo.isEmpty() ? null : logo;
            index.put(key, c);
            map.put(c.id, c);
        } else {
            if (c.logo == null && logo != null && !logo.isEmpty()) c.logo = logo;
            if (cat != null) {
                boolean has = false;
                for (String k : c.cats) if (k.equals(cat)) has = true;
                if (!has) {
                    String[] n = java.util.Arrays.copyOf(c.cats, c.cats.length + 1);
                    n[n.length - 1] = cat;
                    c.cats = n;
                }
            }
        }
        Set<String> merged = new LinkedHashSet<String>(urls);
        for (String u : c.urls) merged.add(u);
        List<String> l = new ArrayList<String>(merged);
        c.urls = l.subList(0, Math.min(MAX_URLS, l.size())).toArray(new String[0]);
    }

    private static String readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    private static final Map<String, String> TDT_CAT = new HashMap<String, String>();
    static {
        TDT_CAT.put("Generalistas", "general");
        TDT_CAT.put("Informativos", "news");
        TDT_CAT.put("Deportivos", "sports");
        TDT_CAT.put("Infantiles", "kids");
    }

    private static void mergeTdt(File f, Map<String, Ch> map, Map<String, Ch> index) throws Exception {
        JSONArray countries = new JSONObject(readAll(f)).getJSONArray("countries");
        for (int i = 0; i < countries.length(); i++) {
            JSONObject co = countries.getJSONObject(i);
            if (!"Spain".equals(co.optString("name"))) continue;
            JSONArray ambits = co.getJSONArray("ambits");
            for (int a = 0; a < ambits.length(); a++) {
                JSONObject am = ambits.getJSONObject(a);
                String an = am.optString("name");
                String cat = TDT_CAT.containsKey(an) ? TDT_CAT.get(an) : an;
                JSONArray chs = am.getJSONArray("channels");
                for (int k = 0; k < chs.length(); k++) {
                    JSONObject ch = chs.getJSONObject(k);
                    JSONArray opts = ch.optJSONArray("options");
                    List<String> urls = new ArrayList<String>();
                    if (opts != null) for (int o = 0; o < opts.length(); o++) {
                        String u = opts.getJSONObject(o).optString("url", null);
                        if (usable(u)) urls.add(u);
                    }
                    add(map, index, ch.optString("name"), "ES", ch.optString("logo", null), cat, urls);
                }
            }
        }
    }

    private static void mergeFree(File f, Map<String, Ch> map, Map<String, Ch> index) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f), 65536);
        String line, name = null, cc = null, logo = null;
        boolean skip = false;
        java.util.regex.Pattern attr = java.util.regex.Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                name = line.substring(line.lastIndexOf(',') + 1).trim();
                cc = null; logo = null; skip = false;
                java.util.regex.Matcher m = attr.matcher(line);
                while (m.find()) {
                    String k = m.group(1), v = m.group(2);
                    if (k.equals("tvg-country")) cc = v.split("[;,]")[0].trim().toUpperCase(Locale.ROOT);
                    else if (k.equals("tvg-logo")) logo = v;
                    else if (k.equals("group-title") && v.startsWith("VOD")) skip = true;
                }
            } else if (line.startsWith("#EXTVLCOPT") || line.startsWith("#KODIPROP")) {
                skip = true;
            } else if (line.startsWith("http")) {
                if (!skip && name != null && cc != null && cc.length() == 2 && usable(line)) {
                    List<String> u = new ArrayList<String>();
                    u.add(line);
                    add(map, index, name.replaceAll("[ⒼⓈⓨⒶ]", "").trim(), cc, logo, null, u);
                }
                name = null; skip = false;
            }
        }
        r.close();
    }

    /** Probes a stream: manifest (and nested playlists) must load and the first media chunk must deliver data. */
    static boolean check(String url) {
        try {
            String cur = url;
            for (int depth = 0; depth < 4; depth++) {
                byte[] b = fetch(cur, 8192);
                if (b == null) return false;
                if (b.length >= 7 && new String(b, 0, 7, "UTF-8").equals("#EXTM3U")) {
                    b = fetch(cur, 400000);
                    if (b == null) return false;
                    String next = null;
                    for (String l : new String(b, "UTF-8").split("\r?\n")) {
                        l = l.trim();
                        if (!l.isEmpty() && !l.startsWith("#")) { next = l; break; }
                    }
                    if (next == null) return false;
                    cur = new URL(new URL(cur), next).toString();
                    continue;
                }
                return b.length >= 1000 && b[0] != '<';
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static byte[] fetch(String url, int limit) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", UA);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while (o.size() < limit && (n = in.read(buf)) > 0) o.write(buf, 0, n);
                return o.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        return null;
    }
}
