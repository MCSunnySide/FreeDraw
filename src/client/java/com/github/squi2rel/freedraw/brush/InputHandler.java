package com.github.squi2rel.freedraw.brush;

import com.github.squi2rel.freedraw.FreeDrawClient;
import com.github.squi2rel.freedraw.network.ClientPacketHandler;
import com.github.squi2rel.freedraw.vivecraft.Vivecraft;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

import static com.github.squi2rel.freedraw.FreeDrawClient.*;

public class InputHandler {
    private static long lastUpload = System.currentTimeMillis();
    private static Vec3d prevPos = null;
    public static boolean drawing;

    public static void register() {
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> {
            if (!FreeDrawClient.connected) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || camera().getFocusedEntity() != client.player || !isVRMode() && camera().isThirdPerson()) return;
            if (client.options.useKey.isPressed()) {
                for (Hand hand : Hand.values()) {
                    ItemStack item = client.player.getStackInHand(hand);
                    boolean isBrush = checkItem(item, brushItem, brushIdStart, brushIdEnd);
                    boolean isEraser = !isBrush && checkItem(item, eraserItem, eraserId, eraserId);
                    if (!isBrush && !isEraser) continue;
                    if (isBrush) {
                        updateBrush(hand);
                        break;
                    } else {
                        updateEraser(hand);
                    }
                }
            } else {
                prevPos = null;
                drawing = false;
                if (currentPath != null) {
                    currentPath.upload(true);
                    currentPath.cache();
                    currentPath = null;
                }
            }
            if (currentPath != null && System.currentTimeMillis() - lastUpload > uploadInterval) {
                currentPath.upload(false);
                lastUpload = System.currentTimeMillis();
            }
        });
    }

    private static void updateBrush(Hand hand) {
        if (drawing && currentPath == null) return;
        Vec3d drawPoint;
        if (isVRMode()) {
            Vector3f off = Vivecraft.getHandDirection(hand).rotate(brushQuat).mul(brushLength);
            drawPoint = Vivecraft.getHandPosition(hand).add(off.x, off.y, off.z);
            if (drawPoint == null) return;
        } else {
            Vec3d eyePos = camera().getCameraPos();
            Vector3f lookVec = new Vector3f(0, 0, -1).rotate(camera().getRotation()).mul(desktopRange);
            drawPoint = eyePos.add(lookVec.x, lookVec.y, lookVec.z);
        }
        if (prevPos != null) {
            if (prevPos.subtract(drawPoint).length() < 0.01) return;
            if (currentPath == null) {
                drawing = true;
                currentPath = new ClientBrushPath(UUID.randomUUID(), prevPos, color, false);
                paths.put(currentPath.uuid, currentPath);
                ClientPacketHandler.newPath(currentPath);
            }
            currentPath.add(drawPoint.x, drawPoint.y, drawPoint.z);
        }
        prevPos = drawPoint;
    }

    private static boolean isVRMode() {
        return Vivecraft.loaded && Vivecraft.isVRActive();
    }

    private static Camera camera() {
        return MinecraftClient.getInstance().gameRenderer.getCamera();
    }

    private static void updateEraser(Hand hand) {
        Vec3d start, end;
        if (isVRMode()) {
            start = Vivecraft.getHandPosition(hand);
            Vector3f off = Vivecraft.getHandDirection(hand).rotate(eraserQuat).mul(eraserLength);
            end = start.add(off.x, off.y, off.z);
        } else {
            start = camera().getCameraPos();
            Vector3f lookVec = new Vector3f(0, 0, -1).rotate(camera().getRotation()).mul(desktopRange);
            end = start.add(lookVec.x, lookVec.y, lookVec.z);
        }
        outer: for (ClientBrushPath path : paths.values()) {
            float[] c = path.posCache;
            if (c == null) continue;
            Vector3d offset = path.offset;
            float sx = (float) (start.x - offset.x), sy = (float) (start.y - offset.y), sz = (float) (start.z - offset.z);
            float ex = (float) (end.x - offset.x), ey = (float) (end.y - offset.y), ez = (float) (end.z - offset.z);
            float v = desktopRange / 2;
            if (lenBox(sx, sy, sz, path.minX, path.minY, path.minZ, path.maxX, path.maxY, path.maxZ) > v && lenBox(ex, ey, ez, path.minX, path.minY, path.minZ, path.maxX, path.maxY, path.maxZ) > v) continue;
            for (int i = 0; i < c.length; i += 3) {
                if (len(c[i], c[i + 1], c[i + 2], sx, sy, sz, ex, ey, ez) <= 0.005) {
                    ClientPacketHandler.removePath(path.uuid);
                    break outer;
                }
            }
        }
    }

    public static float lenBox(float x, float y, float z, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float dx = Math.max(Math.max(minX - x, 0), x - maxX);
        float dy = Math.max(Math.max(minY - y, 0), y - maxY);
        float dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float len(float px, float py, float pz, float ax, float ay, float az, float bx, float by, float bz) {
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float apx = px - ax, apy = py - ay, apz = pz - az;

        float abLenSq = abx * abx + aby * aby + abz * abz;
        float dot = apx * abx + apy * aby + apz * abz;
        float t = dot / abLenSq;
        t = Math.max(0f, Math.min(1f, t));

        float cx = ax + t * abx;
        float cy = ay + t * aby;
        float cz = az + t * abz;

        float dx = px - cx;
        float dy = py - cy;
        float dz = pz - cz;

        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean checkItem(ItemStack itemStack, Item item, float cmd1, float cmd2) {
        if (itemStack.getItem() != item) return false;
        CustomModelDataComponent data = itemStack.getComponents().get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (data == null) return false;
        List<Float> id = data.floats();
        for (Float v : id) {
            if (v >= cmd1 && v <= cmd2) return true;
        }
        return false;
    }
}
