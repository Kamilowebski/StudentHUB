package pl.studenthub.app;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CalendarImport {
    private static final ZoneId CAMPUS = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final java.util.regex.Pattern ROOM_IN_DESCRIPTION = java.util.regex.Pattern.compile(
            "(?iu)(?:^|\\s)sala\\s*:\\s*(.+)");
    final List<Event> events = new ArrayList<>();
    int skipped;

    static final class Event {
        final String title, date, time, endTime, room;
        Event(String title, ZonedDateTime start, ZonedDateTime end, String room) {
            this.title = title;
            this.date = start.toLocalDate().toString();
            this.time = start.format(TIME);
            this.endTime = end == null ? "" : end.format(TIME);
            this.room = room;
        }
    }

    private static final class Property {
        final String head, value;
        Property(String head, String value) { this.head = head; this.value = value; }
    }

    static CalendarImport parse(String raw) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]", "");
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        if (!normalized.startsWith("BEGIN:VCALENDAR\n") || !normalized.trim().endsWith("END:VCALENDAR"))
            throw new IllegalArgumentException("Expected VCALENDAR");
        CalendarImport result = new CalendarImport();
        Map<String, Property> props = null;
        int nested = 0;
        for (String line : normalized.split("\n")) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                props = new HashMap<>();
                nested = 0;
            } else if (props != null && line.equalsIgnoreCase("END:VEVENT") && nested == 0) {
                result.add(props);
                props = null;
            } else if (props != null) {
                if (line.regionMatches(true, 0, "BEGIN:", 0, 6)) { nested++; continue; }
                if (line.regionMatches(true, 0, "END:", 0, 4)) { nested--; continue; }
                if (nested != 0) continue;
                boolean quoted = false;
                int colon = 0;
                for (; colon < line.length(); colon++) {
                    char c = line.charAt(colon);
                    if (c == '"') quoted = !quoted;
                    else if (c == ':' && !quoted) break;
                }
                if (colon == line.length()) continue;
                String head = line.substring(0, colon);
                String name = head.split(";", 2)[0].toUpperCase(Locale.ROOT);
                props.put(name, new Property(head, line.substring(colon + 1)));
            }
        }
        if (result.events.size() > 1000) throw new IllegalArgumentException("Too many events");
        return result;
    }

    private void add(Map<String, Property> props) {
        try {
            if (props.containsKey("RRULE") || props.containsKey("RDATE") || props.containsKey("EXDATE")
                    || props.containsKey("EXRULE")
                    || (props.containsKey("STATUS") && props.get("STATUS").value.equalsIgnoreCase("CANCELLED")))
                throw new IllegalArgumentException();
            ZonedDateTime start = timestamp(props.get("DTSTART"));
            ZonedDateTime end = props.containsKey("DTEND") ? timestamp(props.get("DTEND")) : null;
            if (end == null && props.containsKey("DURATION")) {
                end = start.plus(Duration.parse(props.get("DURATION").value));
            }
            if (end != null && (!start.toLocalDate().equals(end.toLocalDate())
                    || end.toLocalTime().withSecond(0).compareTo(start.toLocalTime().withSecond(0)) <= 0))
                throw new IllegalArgumentException();
            String title = text(props.get("SUMMARY"));
            if (title.isEmpty()) throw new IllegalArgumentException();
            String room = text(props.get("LOCATION"));
            Property description = props.get("DESCRIPTION");
            if (room.isEmpty() && description != null) {
                for (String line : description.value.split("\\\\[nN]")) {
                    java.util.regex.Matcher match = ROOM_IN_DESCRIPTION.matcher(line);
                    if (match.find()) {
                        room = text(new Property("LOCATION", match.group(1)));
                        break;
                    }
                }
            }
            events.add(new Event(title, start, end, room));
        } catch (RuntimeException e) {
            skipped++;
        }
    }

    private static ZonedDateTime timestamp(Property property) {
        if (property == null) throw new IllegalArgumentException();
        String raw = property.value;
        boolean utc = raw.endsWith("Z");
        if (utc) raw = raw.substring(0, raw.length() - 1);
        ZoneId zone = utc ? ZoneOffset.UTC : CAMPUS;
        if (!utc) for (String parameter : property.head.split(";")) {
            if (parameter.regionMatches(true, 0, "TZID=", 0, 5))
                zone = ZoneId.of(parameter.substring(5).replace("\"", ""));
        }
        return LocalDateTime.parse(raw, TIMESTAMP).atZone(zone).withZoneSameInstant(CAMPUS);
    }

    private static String text(Property property) {
        if (property == null) return "";
        // Decode the text escapes before collapsing whitespace to a single line.
        String value = property.value.replaceAll("\\\\[nN]", " ").replaceAll("\\\\([,;\\\\])", "$1").trim();
        if (value.length() <= 120) return value;
        int end = Character.isHighSurrogate(value.charAt(119)) ? 119 : 120;
        return value.substring(0, end).trim();
    }
}
