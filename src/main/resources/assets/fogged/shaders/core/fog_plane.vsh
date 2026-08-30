#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 MeshOffset;    // map corner and boundary height, relative to the camera
uniform vec2 WorldOffsetXZ; // map origin, minus the anchor tile (see FogPlaneRenderer.NOISE_ANCHOR)

out vec3 relPos;
out vec2 worldXZ;

// The surface is a merged quad mesh in map-local block coordinates at y = 0 (see FogPlaneMesh). The
// modelview carries both the camera and the translation to the map's world corner and the boundary
// height, so Position stays independent of where the camera is and how high the boundary sits.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // Camera-relative WORLD position -- the same translation the modelview applies, but without the
    // camera rotation. The fragment shader measures its distances from this, and fog_distance's
    // cylinder shape needs a real up axis, which view space would have rotated away.
    relPos = Position + MeshOffset;
    // Absolute world X/Z so the procedural foam and noise are anchored to the world, not the camera.
    worldXZ = Position.xz + WorldOffsetXZ;
}
