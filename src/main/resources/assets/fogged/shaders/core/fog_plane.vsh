#version 150

in vec3 Position;

out vec2 ndc;

// Full-screen quad supplied directly in clip space (FogPlaneRenderer emits corners at +/-1), so no
// ModelView/Proj transform is needed -- mirrors depth_copy.vsh and vanilla's own screen blits.
//
// The plane is no longer geometry: the fragment shader rebuilds a view ray per pixel and intersects
// the boundary analytically, so this stage only has to hand it the pixel's clip-space position.
void main() {
    gl_Position = vec4(Position, 1.0);
    ndc = Position.xy;
}
