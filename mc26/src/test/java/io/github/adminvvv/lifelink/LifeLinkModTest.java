package io.github.adminvvv.lifelink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LifeLinkModTest {
    @TempDir
    Path worldDirectory;
    private LifeLinkMod mod;
    private CommandDispatcher<CommandSourceStack> commands;
    private MinecraftServer server;
    private PlayerList players;
    private CommandSourceStack commandSource;
    private ServerLevel world;
    private DamageSource damage;
    private ServerPlayer victim;
    private ServerPlayer other;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() {
        mod = new LifeLinkMod();
        commands = new CommandDispatcher<>();
        mod.registerCommands(commands);
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(worldDirectory);
        players = mock(PlayerList.class);
        world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        damage = mock(DamageSource.class);
        commandSource = mock(CommandSourceStack.class);
        when(commandSource.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        victim = player("First");
        when(commandSource.getPlayer()).thenReturn(victim);
        operatorPermission(true);
        other = player("Other");
        when(victim.isDeadOrDying()).thenReturn(true);
        when(players.getPlayers()).thenReturn(new ArrayList<>(List.of(victim, other)));
    }

    private ServerPlayer player(String name) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getName()).thenReturn(Component.literal(name));
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));

        when(playerWorld(player)).thenReturn(world);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private void stubWorldOwner(com.mojang.authlib.GameProfile profile) {
        when(server.isSingleplayerOwner(new net.minecraft.server.players.NameAndId(profile))).thenReturn(true);
    }
    private void operatorPermission(boolean allowed) {
        var permissions = mock(net.minecraft.server.permissions.PermissionSet.class);
        when(commandSource.permissions()).thenReturn(permissions);
        when(permissions.hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
            net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS))).thenReturn(allowed);
    }
    private ServerLevel playerWorld(ServerPlayer player) { return player.level(); }
    private void killCall(ServerPlayer player, boolean anyWorld) {
        player.kill(anyWorld ? any(ServerLevel.class) : world);
    }
    private void command(String name) throws Exception {
        assertEquals(1, commands.execute("lifelink " + name, commandSource));
    }

    @Test
    void inactiveAndPvpDeathsAreIgnoredByDefault() throws Exception {
        mod.afterDeath(victim, damage);
        command("start");
        when(victim.getKillCredit()).thenReturn(other);
        mod.afterDeath(victim, damage);
        mod.afterDeath(mock(LivingEntity.class), damage);
        killCall(verify(other, never()), true);
    }

    @Test
    void pvpPropagatesOnceAndPreservesFirstDeathThroughRecursiveEvents() throws Exception {
        command("start");
        command("pvpdeaths");
        when(victim.getKillCredit()).thenReturn(other);
        when(other.getKillCredit()).thenReturn(victim);
        killCall(doAnswer(call -> {
            mod.afterDeath(other, damage);
            return null;
        }).when(other), false);
        mod.afterDeath(victim, damage);
        mod.afterDeath(victim, damage);
        killCall(verify(victim, never()), true);
        killCall(verify(other, times(1)), false);
        verify(victim).setGameMode(GameType.SPECTATOR);
        verify(other).setGameMode(GameType.SPECTATOR);
        verify(other).sendSystemMessage(argThat(text -> text.getString().equals("You are dead! First death: First")), eq(false));
        verify(other.connection).send(isA(ClientboundSetSubtitleTextPacket.class));
    }

    @Test
    void naturalDeathsToggleControlsPropagation() throws Exception {
        command("start");
        command("naturaldeaths");
        mod.afterDeath(victim, damage);
        killCall(verify(other, never()), true);
        command("naturaldeaths");
        mod.afterDeath(victim, damage);
        killCall(verify(other), false);
    }

    @Test
    void lateJoinersAndRespawnsRemainSpectators() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        ServerPlayer late = player("Late");
        mod.onJoin(late, server);
        killCall(verify(late), false);
        verify(late).setGameMode(GameType.SPECTATOR);
        ServerPlayer respawned = player("Respawned");
        mod.afterRespawn(respawned);
        verify(respawned).setGameMode(GameType.SPECTATOR);
        killCall(verify(respawned, never()), true);
    }

    @Test
    void reviveUsesReplacementPlayerAndAllowsFurtherRounds() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        ServerPlayer replacement = player("First");
        when(players.respawn(victim, false, Entity.RemovalReason.KILLED)).thenAnswer(call -> {
            mod.afterRespawn(replacement);
            players.getPlayers().remove(victim);
            players.getPlayers().add(replacement);
            return replacement;
        });
        command("revive");
        verify(replacement).setGameMode(GameType.SURVIVAL);
        verify(replacement, never()).setGameMode(GameType.SPECTATOR);
        verify(other).setGameMode(GameType.SURVIVAL);
        ServerPlayer late = player("Late");
        mod.onJoin(late, server);
        verify(late).setGameMode(GameType.SURVIVAL);
        killCall(verify(late, never()), true);
        mod.afterDeath(victim, damage);
        killCall(verify(other, times(2)), false);
    }

    @Test
    void stopAndRestartClearThePreviousDeath() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        command("stop");
        ServerPlayer late = player("Late");
        mod.onJoin(late, server);
        verify(late, never()).setGameMode(any());
        command("start");
        mod.onJoin(late, server);
        verify(late).setGameMode(GameType.SURVIVAL);
        killCall(verify(late, never()), true);
    }

    @Test
    void lifecycleResetRestoresNaturalDeathDefault() throws Exception {
        command("naturaldeaths");
        mod.reset();
        command("start");
        mod.afterDeath(victim, damage);
        killCall(verify(other), false);
    }
    private void reopenWorld() {
        mod.reset();
        mod = new LifeLinkMod();
        mod.loadState(server);
        commands = new CommandDispatcher<>();
        mod.registerCommands(commands);
    }

    @Test
    void reopeningLanWorldKeepsRejoiningPlayersDeadWithOriginalMessage() throws Exception {
        command("start");
        command("pvpdeaths");
        when(victim.getKillCredit()).thenReturn(other);
        mod.afterDeath(victim, damage);
        reopenWorld();
        ServerPlayer rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining), false);
        verify(rejoining).setGameMode(GameType.SPECTATOR);
        verify(rejoining, never()).setGameMode(GameType.SURVIVAL);
        verify(rejoining).sendSystemMessage(argThat(text -> text.getString().equals("You are dead! First death: First")), eq(false));
        verify(rejoining.connection).send(isA(ClientboundSetSubtitleTextPacket.class));
        verify(rejoining.connection).send(isA(net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket.class));
    }

    @Test
    void reviveRemainsClearedAfterReopeningWorld() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        when(victim.isDeadOrDying()).thenReturn(false);
        command("revive");
        reopenWorld();
        ServerPlayer rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining, never()), true);
        verify(rejoining).setGameMode(GameType.SURVIVAL);
        mod.afterDeath(victim, damage);
        killCall(verify(other, times(2)), false);
    }

    @Test
    void stopRemainsDisabledAfterReopeningWorld() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        command("stop");
        reopenWorld();
        ServerPlayer rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining, never()), true);
        verify(rejoining, never()).setGameMode(any());
    }

    @Test
    void switchingWorldsDoesNotReusePreviousDeath() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        MinecraftServer differentWorld = mock(MinecraftServer.class);
        when(differentWorld.getWorldPath(LevelResource.ROOT)).thenReturn(worldDirectory.resolve("other-world"));
        mod.loadState(differentWorld);
        ServerPlayer joining = player("Other");
        mod.onJoin(joining, differentWorld);
        killCall(verify(joining, never()), true);
        verify(joining, never()).setGameMode(any());
        mod.loadState(server);
        mod.onJoin(joining, server);
        killCall(verify(joining), false);
    }
    @Test
    void ordinaryPlayersCannotRunAnyLifeLinkCommand() {
        operatorPermission(false);
        for (String name : List.of("start", "stop", "revive", "naturaldeaths", "pvpdeaths")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> commands.execute("lifelink " + name, commandSource));
        }
    }

    @Test
    void worldOwnerCanRunCommandsWithoutOperatorPermissions() throws Exception {
        operatorPermission(false);
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Owner");
        when(victim.getGameProfile()).thenReturn(profile);
        stubWorldOwner(profile);
        when(victim.isDeadOrDying()).thenReturn(false);
        for (String name : List.of("start", "stop", "revive", "naturaldeaths", "pvpdeaths")) command(name);
    }

    @Test
    void nonPlayerSourcesCannotRunCommands() {
        when(commandSource.getPlayer()).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertThrows(
            com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> commands.execute("lifelink start", commandSource));
    }

    @Test
    void pvpToggleIsIndependentAndPersistsAcrossReload() throws Exception {
        command("start");
        command("naturaldeaths");
        command("pvpdeaths");
        reopenWorld();
        mod.afterDeath(victim, damage);
        killCall(verify(other, never()), true);
        when(victim.getKillCredit()).thenReturn(other);
        command("pvpdeaths");
        mod.afterDeath(victim, damage);
        killCall(verify(other, never()), true);
        command("pvpdeaths");
        mod.afterDeath(victim, damage);
        killCall(verify(other), false);
    }

    @Test
    void versionOneSaveKeepsItsPreviousPvpBehavior() throws Exception {
        java.nio.file.Files.createDirectories(worldDirectory.resolve("data"));
        java.nio.file.Files.writeString(worldDirectory.resolve("data/lifelink.json"),
            "{\"version\":1,\"active\":true,\"naturalDeathsEnabled\":false}");
        mod.loadState(server);
        mod.afterDeath(victim, damage);
        killCall(verify(other, never()), true);
        when(victim.getKillCredit()).thenReturn(other);
        mod.afterDeath(victim, damage);
        killCall(verify(other), false);
    }
}


