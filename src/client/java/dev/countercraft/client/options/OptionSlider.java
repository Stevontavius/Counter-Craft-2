package dev.countercraft.client.options;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** A slider over [min, max] (snapped to {@code step}) that shows its value with {@code format} and applies it live. */
public final class OptionSlider extends AbstractSliderButton {
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<String> format;
	private final DoubleConsumer apply;

	public OptionSlider(int x, int y, int w, double min, double max, double step, double current, DoubleFunction<String> format, DoubleConsumer apply) {
		super(x, y, w, 20, Component.empty(), (Math.max(min, Math.min(max, current)) - min) / (max - min));
		this.min = min;
		this.max = max;
		this.step = step;
		this.format = format;
		this.apply = apply;
		updateMessage();
	}

	private double current() {
		double v = min + value * (max - min);
		return step > 0 ? Math.round(v / step) * step : v;
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(format.apply(current())));
	}

	@Override
	protected void applyValue() {
		apply.accept(current());
	}
}
