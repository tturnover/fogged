#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 WorldOffset;

out vec4 vertexColor;
out vec2 worldXZ;
out vec3 relPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    // Camera-relative position: interpolates correctly, so the fragment shader can take a true
    // per-pixel distance (a per-vertex fog_distance would just average the far corners).
    relPos = Position;
    // Absolute world X/Z so the procedural foam pattern is anchored to the world, not the camera.
    worldXZ = Position.xz + WorldOffset.xz;
}
