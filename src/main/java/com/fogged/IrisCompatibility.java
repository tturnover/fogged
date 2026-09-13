package com.fogged;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.fml.ModList;

/**
 * Everything the renderer needs from Iris, and the one way it draws while a shader pack is active.
 * Iris is optional: only {@link Api} names an Iris class, and it is loaded only behind the ModList
 * check -- the CreateCompatibility / YaclCompatibility pattern.
 *
 * <p>Why a separate path exists: with a pack loaded, Iris turns off colour and depth writes for any
 * ShaderInstance that is not one of its own, so this mod's core shaders draw nothing. What survives
 * is a draw through a vanilla shader Iris knows -- it swaps in the pack's matching gbuffers program,
 * so the murk is lit, shadowed and fogged like the rest of the world -- and that program has been
 * patched at compile time (see IrisShaderPatcher) to carry this mod's shaping code, switched on by
 * the {@code fogged_Mode} uniform set right here, for this draw only. Block Dithering does the same
 * to the pack's terrain program; this is that idea applied to a couple of quads.
 */
public final class IrisCompatibility {

    private static final boolean IRIS = ModList.get().isLoaded("iris");

    // One dynamic buffer per vertex format this mod draws with under a pack; allocated on first use.
    private static VertexBuffer planeBuffer;
    private static VertexBuffer vaporBuffer;

    private IrisCompatibility() {
    }

    /** True when Iris is installed AND a shader pack is currently driving the world render. */
    public static boolean shaderPackActive() {
        return IRIS && Api.shaderPackActive();
    }

    /** True while Iris is rendering the shadow map -- nothing this mod draws belongs in it. */
    public static boolean renderingShadowPass() {
        return IRIS && Api.renderingShadowPass();
    }

    /** The uniforms a draw sets on the pack's program; see {@link #draw}. */
    @FunctionalInterface
    public interface Uniforms {
        void set(int program);
    }

    /**
     * Draw {@code mesh} through {@code shader} (one of Iris' replacements for a vanilla shader), with
     * this mod's uniforms set on the pack's program in between {@code apply()} and the draw call.
     * {@code BufferUploader.drawWithShader} does apply-draw-clear in one go with no hook between, so
     * this is that sequence taken apart. The mode uniform is reset afterwards: the program is shared
     * with everything else the game draws through the same vanilla shader, and GL uniform state
     * persists on it.
     */
    public static void draw(MeshData mesh, ShaderInstance shader, boolean plane, Uniforms uniforms) {
        RenderSystem.assertOnRenderThread();
        // Not one of Iris' own replacements: the pipeline is between packs (a reload, the first frames
        // of a world) and handed out vanilla's shader, which has none of this code and would be
        // blocked anyway. Nothing to draw with this frame.
        if (!shader.getClass().getName().startsWith("net.irisshaders")) {
            mesh.close();
            return;
        }
        VertexBuffer buffer = plane ? planeBuffer : vaporBuffer;
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            if (plane) {
                planeBuffer = buffer;
            } else {
                vaporBuffer = buffer;
            }
        }
        // Vanilla tracks which immediate buffer it has bound; binding ours behind its back would leave
        // that stale, so tell it to forget first.
        BufferUploader.reset();
        VertexFormat.Mode drawMode = mesh.drawState().mode(); // upload() closes the mesh
        buffer.bind();
        buffer.upload(mesh);
        shader.setDefaultUniforms(drawMode, RenderSystem.getModelViewMatrix(),
                RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
        shader.apply();
        int program = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int mode = GL20.glGetUniformLocation(program, "fogged_Mode");
        if (mode >= 0) {
            GL20.glUniform1i(mode, plane ? 1 : 2);
            uniforms.set(program);
            buffer.draw();
            GL20.glUniform1i(mode, 0);
        } else {
            // The program carries none of this mod's code: the mixin did not apply, or the patch
            // failed to compile against this pack (logged there). The plane still draws -- flat, with
            // its foam, to the render distance -- because a murk that suffocates you should be seen;
            // a mist sheet with nothing to shape it is a solid quad, so the vapour does not.
            logUnshapedOnce();
            if (plane) {
                buffer.draw();
            }
        }
        shader.clear();
        VertexBuffer.unbind();
    }

    private static boolean loggedUnshaped;

    private static void logUnshapedOnce() {
        if (loggedUnshaped) {
            return;
        }
        loggedUnshaped = true;
        if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
            Fogged.LOGGER.warn("Fogged: the shader pack's program has none of this mod's code in it, so "
                    + "the murk surface draws flat (no rim fade, dither or dissolve discs) and without its "
                    + "vapour. See the earlier Fogged log lines from the pack's compile for why.");
        }
    }

    /**
     * Draw {@code mesh} through {@code shader} into framebuffer {@code fbo} rather than wherever the
     * shader's apply() binds: Iris' replacements bind the pack's own framebuffer there, which is the
     * one place a draw meant for one of this mod's targets must not land. Works the same with vanilla's
     * plain shaders, whose apply() binds nothing. Colour and depth state are the caller's.
     */
    public static void drawInto(MeshData mesh, ShaderInstance shader, int fbo) {
        RenderSystem.assertOnRenderThread();
        if (fbo == 0) {
            mesh.close();
            return;
        }
        if (planeBuffer == null) {
            planeBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        BufferUploader.reset();
        VertexFormat.Mode drawMode = mesh.drawState().mode();
        planeBuffer.bind();
        planeBuffer.upload(mesh);
        shader.setDefaultUniforms(drawMode, RenderSystem.getModelViewMatrix(),
                RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
        shader.apply();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        planeBuffer.draw();
        shader.clear();
        VertexBuffer.unbind();
    }

    // Uniform setters by name on the bound program. A location of -1 (the pack's program was not
    // patched, or the uniform was optimised out) is silently ignored by GL, so none of these check.
    // Looked up per call rather than cached: GL recycles program ids across pack reloads, so a cache
    // keyed on them could go stale, and the lookups are client-side.
    public static void uniform1f(int program, String name, float v) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), v);
    }

    public static void uniform1i(int program, String name, int v) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), v);
    }

    public static void uniform2f(int program, String name, float x, float y) {
        GL20.glUniform2f(GL20.glGetUniformLocation(program, name), x, y);
    }

    public static void uniform3f(int program, String name, float x, float y, float z) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, name), x, y, z);
    }

    public static void uniform4f(int program, String name, float x, float y, float z, float w) {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, name), x, y, z, w);
    }

    public static void uniform4fv(int program, String name, float[] v) {
        GL20.glUniform4fv(GL20.glGetUniformLocation(program, name), v);
    }

    // The texture units this mod's own textures ride on inside a pack's program: the last ones the
    // GPU offers. Iris hands out units to a program's samplers from 0 up, so the top ones are free
    // unless a pack has declared every unit there is.
    private static int topUnit = -1;

    /** Bind {@code texture} on spare unit {@code slot} (0 = the topmost) and point sampler {@code name} at it. */
    public static void sampler(int program, String name, int texture, int slot) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc < 0) {
            return;
        }
        if (topUnit < 0) {
            topUnit = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS) - 1;
        }
        int unit = topUnit - slot;
        // Raw GL, as Iris binds its own samplers: GlStateManager's tracking stops at the units
        // vanilla itself uses.
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(loc, unit);
    }

    /** The projection the pack rendered the world with this frame (what its depth encodes). */
    public static Matrix4f gbufferProjection() {
        return Api.gbufferProjection();
    }

    /** The camera view the pack rendered the world with this frame. */
    public static Matrix4f gbufferModelView() {
        return Api.gbufferModelView();
    }

    // The only class here that names Iris; the JVM does not load it until a method is called, and
    // every call sits behind the IRIS check.
    private static final class Api {
        static boolean shaderPackActive() {
            return IrisApi.getInstance().isShaderPackInUse();
        }

        static boolean renderingShadowPass() {
            return IrisApi.getInstance().isRenderingShadowPass();
        }

        static Matrix4f gbufferProjection() {
            return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        }

        static Matrix4f gbufferModelView() {
            return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        }
    }
}
