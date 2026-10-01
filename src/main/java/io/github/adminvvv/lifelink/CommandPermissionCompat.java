package io.github.adminvvv.lifelink;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import com.mojang.authlib.GameProfile;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Preserves operator level 2 and world-owner access across Minecraft versions. */
final class CommandPermissionCompat {
    private static final MethodHandle IS_OPERATOR = resolveOperatorCheck();
    private static final MethodHandle IS_HOST = resolveHostCheck();

    private CommandPermissionCompat() {}

    static void initialize() {
        // Resolve once at startup, including on servers with no command users yet.
    }

    static boolean isOperator(ServerCommandSource source) {
        try {
            return (boolean) IS_OPERATOR.invokeExact(source);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Could not check Minecraft command permissions", e);
        }
    }

    static boolean isHost(MinecraftServer server, ServerPlayerEntity player) {
        try {
            return (boolean) IS_HOST.invokeExact(server, player.getGameProfile());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Could not check Minecraft world owner", e);
        }
    }

    private static MethodHandle resolveHostCheck() {
        MappingResolver mappings = FabricLoader.getInstance().getMappingResolver();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            String name = mappings.mapMethodName("intermediary", "net.minecraft.server.MinecraftServer",
                "method_19466", "(Lcom/mojang/authlib/GameProfile;)Z");
            return lookup.findVirtual(MinecraftServer.class, name, MethodType.methodType(boolean.class, GameProfile.class));
        } catch (NoSuchMethodException newPlayerIdentity) {
            try {
                Class<?> entryType = runtimeClass(mappings, "net.minecraft.class_11560");
                String name = mappings.mapMethodName("intermediary", "net.minecraft.server.MinecraftServer",
                    "method_19466", "(Lnet/minecraft/class_11560;)Z");
                MethodHandle host = lookup.findVirtual(MinecraftServer.class, name, MethodType.methodType(boolean.class, entryType));
                MethodHandle entry = lookup.findConstructor(entryType, MethodType.methodType(void.class, GameProfile.class));
                return MethodHandles.filterArguments(host, 1, entry);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No supported Minecraft world owner API found", e);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Minecraft world owner check is inaccessible", e);
        }
    }

    private static MethodHandle resolveOperatorCheck() {
        MappingResolver mappings = FabricLoader.getInstance().getMappingResolver();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            // The declaring interface changed before the permission model itself changed.
            for (String owner : new String[] {"net.minecraft.class_8839", "net.minecraft.class_11456"}) {
                String name = mappings.mapMethodName("intermediary", owner, "method_9259", "(I)Z");
                try {
                    MethodHandle legacy = lookup.findVirtual(ServerCommandSource.class, name,
                        MethodType.methodType(boolean.class, int.class));
                    return MethodHandles.insertArguments(legacy, 1, 2);
                } catch (NoSuchMethodException movedOrRemoved) {
                    // Try the next declaring interface before the new permission API.
                }
            }
            throw new NoSuchMethodException("Legacy permission level check is unavailable");
        } catch (NoSuchMethodException newPermissionApi) {
            try {
                Class<?> levelType = runtimeClass(mappings, "net.minecraft.class_12094");
                Class<?> permissionType = runtimeClass(mappings, "net.minecraft.class_12087");
                Class<?> levelPermissionType = runtimeClass(mappings, "net.minecraft.class_12087$class_12089");
                Class<?> predicateType = runtimeClass(mappings, "net.minecraft.class_12096");
                String field = mappings.mapFieldName("intermediary", "net.minecraft.class_12094",
                    "field_63198", "Lnet/minecraft/class_12094;");
                Object level = levelType.getField(field).get(null); // GAMEMASTERS = operator level 2.
                Object permission = levelPermissionType.getConstructor(levelType).newInstance(level);
                String getterName = mappings.mapMethodName("intermediary", "net.minecraft.class_12097",
                    "method_75037", "()Lnet/minecraft/class_12096;");
                MethodHandle getter = lookup.findVirtual(ServerCommandSource.class, getterName,
                    MethodType.methodType(predicateType));
                MethodHandle check = lookup.findVirtual(predicateType, "hasPermission",
                    MethodType.methodType(boolean.class, permissionType));
                return MethodHandles.filterReturnValue(getter, MethodHandles.insertArguments(check, 1, permission));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No supported Minecraft command permission API found", e);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Minecraft command permissions are inaccessible", e);
        }
    }

    private static Class<?> runtimeClass(MappingResolver mappings, String intermediaryName) throws ClassNotFoundException {
        return Class.forName(mappings.mapClassName("intermediary", intermediaryName), true,
            ServerCommandSource.class.getClassLoader());
    }
}
