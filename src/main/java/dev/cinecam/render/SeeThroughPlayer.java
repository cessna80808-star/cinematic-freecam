package dev.cinecam.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import dev.cinecam.CinecamClient;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
 * not depth-sorted against itself, so a far limb can draw over a near one. It
 * is used only while terrain blocks the view; otherwise the player is drawn
 * normally.</p>
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
            && camera.config().seeThroughPlayer
            && playerBlocked();
    }

    /** Whether terrain sits between the camera and the player's eyes or feet. */
    private static boolean playerBlocked() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        return blocked(minecraft, player, camera, player.getEyePosition())
            || blocked(minecraft, player, camera, player.position().add(0.0, 0.1, 0.0));
    }

    private static boolean blocked(Minecraft minecraft, LocalPlayer player, Vec3 from, Vec3 to) {
        return minecraft.level.clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        )).getType() != HitResult.Type.MISS;
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
                    // Without depth, culling is what stops back faces drawing over front faces.
                    .withCull(true)
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
