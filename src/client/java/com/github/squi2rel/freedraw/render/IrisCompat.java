package com.github.squi2rel.freedraw.render;

import com.github.squi2rel.freedraw.FreeDraw;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Registers FreeDraw's custom render pipeline with Iris so it is rendered
 * through the shaderpack's gbuffer programs (preserving vertex colors) instead
 * of falling through Iris' override list, which produces broken, colorless
 * rendering under shader packs.
 *
 * <p>Iris is deliberately accessed via reflection so this mod has no hard
 * dependency on it; when Iris is absent nothing happens.
 */
public class IrisCompat {
    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

    public static void register(RenderPipeline pipeline) {
        if (!IRIS_LOADED) return;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Class<?> programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object textured = Enum.valueOf((Class<? extends Enum>) programClass, "TEXTURED");

            for (Method method : apiClass.getMethods()) {
                if (method.getName().equals("assignPipeline") && method.getParameterCount() == 2) {
                    method.invoke(api, pipeline, textured);
                    FreeDraw.LOGGER.info("Registered freedraw brush pipeline with Iris (gbuffers_textured)");
                    return;
                }
            }
            FreeDraw.LOGGER.warn("Iris API does not expose assignPipeline, skipping registration");
        } catch (Throwable t) {
            FreeDraw.LOGGER.warn("Failed to register freedraw brush pipeline with Iris", t);
        }
    }
}
