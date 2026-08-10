package com.github.squi2rel.freedraw.render;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Vector3f;

public record BakedSegment(float[] vertices, Vector3f endTangent, Vector3f endNormal) {
    public void close() {
    }

    public void draw(VertexConsumer vc, float dx, float dy, float dz) {
        for (int i = 0; i < vertices.length; i += 6) {
            vc.vertex(vertices[i] + dx, vertices[i + 1] + dy, vertices[i + 2] + dz)
                    .texture(vertices[i + 3], vertices[i + 4])
                    .color(Float.floatToRawIntBits(vertices[i + 5]));
        }
    }
}