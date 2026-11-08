package spellbench.kit.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small JSON reader and writer for the agent role (protocol v2 Sections 2 and 4.3). Agents are lenient readers
 * (Section 4.2): this reader accepts any valid JSON and returns maps (insertion ordered), lists, strings, longs,
 * doubles (only for fractional numbers, which no protocol field carries), booleans and nulls. The writer emits
 * canonical JSON (RFC 8785 for integer-only, ASCII-keyed values): sorted keys, no whitespace.
 */
public final class Json {

    private Json() {
    }

    public static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.pos != text.length()) {
            throw new Malformed("trailing characters at " + r.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new Malformed("top level is not an object");
        }
        return (Map<String, Object>) v;
    }

    /** Canonical JSON: keys sorted by code point, no whitespace, integers in shortest form. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static byte[] canonicalBytes(Object value) {
        return canonical(value).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            string(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof long[]) {
            List<Object> l = new ArrayList<>();
            for (long x : (long[]) v) {
                l.add(x);
            }
            write(sb, l);
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > 200) {
                throw new Malformed("nesting too deep");
            }
            if (pos >= s.length()) {
                throw new Malformed("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return str();
                case 't':
                    lit("true");
                    return Boolean.TRUE;
                case 'f':
                    lit("false");
                    return Boolean.FALSE;
                case 'n':
                    lit("null");
                    return null;
                default:
                    return number();
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, pos)) {
                throw new Malformed("bad literal at " + pos);
            }
            pos += w.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new Malformed("expected key at " + pos);
                }
                String k = str();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new Malformed("expected : at " + pos);
                }
                pos++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (pos >= s.length()) {
                    throw new Malformed("unexpected end in object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new Malformed("expected , or } at " + (pos - 1));
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (pos >= s.length()) {
                    throw new Malformed("unexpected end in array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new Malformed("expected , or ] at " + (pos - 1));
                }
            }
        }

        String str() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new Malformed("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new Malformed("bad escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw new Malformed("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new Malformed("bad escape \\" + e);
                }
            }
        }

        Object number() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            boolean frac = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    frac = true;
                    pos++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-")) {
                throw new Malformed("bad value at " + start);
            }
            if (frac) {
                return Double.parseDouble(t);
            }
            return Long.parseLong(t);
        }
    }

    // ------------------------------------------------------------------ small typed accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o == null ? new ArrayList<>() : (List<Object>) o;
    }

    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        return obj(m.get(key));
    }

    public static List<Object> arr(Map<String, Object> m, String key) {
        return arr(m.get(key));
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : dflt;
    }

    public static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /** A fresh ordered map from key-value pairs. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A deep copy through canonical JSON. */
    public static Object copy(Object v) {
        return parse(canonical(v));
    }
}
