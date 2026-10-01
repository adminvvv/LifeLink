package io.github.adminvvv.lifelink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.util.WorldSavePath;
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
    private CommandDispatcher<ServerCommandSource> commands;
    private MinecraftServer server;
    private PlayerManager players;
    private ServerCommandSource commandSource;
    private ServerWorld world;
    private DamageSource damage;
    private ServerPlayerEntity victim;
    private ServerPlayerEntity other;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void setup() {
        mod = new LifeLinkMod();
        commands = new CommandDispatcher<>();
        mod.registerCommands(commands);
        server = mock(MinecraftServer.class);
        when(server.getSavePath(WorldSavePath.ROOT)).thenReturn(worldDirectory);
        players = mock(PlayerManager.class);
        world = mock(ServerWorld.class);
        when(world.getServer()).thenReturn(server);
        damage = mock(DamageSource.class);
        commandSource = mock(ServerCommandSource.class);
        when(commandSource.getServer()).thenReturn(server);
        when(server.getPlayerManager()).thenReturn(players);
        victim = player("First");
        when(commandSource.getPlayer()).thenReturn(victim);
        operatorPermission(true);
        other = player("Other");
        when(victim.isDead()).thenReturn(true);
        when(players.getPlayerList()).thenReturn(new ArrayList<>(List.of(victim, other)));
    }

    private ServerPlayerEntity player(String name) {
        ServerPlayerEntity player = mock(ServerPlayerEntity.class);
        when(player.getName()).thenReturn(Text.literal(name));
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));
        stubPlayerServer(player);
        when(playerWorld(player)).thenReturn(world);
        player.networkHandler = mock(ServerPlayNetworkHandler.class);
        return player;
    }

    private void stubWorldOwner(com.mojang.authlib.GameProfile profile) {
        try {
            try {
                var legacy = MinecraftServer.class.getMethod("isHost", com.mojang.authlib.GameProfile.class);
                when((boolean) legacy.invoke(server, profile)).thenReturn(true);
                return;
            } catch (NoSuchMethodException changed) {
                // Newer servers wrap profiles in configuration entries.
            }
            var entryType = Class.forName("net.minecraft.server.PlayerConfigEntry");
            Object entry = entryType.getConstructor(com.mojang.authlib.GameProfile.class).newInstance(profile);
            when((boolean) MinecraftServer.class.getMethod("isHost", entryType).invoke(server, entry)).thenReturn(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    private void stubPlayerServer(ServerPlayerEntity player) {
        try {
            when((MinecraftServer) ServerPlayerEntity.class.getMethod("getServer").invoke(player)).thenReturn(server);
        } catch (NoSuchMethodException removed) {
            when(world.getServer()).thenReturn(server);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void operatorPermission(boolean allowed) {
        try {
            try {
                var legacy = ServerCommandSource.class.getMethod("hasPermissionLevel", int.class);
                when((boolean) legacy.invoke(commandSource, 2)).thenReturn(allowed);
                return;
            } catch (NoSuchMethodException removed) {
                // Newer Minecraft expresses operator levels as permissions.
            }
            var permission = Class.forName("net.minecraft.command.permission.Permission");
            var predicate = Class.forName("net.minecraft.command.permission.PermissionPredicate");
            Object permissions = mock(predicate);
            var getter = ServerCommandSource.class.getMethod("getPermissions");
            when(getter.invoke(commandSource)).thenReturn(permissions);
            var levelType = Class.forName("net.minecraft.command.permission.PermissionLevel");
            var levelPermission = Class.forName("net.minecraft.command.permission.Permission$Level");
            Object required = levelPermission.getConstructor(levelType).newInstance(levelType.getField("GAMEMASTERS").get(null));
            when((boolean) predicate.getMethod("hasPermission", permission).invoke(permissions, eq(required))).thenReturn(allowed);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    private ServerWorld playerWorld(ServerPlayerEntity player) {
        for (String name : List.of("getServerWorld", "getWorld", "getEntityWorld")) {
            try {
                return (ServerWorld) ServerPlayerEntity.class.getMethod(name).invoke(player);
            } catch (NoSuchMethodException renamed) {
                // Try the mapping name used by the next target.
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("No server world accessor found");
    }
    // The harness must compile on either side of Minecraft's kill signature change.
    // This only adapts Mockito verification/stubbing; it does not adapt LifeLink itself.
    private void killCall(ServerPlayerEntity player, boolean anyWorld) {
        try {
            java.lang.reflect.Method kill;
            try {
                kill = ServerPlayerEntity.class.getMethod("kill", ServerWorld.class);
            } catch (NoSuchMethodException legacy) {
                ServerPlayerEntity.class.getMethod("kill").invoke(player);
                return;
            }
            kill.invoke(player, anyWorld ? any(ServerWorld.class) : world);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke the target Minecraft kill method", e);
        }
    }
    private void command(String name) throws Exception {
        assertEquals(1, commands.execute("lifelink " + name, commandSource));
    }

    @Test
    void inactiveAndPvpDeathsAreIgnoredByDefault() throws Exception {
        mod.afterDeath(victim, damage);
        command("start");
        when(victim.getPrimeAdversary()).thenReturn(other);
        mod.afterDeath(victim, damage);
        mod.afterDeath(mock(LivingEntity.class), damage);
        killCall(verify(other, never()), true);
    }

    @Test
    void pvpPropagatesOnceAndPreservesFirstDeathThroughRecursiveEvents() throws Exception {
        command("start");
        command("pvpdeaths");
        when(victim.getPrimeAdversary()).thenReturn(other);
        when(other.getPrimeAdversary()).thenReturn(victim);
        killCall(doAnswer(call -> {
            mod.afterDeath(other, damage);
            return null;
        }).when(other), false);
        mod.afterDeath(victim, damage);
        mod.afterDeath(victim, damage);
        killCall(verify(victim, never()), true);
        killCall(verify(other, times(1)), false);
        verify(victim).changeGameMode(GameMode.SPECTATOR);
        verify(other).changeGameMode(GameMode.SPECTATOR);
        verify(other).sendMessage(argThat(text -> text.getString().equals("You are dead! First death: First")), eq(false));
        verify(other.networkHandler).sendPacket(isA(SubtitleS2CPacket.class));
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
        ServerPlayerEntity late = player("Late");
        mod.onJoin(late, server);
        killCall(verify(late), false);
        verify(late).changeGameMode(GameMode.SPECTATOR);
        ServerPlayerEntity respawned = player("Respawned");
        mod.afterRespawn(respawned);
        verify(respawned).changeGameMode(GameMode.SPECTATOR);
        killCall(verify(respawned, never()), true);
    }

    @Test
    void reviveUsesReplacementPlayerAndAllowsFurtherRounds() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        ServerPlayerEntity replacement = player("First");
        when(players.respawnPlayer(victim, false, Entity.RemovalReason.KILLED)).thenAnswer(call -> {
            mod.afterRespawn(replacement);
            players.getPlayerList().remove(victim);
            players.getPlayerList().add(replacement);
            return replacement;
        });
        command("revive");
        verify(replacement).changeGameMode(GameMode.SURVIVAL);
        verify(replacement, never()).changeGameMode(GameMode.SPECTATOR);
        verify(other).changeGameMode(GameMode.SURVIVAL);
        ServerPlayerEntity late = player("Late");
        mod.onJoin(late, server);
        verify(late).changeGameMode(GameMode.SURVIVAL);
        killCall(verify(late, never()), true);
        mod.afterDeath(victim, damage);
        killCall(verify(other, times(2)), false);
    }

    @Test
    void stopAndRestartClearThePreviousDeath() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        command("stop");
        ServerPlayerEntity late = player("Late");
        mod.onJoin(late, server);
        verify(late, never()).changeGameMode(any());
        command("start");
        mod.onJoin(late, server);
        verify(late).changeGameMode(GameMode.SURVIVAL);
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
        when(victim.getPrimeAdversary()).thenReturn(other);
        mod.afterDeath(victim, damage);
        reopenWorld();
        ServerPlayerEntity rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining), false);
        verify(rejoining).changeGameMode(GameMode.SPECTATOR);
        verify(rejoining, never()).changeGameMode(GameMode.SURVIVAL);
        verify(rejoining).sendMessage(argThat(text -> text.getString().equals("You are dead! First death: First")), eq(false));
        verify(rejoining.networkHandler).sendPacket(isA(SubtitleS2CPacket.class));
        verify(rejoining.networkHandler).sendPacket(isA(net.minecraft.network.packet.s2c.play.TitleS2CPacket.class));
    }

    @Test
    void reviveRemainsClearedAfterReopeningWorld() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        when(victim.isDead()).thenReturn(false);
        command("revive");
        reopenWorld();
        ServerPlayerEntity rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining, never()), true);
        verify(rejoining).changeGameMode(GameMode.SURVIVAL);
        mod.afterDeath(victim, damage);
        killCall(verify(other, times(2)), false);
    }

    @Test
    void stopRemainsDisabledAfterReopeningWorld() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        command("stop");
        reopenWorld();
        ServerPlayerEntity rejoining = player("Other");
        mod.onJoin(rejoining, server);
        killCall(verify(rejoining, never()), true);
        verify(rejoining, never()).changeGameMode(any());
    }

    @Test
    void switchingWorldsDoesNotReusePreviousDeath() throws Exception {
        command("start");
        mod.afterDeath(victim, damage);
        MinecraftServer differentWorld = mock(MinecraftServer.class);
        when(differentWorld.getSavePath(WorldSavePath.ROOT)).thenReturn(worldDirectory.resolve("other-world"));
        mod.loadState(differentWorld);
        ServerPlayerEntity joining = player("Other");
        mod.onJoin(joining, differentWorld);
        killCall(verify(joining, never()), true);
        verify(joining, never()).changeGameMode(any());
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
        when(victim.isDead()).thenReturn(false);
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
        when(victim.getPrimeAdversary()).thenReturn(other);
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
        when(victim.getPrimeAdversary()).thenReturn(other);
        mod.afterDeath(victim, damage);
        killCall(verify(other), false);
    }
}
