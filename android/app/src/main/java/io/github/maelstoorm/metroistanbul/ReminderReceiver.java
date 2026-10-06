package io.github.maelstoorm.metroistanbul;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;

/**
 * Oyuncu bir süre oynamazsa telefonda yerel olarak oluşturulan hatırlatma bildirimi.
 * İnternet kullanılmaz. Oyundaki "Hatırlatma bildirimi" ayarı (AndroidTTS.setReminders) bunu açıp kapatır.
 * Aralar giderek uzar (1, 2, 4, 7, 14 gün); oyuncu oyuna dönünce sayaç sıfırlanır.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String PREFS = "mi";
    private static final String CHANNEL = "hatirlatma";
    private static final int[] GAP_DAYS = { 1, 2, 4, 7, 14 };
    private static final String[][] MESSAGES = {
        { "Taksim'de yolcular birikiyor", "Hatların seni bekliyor, istasyon taşmadan yetiş!" },
        { "Metrobüs kalkmak üzere", "Bugün kaç yolcu taşıyabilirsin? Rekorunu geç." },
        { "Yeni hafta başladı", "Depoda yeni bir araç seni bekliyor." },
        { "Bölümler seni bekliyor", "Bir sonraki hattın kilidini açıp 3 yıldızı topla." },
        { "Boğaz'ı geçme vakti", "Marmaray ile Avrupa'yı Asya'ya bağla." },
    };

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("on", on).apply();
        if (!on) cancel(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean("on", true)) return;
        int sent = p.getInt("sent", 0);
        if (sent >= GAP_DAYS.length) return;
        String[] m = MESSAGES[sent % MESSAGES.length];
        show(ctx, m[0], m[1]);
        p.edit().putInt("sent", sent + 1).apply();
        schedule(ctx);
    }

    /** Oyundan çıkınca (ya da bir bildirim gösterilince) bir sonraki hatırlatmayı kurar. */
    static void schedule(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            if (!p.getBoolean("on", true)) return;
            int sent = p.getInt("sent", 0);
            if (sent >= GAP_DAYS.length) return;
            Calendar c = Calendar.getInstance();
            long now = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, GAP_DAYS[sent]);
            c.set(Calendar.HOUR_OF_DAY, 18);
            c.set(Calendar.MINUTE, 30);
            c.set(Calendar.SECOND, 0);
            while (c.getTimeInMillis() < now + 3L * 60 * 60 * 1000) c.add(Calendar.DAY_OF_YEAR, 1);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            // Tam zamanlı alarm izni gerekmez; sistem birkaç dakika kaydırabilir.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pending(ctx));
        } catch (Exception e) {
            // hatırlatma kurulamazsa oyun etkilenmesin
        }
    }

    /** Oyuncu oyuna döndü: bekleyen hatırlatma iptal, sayaç sıfır. */
    static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(ctx));
            prefs(ctx).edit().putInt("sent", 0).apply();
        } catch (Exception e) {
            // yok say
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        return PendingIntent.getBroadcast(ctx, 23, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private static void show(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hatırlatmalar", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 23, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(0xFFE2007A).setAutoCancel(true).setContentIntent(pi);
            nm.notify(1, b.build());
        } catch (Exception e) {
            // bildirim gösterilemezse (ör. izin verilmemiş) oyun etkilenmesin
        }
    }
}
