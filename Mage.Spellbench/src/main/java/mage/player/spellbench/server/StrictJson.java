package mage.player.spellbench.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Strict JSON for protocol v2 (Section 2) and its canonical form (Section 4.3, RFC 8785).
 * <p>
 * The reader rejects, as {@link Malformed}: invalid UTF-8, duplicate keys, numbers with a fraction or an
 * exponent, integers outside |x| <= 2^53 - 1, non-finite constants, nesting deeper than 64 levels, unpaired
 * surrogate escapes, and trailing content. A valid value whose top level is not an object is
 * {@link NotAnObject} (Section 9.8 answers it with {@code malformed_request}). Values map to
 * {@code Map<String, Object>} (insertion ordered), {@code List<Object>}, {@code Long}, {@code String},
 * {@code Boolean} and {@code null}.
 */
public final class StrictJson {

    public static final long MAX_INT = (1L << 53) - 1;
    public static final int MAX_NESTING = 64;

    /** Not strict JSON: {@code malformed_json}. */
    public static class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /** Strict JSON whose top level is not an object: {@code malformed_request}. */
    public static final class NotAnObject extends Malformed {
        private static final long serialVersionUID = 1L;

        NotAnObject() {
            super("top-level JSON value is not an object");
        }
    }

    private final String s;
    private int i;

    private StrictJson(String s) {
        this.s = s;
    }

    /** Parses one line (terminator already stripped); the top level must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] line) throws Malformed {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line)).toString();
        } catch (CharacterCodingException e) {
            throw new Malformed("line is not valid UTF-8");
        }
        StrictJson p = new StrictJson(text);
        p.ws();
        Object value = p.value(1);
        p.ws();
        if (p.i != text.length()) {
            throw new Malformed("trailing content after the JSON value at offset " + p.i);
        }
        if (!(value instanceof Map)) {
            throw new NotAnObject();
        }
        return (Map<String, Object>) value;
    }

    private Object value(int depth) throws Malformed {
        if (i >= s.length()) {
            throw new Malformed("unexpected end of line");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new Malformed("unexpected character at offset " + i);
        }
    }

    private Map<String, Object> object(int depth) throws Malformed {
        if (depth > MAX_NESTING) {
            throw new Malformed("JSON nesting deeper than " + MAX_NESTING + " levels");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return out;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw new Malformed("expected a string key at offset " + i);
            }
            String key = string();
            ws();
            expect(':');
            ws();
            Object v = value(depth + 1);
            if (out.containsKey(key)) {
                throw new Malformed("duplicate JSON key: " + key);
            }
            out.put(key, v);
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    private List<Object> array(int depth) throws Malformed {
        if (depth > MAX_NESTING) {
            throw new Malformed("JSON nesting deeper than " + MAX_NESTING + " levels");
        }
        List<Object> out = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return out;
        }
    }

    private String string() throws Malformed {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new Malformed("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new Malformed("unescaped control character in a string");
            }
            if (c != '\\') {
                sb.append(c); // surrogates here come from valid UTF-8, so they are paired
                continue;
            }
            if (i >= s.length()) {
                throw new Malformed("unterminated escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
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
                    char u = hex4();
                    if (Character.isHighSurrogate(u)) {
                        if (i + 6 <= s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                            i += 2;
                            char low = hex4();
                            if (!Character.isLowSurrogate(low)) {
                                throw new Malformed("string contains a lone surrogate escape");
                            }
                            sb.append(u).append(low);
                        } else {
                            throw new Malformed("string contains a lone surrogate escape");
                        }
                    } else if (Character.isLowSurrogate(u)) {
                        throw new Malformed("string contains a lone surrogate escape");
                    } else {
                        sb.append(u);
                    }
                    break;
                default:
                    throw new Malformed("invalid escape \\" + e);
            }
        }
    }

    private char hex4() throws Malformed {
        if (i + 4 > s.length()) {
            throw new Malformed("truncated \\u escape");
        }
        int v = 0;
        for (int k = 0; k < 4; k++) {
            int d = Character.digit(s.charAt(i + k), 16);
            if (d < 0) {
                throw new Malformed("invalid \\u escape");
            }
            v = (v << 4) | d;
        }
        i += 4;
        return (char) v;
    }

    private Long number() throws Malformed {
        int start = i;
        if (peek('-')) {
            i++;
        }
        if (i >= s.length() || !Character.isDigit(s.charAt(i))) {
            throw new Malformed("invalid number at offset " + start);
        }
        if (s.charAt(i) == '0') {
            i++;
        } else {
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
        }
        if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            throw new Malformed("fractional JSON number rejected at offset " + start);
        }
        String digits = s.substring(start, i);
        int count = digits.startsWith("-") ? digits.length() - 1 : digits.length();
        if (count > 16) {
            throw new Malformed("JSON integer outside |x| <= 2^53 - 1");
        }
        long v = Long.parseLong(digits);
        if (Math.abs(v) > MAX_INT) {
            throw new Malformed("JSON integer outside |x| <= 2^53 - 1: " + digits);
        }
        return v;
    }

    private void literal(String word) throws Malformed {
        if (!s.startsWith(word, i)) {
            throw new Malformed("invalid literal at offset " + i);
        }
        i += word.length();
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                return;
            }
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) throws Malformed {
        if (!peek(c)) {
            throw new Malformed("expected '" + c + "' at offset " + i);
        }
        i++;
    }

    // ----------------------------------------------------------------------------------------------
    // Canonical JSON (RFC 8785 as Section 4.3 states it): keys sorted by UTF-16 code units, no
    // whitespace, integers only, strings escaping only '"', '\' and control characters, raw UTF-8.

    public static byte[] canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalString(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            long n = ((Number) v).longValue();
            if (Math.abs(n) > MAX_INT) {
                throw new IllegalArgumentException("integer outside |x| <= 2^53 - 1: " + n);
            }
            sb.append(n);
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sorted.put((String) e.getKey(), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
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
}
