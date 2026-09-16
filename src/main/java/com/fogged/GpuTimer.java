package com.fogged;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

// GPU wall time of a span of draws, for the debug HUD: a GL_TIME_ELAPSED query per frame, read back
// a frame later so it never stalls the pipeline. Only ever driven while the HUD is on.
final class GpuTimer {

    private final int[] queries = new int[2];
    private int frame;
    private boolean pending;
    private boolean active;
    double lastMs;

    void begin() {
        if (queries[0] == 0) {
            GL15.glGenQueries(queries);
        }
        int previous = (frame + 1) & 1;
        if (pending && GL15.glGetQueryObjecti(queries[previous], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            lastMs = GL33.glGetQueryObjectui64(queries[previous], GL15.GL_QUERY_RESULT) / 1.0e6;
        }
        // Time queries cannot nest: with someone else's open (another mod's profiler, a nested level
        // pass) ours would fail to begin and the end would have nothing to end -- a GL error a frame.
        if (GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) {
            active = false;
            return;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[frame]);
        active = true;
    }

    void end() {
        if (!active) {
            return;
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        active = false;
        pending = true;
        frame = (frame + 1) & 1;
    }
}
