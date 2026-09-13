#version 150

in vec3 Position;

// Full-screen quad supplied directly in clip space (NoiseField emits corners at +/-1).
void main() {
    gl_Position = vec4(Position, 1.0);
}
