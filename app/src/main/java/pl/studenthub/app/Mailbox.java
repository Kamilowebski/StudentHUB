package pl.studenthub.app;

import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import javax.mail.Address;
import javax.mail.Folder;
import javax.mail.Flags;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

final class Mailbox {
    private static final String HOST = "student.tu.kielce.pl";
    private static final int MAX_TEXT = 50000;
    private final String login;
    private final String password;
    private final Session session;

    Mailbox(String login, String password) {
        this.login = login;
        this.password = password;
        Properties properties = new Properties();
        for (String protocol : new String[]{"imaps", "smtps"}) {
            String prefix = "mail." + protocol + ".";
            properties.setProperty(prefix + "ssl.enable", "true");
            properties.setProperty(prefix + "ssl.checkserveridentity", "true");
            properties.setProperty(prefix + "connectiontimeout", "10000");
            properties.setProperty(prefix + "timeout", "15000");
            properties.setProperty(prefix + "writetimeout", "15000");
        }
        properties.setProperty("mail.imaps.peek", "true");
        properties.setProperty("mail.smtps.auth", "true");
        session = Session.getInstance(properties);
    }

    String credentials() throws org.json.JSONException {
        return new JSONObject().put("login", login).put("password", password).toString();
    }

    JSONArray inbox() throws Exception {
        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, 993, login, password);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            try {
                JSONArray inbox = new JSONArray();
                int count = folder.getMessageCount();
                UIDFolder uids = (UIDFolder) folder;
                if (count == 0) return inbox;
                Message[] messages = folder.getMessages(Math.max(1, count - 49), count);
                javax.mail.FetchProfile profile = new javax.mail.FetchProfile();
                profile.add(javax.mail.FetchProfile.Item.ENVELOPE);
                profile.add(javax.mail.FetchProfile.Item.FLAGS);
                profile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, profile);
                for (int i = messages.length - 1; i >= 0; i--) {
                    inbox.put(summary(messages[i], uids.getUIDValidity(), uids.getUID(messages[i])));
                }
                return inbox;
            } finally { folder.close(false); }
        }
    }

    JSONObject read(String key) throws Exception {
        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, 993, login, password);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            try {
                UIDFolder uids = (UIDFolder) folder;
                long validity = uids.getUIDValidity();
                long uid = parseUid(key, validity);
                Message message = uids.getMessageByUID(uid);
                if (message == null) throw new IllegalStateException();
                String body = body(message, 0);
                message.setFlag(Flags.Flag.SEEN, true);
                JSONArray files = new JSONArray();
                for (Part part : attachments(message)) files.put(fileName(part));
                return summary(message, validity, uid).put("attachments", files).put("body",
                        body.isEmpty() ? "Ta wiadomość nie zawiera tekstu do wyświetlenia." : body);
            } finally { folder.close(false); }
        }
    }

    private static java.util.List<Part> attachments(Part root) throws Exception {
        java.util.List<Part> found = new java.util.ArrayList<>();
        collectAttachments(root, found, 0);
        return found;
    }

    private static void collectAttachments(Part part, java.util.List<Part> found, int depth) throws Exception {
        if (depth > 12 || found.size() >= 50) return;
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            found.add(part);
        } else if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart) {
                Multipart multipart = (Multipart) content;
                for (int i = 0; i < multipart.getCount() && found.size() < 50; i++)
                    collectAttachments(multipart.getBodyPart(i), found, depth + 1);
            }
        }
    }

    static String safeFileName(String value) {
        if (value == null) return "załącznik";
        String clean = value.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "załącznik";
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    private static String fileName(Part part) throws Exception {
        String name = part.getFileName();
        if (name == null) return "załącznik";
        try { name = MimeUtility.decodeText(name); }
        catch (java.io.UnsupportedEncodingException ignored) { }
        return safeFileName(name);
    }

    void download(String key, int index, java.io.OutputStream out) throws Exception {
        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, 993, login, password);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uids = (UIDFolder) folder;
                Message message = uids.getMessageByUID(parseUid(key, uids.getUIDValidity()));
                if (message == null) throw new IllegalArgumentException();
                Part part = attachments(message).get(index);
                try (java.io.InputStream data = part.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    int total = 0;
                    while ((count = data.read(buffer)) != -1) {
                        total += count;
                        if (total > 25 * 1024 * 1024) throw new java.io.IOException("attachment exceeds limit");
                        out.write(buffer, 0, count);
                    }
                }
            } finally { folder.close(false); }
        }
    }

    private static long parseUid(String key, long validity) {
        String[] parts = key.split(":", -1);
        if (parts.length != 2 || Long.parseLong(parts[0]) != validity) throw new IllegalArgumentException();
        return Long.parseLong(parts[1]);
    }

    void mark(JSONArray keys, boolean read) throws Exception {
        if (keys.length() == 0 || keys.length() > 50) throw new IllegalArgumentException();
        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, 993, login, password);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            try {
                UIDFolder uids = (UIDFolder) folder;
                long validity = uids.getUIDValidity();
                long[] ids = new long[keys.length()];
                for (int i = 0; i < ids.length; i++) ids[i] = parseUid(keys.getString(i), validity);
                Message[] messages = java.util.Arrays.stream(uids.getMessagesByUID(ids))
                        .filter(java.util.Objects::nonNull).toArray(Message[]::new);
                if (messages.length > 0) folder.setFlags(messages, new Flags(Flags.Flag.SEEN), read);
            } finally { folder.close(false); }
        }
    }

    static final class Checkpoint {
        final long validity, uid;
        final int unread;
        Checkpoint(long validity, long uid, int unread) {
            this.validity = validity;
            this.uid = uid;
            this.unread = unread;
        }
    }

    Checkpoint checkpoint(long previousValidity, long previousUid) throws Exception {
        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, 993, login, password);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uids = (UIDFolder) folder;
                long validity = uids.getUIDValidity();
                int count = folder.getMessageCount();
                long newest = count == 0 ? 0 : uids.getUID(folder.getMessage(count));
                int unread = 0;
                if (validity == previousValidity && newest > previousUid) {
                    Message[] arrived = uids.getMessagesByUID(previousUid + 1, newest);
                    javax.mail.FetchProfile profile = new javax.mail.FetchProfile();
                    profile.add(javax.mail.FetchProfile.Item.FLAGS);
                    folder.fetch(arrived, profile);
                    for (Message message : arrived) if (!message.isSet(Flags.Flag.SEEN)) unread++;
                }
                if (validity == previousValidity) newest = Math.max(newest, previousUid);
                return new Checkpoint(validity, newest, unread);
            } finally { folder.close(false); }
        }
    }

    void send(String recipient, String subject, String body, String attachmentName, byte[] attachment) throws Exception {
        if (recipient == null || subject == null || body == null || body.trim().isEmpty()
                || recipient.length() > 254 || subject.length() > 240 || body.length() > 30000
                || subject.contains("\n") || subject.contains("\r")) throw new IllegalArgumentException();
        InternetAddress address = new InternetAddress(recipient, true);
        address.validate();
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(login + "@" + HOST));
        message.setRecipient(Message.RecipientType.TO, address);
        message.setSubject(subject, "UTF-8");
        if (attachment == null) {
            message.setText(body, "UTF-8");
        } else {
            javax.mail.internet.MimeBodyPart text = new javax.mail.internet.MimeBodyPart();
            text.setText(body, "UTF-8");
            javax.mail.internet.MimeBodyPart file = new javax.mail.internet.MimeBodyPart();
            file.setDataHandler(new javax.activation.DataHandler(
                    new javax.mail.util.ByteArrayDataSource(attachment, "application/octet-stream")));
            file.setFileName(MimeUtility.encodeText(attachmentName, "UTF-8", null));
            file.setDisposition(Part.ATTACHMENT);
            javax.mail.internet.MimeMultipart parts = new javax.mail.internet.MimeMultipart();
            parts.addBodyPart(text);
            parts.addBodyPart(file);
            message.setContent(parts);
        }
        message.setSentDate(new Date());
        message.saveChanges();
        try (Transport transport = session.getTransport("smtps")) {
            transport.connect(HOST, 465, login, password);
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    private static JSONObject summary(Message message, long validity, long uid) throws Exception {
        Address[] senders = message.getFrom();
        String from = senders == null || senders.length == 0 ? "(Nieznany nadawca)" : senders[0].toString();
        if (senders != null && senders.length > 0 && senders[0] instanceof InternetAddress) {
            InternetAddress sender = (InternetAddress) senders[0];
            if (sender.getPersonal() != null) from = sender.getPersonal() + " <" + sender.getAddress() + ">";
        }
        Date date = message.getSentDate();
        return new JSONObject().put("uid", validity + ":" + uid)
                .put("subject", message.getSubject() == null ? "" : message.getSubject())
                .put("from", from)
                .put("read", message.isSet(Flags.Flag.SEEN))
                .put("date", date == null ? "" : DateFormat.getDateInstance(DateFormat.MEDIUM,
                        Locale.forLanguageTag("pl-PL")).format(date));
    }

    private static String body(Part part, int depth) throws Exception {
        if (depth > 10 || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null)
            return "";
        if (part.isMimeType("text/plain")) return text(part);
        if (part.isMimeType("text/html")) return Html.fromHtml(text(part), Html.FROM_HTML_MODE_COMPACT).toString();
        if (!part.isMimeType("multipart/*")) return "";
        Object content = part.getContent();
        if (!(content instanceof Multipart)) return "";
        Multipart multi = (Multipart) content;
        if (part.isMimeType("multipart/alternative")) {
            String fallback = "";
            for (int i = 0; i < multi.getCount(); i++) {
                Part child = multi.getBodyPart(i);
                String value = body(child, depth + 1);
                if (!value.isEmpty() && child.isMimeType("text/plain")) return value;
                if (!value.isEmpty()) fallback = value;
            }
            return fallback;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < multi.getCount() && result.length() < MAX_TEXT; i++) {
            String value = body(multi.getBodyPart(i), depth + 1);
            if (value.isEmpty()) continue;
            if (result.length() > 0) result.append("\n\n");
            result.append(value, 0, Math.min(value.length(), Math.max(0, MAX_TEXT - result.length())));
        }
        return result.toString();
    }

    private static String text(Part part) throws Exception {
        Charset charset = StandardCharsets.UTF_8;
        try {
            String specified = new ContentType(part.getContentType()).getParameter("charset");
            if (specified != null) charset = Charset.forName(MimeUtility.javaCharset(specified));
        } catch (IllegalArgumentException | javax.mail.internet.ParseException ignored) { }
        StringBuilder result = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(part.getInputStream(), charset)) {
            char[] chunk = new char[4096];
            int count;
            while (result.length() < MAX_TEXT && (count = reader.read(chunk, 0,
                    Math.min(chunk.length, MAX_TEXT - result.length()))) > 0) {
                result.append(chunk, 0, count);
            }
            if (result.length() == MAX_TEXT) result.append("\n\n[Wiadomość skrócona]");
        }
        return result.toString();
    }
}
