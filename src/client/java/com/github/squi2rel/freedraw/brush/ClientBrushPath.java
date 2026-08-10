package com.github.squi2rel.freedraw.brush;

import com.github.squi2rel.freedraw.FreeDraw;
import com.github.squi2rel.freedraw.FreeDrawClient;
import com.github.squi2rel.freedraw.network.ClientPacketHandler;
import com.github.squi2rel.freedraw.render.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.*;

public class ClientBrushPath extends BrushPath {
    public static final int BAKE_THRESHOLD = 1024;
    public final List<BakedSegment> bakedSegments = new ArrayList<>();
    public List<RenderNode> dynamicNodes = new ArrayList<>();
    private List<Vector3f> nowPoints = new ArrayList<>();
    private BakeState bakeState;
    public float[] posCache;
    public int lastIndex = 0;
    public boolean created = false;


    public ClientBrushPath(UUID uuid, Vec3d offset, int color, boolean finalized) {
        super(uuid, getWorldName(), new Vector3d(offset.x, offset.y, offset.z), color);
        this.finalized = finalized;
    }

    public ClientBrushPath(UUID uuid, Vector3d offset, int color, boolean finalized) {
        super(uuid, getWorldName(), offset, color);
        this.finalized = finalized;
    }

    private static String getWorldName() {
        return Objects.requireNonNull(MinecraftClient.getInstance().player).getEntityWorld().getRegistryKey().getValue().toString();
    }

    private static void actionBar(int limit, int s) {
        MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal(String.valueOf(s)).formatted(s < limit * 0.3 ? Formatting.GREEN : s < limit * 0.6 ? Formatting.YELLOW : s < limit * 0.9 ? Formatting.GOLD : Formatting.RED).append(" / " + limit), false);
    }

    public void add(double x, double y, double z) {
        if (points.size() >= FreeDrawClient.maxPoints) return;
        addRawPoint(x - offset.x, y - offset.y, z - offset.z);
        actionBar(FreeDrawClient.maxPoints, points.size());
    }

    public void addRawPoint(double x, double y, double z) {
        addRawPoint((float) x, (float) y, (float) z);
    }

    public void addRawPoint(float x, float y, float z) {
        updateBounds(x, y, z);
        points.add(new Vector3f(x, y, z));
        if (finalized) return;

        nowPoints.add(new Vector3f(x, y, z));
        if (nowPoints.size() >= BAKE_THRESHOLD + 3) bakeSegment(nowPoints, BAKE_THRESHOLD);
        recalculateDynamicNodes(nowPoints);
    }

    private void bakeSegment(List<Vector3f> list, int nodes) {
        NodeGroup group = generateNodes(list, nodes);
        if (group.nodes.isEmpty()) return;

        float[] baked = PathRenderer.bake(group.nodes, bakeState == null, finalized);

        if (bakeState == null) bakeState = new BakeState();
        bakeState.lastBakedTangent.set(group.lastTangent);
        bakeState.lastBakedNormal.set(group.lastNormal);
        bakeState.lastBakedPos.set(group.lastPos);
        bakeState.lastBakedDistance = group.lastDistance;

        bakedSegments.add(new BakedSegment(baked, new Vector3f(group.lastTangent), new Vector3f(group.lastNormal)));
        List<Vector3f> newActive = new ArrayList<>();
        for (int i = BAKE_THRESHOLD - 1; i < nowPoints.size(); i++) {
            newActive.add(nowPoints.get(i));
        }
        nowPoints.clear();
        nowPoints.addAll(newActive);
    }

    private void recalculateDynamicNodes(List<Vector3f> list) {
        dynamicNodes.clear();
        dynamicNodes.addAll(generateNodes(list, list.size() - 1).nodes);
    }

    private NodeGroup generateNodes(List<Vector3f> points, int maxIndex) {
        List<RenderNode> result = new ArrayList<>();
        if (points.size() < 2) return new NodeGroup(null, null, null, 0, result);

        Vector3f currTangent = new Vector3f();
        Vector3f currNormal = new Vector3f();

        Vector3f prevT = bakeState != null ? new Vector3f(bakeState.lastBakedTangent) : null;
        Vector3f prevN = bakeState != null ? new Vector3f(bakeState.lastBakedNormal) : null;
        float totalDistance = bakeState != null ? bakeState.lastBakedDistance : 0f;
        Vector3d lastPos = bakeState != null ? new Vector3d(bakeState.lastBakedPos) : null;

        for (int i = 0; i < maxIndex; i++) {
            if (i + 1 >= points.size()) break;
            Vector3f p1 = points.get(i);
            Vector3f p2 = points.get(i + 1);
            Vector3f p0 = (i == 0) ? p1 : points.get(i - 1);
            Vector3f p3 = (i + 2 < points.size()) ? points.get(i + 2) : p2;

            int steps = Math.max(1, (int) (SPLINE_STEPS * p1.distance(p2)));
            for (int j = 0; j < steps; j++) {
                double t = (double) j / steps;
                Vector3d pos = new Vector3d();
                BrushMath.catmullRom(p0, p1, p2, p3, t, pos);

                Vector3d nextPos = new Vector3d();
                BrushMath.catmullRom(p0, p1, p2, p3, t + 0.01, nextPos);
                currTangent.set((float) (nextPos.x - pos.x), (float) (nextPos.y - pos.y), (float) (nextPos.z - pos.z)).normalize();

                BrushMath.computeNextFrame(prevT, prevN, currTangent, currNormal);

                int finalColor = this.color;
                if (this.color == 0) {
                    if (lastPos != null) {
                        totalDistance += (float) pos.distance(lastPos);
                    }
                    float hue = (totalDistance * 0.2f) % 1.0f;
                    finalColor = FreeDraw.hsbToRgb(hue, 0.8f, 1.0f);
                }
                lastPos = pos;

                result.add(new RenderNode(pos, currTangent, currNormal, finalColor));

                if (prevT == null || prevN == null) {
                    prevT = new Vector3f(); prevN = new Vector3f();
                }
                prevT.set(currTangent);
                prevN.set(currNormal);
            }
        }

        return new NodeGroup(prevT, prevN, lastPos, totalDistance, result);
    }

    public void cache() {
        posCache = new float[points.size() * 3];
        for (int i = 0; i < points.size(); i += 3) {
            Vector3f point = points.get(i);
            posCache[i] = point.x;
            posCache[i + 1] = point.y;
            posCache[i + 2] = point.z;
        }

        if (points.size() < BAKE_THRESHOLD / 2) {
            recalculateDynamicNodes(points);
            return;
        }

        closeBaked();
        bakeSegment(points, points.size());
        bakeState = null;

        dynamicNodes = null;
        nowPoints = null;
    }

    public boolean isFirstDraw() {
        return bakeState == null;
    }

    public List<Vector3f> getNewPoints() {
        int size = points.size();
        List<Vector3f> subList = points.subList(lastIndex, size);
        lastIndex = size;
        return subList;
    }

    public void created() {
        if (!created && finalized) {
            upload(true);
        }
        created = true;
    }

    public void upload(boolean finalize) {
        if (!created) return;
        List<Vector3f> points = getNewPoints();
        if (points.isEmpty() && !finalize) return;
        ClientPacketHandler.addPoints(uuid, points, finalize);
    }

    private void closeBaked() {
        for (BakedSegment s : bakedSegments) s.close();
        bakedSegments.clear();
    }

    public void remove() {
        closeBaked();
    }

    private static class BakeState {
        public final Vector3f lastBakedTangent = new Vector3f();
        public final Vector3f lastBakedNormal = new Vector3f();
        public final Vector3d lastBakedPos = new Vector3d();
        public float lastBakedDistance;
    }

    private record NodeGroup(Vector3f lastTangent, Vector3f lastNormal, Vector3d lastPos, float lastDistance,
                             List<RenderNode> nodes) {
    }
}
