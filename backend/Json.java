import java.util.HashMap;
import java.util.Map;

/**
 * Tiny JSON helper. Provides string quoting for output and a minimal parser
 * for flat JSON objects with string/number values (sufficient for request
 * bodies used by this app). No external dependencies.
 */
public final class Json {
    private Json() {}

    /** Escape and quote a string value for JSON output. */
    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Parse a flat JSON object into a map of String keys to String values.
     * Numbers, booleans and null are returned as their raw string form.
     * This intentionally supports only the flat shape used by request bodies.
     */
    public static Map<String, String> parseObject(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null) return result;
        int i = 0;
        int n = json.length();

        i = skipWhitespace(json, i);
        if (i >= n || json.charAt(i) != '{') return result;
        i++; // consume '{'

        while (i < n) {
            i = skipWhitespace(json, i);
            if (i < n && json.charAt(i) == '}') break;

            if (json.charAt(i) != '"') break; // malformed
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);

            i = skipWhitespace(json, i);
            if (i >= n || json.charAt(i) != ':') break;
            i++; // consume ':'
            i = skipWhitespace(json, i);

            String value;
            if (i < n && json.charAt(i) == '"') {
                StringBuilder val = new StringBuilder();
                i = readString(json, i, val);
                value = val.toString();
            } else {
                // read a bare token (number/true/false/null) until , or }
                int start = i;
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    i++;
                }
                value = json.substring(start, i).trim();
            }
            result.put(key.toString(), value);

            i = skipWhitespace(json, i);
            if (i < n && json.charAt(i) == ',') {
                i++;
            } else {
                break;
            }
        }
        return result;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Reads a quoted string starting at index i (which points at the opening quote). */
    private static int readString(String s, int i, StringBuilder out) {
        i++; // consume opening quote
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"':  out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/':  out.append('/'); break;
                    case 'n':  out.append('\n'); break;
                    case 'r':  out.append('\r'); break;
                    case 't':  out.append('\t'); break;
                    case 'b':  out.append('\b'); break;
                    case 'f':  out.append('\f'); break;
                    case 'u':
                        if (i + 5 < n) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ignored) {}
                            i += 4;
                        }
                        break;
                    default: out.append(next);
                }
                i += 2;
            } else if (c == '"') {
                return i + 1; // consume closing quote
            } else {
                out.append(c);
                i++;
            }
        }
        return i;
    }
}
