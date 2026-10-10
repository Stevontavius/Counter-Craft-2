package dev.countercraft.client.agent.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.countercraft.client.agent.AgentClip;
import dev.countercraft.client.agent.graph.GraphInstance.Ctx;
import dev.countercraft.client.agent.graph.GraphInstance.GraphEvent;
import dev.countercraft.client.agent.graph.GraphInstance.SampledEvent;
import java.util.List;

/** Value nodes of CS2's animation graphs: parameters, constants, comparisons, logic, curves, springs, bone masks, conditions. */
final class Values {
	private Values() {
	}

	abstract static class ValueNode extends Nodes.Node {
		boolean b(Ctx c) {
			return f(c) != 0f;
		}

		float f(Ctx c) {
			return b(c) ? 1f : 0f;
		}

		String id(Ctx c) {
			return "";
		}

		float[] mask(Ctx c) {
			return null;
		}
	}

	/** A value node with state that must advance exactly once per frame. */
	abstract static class FrameValue extends ValueNode {
		private long frame = -1;
		private long lastFrame = -2;
		float value;

		@Override
		final float f(Ctx c) {
			if (frame != c.frame) {
				boolean fresh = lastFrame != c.frame - 1;
				lastFrame = frame = c.frame;
				value = step(c, fresh);
			}
			return value;
		}

		@Override
		final boolean b(Ctx c) {
			return f(c) != 0f;
		}

		/** {@code fresh}: the node was not evaluated last frame (its branch just became active). */
		abstract float step(Ctx c, boolean fresh);
	}

	static ValueNode create(GraphInstance g, String cls, JsonObject j, int index) {
		return switch (cls) {
			case "ControlParameterFloat", "ControlParameterBool", "ControlParameterID", "ControlParameterTarget" -> {
				ValueNode v = g.virtualParam(g.def.params[index]);
				yield v != null ? v : new Param(g.params, g.def.params[index]);
			}
			case "ConstFloat" -> new ConstF(GraphDef.f(j, "m_flValue", 0f));
			case "ConstID" -> new ConstId(GraphDef.s(j, "m_value"));
			case "IDComparison" -> new IdCompare(g, j);
			case "FloatComparison" -> new FloatCompare(g, j);
			case "FloatRangeComparison" -> new RangeCompare(g, j);
			case "And" -> new Logic(g, GraphDef.ints(j, "m_conditionNodeIndices"), true);
			case "Or" -> new Logic(g, GraphDef.ints(j, "m_conditionNodeIndices"), false);
			case "Not" -> new Not(g, GraphDef.i(j, "m_nInputValueNodeIdx", -1));
			case "IDToFloat" -> new IdToFloat(g, j);
			case "FloatCurve" -> new Curve(g, j);
			case "FloatSpring" -> new Spring(g, j);
			case "FloatEase" -> new Ease(g, j);
			case "FloatSwitch" -> new FloatSwitch(g, j);
			case "FloatRemap" -> new Remap(g, j);
			case "CachedFloat" -> new Cached(g, j);
			case "IDEventCondition" -> new IdEventCondition(g, j);
			case "GraphEventCondition" -> new GraphEventCondition(g, j);
			case "StateCompletedCondition" -> new StateCompleted(g, j);
			case "TimeCondition" -> new TimeCondition(g, j);
			case "CurrentSyncEventID" -> new CurrentSyncEvent(g, j);
			case "BoneMask" -> new Mask(g, GraphDef.s(j, "m_boneMaskID"));
			case "BoneMaskSelector" -> new MaskSelector(g, j);
			case "BoneMaskSwitch" -> new MaskSwitch(g, j);
			default -> null;
		};
	}

	// ------------------------------------------------------------------ parameters / constants

	static final class Param extends ValueNode {
		private final GraphInstance.Params params;
		private final String name;

		Param(GraphInstance.Params params, String name) {
			this.params = params;
			this.name = name;
		}

		@Override
		float f(Ctx c) {
			return params.f(name);
		}

		@Override
		boolean b(Ctx c) {
			return params.b(name);
		}

		@Override
		String id(Ctx c) {
			return params.id(name);
		}
	}

	static final class ConstF extends ValueNode {
		private final float v;

		ConstF(float v) {
			this.v = v;
		}

		@Override
		float f(Ctx c) {
			return v;
		}
	}

	static final class ConstId extends ValueNode {
		private final String v;

		ConstId(String v) {
			this.v = v;
		}

		@Override
		String id(Ctx c) {
			return v;
		}

		@Override
		boolean b(Ctx c) {
			return !v.isEmpty();
		}
	}

	// ------------------------------------------------------------------ comparisons / logic

	static final class IdCompare extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final boolean matches;
		private final String[] ids;

		IdCompare(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			matches = !"DoesntMatch".equals(GraphDef.s(j, "m_comparison"));
			ids = GraphDef.strs(j, "m_comparisionIDs");
		}

		@Override
		boolean b(Ctx c) {
			ValueNode v = g.value(input);
			String x = v == null ? "" : v.id(c);
			boolean any = false;
			for (String id : ids) {
				if (id.equals(x)) {
					any = true;
					break;
				}
			}
			return matches == any;
		}
	}

	static final class FloatCompare extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final int comparand;
		private final String op;
		private final float value;
		private final float eps;

		FloatCompare(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			comparand = GraphDef.i(j, "m_nComparandValueNodeIdx", -1);
			op = GraphDef.s(j, "m_comparison");
			value = GraphDef.f(j, "m_flComparisonValue", 0f);
			eps = Math.max(1e-4f, GraphDef.f(j, "m_flEpsilon", 0f));
		}

		@Override
		boolean b(Ctx c) {
			ValueNode v = g.value(input);
			float x = v == null ? 0f : v.f(c);
			ValueNode cv = g.value(comparand);
			float y = cv == null ? value : cv.f(c);
			return switch (op) {
				case "GreaterThanEqual" -> x >= y;
				case "LessThanEqual" -> x <= y;
				case "GreaterThan" -> x > y;
				case "LessThan" -> x < y;
				case "NearEqual" -> Math.abs(x - y) <= eps;
				default -> false;
			};
		}
	}

	static final class RangeCompare extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final float min;
		private final float max;
		private final boolean inclusive;

		RangeCompare(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			JsonObject r = j.getAsJsonObject("m_range");
			min = GraphDef.f(r, "m_flMin", 0f);
			max = GraphDef.f(r, "m_flMax", 0f);
			inclusive = GraphDef.b(j, "m_bIsInclusiveCheck");
		}

		@Override
		boolean b(Ctx c) {
			ValueNode v = g.value(input);
			float x = v == null ? 0f : v.f(c);
			return inclusive ? x >= min && x <= max : x > min && x < max;
		}
	}

	static final class Logic extends ValueNode {
		private final GraphInstance g;
		private final int[] in;
		private final boolean and;

		Logic(GraphInstance g, int[] in, boolean and) {
			this.g = g;
			this.in = in;
			this.and = and;
		}

		@Override
		boolean b(Ctx c) {
			for (int i : in) {
				ValueNode v = g.value(i);
				boolean x = v != null && v.b(c);
				if (and && !x) {
					return false;
				}
				if (!and && x) {
					return true;
				}
			}
			return and;
		}
	}

	static final class Not extends ValueNode {
		private final GraphInstance g;
		private final int in;

		Not(GraphInstance g, int in) {
			this.g = g;
			this.in = in;
		}

		@Override
		boolean b(Ctx c) {
			ValueNode v = g.value(in);
			return v == null || !v.b(c);
		}
	}

	// ------------------------------------------------------------------ float operators

	static final class IdToFloat extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final String[] ids;
		private final float[] values;
		private final float dflt;

		IdToFloat(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			ids = GraphDef.strs(j, "m_IDs");
			JsonArray v = j.getAsJsonArray("m_values");
			values = new float[v == null ? 0 : v.size()];
			for (int k = 0; k < values.length; k++) {
				values[k] = v.get(k).getAsFloat();
			}
			dflt = GraphDef.f(j, "m_defaultValue", 0f);
		}

		@Override
		float f(Ctx c) {
			ValueNode v = g.value(input);
			String x = v == null ? "" : v.id(c);
			for (int k = 0; k < ids.length && k < values.length; k++) {
				if (ids[k].equals(x)) {
					return values[k];
				}
			}
			return dflt;
		}
	}

	/** Hermite spline through (x, y) points with in/out slopes, clamped at the ends. */
	static final class Curve extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final float[] x;
		private final float[] y;
		private final float[] sIn;
		private final float[] sOut;

		Curve(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			JsonArray s = j.getAsJsonObject("m_curve").getAsJsonArray("m_spline");
			x = new float[s.size()];
			y = new float[s.size()];
			sIn = new float[s.size()];
			sOut = new float[s.size()];
			for (int k = 0; k < s.size(); k++) {
				JsonObject p = s.get(k).getAsJsonObject();
				x[k] = GraphDef.f(p, "x", 0f);
				y[k] = GraphDef.f(p, "y", 0f);
				sIn[k] = GraphDef.f(p, "m_flSlopeIncoming", 0f);
				sOut[k] = GraphDef.f(p, "m_flSlopeOutgoing", 0f);
			}
		}

		@Override
		float f(Ctx c) {
			ValueNode v = g.value(input);
			float t = v == null ? 0f : v.f(c);
			if (x.length == 0) {
				return 0f;
			}
			if (t <= x[0]) {
				return y[0];
			}
			if (t >= x[x.length - 1]) {
				return y[y.length - 1];
			}
			int k = 0;
			while (k + 1 < x.length && t > x[k + 1]) {
				k++;
			}
			float h = x[k + 1] - x[k];
			float u = (t - x[k]) / h;
			float u2 = u * u, u3 = u2 * u;
			return (2 * u3 - 3 * u2 + 1) * y[k] + (u3 - 2 * u2 + u) * h * sOut[k] + (-2 * u3 + 3 * u2) * y[k + 1] + (u3 - u2) * h * sIn[k + 1];
		}
	}

	/** Damped spring toward the input (hertz, damping ratio). */
	static final class Spring extends FrameValue {
		private final GraphInstance g;
		private final int input;
		private final float hz;
		private final float damping;
		private final float start;
		private final boolean useStart;
		private float vel;

		Spring(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			hz = GraphDef.f(j, "m_flHertz", 1f);
			damping = GraphDef.f(j, "m_flDampingRatio", 1f);
			start = GraphDef.f(j, "m_flStartValue", 0f);
			useStart = GraphDef.b(j, "m_bUseStartValue");
		}

		@Override
		float step(Ctx c, boolean fresh) {
			ValueNode v = g.value(input);
			float target = v == null ? 0f : v.f(c);
			if (fresh) {
				vel = 0f;
				return useStart ? start : target;
			}
			float w = (float) (2 * Math.PI * hz);
			float dt = Math.min(c.dt, 0.1f);
			// semi-implicit damped spring
			float acc = w * w * (target - value) - 2 * damping * w * vel;
			vel += acc * dt;
			return value + vel * dt;
		}
	}

	/** Eases toward the input over a fixed time each time the input changes. */
	static final class Ease extends FrameValue {
		private final GraphInstance g;
		private final int input;
		private final float time;
		private final float start;
		private final boolean useStart;
		private float from;
		private float target;
		private float t;

		Ease(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			time = Math.max(1e-3f, GraphDef.f(j, "m_flEaseTime", 0.2f));
			start = GraphDef.f(j, "m_flStartValue", 0f);
			useStart = GraphDef.b(j, "m_bUseStartValue");
		}

		@Override
		float step(Ctx c, boolean fresh) {
			ValueNode v = g.value(input);
			float in = v == null ? 0f : v.f(c);
			if (fresh) {
				from = useStart ? start : in;
				target = in;
				t = useStart ? 0f : 1f;
			}
			if (Math.abs(in - target) > 1e-5f) {
				from = value;
				target = in;
				t = 0f;
			}
			t = Math.min(1f, t + c.dt / time);
			return from + (target - from) * t;
		}
	}

	static final class FloatSwitch extends ValueNode {
		private final GraphInstance g;
		private final int sw;
		private final int tNode;
		private final int fNode;
		private final float tVal;
		private final float fVal;

		FloatSwitch(GraphInstance g, JsonObject j) {
			this.g = g;
			sw = GraphDef.i(j, "m_nSwitchValueNodeIdx", -1);
			tNode = GraphDef.i(j, "m_nTrueValueNodeIdx", -1);
			fNode = GraphDef.i(j, "m_nFalseValueNodeIdx", -1);
			tVal = GraphDef.f(j, "m_flTrueValue", 1f);
			fVal = GraphDef.f(j, "m_flFalseValue", 0f);
		}

		@Override
		float f(Ctx c) {
			ValueNode s = g.value(sw);
			boolean on = s != null && s.b(c);
			ValueNode v = g.value(on ? tNode : fNode);
			return v != null ? v.f(c) : on ? tVal : fVal;
		}
	}

	static final class Remap extends ValueNode {
		private final GraphInstance g;
		private final int input;
		private final float ia;
		private final float ib;
		private final float oa;
		private final float ob;

		Remap(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			JsonObject i = j.getAsJsonObject("m_inputRange");
			JsonObject o = j.getAsJsonObject("m_outputRange");
			ia = GraphDef.f(i, "m_flBegin", 0f);
			ib = GraphDef.f(i, "m_flEnd", 1f);
			oa = GraphDef.f(o, "m_flBegin", 0f);
			ob = GraphDef.f(o, "m_flEnd", 1f);
		}

		@Override
		float f(Ctx c) {
			ValueNode v = g.value(input);
			float x = v == null ? 0f : v.f(c);
			float t = ib != ia ? (x - ia) / (ib - ia) : 0f;
			t = Math.max(0f, Math.min(1f, t));
			return oa + (ob - oa) * t;
		}
	}

	/** OnEntry: the input's value when its branch became active. OnExit: live, but held while the branch blends out. */
	static final class Cached extends FrameValue {
		private final GraphInstance g;
		private final int input;
		private final boolean onEntry;

		Cached(GraphInstance g, JsonObject j) {
			this.g = g;
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			onEntry = "OnEntry".equals(GraphDef.s(j, "m_mode"));
		}

		@Override
		float step(Ctx c, boolean fresh) {
			ValueNode v = g.value(input);
			float live = v == null ? 0f : v.f(c);
			if (onEntry) {
				return fresh ? live : value;
			}
			return c.exiting && !fresh ? value : live;
		}
	}

	// ------------------------------------------------------------------ conditions on events and states

	static final class IdEventCondition extends ValueNode {
		private final GraphInstance g;
		private final int sourceState;
		private final String[] ids;
		private final int rules;

		IdEventCondition(GraphInstance g, JsonObject j) {
			this.g = g;
			sourceState = GraphDef.i(j, "m_nSourceStateNodeIdx", -1);
			ids = GraphDef.strs(j, "m_eventIDs");
			JsonObject r = j.getAsJsonObject("m_eventConditionRules");
			rules = r == null ? 0 : GraphDef.i(r, "m_flags", 0);
		}

		@Override
		boolean b(Ctx c) {
			Object state = sourceState >= 0 ? g.node(sourceState) : null;
			boolean and = (rules & 32) != 0;
			boolean graphToo = (rules & (64 | 256)) != 0;
			int found = 0;
			for (String id : ids) {
				if (has(c.events, id, state) || has(c.prevEvents, id, state) || graphToo && hasGraph(c, id, state)) {
					found++;
					if (!and) {
						return true;
					}
				}
			}
			return and && found == ids.length && ids.length > 0;
		}

		private static boolean has(List<SampledEvent> list, String id, Object state) {
			for (SampledEvent e : list) {
				if (e.id().equals(id) && (state == null || contains(e.states(), state))) {
					return true;
				}
			}
			return false;
		}

		private static boolean hasGraph(Ctx c, String id, Object state) {
			for (GraphEvent e : c.graphEvents) {
				if (e.id().equals(id) && (state == null || contains(e.states(), state))) {
					return true;
				}
			}
			return false;
		}
	}

	static boolean contains(Object[] states, Object s) {
		for (Object o : states) {
			if (o == s) {
				return true;
			}
		}
		return false;
	}

	static final class GraphEventCondition extends ValueNode {
		private final GraphInstance g;
		private final int sourceState;
		private final String[] ids;
		private final String[] types;

		GraphEventCondition(GraphInstance g, JsonObject j) {
			this.g = g;
			sourceState = GraphDef.i(j, "m_nSourceStateNodeIdx", -1);
			JsonArray a = j.getAsJsonArray("m_conditions");
			ids = new String[a == null ? 0 : a.size()];
			types = new String[ids.length];
			for (int k = 0; k < ids.length; k++) {
				JsonObject o = a.get(k).getAsJsonObject();
				ids[k] = GraphDef.s(o, "m_eventID");
				types[k] = GraphDef.s(o, "m_eventTypeCondition");
			}
		}

		@Override
		boolean b(Ctx c) {
			Object state = sourceState >= 0 ? g.node(sourceState) : null;
			for (int k = 0; k < ids.length; k++) {
				for (List<GraphEvent> list : List.of(c.graphEvents, c.prevGraphEvents)) {
					for (GraphEvent e : list) {
						if (e.id().equals(ids[k]) && typeOk(types[k], e.type()) && (state == null || contains(e.states(), state))) {
							return true;
						}
					}
				}
			}
			return false;
		}

		private static boolean typeOk(String want, int type) {
			return switch (want) {
				case "Entry" -> type == GraphEvent.ENTRY;
				case "Exit" -> type == GraphEvent.EXIT;
				case "FullyInState", "Execute" -> type == GraphEvent.EXECUTE;
				default -> true;
			};
		}
	}

	static final class StateCompleted extends ValueNode {
		private final GraphInstance g;
		private final int state;
		private final int durationNode;
		private final float seconds;

		StateCompleted(GraphInstance g, JsonObject j) {
			this.g = g;
			state = GraphDef.i(j, "m_nSourceStateNodeIdx", -1);
			durationNode = GraphDef.i(j, "m_nTransitionDurationOverrideNodeIdx", -1);
			seconds = GraphDef.f(j, "m_flTransitionDurationSeconds", 0f);
		}

		@Override
		boolean b(Ctx c) {
			Nodes.Node n = g.node(state);
			if (!(n instanceof Nodes.StateNode s) || !s.active) {
				return false;
			}
			ValueNode d = g.value(durationNode);
			return s.completes(d != null ? d.f(c) : seconds);
		}
	}

	static final class TimeCondition extends ValueNode {
		private final GraphInstance g;
		private final int state;
		private final int input;
		private final float comparand;
		private final String op;

		TimeCondition(GraphInstance g, JsonObject j) {
			this.g = g;
			state = GraphDef.i(j, "m_sourceStateNodeIdx", -1);
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			comparand = GraphDef.f(j, "m_flComparand", 0f);
			op = GraphDef.s(j, "m_operator");
		}

		@Override
		boolean b(Ctx c) {
			Nodes.Node n = g.node(state);
			if (!(n instanceof Nodes.StateNode s)) {
				return false;
			}
			ValueNode v = g.value(input);
			float y = v != null ? v.f(c) : comparand;
			float x = s.elapsed;
			return switch (op) {
				case "LessThan" -> x < y;
				case "LessThanEqual" -> x <= y;
				case "GreaterThan" -> x > y;
				case "GreaterThanEqual" -> x >= y;
				default -> false;
			};
		}
	}

	static final class CurrentSyncEvent extends ValueNode {
		private final GraphInstance g;
		private final int state;

		CurrentSyncEvent(GraphInstance g, JsonObject j) {
			this.g = g;
			state = GraphDef.i(j, "m_nSourceStateNodeIdx", -1);
		}

		@Override
		String id(Ctx c) {
			Nodes.Node n = g.node(state);
			if (!(n instanceof Nodes.StateNode s)) {
				return "";
			}
			List<AgentClip.Event> track = s.syncTrack();
			if (track == null) {
				return "";
			}
			for (AgentClip.Event e : track) {
				if (s.cur >= e.start() && s.cur <= e.start() + e.duration()) {
					return e.id();
				}
			}
			return "";
		}
	}

	// ------------------------------------------------------------------ bone masks

	static final class Mask extends ValueNode {
		private final float[] weights;

		Mask(GraphInstance g, String id) {
			weights = g.source.skeleton().masks.get(id);
		}

		@Override
		float[] mask(Ctx c) {
			return weights;
		}
	}

	static final class MaskSelector extends ValueNode {
		private final GraphInstance g;
		private final int dflt;
		private final int param;
		private final int[] masks;
		private final String[] values;

		MaskSelector(GraphInstance g, JsonObject j) {
			this.g = g;
			dflt = GraphDef.i(j, "m_defaultMaskNodeIdx", -1);
			param = GraphDef.i(j, "m_parameterValueNodeIdx", -1);
			masks = GraphDef.ints(j, "m_maskNodeIndices");
			values = GraphDef.strs(j, "m_parameterValues");
		}

		@Override
		float[] mask(Ctx c) {
			ValueNode p = g.value(param);
			String x = p == null ? "" : p.id(c);
			for (int k = 0; k < values.length && k < masks.length; k++) {
				if (values[k].equals(x)) {
					ValueNode m = g.value(masks[k]);
					return m == null ? null : m.mask(c);
				}
			}
			ValueNode m = g.value(dflt);
			return m == null ? null : m.mask(c);
		}
	}

	static final class MaskSwitch extends ValueNode {
		private final GraphInstance g;
		private final int sw;
		private final int t;
		private final int f;

		MaskSwitch(GraphInstance g, JsonObject j) {
			this.g = g;
			sw = GraphDef.i(j, "m_nSwitchValueNodeIdx", -1);
			t = GraphDef.i(j, "m_nTrueValueNodeIdx", -1);
			f = GraphDef.i(j, "m_nFalseValueNodeIdx", -1);
		}

		@Override
		float[] mask(Ctx c) {
			ValueNode s = g.value(sw);
			ValueNode m = g.value(s != null && s.b(c) ? t : f);
			return m == null ? null : m.mask(c);
		}
	}

	static float[] floats(JsonElement e) {
		JsonArray a = e.getAsJsonArray();
		float[] out = new float[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsFloat();
		}
		return out;
	}
}
