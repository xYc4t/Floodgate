/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.api.unsafe.Unsafe;
import org.geysermc.floodgate.config.FloodgateConfig;
import org.geysermc.floodgate.pluginmessage.PluginMessageManager;
import org.geysermc.floodgate.pluginmessage.channel.FormChannel;
import org.geysermc.floodgate.pluginmessage.channel.TransferChannel;
import org.geysermc.floodgate.util.Constants;
import org.geysermc.floodgate.util.HttpClient;
import org.geysermc.floodgate.util.Utils;

public class SimpleFloodgateApi implements FloodgateApi {
    private final Map<UUID, FloodgatePlayer> players = new ConcurrentHashMap<>();
    private final Cache<UUID, FloodgatePlayer> pendingRemove =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(20, TimeUnit.SECONDS)
                    .build();

    /**
     * Bedrock sessions may not be discoverable by {@link Player#getUniqueId()} alone (Velocity/Bungee forwarding,
     * {@code use-offline-uuids}, linked accounts). We mirror handshake UUIDs, linked UUIDs, legacy XUID-layout UUIDs
     * ({@link Utils#getJavaUuid(long)}), optional forwarded session UUIDs, and the Floodgate joined username so
     * {@link #getPlayer(UUID)}, {@link #isFloodgatePlayer(UUID)}, and {@link #isFloodgateJoinedUsername(String)} work.
     */
    private final Set<String> offlineBedrockJavaUsernames = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<UUID, String> offlineBedrockUuidToJoinedName = new ConcurrentHashMap<>();

    /**
     * When a proxy forwards a profile, Bukkit's online UUID can differ from Floodgate handshake IDs. Spigot resolves
     * {@code Bukkit.getPlayer} using the forwarded id; this map stores handshake/XUID keys to that live session id
     * for plugin messages and skins.
     */
    private final ConcurrentHashMap<UUID, UUID> bukkitSessionUuidByHandshakeId = new ConcurrentHashMap<>();

    @Inject private PluginMessageManager pluginMessageManager;
    @Inject private FloodgateConfig config;
    @Inject private HttpClient httpClient;
    @Inject private FloodgateLogger logger;

    @Override
    public String getPlayerPrefix() {
        return config.getUsernamePrefix();
    }

    @Override
    public Collection<FloodgatePlayer> getPlayers() {
        return ImmutableSet.copyOf(new HashSet<>(players.values()));
    }

    @Override
    public int getPlayerCount() {
        return new HashSet<>(players.values()).size();
    }

    @Override
    public boolean isFloodgatePlayer(UUID uuid) {
        FloodgatePlayer player = getPlayer(uuid);
        if (config.isDebug()) {
            logger.debug(
                    "[floodgate] isFloodgatePlayer uuid={} hit={} mapContainsKey={}",
                    uuid,
                    player != null,
                    offlineBedrockUuidToJoinedName.containsKey(uuid));
            for (Map.Entry<UUID, String> entry : offlineBedrockUuidToJoinedName.entrySet()) {
                logger.trace("[floodgate] bedrockLookupMap {} -> {}", entry.getKey(), entry.getValue());
            }
        }
        return player != null;
    }

    @Override
    public boolean isFloodgateJoinedUsername(String floodgateJoinedUsername) {
        if (floodgateJoinedUsername == null) {
            return false;
        }
        return offlineBedrockJavaUsernames.contains(floodgateJoinedUsername);
    }

    /**
     * Resolves a Bedrock {@link FloodgatePlayer} by online session UUID or, when proxy forwarding uses a different
     * UUID than the handshake, by the Bukkit-visible name.
     */
    public FloodgatePlayer getPlayerIfBedrockSession(UUID onlineUniqueId, String bukkitPlayerName) {
        FloodgatePlayer byId = getPlayer(onlineUniqueId);
        if (byId != null) {
            return byId;
        }
        return findBedrockPlayerByVisibleName(bukkitPlayerName);
    }

    /**
     * Registers an extra session UUID (e.g. {@code Player#getUniqueId()} from a forwarded profile) for API lookups.
     */
    public void registerMirroredSessionUuid(UUID sessionUuid, FloodgatePlayer player) {
        if (sessionUuid == null || player == null) {
            return;
        }
        String joined = player.getJavaUsername();
        offlineBedrockJavaUsernames.add(joined);
        offlineBedrockUuidToJoinedName.put(sessionUuid, joined);
        putBukkitSessionMappingsIfNeeded(sessionUuid, player);
        boolean alreadyHandshakeKey = sessionUuid.equals(player.getJavaUniqueId())
                || sessionUuid.equals(player.getCorrectUniqueId());
        if (!alreadyHandshakeKey) {
            try {
                String xuid = player.getXuid();
                if (xuid != null && !xuid.isEmpty() && sessionUuid.equals(Utils.getJavaUuid(xuid))) {
                    alreadyHandshakeKey = true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (!alreadyHandshakeKey) {
            logger.info(
                    "[floodgate] Mirrored Bedrock session UUID {} for joined name {} (forwarded profile UUID differs from handshake)",
                    sessionUuid,
                    joined);
        }
    }

    /**
     * Resolves the UUID the Spigot server uses for {@code Bukkit.getPlayer} when it differs from Floodgate handshake
     * IDs (e.g. Velocity modern forwarding).
     */
    public UUID resolveBukkitServerUniqueId(UUID floodgateHandshakeOrInternalId) {
        UUID mapped = bukkitSessionUuidByHandshakeId.get(floodgateHandshakeOrInternalId);
        return mapped != null ? mapped : floodgateHandshakeOrInternalId;
    }

    private void putBukkitSessionMappingsIfNeeded(UUID sessionUuid, FloodgatePlayer player) {
        putOneHandshakeToSession(player.getJavaUniqueId(), sessionUuid);
        putOneHandshakeToSession(player.getCorrectUniqueId(), sessionUuid);
        try {
            String xuid = player.getXuid();
            if (xuid != null && !xuid.isEmpty()) {
                putOneHandshakeToSession(Utils.getJavaUuid(xuid), sessionUuid);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void putOneHandshakeToSession(UUID handshakeId, UUID sessionUuid) {
        if (handshakeId.equals(sessionUuid)) {
            bukkitSessionUuidByHandshakeId.remove(handshakeId);
        } else {
            bukkitSessionUuidByHandshakeId.put(handshakeId, sessionUuid);
        }
    }

    private void clearBukkitSessionMappings(FloodgatePlayer player) {
        bukkitSessionUuidByHandshakeId.remove(player.getJavaUniqueId());
        bukkitSessionUuidByHandshakeId.remove(player.getCorrectUniqueId());
        try {
            String xuid = player.getXuid();
            if (xuid != null && !xuid.isEmpty()) {
                bukkitSessionUuidByHandshakeId.remove(Utils.getJavaUuid(xuid));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private FloodgatePlayer findBedrockPlayerByVisibleName(String bukkitPlayerName) {
        if (bukkitPlayerName == null) {
            return null;
        }
        for (FloodgatePlayer p : new HashSet<>(players.values())) {
            if (profileNameMatches(p, bukkitPlayerName)) {
                return p;
            }
        }
        for (FloodgatePlayer p : pendingRemove.asMap().values()) {
            if (profileNameMatches(p, bukkitPlayerName)) {
                return p;
            }
        }
        return null;
    }

    private static boolean profileNameMatches(FloodgatePlayer p, String bukkitPlayerName) {
        return bukkitPlayerName.equals(p.getJavaUsername())
                || bukkitPlayerName.equals(p.getCorrectUsername())
                || bukkitPlayerName.equals(p.getUsername());
    }

    @Override
    public FloodgatePlayer getPlayer(UUID uuid) {
        FloodgatePlayer pendingLookup = pendingRemove.getIfPresent(uuid);
        if (pendingLookup != null) {
            return pendingLookup;
        }

        FloodgatePlayer selfPlayer = players.get(uuid);
        if (selfPlayer != null) {
            return selfPlayer;
        }

        // make it possible to find player by Java id (linked players)
        for (FloodgatePlayer player : players.values()) {
            if (player.getCorrectUniqueId().equals(uuid)) {
                return player;
            }
        }

        // Forwarded / legacy / name-based session UUIDs mirrored in {@link #offlineBedrockUuidToJoinedName}
        FloodgatePlayer byAux = findPlayerByLookupMap(uuid);
        if (byAux != null) {
            return byAux;
        }

        // and don't forget the pending remove linked players
        return getPendingRemovePlayer(uuid);
    }

    @Override
    public CompletableFuture<UUID> getUuidFor(String gamertag) {
        if (gamertag == null || gamertag.isEmpty() || gamertag.length() > 16) {
            return Utils.failedFuture(new IllegalStateException("Received an invalid gamertag"));
        }
        if (config.isUseOfflineUuids()) {
            String joined = Utils.computeJavaUsername(config, gamertag);
            return CompletableFuture.completedFuture(createJavaPlayerId(0L, joined));
        }
        return FloodgateApi.super.getUuidFor(gamertag);
    }

    @Override
    public UUID createJavaPlayerId(long xuid) {
        return Utils.getJavaUuid(xuid);
    }

    @Override
    public UUID createJavaPlayerId(long xuid, String floodgateJoinedUsername) {
        if (config.isUseOfflineUuids()) {
            return Utils.offlineLikeJoinUuid(config, floodgateJoinedUsername);
        }
        return Utils.getJavaUuid(xuid);
    }

    @Override
    public boolean isFloodgateId(UUID uuid) {
        if (uuid.getMostSignificantBits() == 0L) {
            return true;
        }
        return config.isUseOfflineUuids() && uuid.version() == 3;
    }

    @Override
    public boolean sendForm(UUID uuid, Form form) {
        FloodgatePlayer player = getPlayer(uuid);
        // Before this check was added, we used to just send the form to the user no matter if they
        // were a FloodgatePlayer or not. Keep this since the Floodgate API is deprecated anyway.
        if (player == null) {
            return true;
        }
        return pluginMessageManager.getChannel(FormChannel.class).sendForm(player, form);
    }

    @Override
    public boolean sendForm(UUID uuid, FormBuilder<?, ?, ?> formBuilder) {
        return sendForm(uuid, formBuilder.build());
    }

    @Override
    public boolean closeForm(UUID uuid) {
        FloodgatePlayer player = getPlayer(uuid);
        // Before this check was added, we used to just send the form to the user no matter if they
        // were a FloodgatePlayer or not. Keep this since the Floodgate API is deprecated anyway.
        if (player == null) {
            return true;
        }
        return pluginMessageManager.getChannel(FormChannel.class).closeForm(player);
    }

    @Override
    public boolean sendForm(UUID uuid, org.geysermc.cumulus.Form<?> form) {
        return sendForm(uuid, form.newForm());
    }

    @Override
    public boolean sendForm(UUID uuid, org.geysermc.cumulus.util.FormBuilder<?, ?> formBuilder) {
        return sendForm(uuid, formBuilder.build());
    }

    @Override
    public boolean transferPlayer(UUID uuid, String address, int port) {
        return pluginMessageManager
                .getChannel(TransferChannel.class)
                .sendTransfer(uuid, address, port);
    }

    @Override
    public CompletableFuture<Long> getXuidFor(String gamertag) {
        if (gamertag == null || gamertag.isEmpty() || gamertag.length() > 16) {
            return Utils.failedFuture(new IllegalStateException("Received an invalid gamertag"));
        }

        return httpClient.asyncGet(Constants.GET_XUID_URL + gamertag)
                .thenApply(result -> {
                    JsonObject response = result.getResponse();

                    if (!result.isCodeOk()) {
                        throw new IllegalStateException(response.get("message").getAsString());
                    }

                    JsonElement xuid = response.get("xuid");
                    return xuid != null ? xuid.getAsLong() : null;
                });
    }

    @Override
    public CompletableFuture<String> getGamertagFor(long xuid) {
        return httpClient.asyncGet(Constants.GET_GAMERTAG_URL + xuid)
                .thenApply(result -> {
                    JsonObject response = result.getResponse();

                    if (!result.isCodeOk()) {
                        throw new IllegalStateException(response.get("message").getAsString());
                    }

                    JsonElement gamertag = response.get("gamertag");
                    return gamertag != null ? gamertag.getAsString() : null;
                });
    }

    @Override
    public final Unsafe unsafe() {
        String callerClass = Thread.currentThread().getStackTrace()[2].getClassName();
        logger.warn("A plugin is trying to access an unsafe part of the Floodgate api!" +
                " The use of this api can result in client crashes if used incorrectly." +
                " Caller: " + callerClass);
        return new UnsafeFloodgateApi(pluginMessageManager);
    }

    public FloodgatePlayer addPlayer(FloodgatePlayer player) {
        FloodgatePlayer previous = players.put(player.getJavaUniqueId(), player);
        UUID correctId = player.getCorrectUniqueId();
        if (!correctId.equals(player.getJavaUniqueId())) {
            players.put(correctId, player);
        }
        registerBedrockLookupMirrors(player);
        if (previous != null && previous != player) {
            unregisterBedrockLookupMirrors(previous);
        }
        return previous;
    }

    /**
     * This method is invoked when the player is no longer on the server, but the related platform-
     * dependant event hasn't fired yet
     */
    public boolean setPendingRemove(FloodgatePlayer player) {
        pendingRemove.put(player.getJavaUniqueId(), player);
        UUID correctId = player.getCorrectUniqueId();
        if (!correctId.equals(player.getJavaUniqueId())) {
            pendingRemove.put(correctId, player);
        }
        boolean removed = players.remove(player.getJavaUniqueId(), player);
        players.remove(player.getCorrectUniqueId(), player);
        return removed;
    }

    public void playerRemoved(UUID disconnectUuid) {
        FloodgatePlayer pending = getPendingRemovePlayer(disconnectUuid);
        if (pending != null) {
            unregisterBedrockLookupMirrors(pending);
            pendingRemove.invalidate(pending.getJavaUniqueId());
            UUID correctId = pending.getCorrectUniqueId();
            if (!correctId.equals(pending.getJavaUniqueId())) {
                pendingRemove.invalidate(correctId);
            }
            return;
        }
        String joined = offlineBedrockUuidToJoinedName.remove(disconnectUuid);
        if (joined != null) {
            removeAllLookupKeysForJoinedName(joined);
        } else if (isFloodgateId(disconnectUuid)) {
            pendingRemove.invalidate(disconnectUuid);
        }
    }

    private void registerBedrockLookupMirrors(FloodgatePlayer player) {
        String joined = player.getJavaUsername();
        offlineBedrockJavaUsernames.add(joined);
        offlineBedrockUuidToJoinedName.put(player.getJavaUniqueId(), joined);
        offlineBedrockUuidToJoinedName.put(player.getCorrectUniqueId(), joined);
        try {
            String xuid = player.getXuid();
            if (xuid != null && !xuid.isEmpty()) {
                offlineBedrockUuidToJoinedName.put(Utils.getJavaUuid(xuid), joined);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void unregisterBedrockLookupMirrors(FloodgatePlayer player) {
        clearBukkitSessionMappings(player);
        removeAllLookupKeysForJoinedName(player.getJavaUsername());
    }

    private void removeAllLookupKeysForJoinedName(String joined) {
        if (joined == null) {
            return;
        }
        offlineBedrockJavaUsernames.remove(joined);
        Iterator<Map.Entry<UUID, String>> iterator = offlineBedrockUuidToJoinedName.entrySet().iterator();
        while (iterator.hasNext()) {
            if (joined.equals(iterator.next().getValue())) {
                iterator.remove();
            }
        }
    }

    private FloodgatePlayer findPlayerByLookupMap(UUID uuid) {
        String joined = offlineBedrockUuidToJoinedName.get(uuid);
        if (joined == null) {
            return null;
        }
        return findOnlineOrPendingByJavaUsername(joined);
    }

    private FloodgatePlayer findOnlineOrPendingByJavaUsername(String joined) {
        for (FloodgatePlayer p : new HashSet<>(players.values())) {
            if (joined.equals(p.getJavaUsername())) {
                return p;
            }
        }
        for (FloodgatePlayer p : pendingRemove.asMap().values()) {
            if (joined.equals(p.getJavaUsername())) {
                return p;
            }
        }
        return null;
    }

    public FloodgatePlayer getPendingRemovePlayer(UUID uuid) {
        for (FloodgatePlayer player : pendingRemove.asMap().values()) {
            if (player.getCorrectUniqueId().equals(uuid) || player.getJavaUniqueId().equals(uuid)) {
                return player;
            }
        }
        String joined = offlineBedrockUuidToJoinedName.get(uuid);
        if (joined != null) {
            for (FloodgatePlayer player : pendingRemove.asMap().values()) {
                if (joined.equals(player.getJavaUsername())) {
                    return player;
                }
            }
        }
        return null;
    }
}
