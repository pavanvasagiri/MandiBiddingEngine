package com.mandi.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON support without external dependencies: parsing of flat objects
 * (string / number / boolean / null values) and string escaping for building
 * responses. Not a general-purpose JSON library - the local API only exchanges
 * small flat documents.
 */
public final class JsonUtil {

    private JsonUtil() { }

    /** Parses a flat JSON object such as {@code {"cropId":1,"price":2400.5}}. */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return values;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) != '"') break;
            int keyEnd = s.indexOf('"', i + 1);
            if (keyEnd < 0) break;
            String key = unescape(s.substring(i + 1, keyEnd));
            i = keyEnd + 1;
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) i++;
            if (i >= n) break;
            StringBuilder value = new StringBuilder();
            if (s.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        value.append(escaped(s.charAt(i + 1)));
                        i += 2;
                    } else if (c == '"') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        value.append(c);
                        i++;
                    }
                }
                if (!closed) break;
            } else {
                while (i < n && s.charAt(i) != ',') {
                    value.append(s.charAt(i));
                    i++;
                }
            }
            values.put(key, value.toString().trim());
        }
        return values;
    }

    /** Escapes a value into a JSON string literal (null-safe). */
    public static String str(Object value) {
        return value == null ? "null" : "\"" + escape(String.valueOf(value)) + "\"";
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static char escaped(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> c;
        };
    }
}
