package pl.studenthub.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PreparationReminder extends BroadcastReceiver {
    static final String CHANNEL = "studenthub-preparation";
    private static final int REQUEST = 712;
    private static final int NOTIFICATION = 302;

    static final class Agenda {
        final String semester;
        final LocalDate date;
        final ZonedDateTime at;
        final List<String> lessons;

        Agenda(String semester, LocalDate date, ZonedDateTime at, List<String> lessons) {
            this.semester = semester;
            this.date = date;
            this.at = at;
            this.lessons = lessons;
        }
    }

    static Agenda nextAgenda(JSONObject organizer, ZonedDateTime now, String lastDate) throws JSONException {
        JSONObject settings = organizer.optJSONObject("scheduleSettings");
        if (settings != null && "0".equals(settings.optString("prepEnabled"))) return null;
        LocalTime reminderTime = LocalTime.parse(settings == null ? "19:00" : settings.optString("prepTime", "19:00"));
        String active = organizer.optString("active", "3");
        Map<LocalDate, List<JSONObject>> dates = new TreeMap<>();
        JSONArray classes = organizer.optJSONArray("zajecia");
        if (classes == null) return null;
        for (int i = 0; i < classes.length(); i++) {
            JSONObject item = classes.getJSONObject(i);
            if (!active.equals(item.optString("semester", "3"))) continue;
            LocalDate date = LocalDate.parse(item.getString("date"));
            if (!date.isAfter(now.toLocalDate()) || date.toString().equals(lastDate)) continue;
            dates.computeIfAbsent(date, unused -> new ArrayList<>()).add(item);
        }
        Map<String, String> locations = new HashMap<>();
        JSONArray trips = organizer.optJSONArray("zjazdy");
        if (trips != null) for (int i = 0; i < trips.length(); i++) {
            JSONObject trip = trips.getJSONObject(i);
            locations.put(trip.optString("id"), trip.optString("room"));
        }
        for (Map.Entry<LocalDate, List<JSONObject>> entry : dates.entrySet()) {
            LocalDate date = entry.getKey();
            ZonedDateTime at = date.minusDays(1).atTime(reminderTime).atZone(now.getZone());
            if (now.isAfter(at.plusHours(2))) continue;
            List<JSONObject> items = entry.getValue();
            items.sort(Comparator.comparing(item -> item.optString("time")));
            List<String> lessons = new ArrayList<>();
            for (JSONObject item : items) {
                String room = item.optString("room");
                String place = room.isEmpty() ? locations.getOrDefault(item.optString("trip"), "") : "Sala " + room;
                String end = item.optString("endTime");
                String hours = item.optString("time") + (end.isEmpty() ? "" : "–" + end);
                String line = hours + " · " + item.optString("title");
                if (!place.isEmpty()) line += " · " + place;
                lessons.add(line);
            }
            return new Agenda(active, date, at, lessons);
        }
        return null;
    }

    private static Agenda currentAgenda(Context context, ZonedDateTime now) throws JSONException {
        String raw = context.getSharedPreferences("organizer", Context.MODE_PRIVATE).getString("records", "{}");
        JSONObject organizer = new JSONObject(raw);
        String active = organizer.optString("active", "3");
        return nextAgenda(organizer, now, delivered(context).getString(active, ""));
    }

    private static SharedPreferences delivered(Context context) {
        return context.getSharedPreferences("preparation_delivered", Context.MODE_PRIVATE);
    }

    static synchronized void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST,
                new Intent(context, PreparationReminder.class), PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(pending);
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            Agenda agenda = currentAgenda(context, now);
            if (agenda == null) return;
            long at = Math.max(agenda.at.toInstant().toEpochMilli(), now.toInstant().toEpochMilli() + 1000);
            if (Reminders.canScheduleExactly(context)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
                    return;
                } catch (SecurityException ignored) { }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (JSONException | java.time.DateTimeException ignored) { }
    }

    @Override public void onReceive(Context context, Intent intent) {
        synchronized (PreparationReminder.class) {
            try {
                ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
                Agenda agenda = currentAgenda(context, now);
                if (agenda != null && !now.isBefore(agenda.at) && agenda.date.equals(now.toLocalDate().plusDays(1))) {
                    Reminders.registerChannels(context);
                    NotificationManager manager = context.getSystemService(NotificationManager.class);
                    if (!manager.areNotificationsEnabled()
                            || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
                        // Avoid an immediate retry loop while delivery is disabled.
                        return;
                    }
                    post(context, manager, agenda);
                    delivered(context).edit().putString(agenda.semester, agenda.date.toString()).apply();
                }
            } catch (JSONException | java.time.DateTimeException | SecurityException ignored) { return; }
            schedule(context);
        }
    }

    private static void post(Context context, NotificationManager manager, Agenda agenda) {
        StringBuilder body = new StringBuilder("Spakuj zeszyty, długopis i materiały na zajęcia.");
        for (String lesson : agenda.lessons) body.append("\n").append(lesson);
        Intent plan = new Intent(context, MainActivity.class).putExtra("agendaDate", agenda.date.toString())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, REQUEST, plan, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification reminder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_studenthub_notification)
                .setColor(0xff6841dc).setContentTitle("Przygotuj się na jutro")
                .setContentText("Zajęcia jutro: " + agenda.lessons.size() + " · Otwórz pełny plan")
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true).build();
        manager.notify(NOTIFICATION, reminder);
    }
}
