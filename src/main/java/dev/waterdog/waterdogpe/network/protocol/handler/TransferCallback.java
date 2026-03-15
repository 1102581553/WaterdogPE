package dev.waterdog.waterdogpe.network.protocol.handler;

import dev.waterdog.waterdogpe.event.defaults.TransferCompleteEvent;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.connection.handler.ReconnectReason;
import dev.waterdog.waterdogpe.network.protocol.handler.downstream.ConnectedDownstreamHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.upstream.ConnectedUpstreamHandler;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;

import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.*;
import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.*;

public class TransferCallback {
    public enum TransferPhase {
        RESET,
        PHASE_1,
        PHASE_2
    }

    private final ProxiedPlayer player;
    private final ClientConnection connection;
    private final ServerInfo targetServer;
    private final ServerInfo sourceServer;
    private final int targetDimension;

    private volatile TransferPhase transferPhase = PHASE_1;

    public TransferCallback(ProxiedPlayer player, ClientConnection connection, ServerInfo sourceServer, int targetDimension) {
        this.player = player;
        this.connection = connection;
        this.targetServer = connection.getServerInfo();
        this.sourceServer = sourceServer;
        this.targetDimension = targetDimension;
    }

    public boolean onDimChangeSuccess() {
        switch (this.transferPhase) {
            case PHASE_1:
                this.onTransferPhase1Completed();
                this.transferPhase = PHASE_2;
                break;
            case PHASE_2:
                this.onTransferPhase2Completed();
                this.transferPhase = RESET;
                break;
            default:
                return false;
        }
        return true;
    }

    private void onTransferPhase1Completed() {
        RewriteData rewriteData = this.player.getRewriteData();
        injectEntityImmobile(this.player.getConnection(), rewriteData.getEntityId(), true);
        if (rewriteData.getDimension() == this.targetDimension) {
            return;
        }

        Vector3f fakePosition = rewriteData.getSpawnPosition().add(-2000, 0, -2000);
        injectPosition(this.player.getConnection(), fakePosition, rewriteData.getRotation(), rewriteData.getEntityId());

        rewriteData.setDimension(determineDimensionId(rewriteData.getDimension(), this.targetDimension));
        injectDimensionChange(this.player.getConnection(), rewriteData.getDimension(), rewriteData.getSpawnPosition(), rewriteData.getEntityId(), this.player.getProtocol(), true);
    }

    private void onTransferPhase2Completed() {
        RewriteData rewriteData = this.player.getRewriteData();
        rewriteData.setTransferCallback(null);

        StopSoundPacket soundPacket = new StopSoundPacket();
        soundPacket.setSoundName("portal.travel");
        soundPacket.setStoppingAllSound(true);
        this.player.sendPacketImmediately(soundPacket);

        injectPosition(this.player.getConnection(), rewriteData.getSpawnPosition(), rewriteData.getRotation(), rewriteData.getEntityId());

        if (!this.connection.isConnected()) {
            this.onTransferFailed();
            return;
        }

        SetLocalPlayerAsInitializedPacket initializedPacket = new SetLocalPlayerAsInitializedPacket();
        initializedPacket.setRuntimeEntityId(this.player.getRewriteData().getOriginalEntityId());
        this.connection.sendPacket(initializedPacket);

        this.connection.setPacketHandler(new ConnectedDownstreamHandler(player, this.connection));

        // 先设好 upstream 指向新连接，再 flush 队列
        if (this.player.getConnection().getPacketHandler() instanceof ConnectedUpstreamHandler handler) {
            handler.setTargetConnection(this.connection);
        }

        // 所有 handler 就绪后再释放队列
        this.player.getConnection().setTransferQueueActive(false);

        TransferCompleteEvent event = new TransferCompleteEvent(this.sourceServer, this.connection, this.player);
        this.player.getProxy().getEventManager().callEvent(event);
    }

    public void onTransferFailed() {
        this.player.getRewriteData().setTransferCallback(null);
        if (this.player.sendToFallback(this.targetServer, ReconnectReason.TRANSFER_FAILED, "Disconnected")) {
            this.player.sendMessage(new TranslationContainer("waterdog.connected.fallback", this.targetServer.getServerName()));
        } else {
            this.player.disconnect(new TranslationContainer("waterdog.downstream.transfer.failed", targetServer.getServerName(), "Server was closed"));
        }

        this.connection.disconnect();
        this.player.getLogger().warning("Failed to transfer " + this.player.getName() + " to " + this.targetServer.getServerName() + ": Server was closed");
    }

    public TransferPhase getPhase() {
        return this.transferPhase;
    }
}
