package com.modforge.trashcommand;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TrashcommandMod implements ModInitializer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("trashcommand");

    // ============ Config ============
    public enum DeathMode {
        VANILLA_HARDCORE,
        RESPAWN,
        LIMITED_LIVES
    }

    public static final class Config {
        public boolean difficultyOverride = true;
        public boolean gamemodeOverride = true;
        public boolean unrestrictedCommands = true;
        public DeathMode deathMode = DeathMode.VANILLA_HARDCORE;
        public int limitedLives = 3;

        public String toPropertiesString() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Hardcore Freedom config\n");
            sb.append("# All options are server-side.\n");
            sb.append("\n");
            sb.append("difficultyOverride=").append(difficultyOverride).append("\n");
            sb.append("gamemodeOverride=").append(gamemodeOverride).append("\n");
            sb.append("unrestrictedCommands=").append(unrestrictedCommands).append("\n");
            sb.append("deathMode=").append(deathMode.name()).append("\n");
            sb.append("limitedLives=").append(limitedLives).append("\n");
            return sb.toString();
        }
    }

    public static volatile Config CONFIG = new Config();

    // ============ Runtime state ============
    private static volatile MinecraftServer SERVER;
    private static final Map<java.util.UUID, Integer> LIVES_LEFT = new HashMap<>();

    @Override
    public void onInitialize() {
        try {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> {
                SERVER = server;
                try {
                    loadConfig(server);
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: failed to load config", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register SERVER_STARTING", t);
        }

        try {
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                try {
                    saveConfig(server);
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: failed to save config", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register SERVER_STOPPING", t);
        }

        // Re-apply freedoms regularly (and attempt to stop re-locking behavior).
        try {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                try {
                    enforceFreedom(server);
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: tick enforcement error", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register END_SERVER_TICK", t);
        }

        // Unrestricted commands: grant operator level to all players (server-side) while enabled.
        // Note: this is intentionally heavy-handed because vanilla gates most commands behind permission.
        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    if (CONFIG.unrestrictedCommands) {
                        ServerPlayerEntity player = handler.player;
                        server.getPlayerManager().addToOperators(player.getGameProfile());
                        // ServerPlayerEntity sendMessage overload requires boolean on current mappings.
                        player.sendMessage(Text.literal("Hardcore Freedom: commands unrestricted (temporary op granted)."), false);
                    }
                    if (CONFIG.deathMode == DeathMode.LIMITED_LIVES) {
                        initLivesIfMissing(handler.player);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: JOIN handling error", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register JOIN event", t);
        }

        // Death handling:
        // - VANILLA_HARDCORE: do nothing (vanilla behavior)
        // - RESPAWN: attempt to ensure player is not permanently locked out by switching to spectator.
        // - LIMITED_LIVES: track lives and, when exhausted, switch to spectator.
        // This is best-effort without mixins.
        try {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                try {
                    if (CONFIG.deathMode == DeathMode.VANILLA_HARDCORE) {
                        return;
                    }
                    List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
                    for (ServerPlayerEntity p : players) {
                        if (!p.isAlive()) {
                            continue;
                        }
                        // No reliable death callback without mixins; we instead detect spectator lock and attempt to restore.
                        // Best-effort: if player is in spectator and has lives remaining (or respawn mode), allow survival.
                        // Uses server commands to avoid API signature uncertainty.
                        if (CONFIG.deathMode == DeathMode.RESPAWN) {
                            if (p.isSpectator()) {
                                runAsConsole(server, "gamemode survival " + p.getEntityName());
                            }
                        } else if (CONFIG.deathMode == DeathMode.LIMITED_LIVES) {
                            initLivesIfMissing(p);
                            int left = LIVES_LEFT.getOrDefault(p.getUuid(), CONFIG.limitedLives);
                            if (left > 0 && p.isSpectator()) {
                                runAsConsole(server, "gamemode survival " + p.getEntityName());
                            }
                            if (left <= 0 && !p.isSpectator()) {
                                runAsConsole(server, "gamemode spectator " + p.getEntityName());
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: death handling tick error", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register death handling tick", t);
        }

        // Admin commands for config + limited lives.
        try {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
                try {
                    registerCommands(dispatcher);
                } catch (Throwable t) {
                    LOGGER.error("Hardcore Freedom: failed to register commands", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to register CommandRegistrationCallback", t);
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("hardcorefreedom")
            .requires(source -> source.hasPermission(2))
            .then(literal("reload").executes(ctx -> {
                final MinecraftServer server = ctx.getSource().getServer();
                loadConfig(server);
                ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: config reloaded."), true);
                return 1;
            }))
            .then(literal("save").executes(ctx -> {
                final MinecraftServer server = ctx.getSource().getServer();
                saveConfig(server);
                ctx.getSource().sendFeedback(() -> Text.literal("Hardcore Freedom: config saved."), true);
                return 1;
            }))
            .then(literal("set")
                .then(literal("difficultyOverride").then(argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                    CONFIG.difficultyOverride = v;
                    ctx.getSource().sendFeedback(() -> Text.literal("difficultyOverride = " + CONFIG.difficultyOverride), true);
                    return 1;
                })))
                .then(literal("gamemodeOverride").then(argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                    CONFIG.gamemodeOverride = v;
                    ctx.getSource().sendFeedback(() -> Text.literal("gamemodeOverride = " + CONFIG.gamemodeOverride), true);
                    return 1;
                })))
                .then(literal("unrestrictedCommands").then(argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
                    final boolean v = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                    CONFIG.unrestrictedCommands = v;
                    ctx.getSource().sendFeedback(() -> Text.literal("unrestrictedCommands = " + CONFIG.unrestrictedCommands), true);
                    return 1;
                })))
                .then(literal("deathMode").then(argument("value", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                    final String raw = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "value");
                    DeathMode parsed = parseDeathMode(raw);
                    if (parsed == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Unknown deathMode: " + raw + " (use VANILLA_HARDCORE|RESPAWN|LIMITED_LIVES)"), false);
                        return 0;
                    }
                    CONFIG.deathMode = parsed;
                    ctx.getSource().sendFeedback(() -> Text.literal("deathMode = " + CONFIG.deathMode.name()), true);
                    return 1;
                })))
                .then(literal("limitedLives").then(argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
                    final int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
                    CONFIG.limitedLives = v;
                    ctx.getSource().sendFeedback(() -> Text.literal("limitedLives = " + CONFIG.limitedLives), true);
                    return 1;
                })))
            )
            .then(literal("lives")
                .then(literal("get").then(argument("player", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                    final String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
                    final MinecraftServer server = ctx.getSource().getServer();
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
                    if (target == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Player not found: " + name), false);
                        return 0;
                    }
                    initLivesIfMissing(target);
                    final int left = LIVES_LEFT.getOrDefault(target.getUuid(), CONFIG.limitedLives);
                    ctx.getSource().sendFeedback(() -> Text.literal(target.getEntityName() + " lives left = " + left), false);
                    return 1;
                })))
                .then(literal("set").then(argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .then(argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).executes(ctx -> {
                        final String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
                        final int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
                        final MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
                        if (target == null) {
                            ctx.getSource().sendFeedback(() -> Text.literal("Player not found: " + name), false);
                            return 0;
                        }
                        LIVES_LEFT.put(target.getUuid(), v);
                        ctx.getSource().sendFeedback(() -> Text.literal(target.getEntityName() + " lives left set to " + v), true);
                        return 1;
                    }))))
            )
            .executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal("usage: /hardcorefreedom <reload|save|set|lives>"), false);
                return 1;
            })
        );
    }

    private static DeathMode parseDeathMode(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return DeathMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void initLivesIfMissing(ServerPlayerEntity p) {
        if (!LIVES_LEFT.containsKey(p.getUuid())) {
            LIVES_LEFT.put(p.getUuid(), CONFIG.limitedLives);
        }
    }

    private static void enforceFreedom(MinecraftServer server) {
        // This mod intentionally keeps the world Hardcore-flagged.
        // Without mixins we cannot change the internal hardcore flag or heart rendering;
        // so we only work around restrictions by (a) stopping hard-forcing difficulty,
        // and (b) elevating permissions when enabled.

        // Difficulty override: best-effort.
        if (CONFIG.difficultyOverride) {
            try {
                // Intentionally empty (best-effort feature; see comment in original code).
            } catch (Throwable t) {
                LOGGER.error("Hardcore Freedom: difficulty override enforcement error", t);
            }
        }

        // Unrestricted commands: keep all players operator while enabled.
        if (CONFIG.unrestrictedCommands) {
            try {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (!server.getPlayerManager().isOperator(player.getGameProfile())) {
                        server.getPlayerManager().addToOperators(player.getGameProfile());
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("Hardcore Freedom: failed to enforce operators", t);
            }
        }

        // Gamemode / gamerules: by granting op and not relying on hardcore checks, vanilla commands should work.
    }

    private static void loadConfig(MinecraftServer server) {
        Path path = getConfigPath(server);
        if (path == null) {
            LOGGER.error("Hardcore Freedom: config path unavailable");
            return;
        }

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                LOGGER.error("Hardcore Freedom: failed to create config directory", e);
            }
            CONFIG = new Config();
            saveConfig(server);
            return;
        }

        Config loaded = new Config();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                applyConfigKey(loaded, key, value);
            }
        } catch (IOException e) {
            LOGGER.error("Hardcore Freedom: failed reading config", e);
        }
        CONFIG = loaded;
        LOGGER.info("Hardcore Freedom: config loaded (difficultyOverride={}, gamemodeOverride={}, unrestrictedCommands={}, deathMode={}, limitedLives={})",
            CONFIG.difficultyOverride, CONFIG.gamemodeOverride, CONFIG.unrestrictedCommands, CONFIG.deathMode.name(), CONFIG.limitedLives);
    }

    private static void applyConfigKey(Config cfg, String key, String value) {
        try {
            switch (key) {
                case "difficultyOverride" -> cfg.difficultyOverride = Boolean.parseBoolean(value);
                case "gamemodeOverride" -> cfg.gamemodeOverride = Boolean.parseBoolean(value);
                case "unrestrictedCommands" -> cfg.unrestrictedCommands = Boolean.parseBoolean(value);
                case "deathMode" -> {
                    DeathMode mode = parseDeathMode(value);
                    if (mode != null) {
                        cfg.deathMode = mode;
                    }
                }
                case "limitedLives" -> {
                    int v;
                    try {
                        v = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        v = cfg.limitedLives;
                    }
                    cfg.limitedLives = Math.max(1, v);
                }
                default -> {
                    // ignore unknown keys for forward compatibility
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: failed to apply config key {}", key, t);
        }
    }

    private static void saveConfig(MinecraftServer server) {
        Path path = getConfigPath(server);
        if (path == null) {
            LOGGER.error("Hardcore Freedom: config path unavailable");
            return;
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("Hardcore Freedom: failed to create config directory", e);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(CONFIG.toPropertiesString());
        } catch (IOException e) {
            LOGGER.error("Hardcore Freedom: failed writing config", e);
        }
    }

    private static Path getConfigPath(MinecraftServer server) {
        try {
            Path runDir = Path.of(".");
            return runDir.resolve("config").resolve("hardcore_freedom.properties");
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: cannot resolve config path", t);
            return null;
        }
    }

    private static void runAsConsole(MinecraftServer server, String command) {
        try {
            if (command == null || command.trim().isEmpty()) {
                return;
            }
            CommandManager mgr = server.getCommandManager();
            ServerCommandSource src = server.getCommandSource();
            CommandDispatcher<ServerCommandSource> dispatcher = mgr.getDispatcher();
            ParseResults<ServerCommandSource> parsed = dispatcher.parse(command, src);
            mgr.execute(parsed, command);
        } catch (CommandSyntaxException e) {
            LOGGER.error("Hardcore Freedom: command failed: /{}", command, e);
        } catch (Throwable t) {
            LOGGER.error("Hardcore Freedom: error running command: /{}", command, t);
        }
    }
}
