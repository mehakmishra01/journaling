import java.util.List;

/**
 * A single journal record. Immutable value object with helpers to convert
 * to/from a CSV row and to a JSON object string.
 */
public class JournalEntry {
    private final int id;
    private final String date;   // ISO format yyyy-MM-dd
    private final String mood;   // e.g. "Happy"
    private final String title;
    private final String entry;
    private final String image;  // optional photo caption, e.g. "photograph — desk at golden hour"

    public JournalEntry(int id, String date, String mood, String title, String entry, String image) {
        this.id = id;
        this.date = date == null ? "" : date;
        this.mood = mood == null ? "" : mood;
        this.title = title == null ? "" : title;
        this.entry = entry == null ? "" : entry;
        this.image = image == null ? "" : image;
    }

    public int getId() { return id; }
    public String getDate() { return date; }
    public String getMood() { return mood; }
    public String getTitle() { return title; }
    public String getEntry() { return entry; }
    public String getImage() { return image; }

    /** Build an entry from parsed CSV fields (id,date,mood,title,entry,image). */
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
        return new JournalEntry(id, date, mood, title, entry, image);
    }

    /** Serialize to a single CSV line with proper quoting. */
    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(',');
        sb.append(Csv.escapeField(date)).append(',');
        sb.append(Csv.escapeField(mood)).append(',');
        sb.append(Csv.escapeField(title)).append(',');
        sb.append(Csv.escapeField(entry)).append(',');
        sb.append(Csv.escapeField(image));
        return sb.toString();
    }

    /** Serialize to a JSON object string. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(id).append(',');
        sb.append("\"date\":").append(Json.quote(date)).append(',');
        sb.append("\"mood\":").append(Json.quote(mood)).append(',');
        sb.append("\"title\":").append(Json.quote(title)).append(',');
        sb.append("\"entry\":").append(Json.quote(entry)).append(',');
        sb.append("\"image\":").append(Json.quote(image));
        sb.append('}');
        return sb.toString();
    }
}
