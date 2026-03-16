/*
 * Copyright 2022 WaterdogTEAM
 *
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html  
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.player;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.command.CommandSender;
import dev.waterdog.waterdogpe.event.defaults.InitialServerDeterminedEvent;
import dev.waterdog.waterdogpe.event.defaults.PlayerDisconnectedEvent;
import dev.waterdog.waterdogpe.event.defaults.PlayerLoginEvent;
import dev.waterdog.waterdogpe.event.defaults.PlayerPermissionCheckEvent;
import dev.waterdog.waterdogpe.event.defaults.PlayerResourcePackInfoSendEvent;
import dev.waterdog.waterdogpe.event.defaults.ServerConnectedEvent;
import dev.waterdog.waterdogpe.event.defaults.ServerTransferRequestEvent;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.connection.codec.compression.CompressionType;
import dev.waterdog.waterdogpe.network.connection.handler.ReconnectReason;
import dev.waterdog.waterdogpe.network.connection.peer.BedrockServerSession;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.handler.PluginPacketHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback;
import dev.waterdog.waterdogpe.network.protocol.handler.downstream.CompressionInitHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.downstream.InitialHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.downstream.SwitchDownstreamHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.upstream.ConnectedUpstreamHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.upstream.ResourcePacksHandler;
import dev.waterdog.waterdogpe.network.protocol.rewrite.RewriteMaps;
import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.protocol.user.LoginData;
import dev.waterdog.waterdogpe.network.protocol.user.Platform;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.utils.types.Permission;
import dev.waterdog.waterdogpe.utils.types.TextContainer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollections;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.ToastRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base Player class.
 * Base Management of the Player System is done in here.
 */
public class ProxiedPlayer implements CommandSender {

    private static final long TRANSFER_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(400);

    private final ProxyServer proxy;

    @Getter
    private final BedrockServerSession connection;

    @Getter
    private final CompressionType compression;

    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicBoolean loginCalled = new AtomicBoolean(false);
    private final AtomicBoolean loginCompleted = new AtomicBoolean(false);

    private volatile CharSequence disconnectReason;

    @Getter
    private final RewriteData rewriteData = new RewriteData();

    @Getter
    private final LoginData loginData;

    @Getter
    private final RewriteMaps rewriteMaps;

    /**
     * Legacy entity cache used by existing cleanup logic.
     */
    @Getter
    private final LongSet entities = LongSets.synchronize(new LongOpenHashSet());

    /**
     * New shadow-state cache of entities that were actually sent to the client.
     * Transfer cleanup should prefer this one.
     */
    @Getter
    private final LongSet trackedClientEntities = LongSets.synchronize(new LongOpenHashSet());

    @Getter
    private final LongSet bossbars = LongSets.synchronize(new LongOpenHashSet());

    private final ObjectSet<UUID> players = ObjectSets.synchronize(new ObjectOpenHashSet<>());

    @Getter
    private final ObjectSet<Object> scoreboards = ObjectSets.synchronize(new ObjectOpenHashSet<>());

    @Getter
    private final Long2ObjectMap<ScoreInfo> scoreInfos =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    @Getter
    private final Long2LongMap entityLinks =
            Long2LongMaps.synchronize(new Long2LongOpenHashMap());

    @Getter
    private final LongSet chunkBlobs = LongSets.synchronize(new LongOpenHashSet());

    private final Object2ObjectMap<String, Permission> permissions = new Object2ObjectOpenHashMap<>();

    private final Collection<ServerInfo> pendingServers =
            ObjectCollections.synchronize(new ObjectArrayList<>());

    private ClientConnection clientConnection;
    private ClientConnection pendingConnection;

    private volatile ServerInfo queuedTransferTarget;
    private volatile long transferSettleUntilNanos = 0L;

    /**
     * Whether this player should have administrator status.
     * Players with administrator status are granted every permission,
     * even if not specifically applied.
     */
    @Setter
    @Getter
    private boolean admin = false;

    /**
     * Signalizes if connection bridges can do entity and block rewrite.
     * Since first StartGamePacket was received, we start with entity id and block rewrite.
     */
    @Setter
    private volatile boolean canRewrite = false;

    private volatile boolean hasUpstreamBridge = false;

    /**
     * Some downstream server software requires strict packet sending policy (like PMMP4).
     * To pass packet handler dedicated to SetLocalPlayerAsInitializedPacket only,
     * proxy has to post-complete server transfer.
     * Using this bool allows telling us if we except post-complete phase operation.
     * See ConnectedDownstreamHandler and SwitchDownstreamHandler for exact usage.
     */
    @Setter
    private volatile boolean acceptPlayStatus = false;

    /**
     * Used to determine if proxy can send resource packs packets to player.
     * This value is changed by PlayerResourcePackInfoSendEvent.
     */
    private volatile boolean acceptResourcePacks = true;

    /**
     * Used to determine if proxy can send ItemComponentPacket to player.
     * Client will crash if ItemComponentPacket is sent twice.
     */
    @Setter
    private volatile boolean acceptItemComponentPacket = true;

    /**
     * Additional downstream and upstream handlers can be set by plugin.
     * Do not set directly BedrockPacketHandler to sessions!
     */
    @Getter
    private final Collection<PluginPacketHandler> pluginPacketHandlers = new ObjectArrayList<>();

    public ProxiedPlayer(
            ProxyServer proxy,
            BedrockServerSession session,
            CompressionType compression,
            LoginData loginData
    ) {
        this.proxy = proxy;
        this.connection = session;
        this.compression = compression;
        this.loginData = loginData;
        this.rewriteMaps = new RewriteMaps(this);

        this.proxy.getPlayerManager().subscribePermissions(this);
        this.connection.addDisconnectListener(this::disconnect);
        this.rewriteData.setCodecHelper(session.getPeer().getCodecHelper());
    }

    /**
     * Called after sending LOGIN_SUCCESS in PlayStatusPacket.
     */
    public void initPlayer() {
        if (!this.loginCalled.compareAndSet(false, true)) {
            return;
        }

        PlayerLoginEvent event = new PlayerLoginEvent(this);
        this.proxy.getEventManager().callEvent(event).whenComplete((futureEvent, error) -> {
            this.loginCompleted.set(true);

            if (error != null) {
                this.getLogger().throwing(error);
                this.disconnect(new TranslationContainer("waterdog.downstream.initial.connect"));
                return;
            }

            if (event.isCancelled()) {
                this.disconnect(event.getCancelReason());
                return;
            }

            if (!this.isConnected() || this.disconnectReason != null) {
                this.disconnect(this.disconnectReason == null ? "Already disconnected" : this.disconnectReason);
                return;
            }

            if (this.proxy.getConfiguration().enableResourcePacks()) {
                this.sendResourcePacks();
            } else {
                this.initialConnect();
            }
        });
    }

    private void sendResourcePacks() {
        var packet = this.proxy.getPackManager().getPacksInfoPacket();
        PlayerResourcePackInfoSendEvent event = new PlayerResourcePackInfoSendEvent(this, packet);
        this.proxy.getEventManager().callEvent(event);

        if (event.isCancelled()) {
            this.acceptResourcePacks = false;
            this.initialConnect();
        } else {
            // 修改：使用 addPacketHandler 代替 setPacketHandler
            this.connection.addPacketHandler(new ResourcePacksHandler(this));
            this.connection.sendPacket(event.getPacket());
        }
    }

    /**
     * Called only on the initial connect.
     * Determines the first player the player gets transferred to
     * based on the currently present JoinHandler.
     */
    public final void initialConnect() {
        if (this.disconnected.get()) {
            return;
        }

        // 修改：使用 addPacketHandler 代替 setPacketHandler
        this.connection.addPacketHandler(new ConnectedUpstreamHandler(this));

        ServerInfo initialServer = this.proxy.getForcedHostHandler()
                .resolveForcedHost(this.loginData.getJoinHostname(), this);
        if (initialServer == null) {
            initialServer = this.proxy.getJoinHandler().determineServer(this);
        }

        if (initialServer == null) {
            this.disconnect(new TranslationContainer("waterdog.no.initial.server"));
            return;
        }

        InitialServerDeterminedEvent serverEvent = new InitialServerDeterminedEvent(this, initialServer);
        this.proxy.getEventManager().callEvent(serverEvent);
        this.connect(initialServer);
    }

    /**
     * Transfers the player to another downstream server.
     */
    public void connect(ServerInfo serverInfo) {
        Preconditions.checkNotNull(serverInfo, "Server info can not be null!");
        Preconditions.checkArgument(this.isConnected(), "User not connected");
        Preconditions.checkArgument(this.loginCompleted.get(), "User not logged in");

        ServerTransferRequestEvent event = new ServerTransferRequestEvent(this, serverInfo);
        ProxyServer.getInstance().getEventManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        ServerInfo targetServer = event.getTargetServer();

        if (this.clientConnection != null && this.clientConnection.getServerInfo() == targetServer) {
            this.sendMessage(new TranslationContainer("waterdog.downstream.connected", targetServer.getServerName()));
            return;
        }

        if (this.clientConnection != null && this.isTransferBusy()) {
            this.queueTransfer(targetServer);
            this.sendMessage(new TranslationContainer("waterdog.downstream.connecting", targetServer.getServerName()));
            return;
        }

        if (this.pendingServers.contains(targetServer)) {
            this.sendMessage(new TranslationContainer("waterdog.downstream.connecting", targetServer.getServerName()));
            return;
        }

        this.pendingServers.add(targetServer);

        ClientConnection connectingServer = this.getPendingConnection();
        if (connectingServer != null) {
            if (connectingServer.getServerInfo() == targetServer) {
                this.pendingServers.remove(targetServer);
                this.sendMessage(new TranslationContainer("waterdog.downstream.connecting", targetServer.getServerName()));
                return;
            } else {
                connectingServer.disconnect();
                this.getLogger().debug(
                        "Discarding pending connection for " + this.getName()
                                + "! Tried to join " + targetServer.getServerName()
                );
            }
            this.setPendingConnection(null);
        }

        targetServer.createConnection(this).addListener(future -> {
            ClientConnection connection = null;
            try {
                if (future.cause() == null) {
                    this.connect0(targetServer, connection = (ClientConnection) future.get());
                } else {
                    this.connectFailure(null, targetServer, future.cause());
                }
            } catch (Throwable e) {
                this.connectFailure(connection, targetServer, e);
                this.setPendingConnection(null);
            } finally {
                this.pendingServers.remove(targetServer);
            }
        });
    }

    private void connect0(ServerInfo targetServer, ClientConnection connection) {
        if (!this.isConnected()) {
            connection.disconnect();
            return;
        }

        ServerConnectedEvent event = new ServerConnectedEvent(this, connection);
        this.getProxy().getEventManager().callEvent(event);
        if (event.isCancelled() || !connection.isConnected()) {
            if (connection.isConnected()) {
                connection.disconnect();
            }
            return;
        }

        this.setPendingConnection(connection);
        connection.setCodecHelper(this.getProtocol().getCodec(), this.connection.getPeer().getCodecHelper());

        // 修改：直接使用处理器，不再依赖 BedrockPacketHandler 类型
        Object handler;
        if (this.clientConnection == null) {
            ((ConnectedUpstreamHandler) this.connection.getPacketHandler()).setTargetConnection(connection);
            this.hasUpstreamBridge = true;
            handler = new InitialHandler(this, connection);
        } else {
            handler = new SwitchDownstreamHandler(this, connection);
        }

        if (this.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_19_30)) {
            connection.addPacketHandler(new CompressionInitHandler(this, connection, handler));
        } else {
            connection.addPacketHandler((org.cloudburstmc.protocol.bedrock.handler.PacketHandler) handler);
            connection.sendPacket(this.loginData.getLoginPacket());
        }

        this.getLogger().info(
                "[{}|{}] -> Downstream [{}] has connected",
                connection.getSocketAddress(),
                this.getName(),
                targetServer.getServerName()
        );
    }

    private void connectFailure(ClientConnection connection, ServerInfo targetServer, Throwable error) {
        if (connection != null) {
            connection.disconnect();
        }

        if (this.disconnected.get()) {
            return;
        }

        this.getLogger().error(
                "[{}|{}] Unable to connect to downstream {}",
                this.getAddress(),
                this.getName(),
                targetServer.getServerName(),
                error
        );

        String exceptionMessage = Objects.requireNonNullElse(
                error.getLocalizedMessage(),
                error.getClass().getSimpleName()
        );

        if (this.sendToFallback(targetServer, ReconnectReason.EXCEPTION, exceptionMessage)) {
            this.sendMessage(new TranslationContainer("waterdog.connected.fallback", targetServer.getServerName()));
        } else {
            this.disconnect(new TranslationContainer(
                    "waterdog.downstream.transfer.failed",
                    targetServer.getServerName(),
                    exceptionMessage
            ));
        }
    }

    public void disconnect() {
        this.disconnect((String) null);
    }

    public void disconnect(TextContainer message) {
        if (message instanceof TranslationContainer) {
            this.disconnect(((TranslationContainer) message).getTranslated());
        } else {
            this.disconnect(message.getMessage());
        }
    }

    /**
     * Calls the PlayerDisconnectEvent and disconnects the player from downstream.
     */
    public void disconnect(CharSequence reason) {
        if (this.loginCalled.get() && !this.loginCompleted.get()) {
            this.disconnectReason = reason;
            return;
        }

        if (!this.disconnected.compareAndSet(false, true)) {
            return;
        }

        this.disconnectReason = reason;
        this.queuedTransferTarget = null;
        this.transferSettleUntilNanos = 0L;

        PlayerDisconnectedEvent event = new PlayerDisconnectedEvent(this, reason);
        this.proxy.getEventManager().callEvent(event);

        if (this.connection != null && this.connection.isConnected()) {
            this.connection.disconnect(reason);
        }

        if (this.clientConnection != null) {
            this.clientConnection.getServerInfo().removeConnection(this.clientConnection);
            this.clientConnection.disconnect();
        }

        ClientConnection connection = this.getPendingConnection();
        if (connection != null) {
            connection.disconnect();
        }

        this.trackedClientEntities.clear();
        this.entities.clear();
        this.entityLinks.clear();
        this.players.clear();
        this.bossbars.clear();
        this.scoreInfos.clear();
        this.scoreboards.clear();

        this.proxy.getPlayerManager().removePlayer(this);
        this.getLogger().info("[{}|{}] -> Upstream has disconnected: {}", this.getAddress(), this.getName(), reason);
    }

    public boolean sendToFallback(ServerInfo oldServer, String message) {
        return this.sendToFallback(oldServer, ReconnectReason.UNKNOWN, message);
    }

    /**
     * Send player to fallback server if any exists.
     */
    public boolean sendToFallback(ServerInfo oldServer, ReconnectReason reason, String message) {
        if (!this.isConnected()) {
            return false;
        }

        ServerInfo fallbackServer = this.proxy.getReconnectHandler()
                .getFallbackServer(this, oldServer, reason, message);

        if (fallbackServer != null && fallbackServer != this.getServerInfo()) {
            this.getLogger().debug(
                    "[{}] Connecting to fallback server {} with reason {}",
                    this.getName(),
                    fallbackServer.getServerName(),
                    reason.getName()
            );
            this.connect(fallbackServer);
            return true;
        }

        return false;
    }

    public final void onDownstreamTimeout(ServerInfo serverInfo) {
        if (!this.sendToFallback(serverInfo, ReconnectReason.TIMEOUT, "Downstream Timeout")) {
            this.disconnect(new TranslationContainer(
                    "waterdog.downstream.down",
                    serverInfo.getServerName(),
                    "Timeout"
            ));
        }
    }

    public final void onDownstreamDisconnected(ClientConnection connection) {
        this.getLogger().info(
                "[" + connection.getSocketAddress() + "|" + this.getName()
                        + "] -> Downstream [" + connection.getServerInfo().getServerName()
                        + "] has disconnected"
        );

        if (this.getPendingConnection() == connection) {
            this.setPendingConnection(null);
        }
    }

    /**
     * Shadow-state helpers for all client-visible entities.
     */
    public void trackClientEntity(long entityId) {
        if (entityId == 0L) {
            return;
        }

        long selfId = this.getRewriteData().getEntityId();
        if (entityId == selfId) {
            return;
        }

        this.trackedClientEntities.add(entityId);
        this.entities.add(entityId);
    }

    public void untrackClientEntity(long entityId) {
        if (entityId == 0L) {
            return;
        }

        this.trackedClientEntities.remove(entityId);
        this.entities.remove(entityId);

        LongArrayList linkKeysToRemove = new LongArrayList();
        for (Long2LongMap.Entry entry : this.entityLinks.long2LongEntrySet()) {
            if (entry.getLongKey() == entityId || entry.getLongValue() == entityId) {
                linkKeysToRemove.add(entry.getLongKey());
            }
        }

        for (long linkKey : linkKeysToRemove) {
            this.entityLinks.remove(linkKey);
        }
    }

    public LongSet copyTrackedClientEntities() {
        return new LongOpenHashSet(this.trackedClientEntities);
    }

    public void clearTrackedClientEntities() {
        this.trackedClientEntities.clear();
    }

    /**
     * Sends a packet to the upstream connection (client).
     */
    public void sendPacket(BedrockPacket packet) {
        if (this.connection != null && this.connection.isConnected()) {
            this.connection.sendPacket(packet);
        }
    }

    /**
     * Sends immediately packet to the upstream connection.
     */
    public void sendPacketImmediately(BedrockPacket packet) {
        if (this.connection != null && this.connection.isConnected()) {
            this.connection.sendPacketImmediately(packet);
        }
    }

    @Override
    public void sendMessage(TextContainer message) {
        if (message instanceof TranslationContainer) {
            this.sendTranslation((TranslationContainer) message);
        } else {
            this.sendMessage(message.getMessage());
        }
    }

    public void sendTranslation(TranslationContainer textContainer) {
        this.sendMessage(this.proxy.translate(textContainer));
    }

    @Override
    public void sendMessage(String message) {
        if (message.trim().isEmpty()) {
            return;
        }

        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.RAW);
        packet.setXuid(this.getXuid());
        packet.setMessage(message);
        this.sendPacket(packet);
    }

    public void chat(String message) {
        if (message.trim().isEmpty()) {
            return;
        }

        ClientConnection connection = this.getDownstreamConnection();
        if (connection == null || !connection.isConnected()) {
            return;
        }

        if (message.charAt(0) == '/') {
            CommandRequestPacket packet = new CommandRequestPacket();
            packet.setCommand(message);
            packet.setCommandOriginData(
                    new CommandOriginData(CommandOriginType.PLAYER, this.getUniqueId(), "", 0L)
            );
            packet.setInternal(false);
            connection.sendPacket(packet);
            return;
        }

        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.CHAT);
        packet.setSourceName(this.getName());
        packet.setXuid(this.getXuid());
        packet.setMessage(message);
        connection.sendPacket(packet);
    }

    public void sendPopup(String message, String subtitle) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.POPUP);
        packet.setMessage(message);
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void sendTip(String message) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.TIP);
        packet.setMessage(message);
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void setSubtitle(String subtitle) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.SUBTITLE);
        packet.setText(subtitle);
        packet.setXuid(this.getXuid());
        packet.setPlatformOnlineId("");
        this.sendPacket(packet);
    }

    public void setTitleAnimationTimes(int fadein, int duration, int fadeout) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.TIMES);
        packet.setFadeInTime(fadein);
        packet.setStayTime(duration);
        packet.setFadeOutTime(fadeout);
        packet.setXuid(this.getXuid());
        packet.setText("");
        packet.setPlatformOnlineId("");
        this.sendPacket(packet);
    }

    private void setTitle(String text) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.TITLE);
        packet.setText(text);
        packet.setXuid(this.getXuid());
        packet.setPlatformOnlineId("");
        this.sendPacket(packet);
    }

    public void clearTitle() {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.CLEAR);
        packet.setText("");
        packet.setXuid(this.getXuid());
        packet.setPlatformOnlineId("");
        this.sendPacket(packet);
    }

    public void resetTitleSettings() {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.RESET);
        packet.setText("");
        packet.setXuid(this.getXuid());
        packet.setPlatformOnlineId("");
        this.sendPacket(packet);
    }

    public void sendTitle(String title) {
        this.sendTitle(title, null, 20, 20, 5);
    }

    public void sendTitle(String title, String subtitle) {
        this.sendTitle(title, subtitle, 20, 20, 5);
    }

    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        this.setTitleAnimationTimes(fadeIn, stay, fadeOut);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            this.setSubtitle(subtitle);
        }
        this.setTitle((title == null || title.isEmpty()) ? " " : title);
    }

    public void sendToastMessage(String title, String content) {
        if (this.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_19_0)) {
            return;
        }

        ToastRequestPacket packet = new ToastRequestPacket();
        packet.setTitle(title);
        packet.setContent(content);
        this.sendPacket(packet);
    }

    public void redirectServer(ServerInfo serverInfo) {
        Preconditions.checkNotNull(serverInfo, "Server info can not be null!");

        TransferPacket packet = new TransferPacket();
        packet.setAddress(serverInfo.getPublicAddress().getHostString());
        packet.setPort(serverInfo.getPublicAddress().getPort());
        this.sendPacket(packet);
    }

    public boolean addPermission(String permission) {
        return this.addPermission(new Permission(permission, true));
    }

    public boolean addPermission(Permission permission) {
        Permission oldPerm = this.permissions.get(permission.getName());
        if (oldPerm == null) {
            this.permissions.put(permission.getName(), permission);
            return true;
        }
        return oldPerm.getAtomicValue().getAndSet(permission.getValue()) != permission.getValue();
    }

    @Override
    public boolean hasPermission(String permission) {
        if (this.admin || permission.isEmpty()) {
            return true;
        }

        Permission perm = this.permissions.get(permission.toLowerCase());
        boolean result = perm != null && perm.getValue();

        PlayerPermissionCheckEvent event = new PlayerPermissionCheckEvent(this, permission, result);
        this.getProxy().getEventManager().callEvent(event);
        return event.hasPermission();
    }

    public boolean removePermission(String permission) {
        return this.permissions.remove(permission.toLowerCase()) != null;
    }

    public Permission getPermission(String permission) {
        return this.permissions.get(permission.toLowerCase());
    }

    public Collection<Permission> getPermissions() {
        return Collections.unmodifiableCollection(this.permissions.values());
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    public long getPing() {
        return this.connection.getPing();
    }

    public ServerInfo getServerInfo() {
        return this.clientConnection == null ? null : this.clientConnection.getServerInfo();
    }

    public InetSocketAddress getAddress() {
        return this.connection == null ? null : (InetSocketAddress) this.connection.getSocketAddress();
    }

    @Override
    public ProxyServer getProxy() {
        return this.proxy;
    }

    public MainLogger getLogger() {
        return this.proxy.getLogger();
    }

    public void setDownstreamConnection(ClientConnection connection) {
        this.clientConnection = connection;
        if (this.getPendingConnection() == connection) {
            this.setPendingConnection(null);
        }
    }

    public ClientConnection getDownstreamConnection() {
        return this.clientConnection;
    }

    private synchronized ClientConnection getPendingConnection() {
        return this.pendingConnection;
    }

    private synchronized void setPendingConnection(ClientConnection connection) {
        this.pendingConnection = connection;
    }

    public boolean isTransferBusy() {
        if (this.clientConnection == null) {
            return false;
        }

        TransferCallback transferCallback = this.rewriteData.getTransferCallback();
        return this.acceptPlayStatus
                || this.getPendingConnection() != null
                || (transferCallback != null && transferCallback.getPhase() != TransferCallback.TransferPhase.RESET)
                || System.nanoTime() < this.transferSettleUntilNanos;
    }

    public synchronized void queueTransfer(ServerInfo targetServer) {
        if (targetServer != null) {
            this.queuedTransferTarget = targetServer;
        }
    }

    public synchronized ServerInfo consumeQueuedTransfer() {
        ServerInfo targetServer = this.queuedTransferTarget;
        this.queuedTransferTarget = null;
        return targetServer;
    }

    public void armTransferSettleWindow() {
        this.transferSettleUntilNanos = System.nanoTime() + TRANSFER_SETTLE_NANOS;
    }

    public void flushQueuedTransfer() {
        ServerInfo targetServer = this.consumeQueuedTransfer();
        if (targetServer == null || !this.isConnected()) {
            return;
        }

        this.getProxy().getScheduler().scheduleDelayed(() -> {
            if (this.isConnected()) {
                this.connect(targetServer);
            }
        }, 10);
    }

    public Collection<ServerInfo> getPendingServers() {
        return Collections.unmodifiableCollection(this.pendingServers);
    }

    public ServerInfo getConnectingServer() {
        return this.pendingConnection == null ? null : this.pendingConnection.getServerInfo();
    }

    public boolean isConnected() {
        return !this.disconnected.get() && this.connection != null && this.connection.isConnected();
    }

    @Override
    public String getName() {
        return this.loginData.getDisplayName();
    }

    public UUID getUniqueId() {
        return this.loginData.getUuid();
    }

    public String getXuid() {
        return this.loginData.getXuid();
    }

    public Platform getDevicePlatform() {
        return this.loginData.getDevicePlatform();
    }

    public String getDeviceModel() {
        return this.loginData.getDeviceModel();
    }

    public String getDeviceId() {
        return this.loginData.getDeviceId();
    }

    public ProtocolVersion getProtocol() {
        return this.loginData.getProtocol();
    }

    public boolean canRewrite() {
        return this.canRewrite;
    }

    public boolean hasUpstreamBridge() {
        return this.hasUpstreamBridge;
    }

    public Collection<UUID> getPlayers() {
        return this.players;
    }

    public boolean acceptPlayStatus() {
        return this.acceptPlayStatus;
    }

    public boolean acceptResourcePacks() {
        return this.acceptResourcePacks;
    }

    public boolean acceptItemComponentPacket() {
        return this.acceptItemComponentPacket;
    }

    public String getDisconnectReason() {
        return this.getDisconnectReason(String.class);
    }

    public <T> T getDisconnectReason(Class<T> type) {
        return type.cast(this.disconnectReason);
    }

    @Override
    public String toString() {
        return "ProxiedPlayer(displayName=" + this.getName()
                + ", protocol=" + this.getProtocol()
                + ", connected=" + this.isConnected()
                + ", address=" + this.getAddress()
                + ", serverInfo=" + this.getServerInfo()
                + ")";
    }
}
