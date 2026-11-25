package pl.studenthub.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZoneId;

public final class LiveClassNotification extends BroadcastReceiver {
    static final String CHANNEL = "studenthub-live-class";
    private static final int CURRENT = 304, NEXT = 305;
    private static final long ADVANCE = 30 * 60000L;
    @Override public void onReceive(Context context, Intent intent) {
        refresh(context);
    }

    static synchronized void refresh(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent tick = PendingIntent.getBroadcast(context, CURRENT,
                new Intent(context, LiveClassNotification.class), PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(tick);

        String raw = context.getSharedPreferences("organizer", Context.MODE_PRIVATE).getString("records", "{}");
        try {
            long now = System.currentTimeMillis();
            Reminders.clearStartedAlerts(manager, now);
            JSONObject organizer = new JSONObject(raw);
            if (!Reminders.classAlertsEnabled(organizer) || !manager.areNotificationsEnabled()) {
                cancel(manager);
                return;
            }
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                cancel(manager);
                return;
            }
            ZoneId zone = ZoneId.systemDefault();
            PlanWidget.Snapshot snapshot = PlanWidget.snapshot(organizer, now, zone);
            LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            if (snapshot.current == null) manager.cancel(CURRENT);
            else post(context, manager, CURRENT, "Teraz", snapshot.current, snapshot.current.end, now);
            if (!showNext(snapshot, now, today)) manager.cancel(NEXT);
            else {
                long expires = snapshot.next.start;
                if (snapshot.current != null && snapshot.next.start - snapshot.current.end > ADVANCE)
                    expires = snapshot.current.end;
                post(context, manager, NEXT, "Następne", snapshot.next, expires, now);
            }

            long boundary = nextRefresh(snapshot, now, zone);
            if (boundary == Long.MAX_VALUE) return;
            if (Reminders.canScheduleExactly(context)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, boundary, tick);
                    return;
                } catch (SecurityException ignored) { }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, boundary, tick);
        } catch (JSONException | java.time.DateTimeException | SecurityException ignored) {
            cancel(manager);
        }
    }

    private static void cancel(NotificationManager manager) {
        manager.cancel(CURRENT);
        manager.cancel(NEXT);
    }

    static boolean showNext(PlanWidget.Snapshot snapshot, long now, LocalDate today) {
        return snapshot.next != null && snapshot.next.date.equals(today)
                && (snapshot.current != null || snapshot.next.start - now <= ADVANCE);
    }

    static long nextRefresh(PlanWidget.Snapshot snapshot, long now, ZoneId zone) {
        if (snapshot.next == null) return snapshot.boundary;
        long midnight = snapshot.next.date.atStartOfDay(zone).toInstant().toEpochMilli();
        long opensAt = Math.max(midnight, snapshot.next.start - ADVANCE);
        if (opensAt <= now) return snapshot.boundary;
        return Math.min(snapshot.boundary, opensAt);
    }

    private static void post(Context context, NotificationManager manager, int id, String title,
                             PlanWidget.Slot slot, long expires, long now) {
        String hours = slot.time + (slot.endTime.isEmpty() ? "" : "–" + slot.endTime);
        String line = hours + " · " + slot.title + (slot.place.isEmpty() ? "" : " · " + slot.place);
        Intent destination = new Intent(context, MainActivity.class).putExtra("openPlan", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, id, destination, PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_studenthub_notification)
                .setColor(0xff6841dc).setContentTitle(title)
                .setContentText(line).setStyle(new Notification.BigTextStyle().bigText(line))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setTimeoutAfter(Math.max(1000, expires - now))
                .setCategory(Notification.CATEGORY_STATUS).setContentIntent(open).build();
        manager.notify(id, notice);
    }
}
