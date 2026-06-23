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
    // Camera-relative position for a true per-pixel fog/rim distance in the fragment shader.
    relPos = Position;
    // Absolute world X/Z so the wisp noise is anchored to the world (drifts, doesn't swim with camera).
    worldXZ = Position.xz + WorldOffset.xz;
}
