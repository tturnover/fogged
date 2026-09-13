package com.fogged;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.opengl.GL20;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * Splices this mod's fragment code into a shader pack's gbuffers program as Iris compiles it (called
 * from the ShaderCreator mixin), so the murk can be drawn through the pack -- see IrisCompatibility
 * for why, and fogged_iris.glsl for what goes in.
 *
 * <p>Only the two programs this mod draws with are touched: the pack's replacements for vanilla's
 * position_tex_color (the plane) and position_color (the vapour), by the names Iris gives them. The
 * source arriving here has already been through Iris' own transform, so it is plain modern GLSL with
 * exactly one {@code void main()}: the helpers go in front of it, the one call at the top of it.
 * The call is a no-op until a draw sets {@code fogged_Mode}, so every other draw through the same
 * program is untouched. A pack that never declared the Iris uniforms the code reconstructs positions
 * from gets them declared here; Iris fills whatever a program declares.
 */
public final class IrisShaderPatcher {

    private static final String MARKER = "// fogged:iris";
    private static final Set<String> PROGRAMS = Set.of("textured_color", "basic_color");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final String[] INCLUDES = { "fogged_dither", "fogged_noise", "fogged_field", "fogged_iris" };
    private static final String[] IRIS_UNIFORMS = {
            "uniform float viewWidth;", "uniform float viewHeight;",
            "uniform mat4 gbufferProjection;", "uniform mat4 gbufferProjectionInverse;",
            "uniform mat4 gbufferModelViewInverse;" };

    private IrisShaderPatcher() {
    }

    /** Whether {@code name} (Iris' ShaderKey name) is a program this mod draws through. */
    public static boolean patches(String name) {
        return name != null && PROGRAMS.contains(name);
    }

    /**
     * The fragment source with this mod's code spliced in, or null if it cannot be: no main, already
     * patched, or -- checked by compiling it here, since Iris compiles the result too late to fall
     * back -- the spliced source does not compile against this pack. The caller keeps the original
     * then, and the draws through it run unshaped (see IrisCompatibility#draw).
     */
    public static String patchFragment(String source) throws IOException {
        if (source == null || source.contains(MARKER)) {
            return null;
        }
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            return null;
        }
        StringBuilder header = new StringBuilder(MARKER).append(" begin\n");
        for (String decl : IRIS_UNIFORMS) {
            // Name only: a pack declares these in its own words ("uniform mat4  gbufferModelViewInverse;").
            String name = decl.substring(decl.lastIndexOf(' ') + 1, decl.length() - 1);
            if (!Pattern.compile("uniform[^;]*\\b" + name + "\\b[^;]*;").matcher(source).find()) {
                header.append(decl).append('\n');
            }
        }
        // The noise includes tile at the anchor period, declared by their includer (see Config's
        // mirrored constants).
        header.append("const float NOISE_PERIOD_BLOCKS = 4096.0;\n");
        for (String include : INCLUDES) {
            header.append(include(include)).append('\n');
        }
        header.append(MARKER).append(" end\n\n");
        String patched = source.substring(0, main.start())
                + header
                + source.substring(main.start(), main.end())
                + "\n    fogged_apply();\n"
                + source.substring(main.end());
        String error = compileError(patched);
        if (error != null) {
            throw new IOException("the patched fragment does not compile: " + error.strip());
        }
        return patched;
    }

    // Compile the source as a throwaway fragment shader and return the info log on failure, null on
    // success. On the render thread, as Iris' own compile is.
    private static String compileError(String source) {
        int shader = GlStateManager.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        try {
            GlStateManager.glShaderSource(shader, java.util.List.of(source));
            GlStateManager.glCompileShader(shader);
            if (GlStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                return GlStateManager.glGetShaderInfoLog(shader, 32768);
            }
            return null;
        } finally {
            GlStateManager.glDeleteShader(shader);
        }
    }

    private static String include(String name) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "shaders/include/" + name + ".glsl");
        Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id);
        return resource.openAsReader().lines()
                // Core-shader directives have no place inside a foreign program.
                .filter(line -> !line.startsWith("#version") && !line.startsWith("#moj_import"))
                .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
                .toString();
    }
}
