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

package dev.waterdog.waterdogpe.network.protocol.handler;

import dev.waterdog.waterdogpe.event.defaults.TransferCompleteEvent;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.connection.handler.ReconnectReason;
import dev.waterdog.waterdogpe.network.protocol.handler.downstream.ConnectedDownstreamHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.upstream.ConnectedUpstreamHandler;
import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;

import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.PHASE_1;
import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.PHASE_2;
import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.RESET;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectClearWeather;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectEntityImmobile;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectPosition;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectRemoveAllEffects;

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
    private final int sourceDimension;
    private final int targetDimension;

    private volatile TransferPhase transferPhase = PHASE_1;

    public TransferCallback(
            ProxiedPlayer player,
            ClientConnection connection,
            ServerInfo sourceServer,
            int sourceDimension,
            int targetDimension
    ) {
        this.player = player;
        this.connection = connection;
        this.targetServer = connection.getServerInfo();
        this.sourceServer = sourceServer;
        this.sourceDimension = sourceDimension;
        this.targetDimension = targetDimension;
    }

    public boolean onDimChangeSuccess() {
        switch (this.transferPhase) {
            case PHASE_1:
                this.onTransferPhase1Completed();
                this.transferPhase = PHASE_2;
                break;

            case PHASE_2:
                if (!this.completeTransferPhase2()) {
                    this.transferPhase = RESET;
                    return false;
                }
                this.transferPhase = RESET;
                break;

            default:
                return false;
        }
        return true;
    }

    public boolean onServerReadySignal(String reason) {
        if (this.transferPhase != PHASE_2) {
            return false;
        }

        this.player.getLogger().info(
                "Completing transfer of " + this.player.getName()
                        + " to " + this.targetServer.getServerName()
                        + " using fallback signal: " + reason
        );

        if (!this.completeTransferPhase2()) {
            this.transferPhase = RESET;
            return false;
        }

        this.transferPhase = RESET;
        return true;
    }

    private void onTransferPhase1Completed() {
        RewriteData rewriteData = this.player.getRewriteData();

        if (!this.player.isConnected()) {
            return;
        }

        injectRemoveAllEffects(this.player.getConnection(), rewriteData.getEntityId(), this.player.getProtocol());
        injectClearWeather(this.player.getConnection());
        injectEntityImmobile(this.player.getConnection(), rewriteData.getEntityId(), true);

        this.player.getLogger().info(
                "[" + this.player.getName() + "] Transfer phase 1 prepared for "
                        + this.targetServer.getServerName()
                        + " (oldDim=" + this.sourceDimension
                        + ", targetDim=" + this.targetDimension + ")"
        );

        injectPosition(
                this.player.getConnection(),
                rewriteData.getSpawnPosition(),
                rewriteData.getRotation(),
                rewriteData.getEntityId()
        );

        rewriteData.setDimension(this.targetDimension);

        injectClearWeather(this.player.getConnection());
        injectRemoveAllEffects(this.player.getConnection(), rewriteData.getEntityId(), this.player.getProtocol());
    }

    private boolean completeTransferPhase2() {
        RewriteData rewriteData = this.player.getRewriteData();
        rewriteData.setTransferCallback(null);

        if (!this.player.isConnected()) {
            this.connection.disconnect();
            return false;
        }

        if (!this.connection.isConnected()) {
            this.onTransferFailed();
            return false;
        }

        try {
            injectRemoveAllEffects(this.player.getConnection(), rewriteData.getEntityId(), this.player.getProtocol());
            injectClearWeather(this.player.getConnection());

            StopSoundPacket soundPacket = new StopSoundPacket();
            soundPacket.setSoundName("portal.travel");
            soundPacket.setStoppingAllSound(true);
            this.player.sendPacketImmediately(soundPacket);

            injectPosition(
                    this.player.getConnection(),
                    rewriteData.getSpawnPosition(),
                    rewriteData.getRotation(),
                    rewriteData.getEntityId()
            );

            rewriteData.setDimension(this.targetDimension);

            injectRemoveAllEffects(this.player.getConnection(), rewriteData.getEntityId(), this.player.getProtocol());
            injectClearWeather(this.player.getConnection());

            if (!this.connection.isConnected()) {
                this.onTransferFailed();
                return false;
            }

            SetLocalPlayerAsInitializedPacket initializedPacket = new SetLocalPlayerAsInitializedPacket();
            initializedPacket.setRuntimeEntityId(this.player.getRewriteData().getOriginalEntityId());
            this.connection.sendPacket(initializedPacket);

            this.connection.setPacketHandler(new ConnectedDownstreamHandler(this.player, this.connection));

            if (this.player.getConnection().getPacketHandler() instanceof ConnectedUpstreamHandler handler) {
                handler.setTargetConnection(this.connection);
            }

            this.player.getConnection().setTransferQueueActive(false);

            TransferCompleteEvent event = new TransferCompleteEvent(this.sourceServer, this.connection, this.player);
            this.player.getProxy().getEventManager().callEvent(event);

            this.player.armTransferSettleWindow();

            if (this.player.isConnected()) {
                this.player.flushQueuedTransfer();
            } else {
                this.connection.disconnect();
                return false;
            }

            injectRemoveAllEffects(this.player.getConnection(), rewriteData.getEntityId(), this.player.getProtocol());
            return true;
        } catch (Throwable t) {
            this.player.getLogger().warning(
                    "Failed to finalize transfer of " + this.player.getName()
                            + " to " + this.targetServer.getServerName()
                            + ": " + t.getMessage()
            );

            this.connection.disconnect();
            return false;
        }
    }

    public void onTransferFailed() {
        RewriteData rewriteData = this.player.getRewriteData();
        if (rewriteData.getTransferCallback() == this) {
            rewriteData.setTransferCallback(null);
        }

        this.player.getConnection().setTransferQueueActive(false);

        if (this.player.sendToFallback(this.targetServer, ReconnectReason.TRANSFER_FAILED, "Disconnected")) {
            this.player.sendMessage(new TranslationContainer("waterdog.connected.fallback", this.targetServer.getServerName()));
        } else {
            this.player.disconnect(new TranslationContainer(
                    "waterdog.downstream.transfer.failed",
                    this.targetServer.getServerName(),
                    "Server was closed"
            ));
        }

        this.connection.disconnect();
        this.player.getLogger().warning(
                "Failed to transfer " + this.player.getName()
                        + " to " + this.targetServer.getServerName()
                        + ": Server was closed"
        );
    }

    public TransferPhase getPhase() {
        return this.transferPhase;
    }
}
