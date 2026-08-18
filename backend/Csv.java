import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC-4180-style CSV helpers: field escaping and line parsing that
 * correctly handles quoted fields containing commas, quotes and newlines.
 */
public final class Csv {
    private Csv() {}

    /** Quote a field if it contains a comma, quote, or newline; double internal quotes. */
    public static String escapeField(String value) {
        if (value == null) value = "";
        boolean mustQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!mustQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Parse the entire CSV text into rows of fields. Handles quoted fields with
     * embedded commas and newlines. Empty trailing content is ignored.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;

        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    rowHasContent = true;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                } else if (c == '\n' || c == '\r') {
                    // handle \r\n as a single line break
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    current.add(field.toString());
                    field.setLength(0);
                    if (rowHasContent) {
                        rows.add(current);
                    }
                    current = new ArrayList<>();
                    rowHasContent = false;
                } else {
                    field.append(c);
                    rowHasContent = true;
                }
            }
        }

        // flush last field/row if the file didn't end with a newline
        if (rowHasContent || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            if (rowHasContent) {
                rows.add(current);
            }
        }
        return rows;
    }
}
