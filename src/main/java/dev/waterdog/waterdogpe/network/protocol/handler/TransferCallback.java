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
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;

import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.PHASE_1;
import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.PHASE_2;
import static dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback.TransferPhase.RESET;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.determineDimensionId;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectDimensionChange;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectEntityImmobile;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectPosition;

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

    /**
     * BDS compatibility fallback:
     * If downstream is already sending world data but the client does not deliver
     * the second DIMENSION_CHANGE_SUCCESS in time, allow PHASE_2 to finish.
     */
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

    /**
     * 【你的核心要求】所有维度都按“相同维度逻辑”处理
     * 无论 dim0→dim0、dim1→dim0、dim0→dim1……全部强制走清理
     */
    private void onTransferPhase1Completed() {
        RewriteData rewriteData = this.player.getRewriteData();

        if (!this.player.isConnected()) {
            return;
        }

        injectEntityImmobile(this.player.getConnection(), rewriteData.getEntityId(), true);

        // === 强制相同维度效果（永远 flip）===
        // determineDimensionId(target, target) = 永远返回对立维度（0<->1）
        // 这样 dim1→dim0 也和 dim0→dim0 一样触发完整实体卸载
        int tempDimension = determineDimensionId(this.targetDimension, this.targetDimension);

        Vector3f fakePosition = rewriteData.getSpawnPosition().add(-2000, 0, -2000);
        injectPosition(this.player.getConnection(), fakePosition, rewriteData.getRotation(), rewriteData.getEntityId());

        rewriteData.setDimension(tempDimension);
        injectDimensionChange(
                this.player.getConnection(),
                tempDimension,
                rewriteData.getSpawnPosition(),
                rewriteData.getEntityId(),
                this.player.getProtocol(),
                true   // 发送 empty chunks + ACK
        );
        // === 改动结束 ===
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
            StopSoundPacket soundPacket = new StopSoundPacket();
            soundPacket.setSoundName("portal.travel");
            soundPacket.setStoppingAllSound(true);

            if (this.player.isConnected()) {
                this.player.sendPacketImmediately(soundPacket);
            } else {
                this.connection.disconnect();
                return false;
            }

            if (!this.player.isConnected()) {
                this.connection.disconnect();
                return false;
            }

            injectPosition(
                    this.player.getConnection(),
                    rewriteData.getSpawnPosition(),
                    rewriteData.getRotation(),
                    rewriteData.getEntityId()
            );

            // === fasttrans 维度快速纠正（phase2）===
            // 把维度切回真实 targetDimension，不发多余 empty chunks
            rewriteData.setDimension(this.targetDimension);
            injectDimensionChange(
                    this.player.getConnection(),
                    this.targetDimension,
                    rewriteData.getSpawnPosition(),
                    rewriteData.getEntityId(),
                    this.player.getProtocol(),
                    false   // false = 超快，不重复发 chunks
            );
            // === 结束 ===

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
