package pl.studenthub.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class MailUpdates extends JobService {
    private static final int JOB = 201, NOTIFICATION = 301;
    private static final String KEY = "studenthub-mail-v1";
    private volatile Thread worker;

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("mail_private", MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return settings(context).contains("account");
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY) && create) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }

    static void enable(Context context, Mailbox mailbox) throws Exception {
        Mailbox.Checkpoint baseline = mailbox.checkpoint(-1, 0);
        synchronized (MailUpdates.class) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(mailbox.credentials().getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            if (!settings(context).edit().putString("account", blob)
                    .putLong("validity", baseline.validity).putLong("uid", baseline.uid).commit())
                throw new IllegalStateException();
            if (!ensureScheduled(context)) {
                disable(context);
                throw new IllegalStateException();
            }
        }
    }

    static synchronized boolean ensureScheduled(Context context) {
        if (!enabled(context)) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB) != null) return true;
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(context, MailUpdates.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Reminders.registerChannels(context);
        return manager.areNotificationsEnabled()
                && manager.getNotificationChannel(Reminders.MAIL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static synchronized void disable(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB);
        settings(context).edit().clear().commit();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(KEY);
        } catch (Exception ignored) { }
    }

    static synchronized Mailbox restore(Context context) throws Exception {
        String data = settings(context).getString("account", null);
        return data == null ? null : decrypt(data);
    }

    private static Mailbox decrypt(String data) throws Exception {
        String[] parts = data.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        JSONObject account = new JSONObject(new String(cipher.doFinal(
                Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
        return new Mailbox(account.getString("login"), account.getString("password"));
    }

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean retry = false;
            try { poll(this); }
            catch (Exception e) { retry = !(e instanceof javax.mail.AuthenticationFailedException); }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        }, "studenthub-mail");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }

    static void poll(Context context) throws Exception {
        if (!notificationsAllowed(context)) return;
        SharedPreferences preferences = settings(context);
        String data;
        Mailbox mailbox;
        long validity, uid;
        synchronized (MailUpdates.class) {
            data = preferences.getString("account", null);
            if (data == null) return;
            mailbox = decrypt(data);
            validity = preferences.getLong("validity", -1);
            uid = preferences.getLong("uid", 0);
        }
        Mailbox.Checkpoint latest = mailbox.checkpoint(validity, uid);
        synchronized (MailUpdates.class) {
            if (Thread.currentThread().isInterrupted() || !data.equals(preferences.getString("account", null))) return;
            if (preferences.getLong("validity", -1) != validity || preferences.getLong("uid", 0) != uid
                    || !notificationsAllowed(context)) return;
            if (latest.unread > 0) notifyMail(context, latest.unread);
            preferences.edit().putLong("validity", latest.validity).putLong("uid", latest.uid).commit();
        }
    }

    private static void notifyMail(Context context, int count) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Intent openMail = new Intent(context, MainActivity.class).putExtra("openMail", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION, openMail, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, Reminders.MAIL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Nowa poczta uczelniana")
                .setContentText(count == 1 ? "Masz nową wiadomość." : "Nowe wiadomości: " + count)
                .setCategory(Notification.CATEGORY_EMAIL).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(tap).build();
        manager.notify(NOTIFICATION, notification);
    }
}
