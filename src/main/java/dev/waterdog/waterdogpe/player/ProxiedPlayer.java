/*
 * Copyright 2022 WaterdogTEAM
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
import dev.waterdog.waterdogpe.event.defaults.*;
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
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base Player class.
 * Base Management of the Player System is done in here.
 */
public class ProxiedPlayer implements CommandSender {
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
    @Getter
    private final LongSet entities = LongSets.synchronize(new LongOpenHashSet());
    @Getter
    private final LongSet bossbars = LongSets.synchronize(new LongOpenHashSet());
    private final ObjectSet<UUID> players = ObjectSets.synchronize(new ObjectOpenHashSet<>());
    @Getter
    private final ObjectSet<String> scoreboards = ObjectSets.synchronize(new ObjectOpenHashSet<>());
    @Getter
    private final Long2ObjectMap<ScoreInfo> scoreInfos = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    @Getter
    private final Long2LongMap entityLinks = Long2LongMaps.synchronize(new Long2LongOpenHashMap());
    @Getter
    private final LongSet chunkBlobs = LongSets.synchronize(new LongOpenHashSet());
    private final Object2ObjectMap<String, Permission> permissions = new Object2ObjectOpenHashMap<>();
    private static final long TRANSFER_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(400);

    private final Collection<ServerInfo> pendingServers = ObjectCollections.synchronize(new ObjectArrayList<>());
    private ClientConnection clientConnection;
    private ClientConnection pendingConnection;
    private volatile ServerInfo queuedTransferTarget;
    private volatile long transferSettleUntilNanos = 0L;

    /**
     *  Whether this player should have administrator status.
     *  Players with administrator status are granted every permission, even if not specifically applied.
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
     * To pass packet handler dedicated to SetLocalPlayerAsInitializedPacket only, proxy has to post-complete server transfer.
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

    public ProxiedPlayer(ProxyServer proxy, BedrockServerSession session, CompressionType compression, LoginData loginData) {
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

            if (!this.isConnected() || this.disconnectReason != null) { // player might have disconnected itself
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
        ResourcePacksInfoPacket packet = this.proxy.getPackManager().getPacksInfoPacket();
        PlayerResourcePackInfoSendEvent event = new PlayerResourcePackInfoSendEvent(this, packet);
        this.proxy.getEventManager().callEvent(event);
        if (event.isCancelled()) {
            // Connect player to downstream without sending ResourcePacksInfoPacket
            this.acceptResourcePacks = false;
            this.initialConnect();
        } else {
            this.connection.setPacketHandler(new ResourcePacksHandler(this));
            this.connection.sendPacket(event.getPacket());
        }
    }

    /**
     * Called only on the initial connect.
     * Determines the first player the player gets transferred to based on the currently present JoinHandler.
     */
    public final void initialConnect() {
        if (this.disconnected.get()) {
            return;
        }

        this.connection.setPacketHandler(new ConnectedUpstreamHandler(this));
        // Determine forced host first
        ServerInfo initialServer = this.proxy.getForcedHostHandler().resolveForcedHost(this.loginData.getJoinHostname(), this);
        if (initialServer == null) {
            initialServer = this.proxy.getJoinHandler().determineServer(this);
        }

        if (initialServer == null) {
            this.disconnect(new TranslationContainer("waterdog.no.initial.server"));
            return;
        }

        // Event should not change initial server. For we use join handler.
        InitialServerDeterminedEvent serverEvent = new InitialServerDeterminedEvent(this, initialServer);
        this.proxy.getEventManager().callEvent(serverEvent);
        this.connect(initialServer);
    }

    /**
     * Transfers the player to another downstream server
     *
     * @param serverInfo ServerInfo of the target downstream server, can be received using ProxyServer#getServer
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
                this.getLogger().debug("Discarding pending connection for " + this.getName() + "! Tried to join " + targetServer.getServerName());
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

        connection.setCodecHelper(this.getProtocol().getCodec(),
                this.connection.getPeer().getCodecHelper());

        // Remove the BedrockPacketHandler part.
        BedrockPacketHandler handler;
        if (this.clientConnection == null) {
            ((ConnectedUpstreamHandler) this.connection.getPacketHandler()).setTargetConnection(connection);
            this.hasUpstreamBridge = true;
            handler = new InitialHandler(this, connection);
        } else {
            handler = new SwitchDownstreamHandler(this, connection);
        }

        if (this.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_19_30)) {
            connection.setPacketHandler(new CompressionInitHandler(this, connection, handler));
        } else {
            connection.setPacketHandler(handler);
            connection.sendPacket(this.loginData.getLoginPacket());
        }

        this.getLogger().info("[{}|{}] -> Downstream [{}] has connected", connection.getSocketAddress(), this.getName(), targetServer.getServerName());
    }
