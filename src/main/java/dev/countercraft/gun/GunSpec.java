package dev.countercraft.gun;

/**
 * One CS2 weapon, loaded from countercraft/weapons.json (generated from the user's weapons.vdata by
 * converter/weapons.py). Distances are Hammer units (1 block = 39.37 units), damage is CS2 HP (100 = a full
 * player); pairs are {normal, alternate} (see {@link AccuracySpec}).
 */
public record GunSpec(
		String id,
		String name,
		String type,
		String slot,
		int price,
		int killAward,
		float damage,
		int pellets,
		float armorRatio,
		float penetration,
		float range,
		float rangeModifier,
		float headMult,
		float[] cycle,
		boolean automatic,
		int magSize,
		int reserve,
		float reloadTime,
		boolean singleShellReload,
		float deploy,
		float[] maxSpeed,
		float attackMoveSpeedFactor,
		AccuracySpec accuracy,
		Zoom zoom,
		String silencer,
		Burst burst,
		boolean revolver,
		boolean exhaustible,
		Sounds sounds,
		Ironsight ironsight,
		ShellReload shellReload,
		float[] reloadAmmoAt,
		float[] silencerAt,
		RecoilPattern recoil,
		ShotFx fx) {

	public record Zoom(int levels, int[] fov, boolean unzoomAfterShot, boolean hideViewmodel, boolean crosshair) {
		public boolean scoped() {
			return levels > 0;
		}
	}

	public record Burst(boolean enabled, float between, float cycle) {
	}

	/** AUG / SG 553: zooming aims down the scope (pose blend at these rates per second) instead of a scope overlay. */
	public record Ironsight(float pullUp, float putDown, float fov) {
	}

	/**
	 * CS2 shotgun reload, from the reload clip's sync track (seconds): an intro, then a loop that loads one shell
	 * {@code add} seconds in, repeated per shell, then the outro.
	 */
	public record ShellReload(float intro, float loop, float add, float outro, float duration) {
		/** Seconds from the reload start until shell {@code k} (0-based) is in. */
		public float shellAt(int k) {
			return intro + add + k * loop;
		}
	}

	/**
	 * CS2 shot effects: muzzle flash family per silencer state, tracer type and frequency {normal, alternate} (a tracer
	 * every Nth bullet, 0 = never; silenced guns have none), and the third-person muzzle points (units from the eye:
	 * forward, left, up; the second is the Dual Berettas' left gun or the silencer's end).
	 */
	public record ShotFx(String flash, String flashSilenced, String tracer, int[] tracerFrequency, float[][] muzzle) {
		public int tracerEvery(boolean alt) {
			return tracerFrequency[alt ? 1 : 0];
		}
	}

	/** Sound event ids (provided by the converted CS2 asset pack). */
	public record Sounds(String fire, String fireUnsilenced, String zoomIn, String zoomOut) {
	}

	/** Seconds between shots; {@code alt} = alternate mode (R8 fan fire, burst...). */
	public float cycleSeconds(boolean alt) {
		return cycle[alt && cycle.length > 1 ? 1 : 0];
	}

	/** Ticks between shots, fractional (MP9: 0.07 s = 1.4 ticks; the remainder carries over). */
	public double cycleTicks(boolean alt) {
		return cycleSeconds(alt) * 20.0;
	}

	public int reloadTicks() {
		return Math.max(1, Math.round(reloadTime * 20f));
	}

	/**
	 * Ticks from a magazine reload's start until the rounds are in: where CS2's reload clip puts them in (its
	 * WPN_RELOAD_ADD_AMMO event; the clip plays over reloadTime), the empty reload's when {@code empty}; without that
	 * data (a weapon table from before BETA 1.2.3), the reload's end.
	 */
	public int reloadAmmoTicks(boolean empty) {
		float f = reloadAmmoAt == null ? Float.NaN : reloadAmmoAt[empty ? 1 : 0];
		return Float.isNaN(f) ? reloadTicks() : Math.max(1, Math.round(f * reloadTime * 20f));
	}

	/**
	 * Ticks from screwing the silencer on ({@code attach}) or off until it is: CS2's WPN_SILENCER_ATTACH / _DETACH in the
	 * clip; without that data, two seconds (BETA 1.2.2 and before).
	 */
	public int silencerTicks(boolean attach) {
		float s = silencerAt == null ? Float.NaN : silencerAt[attach ? 0 : 1];
		return Float.isNaN(s) ? 40 : Math.max(1, Math.round(s * 20f));
	}

	/** Shot sound event id (without namespace). */
	public String fireSound() {
		return sounds.fire() != null ? sounds.fire() : "weapon." + id + ".single";
	}

	public boolean taser() {
		return "taser".equals(type);
	}
}
