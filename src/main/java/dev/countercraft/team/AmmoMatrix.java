package dev.countercraft.team;

import dev.countercraft.gun.GunSpec;
import java.util.Map;

/**
 * Counter-Craft's ammo prices (docs/ammo-matrix.html). CS2 has no ammo purchases, so a magazine costs Counter-Strike
 * 1.6's price per round for the gun's caliber, times the gun's magazine, times {@link #K}, rounded up to $10. Calibers
 * are CS2's own (weapons.vdata m_nPrimaryAmmoType; CS2 lists the AUG as 7.62mm). The 1.6 box prices are recalled, not
 * measured: an approximation.
 */
public final class AmmoMatrix {
	/** Survival price factor over CS 1.6 (chosen: 3). */
	public static final int K = 3;

	/** CS2's caliber per gun id. */
	private static final Map<String, String> CALIBER = Map.ofEntries(
			Map.entry("glock", "9mm"), Map.entry("elite", "9mm"), Map.entry("tec9", "9mm"), Map.entry("mp9", "9mm"), Map.entry("mp7", "9mm"),
			Map.entry("mp5sd", "9mm"), Map.entry("bizon", "9mm"),
			Map.entry("mac10", "45acp"), Map.entry("ump45", "45acp"),
			Map.entry("hkp2000", "357sig"), Map.entry("usp_silencer", "357sig"), Map.entry("p250", "357sig"), Map.entry("cz75a", "357sig"),
			Map.entry("deagle", "50ae"), Map.entry("revolver", "50ae"),
			Map.entry("fiveseven", "57mm"), Map.entry("p90", "57mm"),
			Map.entry("nova", "buckshot"), Map.entry("xm1014", "buckshot"), Map.entry("sawedoff", "buckshot"), Map.entry("mag7", "buckshot"),
			Map.entry("famas", "556mm"), Map.entry("galilar", "556mm"), Map.entry("m4a1", "556mm"), Map.entry("m4a1_silencer", "556mm"),
			Map.entry("sg556", "556mm"), Map.entry("m249", "556mm"), Map.entry("negev", "556mm"),
			Map.entry("ak47", "762mm"), Map.entry("aug", "762mm"), Map.entry("ssg08", "762mm"), Map.entry("g3sg1", "762mm"), Map.entry("scar20", "762mm"),
			Map.entry("awp", "338mag"));

	/** CS 1.6 ammo boxes: {price, rounds}. */
	private static final Map<String, int[]> BOX = Map.of(
			"9mm", new int[] {20, 30}, "45acp", new int[] {25, 12}, "357sig", new int[] {50, 13}, "50ae", new int[] {40, 7},
			"57mm", new int[] {50, 50}, "buckshot", new int[] {65, 8}, "556mm", new int[] {60, 30}, "762mm", new int[] {80, 30},
			"338mag", new int[] {125, 10});

	private static final Map<String, String> NAMES = Map.of("9mm", "9mm", "45acp", ".45 ACP", "357sig", ".357 SIG", "50ae", ".50 AE", "57mm", "5.7mm",
			"buckshot", "12 gauge", "556mm", "5.56mm", "762mm", "7.62mm", "338mag", ".338 Magnum");

	private AmmoMatrix() {
	}

	/** The gun's caliber key, or null (no bought ammo: the Zeus, knives). */
	public static String caliber(GunSpec spec) {
		return CALIBER.get(spec.id());
	}

	public static String caliberName(String caliber) {
		return NAMES.getOrDefault(caliber, caliber);
	}

	/** One magazine for this gun, in dollars (0: can't be bought). */
	public static int magPrice(GunSpec spec) {
		String c = caliber(spec);
		int[] box = c == null ? null : BOX.get(c);
		if (box == null) {
			return 0;
		}
		double dollars = (double) box[0] / box[1] * spec.magSize() * K;
		return Math.max(10, (int) Math.ceil(dollars / 10.0) * 10);
	}
}
