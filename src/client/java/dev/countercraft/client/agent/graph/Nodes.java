package dev.countercraft.client.agent.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.countercraft.client.agent.AgentClip;
import dev.countercraft.client.agent.Pose;
import dev.countercraft.client.agent.graph.GraphInstance.Ctx;
import dev.countercraft.client.agent.graph.GraphInstance.GraphEvent;
import dev.countercraft.client.agent.graph.GraphInstance.SampledEvent;
import java.util.List;

/**
 * Pose nodes of CS2's animation graphs. Time is kept as a fraction of the node's duration (CS2 graphs synchronise by
 * sync track; every third-person clip but the shotgun reloads has a single sync event, so a fraction is the sync time).
 * {@link PoseNode#update} advances a node by the frame time on its own; {@link PoseNode#updateSync} moves it to a
 * time range dictated by a parent (blend spaces keep their sources in step that way).
 */
public final class Nodes {
	private Nodes() {
	}

	/** Debug: the active branch under a pose node (state machines' active states, layers, referenced graphs). */
	public static void describe(Ctx c, PoseNode n, StringBuilder sb, int depth) {
		if (n == null || depth > 40) {
			return;
		}
		if (n instanceof StateMachineNode sm) {
			StateNode s = sm.state(sm.activeState);
			sb.append("  ".repeat(depth)).append(sm.activePath()).append(sm.transition != null ? " (blending)" : "")
					.append(String.format(" t=%.2f/%.2fs", sm.cur, sm.duration)).append('\n');
			if (s != null) {
				describe(c, s.childNode(), sb, depth + 1);
			}
		} else if (n instanceof LayerBlendNode lb) {
			describe(c, lb.g.pose(lb.base), sb, depth);
			for (int k = 0; k < lb.inputs.length; k++) {
				PoseNode p = lb.g.pose(lb.inputs[k]);
				float w = lb.lastWeights[k];
				sb.append("  ".repeat(depth)).append("layer ").append(k).append(' ').append(lb.modes[k]).append(String.format(" w=%.2f", w)).append('\n');
				describe(c, p, sb, depth + 1);
			}
		} else if (n instanceof ReferencedGraphNode rg) {
			GraphInstance child = rg.g.child(rg.ref);
			sb.append("  ".repeat(depth)).append("graph ").append(child == null ? "?" : child.def.name).append('\n');
			if (child != null) {
				describe(c, child.root(), sb, depth + 1);
			}
		} else if (n instanceof PassThroughNode pt) {
			describe(c, pt.g.pose(pt.child), sb, depth);
		} else if (n instanceof ScaleNode sc) {
			describe(c, sc.g.pose(sc.child), sb, depth);
		} else if (n instanceof SelectorNode se) {
			describe(c, se.chosenNode(), sb, depth);
		} else if (n instanceof BlendNode bn) {
			sb.append("  ".repeat(depth)).append("blend");
			for (int m = 0; m < bn.count; m++) {
				sb.append(String.format(" %s:%.2f", bn.g.def.path(bn.sources[bn.sel[m]]).replaceAll(".*/", ""), bn.w[m]));
			}
			sb.append(String.format(" t=%.2f d=%.2f", bn.cur, bn.duration)).append('\n');
		}
	}

	static float[] combine(float[] a, float[] b) {
		if (a == null) {
			return b;
		}
		float[] out = new float[a.length];
		for (int i = 0; i < a.length; i++) {
			out[i] = a[i] * b[i];
		}
		return out;
	}

	static final boolean DEBUG = Boolean.getBoolean("agent.debug");

	public abstract static class Node {
		int index = -1;
	}

	public abstract static class PoseNode extends Node {
		/** Seconds for one pass (0 = a static pose). */
		float duration;
		float prev;
		float cur;
		boolean active;
		boolean looping;

		abstract void init(Ctx c, float time);

		void shutdown(Ctx c) {
			active = false;
		}

		abstract void update(Ctx c, Pose out);

		abstract void updateSync(Ctx c, float from, float to, Pose out);

		/** False for a clip node without a clip (selectors with "ignore invalid options" skip it). */
		boolean valid() {
			return true;
		}

		/** Sync track of what is playing (for sync-event matching), or null. */
		List<AgentClip.Event> syncTrack() {
			return null;
		}

		/** Advance own time by dt (fraction), looping or clamping. */
		final void advance(float dt) {
			prev = cur;
			if (duration <= 1e-5f) {
				return;
			}
			cur += dt / duration;
			if (cur > 1f) {
				cur = looping ? cur - (float) Math.floor(cur) : 1f;
			}
		}
	}

	// ------------------------------------------------------------------ clips

	static final class ClipNode extends PoseNode {
		private final GraphInstance g;
		private final AgentClip clip;
		private final float speed;
		private final int resetNode;
		private final String[] graphEvents;

		ClipNode(GraphInstance g, JsonObject j) {
			this.g = g;
			clip = g.clip(GraphDef.i(j, "m_nDataSlotIdx", -1));
			speed = GraphDef.f(j, "m_flSpeedMultiplier", 1f);
			looping = GraphDef.b(j, "m_bAllowLooping");
			resetNode = GraphDef.i(j, "m_nResetTimeValueNodeIdx", -1);
			graphEvents = GraphDef.strs(j, "m_graphEvents");
			duration = clip == null ? 0f : clip.duration / Math.max(1e-3f, speed);
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			prev = cur = Math.max(0f, Math.min(1f, time));
		}

		@Override
		void update(Ctx c, Pose out) {
			Values.ValueNode reset = g.value(resetNode);
			if (reset != null && reset.b(c)) {
				cur = 0f;
			}
			advance(c.dt);
			finish(c, out);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			prev = from;
			cur = to;
			finish(c, out);
		}

		private void finish(Ctx c, Pose out) {
			if (clip == null) {
				out.setReference(c.skel);
				return;
			}
			clip.sample(cur, out);
			sampleEvents(c);
			for (String e : graphEvents) {
				c.graphEvents.add(new GraphEvent(e, GraphEvent.EXECUTE, c.stateSnapshot()));
			}
		}

		private void sampleEvents(Ctx c) {
			if (clip.events.isEmpty()) {
				return;
			}
			boolean wrapped = cur < prev;
			for (AgentClip.Event e : clip.events) {
				float s = e.start();
				float end = s + e.duration();
				boolean hit;
				if (!wrapped) {
					hit = e.duration() > 0 ? s <= cur && end >= prev : (s > prev && s <= cur) || (s == 0f && prev == 0f);
				} else {
					hit = e.duration() > 0 ? end >= prev || s <= cur : s > prev || s <= cur;
				}
				if (hit) {
					c.events.add(new SampledEvent(e.id(), c.stateSnapshot()));
				}
			}
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			return clip == null ? null : clip.sync;
		}

		@Override
		boolean valid() {
			return clip != null;
		}
	}

	static final class AnimationPoseNode extends PoseNode {
		private final GraphInstance g;
		private final AgentClip clip;
		private final int timeNode;
		private final float remapMin;
		private final float remapMax;
		private final float userTime;
		private final boolean frames;

		AnimationPoseNode(GraphInstance g, JsonObject j) {
			this.g = g;
			clip = g.clip(GraphDef.i(j, "m_nDataSlotIdx", -1));
			timeNode = GraphDef.i(j, "m_nPoseTimeValueNodeIdx", -1);
			JsonObject r = j.getAsJsonObject("m_inputTimeRemapRange");
			remapMin = r == null ? 1f : GraphDef.f(r, "m_flMin", 1f);
			remapMax = r == null ? 0f : GraphDef.f(r, "m_flMax", 0f);
			userTime = GraphDef.f(j, "m_flUserSpecifiedTime", 0f);
			frames = GraphDef.b(j, "m_bUseFramesAsInput");
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
		}

		@Override
		void update(Ctx c, Pose out) {
			float t = userTime;
			Values.ValueNode v = g.value(timeNode);
			if (v != null) {
				t = v.f(c);
			}
			if (remapMin <= remapMax) {
				t = (t - remapMin) / Math.max(1e-6f, remapMax - remapMin);
			}
			if (frames && clip != null && clip.frames > 1) {
				t = t / (clip.frames - 1);
			}
			prev = cur = Math.max(0f, Math.min(1f, t));
			if (clip == null) {
				out.setReference(c.skel);
			} else {
				clip.sample(cur, out);
			}
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			update(c, out);
		}
	}

	static final class ZeroPoseNode extends PoseNode {
		@Override
		void init(Ctx c, float time) {
			active = true;
		}

		@Override
		void update(Ctx c, Pose out) {
			out.setZero();
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			out.setZero();
		}
	}

	// ------------------------------------------------------------------ blends

	/** Shared machinery for blend spaces: up to three weighted sources played in step. */
	abstract static class BlendNode extends PoseNode {
		final GraphInstance g;
		final int[] sources;
		final int[] sel = new int[3];
		final float[] w = new float[3];
		int count;
		private final int[] activeSet = new int[3];
		private int activeCount;

		BlendNode(GraphInstance g, int[] sources, boolean loop) {
			this.g = g;
			this.sources = sources;
			this.looping = loop;
		}

		abstract void choose(Ctx c);

		@Override
		void init(Ctx c, float time) {
			active = true;
			prev = cur = time;
			activeCount = 0;
			choose(c);
			refresh(c);
		}

		@Override
		void shutdown(Ctx c) {
			for (int k = 0; k < activeCount; k++) {
				PoseNode p = g.pose(sources[activeSet[k]]);
				if (p != null) {
					p.shutdown(c);
				}
			}
			activeCount = 0;
			super.shutdown(c);
		}

		/** Start new sources at the current time, stop the ones no longer used. */
		private void refresh(Ctx c) {
			for (int k = 0; k < activeCount; k++) {
				boolean keep = false;
				for (int m = 0; m < count; m++) {
					keep |= sel[m] == activeSet[k];
				}
				if (!keep) {
					PoseNode p = g.pose(sources[activeSet[k]]);
					if (p != null) {
						p.shutdown(c);
					}
				}
			}
			for (int m = 0; m < count; m++) {
				PoseNode p = g.pose(sources[sel[m]]);
				if (p != null && !p.active) {
					p.init(c, cur);
				}
			}
			activeCount = count;
			System.arraycopy(sel, 0, activeSet, 0, count);
			float d = 0f;
			for (int m = 0; m < count; m++) {
				PoseNode p = g.pose(sources[sel[m]]);
				if (p != null) {
					d += w[m] * p.duration;
				}
			}
			duration = d;
		}

		@Override
		void update(Ctx c, Pose out) {
			choose(c);
			refresh(c);
			advance(c.dt);
			evaluate(c, out);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			choose(c);
			refresh(c);
			prev = from;
			cur = to;
			evaluate(c, out);
		}

		private void evaluate(Ctx c, Pose out) {
			if (count == 0) {
				out.setReference(c.skel);
				return;
			}
			float acc = 0f;
			boolean first = true;
			for (int m = 0; m < count; m++) {
				PoseNode p = g.pose(sources[sel[m]]);
				if (p == null) {
					continue;
				}
				if (first) {
					p.updateSync(c, prev, cur, out);
					acc = w[m];
					first = false;
				} else {
					Pose tmp = c.acquire();
					p.updateSync(c, prev, cur, tmp);
					acc += w[m];
					if (acc > 1e-6f) {
						Pose.blend(out, tmp, w[m] / acc, null, out);
					}
					c.release(tmp);
				}
			}
			if (first) {
				out.setReference(c.skel);
			}
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			if (count == 0) {
				return null;
			}
			int best = 0;
			for (int m = 1; m < count; m++) {
				if (w[m] > w[best]) {
					best = m;
				}
			}
			PoseNode p = g.pose(sources[sel[best]]);
			return p == null ? null : p.syncTrack();
		}
	}

	static final class Blend1DNode extends BlendNode {
		private final int param;
		private final int[] in0;
		private final int[] in1;
		private final float[] min;
		private final float[] max;

		Blend1DNode(GraphInstance g, JsonObject j) {
			super(g, GraphDef.ints(j, "m_sourceNodeIndices"), GraphDef.b(j, "m_bAllowLooping"));
			param = GraphDef.i(j, "m_nInputParameterValueNodeIdx", -1);
			JsonArray r = j.getAsJsonObject("m_parameterization").getAsJsonArray("m_blendRanges");
			in0 = new int[r.size()];
			in1 = new int[r.size()];
			min = new float[r.size()];
			max = new float[r.size()];
			for (int k = 0; k < r.size(); k++) {
				JsonObject o = r.get(k).getAsJsonObject();
				in0[k] = o.get("m_nInputIdx0").getAsInt();
				in1[k] = o.get("m_nInputIdx1").getAsInt();
				JsonObject v = o.getAsJsonObject("m_parameterValueRange");
				min[k] = v.get("m_flMin").getAsFloat();
				max[k] = v.get("m_flMax").getAsFloat();
			}
		}

		@Override
		void choose(Ctx c) {
			Values.ValueNode v = g.value(param);
			float x = v == null ? 0f : v.f(c);
			if (in0.length == 0) {
				count = sources.length > 0 ? 1 : 0;
				sel[0] = 0;
				w[0] = 1f;
				return;
			}
			int k = 0;
			if (x <= min[0]) {
				one(in0[0]);
				return;
			}
			if (x >= max[in0.length - 1]) {
				one(in1[in0.length - 1]);
				return;
			}
			for (k = 0; k < in0.length; k++) {
				if (x <= max[k]) {
					break;
				}
			}
			k = Math.min(k, in0.length - 1);
			float a = max[k] > min[k] ? (x - min[k]) / (max[k] - min[k]) : 0f;
			a = Math.max(0f, Math.min(1f, a));
			if (a <= 1e-4f) {
				one(in0[k]);
			} else if (a >= 1f - 1e-4f || in0[k] == in1[k]) {
				one(in1[k]);
			} else {
				count = 2;
				sel[0] = in0[k];
				w[0] = 1f - a;
				sel[1] = in1[k];
				w[1] = a;
			}
		}

		private void one(int s) {
			count = 1;
			sel[0] = s;
			w[0] = 1f;
		}
	}

	static final class Blend2DNode extends BlendNode {
		private final int px;
		private final int py;
		private final float[] vx;
		private final float[] vy;
		private final int[] tris;
		private final int[] hull;

		Blend2DNode(GraphInstance g, JsonObject j) {
			super(g, GraphDef.ints(j, "m_sourceNodeIndices"), GraphDef.b(j, "m_bAllowLooping"));
			px = GraphDef.i(j, "m_nInputParameterNodeIdx0", -1);
			py = GraphDef.i(j, "m_nInputParameterNodeIdx1", -1);
			JsonArray v = j.getAsJsonArray("m_values");
			vx = new float[v.size()];
			vy = new float[v.size()];
			for (int k = 0; k < v.size(); k++) {
				vx[k] = v.get(k).getAsJsonArray().get(0).getAsFloat();
				vy[k] = v.get(k).getAsJsonArray().get(1).getAsFloat();
			}
			tris = GraphDef.ints(j, "m_indices");
			hull = GraphDef.ints(j, "m_hullIndices");
		}

		@Override
		void choose(Ctx c) {
			Values.ValueNode a = g.value(px);
			Values.ValueNode b = g.value(py);
			float x = a == null ? 0f : a.f(c);
			float y = b == null ? 0f : b.f(c);
			if (!inTriangle(x, y)) {
				// outside the blend space: clamp to the closest point on the hull
				float bx = x, by = y, bd = Float.MAX_VALUE;
				for (int k = 0; k + 1 < hull.length; k++) {
					float ax = vx[hull[k]], ay = vy[hull[k]], cx = vx[hull[k + 1]], cy = vy[hull[k + 1]];
					float ex = cx - ax, ey = cy - ay;
					float len = ex * ex + ey * ey;
					float t = len > 0 ? Math.max(0f, Math.min(1f, ((x - ax) * ex + (y - ay) * ey) / len)) : 0f;
					float qx = ax + ex * t, qy = ay + ey * t;
					float d = (qx - x) * (qx - x) + (qy - y) * (qy - y);
					if (d < bd) {
						bd = d;
						bx = qx;
						by = qy;
					}
				}
				if (!inTriangle(bx, by)) {
					count = 1;
					sel[0] = 0;
					w[0] = 1f;
				}
			}
		}

		private boolean inTriangle(float x, float y) {
			for (int k = 0; k + 2 < tris.length; k += 3) {
				int i0 = tris[k], i1 = tris[k + 1], i2 = tris[k + 2];
				float x0 = vx[i0], y0 = vy[i0], x1 = vx[i1], y1 = vy[i1], x2 = vx[i2], y2 = vy[i2];
				float den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
				if (Math.abs(den) < 1e-9f) {
					continue;
				}
				float l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den;
				float l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den;
				float l2 = 1f - l0 - l1;
				float eps = -1e-4f;
				if (l0 >= eps && l1 >= eps && l2 >= eps) {
					count = 0;
					add(i0, Math.max(0f, l0));
					add(i1, Math.max(0f, l1));
					add(i2, Math.max(0f, l2));
					float sum = w[0] + (count > 1 ? w[1] : 0f) + (count > 2 ? w[2] : 0f);
					for (int m = 0; m < count; m++) {
						w[m] /= sum;
					}
					return true;
				}
			}
			return false;
		}

		private void add(int s, float weight) {
			if (weight <= 1e-4f) {
				return;
			}
			sel[count] = s;
			w[count] = weight;
			count++;
		}
	}

	// ------------------------------------------------------------------ simple wrappers

	/** DurationScale (the child takes scale x its duration) and SpeedScale (the child plays at a speed). */
	static final class ScaleNode extends PoseNode {
		final GraphInstance g;
		final int child;
		private final int input;
		private final float dflt;
		private final boolean durationScale;

		ScaleNode(GraphInstance g, JsonObject j, boolean durationScale) {
			this.g = g;
			this.durationScale = durationScale;
			child = GraphDef.i(j, "m_nChildNodeIdx", -1);
			input = GraphDef.i(j, "m_nInputValueNodeIdx", -1);
			dflt = GraphDef.f(j, "m_flDefaultInputValue", 1f);
		}

		private float factor(Ctx c) {
			Values.ValueNode v = g.value(input);
			float k = v == null ? dflt : v.f(c);
			return Math.max(1e-3f, k);
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			PoseNode p = g.pose(child);
			if (p != null) {
				p.init(c, time);
			}
			mirror(c);
		}

		@Override
		void shutdown(Ctx c) {
			PoseNode p = g.pose(child);
			if (p != null) {
				p.shutdown(c);
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			PoseNode p = g.pose(child);
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			float k = factor(c);
			float dt = c.dt;
			c.dt = durationScale ? dt / k : dt * k;
			p.update(c, out);
			c.dt = dt;
			mirror(c);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			PoseNode p = g.pose(child);
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			p.updateSync(c, from, to, out);
			mirror(c);
		}

		private void mirror(Ctx c) {
			PoseNode p = g.pose(child);
			if (p == null) {
				return;
			}
			float k = factor(c);
			duration = durationScale ? p.duration * k : p.duration / k;
			prev = p.prev;
			cur = p.cur;
			looping = p.looping;
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			PoseNode p = g.pose(child);
			return p == null ? null : p.syncTrack();
		}
	}

	/** A node CS2 drives from game code (aim, weapon snapping, foot IK): its child passes through here. */
	static final class PassThroughNode extends PoseNode {
		final GraphInstance g;
		final int child;

		PassThroughNode(GraphInstance g, JsonObject j) {
			this.g = g;
			child = GraphDef.i(j, "m_nChildNodeIdx", -1);
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			PoseNode p = g.pose(child);
			if (p != null) {
				p.init(c, time);
			}
		}

		@Override
		void shutdown(Ctx c) {
			PoseNode p = g.pose(child);
			if (p != null) {
				p.shutdown(c);
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			PoseNode p = g.pose(child);
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			p.update(c, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			PoseNode p = g.pose(child);
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			p.updateSync(c, from, to, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
		}
	}

	/** Selector / ClipSelector: the first option whose condition holds when the node starts. */
	static class SelectorNode extends PoseNode {
		final GraphInstance g;
		final int[] options;
		private final int[] conditions;
		int chosen = -1;

		SelectorNode(GraphInstance g, JsonObject j) {
			this.g = g;
			options = GraphDef.ints(j, "m_optionNodeIndices");
			conditions = GraphDef.ints(j, "m_conditionNodeIndices");
		}

		int pick(Ctx c) {
			for (int k = 0; k < options.length; k++) {
				Values.ValueNode v = k < conditions.length ? g.value(conditions[k]) : null;
				if (v != null && v.b(c)) {
					return k;
				}
			}
			return options.length > 0 ? 0 : -1;
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			chosen = pick(c);
			PoseNode p = chosenNode();
			if (p != null) {
				p.init(c, time);
				duration = p.duration;
				looping = p.looping;
			}
		}

		PoseNode chosenNode() {
			return chosen < 0 ? null : g.pose(options[chosen]);
		}

		@Override
		void shutdown(Ctx c) {
			PoseNode p = chosenNode();
			if (p != null) {
				p.shutdown(c);
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			PoseNode p = chosenNode();
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			p.update(c, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			PoseNode p = chosenNode();
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			p.updateSync(c, from, to, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			PoseNode p = chosenNode();
			return p == null ? null : p.syncTrack();
		}
	}

	/** ParameterizedClipSelector: the option indexed by a parameter (or a weighted pick from a 0..1 variation value). */
	static final class ParamSelectorNode extends SelectorNode {
		private final int param;
		private final float[] weights;
		private final boolean ignoreInvalid;

		ParamSelectorNode(GraphInstance g, JsonObject j) {
			super(g, j);
			param = GraphDef.i(j, "m_parameterNodeIdx", -1);
			ignoreInvalid = GraphDef.b(j, "m_bIgnoreInvalidOptions");
			int[] wi = GraphDef.ints(j, "m_optionWeights");
			weights = GraphDef.b(j, "m_bHasWeightsSet") && wi.length > 0 ? new float[wi.length] : null;
			if (weights != null) {
				for (int k = 0; k < wi.length; k++) {
					weights[k] = wi[k];
				}
			}
		}

		@Override
		int pick(Ctx c) {
			if (options.length == 0) {
				return -1;
			}
			Values.ValueNode v = g.value(param);
			float x = v == null ? 0f : v.f(c);
			if (weights != null && x >= 0f && x < 1f) {
				float total = 0f;
				for (int k = 0; k < weights.length && k < options.length; k++) {
					total += usable(k) ? weights[k] : 0f;
				}
				float r = x * total;
				int last = -1;
				for (int k = 0; k < weights.length && k < options.length; k++) {
					if (!usable(k)) {
						continue;
					}
					last = k;
					r -= weights[k];
					if (r < 0f) {
						return k;
					}
				}
				return last >= 0 ? last : 0;
			}
			int k = Math.max(0, Math.min(options.length - 1, Math.round(x)));
			if (!usable(k)) {
				for (int m = 0; m < options.length; m++) {
					if (usable(m)) {
						return m;
					}
				}
			}
			return k;
		}

		private boolean usable(int k) {
			if (!ignoreInvalid) {
				return true;
			}
			PoseNode p = g.pose(options[k]);
			return p != null && p.valid();
		}
	}

	// ------------------------------------------------------------------ state machines

	static final class TimedEvent {
		final String id;
		final float seconds;
		final boolean lessEqual;

		TimedEvent(JsonObject o) {
			id = GraphDef.s(o, "m_ID");
			seconds = GraphDef.f(o, "m_flTimeValueSeconds", 0f);
			lessEqual = GraphDef.s(o, "m_comparisionOperator").startsWith("Less");
		}
	}

	public static final class StateNode extends PoseNode {
		final GraphInstance g;
		private final int child;
		private final String[] entry;
		private final String[] execute;
		private final String[] exit;
		private final TimedEvent[] elapsedEvents;
		private final TimedEvent[] remainingEvents;
		private final int weightNode;
		private final int maskNode;
		final boolean off;
		float elapsed;
		private boolean entered;

		StateNode(GraphInstance g, JsonObject j) {
			this.g = g;
			child = GraphDef.i(j, "m_nChildNodeIdx", -1);
			entry = GraphDef.strs(j, "m_entryEvents");
			execute = GraphDef.strs(j, "m_executeEvents");
			exit = GraphDef.strs(j, "m_exitEvents");
			elapsedEvents = timed(j, "m_timedElapsedEvents");
			remainingEvents = timed(j, "m_timedRemainingEvents");
			weightNode = GraphDef.i(j, "m_nLayerWeightNodeIdx", -1);
			maskNode = GraphDef.i(j, "m_nLayerBoneMaskNodeIdx", -1);
			off = GraphDef.b(j, "m_bIsOffState");
		}

		private static TimedEvent[] timed(JsonObject j, String k) {
			JsonElement e = j.get(k);
			if (e == null || !e.isJsonArray()) {
				return new TimedEvent[0];
			}
			JsonArray a = e.getAsJsonArray();
			TimedEvent[] out = new TimedEvent[a.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = new TimedEvent(a.get(i).getAsJsonObject());
			}
			return out;
		}

		PoseNode childNode() {
			return g.pose(child);
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			elapsed = 0f;
			entered = false;
			PoseNode p = childNode();
			if (p != null) {
				p.init(c, time);
				duration = p.duration;
				looping = p.looping;
				cur = prev = p.cur;
			}
		}

		@Override
		void shutdown(Ctx c) {
			for (String e : exit) {
				c.graphEvents.add(new GraphEvent(e, GraphEvent.EXIT, c.stateSnapshot()));
			}
			PoseNode p = childNode();
			if (p != null) {
				p.shutdown(c);
			}
			super.shutdown(c);
		}

		private void events(Ctx c) {
			c.states.add(this);
			if (c.inLayer) {
				c.layerWeight *= layerWeight(c);
				float[] m = layerMask(c);
				if (m != null) {
					c.layerMask = combine(c.layerMask, m);
				}
			}
			if (!entered) {
				entered = true;
				for (String e : entry) {
					c.graphEvents.add(new GraphEvent(e, GraphEvent.ENTRY, c.stateSnapshot()));
				}
			}
			for (String e : execute) {
				c.graphEvents.add(new GraphEvent(e, GraphEvent.EXECUTE, c.stateSnapshot()));
			}
			for (TimedEvent t : elapsedEvents) {
				if (t.lessEqual ? elapsed <= t.seconds : elapsed >= t.seconds) {
					c.graphEvents.add(new GraphEvent(t.id, GraphEvent.TIMED, c.stateSnapshot()));
				}
			}
			float remaining = duration * (1f - cur);
			for (TimedEvent t : remainingEvents) {
				if (t.lessEqual ? remaining <= t.seconds : remaining >= t.seconds) {
					c.graphEvents.add(new GraphEvent(t.id, GraphEvent.TIMED, c.stateSnapshot()));
				}
			}
		}

		@Override
		void update(Ctx c, Pose out) {
			events(c);
			PoseNode p = childNode();
			if (p == null) {
				out.setReference(c.skel);
			} else {
				p.update(c, out);
				duration = p.duration;
				prev = p.prev;
				cur = p.cur;
			}
			elapsed += c.dt;
			c.states.remove(c.states.size() - 1);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			events(c);
			PoseNode p = childNode();
			if (p == null) {
				out.setReference(c.skel);
			} else {
				p.updateSync(c, from, to, out);
				duration = p.duration;
				prev = p.prev;
				cur = p.cur;
			}
			elapsed += c.dt;
			c.states.remove(c.states.size() - 1);
		}

		float layerWeight(Ctx c) {
			if (off) {
				return 0f;
			}
			Values.ValueNode v = g.value(weightNode);
			return v == null ? 1f : Math.max(0f, Math.min(1f, v.f(c)));
		}

		float[] layerMask(Ctx c) {
			Values.ValueNode v = g.value(maskNode);
			return v == null ? null : v.mask(c);
		}

		/** Whether a non-looping state finishes within {@code within} seconds. */
		boolean completes(float within) {
			if (looping || duration <= 1e-5f) {
				return !looping;
			}
			return (1f - cur) * duration <= within + 1e-4f;
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			PoseNode p = childNode();
			return p == null ? null : p.syncTrack();
		}
	}

	/** A transition in progress: the source (a state or an earlier transition) blends into the target state. */
	static final class Transition extends PoseNode {
		final PoseNode source;
		final StateNode target;
		final float length;
		final String easing;
		float elapsed;

		Transition(PoseNode source, StateNode target, float length, String easing) {
			this.source = source;
			this.target = target;
			this.length = length;
			this.easing = easing;
			active = true;
		}

		float weight() {
			float x = Math.min(1f, elapsed / Math.max(1e-4f, length));
			return switch (easing) {
				case "OutSine" -> (float) Math.sin(x * Math.PI / 2);
				case "OutQuart" -> 1f - (float) Math.pow(1 - x, 4);
				case "OutQuint" -> 1f - (float) Math.pow(1 - x, 5);
				case "InSine" -> 1f - (float) Math.cos(x * Math.PI / 2);
				case "InOutSine" -> -((float) Math.cos(Math.PI * x) - 1f) / 2f;
				default -> x;
			};
		}

		boolean done() {
			return elapsed >= length;
		}

		@Override
		void init(Ctx c, float time) {
		}

		@Override
		void shutdown(Ctx c) {
			source.shutdown(c);
			target.shutdown(c);
			active = false;
		}

		@Override
		void update(Ctx c, Pose out) {
			Pose src = c.acquire();
			boolean ex = c.exiting;
			float lw = c.layerWeight;
			float[] lm = c.layerMask;
			c.exiting = true;
			source.update(c, src);
			c.exiting = ex;
			float sw = c.layerWeight;
			float[] sm = c.layerMask;
			c.layerWeight = lw;
			c.layerMask = lm;
			target.update(c, out);
			blendLayer(c, sw, sm);
			elapsed += c.dt;
			mix(src, out);
			c.release(src);
			mirror();
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			Pose src = c.acquire();
			boolean ex = c.exiting;
			float lw = c.layerWeight;
			float[] lm = c.layerMask;
			c.exiting = true;
			source.updateSync(c, from, to, src);
			c.exiting = ex;
			float sw = c.layerWeight;
			float[] sm = c.layerMask;
			c.layerWeight = lw;
			c.layerMask = lm;
			target.updateSync(c, from, to, out);
			blendLayer(c, sw, sm);
			elapsed += c.dt;
			mix(src, out);
			c.release(src);
			mirror();
		}

		/** Layer context during a transition: the source's and the target's, blended by the transition weight. */
		private void blendLayer(Ctx c, float sw, float[] sm) {
			if (!c.inLayer) {
				return;
			}
			float k = weight();
			float tw = c.layerWeight;
			float[] tm = c.layerMask;
			c.layerWeight = sw + (tw - sw) * k;
			if (sm != null || tm != null) {
				int n = c.skel.bones();
				float[] m = new float[n];
				for (int i = 0; i < n; i++) {
					float x = sm == null ? 1f : sm[i];
					float y = tm == null ? 1f : tm[i];
					m[i] = x + (y - x) * k;
				}
				c.layerMask = m;
			}
		}

		/**
		 * Blend source into target. An off state (or a state without a pose) has no pose of its own: the other side's pose
		 * is used and only the layer weight fades (an off layer state blending with an additive one must not add a
		 * full pose).
		 */
		private void mix(Pose src, Pose out) {
			boolean srcOff = isOff(source);
			boolean tgtOff = isOff(target);
			if (srcOff && !tgtOff) {
				return;
			}
			if (tgtOff && !srcOff) {
				out.copyFrom(src);
				return;
			}
			Pose.blend(src, out, weight(), null, out);
		}

		private static boolean isOff(PoseNode n) {
			return n instanceof StateNode s && (s.off || s.childNode() == null);
		}

		private void mirror() {
			duration = target.duration;
			prev = target.prev;
			cur = target.cur;
			looping = target.looping;
		}

		/** Release the finished source side (and anything it was blending from). */
		void finishSource(Ctx c) {
			source.shutdown(c);
		}

		float layerWeight(Ctx c) {
			float a = source instanceof Transition t ? t.layerWeight(c) : ((StateNode) source).layerWeight(c);
			float b = target.layerWeight(c);
			return a + (b - a) * weight();
		}

		float[] layerMask(Ctx c) {
			float[] a = source instanceof Transition t ? t.layerMask(c) : ((StateNode) source).layerMask(c);
			float[] b = target.layerMask(c);
			if (a == null && b == null) {
				return null;
			}
			int n = c.skel.bones();
			float[] out = new float[n];
			float k = weight();
			for (int i = 0; i < n; i++) {
				float x = a == null ? 1f : a[i];
				float y = b == null ? 1f : b[i];
				out[i] = x + (y - x) * k;
			}
			return out;
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			return target.syncTrack();
		}
	}

	public static final class StateMachineNode extends PoseNode {
		private static final int CLAMP = 1;
		private static final int SYNCHRONIZED = 1 << 1;
		private static final int MATCH_SOURCE_TIME = 1 << 2;
		private static final int MATCH_SYNC_INDEX = 1 << 3;
		private static final int MATCH_SYNC_ID = 1 << 4;
		private static final int MATCH_SYNC_PERCENT = 1 << 5;

		final GraphInstance g;
		private final int[] stateNodes;
		private final int[] entryConditions;
		private final int[][] targets;
		private final int[][] conditions;
		private final int[][] transitionNodes;
		private final int defaultState;
		int activeState = -1;
		Transition transition;

		StateMachineNode(GraphInstance g, JsonObject j) {
			this.g = g;
			JsonArray s = j.getAsJsonArray("m_stateDefinitions");
			int n = s.size();
			stateNodes = new int[n];
			entryConditions = new int[n];
			targets = new int[n][];
			conditions = new int[n][];
			transitionNodes = new int[n][];
			for (int k = 0; k < n; k++) {
				JsonObject o = s.get(k).getAsJsonObject();
				stateNodes[k] = o.get("m_nStateNodeIdx").getAsInt();
				entryConditions[k] = GraphDef.i(o, "m_nEntryConditionNodeIdx", -1);
				JsonArray t = o.getAsJsonArray("m_transitionDefinitions");
				targets[k] = new int[t.size()];
				conditions[k] = new int[t.size()];
				transitionNodes[k] = new int[t.size()];
				for (int m = 0; m < t.size(); m++) {
					JsonObject to = t.get(m).getAsJsonObject();
					targets[k][m] = to.get("m_nTargetStateIdx").getAsInt();
					conditions[k][m] = GraphDef.i(to, "m_nConditionNodeIdx", -1);
					transitionNodes[k][m] = GraphDef.i(to, "m_nTransitionNodeIdx", -1);
				}
			}
			defaultState = GraphDef.i(j, "m_nDefaultStateIndex", 0);
		}

		StateNode state(int k) {
			Node n = g.node(stateNodes[k]);
			return n instanceof StateNode s ? s : null;
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			transition = null;
			activeState = defaultState;
			for (int k = 0; k < stateNodes.length; k++) {
				Values.ValueNode v = g.value(entryConditions[k]);
				if (v != null && v.b(c)) {
					activeState = k;
					break;
				}
			}
			StateNode s = state(activeState);
			if (s != null) {
				s.init(c, time);
				mirror(s);
			}
		}

		@Override
		void shutdown(Ctx c) {
			if (transition != null) {
				transition.shutdown(c);
				transition = null;
			} else {
				StateNode s = state(activeState);
				if (s != null && s.active) {
					s.shutdown(c);
				}
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			step(c, out, false, 0f, 0f);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			step(c, out, true, from, to);
		}

		private void step(Ctx c, Pose out, boolean sync, float from, float to) {
			StateNode s = state(activeState);
			if (s == null) {
				out.setReference(c.skel);
				return;
			}
			if (transition != null) {
				if (sync) {
					transition.updateSync(c, from, to, out);
				} else {
					transition.update(c, out);
				}
				if (transition.done()) {
					transition.finishSource(c);
					transition = null;
				}
				mirror(s);
			} else {
				if (sync) {
					s.updateSync(c, from, to, out);
				} else {
					s.update(c, out);
				}
				mirror(s);
			}
			evaluateTransitions(c);
		}

		private void evaluateTransitions(Ctx c) {
			int[] t = targets[activeState];
			for (int m = 0; m < t.length; m++) {
				int target = t[m];
				if (target == activeState || target < 0 || target >= stateNodes.length) {
					continue;
				}
				Values.ValueNode cond = g.value(conditions[activeState][m]);
				if (cond == null || !cond.b(c)) {
					continue;
				}
				start(c, target, transitionNodes[activeState][m]);
				return;
			}
		}

		private void start(Ctx c, int target, int transitionNode) {
			StateNode from = state(activeState);
			StateNode to = state(target);
			if (to == null) {
				return;
			}
			JsonObject d = transitionNode >= 0 ? g.def.nodes[transitionNode] : new JsonObject();
			float length = GraphDef.f(d, "m_flDuration", 0f);
			Values.ValueNode lo = g.value(GraphDef.i(d, "m_nDurationOverrideNodeIdx", -1));
			if (lo != null) {
				length = lo.f(c);
			}
			JsonObject opts = d.getAsJsonObject("m_transitionOptions");
			int flags = opts == null ? 0 : GraphDef.i(opts, "m_flags", 0);
			if (transition != null && to.active) {
				// going back to a state that is still blending out: drop the old blend, start over from the current state
				transition.finishSource(c);
				transition = null;
			}
			PoseNode source = transition != null ? transition : from;
			float start = 0f;
			if ((flags & (SYNCHRONIZED | MATCH_SOURCE_TIME | MATCH_SYNC_INDEX | MATCH_SYNC_PERCENT)) != 0) {
				start = source.cur;
			}
			float offset = GraphDef.f(d, "m_flTimeOffset", 0f);
			Values.ValueNode oo = g.value(GraphDef.i(d, "m_timeOffsetOverrideNodeIdx", -1));
			if (oo != null) {
				offset = oo.f(c);
			}
			to.init(c, start);
			if ((flags & MATCH_SYNC_ID) != 0) {
				Values.ValueNode idn = g.value(GraphDef.i(d, "m_targetSyncIDNodeIdx", -1));
				String id = idn != null ? idn.id(c) : null;
				List<AgentClip.Event> track = to.syncTrack();
				if (id != null && track != null) {
					for (AgentClip.Event e : track) {
						if (e.id().equals(id)) {
							to.shutdown(c);
							to.init(c, e.start());
							break;
						}
					}
				}
			}
			if (offset != 0f) {
				to.shutdown(c);
				to.init(c, Math.max(0f, Math.min(1f, to.cur + offset)));
			}
			if ((flags & CLAMP) != 0 && source.duration > 0 && !source.looping) {
				length = Math.min(length, (1f - source.cur) * source.duration);
			}
			if (length <= 1e-4f) {
				source.shutdown(c);
				transition = null;
			} else {
				transition = new Transition(source, to, length, GraphDef.s(d, "m_blendWeightEasing"));
			}
			activeState = target;
			mirror(to);
		}

		private void mirror(StateNode s) {
			duration = s.duration;
			prev = s.prev;
			cur = s.cur;
			looping = s.looping;
		}

		float layerWeight(Ctx c) {
			if (transition != null) {
				return transition.layerWeight(c);
			}
			StateNode s = state(activeState);
			return s == null ? 0f : s.layerWeight(c);
		}

		float[] layerMask(Ctx c) {
			if (transition != null) {
				return transition.layerMask(c);
			}
			StateNode s = state(activeState);
			return s == null ? null : s.layerMask(c);
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			StateNode s = state(activeState);
			return s == null ? null : s.syncTrack();
		}

		/** Path of the active state (debug). */
		public String activePath() {
			return activeState < 0 ? "-" : g.def.path(stateNodes[activeState]);
		}
	}

	// ------------------------------------------------------------------ layers and referenced graphs

	static final class LayerBlendNode extends PoseNode {
		final GraphInstance g;
		final int base;
		final int[] inputs;
		private final int[] weights;
		private final int[] masks;
		final String[] modes;
		final float[] lastWeights;

		LayerBlendNode(GraphInstance g, JsonObject j) {
			this.g = g;
			base = GraphDef.i(j, "m_nBaseNodeIdx", -1);
			JsonArray l = j.getAsJsonArray("m_layerDefinition");
			inputs = new int[l.size()];
			weights = new int[l.size()];
			masks = new int[l.size()];
			modes = new String[l.size()];
			lastWeights = new float[l.size()];
			for (int k = 0; k < l.size(); k++) {
				JsonObject o = l.get(k).getAsJsonObject();
				inputs[k] = GraphDef.i(o, "m_nInputNodeIdx", -1);
				weights[k] = GraphDef.i(o, "m_nWeightValueNodeIdx", -1);
				masks[k] = GraphDef.i(o, "m_nBoneMaskValueNodeIdx", -1);
				modes[k] = GraphDef.s(o, "m_blendMode");
			}
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			PoseNode b = g.pose(base);
			if (b != null) {
				b.init(c, time);
			}
			for (int in : inputs) {
				PoseNode p = g.pose(in);
				if (p != null) {
					p.init(c, 0f);
				}
			}
		}

		@Override
		void shutdown(Ctx c) {
			PoseNode b = g.pose(base);
			if (b != null) {
				b.shutdown(c);
			}
			for (int in : inputs) {
				PoseNode p = g.pose(in);
				if (p != null) {
					p.shutdown(c);
				}
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			PoseNode b = g.pose(base);
			if (b == null) {
				out.setReference(c.skel);
			} else {
				b.update(c, out);
				duration = b.duration;
				prev = b.prev;
				cur = b.cur;
				looping = b.looping;
			}
			layers(c, out);
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			PoseNode b = g.pose(base);
			if (b == null) {
				out.setReference(c.skel);
			} else {
				b.updateSync(c, from, to, out);
				duration = b.duration;
				prev = b.prev;
				cur = b.cur;
			}
			layers(c, out);
		}

		private void layers(Ctx c, Pose out) {
			for (int k = 0; k < inputs.length; k++) {
				PoseNode p = g.pose(inputs[k]);
				if (p == null) {
					continue;
				}
				Pose layer = c.acquire();
				boolean inLayer = c.inLayer;
				float lw = c.layerWeight;
				float[] lm = c.layerMask;
				c.inLayer = true;
				c.layerWeight = 1f;
				c.layerMask = null;
				p.update(c, layer);
				float w = c.layerWeight;
				float[] mask = c.layerMask;
				c.inLayer = inLayer;
				c.layerWeight = lw;
				c.layerMask = lm;
				lastWeights[k] = w;
				Values.ValueNode wv = g.value(weights[k]);
				if (wv != null) {
					w *= wv.f(c);
				}
				Values.ValueNode mv = g.value(masks[k]);
				if (mv != null) {
					mask = mv.mask(c);
				}
				if (w > 1e-4f) {
					switch (modes[k]) {
						case "Additive" -> {
							if (DEBUG) {
								System.out.printf("  [dbg] layer %d additive=%b w=%.2f pelvis t=(%.3f,%.3f,%.3f)%n", k, layer.additive, w, layer.t[3], layer.t[4], layer.t[5]);
							}
							Pose.addOnto(out, layer, w, mask, out);
						}
						case "ModelSpace" -> Pose.blendModelSpace(out, layer, w, mask, c.skel, out, c.scratchA, c.scratchB, c.scratchO);
						default -> Pose.blend(out, layer, w, mask, out);
					}
				}
				c.release(layer);
			}
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			PoseNode b = g.pose(base);
			return b == null ? null : b.syncTrack();
		}
	}

	static final class ReferencedGraphNode extends PoseNode {
		final GraphInstance g;
		final int ref;
		private final int fallback;

		ReferencedGraphNode(GraphInstance g, JsonObject j) {
			this.g = g;
			ref = GraphDef.i(j, "m_nReferencedGraphIdx", -1);
			fallback = GraphDef.i(j, "m_nFallbackNodeIdx", -1);
		}

		private PoseNode target() {
			GraphInstance child = g.child(ref);
			if (child != null) {
				return child.root();
			}
			return g.pose(fallback);
		}

		@Override
		void init(Ctx c, float time) {
			active = true;
			PoseNode p = target();
			if (p != null) {
				p.init(c, time);
			}
		}

		@Override
		void shutdown(Ctx c) {
			PoseNode p = target();
			if (p != null && p.active) {
				p.shutdown(c);
			}
			super.shutdown(c);
		}

		@Override
		void update(Ctx c, Pose out) {
			PoseNode p = target();
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			if (!p.active) {
				p.init(c, 0f);
			}
			p.update(c, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
			looping = p.looping;
		}

		@Override
		void updateSync(Ctx c, float from, float to, Pose out) {
			PoseNode p = target();
			if (p == null) {
				out.setReference(c.skel);
				return;
			}
			if (!p.active) {
				p.init(c, from);
			}
			p.updateSync(c, from, to, out);
			duration = p.duration;
			prev = p.prev;
			cur = p.cur;
		}

		@Override
		List<AgentClip.Event> syncTrack() {
			PoseNode p = target();
			return p == null ? null : p.syncTrack();
		}
	}
}
