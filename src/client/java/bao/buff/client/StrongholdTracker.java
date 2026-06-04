package bao.buff.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

public final class StrongholdTracker {
    private static final int MIN_LOCAL_SAMPLES = 5;
    private static final int TARGET_LOCAL_SAMPLES = 10;
    private static final int STABLE_SUFFIX_SAMPLES = 4;
    private static final int MAX_LOCAL_OBSERVATION_TICKS = 16;
    private static final int MAX_OBSERVATIONS = 12;
    private static final double MAX_CAPTURE_DISTANCE_SQR = 16.0 * 16.0;
    private static final double MIN_SEGMENT_LENGTH_SQR = 0.75;
    private static final double MIN_DETERMINANT = 1.0E-5;
    private static final double MIN_PAIR_SINE = 0.02;
    private static final double RANSAC_INLIER_THRESHOLD = 16.0;
    private static final double MARKER_HALF_WIDTH = 0.6;
    private static final double MIN_SAMPLE_DELTA_SQR = 1.0E-4;
    private static final double MAX_RAY_RMSE_FOR_ACCEPT = 20.0;
    private static final double MIN_ACCEPT_CONFIDENCE = 35.0;
    private static final int MAX_CANDIDATES_TO_SHOW = 4;

    private static final List<RingInfo> RINGS = List.of(
        new RingInfo(1, 1280.0, 2816.0, 3),
        new RingInfo(2, 4352.0, 5888.0, 6),
        new RingInfo(3, 7424.0, 8960.0, 10),
        new RingInfo(4, 10496.0, 12032.0, 15),
        new RingInfo(5, 13568.0, 15104.0, 21),
        new RingInfo(6, 16640.0, 18176.0, 28),
        new RingInfo(7, 19712.0, 21248.0, 36),
        new RingInfo(8, 22784.0, 24320.0, 9)
    );

    private static final Map<Integer, PendingEyeObservation> pendingEyes = new HashMap<>();
    private static final List<RayObservation> observations = new ArrayList<>();
    private static final List<StrongholdCandidate> candidateTable = new ArrayList<>();

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TITLE_COLOR = 0xFFFFD966;
    private static final int HIGHLIGHT_COLOR = 0xFFA5FF8C;
    private static final int MUTED_COLOR = 0xFFB7C0D8;

    private static String activeDimensionId;
    private static FitResult estimate;
    private static String statusMessage = "Throw an eye of ender in the Overworld to start tracking.";

    private StrongholdTracker() {
    }

    public static void onClientTick(Minecraft client) {
        if (client.level == null) {
            if (!observations.isEmpty() || estimate != null || !pendingEyes.isEmpty() || activeDimensionId != null) {
                clearAll();
                statusMessage = "Tracker reset after leaving the world.";
            }
            activeDimensionId = null;
            return;
        }

        String currentDimensionId = client.level.dimension().toString();
        if (!Objects.equals(activeDimensionId, currentDimensionId)) {
            activeDimensionId = currentDimensionId;
            pendingEyes.clear();
            observations.clear();
            estimate = null;
            candidateTable.clear();

            if (isNether(client.level)) {
                statusMessage = "Using saved stronghold estimate for Nether travel.";
            } else {
                statusMessage = "Throw eyes in the Overworld to start a new estimate.";
            }
        }

        if (!Config.strongholdTrackerEnabled) {
            pendingEyes.clear();
            return;
        }

        processPendingEyes(client);
    }

    public static void onEntityLoad(Entity entity, ClientLevel world) {
        if (!Config.strongholdTrackerEnabled || !(entity instanceof EyeOfEnder eye)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || player.level() != world) {
            return;
        }

        if (!isOverworld(world)) {
            if (estimate == null) {
                statusMessage = "Throw eyes in the Overworld, then use the Nether proxy.";
            }
            return;
        }

        if (player.distanceToSqr(eye) > MAX_CAPTURE_DISTANCE_SQR) {
            return;
        }

        pendingEyes.put(eye.getId(), new PendingEyeObservation(
            eye,
            eye.getX(),
            eye.getZ(),
            player.getDeltaMovement().horizontalDistance()
        ));
        statusMessage = "Tracking eye trajectory...";
    }

    public static void onEntityUnload(Entity entity, ClientLevel world) {
        if (!(entity instanceof EyeOfEnder)) {
            return;
        }

        PendingEyeObservation observation = pendingEyes.remove(entity.getId());
        if (observation != null) {
            boolean valid = finalizeObservation(observation);
            if (!valid) {
                clearAll();
            }
        }
    }

    public static void manualReset() {
        clearAll();
        statusMessage = "Saved throws cleared.";
    }

    public static boolean hasEstimate() {
        return estimate != null;
    }

    public static String getStatusMessage() {
        return statusMessage;
    }

    public static String getDimensionLabel() {
        if (activeDimensionId == null) {
            return "Unknown";
        }

        return switch (activeDimensionId) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> activeDimensionId;
        };
    }

    public static String getSavedEyesLine() {
        return "Throws: " + observations.size();
    }

    public static String getLatestThrowLine() {
        if (!observations.isEmpty()) {
            RayObservation observation = observations.get(observations.size() - 1);
            return String.format(
                Locale.ROOT,
                "Last ray: X %.1f  Z %.1f  W %.2f",
                observation.originX(),
                observation.originZ(),
                observation.baseWeight()
            );
        }

        if (!pendingEyes.isEmpty()) {
            return "Last ray: reading eye path...";
        }

        return "Last ray: --";
    }

    public static String getEstimateLine() {
        return getOverworldEstimateLine();
    }

    public static String getOverworldEstimateLine() {
        if (estimate == null) {
            if (!candidateTable.isEmpty()) {
                StrongholdCandidate candidate = candidateTable.get(0);
                return String.format(
                    Locale.ROOT,
                    "OW target: X %.1f  Z %.1f",
                    candidate.x(),
                    candidate.z()
                );
            }
            return "OW target: waiting for 2 valid throws.";
        }

        return String.format(
            Locale.ROOT,
            "OW target: X %.1f  Z %.1f",
            estimate.x(),
            estimate.z()
        );
    }

    public static String getNetherEstimateLine() {
        if (estimate == null) {
            return "Nether proxy: waiting for target.";
        }

        return String.format(
            Locale.ROOT,
            "Nether proxy: X %.1f  Z %.1f",
            estimate.x() / 8.0,
            estimate.z() / 8.0
        );
    }

    public static String getAccuracyLine() {
        if (estimate == null) {
            if (!candidateTable.isEmpty()) {
                return String.format(
                    Locale.ROOT,
                    "Accuracy: %.1f%% (single-eye preview)",
                    candidateTable.get(0).confidencePercent()
                );
            }
            return "Accuracy: --";
        }

        return String.format(
            Locale.ROOT,
            "Accuracy: %.1f%% (%d/%d)",
            estimate.confidencePercent(),
            estimate.inlierCount(),
            estimate.totalCount()
        );
    }

    public static String getActiveTargetLine() {
        NavigationTarget target = getNavigationTarget(Minecraft.getInstance());
        if (target == null) {
            return "Marker: waiting for target.";
        }

        return String.format(
            Locale.ROOT,
            "Marker: %s  X %.1f  Z %.1f",
            target.description(),
            target.x(),
            target.z()
        );
    }

    public static void renderHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Config.strongholdTrackerEnabled || minecraft.options.hideGui || minecraft.player == null || minecraft.level == null) {
            return;
        }

        Font font = minecraft.font;
        List<String> lines = new ArrayList<>();
        lines.add("Stronghold Tracker");
        lines.add(getSavedEyesLine());
        lines.add(getOverworldEstimateLine());
        lines.add(getNetherEstimateLine());
        lines.add(getAccuracyLine());

        if (!candidateTable.isEmpty()) {
            lines.add("Candidates:");
            int shown = Math.min(MAX_CANDIDATES_TO_SHOW, candidateTable.size());
            for (int i = 0; i < shown; i++) {
                StrongholdCandidate candidate = candidateTable.get(i);
                lines.add(formatCandidateLine(i + 1, candidate));
            }
        }

        lines.add("Status: " + statusMessage);

        int padding = 6;
        int lineHeight = font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }

        int x = 8;
        int y = 8;
        int boxWidth = width + padding * 2;
        int boxHeight = lines.size() * lineHeight + padding * 2;

        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0x90000000);

        int textY = y + padding;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int color = TEXT_COLOR;
            if (index == 0) {
                color = TITLE_COLOR;
            } else if (line.startsWith("Candidates:")) {
                color = HIGHLIGHT_COLOR;
            } else if (line.startsWith("Status:")) {
                color = MUTED_COLOR;
            }
            guiGraphics.drawString(font, line, x + padding, textY, color, false);
            textY += lineHeight;
        }

        renderWaypointIndicator(guiGraphics, minecraft);
    }

    public static void renderWorldMarker(WorldRenderContext context) {
        if (!Config.strongholdTrackerEnabled || estimate == null || context.matrices() == null || context.consumers() == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        NavigationTarget target = getNavigationTarget(minecraft);
        if (target == null) {
            return;
        }

        PoseStack poseStack = context.matrices();
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();
        double minY = minecraft.level.dimensionType().minY();
        double maxY = minY + minecraft.level.dimensionType().height();

        VertexConsumer fill = context.consumers().getBuffer(RenderTypes.debugFilledBox());
        addFilledBox(
            fill,
            poseStack.last(),
            target.x() - cameraPos.x - MARKER_HALF_WIDTH,
            minY - cameraPos.y,
            target.z() - cameraPos.z - MARKER_HALF_WIDTH,
            target.x() - cameraPos.x + MARKER_HALF_WIDTH,
            maxY - cameraPos.y,
            target.z() - cameraPos.z + MARKER_HALF_WIDTH,
            target.red(),
            target.green(),
            target.blue(),
            0.08F
        );
    }

    private static String formatCandidateLine(int index, StrongholdCandidate candidate) {
        return String.format(
            Locale.ROOT,
            "%d) X %.1f  Z %.1f  Acc %.1f%%  R%d  n=%d",
            index,
            candidate.x(),
            candidate.z(),
            candidate.confidencePercent(),
            candidate.ringIndex(),
            candidate.supportingRays()
        );
    }

    private static void addFilledBox(
        VertexConsumer consumer,
        Pose pose,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        float red,
        float green,
        float blue,
        float alpha
    ) {
        addQuad(consumer, pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        addQuad(consumer, pose, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        addQuad(consumer, pose, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);
        addQuad(consumer, pose, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        addQuad(consumer, pose, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        addQuad(consumer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
    }

    private static void addQuad(
        VertexConsumer consumer,
        Pose pose,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        double x3,
        double y3,
        double z3,
        double x4,
        double y4,
        double z4,
        float red,
        float green,
        float blue,
        float alpha
    ) {
        addVertex(consumer, pose, x1, y1, z1, red, green, blue, alpha);
        addVertex(consumer, pose, x2, y2, z2, red, green, blue, alpha);
        addVertex(consumer, pose, x3, y3, z3, red, green, blue, alpha);
        addVertex(consumer, pose, x4, y4, z4, red, green, blue, alpha);
    }

    private static void addVertex(VertexConsumer consumer, Pose pose, double x, double y, double z, float red, float green, float blue, float alpha) {
        consumer.addVertex(pose, (float) x, (float) y, (float) z).setColor(red, green, blue, alpha);
    }

    private static void processPendingEyes(Minecraft client) {
        Iterator<PendingEyeObservation> iterator = pendingEyes.values().iterator();
        while (iterator.hasNext()) {
            PendingEyeObservation observation = iterator.next();
            EyeOfEnder eye = observation.eye();

            if (eye.isRemoved() || eye.level() != client.level) {
                iterator.remove();
                boolean valid = finalizeObservation(observation);
                if (!valid) {
                    clearAll();
                    statusMessage = "Eye vectors were inconsistent. Cache cleared immediately.";
                    return;
                }
                continue;
            }

            observation.capture();
            if (observation.shouldFinalize()) {
                iterator.remove();
                boolean valid = finalizeObservation(observation);
                if (!valid) {
                    clearAll();
                    statusMessage = "Eye vectors were inconsistent. Cache cleared immediately.";
                    return;
                }
            }
        }
    }

    private static boolean finalizeObservation(PendingEyeObservation observation) {
        RayObservation ray = observation.buildRayObservation();
        if (ray == null) {
            if (!observation.hasEnoughSamples()) {
                statusMessage = "Eye disappeared before enough stable samples were collected.";
            } else {
                statusMessage = "Could not stabilize that eye trajectory. Throw again.";
            }
            return true;
        }

        if (observations.size() >= MAX_OBSERVATIONS) {
            observations.remove(0);
        }

        observations.add(ray);
        return recalculateEstimate();
    }

    private static boolean recalculateEstimate() {
        if (observations.isEmpty()) {
            estimate = null;
            candidateTable.clear();
            statusMessage = "Throw an eye of ender in the Overworld to start tracking.";
            return true;
        }

        FitResult fit = fitObservations(observations);
        if (fit == null) {
            estimate = null;
            candidateTable.clear();
            statusMessage = observations.size() < 2
                ? "Saved throw 1 of 2. Move sideways and throw again."
                : "Throws are too parallel or inconsistent. Cache will be cleared.";
            return observations.size() < 2;
        }

        estimate = fit;
        candidateTable.clear();
        candidateTable.addAll(fit.candidates());

        if (fit.ignoredOutliers() > 0) {
            statusMessage = String.format(
                Locale.ROOT,
                "Estimate updated from %d inlier throw(s); ignored %d outlier(s).",
                fit.inlierCount(),
                fit.ignoredOutliers()
            );
        } else {
            statusMessage = String.format(
                Locale.ROOT,
                "Estimate updated from %d throw(s).",
                fit.inlierCount()
            );
        }
        return true;
    }

    private static FitResult fitObservations(List<RayObservation> rays) {
        if (rays.isEmpty()) {
            return null;
        }

        if (rays.size() == 1) {
            StrongholdCandidate preview = buildSingleRayCandidate(rays.get(0));
            if (preview == null) {
                return null;
            }
            return new FitResult(
                preview.x(),
                preview.z(),
                preview.rmsError(),
                preview.confidencePercent(),
                preview.supportingRays(),
                1,
                0,
                0.0,
                List.of(preview)
            );
        }

        double totalWeight = rays.stream().mapToDouble(RayObservation::baseWeight).sum();
        if (totalWeight <= 0.0) {
            return null;
        }

        Map<Long, Point2> seeds = new LinkedHashMap<>();

        Point2 weightedSeed = solveWeightedLeastSquares(rays, RayObservation::baseWeight);
        if (weightedSeed != null) {
            putUniqueSeed(seeds, weightedSeed);
        }

        for (int i = 0; i < rays.size(); i++) {
            for (int j = i + 1; j < rays.size(); j++) {
                Point2 candidate = intersect(rays.get(i), rays.get(j));
                if (candidate != null) {
                    putUniqueSeed(seeds, candidate);
                }
            }
        }

        for (RayObservation ray : rays) {
            for (RingInfo ring : RINGS) {
                Point2 candidate = intersectRayWithCircle(ray, ring.midRadius());
                if (candidate != null) {
                    putUniqueSeed(seeds, candidate);
                }
            }
        }

        List<StrongholdCandidate> ranked = new ArrayList<>();
        for (Point2 seed : seeds.values()) {
            StrongholdCandidate candidate = evaluateCandidate(seed, rays, totalWeight);
            if (candidate != null) {
                ranked.add(candidate);
            }
        }

        ranked.sort((a, b) -> {
            int cmp = Double.compare(b.confidencePercent(), a.confidencePercent());
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.score(), a.score());
            if (cmp != 0) return cmp;
            return Double.compare(a.rmsError(), b.rmsError());
        });

        if (ranked.isEmpty()) {
            return null;
        }

        StrongholdCandidate best = ranked.get(0);
        if (best.supportingRays() >= 2) {
            if (best.rmsError() > MAX_RAY_RMSE_FOR_ACCEPT || best.confidencePercent() < MIN_ACCEPT_CONFIDENCE) {
                return null;
            }
        }

        Point2 refinedPoint = refineAroundBest(rays, best);
        if (refinedPoint != null) {
            StrongholdCandidate refined = evaluateCandidate(refinedPoint, rays, totalWeight);
            if (refined != null && refined.confidencePercent() >= best.confidencePercent()) {
                best = refined;
            }
        }

        List<StrongholdCandidate> finalCandidates = buildFinalCandidateTable(rays, totalWeight, best);
        if (finalCandidates.isEmpty()) {
            return null;
        }

        StrongholdCandidate finalBest = finalCandidates.get(0);
        if (rays.size() >= 2) {
            if (finalBest.rmsError() > MAX_RAY_RMSE_FOR_ACCEPT || finalBest.confidencePercent() < MIN_ACCEPT_CONFIDENCE) {
                return null;
            }
        }

        double maxAngle = computeMaxPairAngle(rays);
        int inlierCount = finalBest.supportingRays();
        int totalCount = rays.size();
        int ignoredOutliers = Math.max(0, totalCount - inlierCount);

        return new FitResult(
            finalBest.x(),
            finalBest.z(),
            finalBest.rmsError(),
            finalBest.confidencePercent(),
            inlierCount,
            totalCount,
            ignoredOutliers,
            maxAngle,
            finalCandidates
        );
    }

    private static List<StrongholdCandidate> buildFinalCandidateTable(List<RayObservation> rays, double totalWeight, StrongholdCandidate best) {
        Map<Long, StrongholdCandidate> bestByCell = new LinkedHashMap<>();

        addCandidateIfUseful(bestByCell, best);

        Point2 bestPoint = new Point2(best.x(), best.z());
        List<Point2> extraSeeds = new ArrayList<>();
        extraSeeds.add(bestPoint);
        for (int i = 0; i < rays.size(); i++) {
            for (int j = i + 1; j < rays.size(); j++) {
                Point2 point = intersect(rays.get(i), rays.get(j));
                if (point != null) {
                    extraSeeds.add(point);
                }
            }
        }
        for (RayObservation ray : rays) {
            for (RingInfo ring : RINGS) {
                Point2 point = intersectRayWithCircle(ray, ring.midRadius());
                if (point != null) {
                    extraSeeds.add(point);
                }
            }
        }

        for (Point2 seed : extraSeeds) {
            StrongholdCandidate candidate = evaluateCandidate(seed, rays, totalWeight);
            addCandidateIfUseful(bestByCell, candidate);
        }

        List<StrongholdCandidate> result = new ArrayList<>(bestByCell.values());
        result.sort((a, b) -> {
            int cmp = Double.compare(b.confidencePercent(), a.confidencePercent());
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.score(), a.score());
            if (cmp != 0) return cmp;
            return Double.compare(a.rmsError(), b.rmsError());
        });

        if (result.size() > MAX_CANDIDATES_TO_SHOW) {
            return new ArrayList<>(result.subList(0, MAX_CANDIDATES_TO_SHOW));
        }
        return result;
    }

    private static void addCandidateIfUseful(Map<Long, StrongholdCandidate> map, StrongholdCandidate candidate) {
        if (candidate == null) {
            return;
        }
        long key = quantizeKey(candidate.x(), candidate.z());
        StrongholdCandidate current = map.get(key);
        if (current == null || candidate.score() > current.score()) {
            map.put(key, candidate);
        }
    }

    private static void putUniqueSeed(Map<Long, Point2> seeds, Point2 candidate) {
        long key = quantizeKey(candidate.x(), candidate.z());
        seeds.putIfAbsent(key, candidate);
    }

    private static long quantizeKey(double x, double z) {
        long qx = Math.round(x * 4.0);
        long qz = Math.round(z * 4.0);
        return (qx << 32) ^ (qz & 0xFFFFFFFFL);
    }

    private static Point2 refineAroundBest(List<RayObservation> rays, StrongholdCandidate best) {
        List<RayObservation> inliers = new ArrayList<>();
        Point2 point = new Point2(best.x(), best.z());

        for (RayObservation ray : rays) {
            if (ray.distanceTo(point.x(), point.z()) <= RANSAC_INLIER_THRESHOLD) {
                inliers.add(ray);
            }
        }

        if (inliers.size() < 2) {
            return null;
        }

        Point2 refined = point;
        for (int iteration = 0; iteration < 4; iteration++) {
            Point2 currentPoint = refined;
            double huberScale = Math.max(2.5, medianResidual(inliers, currentPoint) * 1.5);
            Point2 candidate = solveWeightedLeastSquares(inliers, ray -> {
                double residual = ray.distanceTo(currentPoint.x(), currentPoint.z());
                return ray.baseWeight() * huberWeight(residual, huberScale);
            });
            if (candidate == null) {
                break;
            }
            refined = candidate;
        }

        return refined;
    }

    private static StrongholdCandidate buildSingleRayCandidate(RayObservation ray) {
        StrongholdCandidate best = null;
        double pathQuality = weightedAverage(List.of(ray), r -> 0.55 * r.linearityScore() + 0.25 * r.stationaryScore() + 0.20 * r.travelScore());

        for (RingInfo ring : RINGS) {
            Point2 point = intersectRayWithCircle(ray, ring.midRadius());
            if (point == null) {
                continue;
            }

            double ringQuality = ringQuality(point);
            double confidence = Math.min(55.0, 18.0 + 28.0 * ringQuality + 14.0 * pathQuality + 8.0 * ray.travelScore());
            double score = confidence / 100.0;
            double rmsError = ray.distanceTo(point.x(), point.z());
            StrongholdCandidate candidate = new StrongholdCandidate(
                point.x(),
                point.z(),
                score,
                confidence,
                1,
                1,
                rmsError,
                ring.midRadius(),
                ring.index()
            );

            if (best == null || candidate.confidencePercent() > best.confidencePercent()) {
                best = candidate;
            }
        }

        return best;
    }

    private static StrongholdCandidate evaluateCandidate(Point2 point, List<RayObservation> rays, double totalWeight) {
        if (point == null) {
            return null;
        }

        int inlierCount = 0;
        double inlierWeight = 0.0;
        double weightedResidual = 0.0;
        double weightedResidualSquared = 0.0;
        double supportStrength = 0.0;

        for (RayObservation ray : rays) {
            double residual = ray.distanceTo(point.x(), point.z());
            if (residual <= RANSAC_INLIER_THRESHOLD) {
                inlierCount++;
                inlierWeight += ray.baseWeight();
                weightedResidual += residual * ray.baseWeight();
                weightedResidualSquared += residual * residual * ray.baseWeight();
                supportStrength += ray.baseWeight();
            }
        }

        if (rays.size() >= 2 && inlierCount < 2) {
            return null;
        }

        if (inlierWeight <= 0.0 && rays.size() >= 2) {
            return null;
        }

        double rmsError = inlierWeight <= 0.0 ? distanceFromRings(point) : Math.sqrt(weightedResidualSquared / inlierWeight);
        double radius = Math.sqrt(point.x() * point.x() + point.z() * point.z());
        RingInfo ring = nearestRing(radius);
        double ringQuality = ringQuality(point);
        double geometryQuality = rays.size() < 2 ? 0.15 : clamp01(computeMaxPairAngle(rays) / 40.0);
        double pathQuality = weightedAverage(rays, r -> 0.55 * r.linearityScore() + 0.25 * r.stationaryScore() + 0.20 * r.travelScore());
        double supportRatio = totalWeight <= 0.0 ? 0.0 : inlierWeight / totalWeight;
        double residualQuality = clamp01(1.0 - rmsError / 24.0);

        double score = 0.34 * supportRatio
            + 0.18 * geometryQuality
            + 0.18 * residualQuality
            + 0.18 * ringQuality
            + 0.12 * pathQuality;

        double confidence;
        if (rays.size() == 1) {
            confidence = Math.min(55.0, 20.0 + 28.0 * ringQuality + 14.0 * pathQuality + 8.0 * residualQuality);
        } else {
            confidence = Math.min(99.99, 58.0 + score * 41.99);
        }

        return new StrongholdCandidate(
            point.x(),
            point.z(),
            score,
            confidence,
            inlierCount,
            rays.size(),
            rmsError,
            ring.midRadius(),
            ring.index()
        );
    }

    private static double distanceFromRings(Point2 point) {
        double radius = Math.sqrt(point.x() * point.x() + point.z() * point.z());
        return Math.abs(radius - nearestRing(radius).midRadius());
    }

    private static RingInfo nearestRing(double radius) {
        RingInfo best = RINGS.get(0);
        double bestDistance = Math.abs(radius - best.midRadius());
        for (int i = 1; i < RINGS.size(); i++) {
            RingInfo ring = RINGS.get(i);
            double distance = Math.abs(radius - ring.midRadius());
            if (distance < bestDistance) {
                best = ring;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double ringQuality(Point2 point) {
        double radius = Math.sqrt(point.x() * point.x() + point.z() * point.z());
        double best = 0.0;
        for (RingInfo ring : RINGS) {
            double halfWidth = (ring.maxRadius() - ring.minRadius()) * 0.5;
            double distance = Math.abs(radius - ring.midRadius());
            double quality = 1.0 - distance / Math.max(1.0, halfWidth);
            best = Math.max(best, quality);
        }
        return clamp01(best);
    }

    private static Point2 solveWeightedLeastSquares(List<RayObservation> rays, ToDoubleFunction<RayObservation> weightProvider) {
        double a00 = 0.0;
        double a01 = 0.0;
        double a11 = 0.0;
        double b0 = 0.0;
        double b1 = 0.0;

        for (RayObservation ray : rays) {
            double weight = weightProvider.applyAsDouble(ray);
            if (weight <= 0.0) {
                continue;
            }

            double nx = -ray.dirZ();
            double nz = ray.dirX();
            double projection = nx * ray.originX() + nz * ray.originZ();

            a00 += weight * nx * nx;
            a01 += weight * nx * nz;
            a11 += weight * nz * nz;
            b0 += weight * nx * projection;
            b1 += weight * nz * projection;
        }

        double determinant = a00 * a11 - a01 * a01;
        if (Math.abs(determinant) < MIN_DETERMINANT) {
            return null;
        }

        double x = (b0 * a11 - a01 * b1) / determinant;
        double z = (a00 * b1 - b0 * a01) / determinant;
        return new Point2(x, z);
    }

    private static Point2 intersect(RayObservation first, RayObservation second) {
        double determinant = first.dirX() * second.dirZ() - first.dirZ() * second.dirX();
        if (Math.abs(determinant) < MIN_PAIR_SINE) {
            return null;
        }

        double deltaX = second.originX() - first.originX();
        double deltaZ = second.originZ() - first.originZ();

        double t = (deltaX * second.dirZ() - deltaZ * second.dirX()) / determinant;
        double u = (deltaX * first.dirZ() - deltaZ * first.dirX()) / determinant;

        // Chỉ nhận giao điểm nằm phía trước cả 2 ray
        if (t <= 0.0 || u <= 0.0) {
            return null;
        }

        return new Point2(
            first.originX() + first.dirX() * t,
            first.originZ() + first.dirZ() * t
        );
    }

    private static Point2 intersectRayWithCircle(RayObservation ray, double radius) {
        double ox = ray.originX();
        double oz = ray.originZ();
        double dx = ray.dirX();
        double dz = ray.dirZ();

        double b = ox * dx + oz * dz;
        double c = ox * ox + oz * oz - radius * radius;
        double discriminant = b * b - c;
        if (discriminant < 0.0) {
            return null;
        }

        double sqrt = Math.sqrt(discriminant);
        double t1 = -b - sqrt;
        double t2 = -b + sqrt;
        double t = chooseForwardT(t1, t2);
        if (Double.isNaN(t) || t <= 0.0) {
            return null;
        }

        return new Point2(ox + dx * t, oz + dz * t);
    }

    private static double chooseForwardT(double t1, double t2) {
        double best = Double.POSITIVE_INFINITY;
        if (t1 > 0.0) {
            best = Math.min(best, t1);
        }
        if (t2 > 0.0) {
            best = Math.min(best, t2);
        }
        return Double.isInfinite(best) ? Double.NaN : best;
    }

    private static double computeMaxPairAngle(List<RayObservation> rays) {
        double maxAngle = 0.0;
        for (int i = 0; i < rays.size(); i++) {
            for (int j = i + 1; j < rays.size(); j++) {
                double cross = Math.abs(rays.get(i).dirX() * rays.get(j).dirZ() - rays.get(i).dirZ() * rays.get(j).dirX());
                double angle = Math.toDegrees(Math.asin(Mth.clamp(cross, 0.0, 1.0)));
                maxAngle = Math.max(maxAngle, angle);
            }
        }
        return maxAngle;
    }

    private static double medianResidual(List<RayObservation> rays, Point2 point) {
        List<Double> residuals = new ArrayList<>(rays.size());
        for (RayObservation ray : rays) {
            residuals.add(ray.distanceTo(point.x(), point.z()));
        }

        residuals.sort(Comparator.naturalOrder());
        int size = residuals.size();
        if (size == 0) {
            return 2.5;
        }

        if ((size & 1) == 1) {
            return Math.max(2.5, residuals.get(size / 2));
        }

        return Math.max(2.5, (residuals.get(size / 2 - 1) + residuals.get(size / 2)) * 0.5);
    }

    private static double huberWeight(double residual, double scale) {
        if (residual <= scale) {
            return 1.0;
        }

        return scale / residual;
    }

    private static double weightedAverage(List<RayObservation> rays, ToDoubleFunction<RayObservation> valueProvider) {
        double totalWeight = 0.0;
        double weightedSum = 0.0;

        for (RayObservation ray : rays) {
            totalWeight += ray.baseWeight();
            weightedSum += ray.baseWeight() * valueProvider.applyAsDouble(ray);
        }

        return totalWeight <= 0.0 ? 0.0 : weightedSum / totalWeight;
    }

    private static void renderWaypointIndicator(GuiGraphics guiGraphics, Minecraft minecraft) {
        NavigationTarget target = getNavigationTarget(minecraft);
        if (target == null) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vec3 cameraPos = camera.position();
        Vec3 relative = new Vec3(target.x() - cameraPos.x, 0.0, target.z() - cameraPos.z);
        double horizontalDistance = Math.sqrt(relative.x * relative.x + relative.z * relative.z);
        if (horizontalDistance < 1.0E-4) {
            return;
        }

        Vector3fc leftVector = camera.leftVector();
        Vector3fc upVector = camera.upVector();
        Vector3fc lookVector = camera.forwardVector();

        Vec3 right = new Vec3(-leftVector.x(), -leftVector.y(), -leftVector.z());
        Vec3 up = new Vec3(upVector.x(), upVector.y(), upVector.z());
        Vec3 forward = new Vec3(lookVector.x(), lookVector.y(), lookVector.z());

        double x = relative.dot(right);
        double y = relative.dot(up);
        double z = relative.dot(forward);

        // Không mirror qua camera nữa
        if (z <= 0.05) {
            return; // target ở sau lưng thì không vẽ marker, tránh sinh điểm thứ 2
        }

        int width = guiGraphics.guiWidth();
        int height = guiGraphics.guiHeight();
        double aspect = (double) width / Math.max(1, height);
        double verticalFov = Math.toRadians(minecraft.options.fov().get());
        double tanY = Math.tan(verticalFov * 0.5);
        double tanX = tanY * aspect;

        double normalizedX = x / (z * tanX);
        double normalizedY = y / (z * tanY);

        int padding = 20;
        int markerX;
        int markerY;

        if (Math.abs(normalizedX) <= 1.0 && Math.abs(normalizedY) <= 1.0) {
            markerX = (int) Math.round(width * 0.5 + normalizedX * width * 0.5);
            markerY = (int) Math.round(height * 0.5 - normalizedY * height * 0.5);
        } else {
            double scale = Math.max(1.0, Math.max(Math.abs(normalizedX), Math.abs(normalizedY)));
            normalizedX /= scale;
            normalizedY /= scale;
            markerX = (int) Math.round(width * 0.5 + normalizedX * (width * 0.5 - padding));
            markerY = (int) Math.round(height * 0.5 - normalizedY * (height * 0.5 - padding));
        }

        int outlineColor = 0xFF000000;
        guiGraphics.fill(markerX - 4, markerY - 4, markerX + 5, markerY + 5, outlineColor);
        guiGraphics.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, target.argb());

        String label = String.format(Locale.ROOT, "%s %.0fm", target.label(), horizontalDistance);
        guiGraphics.drawString(minecraft.font, label, markerX + 8, markerY - 4, target.argb(), true);
    }

    private static NavigationTarget getNavigationTarget(Minecraft minecraft) {
        if (estimate == null || minecraft.player == null) {
            return null;
        }

        if (minecraft.player.level().dimension().equals(Level.NETHER)) {
            return new NavigationTarget(
                estimate.x() / 8.0,
                estimate.z() / 8.0,
                "NE",
                "Nether proxy",
                0xFF62E7FF,
                0.384F,
                0.906F,
                1.0F
            );
        }

        return new NavigationTarget(
            estimate.x(),
            estimate.z(),
            "OW",
            "Overworld stronghold",
            0xFFFFD966,
            1.0F,
            0.851F,
            0.4F
        );
    }

    private static boolean isOverworld(ClientLevel level) {
        return level.dimension().equals(Level.OVERWORLD);
    }

    private static boolean isNether(ClientLevel level) {
        return level.dimension().equals(Level.NETHER);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void clearAll() {
        observations.clear();
        estimate = null;
        pendingEyes.clear();
        candidateTable.clear();
    }

    private static final class PendingEyeObservation {
        private final EyeOfEnder eye;
        private final double spawnX;
        private final double spawnZ;
        private final double playerSpeed;
        private final List<PathSample> samples = new ArrayList<>();
        private int lastRecordedTick = Integer.MIN_VALUE;
        private int localObservationTicks;

        private PendingEyeObservation(EyeOfEnder eye, double spawnX, double spawnZ, double playerSpeed) {
            this.eye = eye;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
            this.playerSpeed = playerSpeed;
        }

        private EyeOfEnder eye() {
            return eye;
        }

        private void capture() {
            localObservationTicks++;

            if (eye.tickCount == lastRecordedTick) {
                return;
            }

            double x = eye.getX();
            double z = eye.getZ();
            if (!samples.isEmpty()) {
                PathSample last = samples.get(samples.size() - 1);
                double dx = x - last.x();
                double dz = z - last.z();
                if (dx * dx + dz * dz < MIN_SAMPLE_DELTA_SQR) {
                    lastRecordedTick = eye.tickCount;
                    return;
                }
            }

            samples.add(new PathSample(eye.tickCount, x, z));
            lastRecordedTick = eye.tickCount;
        }

        private boolean hasEnoughSamples() {
            return samples.size() >= MIN_LOCAL_SAMPLES;
        }

        private boolean shouldFinalize() {
            // Chỉ finalize khi mắt đã unload/remove
            return false;
        }

        private RayObservation buildRayObservation() {
            if (!hasEnoughSamples()) {
                return null;
            }

            List<PathSample> stableSamples = samples.subList(Math.max(0, samples.size() - STABLE_SUFFIX_SAMPLES), samples.size());
            if (stableSamples.size() < 2) {
                return null;
            }

            double endX = 0.0;
            double endZ = 0.0;
            for (PathSample sample : stableSamples) {
                endX += sample.x();
                endZ += sample.z();
            }
            endX /= stableSamples.size();
            endZ /= stableSamples.size();

            double segmentX = endX - spawnX;
            double segmentZ = endZ - spawnZ;
            double segmentLengthSqr = segmentX * segmentX + segmentZ * segmentZ;
            if (segmentLengthSqr < MIN_SEGMENT_LENGTH_SQR) {
                return null;
            }
            double segmentLength = Math.sqrt(segmentLengthSqr);

            double dirX = segmentX / segmentLength;
            double dirZ = segmentZ / segmentLength;

            double maxDeviation = 0.0;
            for (PathSample sample : stableSamples) {
                double deviation = Math.abs((-dirZ) * (sample.x() - spawnX) + dirX * (sample.z() - spawnZ));
                maxDeviation = Math.max(maxDeviation, deviation);
            }

            double linearityScore = clamp01(1.0 - maxDeviation / 0.45);
            double stationaryScore = clamp01(1.0 - playerSpeed * 4.0);
            double travelScore = clamp01(segmentLength / 2.5);
            double baseWeight = Math.max(0.15, 0.45 * stationaryScore + 0.35 * linearityScore + 0.20 * travelScore);
            double yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dirX, dirZ)));

            return new RayObservation(spawnX, spawnZ, dirX, dirZ, baseWeight, linearityScore, stationaryScore, travelScore, yaw, spawnX, spawnZ);
        }
    }

    private record PathSample(int tick, double x, double z) {
    }

    private record RayObservation(
        double originX,
        double originZ,
        double dirX,
        double dirZ,
        double baseWeight,
        double linearityScore,
        double stationaryScore,
        double travelScore,
        double yaw,
        double spawnX,
        double spawnZ
    ) {
        private double distanceTo(double x, double z) {
            return Math.abs((-dirZ) * (x - originX) + dirX * (z - originZ));
        }
    }

    private record Point2(double x, double z) {
    }

    private record RingInfo(int index, double minRadius, double maxRadius, int strongholdCount) {
        private double midRadius() {
            return (minRadius + maxRadius) * 0.5;
        }
    }

    private record StrongholdCandidate(
        double x,
        double z,
        double score,
        double confidencePercent,
        int supportingRays,
        int totalRays,
        double rmsError,
        double ringRadius,
        int ringIndex
    ) {
    }

    private record FitResult(
        double x,
        double z,
        double rmsError,
        double confidencePercent,
        int inlierCount,
        int totalCount,
        int ignoredOutliers,
        double maxAngle,
        List<StrongholdCandidate> candidates
    ) {
    }

    private record NavigationTarget(
        double x,
        double z,
        String label,
        String description,
        int argb,
        float red,
        float green,
        float blue
    ) {
    }
}
