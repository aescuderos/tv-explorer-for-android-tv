package com.tvexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#14100F");
    private static final int PANEL = Color.parseColor("#1F1816");
    private static final int ACCENT = Color.parseColor("#D93A3A");
    private static final int T_FAV = 0, T_SEARCH = 1, T_COUNTRY = 2, T_CAT = 3, T_REFRESH = 4;
    private static final long MAX_AGE = 14L * 24 * 3600 * 1000;
    private static final long ALIVE_TTL = 12L * 3600 * 1000;

    private static final Map<String, String> CAT_ES = new HashMap<String, String>();
    static {
        String[] p = {"animation", "Animación", "auto", "Motor", "business", "Negocios", "classic", "Clásicos", "comedy", "Comedia",
                "cooking", "Cocina", "culture", "Cultura", "documentary", "Documentales", "education", "Educación",
                "entertainment", "Entretenimiento", "family", "Familia", "general", "Generalistas", "kids", "Infantil",
                "legislative", "Parlamentos", "lifestyle", "Estilo de vida", "movies", "Películas", "music", "Música",
                "news", "Noticias", "outdoor", "Aire libre", "public", "Públicos", "relax", "Relax", "religious", "Religión",
                "science", "Ciencia", "series", "Series", "shop", "Compras", "sports", "Deportes", "travel", "Viajes", "weather", "Tiempo"};
        for (int i = 0; i < p.length; i += 2) CAT_ES.put(p[i], p[i + 1]);
    }

    private static final class Entry {
        int type; String label; String key; int count;
        Entry(int t, String l, String k, int c) { type = t; label = l; key = k; count = c; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService checkPool = Executors.newFixedThreadPool(24);
    private final Map<String, Boolean> alive = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Long> aliveAt = new ConcurrentHashMap<String, Long>();
    private int gen;
    private boolean lenient;
    private File aliveFile;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private List<Catalog.Ch> all = new ArrayList<Catalog.Ch>();
    private List<Catalog.Ch> shown = new ArrayList<Catalog.Ch>();
    private final List<Entry> entries = new ArrayList<Entry>();
    private int current = -1;
    private String country;
    private String query = "";
    private Set<String> favs = new HashSet<String>();
    private SharedPreferences prefs;
    private File cacheFile;
    private ListView sideList;
    private GridView grid;
    private TextView header;
    private TextView status;
    private float dp;
    private boolean resetPending;
    private boolean ready;
    private boolean compact;
    private Button countryButton, categoryButton;
    private String verificationStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("tvx", MODE_PRIVATE);
        country = prefs.getString("country", "ES");
        favs = new HashSet<String>(prefs.getStringSet("favs", new HashSet<String>()));
        cacheFile = new File(getFilesDir(), "channels.tsv");
        aliveFile = new File(getFilesDir(), "alive.tsv");
        loadAlive();
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(BG);
        setContentView(status);
        boolean stale = !cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > MAX_AGE;
        startLoad(stale);
    }

    private void startLoad(final boolean download) {
        ready = false;
        setContentView(status);
        status.setText(download ? "Cargando canales…" : "Abriendo…");
        pool.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                if (download) {
                    alive.clear(); aliveAt.clear(); aliveFile.delete();
                    try {
                        Catalog.refresh(getCacheDir(), cacheFile, new Catalog.Progress() {
                            @Override public void on(final String m) { ui.post(new Runnable() { @Override public void run() { status.setText(m); } }); }
                        });
                    } catch (Exception e) { err = String.valueOf(e); }
                }
                final List<Catalog.Ch> list;
                List<Catalog.Ch> tmp = null;
                try { if (cacheFile.exists()) tmp = Catalog.load(cacheFile); } catch (Exception e) { if (err == null) err = String.valueOf(e); }
                list = tmp;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (list == null || list.isEmpty()) {
                            status.setText("No se pudo cargar la lista de canales.\n" + (fe != null ? fe : "") + "\n\nPulsa OK para reintentar");
                            status.setFocusable(true);
                            status.requestFocus();
                            status.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startLoad(true); } });
                            return;
                        }
                        all = list;
                        if (fe != null) Toast.makeText(MainActivity.this, "Sin conexión: se usa la lista guardada", Toast.LENGTH_LONG).show();
                        buildUi();
                        ready = true;
                        refreshScope();
                    }
                });
            }
        });
    }

    private String countryName(String code) {
        if (code == null || code.isEmpty()) return "Todos los países";
        String n = new Locale("", code).getDisplayCountry(new Locale("es"));
        return n == null || n.isEmpty() ? code : n;
    }

    private String catName(String c) {
        String n = CAT_ES.get(c);
        return n != null ? n : Character.toUpperCase(c.charAt(0)) + c.substring(1);
    }

    private boolean inCountry(Catalog.Ch c) { return country.isEmpty() || country.equals(c.country); }

    private void rebuildEntries(boolean selectDefault) {
        entries.clear();
        entries.add(new Entry(T_FAV, "★  Favoritos", null, favs.size()));
        entries.add(new Entry(T_SEARCH, "Buscar…", null, 0));
        entries.add(new Entry(T_COUNTRY, "País: " + countryName(country), null, 0));
        int total = 0;
        TreeMap<String, Integer> counts = new TreeMap<String, Integer>();
        for (Catalog.Ch c : all) {
            if (!inCountry(c) || !okFor(c)) continue;
            total++;
            for (String k : c.cats) { Integer n = counts.get(k); counts.put(k, n == null ? 1 : n + 1); }
        }
        entries.add(new Entry(T_CAT, "Todos", "", total));
        List<Map.Entry<String, Integer>> cl = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(cl, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return catName(a.getKey()).compareToIgnoreCase(catName(b.getKey())); }
        });
        for (Map.Entry<String, Integer> e : cl) entries.add(new Entry(T_CAT, catName(e.getKey()), e.getKey(), e.getValue()));
        entries.add(new Entry(T_REFRESH, "Actualizar lista", null, 0));
        updateNavigation();
        int sel = selectDefault ? 3 : Math.max(0, Math.min(current, entries.size() - 1));
        current = -1;
        if (sideList != null) sideList.setSelectionFromTop(sel, (int) (Math.min(sel, 3) * 44 * dp));
        showEntry(sel);
        if (sideList != null) sideList.requestFocus();
    }

    private void buildUi() {
        Configuration config = getResources().getConfiguration();
        compact = (config.uiMode & Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION
                && config.screenWidthDp < 600;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int listW = compact ? 0 : (int) (screenW * 0.23f);
        int pad = (int) (10 * dp);
        sideList = null;
        countryButton = null;
        categoryButton = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        if (compact) buildMobileControls(left);
        header = new TextView(this);
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 22);
        header.setMaxLines(compact ? 2 : 1);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (4 * dp));
        left.addView(header, new LinearLayout.LayoutParams(-1, -2));

        int cols = compact ? Math.max(2, (int) ((screenW / dp - 20) / 180)) : 5;
        int gap = (int) (6 * dp);
        grid = new GridView(this);
        grid.setNumColumns(cols);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(pad, (int) (4 * dp), pad, pad);
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(0));
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(false);
        int cellW = (screenW - listW - 2 * pad - gap * (cols - 1)) / cols;
        grid.setAdapter(new ChannelAdapter(cellW));
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { play(shown.get(pos), 0); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { options(shown.get(pos)); return true; }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                grid.setSelector(new ColorDrawable(has ? ACCENT : 0));
                if (has && resetPending) {
                    resetPending = false;
                    resetGrid();
                }
            }
        });
        left.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (compact) {
            TextView hint = new TextView(this);
            hint.setText("Mantén pulsado un canal para favoritos y emisiones alternativas");
            hint.setTextColor(Color.LTGRAY);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setPadding(pad, pad, pad, pad);
            left.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        if (!compact) buildSidebar(root, listW, pad);
        setContentView(root);
        updateNavigation();
    }

    private void buildSidebar(LinearLayout root, int listW, int pad) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(PANEL);
        TextView brand = new TextView(this);
        brand.setText("TV EXPLORER");
        brand.setTextColor(ACCENT);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        brand.setPadding((int) (20 * dp), (int) (14 * dp), 0, (int) (6 * dp));
        side.addView(brand, new LinearLayout.LayoutParams(-1, -2));
        sideList = new ListView(this);
        sideList.setDivider(null);
        sideList.setVerticalScrollBarEnabled(false);
        sideList.setSelector(new ColorDrawable(ACCENT));
        sideList.setPadding(0, pad, 0, pad);
        sideList.setClipToPadding(false);
        sideList.setAdapter(new SideAdapter());
        sideList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { showEntry(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        sideList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { activate(pos); }
        });
        side.addView(sideList, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(side, new LinearLayout.LayoutParams(listW, -1));
    }

    private Button mobileButton(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(Color.WHITE);
        button.setMinHeight((int) (48 * dp));
        button.setOnClickListener(action);
        return button;
    }

    private void buildMobileControls(LinearLayout parent) {
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding((int) (10 * dp), 0, (int) (6 * dp), 0);
        TextView brand = new TextView(this);
        brand.setText("TV EXPLORER");
        brand.setTextColor(ACCENT);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = mobileButton("⋮", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setItems(new String[]{"Actualizar lista"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { startLoad(true); }
                        }).show();
            }
        });
        menu.setContentDescription("Más opciones");
        brandRow.addView(menu, new LinearLayout.LayoutParams((int) (48 * dp), -2));
        parent.addView(brandRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding((int) (6 * dp), 0, (int) (6 * dp), 0);
        countryButton = mobileButton(countryName(country), new View.OnClickListener() {
            @Override public void onClick(View v) { pickCountry(); }
        });
        countryButton.setContentDescription("Elegir país");
        actions.addView(countryButton, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(mobileButton("Buscar", new View.OnClickListener() {
            @Override public void onClick(View v) { askSearch(); }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(mobileButton("Favoritos", new View.OnClickListener() {
            @Override public void onClick(View v) { showEntry(0); }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        categoryButton = mobileButton("Categorías", new View.OnClickListener() {
            @Override public void onClick(View v) { pickCategory(); }
        });
        categoryButton.setContentDescription("Elegir categoría");
        parent.addView(categoryButton, new LinearLayout.LayoutParams(-1, -2));
    }

    private void pickCategory() {
        final List<Integer> positions = new ArrayList<Integer>();
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type == T_CAT) {
                positions.add(i);
                labels.add(e.label + " (" + e.count + ")");
            }
        }
        new AlertDialog.Builder(this).setTitle("Categorías")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showEntry(positions.get(which)); }
                }).setNegativeButton("Cancelar", null).show();
    }

    private void updateNavigation() {
        if (sideList != null) ((BaseAdapter) sideList.getAdapter()).notifyDataSetChanged();
        if (countryButton != null) countryButton.setText(countryName(country));
        if (categoryButton != null) {
            Entry e = current >= 0 && current < entries.size() ? entries.get(current) : null;
            categoryButton.setText(e != null && e.type == T_CAT ? "Categoría: " + e.label : "Categorías");
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        dp = getResources().getDisplayMetrics().density;
        if (!ready) return;
        buildUi();
        if (verificationStatus != null) header.setText(verificationStatus);
        else rebuildEntries(false);
    }

    private void resetGrid() {
        grid.setSelection(0);
        grid.post(new Runnable() { @Override public void run() { grid.setSelection(0); grid.smoothScrollToPosition(0); } });
    }

    private void applyFilter(Entry e) {
        List<Catalog.Ch> out = new ArrayList<Catalog.Ch>();
        String title;
        if (e.type == T_FAV) {
            for (Catalog.Ch c : all) if (favs.contains(c.id)) out.add(c);
            title = "Favoritos";
        } else if (e.type == T_SEARCH) {
            String q = query.toLowerCase(Locale.ROOT);
            if (!q.isEmpty()) for (Catalog.Ch c : all) if (c.name.toLowerCase(Locale.ROOT).contains(q) && okFor(c)) out.add(c);
            title = q.isEmpty() ? "Buscar" : "Búsqueda: " + query;
        } else {
            for (Catalog.Ch c : all) {
                if (!inCountry(c) || !okFor(c)) continue;
                boolean ok = e.key.isEmpty();
                for (String k : c.cats) if (k.equals(e.key)) ok = true;
                if (ok) out.add(c);
            }
            title = countryName(country) + "  ·  " + e.label;
        }
        Collections.sort(out, new Comparator<Catalog.Ch>() {
            @Override public int compare(Catalog.Ch a, Catalog.Ch b) { return a.name.compareToIgnoreCase(b.name); }
        });
        shown = out;
        header.setText(title + "  ·  " + out.size() + " canales");
    }

    private void showEntry(int idx) {
        if (idx == current || idx < 0 || idx >= entries.size()) return;
        Entry e = entries.get(idx);
        if (e.type == T_COUNTRY || e.type == T_REFRESH) return;
        current = idx;
        applyFilter(e);
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        resetPending = true;
        grid.setSelector(new ColorDrawable(grid.hasFocus() ? ACCENT : 0));
        resetGrid();
        updateNavigation();
    }

    private void activate(int pos) {
        Entry e = entries.get(pos);
        if (e.type == T_COUNTRY) pickCountry();
        else if (e.type == T_REFRESH) startLoad(true);
        else if (e.type == T_SEARCH) askSearch();
        else enterGrid();
    }

    private void enterGrid() {
        resetPending = false;
        if (shown.isEmpty()) return;
        grid.requestFocus();
        grid.setSelection(0);
        for (int d : new int[]{30, 120, 300}) grid.postDelayed(new Runnable() { @Override public void run() { grid.setSelection(0); } }, d);
    }

    private void pickCountry() {
        TreeMap<String, Integer> counts = new TreeMap<String, Integer>();
        for (Catalog.Ch c : all) if (c.country != null && !c.country.isEmpty()) { Integer n = counts.get(c.country); counts.put(c.country, n == null ? 1 : n + 1); }
        final List<String> codes = new ArrayList<String>(counts.keySet());
        Collections.sort(codes, new Comparator<String>() {
            @Override public int compare(String a, String b) { return countryName(a).compareToIgnoreCase(countryName(b)); }
        });
        codes.add(0, "");
        String[] names = new String[codes.size()];
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            String c = codes.get(i);
            names[i] = countryName(c) + (c.isEmpty() ? "" : "  (" + counts.get(c) + ")");
            if (c.equals(country)) sel = i;
        }
        AlertDialog d = new AlertDialog.Builder(this).setTitle("País").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface di, int which) {
                country = codes.get(which);
                prefs.edit().putString("country", country).apply();
                refreshScope();
            }
        }).create();
        d.show();
        d.getListView().setSelection(sel);
    }

    private void askSearch() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(query);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Buscar canal (todos los países)").setView(et)
                .setPositiveButton("Buscar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        query = et.getText().toString().trim();
                        List<Catalog.Ch> m = new ArrayList<Catalog.Ch>();
                        String q = query.toLowerCase(Locale.ROOT);
                        if (!q.isEmpty()) for (Catalog.Ch c : all) if (c.name.toLowerCase(Locale.ROOT).contains(q)) m.add(c);
                        Collections.sort(m, new Comparator<Catalog.Ch>() {
                            @Override public int compare(Catalog.Ch a, Catalog.Ch b) { return a.name.compareToIgnoreCase(b.name); }
                        });
                        if (m.size() > 300) m = new ArrayList<Catalog.Ch>(m.subList(0, 300));
                        lenient = false;
                        verify(m, new Runnable() {
                            @Override public void run() {
                                current = -1;
                                showEntry(1);
                                enterGrid();
                            }
                        });
                    }
                }).setNegativeButton("Cancelar", null).show();
    }

    private void toggleFav(Catalog.Ch c) {
        Set<String> n = new HashSet<String>(favs);
        if (!n.remove(c.id)) n.add(c.id);
        favs = n;
        prefs.edit().putStringSet("favs", n).apply();
        Entry e = entries.get(current);
        if (e.type == T_FAV) {
            int pos = grid.getSelectedItemPosition();
            applyFilter(e);
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
            if (pos >= 0) grid.setSelection(Math.min(pos, Math.max(0, shown.size() - 1)));
            if (shown.isEmpty() && sideList != null) sideList.requestFocus();
        } else {
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
        entries.get(0).count = favs.size();
        updateNavigation();
    }

    private void options(final Catalog.Ch c) {
        List<String> items = new ArrayList<String>();
        items.add(favs.contains(c.id) ? "Quitar de favoritos" : "Añadir a favoritos");
        for (int i = 1; i < c.urls.length; i++) items.add("Probar emisión alternativa " + i);
        new AlertDialog.Builder(this).setTitle(c.name).setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) toggleFav(c); else play(c, which);
            }
        }).show();
    }

    private void play(Catalog.Ch c, int n) {
        List<String> ord = ordered(c);
        if (ord.isEmpty()) {
            Toast.makeText(this, "Este canal no tiene emisiones disponibles", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_NAME, c.name);
        i.putStringArrayListExtra(PlayerActivity.EXTRA_URLS, new ArrayList<String>(ord));
        i.putExtra(PlayerActivity.EXTRA_INDEX, Math.min(n, ord.size() - 1));
        startActivity(i);
    }

    private void loadAlive() {
        try {
            if (!aliveFile.exists()) return;
            BufferedReader r = new BufferedReader(new FileReader(aliveFile));
            String l;
            while ((l = r.readLine()) != null) {
                String[] f = l.split("\t");
                if (f.length == 3) { alive.put(f[0], f[1].equals("1")); aliveAt.put(f[0], Long.parseLong(f[2])); }
            }
            r.close();
        } catch (Exception e) { }
    }

    private synchronized void saveAlive() {
        try {
            PrintWriter w = new PrintWriter(new java.io.FileWriter(aliveFile));
            for (Map.Entry<String, Boolean> e : alive.entrySet()) w.println(e.getKey() + "\t" + (e.getValue() ? "1" : "0") + "\t" + aliveAt.get(e.getKey()));
            w.close();
        } catch (Exception e) { }
    }

    private boolean fresh(String url) {
        Long t = aliveAt.get(url);
        return t != null && System.currentTimeMillis() - t < ALIVE_TTL;
    }

    private boolean okFor(Catalog.Ch c) {
        boolean unknown = false;
        for (String u : c.urls) {
            Boolean a = alive.get(u);
            if (a == null) unknown = true; else if (a && fresh(u)) return true;
        }
        return lenient && unknown;
    }

    private List<String> ordered(Catalog.Ch c) {
        List<String> good = new ArrayList<String>(), rest = new ArrayList<String>();
        for (String u : c.urls) { if (Boolean.TRUE.equals(alive.get(u))) good.add(u); else rest.add(u); }
        good.addAll(rest);
        return good;
    }

    /** Checks each channel's streams in order until one works, then runs done on the UI thread. */
    private void verify(final List<Catalog.Ch> chs, final Runnable done) {
        final int my = ++gen;
        final List<Catalog.Ch> todo = new ArrayList<Catalog.Ch>();
        for (Catalog.Ch c : chs) {
            boolean ok = false, need = false;
            for (String u : c.urls) {
                if (Boolean.TRUE.equals(alive.get(u)) && fresh(u)) { ok = true; break; }
                if (!fresh(u)) need = true;
            }
            if (!ok && need) todo.add(c);
        }
        verificationStatus = null;
        if (todo.isEmpty()) { done.run(); return; }
        shown = new ArrayList<Catalog.Ch>();
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        final int total = todo.size();
        final AtomicInteger n = new AtomicInteger();
        verificationStatus = "Comprobando emisiones… 0/" + total;
        header.setText(verificationStatus);
        for (final Catalog.Ch c : todo) {
            checkPool.execute(new Runnable() {
                @Override public void run() {
                    if (my != gen) return;
                    for (String u : c.urls) {
                        if (my != gen) return;
                        if (fresh(u) && !Boolean.TRUE.equals(alive.get(u))) continue;
                        boolean ok = Boolean.TRUE.equals(alive.get(u)) && fresh(u) || Catalog.check(u);
                        alive.put(u, ok);
                        aliveAt.put(u, System.currentTimeMillis());
                        if (ok) break;
                    }
                    final int k = n.incrementAndGet();
                    if (k % 5 == 0 || k == total) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (my != gen) return;
                                if (k == total) { verificationStatus = null; saveAlive(); done.run(); }
                                else {
                                    verificationStatus = "Comprobando emisiones… " + k + "/" + total;
                                    header.setText(verificationStatus);
                                }
                            }
                        });
                    }
                }
            });
        }
    }

    private void refreshScope() {
        lenient = country.isEmpty();
        if (country.isEmpty()) { rebuildEntries(true); return; }
        List<Catalog.Ch> l = new ArrayList<Catalog.Ch>();
        for (Catalog.Ch c : all) if (country.equals(c.country)) l.add(c);
        verify(l, new Runnable() { @Override public void run() { rebuildEntries(true); } });
    }

    private void cycle(int dir) {
        int n = entries.size();
        int i = current;
        for (int k = 0; k < n; k++) {
            i = (i + dir + n) % n;
            int t = entries.get(i).type;
            if (t == T_FAV || t == T_CAT) {
                if (sideList != null) sideList.setSelection(i); else showEntry(i);
                return;
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!ready || grid == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT && sideList != null && sideList.hasFocus() && !shown.isEmpty()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) enterGrid();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_A) return super.dispatchKeyEvent(new KeyEvent(e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER));
        if (k == KeyEvent.KEYCODE_BUTTON_B) {
            if (e.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        if ((k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_X) && grid.hasFocus()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int p = grid.getSelectedItemPosition();
                if (p >= 0 && p < shown.size()) options(shown.get(p));
            }
            return true;
        }
        if ((k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1) && e.getAction() == KeyEvent.ACTION_DOWN) {
            cycle(k == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (ready && grid != null && grid.hasFocus() && sideList != null) sideList.requestFocus(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        checkPool.shutdownNow();
        super.onDestroy();
    }

    private class SideAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return null; }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup parent) {
            TextView t = (TextView) v;
            if (t == null) {
                t = new TextView(MainActivity.this);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding((int) (20 * dp), 0, (int) (12 * dp), 0);
                t.setLayoutParams(new AbsListView.LayoutParams(-1, (int) (44 * dp)));
            }
            Entry e = entries.get(p);
            t.setText(e.label + ((e.type == T_CAT || e.type == T_FAV) && e.count > 0 ? "  " + e.count : ""));
            boolean sel = p == current;
            boolean action = e.type == T_COUNTRY || e.type == T_REFRESH;
            t.setTextColor(sel ? Color.WHITE : Color.parseColor(action ? "#E8A9A9" : "#B3A6A3"));
            t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            return t;
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        private final int cellW;
        ChannelAdapter(int w) { cellW = w; }
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return null; }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup parent) {
            Cell c = (Cell) v;
            if (c == null) c = new Cell(cellW);
            c.bind(shown.get(p));
            return c;
        }
    }

    private class Cell extends LinearLayout {
        private final ImageView img;
        private final TextView title, star;
        private String tag = "";

        Cell(int w) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            int pad = (int) (6 * dp);
            setPadding(pad, pad, pad, pad);
            FrameLayout f = new FrameLayout(MainActivity.this);
            img = new ImageView(MainActivity.this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(pad, pad, pad, pad);
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
            star = new TextView(MainActivity.this);
            star.setText("★");
            star.setTextColor(Color.parseColor("#FFC93C"));
            star.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            star.setPadding(pad, 0, pad, 0);
            f.addView(star, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
            addView(f, new LayoutParams(-1, (int) (w * 0.6f)));
            title = new TextView(MainActivity.this);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 14);
            title.setMinLines(2);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            addView(title, new LayoutParams(-1, -2));
            setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        }

        void bind(Catalog.Ch c) {
            title.setText(c.name);
            star.setVisibility(favs.contains(c.id) ? VISIBLE : GONE);
            img.setImageDrawable(null);
            img.setBackgroundColor(Color.parseColor("#2A2220"));
            tag = c.logo == null ? "" : c.logo;
            if (tag.isEmpty()) return;
            Bitmap bm = cache.get(tag);
            if (bm != null) { img.setImageBitmap(bm); return; }
            final String url = tag;
            pool.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap b = fetchLogo(url);
                    if (b == null) return;
                    cache.put(url, b);
                    ui.post(new Runnable() { @Override public void run() { if (url.equals(tag)) img.setImageBitmap(b); } });
                }
            });
        }
    }

    private Bitmap fetchLogo(String url) {
        try {
            File dir = new File(getCacheDir(), "logos");
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()));
            if (!f.exists()) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", Catalog.UA);
                if (c.getResponseCode() != 200) return null;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); if (o.size() > 2_000_000) return null; }
                in.close();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(o.toByteArray());
                fo.close();
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), opt);
            int s = 1;
            while (opt.outWidth / (s * 2) >= 300) s *= 2;
            opt = new BitmapFactory.Options();
            opt.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getPath(), opt);
        } catch (Exception e) {
            return null;
        }
    }
}
