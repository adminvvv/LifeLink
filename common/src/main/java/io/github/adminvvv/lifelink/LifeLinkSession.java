package io.github.adminvvv.lifelink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Shared round and save logic for all Minecraft builds. */
public final class LifeLinkSession<P, S> {
    public interface Game<P, S> {
        Path worldRoot(S server);
        List<P> players(S server);
        String name(P player);
        void markDead(P player, String firstDeath);
        void restore(P player, S server);
        void spectator(P player);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private record SavedState(int version, boolean active, boolean naturalDeathsEnabled,
                              boolean pvpDeathsEnabled, String firstDeathPlayer) {}
    private final Game<P, S> game;
    private boolean active;
    private boolean naturalDeathsEnabled = true;
    private boolean pvpDeathsEnabled;
    private String firstDeathPlayer;

    public LifeLinkSession(Game<P, S> game) { this.game = game; }

    public void start(S server) { active = true; firstDeathPlayer = null; saveState(server); }
    public void stop(S server) { active = false; firstDeathPlayer = null; saveState(server); }
    public boolean togglePvp(S server) {
        pvpDeathsEnabled = !pvpDeathsEnabled;
        saveState(server);
        return pvpDeathsEnabled;
    }
    public boolean toggleNatural(S server) {
        naturalDeathsEnabled = !naturalDeathsEnabled;
        saveState(server);
        return naturalDeathsEnabled;
    }
    public void afterDeath(P player, S server, boolean pvp) {
        if (!active || firstDeathPlayer != null) return;
        if (pvp ? !pvpDeathsEnabled : !naturalDeathsEnabled) return;
        // Set the guard before killing anyone, since kills can call this again.
        firstDeathPlayer = game.name(player);
        saveState(server);
        for (P linked : List.copyOf(game.players(server))) game.markDead(linked, firstDeathPlayer);
    }
    public void onJoin(P player, S server) {
        if (!active) return;
        if (firstDeathPlayer != null) game.markDead(player, firstDeathPlayer);
        else game.restore(player, server);
    }
    public void afterRespawn(P player) {
        if (active && firstDeathPlayer != null) game.spectator(player);
    }
    public void revive(S server) {
        firstDeathPlayer = null;
        saveState(server);
        // Respawning replaces entries in the player list.
        for (P player : List.copyOf(game.players(server))) game.restore(player, server);
    }
    private Path stateFile(S server) { return game.worldRoot(server).resolve("data/lifelink.json"); }
    public void loadState(S server) {
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
            // Version 1 always linked PvP deaths; retain saved-world behavior.
            pvpDeathsEnabled = state.version() == 1 || state.pvpDeathsEnabled();
            firstDeathPlayer = state.firstDeathPlayer();
        } catch (IOException | com.google.gson.JsonParseException e) {
            throw new IllegalStateException("Could not load LifeLink state: " + file, e);
        }
    }
    private void saveState(S server) {
        Path file = stateFile(server);
        Path temporary = file.resolveSibling("lifelink.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(new SavedState(2, active, naturalDeathsEnabled,
                pvpDeathsEnabled, firstDeathPlayer)));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save LifeLink state: " + file, e);
        }
    }
    public void reset() {
        active = false;
        naturalDeathsEnabled = true;
        pvpDeathsEnabled = false;
        firstDeathPlayer = null;
    }
}
