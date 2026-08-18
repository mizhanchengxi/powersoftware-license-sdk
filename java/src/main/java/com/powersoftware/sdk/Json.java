package com.powersoftware.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具（仅覆盖 SDK 需要的对象/数组/字符串/数字/布尔），零依赖。
 */
final class Json {

    private Json() {
    }

    static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(stringify(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(stringify(item));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    static Map<String, Object> parseObject(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                i += 4;
                return Boolean.TRUE;
            }
            if (c == 'f') {
                i += 5;
                return Boolean.FALSE;
            }
            if (c == 'n') {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWs();
            if (s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                i++;
                map.put(key, parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') {
                    break;
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWs();
            if (s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') {
                    break;
                }
            }
            return list;
        }

        private String parseString() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i < s.length()) {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length() && "-+.0123456789eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(start, i);
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                return Long.parseLong(num);
            }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
