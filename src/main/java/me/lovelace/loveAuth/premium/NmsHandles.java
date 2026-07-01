package me.lovelace.loveAuth.premium;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.crypto.SecretKey;

/**
 * Bundle of reflection handles into Mojang-mapped NMS classes, resolved once
 * at startup against the running server's classes. All names here were
 * confirmed against the actual compiled classes of the target server build
 * via javap, not assumed from training data.
 */
final class NmsHandles {
    final Class<?> serverboundHelloPacketClass;
    final Method helloPacketName;
    final Method helloPacketProfileId;

    final Constructor<?> clientboundHelloPacketCtor;

    final Class<?> keyPacketClass;
    final Method keyPacketIsChallengeValid;
    final Method keyPacketGetSecretKey;

    final Method cryptDigestData;

    final Class<?> connectionClass;
    final Method connectionSend;
    final Method connectionSetEncryptionKey;
    final Method connectionGetPacketListener;
    final Method connectionDisconnect;

    final Class<?> componentClass;
    final Method componentLiteral;

    final Class<?> serverLoginListenerClass;
    final Field loginListenerRequestedUsername;
    final Field loginListenerRequestedUuid;
    final Method loginListenerStartClientVerification;
    final Method loginListenerCallPlayerPreLoginEvents;
    final ExecutorService authenticatorPool;

    final Class<?> gameProfileClass;
    final Constructor<?> gameProfileCtor2;
    final Constructor<?> gameProfileCtor3;

    final Constructor<?> propertyCtor3;
    final Constructor<?> propertyMapCtor;
    final Method multimapCreate;
    final Method multimapPut;

    final KeyPair serverKeyPair;

    NmsHandles(Object minecraftServer) throws ReflectiveOperationException {
        ClassLoader cl = minecraftServer.getClass().getClassLoader();

        serverboundHelloPacketClass = Class.forName("net.minecraft.network.protocol.login.ServerboundHelloPacket", true, cl);
        helloPacketName = serverboundHelloPacketClass.getMethod("name");
        helloPacketProfileId = serverboundHelloPacketClass.getMethod("profileId");

        Class<?> clientboundHelloPacketClass = Class.forName("net.minecraft.network.protocol.login.ClientboundHelloPacket", true, cl);
        clientboundHelloPacketCtor = clientboundHelloPacketClass.getConstructor(String.class, byte[].class, byte[].class, boolean.class);

        keyPacketClass = Class.forName("net.minecraft.network.protocol.login.ServerboundKeyPacket", true, cl);
        keyPacketIsChallengeValid = keyPacketClass.getMethod("isChallengeValid", byte[].class, PrivateKey.class);
        keyPacketGetSecretKey = keyPacketClass.getMethod("getSecretKey", PrivateKey.class);

        Class<?> cryptClass = Class.forName("net.minecraft.util.Crypt", true, cl);
        cryptDigestData = cryptClass.getMethod("digestData", String.class, PublicKey.class, SecretKey.class);

        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
        connectionClass = Class.forName("net.minecraft.network.Connection", true, cl);
        connectionSend = connectionClass.getMethod("send", packetClass);
        connectionSetEncryptionKey = connectionClass.getMethod("setEncryptionKey", SecretKey.class);
        connectionGetPacketListener = connectionClass.getMethod("getPacketListener");

        componentClass = Class.forName("net.minecraft.network.chat.Component", true, cl);
        componentLiteral = componentClass.getMethod("literal", String.class);
        connectionDisconnect = connectionClass.getMethod("disconnect", componentClass);

        gameProfileClass = Class.forName("com.mojang.authlib.GameProfile", true, cl);

        serverLoginListenerClass = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl", true, cl);
        loginListenerRequestedUsername = serverLoginListenerClass.getField("requestedUsername");
        loginListenerRequestedUuid = serverLoginListenerClass.getField("requestedUuid");
        loginListenerStartClientVerification = serverLoginListenerClass.getDeclaredMethod("startClientVerification", gameProfileClass);
        loginListenerStartClientVerification.setAccessible(true);

        // Fires AsyncPlayerPreLoginEvent / PlayerPreLoginEvent and applies their result
        // (the same call vanilla makes off the authenticatorPool). Permission/ban plugins
        // such as LuckPerms hook these events to pre-load their per-player data.
        loginListenerCallPlayerPreLoginEvents = serverLoginListenerClass.getDeclaredMethod("callPlayerPreLoginEvents", gameProfileClass);
        loginListenerCallPlayerPreLoginEvents.setAccessible(true);

        Field poolField = serverLoginListenerClass.getDeclaredField("authenticatorPool");
        poolField.setAccessible(true);
        authenticatorPool = (ExecutorService) poolField.get(null);

        gameProfileCtor2 = gameProfileClass.getConstructor(UUID.class, String.class);

        Class<?> propertyMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap", true, cl);
        Class<?> multimapClass = Class.forName("com.google.common.collect.Multimap", true, cl);
        gameProfileCtor3 = gameProfileClass.getConstructor(UUID.class, String.class, propertyMapClass);
        propertyMapCtor = propertyMapClass.getConstructor(multimapClass);

        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property", true, cl);
        propertyCtor3 = propertyClass.getConstructor(String.class, String.class, String.class);

        Class<?> hashMultimapClass = Class.forName("com.google.common.collect.HashMultimap", true, cl);
        multimapCreate = hashMultimapClass.getMethod("create");
        multimapPut = multimapClass.getMethod("put", Object.class, Object.class);

        Method getKeyPair = minecraftServer.getClass().getMethod("getKeyPair");
        serverKeyPair = (KeyPair) getKeyPair.invoke(minecraftServer);
    }
}
