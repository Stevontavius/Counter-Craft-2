package dev.countercraft.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A model part's children by name and its cubes (MobHeadsClient walks the model tree). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("children")
	Map<String, ModelPart> countercraft$children();

	@Accessor("cubes")
	List<ModelPart.Cube> countercraft$cubes();
}
