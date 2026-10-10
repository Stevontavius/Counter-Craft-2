package dev.countercraft.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.client.agent.graph.GraphDef;
import dev.countercraft.client.agent.graph.GraphInstance;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything converter/agent/agent.py wrote under assets/countercraft/agents/: the animation skeleton, the clip
 * archive (parsed lazily), the compiled graphs (parsed lazily), agent meshes and gun skeletons.
 */
public final class AgentData implements GraphInstance.Source {
	/** Reads a file under agents/ (e.g. "skeleton.json"), or throws. */
	public interface Opener {
		byte[] open(String path) throws IOException;
	}

	/** A gun model's bones and rest pose (3x4 world matrices in model space). */
	public record GunSkeleton(String[] bones, int[] parents, float[] rest) {
		public int index(String name) {
			for (int i = 0; i < bones.length; i++) {
				if (bones[i].equals(name)) {
					return i;
				}
			}
			return -1;
		}
	}

	private final Opener opener;
	private final AgentSkeleton skeleton;
	private final ByteBuffer clipBytes;
	private final Map<String, int[]> clipIndex = new HashMap<>();
	private final Map<String, AgentClip> clips = new HashMap<>();
	private final Map<String, Optional<GraphDef>> graphs = new HashMap<>();
	private final Map<String, Optional<AgentModel>> models = new HashMap<>();
	private final Map<String, Optional<GunSkeleton>> guns = new HashMap<>();
	public final String rootGraph;

	private AgentData(Opener opener) throws IOException {
		this.opener = opener;
		skeleton = new AgentSkeleton(json("skeleton.json"));
		JsonObject index = json("index.json");
		rootGraph = index.has("root") ? index.get("root").getAsString() : "worldmodel";
		clipBytes = ByteBuffer.wrap(opener.open("clips.bin")).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		clipBytes.get(magic);
		if (!"CAB1".equals(new String(magic, StandardCharsets.US_ASCII))) {
			throw new IOException("agents/clips.bin is not a CAB1 archive");
		}
		int n = clipBytes.getInt();
		for (int i = 0; i < n; i++) {
			String name = AgentClip.str(clipBytes);
			int len = clipBytes.getInt();
			clipIndex.put(name, new int[] {clipBytes.position(), len});
			clipBytes.position(clipBytes.position() + len);
		}
	}

	/** Names of every clip in the pack (clips.bin). */
	public java.util.Set<String> clipNames() {
		return java.util.Collections.unmodifiableSet(clipIndex.keySet());
	}

	public static AgentData load(Opener opener) throws IOException {
		return new AgentData(opener);
	}

	private JsonObject json(String path) throws IOException {
		return JsonParser.parseString(new String(opener.open(path), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Override
	public AgentSkeleton skeleton() {
		return skeleton;
	}

	@Override
	public AgentClip clip(String id) {
		AgentClip c = clips.get(id);
		if (c == null) {
			int[] at = clipIndex.get(id);
			if (at == null) {
				return null;
			}
			ByteBuffer b = clipBytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			b.position(at[0]);
			b.limit(at[0] + at[1]);
			c = AgentClip.read(id, b.slice().order(ByteOrder.LITTLE_ENDIAN));
			clips.put(id, c);
		}
		return c;
	}

	@Override
	public GraphDef graph(String name) {
		return graphs.computeIfAbsent(name, n -> {
			try {
				return Optional.of(new GraphDef(json("graphs/" + n + ".json")));
			} catch (IOException | RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger("countercraft").warn("[countercraft] agent graph {} missing: {}", n, e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	public AgentModel model(String agent) {
		return models.computeIfAbsent(agent, a -> {
			try {
				return Optional.of(AgentModel.read(opener.open(a + ".cam")));
			} catch (IOException | RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger("countercraft").warn("[countercraft] agent model {} missing: {}", a, e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	public GunSkeleton gun(String id) {
		return guns.computeIfAbsent(id, g -> {
			try {
				JsonObject j = json("weapons/" + g + ".json");
				JsonArray b = j.getAsJsonArray("bones");
				JsonArray p = j.getAsJsonArray("parents");
				JsonArray r = j.getAsJsonArray("rest");
				String[] bones = new String[b.size()];
				int[] parents = new int[b.size()];
				float[] rest = new float[b.size() * 12];
				for (int i = 0; i < bones.length; i++) {
					bones[i] = b.get(i).getAsString();
					parents[i] = p.get(i).getAsInt();
					JsonArray m = r.get(i).getAsJsonArray();
					for (int k = 0; k < 12; k++) {
						rest[i * 12 + k] = m.get(k).getAsFloat();
					}
				}
				return Optional.of(new GunSkeleton(bones, parents, rest));
			} catch (IOException | RuntimeException e) {
				return Optional.empty();
			}
		}).orElse(null);
	}
}
