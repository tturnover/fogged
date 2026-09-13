#version 150

in vec3 Position;

// Full-screen quad supplied directly in clip space (MurkComposite emits corners at +/-1), as
// depth_copy.vsh does.
void main() {
    gl_Position = vec4(Position, 1.0);
}
