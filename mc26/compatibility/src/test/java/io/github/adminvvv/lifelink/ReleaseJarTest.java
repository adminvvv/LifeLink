package io.github.adminvvv.lifelink;

import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseJarTest {
    @Test
    void loadsReleaseClassesAndRegistersFabricCallbacks() {
        assertTrue(FabricLoader.getInstance().isModLoaded("lifelink"));
        String location = LifeLinkMod.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location.contains(".jar"), "Must test the release JAR, not compiled source: " + location);
        new LifeLinkMod().onInitialize();
    }
}

