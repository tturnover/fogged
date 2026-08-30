package com.fogged;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// Openness of the distant murk, at LOD resolution, so the far haze is cut against Distant Horizons'
// terrain instead of painted over it.
//
// The near mesh gets its cut-out from real block data (WaterlineMap.openAt); out here there are no
// loaded chunks, and the depth buffer is no help either -- DH renders LODs with its own extended
// projection, so their depth values do not mean what vanilla's inverse thinks they mean. Asking DH for
// the terrain height at a column is the only reading that agrees with what is actually on screen.
//
// Sampled on a BACKGROUND THREAD, a small batch at a time. Each sample is a query into DH's data repo,
// and the first version of this ran a batch of them inline from the render handler -- which is called
// per frame, not per tick, so it fired thousands of blocking queries a second and hung the game on
// world load. Nothing here may block the render thread: it reads a published snapshot and never waits.
final class FarPlaneGrid {

    private static final int CELL = 64;             // blocks per grid cell
    private static final int BATCH = 64;            // cells sampled per background pass
    private static final long BATCH_INTERVAL_NANOS = 50_000_000L; // at most ~20 passes a second
    private static final long RESCAN_INTERVAL_NANOS = 10_000_000_000L; // re-walk; LODs stream in
    // DH's getColumnDataAtBlockPos blocks on a CompletableFuture -- it waits for the column to be
    // fetched or generated, and for terrain that does not exist yet it may never come back. One stuck
    // call would otherwise hold BUSY forever and the grid would sit empty with no sign of why.
    private static final long STALL_NANOS = 5_000_000_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Fogged far-plane grid");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    // Published whole, never mutated in place, so the render thread always reads a coherent grid.
    private static volatile boolean[] open = new boolean[0];
    private static volatile int cells;
    private static volatile int revision;
    private static volatile int sampled;

    private static boolean[] working = new boolean[0];
    private static int originX;
    private static int originZ;
    private static int cursor;
    private static long lastBatchNanos;
    private static long lastFullPassNanos;
    private static volatile long batchStartNanos;
    private static volatile boolean stalled;

    private FarPlaneGrid() {
    }

    static int revision() {
        return revision;
    }

    static int cellSize() {
        return CELL;
    }

    /** Grid edge in cells; 0 when there is nothing to draw. */
    static int cells() {
        return cells;
    }

    static int originX() {
        return originX;
    }

    static int originZ() {
        return originZ;
    }

    /** How many cells have a real answer yet, for the debug HUD. */
    static int sampledCells() {
        return sampled;
    }

    /** True once a terrain query has hung long enough that we have stopped asking. */
    static boolean stalled() {
        return stalled;
    }

    /** True where the murk may exist: LOD terrain there is below the boundary, or simply unknown. */
    static boolean openAt(int cellX, int cellZ) {
        boolean[] grid = open;
        int size = cells;
        if (cellX < 0 || cellX >= size || cellZ < 0 || cellZ >= size) {
            return true; // off the grid: assume open, the haze fades out there anyway
        }
        return grid[cellZ * size + cellX];
    }

    /**
     * Re-centre on the camera and queue a batch of samples. Returns immediately; the sampling itself
     * happens on the worker.
     */
    static void update(Vec3 cam, float reachBlocks, double surfaceY) {
        int want = Math.max(2, Mth.ceil(reachBlocks * 2.0F / CELL));
        int cornerX = (Mth.floor(cam.x) - want * CELL / 2) & ~(CELL - 1);
        int cornerZ = (Mth.floor(cam.z) - want * CELL / 2) & ~(CELL - 1);

        if (want != cells || cornerX != originX || cornerZ != originZ) {
            if (BUSY.get()) {
                return; // a batch is mid-flight against the old grid; re-centre next frame
            }
            // Everything the grid held was for other columns. Start open rather than closed: a haze
            // that has not resolved yet is far less jarring than a hole in it that fills in later.
            working = new boolean[want * want];
            java.util.Arrays.fill(working, true);
            originX = cornerX;
            originZ = cornerZ;
            cells = want;
            open = working.clone();
            cursor = 0;
            sampled = 0;
            revision++;
        }

        long now = System.nanoTime();
        if (BUSY.get()) {
            if (now - batchStartNanos > STALL_NANOS) {
                stalled = true; // the worker is parked in DH; stop queueing behind it
            }
            return;
        }
        if (stalled || now - lastBatchNanos < BATCH_INTERVAL_NANOS || !BUSY.compareAndSet(false, true)) {
            return;
        }
        lastBatchNanos = now;
        batchStartNanos = now;
        if (now - lastFullPassNanos > RESCAN_INTERVAL_NANOS) {
            lastFullPassNanos = now;
            cursor = 0;
        }
        int size = cells;
        int startX = originX;
        int startZ = originZ;
        int from = cursor;
        cursor = Math.min(from + BATCH, size * size);
        WORKER.execute(() -> sample(size, startX, startZ, from, Math.min(from + BATCH, size * size),
                surfaceY));
    }

    // Worker side. Touches only `working`, then publishes a copy -- so a half-finished batch is never
    // visible to the renderer, and the renderer never has to lock to read it.
    private static void sample(int size, int startX, int startZ, int from, int to, double surfaceY) {
        try {
            if (working.length != size * size || startX != originX || startZ != originZ) {
                return; // the grid moved under us; this batch is for columns nobody is asking about
            }
            int found = 0;
            boolean changed = false;
            for (int i = from; i < to; i++) {
                int cx = i % size;
                int cz = i / size;
                // One column stands in for the whole cell, which is the trade this class exists to make.
                int top = DistantHorizonsCompatibility.lodTopY(
                        startX + cx * CELL + CELL / 2, startZ + cz * CELL + CELL / 2);
                boolean isOpen = top == Integer.MIN_VALUE || top < surfaceY;
                if (working[i] != isOpen) {
                    working[i] = isOpen;
                    changed = true;
                }
                if (top != Integer.MIN_VALUE) {
                    found++;
                }
            }
            if (changed) {
                open = working.clone();
                revision++;
            }
            sampled = Math.min(size * size, sampled + found);
        } catch (RuntimeException | LinkageError e) {
            if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
                Fogged.LOGGER.warn("Fogged: far-plane LOD sampling failed; the distant murk will not be "
                        + "cut against LOD terrain", e);
            }
        } finally {
            stalled = false; // it came back after all
            BUSY.set(false);
        }
    }
}
