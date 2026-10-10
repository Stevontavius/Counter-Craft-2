package dev.countercraft.client.crosshair;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CS2 crosshair share codes -> crosshair cvars. Layouts from akiver/csgo-sharecode (MIT), which reads CS2's own:
 * <ul>
 * <li>{@code CS} + 44 characters: CS2's current format (cs2-v1, 32 bytes, since the 30/09/2026 update): every setting,
 * outline colour and negative gaps included.</li>
 * <li>{@code CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx} (18 bytes): version 3 / 4 hold the same pixel settings (no outline
 * colour); version 1 is the old CS:GO format, converted approximately (see {@link #legacyV1}).</li>
 * </ul>
 * Codes are base 57 numbers over CS2's alphabet, least significant character first; byte 0 is a checksum.
 */
public final class CrosshairShareCode {
	private static final String DICTIONARY = "ABCDEFGHJKLMNOPQRSTUVWXYZabcdefhijkmnopqrstuvwxyz23456789";

	/** A code that isn't a valid CS2 crosshair code. */
	public static final class InvalidCode extends Exception {
		InvalidCode(String why) {
			super(why);
		}
	}

	/** Decoded cvars; {@code approximate} when the code is the old CS:GO format. */
	public record Result(Map<String, Float> cvars, boolean approximate) {
	}

	private CrosshairShareCode() {
	}

	public static Result decode(String code) throws InvalidCode {
		String c = code.trim().replace(" ", "");
		if (c.matches("CS[" + DICTIONARY + "]{44}")) {
			int[] b = bytes(c.substring(2), 32);
			checksum(b);
			if (b[1] != 1) {
				throw new InvalidCode("Unknown crosshair code version " + b[1]);
			}
			return new Result(cs2V1(b), false);
		}
		if (c.matches("CSGO(-?[" + DICTIONARY + "]{5}){5}")) {
			int[] b = bytes(c.replace("CSGO", "").replace("-", ""), 18);
			checksum(b);
			return switch (b[1]) {
				case 1 -> new Result(legacyV1(b), true);
				case 3, 4 -> new Result(pixel(b, b[1]), false);
				default -> throw new InvalidCode("Unknown crosshair code version " + b[1]);
			};
		}
		throw new InvalidCode("That isn't a CS2 crosshair code");
	}

	private static int[] bytes(String chars, int n) throws InvalidCode {
		BigInteger big = BigInteger.ZERO;
		BigInteger base = BigInteger.valueOf(DICTIONARY.length());
		for (int i = chars.length() - 1; i >= 0; i--) {
			big = big.multiply(base).add(BigInteger.valueOf(DICTIONARY.indexOf(chars.charAt(i))));
		}
		byte[] raw = big.toByteArray();
		int start = Math.max(0, raw.length - n);
		for (int i = 0; i < start; i++) {
			if (raw[i] != 0) {
				throw new InvalidCode("Malformed crosshair code");
			}
		}
		int[] out = new int[n];
		int off = n - (raw.length - start);
		for (int i = start; i < raw.length; i++) {
			out[off + i - start] = raw[i] & 0xFF;
		}
		return out;
	}

	private static void checksum(int[] b) throws InvalidCode {
		int sum = 0;
		for (int i = 1; i < b.length; i++) {
			sum += b[i];
		}
		if ((sum & 0xFF) != b[0]) {
			throw new InvalidCode("Crosshair code checksum doesn't match (typo?)");
		}
	}

	private static int int8(int u) {
		return (byte) u;
	}

	/**
	 * cs2-v1 (CS2 since the 30/09/2026 update), sanitised as CS2 does on import: style capped at 9, thickness at 32,
	 * outline mode at 2, gap (a signed 16-bit value) to +-3840, split values to their ConVar ranges, screen height at
	 * least 240 (0 is rejected).
	 */
	private static Map<String, Float> cs2V1(int[] b) throws InvalidCode {
		int screenHeight = b[2] | b[3] << 8;
		if (screenHeight == 0) {
			throw new InvalidCode("Malformed crosshair code (no screen height)");
		}
		long bits = (b[18] | b[19] << 8 | b[20] << 16 | (long) b[21] << 24) & 0xFFFFFFFFL;
		Map<String, Float> m = new LinkedHashMap<>();
		m.put("cl_crosshairstyle", (float) Math.min(b[4] & 0x1F, 9));
		m.put("cl_crosshair_recoil", (b[4] & 0x20) != 0 ? 1f : 0f);
		m.put("cl_crosshairdot", (b[4] & 0x40) != 0 ? 1f : 0f);
		m.put("cl_crosshair_t", (b[4] & 0x80) != 0 ? 1f : 0f);
		m.put("cl_crosshaircolor_r", (float) b[5]);
		m.put("cl_crosshaircolor_g", (float) b[6]);
		m.put("cl_crosshaircolor_b", (float) b[7]);
		m.put("cl_crosshaircolor_a", (float) b[8]);
		m.put("cl_crosshairoutline_r", (float) b[9]);
		m.put("cl_crosshairoutline_g", (float) b[10]);
		m.put("cl_crosshairoutline_b", (float) b[11]);
		m.put("cl_crosshairoutline_a", (float) b[12]);
		m.put("cl_crosshair_thickness", (float) Math.min(b[13] & 0x3F, 32));
		m.put("cl_crosshair_drawoutline", (float) Math.min(b[13] >> 6, 2));
		m.put("cl_crosshair_gap", (float) Math.max(-3840, Math.min(3840, (short) (b[14] | b[15] << 8))));
		m.put("cl_crosshair_length", (float) b[16]);
		m.put("cl_crosshair_dynamic_spread_limit", (float) b[17]);
		m.put("cl_crosshair_dynamic_splitdist", (float) (bits & 0x7F));
		m.put("cl_crosshair_dynamic_splitalpha_innermod", Math.min(bits >> 7 & 0x7F, 100) / 100f);
		m.put("cl_crosshair_dynamic_splitalpha_outermod", (Math.min(bits >> 14 & 0x7F, 70) + 30) / 100f);
		m.put("cl_crosshair_dynamic_maxdist_splitratio", Math.min(bits >> 21 & 0x7F, 100) / 100f);
		m.put("cl_crosshair_screen_height", (float) Math.max(240, screenHeight));
		return m;
	}

	/** CSGO- codes version 3 / 4: the 2025 pixel crosshair without outline colours. */
	private static Map<String, Float> pixel(int[] b, int version) {
		long bits = (b[10] | b[11] << 8 | b[12] << 16 | (long) b[13] << 24) & 0xFFFFFFFFL;
		Map<String, Float> m = new LinkedHashMap<>();
		m.put("cl_crosshairstyle", (float) (b[2] & 0xF));
		m.put("cl_crosshair_recoil", (b[2] & 0x10) != 0 ? 1f : 0f);
		m.put("cl_crosshairdot", (b[2] & 0x40) != 0 ? 1f : 0f);
		m.put("cl_crosshair_t", (b[2] & 0x80) != 0 ? 1f : 0f);
		m.put("cl_crosshair_drawoutline", (float) (version == 4 ? b[13] >> 4 & 3 : (b[2] & 0x20) != 0 ? 1 : 0));
		m.put("cl_crosshaircolor_r", (float) b[3]);
		m.put("cl_crosshaircolor_g", (float) b[4]);
		m.put("cl_crosshaircolor_b", (float) b[5]);
		m.put("cl_crosshaircolor_a", (float) b[6]);
		m.put("cl_crosshair_gap", (float) b[7]);
		m.put("cl_crosshair_length", (float) b[8]);
		m.put("cl_crosshair_dynamic_spread_limit", (float) b[9]);
		m.put("cl_crosshair_dynamic_splitdist", (float) (bits & 0x7F));
		m.put("cl_crosshair_dynamic_splitalpha_innermod", (bits >> 7 & 0x1F) / 20f);
		m.put("cl_crosshair_dynamic_splitalpha_outermod", ((bits >> 12 & 0xF) + 6) / 20f);
		m.put("cl_crosshair_dynamic_maxdist_splitratio", (bits >> 16 & 0x7F) / 100f);
		m.put("cl_crosshair_thickness", (float) (bits >> 23 & 0x1F));
		m.put("cl_crosshair_screen_height", (float) Math.max(240, b[14] | b[15] << 8));
		m.put("cl_crosshairstyle", (float) Math.min(b[2] & 0xF, version == 4 ? 8 : 7));
		return m;
	}

	/**
	 * The old CS:GO code (version 1): CS:GO-unit sizes, a colour preset and a style 0-5. Approximation: sizes are read as
	 * pixels at a 480 px reference height (CS:GO scaled its crosshair by screen height / 480), the gap gets CS:GO's
	 * built-in +4, styles 0-3 become Dynamic Cross (Classic), 4 Static Cross and 5 Static Cross (Shot Feedback).
	 */
	private static Map<String, Float> legacyV1(int[] b) {
		Map<String, Float> m = new LinkedHashMap<>();
		int style = (b[13] & 0xF) >> 1;
		m.put("cl_crosshairstyle", (float) (style == 4 ? 4 : style == 5 ? 5 : 2));
		float gap = int8(b[2]) / 10f;
		m.put("cl_crosshair_gap", (float) Math.round(gap + 4));
		m.put("cl_crosshair_drawoutline", (b[10] & 8) != 0 ? 1f : 0f);
		int preset = b[10] & 7;
		int[][] presets = {{250, 50, 50}, {50, 250, 50}, {250, 250, 50}, {50, 50, 250}, {50, 250, 250}};
		int[] rgb = preset < presets.length ? presets[preset] : new int[] {b[4], b[5], b[6]};
		m.put("cl_crosshaircolor_r", (float) rgb[0]);
		m.put("cl_crosshaircolor_g", (float) rgb[1]);
		m.put("cl_crosshaircolor_b", (float) rgb[2]);
		m.put("cl_crosshaircolor_a", (float) (((b[13] >> 4) & 4) != 0 ? b[7] : 255));
		m.put("cl_crosshair_dynamic_splitdist", (float) (b[8] & 7));
		m.put("cl_crosshair_recoil", ((b[8] >> 4) & 8) != 0 ? 1f : 0f);
		m.put("cl_crosshair_dynamic_splitalpha_innermod", (b[10] >> 4) / 10f);
		m.put("cl_crosshair_dynamic_splitalpha_outermod", (b[11] & 0xF) / 10f);
		m.put("cl_crosshair_dynamic_maxdist_splitratio", (b[11] >> 4) / 10f);
		m.put("cl_crosshair_thickness", (float) Math.max(1, Math.round(b[12] / 10f)));
		m.put("cl_crosshairdot", ((b[13] >> 4) & 1) != 0 ? 1f : 0f);
		m.put("cl_crosshair_t", ((b[13] >> 4) & 8) != 0 ? 1f : 0f);
		m.put("cl_crosshair_length", (float) Math.round(b[14] / 10f));
		m.put("cl_crosshair_screen_height", 480f);
		return m;
	}
}
