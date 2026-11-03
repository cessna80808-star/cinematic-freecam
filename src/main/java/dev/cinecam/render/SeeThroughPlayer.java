package dev.cinecam.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import dev.cinecam.CinecamClient;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the local player over the terrain while the cinematic camera is
 * engaged. This is deliberately a player-only pass rather than deleting chunk
 * sections: sections are 16x16x16, so at Cinecam's normal six-block distance
 * hiding a section would remove the floor and walls around the player too.
 *
 * <p>The replacement render type keeps the vanilla entity shader and texture
 * but uses {@link CompareOp#ALWAYS_PASS} with depth writes off. The player is
 * still depth-sorted against nothing, so it appears through walls without
 * changing the world mesh or the camera collision rig.</p>
 */
public final class SeeThroughPlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cinecam");
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    private static EntityRenderState localPlayerState;
    private static RenderPipeline pipeline;
    private static boolean pipelineFailed;

    private SeeThroughPlayer() {
    }

    /** Captures the render state the dispatcher created for the local player. */
    public static void capture(EntityRenderState state) {
        localPlayerState = state;
    }

    /** True only for the local player's state and only while Cinecam is active. */
    public static boolean shouldApply(EntityRenderState state) {
        if (state == null || state != localPlayerState) {
            return false;
        }
        CineCamera camera = CinecamClient.cameraInstance();
        return camera != null
            && camera.isEngaged()
            && camera.config().seeThroughPlayer;
    }

    /**
     * Returns a cached depth-test-free copy of the render type for this skin.
     * If the pipeline cannot be created, the vanilla type is returned and the
     * game keeps rendering normally.
     */
    public static RenderType typeFor(Identifier texture, RenderType fallback) {
        if (texture == null) {
            return fallback;
        }
        RenderType cached = TYPES.get(texture);
        if (cached != null) {
            return cached;
        }
        try {
            RenderPipeline activePipeline = pipeline();
            if (activePipeline == null) {
                return fallback;
            }
            RenderType type = RenderType.create(
                "cinecam_see_through",
                RenderSetup.builder(activePipeline)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup()
            );
            TYPES.put(texture, type);
            return type;
        } catch (Throwable throwable) {
            LOGGER.warn("[Cinecam] Could not create the see-through player render type", throwable);
            return fallback;
        }
    }

    private static synchronized RenderPipeline pipeline() {
        if (pipelineFailed) {
            return null;
        }
        if (pipeline == null) {
            try {
                pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/cinecam_see_through_entity")
                    .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                    .withShaderDefine("PER_FACE_LIGHTING")
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());
            } catch (Throwable throwable) {
                pipelineFailed = true;
                LOGGER.warn("[Cinecam] Could not build the see-through player pipeline", throwable);
                return null;
            }
        }
        return pipeline;
    }
}
