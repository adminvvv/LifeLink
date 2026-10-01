package io.github.adminvvv.lifelink;

import com.mojang.brigadier.CommandDispatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.List;

import static net.minecraft.server.command.CommandManager.literal;

public final class LifeLinkMod implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private record SavedState(int version, boolean active, boolean naturalDeathsEnabled, boolean pvpDeathsEnabled, String firstDeathPlayer) {}
    private boolean active;
    private boolean naturalDeathsEnabled = true;
    private boolean pvpDeathsEnabled;
    // A non-null first death means the entire server, including future joiners, is dead.
    private String firstDeathPlayer;

    @Override
    public void onInitialize() {
        PlayerDeathCompat.initialize();
        CommandPermissionCompat.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> registerCommands(dispatcher));
        ServerLivingEntityEvents.AFTER_DEATH.register(this::afterDeath);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer(), server));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> afterRespawn(newPlayer));
        ServerLifecycleEvents.SERVER_STARTING.register(this::loadState);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    void afterDeath(LivingEntity entity, DamageSource source) {
        if (!active || firstDeathPlayer != null || !(entity instanceof ServerPlayerEntity player)) return;
        // Match vanilla kill credit, including projectile kills and deaths after recent PvP.
        boolean pvp = player.getPrimeAdversary() instanceof ServerPlayerEntity;
        if (pvp ? !pvpDeathsEnabled : !naturalDeathsEnabled) return;

        // Set the guard BEFORE killing anyone: each kill can fire AFTER_DEATH again.
        firstDeathPlayer = player.getName().getString();
        saveState(player.getServerWorld().getServer());
        for (ServerPlayerEntity linked : List.copyOf(player.getServerWorld().getServer().getPlayerManager().getPlayerList())) {
            markDead(linked);
        }
    }

    void onJoin(ServerPlayerEntity player, MinecraftServer server) {
        if (!active) return;
        if (firstDeathPlayer != null) {
            markDead(player);
        } else {
            restorePlayer(player, server);
        }
    }

    void afterRespawn(ServerPlayerEntity player) {
        if (active && firstDeathPlayer != null) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
    }

    private void markDead(ServerPlayerEntity player) {
        // The triggering player is already dead; do not run their death logic twice.
        if (!player.isDead()) PlayerDeathCompat.kill(player);
        player.changeGameMode(GameMode.SPECTATOR);
        player.sendMessage(Text.literal("You are dead! First death: " + firstDeathPlayer), false);
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("First death: " + firstDeathPlayer)));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("You are dead!")));
    }

    void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Allow operators and the integrated-server owner, including LAN without cheats.
        dispatcher.register(literal("lifelink")
            .requires(source -> source.getPlayer() != null && (CommandPermissionCompat.isOperator(source)
                || CommandPermissionCompat.isHost(source.getServer(), source.getPlayer())))
            .then(literal("start").executes(ctx -> {
                active = true;
                firstDeathPlayer = null;
                saveState(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("LifeLink started!"), false);
                return 1;
            }))
            .then(literal("stop").executes(ctx -> {
                active = false;
                firstDeathPlayer = null;
                saveState(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("LifeLink stopped!"), false);
                return 1;
            }))
            .then(literal("revive").executes(ctx -> {
                reviveAllPlayers(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("All players revived!"), false);
                return 1;
            }))
            .then(literal("pvpdeaths").executes(ctx -> {
                pvpDeathsEnabled = !pvpDeathsEnabled;
                saveState(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("PvP deaths are now "
                    + (pvpDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            }))
            .then(literal("naturaldeaths").executes(ctx -> {
                naturalDeathsEnabled = !naturalDeathsEnabled;
                saveState(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("Natural deaths are now "
                    + (naturalDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            })));
    }

    private void reviveAllPlayers(MinecraftServer server) {
        // Clear before respawning so AFTER_RESPAWN does not put players back in spectator.
        firstDeathPlayer = null;
        saveState(server);
        // Respawning replaces entries in the player list.
        for (ServerPlayerEntity player : List.copyOf(server.getPlayerManager().getPlayerList())) {
            restorePlayer(player, server);
        }
    }

    private void restorePlayer(ServerPlayerEntity player, MinecraftServer server) {
        if (player.isDead()) {
            player = server.getPlayerManager().respawnPlayer(player, false, Entity.RemovalReason.KILLED);
        }
        player.changeGameMode(GameMode.SURVIVAL);
    }

    private Path stateFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("data/lifelink.json");
    }

    void loadState(MinecraftServer server) {
        reset();
        Path file = stateFile(server);
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            SavedState state = GSON.fromJson(reader, SavedState.class);
            if (state == null || (state.version() != 1 && state.version() != 2)) {
                throw new IllegalStateException("Unsupported or empty LifeLink state: " + file);
            }
            active = state.active();
            naturalDeathsEnabled = state.naturalDeathsEnabled();
            // Version 1 always linked PvP deaths; retain existing saved-world behavior.
            pvpDeathsEnabled = state.version() == 1 || state.pvpDeathsEnabled();
            firstDeathPlayer = state.firstDeathPlayer();
        } catch (IOException | com.google.gson.JsonParseException e) {
            // Preserve the file and report errors instead of silently forgetting a death.
            throw new IllegalStateException("Could not load LifeLink state: " + file, e);
        }
    }

    private void saveState(MinecraftServer server) {
        Path file = stateFile(server);
        Path temporary = file.resolveSibling("lifelink.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(new SavedState(2, active, naturalDeathsEnabled, pvpDeathsEnabled, firstDeathPlayer)));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save LifeLink state: " + file, e);
        }
    }

    void reset() {
        active = false;
        naturalDeathsEnabled = true;
        pvpDeathsEnabled = false;
        firstDeathPlayer = null;
    }
}
