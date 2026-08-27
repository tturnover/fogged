// Shared 3D value-noise fbm used by fog_plane.fsh and fog_vapor.fsh (kept identical between the two so
// the plane and vapour layers share one boil pattern). Pure function of its parameters -- no uniforms
// referenced -- so it is safe to import at any point in the includer, before or after other uniforms.

float fogged_h31(vec3 ip, float per) {
    vec2 w = mod(ip.xy, vec2(per)); // wrap the (already-integer) spatial lattice into one tile; GLSL mod >= 0
    float n = w.x * 127.1 + w.y * 311.7 + ip.z * 74.7;
    return fract(sin(n) * 43758.5453);
}

float fogged_vnoise3(vec3 p, float per) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(fogged_h31(i + vec3(0,0,0), per), fogged_h31(i + vec3(1,0,0), per), f.x),
                  mix(fogged_h31(i + vec3(0,1,0), per), fogged_h31(i + vec3(1,1,0), per), f.x), f.y);
    float b = mix(mix(fogged_h31(i + vec3(0,0,1), per), fogged_h31(i + vec3(1,0,1), per), f.x),
                  mix(fogged_h31(i + vec3(0,1,1), per), fogged_h31(i + vec3(1,1,1), per), f.x), f.y);
    return mix(a, b, f.z);
}

// Fractal noise: stack octaves at doubling frequency; each octave wraps at twice the period of the last
// so the whole sum stays periodic. The time axis scales up per octave so large blobs drift slowly while
// fine detail churns faster. `per` is the base spatial period in the passed p-coordinate space.
float fogged_fbm3(vec2 p, float t, float per) {
    float v = 0.0;
    float amp = 0.5;
    float pr = per;
    for (int i = 0; i < 4; i++) {
        v += amp * fogged_vnoise3(vec3(p, t), pr);
        p *= 2.0;
        pr *= 2.0;
        t *= 1.7;
        amp *= 0.5;
    }
    return v / 0.9375;
}
