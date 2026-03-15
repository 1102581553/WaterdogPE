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

package dev.waterdog.waterdogpe.network.protocol.handler.downstream;

import dev.waterdog.waterdogpe.event.defaults.FastTransferRequestEvent;
import dev.waterdog.waterdogpe.event.defaults.PostTransferCompleteEvent;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.connection.handler.ReconnectReason;
import dev.waterdog.waterdogpe.network.protocol.Signals;
import dev.waterdog.waterdogpe.network.protocol.handler.PluginPacketHandler;
import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

import static dev.waterdog.waterdogpe.network.protocol.Signals.mergeSignals;
import static dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils.injectEntityImmobile;

public class ConnectedDownstreamHandler extends AbstractDownstreamHandler {

    public ConnectedDownstreamHandler(ProxiedPlayer player, ClientConnection connection) {
        super(player, connection);
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (this.isTransferQuarantineActive() && !this.isAllowedDuringFastTransfer(packet)) {
            return Signals.CANCEL;
        }

        PacketSignal signal = super.handlePacket(packet);

        if (!player.getPluginPacketHandlers().isEmpty()) {
            for (PluginPacketHandler handler : this.player.getPluginPacketHandlers()) {
                signal = mergeSignals(signal, handler.handlePacket(packet, PacketDirection.CLIENT_BOUND));
            }
        }

        if (signal != Signals.CANCEL) {
            this.updateClientEntityTracker(packet);
        }

        return signal;
    }

    private void updateClientEntityTracker(BedrockPacket packet) {
        if (packet instanceof AddPlayerPacket addPlayerPacket) {
            this.player.trackClientEntity(addPlayerPacket.getUniqueEntityId());
            return;
        }

        if (packet instanceof AddEntityPacket addEntityPacket) {
            this.player.trackClientEntity(addEntityPacket.getUniqueEntityId());
            return;
        }

        if (packet instanceof RemoveEntityPacket removeEntityPacket) {
            this.player.untrackClientEntity(removeEntityPacket.getUniqueEntityId());
            return;
        }

        if (packet instanceof SetEntityLinkPacket linkPacket) {
            EntityLinkData link = linkPacket.getEntityLink();
            if (link != null) {
                if (link.getType() == EntityLinkData.Type.REMOVE) {
                    this.player.getEntityLinks().remove(link.getFrom());
                } else {
                    this.player.getEntityLinks().put(link.getFrom(), link.getTo());
                }
            }
            return;
        }

        if (packet instanceof BossEventPacket bossEventPacket) {
            if (bossEventPacket.getAction() == BossEventPacket.Action.REMOVE) {
                this.player.getBossbars().remove(bossEventPacket.getBossUniqueEntityId());
            } else {
                this.player.getBossbars().add(bossEventPacket.getBossUniqueEntityId());
            }
            return;
        }

        if (packet instanceof PlayerListPacket playerListPacket) {
            if (playerListPacket.getAction() == PlayerListPacket.Action.ADD) {
                for (PlayerListPacket.Entry entry : playerListPacket.getEntries()) {
                    this.player.getPlayers().add(entry.getUuid());
                }
            } else if (playerListPacket.getAction() == PlayerListPacket.Action.REMOVE) {
                for (PlayerListPacket.Entry entry : playerListPacket.getEntries()) {
                    this.player.getPlayers().remove(entry.getUuid());
                }
            }
        }
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        if (!this.player.acceptPlayStatus() || packet.getStatus() != PlayStatusPacket.Status.PLAYER_SPAWN) {
            return PacketSignal.UNHANDLED;
        }

        this.player.setAcceptPlayStatus(false);

        RewriteData rewriteData = this.player.getRewriteData();
        if (!rewriteData.hasImmobileFlag()) {
            injectEntityImmobile(this.player.getConnection(), rewriteData.getEntityId(), false);
        }

        SetLocalPlayerAsInitializedPacket initializedPacket = new SetLocalPlayerAsInitializedPacket();
        initializedPacket.setRuntimeEntityId(rewriteData.getEntityId());
        this.connection.sendPacket(initializedPacket);

        PostTransferCompleteEvent event = new PostTransferCompleteEvent(this.connection, this.player);
        this.player.getProxy().getEventManager().callEvent(event);

        this.player.armTransferSettleWindow();
        this.player.getProxy().getScheduler().scheduleDelayed(() -> {
            if (this.player.isConnected()
                    && this.player.getConnection() != null
                    && this.player.getConnection().isConnected()) {
                this.player.flushQueuedTransfer();
            }
        }, 1);

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(TransferPacket packet) {
        if (!this.player.getProxy().getConfiguration().useFastTransfer()) {
            return PacketSignal.UNHANDLED;
        }

        ServerInfo serverInfo = this.player.getProxy().getServerInfo(packet.getAddress());
        if (serverInfo == null) {
            serverInfo = this.player.getProxy().getServerInfo(packet.getAddress(), packet.getPort());
        }

        FastTransferRequestEvent event = new FastTransferRequestEvent(
                serverInfo,
                this.player,
                packet.getAddress(),
                packet.getPort()
        );
        this.player.getProxy().getEventManager().callEvent(event);

        if (!event.isCancelled() && event.getServerInfo() != null) {
            if (this.player.isTransferBusy()) {
                this.player.queueTransfer(event.getServerInfo());
            } else {
                this.player.connect(event.getServerInfo());
            }
            return Signals.CANCEL;
        }

        return PacketSignal.UNHANDLED;
    }

    @Override
    public final PacketSignal handle(DisconnectPacket packet) {
        if (this.player.sendToFallback(
                this.connection.getServerInfo(),
                ReconnectReason.SERVER_KICK,
                packet.getKickMessage()
        )) {
            return Signals.CANCEL;
        }

        this.player.disconnect(new TranslationContainer("waterdog.downstream.kicked", packet.getKickMessage()));
        return Signals.CANCEL;
    }
}
