package pl.studenthub.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;

public final class Reminders extends BroadcastReceiver {
    private static final String CLASSES = "studenthub-classes";
    private static final String TRIPS = "studenthub-trips";
    static final String MAIL = "studenthub-mail";
    private static final long CLASS_ADVANCE_MILLIS = 30 * 60000L;
    private static final String ALERT = "pl.studenthub.app.ALERT";

    private static Intent intent(Context context, String id) {
        return new Intent(context, Reminders.class).setAction(ALERT)
                .setData(new Uri.Builder().scheme("studenthub").authority("reminder").appendPath(id).build());
    }

    static void registerChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel live = new NotificationChannel(LiveClassNotification.CHANNEL,
                "Teraz i następne zajęcia", NotificationManager.IMPORTANCE_LOW);
        live.setDescription("Stały podgląd lekcji i sali podczas zajęć.");
        manager.createNotificationChannels(java.util.Arrays.asList(live,
                channel(CLASSES, "Zajęcia", "Przypomnienia o zajęciach poza zjazdami."),
                channel(TRIPS, "Zjazdy", "Przypomnienia o lekcjach zaplanowanych na zjazdach."),
                channel(MAIL, "Poczta uczelniana", "Alerty o nowych wiadomościach studenckich."),
                channel(PreparationReminder.CHANNEL, "Przygotowanie na jutro", "Wieczorny plan zajęć i przypomnienie o spakowaniu materiałów.")));
    }

    private static NotificationChannel channel(String id, String name, String description) {
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(description);
        return channel;
    }

    static boolean canScheduleExactly(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static long start(JSONObject entry) throws JSONException {
        return LocalDateTime.parse(entry.getString("date") + "T" + entry.getString("time"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static void clearStartedAlerts(NotificationManager manager, long now) {
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            Notification notification = active.getNotification();
            String channel = notification.getChannelId();
            if (active.getId() == 0 && (CLASSES.equals(channel) || TRIPS.equals(channel))
                    && notification.when <= now) {
                manager.cancel(active.getTag(), active.getId());
            }
        }
    }

    private static String token(String id, long at) {
        return "delivered:" + id + ":" + at;
    }

    static boolean classAlertsEnabled(JSONObject organizer) {
        JSONObject settings = organizer.optJSONObject("scheduleSettings");
        return settings == null || !"0".equals(settings.optString("classAlerts"));
    }

    static synchronized void reschedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        registerChannels(context);
        boolean exact = canScheduleExactly(context);
        SharedPreferences tracked = context.getSharedPreferences("scheduled", Context.MODE_PRIVATE);
        for (String id : tracked.getStringSet("ids", java.util.Collections.emptySet())) {
            PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent(context, id),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pending != null) { alarms.cancel(pending); pending.cancel(); }
        }
        HashSet<String> ids = new HashSet<>();
        HashSet<String> keepDelivered = new HashSet<>();
        String raw = context.getSharedPreferences("organizer", Context.MODE_PRIVATE).getString("records", "");
        if (!raw.isEmpty()) {
            try {
                JSONObject organizer = new JSONObject(raw);
                JSONArray classes = organizer.getJSONArray("zajecia");
                boolean enabled = classAlertsEnabled(organizer);
                String active = organizer.optString("active", "3");
                long now = System.currentTimeMillis();
                for (int i = 0; i < classes.length(); i++) {
                    JSONObject entry = classes.getJSONObject(i);
                    long start = start(entry);
                    if (start <= now) continue;
                    long at = start - CLASS_ADVANCE_MILLIS;
                    String id = entry.getString("id");
                    String delivery = token(id, at);
                    keepDelivered.add(delivery);
                    if (!enabled || tracked.getBoolean(delivery, false) || !active.equals(entry.optString("semester", "3"))) continue;
                    PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent(context, id),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    long scheduled = Math.max(at, now + 1000);
                    if (exact) {
                        try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduled, pending); }
                        catch (SecurityException revoked) {
                            exact = false;
                            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduled, pending);
                        }
                    } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduled, pending);
                    ids.add(id);
                }
            } catch (JSONException | java.time.DateTimeException ignored) { }
        }
        SharedPreferences.Editor changes = tracked.edit().putStringSet("ids", ids);
        for (String key : tracked.getAll().keySet()) {
            if (key.startsWith("delivered:") && !keepDelivered.contains(key)) changes.remove(key);
        }
        changes.apply();
        PreparationReminder.schedule(context);
        LiveClassNotification.refresh(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ALERT.equals(intent.getAction())) {
            reschedule(context);
            return;
        }
        if (intent.getData() == null) return;
        String id = intent.getData().getLastPathSegment();
        String raw = context.getSharedPreferences("organizer", Context.MODE_PRIVATE).getString("records", "");
        try {
            JSONObject organizer = new JSONObject(raw);
            if (!classAlertsEnabled(organizer)) return;
            JSONArray classes = organizer.getJSONArray("zajecia");
            String active = organizer.optString("active", "3");
            for (int i = 0; i < classes.length(); i++) {
                JSONObject entry = classes.getJSONObject(i);
                if (!entry.getString("id").equals(id) || !active.equals(entry.optString("semester", "3"))) continue;
                long start = start(entry);
                long now = System.currentTimeMillis();
                long at = start - CLASS_ADVANCE_MILLIS;
                if (start <= now || now < at) return;
                SharedPreferences tracked = context.getSharedPreferences("scheduled", Context.MODE_PRIVATE);
                String delivery = token(id, at);
                if (tracked.getBoolean(delivery, false)) return;

                String tripId = entry.optString("trip");
                boolean onTrip = !tripId.isEmpty();
                String channel = onTrip ? TRIPS : CLASSES;
                registerChannels(context);
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (!manager.areNotificationsEnabled()
                        || manager.getNotificationChannel(channel).getImportance() == NotificationManager.IMPORTANCE_NONE) return;
                String tripTitle = "";
                String room = entry.getString("room");
                if (onTrip) {
                    JSONArray trips = organizer.getJSONArray("zjazdy");
                    for (int j = 0; j < trips.length(); j++) {
                        JSONObject trip = trips.getJSONObject(j);
                        if (!tripId.equals(trip.getString("id"))) continue;
                        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern(
                                "d MMMM", java.util.Locale.forLanguageTag("pl-PL"));
                        String first = trip.getString("date");
                        String last = trip.getString("endDate");
                        tripTitle = "Zjazd · " + java.time.LocalDate.parse(first).format(format);
                        if (!last.isEmpty() && !last.equals(first))
                            tripTitle += " – " + java.time.LocalDate.parse(last).format(format);
                        if (room.isEmpty()) room = trip.optString("room");
                        break;
                    }
                }
                String time = entry.getString("time");
                String end = entry.getString("endTime");
                String roomLine = room.isEmpty() ? "Miejsce nieustalone" : room;
                String teacher = entry.optString("teacher");
                if (teacher.isEmpty() && organizer.has("subjects")) {
                    JSONArray subjects = organizer.getJSONArray("subjects");
                    for (int j = 0; j < subjects.length(); j++) {
                        JSONObject subject = subjects.getJSONObject(j);
                        if (entry.optString("subjectId").equals(subject.getString("id"))) {
                            teacher = subject.optString("teacher");
                            break;
                        }
                    }
                }
                String summary = time + " · " + roomLine;
                String details = entry.getString("type") + " · " + time + (end.isEmpty() ? "" : "–" + end)
                        + "\nMiejsce: " + roomLine
                        + "\nProwadzący: " + (teacher.isEmpty() ? "nie wpisano" : teacher);
                if (!teacher.isEmpty()) summary += " · " + teacher;
                Intent destination = new Intent(context, MainActivity.class)
                        .putExtra(onTrip ? "openTrips" : "openClasses", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent open = PendingIntent.getActivity(context, onTrip ? 102 : 103, destination,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification notification = new Notification.Builder(context, channel)
                        .setSmallIcon(pl.studenthub.app.R.drawable.ic_studenthub_notification)
                        .setColor(0xff6841dc)
                        .setSubText(tripTitle.isEmpty() ? "Plan zajęć" : tripTitle)
                        .setContentTitle(entry.getString("title")).setContentText(summary)
                        .setStyle(new Notification.BigTextStyle().bigText(details))
                        .setCategory(Notification.CATEGORY_REMINDER).setWhen(start).setShowWhen(false)
                        .setTimeoutAfter(Math.max(1, start - now))
                        .setContentIntent(open).setAutoCancel(true).build();
                manager.notify(id, 0, notification);
                tracked.edit().putBoolean(delivery, true).apply();
                return;
            }
        } catch (JSONException | java.time.DateTimeException | SecurityException ignored) { }
    }
}
