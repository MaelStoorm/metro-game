package io.github.maelstoorm.metroistanbul;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Base64;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Metro İstanbul: web sürümüyle aynı oyun (assets/index.html) tam ekran bir WebView içinde.
 * Oyun window.AndroidTTS köprüsünü kullanır: durak anonsları (metin okuma), fotoğraf kaydetme,
 * hatırlatma bildirimi ve ödüllü reklamla devam etme.
 */
public class MainActivity extends Activity {
    /** Oyun https kökenli sanal bir adresten açılır; dosyalar uygulamanın assets klasöründen gelir. */
    private static final String GAME_URL = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/assets/index.html";
    private static final int BG = 0xFF0E1F2A;
    private static final String GOOGLE_TTS = "com.google.android.tts";
    private static final Locale TR = new Locale("tr", "TR");

    private FrameLayout root;
    private WebView web;
    private View adLoading;
    private WebViewAssetLoader assets;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private RewardedAds ads;
    private boolean resumed;
    private String pendingAdResult;

    /** Oyunun JavaScript tarafından window.AndroidTTS adıyla çağrılır. Yöntem adları oyunla aynı kalmalı. */
    public class Bridge {
        /** Durak anonsu. tr: Türkçe metin, en: (varsa) İngilizce metin, voice: seçili ses adı ("" = otomatik), vol: 0-1. */
        @JavascriptInterface
        public void speak(final String tr, final String en, final String voice, final float vol) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { speakNow(tr, en, voice, vol); }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (tts != null && ttsReady) tts.stop();
                }
            });
        }

        /** Türkçe sesler: [{n: ad, l: ayarlarda görünen etiket, g: 1 kadın / -1 erkek / 0 bilinmiyor}] */
        @JavascriptInterface
        public String voices() {
            JSONArray out = new JSONArray();
            try {
                int f = 0, m = 0, o = 0;
                for (Voice v : trVoices()) {
                    int g = gender(v.getName());
                    String label = g > 0 ? "Kadın sesi " + (++f) : g < 0 ? "Erkek sesi " + (++m) : "Ses " + (++o);
                    if (v.isNetworkConnectionRequired()) label += " (internetle, daha doğal)";
                    JSONObject j = new JSONObject();
                    j.put("n", v.getName());
                    j.put("l", label);
                    j.put("g", g);
                    out.put(j);
                }
            } catch (Exception e) {
                // ses listesi alınamazsa oyun yalnızca gong çalar
            }
            return out.toString();
        }

        /** Fotoğraf modu: PNG (base64) Galeri > Pictures > Metro İstanbul klasörüne kaydedilir. */
        @JavascriptInterface
        public boolean saveImage(String base64, String name) {
            return saveImageNow(base64, name);
        }

        @JavascriptInterface
        public void setReminders(boolean on) {
            ReminderReceiver.setEnabled(MainActivity.this, on);
        }

        /** "Reklam izle, kaldığın yerden devam et". Sonuç window.__adDone(true|false) ile oyuna döner. */
        @JavascriptInterface
        public void showRewarded() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ads != null) ads.show();
                }
            });
        }

        /** AB ve Birleşik Krallık'ta reklam izin seçenekleri oyun ayarlarından yeniden açılabilmeli. */
        @JavascriptInterface
        public boolean privacyOptionsRequired() {
            return ads != null && ads.privacyOptionsRequired();
        }

        @JavascriptInterface
        public void showPrivacyOptions() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ads != null) ads.showPrivacyOptions();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        assets = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return assets.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (WebViewAssetLoader.DEFAULT_DOMAIN.equals(u.getHost())) return false;
                // Oyunun dışındaki bağlantılar tarayıcıda açılsın, oyun kaybolmasın.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    // açacak uygulama yok
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // WebView'in çizim süreci çökerse uygulama kapanmasın: ekran yeniden kurulur, oyun kayıttan devam eder.
                if (v == web) {
                    root.removeView(web);
                    web.destroy();
                    web = null;
                    recreate();
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidTTS");
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        adLoading = buildAdLoadingView();
        root.addView(adLoading, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                return applyInsets(v, in);
            }
        });
        setContentView(root);
        hideBars();

        initTts();
        ads = new RewardedAds(this, BuildConfig.ADMOB_REWARDED_ID, new RewardedAds.Listener() {
            @Override
            public void onAdLoading(boolean loading) {
                if (adLoading != null) adLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onAdResult(String status) { sendAdResult(status); }
        });

        if (state == null || web.restoreState(state) == null) web.loadUrl(GAME_URL);

        // Önce reklam izni (gerekiyorsa Google'ın izin penceresi), ardından bildirim izni sorulur; iki pencere üst üste binmesin.
        ads.gatherConsent(new Runnable() {
            @Override
            public void run() { askNotificationPermission(); }
        });
    }

    // ---------- ekran ----------

    /**
     * Oyun ekranın tamamını kullanır. Kamera deliği (ve görünürse sistem çubukları) kadar boşluk bırakılır,
     * böylece puan ve düğmeler deliğin altında kalmaz.
     */
    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View v, WindowInsets in) {
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = i.left;
            t = i.top;
            r = i.right;
            b = i.bottom;
        } else if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                l = dc.getSafeInsetLeft();
                t = dc.getSafeInsetTop();
                r = dc.getSafeInsetRight();
                b = dc.getSafeInsetBottom();
            }
        }
        v.setPadding(l, t, r, b);
        if (Build.VERSION.SDK_INT >= 30) return WindowInsets.CONSUMED;
        if (Build.VERSION.SDK_INT >= 28) return in.consumeDisplayCutout().consumeSystemWindowInsets();
        return in.consumeSystemWindowInsets();
    }

    /** Tam ekran: üst çubuk ve alt tuşlar gizli, kenardan kaydırınca kısa süre görünür. */
    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** Reklam yüklenirken oyunun üstünde görünen küçük bekleme ekranı. */
    private View buildAdLoadingView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xB30E1F2A);
        box.setClickable(true);
        box.setVisibility(View.GONE);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        box.addView(pb);
        TextView tv = new TextView(this);
        tv.setText(R.string.ad_loading);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(0, Math.round(12 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(tv);
        return box;
    }

    // ---------- ödüllü reklam ----------

    /** Reklam sonucu oyuna bildirilir. Reklam ekranı kapanırken oyun henüz duraklatılmış olabilir; o zaman dönüşte iletilir. */
    private void sendAdResult(String status) {
        if (resumed) runAdResult(status);
        else pendingAdResult = status;
    }

    private void runAdResult(String status) {
        if (web == null) return;
        // Reklam sonuna kadar izlendiyse ödül verilir. Reklam hiç yüklenemediyse (internet yok, reklam yok, izin yok)
        // oyuncu cezalandırılmaz: web sürümündeki bekleme ekranında olduğu gibi devam hakkı verilir.
        // Oyuncu reklamı erken kapattıysa ödül yok.
        boolean ok = !"dismissed".equals(status);
        web.evaluateJavascript("window.__adDone&&window.__adDone(" + ok + ")", null);
    }

    // ---------- bildirim izni ----------

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || isFinishing()) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = ReminderReceiver.prefs(this);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
    }

    // ---------- metin okuma (durak anonsları) ----------

    private void initTts() {
        TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                try {
                    tts.setLanguage(TR);
                    tts.setSpeechRate(0.97f);
                    Voice best = best();
                    if (best != null) tts.setVoice(best);
                } catch (Exception e) {
                    // ses ayarlanamazsa varsayılan ses kalır
                }
                ttsReady = true;
            }
        };
        // Google'ın konuşma motoru varsa o kullanılır (Türkçe kadın sesleri daha iyi), yoksa cihazın varsayılanı.
        boolean google = false;
        try {
            getPackageManager().getPackageInfo(GOOGLE_TTS, 0);
            google = true;
        } catch (Exception e) {
            // yüklü değil
        }
        try {
            tts = google ? new TextToSpeech(this, onInit, GOOGLE_TTS) : new TextToSpeech(this, onInit);
        } catch (Exception e) {
            tts = null;
        }
    }

    private void speakNow(String tr, String en, String voice, float vol) {
        if (tts == null || !ttsReady || tr == null) return;
        try {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.max(0f, Math.min(1f, vol)));
            tts.setSpeechRate(0.97f);
            tts.setPitch(1.0f);
            Voice chosen = null;
            if (voice != null && voice.length() > 0) {
                Set<Voice> all = tts.getVoices();
                if (all != null) {
                    for (Voice v : all) {
                        if (voice.equals(v.getName())) {
                            chosen = v;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) chosen = best();
            if (chosen != null) tts.setVoice(chosen);
            else tts.setLanguage(TR);
            tts.speak(tr, TextToSpeech.QUEUE_FLUSH, params, "anons-tr");
            if (en != null && en.length() > 0 && tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(Locale.US);
                tts.speak(en, TextToSpeech.QUEUE_ADD, params, "anons-en");
                if (chosen != null) tts.setVoice(chosen);
                else tts.setLanguage(TR);
            }
        } catch (Exception e) {
            // anons okunamazsa oyun etkilenmesin
        }
    }

    /** Cihazda yüklü Türkçe sesler, en uygunu başta: önce kadın sesi, sonra internetsiz çalışan, sonra kalite. */
    private List<Voice> trVoices() {
        List<Voice> out = new ArrayList<>();
        if (tts == null || !ttsReady) return out;
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return out;
            for (Voice v : all) {
                if (v.getLocale() == null || !"tr".equals(v.getLocale().getLanguage())) continue;
                Set<String> features = v.getFeatures();
                if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                out.add(v);
            }
            Collections.sort(out, (a, b) -> Integer.compare(score(b), score(a)));
        } catch (Exception e) {
            // boş liste
        }
        return out;
    }

    private Voice best() {
        List<Voice> l = trVoices();
        return l.isEmpty() ? null : l.get(0);
    }

    private static int score(Voice v) {
        return gender(v.getName()) * 10000 + (v.isNetworkConnectionRequired() ? 0 : 1000) + v.getQuality();
    }

    /** Ses adından cinsiyet tahmini: 1 kadın, -1 erkek, 0 bilinmiyor (Google ve Samsung ses adları). */
    static int gender(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("x-cfs") || n.contains("x-efs") || n.contains("x-ama") || n.contains("smtf")
            || n.contains("female") || n.contains("kadin") || n.contains("-f0")) return 1;
        if (n.contains("x-mfm") || n.contains("x-tmc") || n.contains("smtm")
            || n.contains("male") || n.contains("erkek") || n.contains("-m0")) return -1;
        return 0;
    }

    // ---------- fotoğraf modu ----------

    private boolean saveImageNow(String base64, String name) {
        try {
            byte[] png = Base64.decode(base64, Base64.DEFAULT);
            String file = (name == null || name.isEmpty()) ? "metro-istanbul.png" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Metro İstanbul");
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(png);
                }
                return true;
            }
            // Android 9 ve öncesi: izin istemeden uygulamanın kendi Resimler klasörüne.
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Metro İstanbul");
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(new File(dir, file))) {
                fos.write(png);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- yaşam döngüsü ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Not: WebView.pauseTimers() bilerek çağrılmıyor; tüm WebView'leri (reklamınkini de) durdurur.
        if (web != null) web.onPause();
        if (tts != null && ttsReady) tts.stop();
        ReminderReceiver.schedule(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) web.onResume();
        hideBars();
        ReminderReceiver.cancel(this);
        if (pendingAdResult != null) {
            String status = pendingAdResult;
            pendingAdResult = null;
            runAdResult(status);
        }
    }

    @Override
    protected void onDestroy() {
        if (ads != null) ads.destroy();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {
                // yok say
            }
            tts = null;
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
