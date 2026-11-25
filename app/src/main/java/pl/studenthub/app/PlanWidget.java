package pl.studenthub.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class PlanWidget extends AppWidgetProvider {
    private static final String REFRESH = "pl.studenthub.app.WIDGET_REFRESH";
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern(
            "d MMM", Locale.forLanguageTag("pl-PL"));

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        refresh(context);
    }

    @Override public void onDisabled(Context context) {
        context.getSystemService(AlarmManager.class).cancel(refreshIntent(context));
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (REFRESH.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) refresh(context);
    }

    static synchronized void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PlanWidget.class));
        if (ids.length == 0) return;
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        String raw = context.getSharedPreferences("organizer", Context.MODE_PRIVATE).getString("records", "{}");
        Snapshot snapshot;
        try { snapshot = snapshot(new JSONObject(raw), now, zone); }
        catch (JSONException e) { snapshot = new Snapshot(); }
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();

        PendingIntent launch = PendingIntent.getActivity(context, 710,
                new Intent(context, MainActivity.class).putExtra("openPlan", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.plan_widget);
        bind(view, R.id.widget_current_title, R.id.widget_current_meta, snapshot.current,
                "Brak trwających lekcji", today, false);
        bind(view, R.id.widget_next_title, R.id.widget_next_meta, snapshot.next,
                "Brak zaplanowanych lekcji", today, true);
        long remaining = snapshot.next == null ? 0 : snapshot.next.start - now;
        boolean countdown = remaining > 0 && remaining <= 3600000;
        view.setViewVisibility(R.id.widget_countdown, countdown ? View.VISIBLE : View.GONE);
        view.setChronometerCountDown(R.id.widget_countdown, true);
        view.setChronometer(R.id.widget_countdown, android.os.SystemClock.elapsedRealtime() + remaining,
                "Za %s", countdown);
        view.setOnClickPendingIntent(R.id.widget_root, launch);
        manager.updateAppWidget(ids, view);

        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent refresh = refreshIntent(context);
        alarms.cancel(refresh);
        long midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long boundary = snapshot.next == null ? snapshot.boundary : Math.min(snapshot.boundary, midnight);
        if (remaining > 3600000) boundary = Math.min(boundary, snapshot.next.start - 3600000);
        if (boundary == Long.MAX_VALUE) return;
        if (Reminders.canScheduleExactly(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, boundary, refresh);
                return;
            } catch (SecurityException ignored) { }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, boundary, refresh);
    }

    private static PendingIntent refreshIntent(Context context) {
        return PendingIntent.getBroadcast(context, 711, new Intent(context, PlanWidget.class).setAction(REFRESH),
                PendingIntent.FLAG_IMMUTABLE);
    }

    static final class Slot {
        final String title, time, endTime, place;
        final LocalDate date;
        final long start, end;

        Slot(JSONObject item, Map<String, String> places, ZoneId zone, int duration) {
            title = item.optString("title");
            date = LocalDate.parse(item.optString("date"));
            time = item.optString("time");
            endTime = item.optString("endTime");
            String room = item.optString("room");
            place = room.isEmpty() ? places.getOrDefault(item.optString("trip"), "") : "Sala " + room;
            start = epoch(date, time, zone);
            end = endTime.isEmpty() ? start + duration * 60000L : epoch(date, endTime, zone);
        }

        private static long epoch(LocalDate date, String time, ZoneId zone) {
            return LocalDateTime.parse(date + "T" + time).atZone(zone).toInstant().toEpochMilli();
        }
    }

    static final class Snapshot {
        Slot current, next;
        long boundary = Long.MAX_VALUE;
    }

    static Snapshot snapshot(JSONObject records, long now, ZoneId zone) throws JSONException {
        Snapshot snapshot = new Snapshot();
        String active = records.optString("active", "3");
        JSONObject settings = records.optJSONObject("scheduleSettings");
        int duration = settings == null ? 45 : settings.optInt("duration", 45);
        Map<String, String> places = new HashMap<>();
        JSONArray trips = records.optJSONArray("zjazdy");
        if (trips != null) for (int i = 0; i < trips.length(); i++) {
            JSONObject trip = trips.getJSONObject(i);
            places.put(trip.optString("id"), trip.optString("room"));
        }
        JSONArray classes = records.optJSONArray("zajecia");
        if (classes == null) return snapshot;
        for (int i = 0; i < classes.length(); i++) {
            JSONObject item = classes.getJSONObject(i);
            if (!active.equals(item.optString("semester", "3"))) continue;
            Slot slot;
            try { slot = new Slot(item, places, zone, duration); }
            catch (java.time.DateTimeException invalid) { continue; }
            if (slot.start > now) {
                if (snapshot.next == null || slot.start < snapshot.next.start) snapshot.next = slot;
                snapshot.boundary = Math.min(snapshot.boundary, slot.start);
            } else if (slot.end > now) {
                if (snapshot.current == null || slot.start < snapshot.current.start) snapshot.current = slot;
                snapshot.boundary = Math.min(snapshot.boundary, slot.end);
            }
        }
        return snapshot;
    }

    private static void bind(RemoteViews view, int titleId, int metaId, Slot slot, String empty,
                             LocalDate today, boolean includeDate) {
        view.setTextViewText(titleId, slot == null ? empty : slot.title);
        view.setViewVisibility(metaId, slot == null ? View.GONE : View.VISIBLE);
        if (slot == null) return;

        String prefix = "";
        if (includeDate && !slot.date.equals(today)) {
            prefix = (slot.date.equals(today.plusDays(1)) ? "Jutro" : SHORT_DATE.format(slot.date)) + " · ";
        }
        String hours = slot.time + (slot.endTime.isEmpty() ? "" : "–" + slot.endTime);
        view.setTextViewText(metaId, prefix + hours + " · " + (slot.place.isEmpty() ? "Brak sali" : slot.place));
    }
}
