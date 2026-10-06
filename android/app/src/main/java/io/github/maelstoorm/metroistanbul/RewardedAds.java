package io.github.maelstoorm.metroistanbul;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google AdMob ödüllü reklamları ve Google'ın izin penceresi (User Messaging Platform).
 * Oyun genel kitleye yöneliktir, çocuklara yönelik değildir.
 * Sonuçlar: "rewarded" (reklam izlendi, ödül hak edildi), "dismissed" (ödülden önce kapatıldı),
 * "failed" (izin yok, reklam yüklenemedi ya da gösterilemedi).
 */
final class RewardedAds {
    interface Listener {
        /** Reklam yüklenmesi bekleniyor (true) ya da bekleme bitti (false). */
        void onAdLoading(boolean loading);

        void onAdResult(String status);
    }

    private static final String TAG = "MetroReklam";
    /** Reklam bu kadar sürede yüklenmezse beklemekten vazgeçilir. */
    private static final long LOAD_TIMEOUT_MS = 10000;
    /** Yüklenen ödüllü reklam bir saat geçerlidir; biraz önce tazelenir. */
    private static final long AD_MAX_AGE_MS = 50L * 60 * 1000;
    private static final AtomicBoolean sdkStarted = new AtomicBoolean(false);

    private final Activity activity;
    private final String unitId;
    private final Listener listener;
    private final ConsentInformation consent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private RewardedAd ad;
    private long adLoadedAt;
    private boolean loading, waiting, showing, earned, destroyed;
    private volatile boolean privacyRequired;

    private final Runnable loadTimeout = new Runnable() {
        @Override
        public void run() {
            if (!waiting) return;
            setWaiting(false);
            listener.onAdResult("failed");
        }
    };

    RewardedAds(Activity activity, String unitId, Listener listener) {
        this.activity = activity;
        this.unitId = unitId;
        this.listener = listener;
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    /** Her açılışta izin bilgisini yeniler, gerekiyorsa (ör. AB ve Birleşik Krallık) izin penceresini gösterir. */
    void gatherConsent(final Runnable done) {
        // Genel kitle oyunu: "rıza yaşının altındaki kullanıcı" etiketi konmaz (varsayılan: false).
        ConsentRequestParameters params = new ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build();
        consent.requestConsentInfoUpdate(activity, params,
            new ConsentInformation.OnConsentInfoUpdateSuccessListener() {
                @Override
                public void onConsentInfoUpdateSuccess() {
                    if (destroyed || activity.isFinishing()) return;
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                        new ConsentForm.OnConsentFormDismissedListener() {
                            @Override
                            public void onConsentFormDismissed(FormError error) {
                                if (error != null) Log.w(TAG, "İzin penceresi: " + error.getMessage());
                                consentDone(done);
                            }
                        });
                }
            },
            new ConsentInformation.OnConsentInfoUpdateFailureListener() {
                @Override
                public void onConsentInfoUpdateFailure(FormError error) {
                    Log.w(TAG, "İzin bilgisi alınamadı: " + error.getMessage());
                    consentDone(done);
                }
            });
        // Önceki açılışta verilen izinle hemen başlanabilir.
        if (consent.canRequestAds()) startSdk();
    }

    private void consentDone(Runnable done) {
        if (destroyed) return;
        privacyRequired = consent.getPrivacyOptionsRequirementStatus()
            == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        if (consent.canRequestAds()) startSdk();
        done.run();
    }

    private void startSdk() {
        if (destroyed) return;
        if (sdkStarted.getAndSet(true)) {
            load();
            return;
        }
        // Genel kitle: çocuklara yönelik değil, rıza yaşının altındaki kullanıcı etiketi yok.
        MobileAds.setRequestConfiguration(new RequestConfiguration.Builder()
            .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE)
            .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
            .build());
        final Activity a = activity;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Google'ın önerisi: reklam SDK'sı arka planda başlatılır.
                MobileAds.initialize(a, status -> { });
                main.post(new Runnable() {
                    @Override
                    public void run() { load(); }
                });
            }
        }).start();
    }

    private void load() {
        if (destroyed || ad != null || loading || !consent.canRequestAds()) return;
        loading = true;
        RewardedAd.load(activity, unitId, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(RewardedAd loaded) {
                loading = false;
                if (destroyed) return;
                ad = loaded;
                adLoadedAt = SystemClock.elapsedRealtime();
                if (waiting) showNow();
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                loading = false;
                Log.w(TAG, "Reklam yüklenemedi: " + error.getMessage());
                if (waiting) {
                    setWaiting(false);
                    listener.onAdResult("failed");
                }
            }
        });
    }

    /** Oyun "reklam izle, devam et" için reklam istedi. Hazırsa hemen gösterilir, değilse yüklenir (en çok LOAD_TIMEOUT_MS). */
    void show() {
        if (destroyed || showing || waiting) return;
        if (!consent.canRequestAds()) {
            listener.onAdResult("failed");
            return;
        }
        if (ad != null && SystemClock.elapsedRealtime() - adLoadedAt > AD_MAX_AGE_MS) ad = null;
        if (ad != null) {
            showNow();
            return;
        }
        setWaiting(true);
        if (sdkStarted.get()) load();
        else startSdk();
    }

    private void setWaiting(boolean w) {
        waiting = w;
        if (w) main.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
        else main.removeCallbacks(loadTimeout);
        listener.onAdLoading(w);
    }

    private void showNow() {
        if (waiting) setWaiting(false);
        RewardedAd current = ad;
        ad = null;
        if (current == null || destroyed || activity.isFinishing()) {
            listener.onAdResult("failed");
            return;
        }
        showing = true;
        earned = false;
        current.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                showing = false;
                // Ödül bildirimi kapanışla neredeyse aynı anda gelir; kısa bir bekleyişle ikisi birlikte değerlendirilir.
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!destroyed) listener.onAdResult(earned ? "rewarded" : "dismissed");
                    }
                }, 300);
                load();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) {
                showing = false;
                Log.w(TAG, "Reklam gösterilemedi: " + error.getMessage());
                listener.onAdResult("failed");
                load();
            }
        });
        current.show(activity, new OnUserEarnedRewardListener() {
            @Override
            public void onUserEarnedReward(RewardItem reward) {
                earned = true;
            }
        });
    }

    /** İzin seçeneklerinin oyun içinden yeniden açılabilmesi gerekiyor mu (AB ve Birleşik Krallık'ta zorunlu). */
    boolean privacyOptionsRequired() {
        return privacyRequired;
    }

    void showPrivacyOptions() {
        if (destroyed || activity.isFinishing()) return;
        UserMessagingPlatform.showPrivacyOptionsForm(activity, new ConsentForm.OnConsentFormDismissedListener() {
            @Override
            public void onConsentFormDismissed(FormError error) {
                if (error != null) Log.w(TAG, "Gizlilik seçenekleri: " + error.getMessage());
                if (destroyed) return;
                // İzin değişmiş olabilir: eski izinle yüklenen reklam atılır.
                if (!showing) ad = null;
                privacyRequired = consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
                if (consent.canRequestAds()) startSdk();
            }
        });
    }

    void destroy() {
        destroyed = true;
        waiting = false;
        ad = null;
        main.removeCallbacksAndMessages(null);
    }
}
