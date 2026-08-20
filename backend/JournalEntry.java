import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * A single journal record. Immutable value object with helpers to convert
 * to/from a CSV row and to a JSON object string.
 *
 * sealUntil is an optional ISO date. Until that morning the letter stays closed:
 * list/search JSON hides title and body so the page cannot leak it.
 */
public class JournalEntry {
    private final int id;
    private final String date;   // ISO format yyyy-MM-dd
    private final String mood;   // e.g. "Happy"
    private final String title;
    private final String entry;
    private final String image;  // optional photo caption
    private final String sealUntil; // optional ISO date; blank = not sealed

    public JournalEntry(int id, String date, String mood, String title, String entry, String image, String sealUntil) {
        this.id = id;
        this.date = date == null ? "" : date;
        this.mood = mood == null ? "" : mood;
        this.title = title == null ? "" : title;
        this.entry = entry == null ? "" : entry;
        this.image = image == null ? "" : image;
        this.sealUntil = sealUntil == null ? "" : sealUntil.trim();
    }

    public int getId() { return id; }
    public String getDate() { return date; }
    public String getMood() { return mood; }
    public String getTitle() { return title; }
    public String getEntry() { return entry; }
    public String getImage() { return image; }
    public String getSealUntil() { return sealUntil; }

    /** True when today is still before the open date. Opens on the sealUntil day. */
    public boolean isSealed() {
        LocalDate until = parseIso(sealUntil);
        if (until == null) return false;
        return LocalDate.now().isBefore(until);
    }

    /** Build an entry from parsed CSV fields (id,date,mood,title,entry,image,sealUntil). */
    public static JournalEntry fromCsvFields(List<String> fields) {
        int id = 0;
        try {
            id = Integer.parseInt(fields.get(0).trim());
        } catch (NumberFormatException ignored) {
            // leave id as 0 for malformed rows
        }
        String date = fields.size() > 1 ? fields.get(1) : "";
        String mood = fields.size() > 2 ? fields.get(2) : "";
        String title = fields.size() > 3 ? fields.get(3) : "";
        String entry = fields.size() > 4 ? fields.get(4) : "";
        String image = fields.size() > 5 ? fields.get(5) : "";
        String seal = fields.size() > 6 ? fields.get(6) : "";
        return new JournalEntry(id, date, mood, title, entry, image, seal);
    }

    /** Serialize to a single CSV line with proper quoting. */
    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(',');
        sb.append(Csv.escapeField(date)).append(',');
        sb.append(Csv.escapeField(mood)).append(',');
        sb.append(Csv.escapeField(title)).append(',');
        sb.append(Csv.escapeField(entry)).append(',');
        sb.append(Csv.escapeField(image)).append(',');
        sb.append(Csv.escapeField(sealUntil));
        return sb.toString();
    }

    /**
     * JSON for the API. Sealed letters keep metadata (id, dates, mood) but hide
     * title, body, and image until the open date.
     */
    public String toJson() {
        boolean sealed = isSealed();
        String publicTitle = sealed ? "A sealed letter" : title;
        String publicEntry = sealed ? "" : entry;
        String publicImage = sealed ? "" : image;

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(id).append(',');
        sb.append("\"date\":").append(Json.quote(date)).append(',');
        sb.append("\"mood\":").append(Json.quote(mood)).append(',');
        sb.append("\"title\":").append(Json.quote(publicTitle)).append(',');
        sb.append("\"entry\":").append(Json.quote(publicEntry)).append(',');
        sb.append("\"image\":").append(Json.quote(publicImage)).append(',');
        sb.append("\"sealUntil\":").append(Json.quote(sealUntil)).append(',');
        sb.append("\"sealed\":").append(sealed);
        sb.append('}');
        return sb.toString();
    }

    private static LocalDate parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
