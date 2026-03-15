package dev.waterdog.waterdogpe.command;

import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Base class for proxy commands
 */
public abstract class Command {

    /**
     * The name of the command
     */
    @Getter
    private final String name;

    /**
     * The command settings assigned to it
     */
    @Getter
    private final CommandSettings settings;

    private CommandData commandData;

    public Command(String name) {
        this(name, CommandSettings.empty());
    }

    public Command(String name, CommandSettings settings) {
        this.name = name;
        this.settings = settings;
    }

    public abstract boolean onExecute(CommandSender sender, String alias, String[] args);

    public String getDescription() {
        return this.settings.getDescription();
    }

    public String getUsageMessage() {
        return this.settings.getUsageMessage();
    }

    public String getPermissionMessage() {
        return this.settings.getPermissionMessage();
    }

    public CommandData getCommandData() {
        if (this.commandData == null) {
            this.commandData = this.buildNetworkData();
        }
        return this.commandData;
    }

    public String getPermission() {
        return this.settings.getPermission();
    }

    public Set<String> getAliases() {
        return this.settings.getAliases();
    }

    private CommandData buildNetworkData() {
        String commandName = sanitizeRequiredString(this.name, "wdproxy");
        String description = sanitizeOptionalString(this.getDescription());

        Map<String, Set<CommandEnumConstraint>> aliases = new LinkedHashMap<>();
        aliases.put(commandName, EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));

        for (String alias : this.settings.getAliases()) {
            alias = sanitizeAlias(alias);
            if (alias != null && !aliases.containsKey(alias)) {
                aliases.put(alias, EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));
            }
        }

        CommandOverloadData[] overloads = sanitizeOverloads(this.buildCommandOverloads());

        return new CommandData(
                commandName,
                description,
                Collections.emptySet(),
                CommandPermission.ANY,
                new CommandEnumData(commandName + "_aliases", aliases, false),
                Collections.emptyList(),
                overloads
        );
    }

    protected CommandOverloadData[] buildCommandOverloads() {
        CommandParamData simpleData = new CommandParamData();
        simpleData.setName("args");
        simpleData.setOptional(true);
        simpleData.setType(CommandParam.TEXT);

        return new CommandOverloadData[]{
                new CommandOverloadData(false, new CommandParamData[]{simpleData})
        };
    }

    private static CommandOverloadData[] sanitizeOverloads(CommandOverloadData[] overloads) {
        if (overloads == null || overloads.length == 0) {
            CommandParamData param = new CommandParamData();
            param.setName("args");
            param.setOptional(true);
            param.setType(CommandParam.TEXT);

            return new CommandOverloadData[]{
                    new CommandOverloadData(false, new CommandParamData[]{param})
            };
        }

        for (int i = 0; i < overloads.length; i++) {
            CommandOverloadData overload = overloads[i];

            if (overload == null || overload.getOverloads() == null) {
                overloads[i] = new CommandOverloadData(false, new CommandParamData[0]);
                continue;
            }

            CommandParamData[] params = overload.getOverloads();
            for (int j = 0; j < params.length; j++) {
                CommandParamData param = params[j];

                if (param == null) {
                    param = new CommandParamData();
                    params[j] = param;
                }

                if (param.getName() == null || param.getName().isBlank()) {
                    param.setName("arg" + j);
                }

                if (param.getType() == null && param.getEnumData() == null && param.getPostfix() == null) {
                    param.setType(CommandParam.TEXT);
                }
            }
        }

        return overloads;
    }

    private static String sanitizeRequiredString(String value, String fallback) {
        if (value == null) {
            return fallback;
        }

        value = value.trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String sanitizeOptionalString(String value) {
        return value == null ? "" : value;
    }

    private static String sanitizeAlias(String value) {
        if (value == null) {
            return null;
        }

        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
