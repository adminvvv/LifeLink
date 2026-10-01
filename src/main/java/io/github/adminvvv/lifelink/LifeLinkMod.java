package io.github.adminvvv.lifelink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.util.WorldSavePath;
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
    private final LifeLinkSession<ServerPlayerEntity, MinecraftServer> session = new LifeLinkSession<>(new LifeLinkSession.Game<>() {
        public java.nio.file.Path worldRoot(MinecraftServer server) { return server.getSavePath(WorldSavePath.ROOT); }
        public List<ServerPlayerEntity> players(MinecraftServer server) { return server.getPlayerManager().getPlayerList(); }
        public String name(ServerPlayerEntity player) { return player.getName().getString(); }
        public void markDead(ServerPlayerEntity player, String firstDeath) { LifeLinkMod.this.markDead(player, firstDeath); }
        public void restore(ServerPlayerEntity player, MinecraftServer server) { restorePlayer(player, server); }
        public void spectator(ServerPlayerEntity player) { player.changeGameMode(GameMode.SPECTATOR); }
    });
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
        if (entity instanceof ServerPlayerEntity player) {
            session.afterDeath(player, player.getServerWorld().getServer(), player.getPrimeAdversary() instanceof ServerPlayerEntity);
        }
    }
    void onJoin(ServerPlayerEntity player, MinecraftServer server) { session.onJoin(player, server); }
    void afterRespawn(ServerPlayerEntity player) { session.afterRespawn(player); }
    private void markDead(ServerPlayerEntity player, String firstDeathPlayer) {
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
                session.start(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("LifeLink started!"), false);
                return 1;
            }))
            .then(literal("stop").executes(ctx -> {
                session.stop(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("LifeLink stopped!"), false);
                return 1;
            }))
            .then(literal("revive").executes(ctx -> {
                session.revive(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("All players revived!"), false);
                return 1;
            }))
            .then(literal("pvpdeaths").executes(ctx -> {
                boolean pvpDeathsEnabled = session.togglePvp(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("PvP deaths are now "
                    + (pvpDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            }))
            .then(literal("naturaldeaths").executes(ctx -> {
                boolean naturalDeathsEnabled = session.toggleNatural(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("Natural deaths are now "
                    + (naturalDeathsEnabled ? "enabled" : "disabled")), false);
                return 1;
            })));
    }

    private void restorePlayer(ServerPlayerEntity player, MinecraftServer server) {
        if (player.isDead()) {
            player = server.getPlayerManager().respawnPlayer(player, false, Entity.RemovalReason.KILLED);
        }
        player.changeGameMode(GameMode.SURVIVAL);
    }

    void loadState(MinecraftServer server) { session.loadState(server); }
    void reset() { session.reset(); }
}
