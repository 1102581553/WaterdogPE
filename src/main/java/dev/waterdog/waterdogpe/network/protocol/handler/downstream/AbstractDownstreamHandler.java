package dev.waterdog.waterdogpe.network.protocol.handler.downstream;

import dev.waterdog.waterdogpe.command.Command;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.Signals;
import dev.waterdog.waterdogpe.network.protocol.command.AvailableCommandsNormalizer;
import dev.waterdog.waterdogpe.network.protocol.handler.ProxyPacketHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback;
import dev.waterdog.waterdogpe.network.protocol.rewrite.RewriteMaps;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheMissResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.Collection;
import java.util.function.Consumer;

import static dev.waterdog.waterdogpe.network.protocol.Signals.mergeSignals;

public abstract class AbstractDownstreamHandler implements ProxyPacketHandler {

    protected final ClientConnection connection;
    protected final ProxiedPlayer player;

    public AbstractDownstreamHandler(ProxiedPlayer player, ClientConnection connection) {
        this.player = player;
        this.connection = connection;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (this.isTransferQuarantineActive() && !this.isAllowedDuringFastTransfer(packet)) {
            return Signals.CANCEL;
        }

        return ProxyPacketHandler.super.handlePacket(packet);
    }

    @Override
    public PacketSignal handle(ItemComponentPacket packet) {
        if (!this.player.acceptItemComponentPacket()) {
            return Signals.CANCEL;
        }

        player.setAcceptItemComponentPacket(false);

        if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_60)) {
            setItemDefinitions(packet.getItems());
        }

        return PacketSignal.UNHANDLED;
    }

    @Override
    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        if (this.player.getConnection().isConnected()) {
            this.player.getConnection().sendPacket(batch.retain());
        }
    }

    @Override
    public PacketSignal doPacketRewrite(BedrockPacket packet) {
        RewriteMaps rewriteMaps = this.player.getRewriteMaps();

        if (rewriteMaps.getBlockMap() != null) {
            return mergeSignals(
                    rewriteMaps.getBlockMap().doRewrite(packet),
                    ProxyPacketHandler.super.doPacketRewrite(packet)
            );
        }

        return ProxyPacketHandler.super.doPacketRewrite(packet);
    }

    @Override
    public PacketSignal handle(AvailableCommandsPacket packet) {
        if (!this.player.getProxy().getConfiguration().injectCommands()) {
            return PacketSignal.UNHANDLED;
        }

        try {
            int originalSize = packet.getCommands().size();

            AvailableCommandsNormalizer.normalizeDownstream(packet);

            for (Command command : this.player.getProxy().getCommandMap().getCommands().values()) {
                if (command.getPermission() == null || this.player.hasPermission(command.getPermission())) {
                    AvailableCommandsNormalizer.injectProxyCommand(packet, command.getCommandData());
                }
            }

            AvailableCommandsNormalizer.finalizePacket(packet);

            if (packet.getCommands().size() == originalSize) {
                return PacketSignal.UNHANDLED;
            }

            return PacketSignal.HANDLED;
        } catch (Throwable t) {
            this.player.getLogger().warning(
                    String.format(
                            "[%s|%s] Failed to normalize/inject AvailableCommandsPacket: %s",
                            this.player.getName(),
                            this.connection.getServerInfo().getServerName(),
                            t.getMessage()
                    )
            );

            /*
             * 这里返回 UNHANDLED 是为了尽量保留原始下游包透传，
             * 避免“已经标记 HANDLED 但重编码时再次崩溃”。
             */
            return PacketSignal.UNHANDLED;
        }
    }

    @Override
    public PacketSignal handle(ChunkRadiusUpdatedPacket packet) {
        this.player.getLoginData().getChunkRadius().setRadius(packet.getRadius());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ChangeDimensionPacket packet) {
        this.player.getRewriteData().setDimension(packet.getDimension());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheMissResponsePacket packet) {
        if (this.player.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_18_30)) {
            this.player.getChunkBlobs().removeAll(packet.getBlobs().keySet());
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CameraPresetsPacket packet) {
        setCameraPresetDefinitions(packet.getPresets());
        return PacketSignal.UNHANDLED;
    }

    protected PacketSignal onPlayStatus(PlayStatusPacket packet, Consumer<String> failedTask, ClientConnection connection) {
        String message;
        switch (packet.getStatus()) {
            case LOGIN_SUCCESS -> {
                if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_12)) {
                    connection.sendPacket(this.player.getLoginData().getCachePacket());
                }
                return Signals.CANCEL;
            }
            case LOGIN_FAILED_CLIENT_OLD, LOGIN_FAILED_SERVER_OLD -> message = "Incompatible version";
            case FAILED_SERVER_FULL_SUB_CLIENT -> message = "Server is full";
            default -> {
                return PacketSignal.UNHANDLED;
            }
        }

        failedTask.accept(message);
        return Signals.CANCEL;
    }

    @Override
    public RewriteMaps getRewriteMaps() {
        return this.player.getRewriteMaps();
    }

    @Override
    public ClientConnection getConnection() {
        return connection;
    }

    protected boolean isTransferQuarantineActive() {
        if (this.player.getRewriteData() == null || this.player.getRewriteData().getTransferCallback() == null) {
            return false;
        }

        return this.player.getRewriteData().getTransferCallback().getPhase() != TransferCallback.TransferPhase.RESET;
    }

    protected boolean isAllowedDuringFastTransfer(BedrockPacket packet) {
        return packet instanceof ServerToClientHandshakePacket
                || packet instanceof ClientToServerHandshakePacket
                || packet instanceof ResourcePacksInfoPacket
                || packet instanceof ResourcePackStackPacket
                || packet instanceof ResourcePackClientResponsePacket
                || packet instanceof StartGamePacket
                || packet instanceof PlayStatusPacket
                || packet instanceof DisconnectPacket
                || packet instanceof ChangeDimensionPacket
                || packet instanceof ChunkRadiusUpdatedPacket
                || packet instanceof ClientCacheMissResponsePacket
                || packet instanceof LevelChunkPacket
                || packet instanceof NetworkChunkPublisherUpdatePacket
                || packet instanceof ItemComponentPacket
                || packet instanceof CameraPresetsPacket
                || isPacketSimpleName(packet, "SubChunkPacket")
                || isPacketSimpleName(packet, "UpdateSubChunkBlocksPacket");
    }

    private static boolean isPacketSimpleName(BedrockPacket packet, String simpleName) {
        return packet.getClass().getSimpleName().equals(simpleName);
    }

    protected void setItemDefinitions(Collection<ItemDefinition> definitions) {
        BedrockCodecHelper codecHelper = this.player.getConnection().getPeer().getCodecHelper();

        SimpleDefinitionRegistry.Builder<ItemDefinition> itemRegistry = SimpleDefinitionRegistry.builder();
        IntSet runtimeIds = new IntOpenHashSet();

        for (ItemDefinition definition : definitions) {
            if (runtimeIds.add(definition.getRuntimeId())) {
                itemRegistry.add(definition);
            } else {
                player.getLogger().warning(
                        String.format(
                                "[%s|%s] has duplicate item definition: %s",
                                this.player.getName(),
                                this.connection.getServerInfo().getServerName(),
                                definition
                        )
                );
            }
        }

        codecHelper.setItemDefinitions(itemRegistry.build());
    }

    protected void setCameraPresetDefinitions(Collection<CameraPreset> presets) {
        BedrockCodecHelper codecHelper = this.player.getConnection().getPeer().getCodecHelper();

        SimpleDefinitionRegistry.Builder<NamedDefinition> registry = SimpleDefinitionRegistry.builder();
        int id = 0;

        for (CameraPreset preset : presets) {
            registry.add(new SimpleNamedDefinition(preset.getIdentifier(), id++));
        }

        codecHelper.setCameraPresetDefinitions(registry.build());
    }
}
