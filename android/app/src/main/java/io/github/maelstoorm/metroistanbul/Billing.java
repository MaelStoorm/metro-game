package io.github.maelstoorm.metroistanbul;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Google Play uygulama içi satın alma (Play Billing Library 8).
 * Ürünler: jeton_ ve sezon_ ile başlayanlar tüketilir (jeton_300, jeton_1000, sezon_karti); diğerleri bir kez alınır (destekci, baslangic, cift_jeton, gorunum_paketi).
 * Sonuçlar oyuna window.__billing.products / purchased / owned ile döner.
 * Tüm iş ana iş parçacığında yapılır.
 */
final class Billing implements PurchasesUpdatedListener {
    /** Oyuna JavaScript gönderir; done, sayfanın döndürdüğü değerle ("true"/"false"/null) çağrılır (null olabilir). */
    interface Js {
        void eval(String script, Done done);
    }

    interface Done {
        void onResult(boolean ok);
    }

    /** Bağlantı kurulunca (code == OK) ya da kurulamayınca (başka kod) çalışan iş. */
    private interface Op {
        void run(int code);
    }

    private static final String TAG = "MetroOdeme";
    private static final String PREFS = "billing";
    private static final String KEY_DELIVERED = "delivered";
    private static final long MAX_RETRY_MS = 60000;

    private final Activity activity;
    private final Js js;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BillingClient client;
    private final List<Op> waiting = new ArrayList<>();
    /** Şu an oyuna verilmekte ya da tüketilmekte olan satın alma jetonları (iki kez verilmesin). */
    private final Set<String> busy = new HashSet<>();
    private volatile boolean ready;
    private boolean connecting, destroyed, restoreWaiting;
    private long retryMs = 1000;
    /** Son başlatılan satın alma; hata/iptal sonucu hangi ürüne ait bilinsin. */
    private String buyingId;

    Billing(Activity activity, Js js) {
        this.activity = activity;
        this.js = js;
        this.client = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build();
        connect();
    }

    static boolean isConsumable(String id) {
        return id != null && (id.startsWith("jeton_") || id.startsWith("sezon_"));
    }

    boolean isReady() {
        return ready;
    }

    // ---------- bağlantı ----------

    private void connect() {
        if (destroyed || connecting || ready) return;
        connecting = true;
        try {
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(final BillingResult result) {
                    main.post(new Runnable() {
                        @Override
                        public void run() { setupFinished(result.getResponseCode()); }
                    });
                }

                @Override
                public void onBillingServiceDisconnected() {
                    main.post(new Runnable() {
                        @Override
                        public void run() { disconnected(); }
                    });
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Bağlantı başlatılamadı", e);
            connecting = false;
            setupFinished(BillingClient.BillingResponseCode.ERROR);
        }
    }

    private void setupFinished(int code) {
        connecting = false;
        if (destroyed) return;
        boolean ok = code == BillingClient.BillingResponseCode.OK;
        ready = ok;
        if (ok) retryMs = 1000;
        else Log.w(TAG, "Google Play ödeme hizmeti kullanılamıyor: " + code);
        boolean hadRestore = restoreWaiting;
        restoreWaiting = false;
        List<Op> ops = new ArrayList<>(waiting);
        waiting.clear();
        for (Op op : ops) op.run(code);
        // Bağlanınca bir kez: yarım kalmış satın almalar verilir, sahip olunanlar bildirilir.
        if (ok && !hadRestore) restore();
    }

    private void disconnected() {
        ready = false;
        connecting = false;
        if (destroyed) return;
        final long delay = retryMs;
        retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
        main.postDelayed(new Runnable() {
            @Override
            public void run() { connect(); }
        }, delay);
    }

    /** İş, bağlantı hazırsa hemen, değilse bağlantı kurulunca çalışır. */
    private void whenReady(Op op) {
        if (destroyed) return;
        if (ready && client.isReady()) {
            op.run(BillingClient.BillingResponseCode.OK);
            return;
        }
        ready = false;
        waiting.add(op);
        connect();
    }

    // ---------- ürünler ----------

    /** idsJson: ["jeton_300", ...]. Sonuç: __billing.products([{id,title,price,micros,currency}]) */
    void products(String idsJson) {
        final List<String> ids = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(idsJson == null ? "[]" : idsJson);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception e) {
            // geçersiz liste: boş sonuç
        }
        if (ids.isEmpty()) {
            sendProducts(new JSONArray());
            return;
        }
        whenReady(new Op() {
            @Override
            public void run(int code) {
                if (code != BillingClient.BillingResponseCode.OK) {
                    sendProducts(new JSONArray());
                    return;
                }
                queryDetails(ids, new DetailsDone() {
                    @Override
                    public void onDetails(int code, List<ProductDetails> list) {
                        JSONArray out = new JSONArray();
                        for (ProductDetails d : list) {
                            ProductDetails.OneTimePurchaseOfferDetails offer = d.getOneTimePurchaseOfferDetails();
                            if (offer == null) continue;
                            try {
                                JSONObject j = new JSONObject();
                                j.put("id", d.getProductId());
                                j.put("title", d.getName());
                                j.put("price", offer.getFormattedPrice());
                                j.put("micros", offer.getPriceAmountMicros());
                                j.put("currency", offer.getPriceCurrencyCode());
                                out.put(j);
                            } catch (Exception e) {
                                // bu ürün atlanır
                            }
                        }
                        sendProducts(out);
                    }
                });
            }
        });
    }

    private void sendProducts(JSONArray list) {
        call("products", list.toString(), null);
    }

    private interface DetailsDone {
        void onDetails(int code, List<ProductDetails> list);
    }

    private void queryDetails(List<String> ids, final DetailsDone done) {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String id : ids) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder().setProductList(products).build();
        client.queryProductDetailsAsync(params, (result, details) -> {
            final int code = result.getResponseCode();
            final List<ProductDetails> list = new ArrayList<>();
            if (code == BillingClient.BillingResponseCode.OK && details != null && details.getProductDetailsList() != null) {
                list.addAll(details.getProductDetailsList());
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) done.onDetails(code, list);
                }
            });
        });
    }

    // ---------- satın alma ----------

    void buy(final String id) {
        if (id == null || id.isEmpty()) return;
        whenReady(new Op() {
            @Override
            public void run(int code) {
                if (code != BillingClient.BillingResponseCode.OK) {
                    purchased(id, false, "error:" + code);
                    return;
                }
                queryDetails(Collections.singletonList(id), new DetailsDone() {
                    @Override
                    public void onDetails(int code, List<ProductDetails> list) {
                        ProductDetails details = null;
                        for (ProductDetails d : list) {
                            if (id.equals(d.getProductId())) details = d;
                        }
                        if (details == null) {
                            purchased(id, false, code == BillingClient.BillingResponseCode.OK ? "error:notfound" : "error:" + code);
                            return;
                        }
                        launch(id, details);
                    }
                });
            }
        });
    }

    private void launch(String id, ProductDetails details) {
        if (activity.isFinishing()) {
            purchased(id, false, "error:activity");
            return;
        }
        BillingFlowParams.ProductDetailsParams.Builder p = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details);
        ProductDetails.OneTimePurchaseOfferDetails offer = details.getOneTimePurchaseOfferDetails();
        if (offer != null && offer.getOfferToken() != null && !offer.getOfferToken().isEmpty()) {
            p.setOfferToken(offer.getOfferToken());
        }
        BillingFlowParams flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(Collections.singletonList(p.build()))
            .build();
        buyingId = id;
        int code;
        try {
            code = client.launchBillingFlow(activity, flow).getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "Satın alma ekranı açılamadı", e);
            code = BillingClient.BillingResponseCode.ERROR;
        }
        if (code != BillingClient.BillingResponseCode.OK) {
            buyingId = null;
            failed(id, code);
        }
    }

    private void failed(String id, int code) {
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            purchased(id, false, "cancel");
            return;
        }
        purchased(id, false, "error:" + code);
        // Zaten sahip olunan ürün: yarım kalan jeton verilir / destekçi durumu bildirilir.
        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore();
    }

    @Override
    public void onPurchasesUpdated(final BillingResult result, final List<Purchase> purchases) {
        final int code = result.getResponseCode();
        final List<Purchase> list = purchases == null ? new ArrayList<Purchase>() : new ArrayList<>(purchases);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                String id = buyingId;
                buyingId = null;
                if (code == BillingClient.BillingResponseCode.OK && !list.isEmpty()) {
                    for (Purchase p : list) handle(p, "", true);
                } else if (code != BillingClient.BillingResponseCode.OK) {
                    if (id != null) failed(id, code);
                } else if (id != null) {
                    // Sonuç var ama satın alma listesi boş: durum geri yüklemeyle netleşir.
                    purchased(id, false, "error:empty");
                    restore();
                }
            }
        });
    }

    /** Bir satın alma işlenir. fresh: kullanıcı az önce satın aldı (bekleyen ödeme bildirilsin). */
    private void handle(final Purchase p, String info, boolean fresh) {
        int state = p.getPurchaseState();
        for (String id : p.getProducts()) {
            if (state == Purchase.PurchaseState.PENDING) {
                if (fresh) purchased(id, false, "pending");
            } else if (state == Purchase.PurchaseState.PURCHASED) {
                if (isConsumable(id)) {
                    deliverConsumable(id, p, info);
                } else {
                    if (fresh) purchased(id, true, info);
                    acknowledge(p);
                }
            }
        }
    }

    /** Jeton: önce oyuna verilir, oyun aldığını doğrulayınca tüketilir (yeniden alınabilsin). */
    private void deliverConsumable(String id, final Purchase p, String info) {
        final String token = p.getPurchaseToken();
        if (busy.contains(token)) return;
        busy.add(token);
        if (delivered().contains(token)) {
            // Daha önce verilmiş, tüketme yarım kalmış: yalnızca tüketilir.
            consume(token);
            return;
        }
        call("purchased", JSONObject.quote(id) + ",true," + JSONObject.quote(info), new Done() {
            @Override
            public void onResult(boolean ok) {
                if (destroyed) return;
                if (!ok) {
                    // Sayfa hazır değil: tüketilmez, sonraki geri yüklemede yeniden verilir.
                    busy.remove(token);
                    return;
                }
                markDelivered(token, true);
                consume(token);
            }
        });
    }

    private void consume(final String token) {
        if (destroyed) return;
        ConsumeParams params = ConsumeParams.newBuilder().setPurchaseToken(token).build();
        client.consumeAsync(params, (result, purchaseToken) -> {
            final int code = result.getResponseCode();
            main.post(new Runnable() {
                @Override
                public void run() {
                    busy.remove(token);
                    if (code == BillingClient.BillingResponseCode.OK
                        || code == BillingClient.BillingResponseCode.ITEM_NOT_OWNED) {
                        markDelivered(token, false);
                    } else {
                        Log.w(TAG, "Tüketilemedi: " + code + " (sonraki açılışta yeniden denenir)");
                    }
                }
            });
        });
    }

    private void acknowledge(Purchase p) {
        if (p.isAcknowledged()) return;
        final String token = p.getPurchaseToken();
        if (busy.contains(token)) return;
        busy.add(token);
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build();
        client.acknowledgePurchase(params, result -> {
            final int code = result.getResponseCode();
            main.post(new Runnable() {
                @Override
                public void run() {
                    busy.remove(token);
                    if (code != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Onaylanamadı: " + code);
                }
            });
        });
    }

    // ---------- geri yükleme ----------

    /** Sahip olunan kalıcı ürünler __billing.owned([...]) ile bildirilir, yarım kalan jetonlar verilir. */
    void restore() {
        if (destroyed) return;
        if (!(ready && client.isReady())) {
            if (restoreWaiting) {
                connect();
                return;
            }
            restoreWaiting = true;
        }
        whenReady(new Op() {
            @Override
            public void run(int code) {
                if (code != BillingClient.BillingResponseCode.OK) return;
                QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build();
                client.queryPurchasesAsync(params, (result, purchases) -> {
                    final int rc = result.getResponseCode();
                    final List<Purchase> list = purchases == null ? new ArrayList<Purchase>() : new ArrayList<>(purchases);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || rc != BillingClient.BillingResponseCode.OK) return;
                            JSONArray owned = new JSONArray();
                            Set<String> seen = new HashSet<>();
                            for (Purchase p : list) {
                                if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                                for (String id : p.getProducts()) {
                                    if (!isConsumable(id) && seen.add(id)) owned.put(id);
                                }
                            }
                            call("owned", owned.toString(), null);
                            for (Purchase p : list) handle(p, "restore", false);
                        }
                    });
                });
            }
        });
    }

    // ---------- yardımcılar ----------

    private void purchased(String id, boolean ok, String info) {
        call("purchased", JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(info), null);
    }

    /** window.__billing.<name>(args) çağrılır; sayfada __billing tanımlıysa done(true). */
    private void call(String name, String args, final Done done) {
        String script = "(function(){var b=window.__billing;if(!b||typeof b." + name + "!=='function')return false;"
            + "try{b." + name + "(" + args + ")}catch(e){}return true})()";
        js.eval(script, done);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private Set<String> delivered() {
        return new HashSet<>(prefs().getStringSet(KEY_DELIVERED, Collections.<String>emptySet()));
    }

    /** Oyuna verilmiş ama henüz tüketilmemiş jetonlar kalıcı olarak tutulur (uygulama kapanırsa iki kez verilmesin). */
    private void markDelivered(String token, boolean add) {
        Set<String> s = delivered();
        if (add ? s.add(token) : s.remove(token)) prefs().edit().putStringSet(KEY_DELIVERED, s).commit();
    }

    void destroy() {
        destroyed = true;
        ready = false;
        waiting.clear();
        main.removeCallbacksAndMessages(null);
        try {
            client.endConnection();
        } catch (Exception e) {
            // yok say
        }
    }
}
