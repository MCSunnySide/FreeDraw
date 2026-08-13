package com.github.squi2rel.freedraw.render;

import com.github.squi2rel.freedraw.FreeDraw;
import com.github.squi2rel.freedraw.FreeDrawClient;
import com.github.squi2rel.freedraw.brush.ClientBrushPath;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.List;

import static com.github.squi2rel.freedraw.FreeDrawClient.paths;

public class PathRenderer {
    private static final Identifier WHITE = Identifier.of(FreeDraw.MOD_ID, "white");
    private static RenderLayer brushPath;
    private static boolean registered = false;

    private static final float[] COS_TABLE = {1.0f, 0.0f, -1.0f, 0.0f, 1.0f};
    private static final float[] SIN_TABLE = {0.0f, 1.0f, 0.0f, -1.0f, 0.0f};

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            if (!FreeDrawClient.connected) return;
            init();
            Vec3d camPos = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
            draw(paths.values(), camPos);
        });
    }

    private static void init() {
        if (registered) return;
        registered = true;
        MinecraftClient.getInstance().getTextureManager().registerTexture(WHITE, new WhiteTexture());

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(FreeDraw.MOD_ID, "brush_path"))
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLE_STRIP)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withCull(false)
                .build();
        IrisCompat.register(pipeline);

        GpuSampler sampler = RenderSystem.getSamplerCache().get(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, true);
        RenderSetup setup = RenderSetup.builder(pipeline)
                .texture("Sampler0", WHITE, () -> sampler)
                .expectedBufferSize(32768)
                .build();
        brushPath = RenderLayer.of("brush_path", setup);
    }

    private static void draw(Collection<ClientBrushPath> paths, Vec3d camPos) {
        BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_TEXTURE_COLOR);
        for (ClientBrushPath path : paths) {
            if (path.points.size() < 2) continue;
            float dx = (float) (path.offset.x - camPos.x);
            float dy = (float) (path.offset.y - camPos.y);
            float dz = (float) (path.offset.z - camPos.z);
            for (BakedSegment segment : path.bakedSegments) {
                segment.draw(builder, dx, dy, dz);
            }
            if (path.dynamicNodes != null && path.dynamicNodes.size() >= 2) {
                draw(builder, path.dynamicNodes, 0.01f, path.isFirstDraw(), true, dx, dy, dz);
            }
        }
        BuiltBuffer built = builder.endNullable();
        if (built != null) {
            brushPath.draw(built);
        }
    }

    public static float[] bake(List<RenderNode> nodes, boolean startCap, boolean endCap) {
        FloatArrayList out = new FloatArrayList();
        draw(nodes, 0.01f, startCap, endCap, 0, 0, 0, (x, y, z, color) -> {
            out.add(x);
            out.add(y);
            out.add(z);
            out.add(0);
            out.add(0);
            out.add(Float.intBitsToFloat(color));
        });
        return out.toFloatArray();
    }

    public static void draw(BufferBuilder buf, List<RenderNode> nodes, float radius, boolean startCap, boolean endCap) {
        draw(buf, nodes, radius, startCap, endCap, 0, 0, 0);
    }

    public static void draw(BufferBuilder buf, List<RenderNode> nodes, float radius, boolean startCap, boolean endCap, float dx, float dy, float dz) {
        if (nodes.size() < 2) return;
        draw(nodes, radius, startCap, endCap, dx, dy, dz, (x, y, z, color) -> buf.vertex(x, y, z).texture(0, 0).color(color));
    }

    private static void draw(List<RenderNode> nodes, float radius, boolean startCap, boolean endCap, float dx, float dy, float dz, VertexSink sink) {
        if (nodes.size() < 2) return;

        RenderNode first = nodes.getFirst();
        RenderNode last = nodes.getLast();

        float s0 = SIN_TABLE[0] * radius;
        float c0 = COS_TABLE[0] * radius;

        if (startCap) {
            submitVertex(sink, first, c0, s0, first.color, dx, dy, dz);
            submitVertex(sink, first, c0, s0, first.color, dx, dy, dz);

            for (int j = 0; j < 5; j++) {
                submitVertex(sink, first, COS_TABLE[j] * radius, SIN_TABLE[j] * radius, first.color, dx, dy, dz);
                submitVertex(sink, first, -radius, first.color, dx, dy, dz);
            }
        } else {
            submitVertex(sink, first, c0, s0, first.color, dx, dy, dz);
            submitVertex(sink, first, c0, s0, first.color, dx, dy, dz);
        }

        for (int i = 0; i < nodes.size() - 1; i++) {
            RenderNode n1 = nodes.get(i);
            RenderNode n2 = nodes.get(i + 1);

            if (i > 0) {
                submitVertex(sink, n1, c0, s0, n1.color, dx, dy, dz);
                submitVertex(sink, n1, c0, s0, n1.color, dx, dy, dz);
            }

            for (int j = 0; j < 5; j++) {
                float c = COS_TABLE[j] * radius;
                float s = SIN_TABLE[j] * radius;

                submitVertex(sink, n2, c, s, n2.color, dx, dy, dz);
                submitVertex(sink, n1, c, s, n1.color, dx, dy, dz);
            }
        }

        if (endCap) {
            submitVertex(sink, last, c0, s0, last.color, dx, dy, dz);
            submitVertex(sink, last, c0, s0, last.color, dx, dy, dz);

            for (int j = 0; j < 5; j++) {
                submitVertex(sink, last, radius, last.color, dx, dy, dz);
                submitVertex(sink, last, COS_TABLE[j] * radius, SIN_TABLE[j] * radius, last.color, dx, dy, dz);
            }
        }

        float c4 = COS_TABLE[4] * radius;
        float s4 = SIN_TABLE[4] * radius;
        submitVertex(sink, last, c4, s4, last.color, dx, dy, dz);
        submitVertex(sink, last, c4, s4, last.color, dx, dy, dz);
    }

    private static void submitVertex(VertexSink sink, RenderNode node, float tO, int color, float dx, float dy, float dz) {
        float x = Math.fma(node.tangent.x, tO, (float) node.pos.x) + dx;
        float y = Math.fma(node.tangent.y, tO, (float) node.pos.y) + dy;
        float z = Math.fma(node.tangent.z, tO, (float) node.pos.z) + dz;
        sink.vertex(x, y, z, color);
    }

    private static void submitVertex(VertexSink sink, RenderNode node, float nO, float bO, int color, float dx, float dy, float dz) {
        float x = Math.fma(node.normal.x, nO, (float) node.pos.x);
        x = Math.fma(node.binormal.x, bO, x);
        float y = Math.fma(node.normal.y, nO, (float) node.pos.y);
        y = Math.fma(node.binormal.y, bO, y);
        float z = Math.fma(node.normal.z, nO, (float) node.pos.z);
        z = Math.fma(node.binormal.z, bO, z);
        sink.vertex(x + dx, y + dy, z + dz, color);
    }

    public static void reset() {
    }

    private interface VertexSink {
        void vertex(float x, float y, float z, int color);
    }

    private static class WhiteTexture extends NativeImageBackedTexture {
        public WhiteTexture() {
            super(() -> "freedraw_white", createImage());
        }

        @Override
        public void upload() {
            if (getImage() == null) return;
            super.upload();
            setImage(null);
        }

        private static NativeImage createImage() {
            NativeImage image = new NativeImage(1, 1, false);
            image.setColorArgb(0, 0, 0xFFFFFFFF);
            return image;
        }
    }
}
