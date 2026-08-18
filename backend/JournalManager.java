import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Handles all persistence and querying for journal entries backed by a CSV
 * file. Reads and writes are synchronized so concurrent HTTP requests stay
 * consistent.
 */
public class JournalManager {
    private static final String HEADER = "id,date,mood,title,entry,image";
    private final Path csvPath;
    private final Object lock = new Object();

    public JournalManager(Path csvPath) {
        this.csvPath = csvPath;
    }

    /** Ensure the data directory and CSV file (with header) exist. */
    public void init() throws IOException {
        synchronized (lock) {
            if (csvPath.getParent() != null) {
                Files.createDirectories(csvPath.getParent());
            }
            if (!Files.exists(csvPath)) {
                Files.writeString(csvPath, HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        }
    }

    /** Load all entries from the CSV, skipping the header row. */
    public List<JournalEntry> getAll() throws IOException {
        synchronized (lock) {
            List<JournalEntry> entries = new ArrayList<>();
            if (!Files.exists(csvPath)) return entries;

            String text = Files.readString(csvPath, StandardCharsets.UTF_8);
            List<List<String>> rows = Csv.parse(text);
            for (int r = 0; r < rows.size(); r++) {
                List<String> fields = rows.get(r);
                if (fields.isEmpty()) continue;
                // skip header row
                if (r == 0 && "id".equalsIgnoreCase(fields.get(0).trim())) continue;
                entries.add(JournalEntry.fromCsvFields(fields));
            }
            return entries;
        }
    }

    /** Search/filter entries. Either argument may be null/blank to ignore it. */
    public List<JournalEntry> search(String query, String mood) throws IOException {
        List<JournalEntry> all = getAll();
        List<JournalEntry> matched = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String m = mood == null ? "" : mood.trim();

        for (JournalEntry e : all) {
            boolean matchesQuery = q.isEmpty()
                    || e.getTitle().toLowerCase(Locale.ROOT).contains(q)
                    || e.getEntry().toLowerCase(Locale.ROOT).contains(q);
            boolean matchesMood = m.isEmpty() || m.equalsIgnoreCase("All")
                    || e.getMood().equalsIgnoreCase(m);
            if (matchesQuery && matchesMood) {
                matched.add(e);
            }
        }
        return matched;
    }

    /** Add a new entry with an auto-incremented id and persist it. */
    public JournalEntry add(String date, String mood, String title, String entry, String image) throws IOException {
        synchronized (lock) {
            List<JournalEntry> all = getAll();
            int nextId = 1;
            for (JournalEntry e : all) {
                if (e.getId() >= nextId) nextId = e.getId() + 1;
            }
            String safeDate = (date == null || date.isBlank())
                    ? LocalDate.now().toString() : date.trim();
            JournalEntry created = new JournalEntry(nextId, safeDate, mood, title, entry, image);
            all.add(created);
            writeAll(all);
            return created;
        }
    }

    /** Replace the fields of an existing entry. Returns null if the id is missing. */
    public JournalEntry update(int id, String date, String mood, String title, String entry, String image) throws IOException {
        synchronized (lock) {
            List<JournalEntry> all = getAll();
            for (int i = 0; i < all.size(); i++) {
                JournalEntry existing = all.get(i);
                if (existing.getId() != id) continue;
                String safeDate = (date == null || date.isBlank()) ? existing.getDate() : date.trim();
                String safeMood = mood == null ? existing.getMood() : mood;
                String safeTitle = title == null ? existing.getTitle() : title;
                String safeEntry = entry == null ? existing.getEntry() : entry;
                String safeImage = image == null ? existing.getImage() : image;
                JournalEntry updated = new JournalEntry(id, safeDate, safeMood, safeTitle, safeEntry, safeImage);
                all.set(i, updated);
                writeAll(all);
                return updated;
            }
            return null;
        }
    }

    /** Delete the entry with the given id. Returns true if something was removed. */
    public boolean delete(int id) throws IOException {
        synchronized (lock) {
            List<JournalEntry> all = getAll();
            boolean removed = all.removeIf(e -> e.getId() == id);
            if (removed) {
                writeAll(all);
            }
            return removed;
        }
    }

    /** Compute journal statistics from the current CSV contents. */
    public Stats stats() throws IOException {
        List<JournalEntry> all = getAll();
        Stats s = new Stats();
        s.total = all.size();

        YearMonth thisMonth = YearMonth.now();
        Map<String, Integer> moodCounts = new LinkedHashMap<>();
        TreeSet<LocalDate> dates = new TreeSet<>();

        for (JournalEntry e : all) {
            moodCounts.merge(e.getMood(), 1, Integer::sum);
            LocalDate d = parseDate(e.getDate());
            if (d != null) {
                if (YearMonth.from(d).equals(thisMonth)) {
                    s.entriesThisMonth++;
                }
                dates.add(d);
            }
        }

        s.moodCounts = moodCounts;
        s.mostCommonMood = "";
        int best = -1;
        for (Map.Entry<String, Integer> me : moodCounts.entrySet()) {
            if (me.getValue() > best && me.getKey() != null && !me.getKey().isBlank()) {
                best = me.getValue();
                s.mostCommonMood = me.getKey();
            }
        }
        s.currentStreak = computeStreak(dates);
        s.entryDates = new ArrayList<>(dates);
        return s;
    }

    /**
     * Streak = number of consecutive days ending at the most recent entry date.
     * If the most recent entry is today or yesterday the streak is considered
     * active; older latest entries still report the trailing consecutive run.
     */
    private int computeStreak(TreeSet<LocalDate> dates) {
        if (dates.isEmpty()) return 0;
        LocalDate cursor = dates.last();
        int streak = 1;
        while (dates.contains(cursor.minusDays(1))) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Overwrite the CSV with the header followed by all entries. */
    private void writeAll(List<JournalEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (JournalEntry e : entries) {
            sb.append(e.toCsvRow()).append('\n');
        }
        Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Simple container for statistics, with a JSON serializer. */
    public static class Stats {
        public int total;
        public int entriesThisMonth;
        public String mostCommonMood = "";
        public int currentStreak;
        public Map<String, Integer> moodCounts = new LinkedHashMap<>();
        public List<LocalDate> entryDates = new ArrayList<>();

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"total\":").append(total).append(',');
            sb.append("\"entriesThisMonth\":").append(entriesThisMonth).append(',');
            sb.append("\"mostCommonMood\":").append(Json.quote(mostCommonMood)).append(',');
            sb.append("\"currentStreak\":").append(currentStreak).append(',');

            sb.append("\"moodCounts\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> me : moodCounts.entrySet()) {
                if (me.getKey() == null || me.getKey().isBlank()) continue;
                if (!first) sb.append(',');
                sb.append(Json.quote(me.getKey())).append(':').append(me.getValue());
                first = false;
            }
            sb.append("},");

            sb.append("\"entryDates\":[");
            for (int i = 0; i < entryDates.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Json.quote(entryDates.get(i).toString()));
            }
            sb.append("]");

            sb.append('}');
            return sb.toString();
        }
    }
}
