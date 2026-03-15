package dev.waterdog.waterdogpe.network.protocol.command;

import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AvailableCommandsNormalizer {

    private AvailableCommandsNormalizer() {
    }

    public static void normalizeDownstream(AvailableCommandsPacket packet) {
        List<CommandData> commands = packet.getCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }

        EnumNameRegistry enumNameRegistry = new EnumNameRegistry();
        ListIterator<CommandData> iterator = commands.listIterator();

        while (iterator.hasNext()) {
            CommandData original = iterator.next();
            iterator.set(sanitizeCommand(original, enumNameRegistry));
        }
    }

    public static void injectProxyCommand(AvailableCommandsPacket packet, CommandData proxyCommand) {
        if (packet == null || proxyCommand == null) {
            return;
        }

        EnumNameRegistry enumNameRegistry = buildRegistryFromPacket(packet);
        CommandData sanitized = sanitizeCommand(proxyCommand, enumNameRegistry);

        // 避免和下游同名命令直接冲突；同名时跳过代理命令
        for (CommandData existing : packet.getCommands()) {
            if (equalsIgnoreCase(existing.getName(), sanitized.getName())) {
                return;
            }
        }

        packet.getCommands().add(sanitized);
    }

    public static void finalizePacket(AvailableCommandsPacket packet) {
        if (packet == null || packet.getCommands() == null) {
            return;
        }

        EnumNameRegistry enumNameRegistry = new EnumNameRegistry();
        List<CommandData> rewritten = new ArrayList<>(packet.getCommands().size());
        Set<String> seenCommands = new LinkedHashSet<>();

        for (CommandData command : packet.getCommands()) {
            CommandData sanitized = sanitizeCommand(command, enumNameRegistry);

            String key = sanitized.getName().toLowerCase();
            if (seenCommands.add(key)) {
                rewritten.add(sanitized);
            }
        }

        packet.getCommands().clear();
        packet.getCommands().addAll(rewritten);
    }

    private static EnumNameRegistry buildRegistryFromPacket(AvailableCommandsPacket packet) {
        EnumNameRegistry registry = new EnumNameRegistry();

        if (packet == null || packet.getCommands() == null) {
            return registry;
        }

        for (CommandData command : packet.getCommands()) {
            if (command == null) {
                continue;
            }

            if (command.getAliases() != null) {
                registry.reserve(
                        safeNonBlank(command.getAliases().getName(), safeNonBlank(command.getName(), "aliases") + "_aliases"),
                        EnumSignature.fromEnum(command.getAliases())
                );
            }

            CommandOverloadData[] overloads = command.getOverloads();
            if (overloads == null) {
                continue;
            }

            for (CommandOverloadData overload : overloads) {
                if (overload == null || overload.getOverloads() == null) {
                    continue;
                }

                for (CommandParamData param : overload.getOverloads()) {
                    if (param == null || param.getEnumData() == null) {
                        continue;
                    }

                    registry.reserve(
                            safeNonBlank(param.getEnumData().getName(), "enum"),
                            EnumSignature.fromEnum(param.getEnumData())
                    );
                }
            }
        }

        return registry;
    }

    private static CommandData sanitizeCommand(CommandData input, EnumNameRegistry enumNameRegistry) {
        if (input == null) {
            return createFallbackCommand("unknown");
        }

        String name = safeNonBlank(input.getName(), "unknown");
        String description = safeString(input.getDescription());

        CommandEnumData aliases = sanitizeAliases(input.getAliases(), name, enumNameRegistry);
        CommandOverloadData[] overloads = sanitizeOverloads(input.getOverloads(), enumNameRegistry);

        return new CommandData(
                name,
                description,
                input.getFlags() == null ? Collections.emptySet() : input.getFlags(),
                input.getPermission(),
                aliases,
                Collections.emptyList(),
                overloads
        );
    }

    private static CommandEnumData sanitizeAliases(CommandEnumData aliases, String commandName, EnumNameRegistry enumNameRegistry) {
        if (aliases == null || aliases.getValues() == null || aliases.getValues().isEmpty()) {
            return buildSingleAliasEnum(commandName, enumNameRegistry);
        }

        Map<String, Set<CommandEnumConstraint>> sanitizedValues = new LinkedHashMap<>();

        for (Map.Entry<String, Set<CommandEnumConstraint>> entry : aliases.getValues().entrySet()) {
            String alias = trimToNull(entry.getKey());
            if (alias == null) {
                continue;
            }

            Set<CommandEnumConstraint> constraints = entry.getValue();
            if (constraints == null || constraints.isEmpty()) {
                constraints = EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES);
            } else {
                constraints = EnumSet.copyOf(constraints);
            }

            sanitizedValues.put(alias, constraints);
        }

        if (sanitizedValues.isEmpty()) {
            sanitizedValues.put(commandName, EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));
        } else if (!sanitizedValues.containsKey(commandName)) {
            sanitizedValues.put(commandName, EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));
        }

        String preferredName = safeNonBlank(aliases.getName(), commandName + "_aliases");
        String finalName = enumNameRegistry.reserve(preferredName, EnumSignature.fromMap(sanitizedValues));

        return new CommandEnumData(finalName, sanitizedValues, aliases.isSoft());
    }

    private static CommandOverloadData[] sanitizeOverloads(CommandOverloadData[] overloads, EnumNameRegistry enumNameRegistry) {
        if (overloads == null || overloads.length == 0) {
            return new CommandOverloadData[]{createDefaultTextOverload()};
        }

        CommandOverloadData[] rewritten = new CommandOverloadData[overloads.length];

        for (int i = 0; i < overloads.length; i++) {
            CommandOverloadData overload = overloads[i];

            if (overload == null || overload.getOverloads() == null) {
                rewritten[i] = createDefaultTextOverload();
                continue;
            }

            CommandParamData[] params = overload.getOverloads();
            CommandParamData[] rewrittenParams = new CommandParamData[params.length];

            for (int j = 0; j < params.length; j++) {
                rewrittenParams[j] = sanitizeParam(params[j], j, enumNameRegistry);
            }

            rewritten[i] = new CommandOverloadData(overload.isChaining(), rewrittenParams);
        }

        return rewritten;
    }

    private static CommandParamData sanitizeParam(CommandParamData input, int index, EnumNameRegistry enumNameRegistry) {
        CommandParamData param = new CommandParamData();

        if (input == null) {
            param.setName("arg" + index);
            param.setOptional(true);
            param.setType(CommandParam.TEXT);
            return param;
        }

        param.setName(safeNonBlank(input.getName(), "arg" + index));
        param.setOptional(input.isOptional());
        param.setPostfix(trimToNull(input.getPostfix()));

        if (input.getEnumData() != null) {
            param.setEnumData(sanitizeGenericEnum(input.getEnumData(), param.getName(), enumNameRegistry));
        }

        if (input.getType() != null) {
            param.setType(input.getType());
        } else if (param.getEnumData() == null && param.getPostfix() == null) {
            /*
             * Cloudburst 已知有 CommandParamData.type == null 时再序列化失败的情况。
             * 当 enum/postfix 也没有时，降级成 TEXT 最安全。
             */
            param.setType(CommandParam.TEXT);
        }

        if (input.getOptions() != null) {
            try {
                param.getOptions().addAll(input.getOptions());
            } catch (Throwable ignored) {
                // 某些实现可能返回不可变集合或 null，忽略即可
            }
        }

        return param;
    }

    private static CommandEnumData sanitizeGenericEnum(CommandEnumData input, String fallbackName, EnumNameRegistry enumNameRegistry) {
        if (input == null || input.getValues() == null || input.getValues().isEmpty()) {
            return null;
        }

        Map<String, Set<CommandEnumConstraint>> sanitizedValues = new LinkedHashMap<>();

        for (Map.Entry<String, Set<CommandEnumConstraint>> entry : input.getValues().entrySet()) {
            String key = trimToNull(entry.getKey());
            if (key == null) {
                continue;
            }

            Set<CommandEnumConstraint> value = entry.getValue();
            if (value == null) {
                value = Collections.emptySet();
            }

            sanitizedValues.put(key, value.isEmpty() ? Collections.emptySet() : EnumSet.copyOf(value));
        }

        if (sanitizedValues.isEmpty()) {
            return null;
        }

        String preferredName = safeNonBlank(input.getName(), "wd_enum_" + fallbackName);
        String finalName = enumNameRegistry.reserve(preferredName, EnumSignature.fromMap(sanitizedValues));

        return new CommandEnumData(finalName, sanitizedValues, input.isSoft());
    }

    private static CommandData createFallbackCommand(String name) {
        return new CommandData(
                name,
                "",
                Collections.emptySet(),
                null,
                buildSingleAliasEnum(name, new EnumNameRegistry()),
                Collections.emptyList(),
                new CommandOverloadData[]{createDefaultTextOverload()}
        );
    }

    private static CommandEnumData buildSingleAliasEnum(String commandName, EnumNameRegistry enumNameRegistry) {
        Map<String, Set<CommandEnumConstraint>> aliases = new LinkedHashMap<>();
        aliases.put(commandName, EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));

        String enumName = enumNameRegistry.reserve(
                commandName + "_aliases",
                EnumSignature.fromMap(aliases)
        );

        return new CommandEnumData(enumName, aliases, false);
    }

    private static CommandOverloadData createDefaultTextOverload() {
        CommandParamData param = new CommandParamData();
        param.setName("args");
        param.setOptional(true);
        param.setType(CommandParam.TEXT);

        return new CommandOverloadData(false, new CommandParamData[]{param});
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String safeNonBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return Objects.equals(a == null ? null : a.toLowerCase(), b == null ? null : b.toLowerCase());
    }

    private static final class EnumNameRegistry {
        private final Map<String, EnumSignature> used = new HashMap<>();

        String reserve(String preferredName, EnumSignature signature) {
            String base = safeNonBlank(preferredName, "enum");

            EnumSignature existing = used.get(base);
            if (existing == null) {
                used.put(base, signature);
                return base;
            }

            if (existing.equals(signature)) {
                return base;
            }

            int index = 2;
            while (true) {
                String candidate = base + "_" + index++;
                EnumSignature other = used.get(candidate);
                if (other == null) {
                    used.put(candidate, signature);
                    return candidate;
                }
                if (other.equals(signature)) {
                    return candidate;
                }
            }
        }
    }

    private static final class EnumSignature {
        private final List<String> keys;
        private final boolean soft;

        private EnumSignature(List<String> keys, boolean soft) {
            this.keys = keys;
            this.soft = soft;
        }

        static EnumSignature fromEnum(CommandEnumData data) {
            if (data == null || data.getValues() == null) {
                return new EnumSignature(Collections.emptyList(), false);
            }
            return fromMap(data.getValues(), data.isSoft());
        }

        static EnumSignature fromMap(Map<String, Set<CommandEnumConstraint>> values) {
            return fromMap(values, false);
        }

        static EnumSignature fromMap(Map<String, Set<CommandEnumConstraint>> values, boolean soft) {
            List<String> keys = new ArrayList<>();
            if (values != null) {
                for (String key : values.keySet()) {
                    String sanitized = trimToNull(key);
                    if (sanitized != null) {
                        keys.add(sanitized);
                    }
                }
            }
            return new EnumSignature(keys, soft);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EnumSignature that)) return false;
            return soft == that.soft && Objects.equals(keys, that.keys);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keys, soft);
        }
    }
}
