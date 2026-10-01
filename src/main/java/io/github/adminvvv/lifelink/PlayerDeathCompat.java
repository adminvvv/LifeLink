package io.github.adminvvv.lifelink;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Bridges the native kill signatures used before and after Minecraft 1.21.2. */
final class PlayerDeathCompat {
    private static final MethodHandle KILL = resolveKill();

    private PlayerDeathCompat() {}

    static void initialize() {
        // Class initialization resolves the method once, so unsupported APIs fail at startup.
    }

    static void kill(ServerPlayerEntity player) {
        try {
            KILL.invokeExact(player, player.getServerWorld());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Could not invoke Minecraft's player kill method", e);
        }
    }

    private static MethodHandle resolveKill() {
        MappingResolver mappings = FabricLoader.getInstance().getMappingResolver();
        MethodType commonType = MethodType.methodType(void.class, ServerPlayerEntity.class, ServerWorld.class);
        try {
            String name = mappings.mapMethodName("intermediary", "net.minecraft.class_1297",
                "method_5768", "(Lnet/minecraft/class_3218;)V");
            return MethodHandles.publicLookup().findVirtual(Entity.class, name,
                MethodType.methodType(void.class, ServerWorld.class)).asType(commonType);
        } catch (NoSuchMethodException modernSignatureMissing) {
            try {
                String name = mappings.mapMethodName("intermediary", "net.minecraft.class_1297",
                    "method_5768", "()V");
                MethodHandle legacy = MethodHandles.publicLookup().findVirtual(Entity.class, name,
                    MethodType.methodType(void.class)).asType(MethodType.methodType(void.class, ServerPlayerEntity.class));
                return MethodHandles.dropArguments(legacy, 1, ServerWorld.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No supported Minecraft kill signature found", e);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Minecraft's kill method is inaccessible", e);
        }
    }
}
