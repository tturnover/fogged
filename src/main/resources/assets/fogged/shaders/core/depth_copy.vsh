#version 150

in vec3 Position;

// Full-screen quad supplied directly in clip space (SceneDepth emits corners at +/-1), so no
// ModelView/Proj transform is needed -- mirrors vanilla's own screen-blit shaders.
void main() {
    gl_Position = vec4(Position, 1.0);
}
