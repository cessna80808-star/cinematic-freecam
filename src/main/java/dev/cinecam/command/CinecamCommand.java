package dev.cinecam.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.cinecam.CinecamClient;
import dev.cinecam.Shot;
import dev.cinecam.camera.CineCamera;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Client-side {@code /cinecam} command for driving shots without the keybinds.
 */
public final class CinecamCommand {
    private CinecamCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("cinecam")
                .executes(context -> {
                    context.getSource().sendFeedback(Component.literal(
                        "Cinecam: /cinecam on|off|toggle|shot <name>|next|freeze|director|dynamic|speed|reload"
                    ));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(context -> {
                    camera().setEnabled(true);
                    context.getSource().sendFeedback(Component.literal(
                        "Cinecam on: " + camera().shot().displayName()
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    camera().setEnabled(false);
                    context.getSource().sendFeedback(Component.literal("Cinecam off"));
                    return 1;
                }))
                .then(ClientCommands.literal("toggle").executes(context -> {
                    camera().toggle();
                    context.getSource().sendFeedback(Component.literal(
                        "Cinecam " + (camera().isEnabled() ? "on" : "off")
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("next").executes(context -> {
                    camera().nextShot();
                    context.getSource().sendFeedback(Component.literal("Shot: " + camera().shot().displayName()));
                    return 1;
                }))
                .then(ClientCommands.literal("shot")
                    .then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (Shot shot : Shot.values()) {
                                builder.suggest(shot.name().toLowerCase(Locale.ROOT));
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            Shot shot = Shot.byName(name).orElse(null);
                            if (shot == null) {
                                context.getSource().sendError(Component.literal("Unknown shot: " + name));
                                return 0;
                            }
                            camera().setShot(shot);
                            context.getSource().sendFeedback(Component.literal("Shot: " + shot.displayName()));
                            return 1;
                        })))
                .then(ClientCommands.literal("freeze").executes(context -> {
                    camera().toggleFreeze();
                    context.getSource().sendFeedback(Component.literal(
                        "Cinecam " + (camera().isFrozen() ? "frozen" : "playing")
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("director").executes(context -> {
                    camera().toggleDirector();
                    context.getSource().sendFeedback(Component.literal(
                        "Director " + (camera().isDirectorEnabled() ? "on" : "off")
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("dynamic")
                    .executes(context -> {
                        camera().toggleDynamic();
                        context.getSource().sendFeedback(Component.literal(
                            "Dynamic camera " + (camera().isDynamicEnabled() ? "on" : "off (classic)")
                        ));
                        return 1;
                    })
                    .then(ClientCommands.literal("on").executes(context -> {
                        camera().setDynamic(true);
                        context.getSource().sendFeedback(Component.literal("Dynamic camera on"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        camera().setDynamic(false);
                        context.getSource().sendFeedback(Component.literal("Dynamic camera off (classic)"));
                        return 1;
                    })))
                .then(ClientCommands.literal("speed").executes(context -> {
                    camera().cycleSpeed();
                    context.getSource().sendFeedback(Component.literal(
                        "Speed: " + camera().speedFactor() + "x"
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(context -> {
                    CinecamClient.getInstance().reloadConfig();
                    context.getSource().sendFeedback(Component.literal("Cinecam config reloaded"));
                    return 1;
                }))
            )
        );
    }

    private static CineCamera camera() {
        return CinecamClient.getInstance().camera();
    }
}
