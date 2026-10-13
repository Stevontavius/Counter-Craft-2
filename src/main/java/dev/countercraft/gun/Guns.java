package dev.countercraft.gun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The weapon table: every CS2 gun, read from config/countercraft/weapons.json. It's made on the player's PC from their
 * own Counter-Strike 2 (converter/weapons.py, a step of the asset pack build: CS2's weapon data plus spray patterns
 * measured in CS2), so none of Valve's data ships with the mod; the installer puts a copy in the install's config folder,
 * and a server needs one in its own.
 */
public final class Guns {
	/** Knives: all share CS2's melee stats; they differ in model and animations. */
	public record MeleeDef(String id, String name) {
	}

	/**
	 * Grenades: CS2 data (damage/radius for the HE, throw velocity, speed, deploy time) plus sound event ids by role.
	 * {@code throwAnim}: seconds of the overhand / underhand throw clips; the next grenade is drawn after it.
	 */
	public record GrenadeDef(String id, String name, int price, float damage, float radius, float throwVelocity, float maxSpeed,
			float deploy, float[] throwAnim, Map<String, String> sounds) {
		/** Seconds the throw animation plays for this strength (overhand for a full throw, underhand otherwise). */
		public float throwSeconds(float strength) {
			return strength >= 0.99f ? throwAnim[0] : throwAnim[1];
		}
	}

	/** A muzzle flash family (a CS2 particle system, reduced to what the mod draws). */
	public record Flash(boolean silenced, int sparks, int count, float[] radius, float[] alpha, float[] along, float[] roll,
			int[][] color, float overbright, float radiusScale, float life) {
	}

	/** A tracer type: speed (u/s), visible length (u), length fade-in (s), screen-size clamps, radius, alpha, colour. */
	public record Tracer(float speed, float maxLength, float fadeIn, float minSize, float maxSize, float radius, float[] alpha,
			int[][] color, boolean rope) {
	}

	private static JsonObject root;
	public static final Map<String, GunSpec> ALL = Collections.unmodifiableMap(load());
	public static final Map<String, MeleeDef> MELEE = Collections.unmodifiableMap(loadMelee());
	public static final Map<String, GrenadeDef> GRENADES = Collections.unmodifiableMap(loadGrenades());
	public static final Map<String, Flash> FLASHES = Collections.unmodifiableMap(loadFlashes());
	public static final Map<String, Tracer> TRACERS = Collections.unmodifiableMap(loadTracers());

	private Guns() {
	}

	public static GunSpec get(String id) {
		return ALL.get(id);
	}

	private static Map<String, GunSpec> load() {
		Map<String, GunSpec> out = new LinkedHashMap<>();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("countercraft").resolve("weapons.json");
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("Counter-Craft 2 needs its weapon table at " + file + ". The Counter-Craft 2 installer makes it "
					+ "from your own Counter-Strike 2 and puts it there; it's also in the asset pack (assets/countercraft/weapons.json), so a "
					+ "server can be given a copy in its config/countercraft folder.");
		}
		try (InputStream in = Files.newInputStream(file)) {
			root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			int measured = 0;
			for (JsonElement e : root.getAsJsonArray("weapons")) {
				GunSpec g = parse(e.getAsJsonObject());
				out.put(g.id(), g);
				if (e.getAsJsonObject().get("recoil").isJsonObject()) {
					measured++;
				}
			}
			CounterCraft.LOG.info("[countercraft] weapon table: {} guns, {} with measured CS2 spray patterns", out.size(), measured);
		} catch (Exception ex) {
			throw new IllegalStateException("couldn't read the weapon table", ex);
		}
		return out;
	}

	private static Map<String, MeleeDef> loadMelee() {
		Map<String, MeleeDef> out = new LinkedHashMap<>();
		if (root.has("melee")) {
			for (JsonElement e : root.getAsJsonArray("melee")) {
				JsonObject o = e.getAsJsonObject();
				out.put(str(o, "id"), new MeleeDef(str(o, "id"), str(o, "name")));
			}
		}
		return out;
	}

	private static Map<String, GrenadeDef> loadGrenades() {
		Map<String, GrenadeDef> out = new LinkedHashMap<>();
		if (root.has("grenades")) {
			for (JsonElement e : root.getAsJsonArray("grenades")) {
				JsonObject o = e.getAsJsonObject();
				Map<String, String> sounds = new LinkedHashMap<>();
				o.getAsJsonObject("sounds").entrySet().forEach(en -> sounds.put(en.getKey(), en.getValue().getAsString()));
				float[] anim = o.has("throwAnim") ? pair(o, "throwAnim") : new float[] {0.7667f, 0.5f};
				out.put(str(o, "id"), new GrenadeDef(str(o, "id"), str(o, "name"), i(o, "price"), f(o, "damage"), f(o, "radius"),
						f(o, "throwVelocity"), f(o, "maxSpeed"), o.has("deploy") ? f(o, "deploy") : 1f, anim, Collections.unmodifiableMap(sounds)));
			}
		}
		return out;
	}

	private static Map<String, Flash> loadFlashes() {
		Map<String, Flash> out = new LinkedHashMap<>();
		if (root.has("flashes")) {
			for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("flashes").entrySet()) {
				JsonObject o = en.getValue().getAsJsonObject();
				boolean fire = o.has("count");
				out.put(en.getKey(), new Flash(b(o, "silenced"), o.has("sparks") ? i(o, "sparks") : 0, fire ? i(o, "count") : 0,
						fire ? pair(o, "radius") : new float[] {1f, 1f}, fire ? pair(o, "alpha") : new float[] {1f, 1f},
						fire ? pair(o, "along") : new float[] {0f, 0f}, fire ? pair(o, "roll") : new float[] {0f, 360f},
						fire ? colors(o) : new int[][] {{255, 255, 255}, {255, 255, 255}}, fire ? f(o, "overbright") : 1f,
						fire ? f(o, "radiusScale") : 1f, fire ? f(o, "life") : 0.05f));
			}
		}
		return out;
	}

	private static Map<String, Tracer> loadTracers() {
		Map<String, Tracer> out = new LinkedHashMap<>();
		if (root.has("tracers")) {
			for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("tracers").entrySet()) {
				if (!en.getValue().isJsonObject()) {
					continue;
				}
				JsonObject o = en.getValue().getAsJsonObject();
				out.put(en.getKey(), new Tracer(f(o, "speed"), f(o, "maxLength"), f(o, "fadeIn"), f(o, "minSize"), f(o, "maxSize"),
						f(o, "radius"), pair(o, "alpha"), colors(o), b(o, "rope")));
			}
		}
		return out;
	}

	private static int[][] colors(JsonObject o) {
		JsonArray c = o.getAsJsonArray("color");
		int[][] out = new int[2][3];
		for (int k = 0; k < 2; k++) {
			JsonArray a = c.get(Math.min(k, c.size() - 1)).getAsJsonArray();
			for (int j = 0; j < 3; j++) {
				out[k][j] = a.get(j).getAsInt();
			}
		}
		return out;
	}

	private static GunSpec.ShotFx shotFx(JsonObject o) {
		if (!o.has("fx") || !o.get("fx").isJsonObject()) {
			return new GunSpec.ShotFx(null, null, null, new int[] {0, 0}, new float[][] {{0, 0, 0}, {0, 0, 0}});
		}
		JsonObject fx = o.getAsJsonObject("fx");
		float[] freq = pair(fx, "tracerFrequency");
		JsonArray m = fx.getAsJsonArray("muzzle");
		float[][] muzzle = new float[2][3];
		for (int k = 0; k < 2; k++) {
			JsonArray a = m.get(Math.min(k, m.size() - 1)).getAsJsonArray();
			for (int j = 0; j < 3; j++) {
				muzzle[k][j] = a.get(j).getAsFloat();
			}
		}
		return new GunSpec.ShotFx(str(fx, "flash"), str(fx, "flashSilenced"), str(fx, "tracer"), new int[] {(int) freq[0], (int) freq[1]}, muzzle);
	}

	private static GunSpec parse(JsonObject o) {
		JsonObject inacc = o.getAsJsonObject("inaccuracy");
		JsonObject rec = o.getAsJsonObject("recovery");
		AccuracySpec acc = new AccuracySpec(pair(o, "spread"), pair(inacc, "stand"), pair(inacc, "crouch"), pair(inacc, "move"),
				pair(inacc, "jump"), pair(inacc, "fire"), f(rec, "stand"), f(rec, "crouch"), f(rec, "standFinal"), f(rec, "crouchFinal"),
				i(rec, "transitionStart"), i(rec, "transitionEnd"), pair(o, "maxSpeed"), pair(inacc, "ladder"), pair(inacc, "land"),
				inacc.has("jumpInitial") ? f(inacc, "jumpInitial") : 0f);
		if ("deagle".equals(str(o, "id"))) {
			acc = tunedDeagle(acc);
		}
		JsonObject z = o.getAsJsonObject("zoom");
		JsonArray fov = z.getAsJsonArray("fov");
		GunSpec.Zoom zoom = new GunSpec.Zoom(i(z, "levels"), new int[] {fov.get(0).getAsInt(), fov.get(1).getAsInt()},
				b(z, "unzoomAfterShot"), b(z, "hideViewmodel"), b(z, "crosshair"));
		JsonObject bu = o.getAsJsonObject("burst");
		GunSpec.Burst burst = new GunSpec.Burst(b(bu, "enabled"), f(bu, "between"), f(bu, "cycle"));
		JsonObject s = o.getAsJsonObject("sounds");
		GunSpec.Sounds sounds = new GunSpec.Sounds(str(s, "fire"), str(s, "fireUnsilenced"), str(s, "zoomIn"), str(s, "zoomOut"));
		float[] cycle = pair(o, "cycle");
		int clip = i(o, "clip");
		GunSpec.Ironsight ironsight = null;
		if (o.has("ironsight") && o.get("ironsight").isJsonObject()) {
			JsonObject is = o.getAsJsonObject("ironsight");
			ironsight = new GunSpec.Ironsight(f(is, "pullUp"), f(is, "putDown"), f(is, "fov"));
		}
		GunSpec.ShellReload shells = null;
		if (o.has("shellReload") && o.get("shellReload").isJsonObject()) {
			JsonObject sr = o.getAsJsonObject("shellReload");
			shells = new GunSpec.ShellReload(f(sr, "intro"), f(sr, "loop"), f(sr, "add"), f(sr, "outro"), f(sr, "duration"));
		}
		return new GunSpec(str(o, "id"), str(o, "name"), str(o, "type"), str(o, "slot"), i(o, "price"), i(o, "killAward"),
				f(o, "damage"), i(o, "pellets"), f(o, "armorRatio"), f(o, "penetration"), f(o, "range"), f(o, "rangeModifier"),
				f(o, "headshot"), cycle, b(o, "auto"), clip, i(o, "reserve"), f(o, "reloadTime"), b(o, "singleShellReload"),
				f(o, "deploy"), pair(o, "maxSpeed"), f(o, "attackMoveSpeedFactor"), acc, zoom, str(o, "silencer"), burst,
				b(o, "revolver"), b(o, "exhaustible"), sounds, ironsight, shells, recoil(o, clip, cycle[0]), shotFx(o));
	}

	/** Desert Eagle tuning on top of CS2's data: standing first-shot inaccuracy, firing penalty, recovery time. */
	static final float DEAGLE_STAND = 2.0f;
	static final float DEAGLE_FIRE = 1.35f;
	static final float DEAGLE_RECOVERY = 1.25f;

	/**
	 * Play-testing found the Desert Eagle too accurate standing still and too forgiving to spam with Counter-Craft's
	 * inaccuracy model, so it gets more standing inaccuracy, a bigger penalty per shot and a slower recovery.
	 * ponytail: set by feel against CS2, not measured; measure the Deagle in CS2 and replace these multipliers.
	 */
	private static AccuracySpec tunedDeagle(AccuracySpec a) {
		return new AccuracySpec(a.spread(), scale(a.standing(), DEAGLE_STAND), a.crouching(), a.moving(), a.jumping(), scale(a.firing(), DEAGLE_FIRE),
				a.recoveryStand() * DEAGLE_RECOVERY, a.recoveryCrouch() * DEAGLE_RECOVERY, a.recoveryStandFinal() * DEAGLE_RECOVERY,
				a.recoveryCrouchFinal() * DEAGLE_RECOVERY, a.transitionStart(), a.transitionEnd(), a.maxSpeed(), a.ladder(), a.landing(), a.jumpInitial());
	}

	private static float[] scale(float[] pair, float k) {
		return new float[] {pair[0] * k, pair[1] * k};
	}

	private static RecoilPattern recoil(JsonObject o, int clip, float cycle) {
		RecoilPattern pattern = bulletRecoil(o, clip, cycle);
		JsonElement pu = o.get("punch");
		if (pu != null && pu.isJsonObject()) {
			JsonObject p = pu.getAsJsonObject();
			JsonArray c = p.getAsJsonArray("curve");
			float[][] curve = new float[c.size()][2];
			for (int k = 0; k < c.size(); k++) {
				curve[k][0] = c.get(k).getAsJsonArray().get(0).getAsFloat();
				curve[k][1] = c.get(k).getAsJsonArray().get(1).getAsFloat();
			}
			float[] mag = pair(o.getAsJsonObject("recoilParams"), "magnitude");
			pattern.withCurve(curve, f(p, "fps"), b(p, "bullets"), mag[0] > 0 ? mag[1] / mag[0] : 1f);
		}
		return pattern;
	}

	private static RecoilPattern bulletRecoil(JsonObject o, int clip, float cycle) {
		JsonElement r = o.get("recoil");
		if (r != null && r.isJsonObject()) {
			JsonObject m = r.getAsJsonObject();
			JsonArray pat = m.getAsJsonArray("pattern");
			JsonArray times = m.getAsJsonArray("times");
			float[][] offsets = new float[pat.size()][2];
			double[] t = new double[pat.size()];
			for (int k = 0; k < pat.size(); k++) {
				offsets[k][0] = pat.get(k).getAsJsonArray().get(0).getAsFloat();
				offsets[k][1] = pat.get(k).getAsJsonArray().get(1).getAsFloat();
				t[k] = times.get(k).getAsDouble();
			}
			// recovery was fitted on camera degrees; bullets move 1/CAMERA_RATIO as far
			return RecoilPattern.measured(offsets, t, m.get("decayExp").getAsDouble(),
					m.get("decayLinCamera").getAsDouble() / RecoilPattern.CAMERA_RATIO);
		}
		JsonObject p = o.getAsJsonObject("recoilParams");
		return RecoilPattern.approximate(pair(p, "angle")[0], pair(p, "angleVariance")[0], pair(p, "magnitude")[0],
				pair(p, "magnitudeVariance")[0], i(p, "seed"), Math.max(clip, 1), cycle);
	}

	private static float[] pair(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			return new float[] {a.get(0).getAsFloat(), a.size() > 1 ? a.get(1).getAsFloat() : a.get(0).getAsFloat()};
		}
		return new float[] {e.getAsFloat(), e.getAsFloat()};
	}

	private static float f(JsonObject o, String k) {
		return o.get(k).getAsFloat();
	}

	private static int i(JsonObject o, String k) {
		return o.get(k).getAsInt();
	}

	private static boolean b(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}
}
