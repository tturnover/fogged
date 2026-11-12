package com.fogged;

import java.util.List;
import java.util.OptionalDouble;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.fogged.block.AirCompressorCore;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws what {@link AirtightDebug} sends: each room's skin -- every face where a room cell meets
 * something that is not room -- tinted by the compressor's state, and the leak path as a red line from
 * the outlet to the hole with a box round the hole itself. Drawn through walls and over the murk, so a
 * room can be read from outside it and the way to a leak followed from anywhere.
 *
 * <p>The skin is built once per snapshot (they come at most every few ticks), not once per frame,
 * relative to the room's compressor. A room riding a Sable sub-level keeps its cells at plot
 * coordinates; it is drawn through the ship's pose each frame, so it rides along with it.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class AirtightDebugRenderer {

    // Translucent, through everything, both sides of every face.
    private static final RenderType SKIN = RenderType.create("fogged_airtight_skin",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1 << 16, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private static final RenderType LINES = RenderType.create("fogged_airtight_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1 << 12, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(3.0)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /** One room, ready to draw: quad corners as x,y,z triples, four per face, and its colour. */
    private record Mesh(float[] quads, int argb, long[] leak, long anchor) {}

    private static volatile List<Mesh> meshes = List.of();

    static {
        AirtightDebug.ClientHook.sink = AirtightDebugRenderer::accept;
        AirtightSync.localEye = () -> {
            var player = Minecraft.getInstance().player;
            return player == null ? null : player.getEyePosition();
        };
    }

    private AirtightDebugRenderer() {}

    /** Touch the class so the static block hooks the payload handler up. Called from FoggedClient. */
    public static void init() {
    }

    private static void accept(AirtightDebug.Snapshot snap) {
        List<Mesh> out = new java.util.ArrayList<>(snap.rooms().size());
        for (AirtightDebug.Room room : snap.rooms()) {
            out.add(new Mesh(skin(room.cells(), room.anchor()), colour(room.state()), room.leak(), room.anchor()));
        }
        meshes = out;
    }

    // Faces between a room cell and a cell outside the room, pulled a hair into the room so they sit
    // just in front of the wall rather than in it. Relative to the compressor: a room on a Sable ship
    // is kept at plot coordinates millions of blocks out, far past where a float can place a vertex.
    private static float[] skin(long[] cells, long anchor) {
        int ax = BlockPos.getX(anchor), ay = BlockPos.getY(anchor), az = BlockPos.getZ(anchor);
        LongOpenHashSet in = new LongOpenHashSet(cells);
        FloatArrayList q = new FloatArrayList();
        final float e = 0.01F;
        for (long c : cells) {
            int x = BlockPos.getX(c) - ax, y = BlockPos.getY(c) - ay, z = BlockPos.getZ(c) - az;
            for (Direction d : Direction.values()) {
                if (in.contains(BlockPos.offset(c, d))) {
                    continue;
                }
                float x0 = x + e, y0 = y + e, z0 = z + e, x1 = x + 1 - e, y1 = y + 1 - e, z1 = z + 1 - e;
                switch (d) {
                    case DOWN -> quad(q, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                    case UP -> quad(q, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                    case NORTH -> quad(q, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                    case SOUTH -> quad(q, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                    case WEST -> quad(q, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                    case EAST -> quad(q, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                }
            }
        }
        return q.toFloatArray();
    }

    private static void quad(FloatArrayList q, float... corners) {
        q.addElements(q.size(), corners);
    }

    private static int colour(int state) {
        AirCompressorCore.State s = AirCompressorCore.State.values()[
                Math.max(0, Math.min(state, AirCompressorCore.State.values().length - 1))];
        return switch (s) {
            case SEALED -> 0x3040E060;       // green
            case PRESSURIZING -> 0x40E8D040; // yellow
            default -> 0x30E04040;           // red: a room that is still listed but not holding
        };
    }

    // After the level, and after the murk composite at the same stage, so the view sits over the murk.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        List<Mesh> list = meshes;
        if (list.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        // The event's PoseStack only carries the camera view at some stages; getModelViewMatrix() is
        // the view at every one (see FogPlaneRenderer). Put it on RenderSystem's modelview, which the
        // shaders read, and emit camera-relative positions through a plain translation.
        Vec3 cam = event.getCamera().getPosition();
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();

        VertexConsumer skin = buffers.getBuffer(SKIN);
        for (Mesh mesh : list) {
            Matrix4f m = place(mesh.anchor, cam, partial);
            int a = mesh.argb >>> 24, r = mesh.argb >> 16 & 0xFF, g = mesh.argb >> 8 & 0xFF, b = mesh.argb & 0xFF;
            float[] v = mesh.quads;
            for (int i = 0; i < v.length; i += 3) {
                skin.addVertex(m, v[i], v[i + 1], v[i + 2]).setColor(r, g, b, a);
            }
        }
        buffers.endBatch(SKIN);

        VertexConsumer lines = buffers.getBuffer(LINES);
        for (Mesh mesh : list) {
            Matrix4f m = place(mesh.anchor, cam, partial);
            // A white box on the controller, so it is clear which compressor a room belongs to.
            box(lines, m, mesh.anchor, mesh.anchor, 0.0F, 0xFFFFFFFF);
            long[] path = mesh.leak;
            for (int i = 0; i + 1 < path.length; i++) {
                line(lines, m, mesh.anchor, path[i], path[i + 1], 0xFFFF3030);
            }
            if (path.length > 0) {
                box(lines, m, mesh.anchor, path[path.length - 1], 0.1F, 0xFFFF3030); // where the air got out
            }
        }
        buffers.endBatch(LINES);
        view.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // Camera-relative placement of coordinates given relative to `anchor`: a plain offset for a room in
    // the world, and through the ship's pose this frame for one on a Sable sub-level.
    private static Matrix4f place(long anchor, Vec3 cam, float partial) {
        int ax = BlockPos.getX(anchor), ay = BlockPos.getY(anchor), az = BlockPos.getZ(anchor);
        Matrix4d m = new Matrix4d().translation(-cam.x, -cam.y, -cam.z);
        Matrix4d ship = SABLE ? SableClientCompat.plotToWorld(Minecraft.getInstance().level,
                BlockPos.of(anchor), partial) : null;
        if (ship != null) {
            m.mul(ship);
        }
        m.translate(ax, ay, az);
        return new Matrix4f(m);
    }

    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    private static void line(VertexConsumer vc, Matrix4f m, long anchor, long from, long to, int argb) {
        int ax = BlockPos.getX(anchor), ay = BlockPos.getY(anchor), az = BlockPos.getZ(anchor);
        float x0 = BlockPos.getX(from) - ax + 0.5F, y0 = BlockPos.getY(from) - ay + 0.5F, z0 = BlockPos.getZ(from) - az + 0.5F;
        float x1 = BlockPos.getX(to) - ax + 0.5F, y1 = BlockPos.getY(to) - ay + 0.5F, z1 = BlockPos.getZ(to) - az + 0.5F;
        segment(vc, m, x0, y0, z0, x1, y1, z1, argb);
    }

    private static void segment(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0,
            float x1, float y1, float z1, int argb) {
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-4F) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        vc.addVertex(m, x0, y0, z0).setColor(argb).setNormal(nx, ny, nz);
        vc.addVertex(m, x1, y1, z1).setColor(argb).setNormal(nx, ny, nz);
    }

    // The twelve edges of a cell, grown by `grow`.
    private static void box(VertexConsumer vc, Matrix4f m, long anchor, long cell, float grow, int argb) {
        float x0 = BlockPos.getX(cell) - BlockPos.getX(anchor) - grow;
        float y0 = BlockPos.getY(cell) - BlockPos.getY(anchor) - grow;
        float z0 = BlockPos.getZ(cell) - BlockPos.getZ(anchor) - grow;
        float x1 = x0 + 1 + 2 * grow, y1 = y0 + 1 + 2 * grow, z1 = z0 + 1 + 2 * grow;
        float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            float[] a = c[e[0]], b = c[e[1]];
            segment(vc, m, a[0], a[1], a[2], b[0], b[1], b[2], argb);
        }
    }

    // Leaving a server clears whatever it last sent.
    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        meshes = List.of();
        AirtightSync.localSheltered = false;
        AirtightSync.clientRoom = AirtightSync.ClientRoom.NONE;
    }
}
