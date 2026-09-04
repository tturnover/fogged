package com.fogged;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

// The murk surface as actual geometry: flat quads at the boundary, covering only the block columns
// where the surface is free to exist (see WaterlineMap.openAt -- not inside a solid block, not inside
// a liquid). A hole is a quad that was never emitted, which costs nothing to draw and nothing to
// shade, where the screen-space composite this replaces had to shade every pixel of the screen and
// discard the ones it did not want.
//
// The quads are GREEDILY MERGED: a run of open columns becomes one wide quad, and that quad grows
// down while the rows below match it. Open ground is one or two quads; a broken shoreline is tens.
// Un-merged this would be one quad per block -- 192 * 192 = 36,864 at the largest map size.
//
// Vertices are map-local block coordinates with y = 0, so the mesh does not depend on where the
// camera is or on how high the boundary sits: both are supplied by the modelview translation at draw
// time. It is therefore rebuilt only when WaterlineMap.revision() changes -- a rescan every few
// ticks, an origin move, or a resize -- and never merely because the camera moved.
final class FogPlaneMesh {

    // Blocks per skirt cell. The skirt only ever starts half a waterline map away from the camera, so
    // a cell this size is a handful of pixels out there; finer costs a level sample per cell on every
    // rebuild to resolve detail the distance fade has already washed out.
    //
    // Must DIVIDE both the map's block edge (WaterlineMap clamps it to render distance * 16, between
    // 48 and 256 -- all multiples of 16) and the skirt step (FogPlaneRenderer.SKIRT_STEP, 64). That is
    // what makes the cell grid tile the map exactly, so buildSkirt's "already covered by the near
    // mesh" test catches every inside cell and no skirt quad is ever laid coplanar over the near mesh.
    private static final int SKIRT_CELL = 16;

    private static VertexBuffer buffer;
    private static int builtRevision = Integer.MIN_VALUE;
    private static int builtSkirt = -1;
    private static int builtBoundaryY = Integer.MIN_VALUE;
    private static int quadCount;

    // The far skirt is a separate buffer because it is a separate DRAW: past the projection's own far
    // plane the near mesh is clipped away, so that stretch is drawn with an extended projection and no
    // depth test at all (see FogPlaneRenderer). It never needs rebuilding for terrain, only for reach.
    private static VertexBuffer farBuffer;
    private static int builtFarRevision = Integer.MIN_VALUE;
    private static int farQuadCount;

    private FogPlaneMesh() {
    }

    /** Quads in the current mesh, for the debug HUD. */
    static int quadCount() {
        return quadCount;
    }

    static int builtRevision() {
        return builtRevision;
    }

    // Rebuild if the openness mask has changed since the last build, or if the skirt has to reach
    // further than it did. Cheap to call every frame.
    static void ensureBuilt(Level level, int boundaryY, int skirtBlocks) {
        if (buffer != null && builtRevision == WaterlineMap.revision() && builtSkirt == skirtBlocks
                && builtBoundaryY == boundaryY) {
            return;
        }
        builtRevision = WaterlineMap.revision();
        builtSkirt = skirtBlocks;
        builtBoundaryY = boundaryY;
        build(level, boundaryY, skirtBlocks);
    }

    static void draw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        if (buffer == null || quadCount == 0) {
            return;
        }
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    /**
     * Build (if needed) and draw the distant haze: the far grid's open cells, greedily merged, in the
     * same map-local space as the near mesh so one modelview serves both.
     */
    static void drawFar(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        if (FarPlaneGrid.cells() == 0) {
            return;
        }
        if (farBuffer == null || builtFarRevision != FarPlaneGrid.revision()) {
            builtFarRevision = FarPlaneGrid.revision();
            buildFar();
        }
        if (farBuffer == null || farQuadCount == 0) {
            return;
        }
        farBuffer.bind();
        farBuffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    /** Quads in the distant haze, for the debug HUD. */
    static int farQuadCount() {
        return farQuadCount;
    }

    // Same greedy rectangle merge the near mesh uses, at LOD cell resolution instead of block
    // resolution -- an open plain out there collapses to one quad, a broken coastline to a few dozen.
    private static void buildFar() {
        int cells = FarPlaneGrid.cells();
        int cell = FarPlaneGrid.cellSize();
        // The grid is anchored in world blocks; the mesh is anchored to the waterline map's corner.
        int offX = FarPlaneGrid.originX() - (int) WaterlineMap.originX();
        int offZ = FarPlaneGrid.originZ() - (int) WaterlineMap.originZ();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        boolean[] taken = new boolean[cells * cells];
        int quads = 0;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                if (taken[z * cells + x] || !FarPlaneGrid.openAt(x, z)) {
                    continue;
                }
                int w = 1;
                while (x + w < cells && !taken[z * cells + x + w] && FarPlaneGrid.openAt(x + w, z)) {
                    w++;
                }
                int h = 1;
                while (z + h < cells && farRowMatches(taken, cells, x, z + h, w)) {
                    h++;
                }
                for (int dz = 0; dz < h; dz++) {
                    java.util.Arrays.fill(taken, (z + dz) * cells + x, (z + dz) * cells + x + w, true);
                }
                emitQuad(bb, offX + x * cell, offZ + z * cell,
                        offX + (x + w) * cell, offZ + (z + h) * cell);
                quads++;
            }
        }
        farQuadCount = quads;
        MeshData mesh = bb.build();
        if (mesh == null) {
            return;
        }
        if (farBuffer == null) {
            farBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        farBuffer.bind();
        farBuffer.upload(mesh);
        VertexBuffer.unbind();
    }

    private static boolean farRowMatches(boolean[] taken, int cells, int x, int z, int w) {
        for (int i = 0; i < w; i++) {
            if (taken[z * cells + x + i] || !FarPlaneGrid.openAt(x + i, z)) {
                return false;
            }
        }
        return true;
    }

    private static void build(Level level, int boundaryY, int skirtBlocks) {
        int size = WaterlineMap.size();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        int quads = 0;

        // Greedy rectangle merge over the openness mask. `taken` keeps a column out of later
        // rectangles once it has been claimed, so every open block ends up in exactly one quad.
        boolean[] taken = new boolean[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (taken[z * size + x] || !WaterlineMap.openAt(x, z)) {
                    continue;
                }
                // Widest run of open, unclaimed columns starting here...
                int w = 1;
                while (x + w < size && !taken[z * size + x + w] && WaterlineMap.openAt(x + w, z)) {
                    w++;
                }
                // ...then grow down while every column of that run still matches.
                int h = 1;
                while (z + h < size && rowMatches(taken, size, x, z + h, w)) {
                    h++;
                }
                for (int dz = 0; dz < h; dz++) {
                    java.util.Arrays.fill(taken, (z + dz) * size + x, (z + dz) * size + x + w, true);
                }
                emitQuad(bb, x, z, x + w, z + h);
                quads++;
            }
        }

        // Beyond the map the surface has to continue, or the murk stops at the map's edge -- short of
        // the horizon whenever the render distance outruns WaterlineMap.MAX_SIZE.
        quads += buildSkirt(bb, level, boundaryY, size, skirtBlocks);

        quadCount = quads;
        MeshData mesh = bb.build();
        if (mesh == null) {
            quadCount = 0;
            return; // nothing open anywhere in range
        }
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
    }

    /**
     * The skirt: the ring between the waterline map's edge and the fade distance, as a coarse grid cut
     * against liquid, greedily merged like the near mesh.
     *
     * <p>This used to be four blanket quads with no cut-out at all, which laid solid murk straight
     * across any ocean wider than the map. Read through the translucent water that is a hard
     * horizontal band, and it contradicts the same water inside the map, which the map's own liquid
     * mask correctly leaves as a hole.
     *
     * <p>Only LIQUID closes a skirt cell, where a near column is also closed by a solid block. One
     * sample per cell cannot tell a hill's edge from its middle, and punching out a whole cell because
     * its centre landed inside a mountain is a worse artefact than the murk lying over that mountain --
     * which is what the blanket did anyway. Water bodies are far larger than a cell, so they sample
     * cleanly, and they are the only thing this has to get right.
     */
    private static int buildSkirt(BufferBuilder bb, Level level, int boundaryY, int size, int skirtBlocks) {
        if (skirtBlocks <= 0) {
            return 0;
        }
        int lo = -skirtBlocks;
        int hi = size + skirtBlocks;
        int n = Mth.ceil((hi - lo) / (float) SKIRT_CELL);
        int mapOriginX = (int) WaterlineMap.originX();
        int mapOriginZ = (int) WaterlineMap.originZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        boolean[] open = new boolean[n * n];
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                int x0 = lo + cx * SKIRT_CELL;
                int z0 = lo + cz * SKIRT_CELL;
                // The near mesh already covers the map itself, from real block data.
                if (x0 >= 0 && z0 >= 0 && x0 + SKIRT_CELL <= size && z0 + SKIRT_CELL <= size) {
                    continue;
                }
                pos.set(mapOriginX + x0 + SKIRT_CELL / 2, boundaryY, mapOriginZ + z0 + SKIRT_CELL / 2);
                // An unloaded chunk reads as air, so it stays open -- the same thing the blanket did,
                // and far less jarring than a hole that fills in once the chunk arrives.
                open[cz * n + cx] = level.getBlockState(pos).getFluidState().isEmpty();
            }
        }

        boolean[] taken = new boolean[n * n];
        int quads = 0;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                if (taken[cz * n + cx] || !open[cz * n + cx]) {
                    continue;
                }
                int w = 1;
                while (cx + w < n && !taken[cz * n + cx + w] && open[cz * n + cx + w]) {
                    w++;
                }
                int h = 1;
                while (cz + h < n && skirtRowMatches(taken, open, n, cx, cz + h, w)) {
                    h++;
                }
                for (int dz = 0; dz < h; dz++) {
                    java.util.Arrays.fill(taken, (cz + dz) * n + cx, (cz + dz) * n + cx + w, true);
                }
                // Clamped to the skirt's own bounds: the cell grid is rounded up to cover it, so the
                // last row and column would otherwise overhang the fade distance.
                emitQuad(bb,
                        Math.max(lo, lo + cx * SKIRT_CELL), Math.max(lo, lo + cz * SKIRT_CELL),
                        Math.min(hi, lo + (cx + w) * SKIRT_CELL), Math.min(hi, lo + (cz + h) * SKIRT_CELL));
                quads++;
            }
        }
        return quads;
    }

    private static boolean skirtRowMatches(boolean[] taken, boolean[] open, int n, int x, int z, int w) {
        for (int i = 0; i < w; i++) {
            if (taken[z * n + x + i] || !open[z * n + x + i]) {
                return false;
            }
        }
        return true;
    }

    // Whether the whole run [x, x + w) of row z is open and unclaimed, i.e. the rectangle can grow
    // onto this row without leaving a ragged edge.
    private static boolean rowMatches(boolean[] taken, int size, int x, int z, int w) {
        for (int i = 0; i < w; i++) {
            if (taken[z * size + x + i] || !WaterlineMap.openAt(x + i, z)) {
                return false;
            }
        }
        return true;
    }

    // One flat quad spanning [x0, x1) x [z0, z1) in map-local block coordinates, at y = 0. Wound so
    // it faces up, though the renderer draws with culling off since the surface is seen from below too.
    private static void emitQuad(BufferBuilder bb, int x0, int z0, int x1, int z1) {
        bb.addVertex(x0, 0.0F, z0);
        bb.addVertex(x0, 0.0F, z1);
        bb.addVertex(x1, 0.0F, z1);
        bb.addVertex(x1, 0.0F, z0);
    }
}
