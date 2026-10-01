package io.github.adminvvv.lifelink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

public final class LifeLinkMod implements ModInitializer {
    private final LifeLinkSession<ServerPlayer, MinecraftServer> session = new LifeLinkSession<>(new LifeLinkSession.Game<>() {
        public java.nio.file.Path worldRoot(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
        public List<ServerPlayer> players(MinecraftServer server) { return server.getPlayerList().getPlayers(); }
        public String name(ServerPlayer player) { return player.getName().getString(); }
        public void markDead(ServerPlayer player, String firstDeath) { LifeLinkMod.this.markDead(player, firstDeath); }
        public void restore(ServerPlayer player, MinecraftServer server) { restorePlayer(player, server); }
        public void spectator(ServerPlayer player) { player.setGameMode(GameType.SPECTATOR); }
    });
    @Override
    public void onInitialize() {


        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> registerCommands(dispatcher));
        ServerLivingEntityEvents.AFTER_DEATH.register(this::afterDeath);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer(), server));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> afterRespawn(newPlayer));
        ServerLifecycleEvents.SERVER_STARTING.register(this::loadState);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    void afterDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer player) {
            session.afterDeath(player, player.level().getServer(), player.getKillCredit() instanceof ServerPlayer);
        }
    }
    void onJoin(ServerPlayer player, MinecraftServer server) { session.onJoin(player, server); }
    void afterRespawn(ServerPlayer player) { session.afterRespawn(player); }
    private void markDead(ServerPlayer player, String firstDeathPlayer) {
        // The triggering player is already dead; do not run their death logic twice.
        if (!player.isDeadOrDying()) player.kill(player.level());
        player.setGameMode(GameType.SPECTATOR);
        player.sendSystemMessage(Component.literal("You are dead! First death: " + firstDeathPlayer), false);
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("First death: " + firstDeathPlayer)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("You are dead!")));
    }

    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Allow operators and the integrated-server owner, including LAN without cheats.
        dispatcher.register(literal("lifelink")
            .requires(source -> source.getPlayer() != null && (source.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS))
                || source.getServer().isSingleplayerOwner(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile()))))
            .then(literal("start").executes(ctx -> {
                session.start(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("LifeLink started!"), false);
                return 1;
            }))
            .then(literal("stop").executes(ctx -> {
                session.stop(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("LifeLink stopped!"), false);
                return 1;
            }))
            .then(literal("revive").executes(ctx -> {
                session.revive(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("All players revived!"), false);
                return 1;
            }))
            .then(literal("pvpdeaths").executes(ctx -> {
                boolean pvpDeathsEnabled = session.togglePvp(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("PvP deaths are now "
                    + (pvpDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            }))
            .then(literal("naturaldeaths").executes(ctx -> {
                boolean naturalDeathsEnabled = session.toggleNatural(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("Natural deaths are now "
                    + (naturalDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            })));
    }

    private void restorePlayer(ServerPlayer player, MinecraftServer server) {
        if (player.isDeadOrDying()) {
            player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        }
        player.setGameMode(GameType.SURVIVAL);
    }

    void loadState(MinecraftServer server) { session.loadState(server); }
    void reset() { session.reset(); }
}


