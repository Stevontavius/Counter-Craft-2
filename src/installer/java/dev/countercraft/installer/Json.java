package dev.countercraft.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader / writer (the installer has no libraries): objects as LinkedHashMap (key order kept, so a launcher's
 * own files are written back as they were plus our entry), arrays as ArrayList, numbers as Double, and strings,
 * booleans and null as themselves.
 */
final class Json {
	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	static Object parse(String text) {
		Json j = new Json(text.startsWith("\uFEFF") ? text.substring(1) : text); // a byte order mark (Windows tools write one)
		j.ws();
		Object v = j.value();
		j.ws();
		if (j.i < j.s.length()) {
			throw j.err("trailing text");
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(String text) {
		return (Map<String, Object>) parse(text);
	}

	private IllegalArgumentException err(String what) {
		return new IllegalArgumentException("JSON: " + what + " at " + i);
	}

	private void ws() {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
	}

	private Object value() {
		if (i >= s.length()) {
			throw err("unexpected end");
		}
		char c = s.charAt(i);
		switch (c) {
			case '{' -> {
				i++;
				Map<String, Object> m = new LinkedHashMap<>();
				ws();
				if (s.charAt(i) == '}') {
					i++;
					return m;
				}
				while (true) {
					ws();
					String k = string();
					ws();
					expect(':');
					ws();
					m.put(k, value());
					ws();
					if (s.charAt(i) == ',') {
						i++;
						continue;
					}
					expect('}');
					return m;
				}
			}
			case '[' -> {
				i++;
				List<Object> l = new ArrayList<>();
				ws();
				if (s.charAt(i) == ']') {
					i++;
					return l;
				}
				while (true) {
					ws();
					l.add(value());
					ws();
					if (s.charAt(i) == ',') {
						i++;
						continue;
					}
					expect(']');
					return l;
				}
			}
			case '"' -> {
				return string();
			}
			case 't' -> {
				word("true");
				return Boolean.TRUE;
			}
			case 'f' -> {
				word("false");
				return Boolean.FALSE;
			}
			case 'n' -> {
				word("null");
				return null;
			}
			default -> {
				int start = i;
				while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
					i++;
				}
				if (start == i) {
					throw err("unexpected '" + c + "'");
				}
				return Double.parseDouble(s.substring(start, i));
			}
		}
	}

	private void expect(char c) {
		if (i >= s.length() || s.charAt(i) != c) {
			throw err("expected '" + c + "'");
		}
		i++;
	}

	private void word(String w) {
		if (!s.startsWith(w, i)) {
			throw err("expected " + w);
		}
		i += w.length();
	}

	private String string() {
		expect('"');
		StringBuilder b = new StringBuilder();
		while (true) {
			char c = s.charAt(i++);
			if (c == '"') {
				return b.toString();
			}
			if (c == '\\') {
				char e = s.charAt(i++);
				switch (e) {
					case 'n' -> b.append('\n');
					case 't' -> b.append('\t');
					case 'r' -> b.append('\r');
					case 'b' -> b.append('\b');
					case 'f' -> b.append('\f');
					case 'u' -> {
						b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> b.append(e);
				}
			} else {
				b.append(c);
			}
		}
	}

	/** Pretty-printed, two-space indent (as the Minecraft Launcher writes its files). */
	static String write(Object v) {
		StringBuilder b = new StringBuilder();
		write(b, v, "");
		return b.append('\n').toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder b, Object v, String ind) {
		if (v instanceof Map<?, ?> m) {
			if (m.isEmpty()) {
				b.append("{}");
				return;
			}
			b.append("{\n");
			int n = 0;
			for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
				b.append(ind).append("  ");
				str(b, e.getKey());
				b.append(" : ");
				write(b, e.getValue(), ind + "  ");
				b.append(++n < m.size() ? ",\n" : "\n");
			}
			b.append(ind).append('}');
		} else if (v instanceof List<?> l) {
			if (l.isEmpty()) {
				b.append("[]");
				return;
			}
			b.append("[\n");
			for (int k = 0; k < l.size(); k++) {
				b.append(ind).append("  ");
				write(b, l.get(k), ind + "  ");
				b.append(k + 1 < l.size() ? ",\n" : "\n");
			}
			b.append(ind).append(']');
		} else if (v instanceof String str) {
			str(b, str);
		} else if (v instanceof Double d) {
			b.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : d.toString());
		} else {
			b.append(v);
		}
	}

	private static void str(StringBuilder b, String s) {
		b.append('"');
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) {
						b.append(String.format("\\u%04x", (int) c));
					} else {
						b.append(c);
					}
				}
			}
		}
		b.append('"');
	}
}
