package dev.countercraft.client.agent.graph;

import com.google.gson.JsonObject;
import dev.countercraft.client.agent.AgentClip;
import dev.countercraft.client.agent.AgentSkeleton;
import dev.countercraft.client.agent.Pose;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A running copy of a compiled CS2 animation graph. Node instances are created on first use from the definitions;
 * referenced graphs (per weapon / locomotion set) are child instances sharing the control parameters by name.
 *
 * <p>The node semantics follow the animation system CS2 uses (state machines with entry overrides and timed
 * transitions, synchronised 1D/2D blend spaces, layered additive / model-space / overlay blends with bone masks,
 * clip ID events and graph events feeding conditions). CS2's code-driven nodes (AimCS, SnapWeapon, FootIK) pass their
 * child through here; the agent animator applies its own aim afterwards.
 */
public final class GraphInstance {
	/** What a graph needs from the outside: clips, other graphs, the skeleton. */
	public interface Source {
		AgentClip clip(String id);

		GraphDef graph(String name);

		AgentSkeleton skeleton();
	}

	/** Control parameter values shared by a graph and its referenced graphs (by parameter name). */
	public static final class Params {
		final Map<String, Object> values = new HashMap<>();

		public void set(String name, Object v) {
			values.put(name, v);
		}

		public Object get(String name) {
			return values.get(name);
		}

		float f(String name) {
			Object v = values.get(name);
			if (v instanceof Number n) {
				return n.floatValue();
			}
			if (v instanceof Boolean b) {
				return b ? 1f : 0f;
			}
			return 0f;
		}

		boolean b(String name) {
			Object v = values.get(name);
			return v instanceof Boolean b ? b : v instanceof Number n && n.floatValue() != 0f;
		}

		String id(String name) {
			Object v = values.get(name);
			return v instanceof String s ? s : "";
		}
	}

	/** An ID event sampled from a clip this frame, with the states it was sampled under. */
	record SampledEvent(String id, Object[] states) {
	}

	/** A graph event (state entry / execute / exit / timed, or a clip node's graph events) this frame. */
	record GraphEvent(String id, int type, Object[] states) {
		static final int ENTRY = 0;
		static final int EXECUTE = 1;
		static final int EXIT = 2;
		static final int TIMED = 3;
	}

	/** Per-update context. */
	public static final class Ctx {
		public float dt;
		long frame;
		final Source source;
		final AgentSkeleton skel;
		final List<SampledEvent> events = new ArrayList<>();
		final List<SampledEvent> prevEvents = new ArrayList<>();
		final List<GraphEvent> graphEvents = new ArrayList<>();
		final List<GraphEvent> prevGraphEvents = new ArrayList<>();
		final ArrayList<Object> states = new ArrayList<>();
		/** True while updating the source side of a transition (CachedFloat OnExit holds its value then). */
		boolean exiting;
		/** Layer context: while updating a layer's input, states multiply in their layer weight and add their bone mask. */
		boolean inLayer;
		float layerWeight = 1f;
		float[] layerMask;
		private final ArrayList<Pose> pool = new ArrayList<>();
		private int poolUsed;
		final float[] scratchA;
		final float[] scratchB;
		final float[] scratchO;

		public Ctx(Source source) {
			this.source = source;
			this.skel = source.skeleton();
			int n = skel.bones() * 4;
			scratchA = new float[n];
			scratchB = new float[n];
			scratchO = new float[n];
		}

		Pose acquire() {
			if (poolUsed == pool.size()) {
				pool.add(new Pose(skel.bones()));
			}
			return pool.get(poolUsed++);
		}

		void release(Pose p) {
			poolUsed--;
		}

		Object[] stateSnapshot() {
			return states.toArray();
		}

		void beginFrame(float dt) {
			this.dt = dt;
			frame++;
			prevEvents.clear();
			prevEvents.addAll(events);
			events.clear();
			prevGraphEvents.clear();
			prevGraphEvents.addAll(graphEvents);
			graphEvents.clear();
			states.clear();
			poolUsed = 0;
		}
	}

	final GraphDef def;
	final Params params;
	final Source source;
	private final Nodes.Node[] nodes;
	final Map<Integer, GraphInstance> children = new HashMap<>();
	/** The graph that references this one (its virtual parameters are seen here as control parameters), or null. */
	GraphInstance parent;
	private static final Set<String> WARNED = new HashSet<>();

	public GraphInstance(GraphDef def, Params params, Source source) {
		this.def = def;
		this.params = params;
		this.source = source;
		this.nodes = new Nodes.Node[def.nodes.length];
	}

	public Nodes.PoseNode root() {
		return (Nodes.PoseNode) node(def.root);
	}

	/** Evaluate the graph for one frame into {@code out}. */
	public void evaluate(Ctx c, float dt, Pose out) {
		c.beginFrame(dt);
		Nodes.PoseNode root = root();
		if (root == null) {
			out.setReference(c.skel);
			return;
		}
		if (!root.active) {
			root.init(c, 0f);
		}
		root.update(c, out);
	}

	/** Debug: the active states, layers and referenced graphs. */
	public String describe(Ctx c) {
		StringBuilder sb = new StringBuilder();
		Nodes.describe(c, root(), sb, 0);
		return sb.toString();
	}

	/** A virtual parameter of this graph or a referencing one (e.g. move_crouch_amount_eased), as a value node. */
	Values.ValueNode virtualParam(String name) {
		for (GraphInstance g = parent; g != null; g = g.parent) {
			int[] nodes = g.def.virtualParamNodes;
			for (int k = 0; k < g.def.virtualParams.length && k < nodes.length; k++) {
				if (g.def.virtualParams[k].equals(name)) {
					return g.value(nodes[k]);
				}
			}
		}
		return null;
	}

	Nodes.Node node(int i) {
		if (i < 0 || i >= nodes.length) {
			return null;
		}
		Nodes.Node n = nodes[i];
		if (n == null) {
			n = create(i);
			nodes[i] = n;
		}
		return n;
	}

	Nodes.PoseNode pose(int i) {
		Nodes.Node n = node(i);
		return n instanceof Nodes.PoseNode p ? p : null;
	}

	Values.ValueNode value(int i) {
		Nodes.Node n = node(i);
		return n instanceof Values.ValueNode v ? v : null;
	}

	AgentClip clip(int slot) {
		if (slot < 0 || slot >= def.clipSlots.length || def.clipSlots[slot] == null) {
			return null;
		}
		return source.clip(def.clipSlots[slot]);
	}

	GraphInstance child(int refIdx) {
		return children.computeIfAbsent(refIdx, k -> {
			if (k < 0 || k >= def.refSlots.length) {
				return null;
			}
			String g = def.graphSlots[def.refSlots[k]];
			GraphDef d = g == null ? null : source.graph(g);
			if (d == null) {
				return null;
			}
			GraphInstance child = new GraphInstance(d, params, source);
			child.parent = this;
			return child;
		});
	}

	private Nodes.Node create(int i) {
		JsonObject j = def.nodes[i];
		String cls = j.get("_class").getAsString().replace("CNm", "").replace("Node::CDefinition", "");
		Nodes.Node n = switch (cls) {
			case "Clip" -> new Nodes.ClipNode(this, j);
			case "AnimationPose" -> new Nodes.AnimationPoseNode(this, j);
			case "Blend1D" -> new Nodes.Blend1DNode(this, j);
			case "Blend2D" -> new Nodes.Blend2DNode(this, j);
			case "StateMachine" -> new Nodes.StateMachineNode(this, j);
			case "State" -> new Nodes.StateNode(this, j);
			case "DurationScale" -> new Nodes.ScaleNode(this, j, true);
			case "SpeedScale" -> new Nodes.ScaleNode(this, j, false);
			case "Selector", "ClipSelector" -> new Nodes.SelectorNode(this, j);
			case "ParameterizedClipSelector" -> new Nodes.ParamSelectorNode(this, j);
			case "LayerBlend" -> new Nodes.LayerBlendNode(this, j);
			case "ReferencedGraph" -> new Nodes.ReferencedGraphNode(this, j);
			case "ZeroPose" -> new Nodes.ZeroPoseNode();
			case "SnapWeapon", "AimCS", "FootIK" -> new Nodes.PassThroughNode(this, j);
			case "Transition" -> null; // read by state machines
			default -> Values.create(this, cls, j, i);
		};
		if (n == null && !"Transition".equals(cls) && WARNED.add(cls)) {
			org.slf4j.LoggerFactory.getLogger("countercraft").warn("[countercraft] agent graph: unsupported node {} ({})", cls, def.path(i));
		}
		if (n != null) {
			n.index = i;
		}
		return n;
	}
}
