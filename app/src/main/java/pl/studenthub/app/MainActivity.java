package pl.studenthub.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.backup.BackupManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PAGE = "file:///android_asset/index.html";
    private static final int EXPORT = 101, IMPORT = 102, NOTIFICATIONS = 103, IMPORT_CALENDAR = 104;
    private static final int PICK_ATTACHMENT = 105, SAVE_ATTACHMENT = 106;
    private static final int MAX_OUTGOING_ATTACHMENT = 8 * 1024 * 1024;
    private volatile Uri composeFile;
    private String attachmentMessage;
    private int attachmentIndex;
    private String calendarSemester;
    private static final int MAX_BYTES = 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences organizer;
    private WebView web;
    private Mailbox mailbox;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        organizer = getSharedPreferences("organizer", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xfff7f7fc);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.equals(PAGE) && !url.startsWith(PAGE + "#");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        final int navigationColor = 0xfff2edff;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff7f7fc);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (Build.VERSION.SDK_INT >= 35) {
            android.view.View navigationInset = new android.view.View(this);
            navigationInset.setBackgroundColor(navigationColor);
            root.addView(navigationInset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            getWindow().setNavigationBarContrastEnforced(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.view.WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) controller.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                view.setPadding(bars.left, bars.top, bars.right, keyboard);
                int footerHeight = keyboard > 0 ? 0 : bars.bottom;
                ViewGroup.LayoutParams footer = navigationInset.getLayoutParams();
                if (footer.height != footerHeight) {
                    footer.height = footerHeight;
                    navigationInset.setLayoutParams(footer);
                }
                return insets;
            });
        } else {
            getWindow().setNavigationBarColor(navigationColor);
            android.view.View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        setContentView(root);
        web.loadUrl(PAGE + section(getIntent()));
        Reminders.reschedule(this);
    }

    @Override protected void onResume() {
        super.onResume();
        PlanWidget.refresh(this);
        PreparationReminder.schedule(this);
        LiveClassNotification.refresh(this);
        if (web != null) web.evaluateJavascript("window.refreshMailSettings && refreshMailSettings()", null);
        checkMailAlerts();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATIONS) {
            LiveClassNotification.refresh(this);
            if (web != null) web.evaluateJavascript("window.refreshMailSettings && refreshMailSettings()", null);
            checkMailAlerts();
        }
    }

    private void checkMailAlerts() {
        if (!MailUpdates.enabled(this)) return;
        io.execute(() -> {
            try {
                MailUpdates.ensureScheduled(this);
                MailUpdates.poll(this);
            } catch (Exception ignored) { }
        });
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("Boolean(window.studenthubBack && window.studenthubBack())", handled -> {
            if (isFinishing() || isDestroyed() || "true".equals(handled)) return;
            if (web.canGoBack()) web.goBack();
            else super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        web.removeJavascriptInterface("Android");
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }

    private void toast(String message) {
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void mail(String event, Object payload) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            String js = payload instanceof String ? JSONObject.quote((String) payload)
                    : payload == null ? "null" : payload.toString();
            web.evaluateJavascript("window.nativeMail && nativeMail(" + JSONObject.quote(event) + ","
                    + js.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")", null);
        });
    }

    private void startPicker(int code) {
        boolean exporting = code == EXPORT;
        Intent picker = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(exporting ? "application/json" : "*/*");
        if (exporting) picker.putExtra(Intent.EXTRA_TITLE, "studenthub-kopia.json");
        try { startActivityForResult(picker, code); }
        catch (android.content.ActivityNotFoundException e) { toast("Brak systemowego wyboru plików."); }
    }

    private boolean store(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return false;
        try { validate(raw); }
        catch (JSONException e) { return false; }
        if (!organizer.edit().putString("records", raw).commit()) return false;
        new BackupManager(this).dataChanged();
        Reminders.reschedule(this);
        PlanWidget.refresh(this);
        return true;
    }

    private static void validate(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        String[] categories = {"zajecia", "zjazdy", "zaliczenia", "platnosci"};
        boolean profiles = root.has("semesters");
        if (root.length() != categories.length + (profiles ? 3 : 0) + (root.has("subjects") ? 1 : 0) + (root.has("scheduleSettings") ? 1 : 0))
            throw new JSONException("Nieprawidłowe kategorie");
        HashSet<String> semesters = new HashSet<>();
        if (profiles) {
            JSONArray list = root.getJSONArray("semesters");
            for (int i = 0; i < list.length(); i++) {
                Object value = list.get(i);
                if (!(value instanceof String) || !((String)value).matches("[1-9]|[12][0-9]|30")
                        || !semesters.add((String)value)) throw new JSONException("Nieprawidłowy semestr");
            }
            if (!semesters.contains(text(root, "active", false))) throw new JSONException("Brak aktywnego semestru");
            text(root, "author", true);
        } else semesters.add("3");
        if (root.has("scheduleSettings")) {
            JSONObject settings = root.getJSONObject("scheduleSettings");
            text(settings, "school", true);
            validateMinutes(settings, "duration", 1);
            validateMinutes(settings, "breakMinutes", 0);
            if (settings.has("classAlerts") && !text(settings, "classAlerts", false).matches("[01]"))
                throw new JSONException("Nieprawidłowe ustawienie powiadomień o lekcjach");
            if (settings.has("prepEnabled") && !text(settings, "prepEnabled", false).matches("[01]"))
                throw new JSONException("Nieprawidłowe ustawienie przypomnienia");
            if (settings.has("prepTime") && !text(settings, "prepTime", false).matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
                throw new JSONException("Nieprawidłowa godzina przypomnienia");
        }
        JSONObject subjects = new JSONObject();
        if (root.has("subjects")) {
            JSONArray catalog = root.getJSONArray("subjects");
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i);
                String id = text(item, "id", false);
                if (subjects.has(id) || !semesters.contains(text(item, "semester", false)))
                    throw new JSONException("Nieprawidłowy przedmiot");
                text(item, "title", false);
                text(item, "type", false);
                if (item.has("teacher")) text(item, "teacher", true);
                subjects.put(id, item);
            }
        }
        JSONObject trips = new JSONObject();
        JSONArray tripRecords = root.getJSONArray("zjazdy");
        for (int i = 0; i < tripRecords.length(); i++) {
            JSONObject trip = tripRecords.getJSONObject(i);
            trips.put(text(trip, "id", false), trip);
        }
        for (String category : categories) {
            JSONArray items = root.getJSONArray(category);
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = text(item, "id", false);
                if (!"zjazdy".equals(category)) text(item, "title", false);
                else if (item.has("title")) text(item, "title", true);
                String semester = item.has("semester") ? text(item, "semester", false) : "3";
                if (!semesters.contains(semester)) throw new JSONException("Nieznany semestr");
                if (!ids.add(id)) throw new JSONException("Powtórzony wpis");
                date(item, "date");
                switch (category) {
                    case "zajecia":
                        time(item, "time");
                        if (item.has("subjectId")) {
                            JSONObject subject = subjects.getJSONObject(text(item, "subjectId", false));
                            if (!semester.equals(subject.getString("semester"))
                                    || !item.getString("title").equals(subject.getString("title"))
                                    || !item.getString("type").equals(subject.getString("type")))
                                throw new JSONException("Lekcja nie pasuje do przedmiotu");
                        }
                        if (item.has("trip") && !text(item, "trip", true).isEmpty()) {
                            JSONObject trip = trips.getJSONObject(item.getString("trip"));
                            String start = text(trip, "date", false);
                            String end = text(trip, "endDate", true);
                            String date = item.getString("date");
                            if (!semester.equals(trip.optString("semester", "3")) || date.compareTo(start) < 0
                                    || date.compareTo(end.isEmpty() ? start : end) > 0)
                                throw new JSONException("Zajęcia poza zjazdem");
                        }
                        if (!text(item, "endTime", true).isEmpty()) {
                            time(item, "endTime");
                            if (item.getString("endTime").compareTo(item.getString("time")) <= 0)
                                throw new JSONException("Nieprawidłowy czas zakończenia");
                        }
                        text(item, "room", true);
                        text(item, "type", false);
                        if (item.has("teacher")) text(item, "teacher", true);
                        if (item.has("lead") && !text(item, "lead", false).matches("0|5|10|15|30|60|1440"))
                            throw new JSONException("Nieprawidłowy alert");
                        break;
                    case "zjazdy":
                        if (!text(item, "endDate", true).isEmpty()) {
                            date(item, "endDate");
                            if (item.getString("endDate").compareTo(item.getString("date")) < 0)
                                throw new JSONException("Nieprawidłowa data zakończenia");
                        }
                        text(item, "room", true);
                        break;
                    case "zaliczenia":
                        text(item, "status", false);
                        text(item, "grade", true);
                        break;
                    case "platnosci":
                        text(item, "status", false);
                        if (!text(item, "amount", false).matches("[0-9]+(\\.[0-9]{1,2})?"))
                            throw new JSONException("Nieprawidłowa kwota");
                        break;
                }
            }
        }
    }

    private static void validateMinutes(JSONObject settings, String key, int min) throws JSONException {
        String value = text(settings, key, false);
        if (!value.matches("[0-9]{1,4}")) throw new JSONException("Nieprawidłowa liczba minut");
        int minutes = Integer.parseInt(value);
        if (minutes < min || minutes > 1439) throw new JSONException("Nieprawidłowa liczba minut");
    }

    private static String text(JSONObject item, String field, boolean empty) throws JSONException {
        Object value = item.get(field);
        if (!(value instanceof String)) throw new JSONException("Nieprawidłowe pole");
        String s = (String) value;
        if ((!empty && s.trim().isEmpty()) || s.length() > 120)
            throw new JSONException("Nieprawidłowe pole");
        return s;
    }

    private static void date(JSONObject item, String field) throws JSONException {
        String s = text(item, field, false);
        if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new JSONException("Nieprawidłowa data");
        try { java.time.LocalDate.parse(s); }
        catch (java.time.DateTimeException e) { throw new JSONException("Nieprawidłowa data"); }
    }

    private static void time(JSONObject item, String field) throws JSONException {
        if (!text(item, field, false).matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new JSONException("Nieprawidłowa godzina");
    }

    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (code == EXPORT) {
            String snapshot = organizer.getString("records", "{\"zajecia\":[],\"zjazdy\":[],\"zaliczenia\":[],\"platnosci\":[]}");
            io.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException();
                    out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    toast("Zapisano kopię.");
                } catch (Exception e) { toast("Nie udało się zapisać kopii."); }
            });
        } else if (code == PICK_ATTACHMENT) {
            composeFile = uri;
            mail("attachment", documentName(uri));
        } else if (code == SAVE_ATTACHMENT) {
            String message = attachmentMessage;
            int index = attachmentIndex;
            io.execute(() -> saveAttachment(uri, message, index));
        } else if (code == IMPORT_CALENDAR) {
            String semester = calendarSemester;
            io.execute(() -> importCalendar(uri, semester));
        } else if (code == IMPORT) {
            new AlertDialog.Builder(this).setTitle("Przywrócić kopię?")
                    .setMessage("Dane z pliku zastąpią obecne plany, zaliczenia i płatności.")
                    .setNegativeButton("Anuluj", null).setPositiveButton("Przywróć", (dialog, which) ->
                        io.execute(() -> restore(uri))).show();
        }
    }

    private String documentName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return Mailbox.safeFileName(cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null);
        } catch (RuntimeException e) { return "załącznik"; }
    }

    private byte[] outgoingAttachment(Uri uri) throws java.io.IOException {
        if (uri == null) return null;
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) != -1) {
                if (output.size() + count > MAX_OUTGOING_ATTACHMENT)
                    throw new java.io.IOException("attachment exceeds limit");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void saveAttachment(Uri destination, String message, int index) {
        java.io.File temporary = null;
        try {
            if (mailbox == null || message == null || index < 0 || index >= 50) throw new java.io.IOException();
            temporary = java.io.File.createTempFile("mail-", ".part", getCacheDir());
            try (OutputStream out = new java.io.FileOutputStream(temporary)) {
                mailbox.download(message, index, out);
            }
            try (InputStream in = new java.io.FileInputStream(temporary);
                 OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new java.io.IOException();
                byte[] bytes = new byte[8192];
                int size;
                while ((size = in.read(bytes)) != -1) out.write(bytes, 0, size);
            }
            toast("Zapisano załącznik.");
        } catch (Exception e) { toast("Nie udało się zapisać załącznika."); }
        finally { if (temporary != null) temporary.delete(); }
    }

    private String readDocument(Uri uri) throws java.io.IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return readText(in);
        }
    }

    private static String readText(InputStream in) throws java.io.IOException {
        if (in == null) throw new java.io.IOException();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int size;
        while ((size = in.read(bytes)) != -1) {
            if (buffer.size() + size > MAX_BYTES) throw new java.io.IOException();
            buffer.write(bytes, 0, size);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void importCalendar(Uri uri, String semester) {
        try { deliverCalendar(readDocument(uri), semester); }
        catch (Exception e) { toast("Nie udało się odczytać pliku iCalendar (.ics)."); }
    }

    private void importCalendarLink(String semester) {
        EditText input = new EditText(this);
        input.setHint("Wklej link do planu USOS");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Importuj plan z USOS")
                .setMessage("Wklej adres iCalendar z eksportu planu. StudentHUB użyje go jednorazowo.")
                .setView(input).setNegativeButton("Anuluj", null).setPositiveButton("Importuj", null).create();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            java.net.URI address;
            try {
                address = java.net.URI.create(input.getText().toString().trim());
                if (!"https".equalsIgnoreCase(address.getScheme())
                        || !"api.usos.tu.kielce.pl".equalsIgnoreCase(address.getHost())
                        || !"/services/tt/upcoming_ical".equals(address.getRawPath())
                        || address.getRawUserInfo() != null || address.getRawFragment() != null
                        || (address.getPort() != -1 && address.getPort() != 443)
                        || address.getRawQuery() == null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                input.setError("Wklej link iCalendar z USOS Politechniki Świętokrzyskiej.");
                return;
            }
            input.setText("");
            dialog.dismiss();
            io.execute(() -> fetchCalendar(address, semester));
        }));
        dialog.show();
    }

    private void fetchCalendar(java.net.URI address, String semester) {
        javax.net.ssl.HttpsURLConnection connection = null;
        try {
            connection = (javax.net.ssl.HttpsURLConnection) address.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "text/calendar, */*;q=0.1");
            if (connection.getResponseCode() != 200) throw new java.io.IOException();
            try (InputStream in = connection.getInputStream()) {
                deliverCalendar(readText(in), semester);
            }
        } catch (Exception e) {
            toast("Nie udało się pobrać planu. Sprawdź, czy link z USOS nadal działa.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deliverCalendar(String raw, String semester) throws JSONException {
        CalendarImport calendar = CalendarImport.parse(raw);
        JSONArray events = new JSONArray();
        for (CalendarImport.Event entry : calendar.events) {
            events.put(new JSONObject().put("title", entry.title).put("date", entry.date)
                    .put("time", entry.time).put("endTime", entry.endTime).put("room", entry.room));
        }
        String payload = new JSONObject().put("events", events).put("skipped", calendar.skipped)
                .put("semester", semester == null ? "" : semester).toString()
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        runOnUiThread(() -> {
            if (!isDestroyed())
                web.evaluateJavascript("window.importSchedule && importSchedule(" + payload + ")", null);
        });
    }

    private void restore(Uri uri) {
        try {
            if (!store(readDocument(uri))) throw new JSONException("Kopia");
            runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript("location.reload()", null); });
            toast("Przywrócono kopię.");
        } catch (Exception e) { toast("Nie udało się przywrócić kopii. Sprawdź plik StudentHUB."); }
    }

    private void loginDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding / 2, padding, 0);
        EditText user = new EditText(this);
        user.setHint("Login, np. s012345");
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = new EditText(this);
        password.setHint("Hasło uczelniane");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) password.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        fields.addView(user);
        fields.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Połącz konto studenckie")
                .setMessage("Dane logowania służą do połączenia z serwerem PŚk w tej sesji.")
                .setView(fields).setNegativeButton("Anuluj", null).setPositiveButton("Połącz", null).create();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String login = user.getText().toString().trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceFirst("@student\\.tu\\.kielce\\.pl$", "");
            if (!login.matches("s[0-9]{6}")) { user.setError("Wpisz s i 6 cyfr numeru albumu"); return; }
            if (password.length() == 0) { password.setError("Wpisz hasło"); return; }
            Mailbox candidate = new Mailbox(login, password.getText().toString());
            password.setText("");
            dialog.dismiss();
            mail("loading", null);
            io.execute(() -> {
                try {
                    JSONArray messages = candidate.inbox();
                    if (MailUpdates.enabled(this)) {
                        MailUpdates.disable(this);
                        mail("alerts", false);
                    }
                    mailbox = candidate;
                    mail("inbox", messages);
                } catch (Exception e) { mailError(e); }
            });
        }));
        dialog.show();
    }

    private void mailError(Exception problem) {
        if (problem instanceof javax.mail.AuthenticationFailedException) {
            mail("error", "Logowanie odrzucone. Sprawdź login i hasło uczelniane.");
        } else if (problem instanceof javax.mail.internet.AddressException) {
            mail("error", "Sprawdź adres odbiorcy.");
        } else {
            mail("error", "Nie udało się wykonać operacji pocztowej. Sprawdź połączenie i spróbuj ponownie.");
        }
    }

    private static String section(Intent intent) {
        if (intent.getBooleanExtra("openMail", false)) return "#poczta";
        if (intent.getBooleanExtra("openTrips", false)) return "#zjazdy";
        String agenda = intent.getStringExtra("agendaDate");
        if (agenda != null && agenda.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try { return "#dzien/" + java.time.LocalDate.parse(agenda); }
            catch (java.time.DateTimeException ignored) { }
        }
        if (intent.getBooleanExtra("openPlan", false)) return "#plan";
        if (intent.getBooleanExtra("openClasses", false)) return "#zajecia";
        return "";
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String route = section(intent);
        if (!route.isEmpty()) web.evaluateJavascript("location.hash=" + JSONObject.quote(route), null);
    }

    private void enableMailAlerts() {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Powiadomienia o poczcie")
                .setMessage("StudentHUB będzie okresowo sprawdzać skrzynkę. Login i hasło zostaną zaszyfrowane kluczem tego telefonu i pominięte w kopiach Google.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Włącz", (dialog, which) -> io.execute(() -> {
                    if (mailbox == null) { mail("logout", null); return; }
                    try {
                        MailUpdates.enable(this, mailbox);
                        mail("alerts", true);
                        toast("Włączono sprawdzanie poczty w tle.");
                        runOnUiThread(() -> {
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED)
                                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                        });
                    } catch (Exception e) { mail("error", "Nie udało się włączyć powiadomień o poczcie."); }
                })).show());
    }

    private final class Bridge {
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (PackageManager.NameNotFoundException e) { return ""; }
        }
        @JavascriptInterface public String getOrganizer() { return organizer.getString("records", ""); }
        @JavascriptInterface public boolean saveOrganizer(String raw) { return store(raw); }
        @JavascriptInterface public void exportBackup() { runOnUiThread(() -> startPicker(EXPORT)); }
        @JavascriptInterface public void importCalendarUrl(String semester) {
            runOnUiThread(() -> importCalendarLink(semester));
        }

        @JavascriptInterface public void importCalendarFile(String semester) {
            runOnUiThread(() -> {
                calendarSemester = semester;
                startPicker(IMPORT_CALENDAR);
            });
        }
        @JavascriptInterface public void importBackup() { runOnUiThread(() -> startPicker(IMPORT)); }

        @JavascriptInterface public void backupSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_PRIVACY_SETTINGS)); }
                catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });
        }

        @JavascriptInterface public boolean canScheduleExactly() {
            return Reminders.canScheduleExactly(MainActivity.this);
        }

        @JavascriptInterface public void exactAlarms() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 31 && !Reminders.canScheduleExactly(MainActivity.this)) {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName())));
                    } catch (android.content.ActivityNotFoundException e) {
                        toast("To urządzenie nie udostępnia ustawienia dokładnych alarmów.");
                    }
                }
            });
        }

        @JavascriptInterface public void notifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                } else {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
            });
        }

        @JavascriptInterface public void login() { runOnUiThread(MainActivity.this::loginDialog); }
        @JavascriptInterface public void logout() {
            io.execute(() -> {
                MailUpdates.disable(MainActivity.this);
                mailbox = null;
                mail("logout", null);
            });
        }
        @JavascriptInterface public boolean mailNotificationsAllowed() {
            return MailUpdates.notificationsAllowed(MainActivity.this);
        }

        @JavascriptInterface public void mailNotificationSettings() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } else {
                    startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Reminders.MAIL));
                }
            });
        }

        @JavascriptInterface public boolean mailAlertsEnabled() { return MailUpdates.enabled(MainActivity.this); }
        @JavascriptInterface public void mailAlerts(boolean enabled) {
            if (enabled) enableMailAlerts();
            else io.execute(() -> {
                MailUpdates.disable(MainActivity.this);
                mail("alerts", false);
                toast("Wyłączono sprawdzanie poczty w tle.");
            });
        }
        @JavascriptInterface public void resumeMail() {
            io.execute(() -> {
                try {
                    if (mailbox == null) mailbox = MailUpdates.restore(MainActivity.this);
                    if (mailbox == null) return;
                    mail("alerts", MailUpdates.enabled(MainActivity.this));
                    mail("loading", null);
                    mail("inbox", mailbox.inbox());
                } catch (Exception e) { mailError(e); }
            });
        }
        @JavascriptInterface public void markMail(String keys, boolean read) {
            io.execute(() -> {
                if (mailbox == null) { mail("logout", null); return; }
                try {
                    mailbox.mark(new JSONArray(keys), read);
                    mail("marked", new JSONObject().put("keys", new JSONArray(keys)).put("read", read));
                } catch (Exception e) { mailError(e); }
            });
        }
        @JavascriptInterface public void refresh() {
            mail("loading", null);
            io.execute(() -> {
                if (mailbox == null) { mail("logout", null); return; }
                try { mail("inbox", mailbox.inbox()); }
                catch (Exception e) { mailError(e); }
            });
        }
        @JavascriptInterface public void read(String uid) {
            io.execute(() -> {
                if (mailbox == null) { mail("logout", null); return; }
                try { mail("message", mailbox.read(uid)); }
                catch (Exception e) { mailError(e); }
            });
        }
        @JavascriptInterface public void pickAttachment() { runOnUiThread(() -> startPicker(PICK_ATTACHMENT)); }
        @JavascriptInterface public void clearAttachment() { composeFile = null; }
        @JavascriptInterface public void saveMailAttachment(String message, int index, String filename) {
            runOnUiThread(() -> {
                attachmentMessage = message;
                attachmentIndex = index;
                Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, Mailbox.safeFileName(filename));
                try { startActivityForResult(picker, SAVE_ATTACHMENT); }
                catch (android.content.ActivityNotFoundException e) { toast("Brak systemowego wyboru plików."); }
            });
        }

        @JavascriptInterface public void send(String recipient, String subject, String body) {
            io.execute(() -> {
                if (mailbox == null) { mail("logout", null); return; }
                Uri file = composeFile;
                byte[] content;
                try { content = outgoingAttachment(file); }
                catch (Exception e) { mail("error", "Nie udało się dołączyć pliku. Limit załącznika wynosi 8 MB."); return; }
                try {
                    mailbox.send(recipient, subject, body, file == null ? null : documentName(file), content);
                    composeFile = null;
                    mail("sent", null);
                }
                catch (Exception e) { mailError(e); }
            });
        }
    }
}
