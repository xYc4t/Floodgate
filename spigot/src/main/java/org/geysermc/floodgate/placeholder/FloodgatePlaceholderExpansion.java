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

package org.geysermc.floodgate.placeholder;

import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * PlaceholderAPI hooks when PlaceholderAPI is installed ({@code softdepend}).
 * Use {@code %floodgate_is_bedrock%} — {@code true} or {@code false}.
 *
 * <p>Uses {@link #onRequest(OfflinePlayer, String)} so placeholders still resolve when PlaceholderAPI
 * parses asynchronously or passes an {@link OfflinePlayer} that is not considered {@linkplain Player#isOnline()};
 * the stock bridge only forwards to {@link #onPlaceholderRequest(Player, String)} for online players.</p>
 */
public final class FloodgatePlaceholderExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final FloodgateApi api;

    public FloodgatePlaceholderExpansion(JavaPlugin plugin, FloodgateApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override
    public @NonNull String getIdentifier() {
        return "floodgate";
    }

    @Override
    public @NonNull String getAuthor() {
        List<String> authors = plugin.getDescription().getAuthors();
        return authors.isEmpty() ? "GeyserMC" : String.join(", ", authors);
    }

    @Override
    public @NonNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Primary entry for modern PlaceholderAPI (supports offline profile / async parse via UUID).
     */
    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NonNull String params) {
        return resolveBedrockPlaceholder(player, params);
    }

    @Override
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NonNull String params) {
        return resolveBedrockPlaceholder(player, params);
    }

    private @Nullable String resolveBedrockPlaceholder(@Nullable OfflinePlayer player, @NonNull String params) {
        String key = params.trim().toLowerCase(Locale.ROOT);
        if (!key.equals("is_bedrock") && !key.equals("bedrock")) {
            return null;
        }
        if (player == null) {
            return "";
        }
        boolean bedrock = api.isFloodgatePlayer(player.getUniqueId())
                || api.isFloodgateJoinedUsername(player.getName());
        return bedrock ? "true" : "false";
    }
}
