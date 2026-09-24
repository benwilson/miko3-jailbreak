package com.miko3.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for our wire formats (voice's lane envelopes, the Claude
 * API): objects, arrays, strings, numbers (integers as Long, others as
 * Double), booleans, null. org.json is Android-only and the callers have to
 * run on the host JVM.
 */
public final class Json {
    /** Deepest object/array nesting parse() accepts. The parser recurses once
     * per level, so without a cap a hostile body (an endpoint answering with
     * thousands of '[') overflows the stack, and a StackOverflowError is an
     * Error: it escapes callers' RuntimeException handling and kills the
     * thread. None of our wire formats come near this. */
    static final int MAX_DEPTH = 64;

    private final String s;
    private int i;
    private int depth;

    private Json(String s) {
        this.s = s;
    }

    /** Throws IllegalArgumentException on anything malformed or nested more
     * than MAX_DEPTH deep. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing characters at " + p.i);
        }
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : String.valueOf(d));
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
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
            quote(sb, v.toString());
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

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(i);
        if (c == '{' || c == '[') {
            if (++depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            Object v = c == '{' ? object() : array();
            depth--;
            return v;
        } else if (c == '"') {
            return string();
        } else if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        } else if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        } else if (s.startsWith("null", i)) {
            i += 4;
            return null;
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        }
        throw error("unexpected '" + c + "'");
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw error("expected a key");
            }
            String key = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw error("expected ':'");
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
            } else if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            } else {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
            } else if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            } else {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
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
                    if (i + 4 > s.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    sb.append(e); // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private Object number() {
        int start = i;
        boolean integral = true;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                i++;
            } else {
                break;
            }
        }
        String num = s.substring(start, i);
        try {
            if (integral) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException tooBig) {
                    return Double.parseDouble(num);
                }
            }
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw error("bad number " + num);
        }
    }
}
