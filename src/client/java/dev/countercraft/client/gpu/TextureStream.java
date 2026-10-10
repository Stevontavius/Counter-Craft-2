package dev.countercraft.client.gpu;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;

/**
 * The CS2 pack's big textures (2048 x 2048 PNGs, tens of milliseconds each to decode) loaded off the render thread:
 * the PNG decodes on a worker, only the upload runs on the render thread (between frames). Minecraft's own
 * getTexture decodes in the frame that first draws it, which is what hitched every first equip, buy and dropped gun.
 * Counter-Craft's own textures are scaled down on the worker to Video > Texture Quality's largest side.
 */
public final class TextureStream {
	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "countercraft texture loader");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	/** false: loading; true: uploaded (or missing: then getTexture makes the missing texture). */
	private static final Map<Identifier, Boolean> STATE = new ConcurrentHashMap<>();
	/** Bumped by {@link #reloadAll}: a load started before it doesn't register. */
	private static volatile int generation;

	private TextureStream() {
	}

	/** The texture once it's uploaded; until then null (and it starts loading). Render thread. */
	public static AbstractTexture get(Identifier id) {
		Boolean s = STATE.get(id);
		if (s == Boolean.TRUE) {
			return Minecraft.getInstance().getTextureManager().getTexture(id);
		}
		if (s == null) {
			request(id);
		}
		return null;
	}

	/** Start loading a texture ahead of its first draw (any thread). */
	public static void request(Identifier id) {
		if (STATE.putIfAbsent(id, Boolean.FALSE) != null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int gen = generation;
		CompletableFuture.supplyAsync(() -> {
			try {
				return fit(id, TextureContents.load(mc.getResourceManager(), id));
			} catch (IOException e) {
				return null; // missing: getTexture will make the missing texture (cheap)
			}
		}, POOL).thenAcceptAsync(contents -> {
			if (gen != generation) {
				if (contents != null) {
					contents.close();
				}
				return; // reloaded meanwhile: the next get() asks again
			}
			if (contents != null) {
				SimpleTexture t = new SimpleTexture(id);
				t.apply(contents); // uploads, then closes the image
				mc.getTextureManager().register(id, t);
			}
			STATE.put(id, Boolean.TRUE);
		}, mc).exceptionally(t -> {
			STATE.put(id, Boolean.TRUE); // let getTexture report it the usual way
			return null;
		});
	}

	/** Texture Quality changed: Counter-Craft's textures load again (at the new size) as they're next drawn. */
	public static void reloadAll() {
		generation++;
		STATE.keySet().removeIf(id -> id.getNamespace().equals(dev.countercraft.CounterCraft.MOD_ID));
	}

	/** A Counter-Craft texture larger than Texture Quality allows, scaled down (filtered); anything else as it is. */
	private static TextureContents fit(Identifier id, TextureContents c) {
		int max = dev.countercraft.client.options.CcOptions.textureQuality().maxSize;
		com.mojang.blaze3d.platform.NativeImage img = c.image();
		int w = img.getWidth();
		int h = img.getHeight();
		if (max <= 0 || !id.getNamespace().equals(dev.countercraft.CounterCraft.MOD_ID) || Math.max(w, h) <= max) {
			return c;
		}
		float s = max / (float) Math.max(w, h);
		com.mojang.blaze3d.platform.NativeImage small = new com.mojang.blaze3d.platform.NativeImage(Math.max(1, Math.round(w * s)),
				Math.max(1, Math.round(h * s)), false);
		img.resizeSubRectTo(0, 0, w, h, small);
		TextureContents out = new TextureContents(small, c.metadata());
		c.close();
		return out;
	}
}
