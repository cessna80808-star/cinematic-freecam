package dev.cinecam.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.cinecam.CinecamClient;
import dev.cinecam.Shot;
import dev.cinecam.camera.CineCamera;
import dev.cinecam.gui.CinecamScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Client-side {@code /freecam} and {@code /cinecam} commands for opening the
 * studio and driving shots without the keybinds.
 *
 * <p>The primary help is intentionally short. Older subcommands remain as
 * compatibility aliases, but the studio and keybinds are the normal path for
 * the advanced toggles.</p>
 */
public final class CinecamCommand {
    private CinecamCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("freecam")
                .executes(CinecamCommand::openStudio));

            dispatcher.register(ClientCommands.literal("cinecam")
                .executes(CinecamCommand::status)
                .then(ClientCommands.literal("help").executes(CinecamCommand::help))
                .then(ClientCommands.literal("gui").executes(CinecamCommand::openStudio))
                .then(ClientCommands.literal("on").executes(context -> setCamera(context, true)))
                .then(ClientCommands.literal("off").executes(context -> setCamera(context, false)))
                .then(ClientCommands.literal("toggle").executes(CinecamCommand::toggleCamera))
                .then(ClientCommands.literal("shot")
                    .executes(CinecamCommand::nextShot)
                    .then(ClientCommands.literal("next").executes(CinecamCommand::nextShot))
                    .then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (Shot shot : Shot.values()) {
                                builder.suggest(shot.name().toLowerCase(Locale.ROOT));
                            }
                            return builder.buildFuture();
                        })
                        .executes(CinecamCommand::selectShot)))
                .then(ClientCommands.literal("next").executes(CinecamCommand::nextShot))
                .then(ClientCommands.literal("manual")
                    .executes(context -> setManual(context, null))
                    .then(ClientCommands.literal("on").executes(context -> setManual(context, true)))
                    .then(ClientCommands.literal("off").executes(context -> setManual(context, false))))
                .then(ClientCommands.literal("speed").executes(CinecamCommand::cycleSpeed))
                .then(ClientCommands.literal("reload").executes(CinecamCommand::reload))
                // Compatibility aliases. These stay functional, but the short
                // help and README no longer make users learn them.
                .then(ClientCommands.literal("freeze").executes(CinecamCommand::toggleFreeze))
                .then(ClientCommands.literal("director").executes(CinecamCommand::toggleDirector))
                .then(ClientCommands.literal("dynamic")
                    .executes(CinecamCommand::toggleDynamic)
                    .then(ClientCommands.literal("on").executes(context -> setDynamic(context, true)))
                    .then(ClientCommands.literal("off").executes(context -> setDynamic(context, false))))
                .then(ClientCommands.literal("letterbox").executes(CinecamCommand::toggleLetterbox))
            );
        });
    }

    private static int openStudio(CommandContext<FabricClientCommandSource> context) {
        CinecamScreen.open();
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> context) {
        CineCamera camera = camera();
        context.getSource().sendFeedback(Component.literal(
            "Cinecam: " + camera.shot().displayName()
                + ", camera " + (camera.isEnabled() ? "on" : "off")
                + ", manual " + (camera.isManualEnabled() ? "on" : "off") + "."
        ));
        context.getSource().sendFeedback(Component.literal(
            "/freecam opens the studio. /cinecam help lists commands."
        ));
        return 1;
    }

    private static int help(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("Cinecam commands"));
        source.sendFeedback(Component.literal("/freecam or /cinecam gui  open the studio"));
        source.sendFeedback(Component.literal("/cinecam on|off  engage or release the camera"));
        source.sendFeedback(Component.literal("/cinecam shot [name|next]  choose a shot"));
        source.sendFeedback(Component.literal("/cinecam manual [on|off]  WASD pan, wheel zoom"));
        source.sendFeedback(Component.literal("/cinecam speed  cycle playback speed"));
        source.sendFeedback(Component.literal("/cinecam reload  re-read the config"));
        return 1;
    }

    private static int setCamera(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        camera().setEnabled(enabled);
        return send(context, "Cinecam " + (enabled ? "on" : "off"));
    }

    private static int toggleCamera(CommandContext<FabricClientCommandSource> context) {
        camera().toggle();
        return send(context, "Cinecam " + (camera().isEnabled() ? "on" : "off"));
    }

    private static int nextShot(CommandContext<FabricClientCommandSource> context) {
        camera().nextShot();
        return send(context, "Shot: " + camera().shot().displayName());
    }

    private static int selectShot(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Shot shot = Shot.byName(name).orElse(null);
        if (shot == null) {
            context.getSource().sendError(Component.literal("Unknown shot: " + name));
            return 0;
        }
        camera().setShot(shot);
        return send(context, "Shot: " + shot.displayName());
    }

    private static int setManual(CommandContext<FabricClientCommandSource> context, Boolean enabled) {
        if (enabled == null) {
            camera().toggleManual();
        } else {
            camera().setManualEnabled(enabled);
        }
        String state = camera().isManualEnabled()
            ? "Manual pan on: WASD pans, scroll zooms"
            : "Manual pan off";
        return send(context, state);
    }

    private static int cycleSpeed(CommandContext<FabricClientCommandSource> context) {
        camera().cycleSpeed();
        return send(context, "Speed: " + camera().speedFactor() + "x");
    }

    private static int reload(CommandContext<FabricClientCommandSource> context) {
        CinecamClient.getInstance().reloadConfig();
        return send(context, "Cinecam config reloaded");
    }

    private static int toggleFreeze(CommandContext<FabricClientCommandSource> context) {
        camera().toggleFreeze();
        return send(context, "Cinecam " + (camera().isFrozen() ? "frozen" : "playing"));
    }

    private static int toggleDirector(CommandContext<FabricClientCommandSource> context) {
        camera().toggleDirector();
        return send(context, "Director " + (camera().isDirectorEnabled() ? "on" : "off"));
    }

    private static int toggleDynamic(CommandContext<FabricClientCommandSource> context) {
        camera().toggleDynamic();
        return send(context, "Dynamic camera "
            + (camera().isDynamicEnabled() ? "on" : "off (classic)"));
    }

    private static int setDynamic(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        camera().setDynamic(enabled);
        return send(context, "Dynamic camera " + (enabled ? "on" : "off (classic)"));
    }

    private static int toggleLetterbox(CommandContext<FabricClientCommandSource> context) {
        camera().toggleLetterbox();
        return send(context, "Letterbox "
            + (camera().config().letterbox ? "on" : "off (full frame)"));
    }

    private static int send(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(Component.literal(message));
        return 1;
    }

    private static CineCamera camera() {
        return CinecamClient.getInstance().camera();
    }
}
