#version 150

in vec3 Position;

// Full-screen quad handed in already in clip space (SubmergedMurk emits corners at +/-1), like
// depth_copy and vanilla's own screen blits, so there is no matrix to apply here.
void main() {
    gl_Position = vec4(Position, 1.0);
}
