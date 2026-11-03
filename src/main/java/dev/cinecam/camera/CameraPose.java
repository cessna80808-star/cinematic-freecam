package dev.cinecam.camera;

import net.minecraft.world.phys.Vec3;

/**
 * A single frame of the cinematic camera, expressed in Minecraft's world space
 * and rotation conventions (yaw degrees clockwise from south, pitch positive
 * looking down, roll degrees clockwise).
 */
public record CameraPose(Vec3 pos, float yaw, float pitch, float roll) {
}
