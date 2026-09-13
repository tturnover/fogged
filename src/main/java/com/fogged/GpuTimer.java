package com.fogged;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

// GPU wall time of a span of draws, for the debug HUD: a GL_TIME_ELAPSED query per frame, read back
// a frame later so it never stalls the pipeline. Only ever driven while the HUD is on.
final class GpuTimer {

    private final int[] queries = new int[2];
    private int frame;
    private boolean pending;
    double lastMs;

    void begin() {
        if (queries[0] == 0) {
            GL15.glGenQueries(queries);
        }
        int previous = (frame + 1) & 1;
        if (pending && GL15.glGetQueryObjecti(queries[previous], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            lastMs = GL33.glGetQueryObjectui64(queries[previous], GL15.GL_QUERY_RESULT) / 1.0e6;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[frame]);
    }

    void end() {
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending = true;
        frame = (frame + 1) & 1;
    }
}
