package com.example.agentcli.agent;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 对玩家通过 RUN_COMMAND 提交的 Minecraft 原生命令的预处理 + 闸门。
 *
 * <p>作用:
 * <ul>
 *   <li>{@link #normalizeCommand} 去掉开头的 "/" 和多余空白</li>
 *   <li>{@link #rootCommand} 提取根命令(支持命名空间)</li>
 *   <li>{@link #validate} 拒绝 {@code BLOCKED_ROOTS} 中的根命令(agent/execute),
 *       并把命令交给 Mojang 的 {@code CommandDispatcher} 解析以检查权限/语法</li>
 * </ul>
 *
 * <p>管理员指令(op/ban/kick 等)即使能通过这里,也会在 {@code PolicyEngine} 的
 * admin 闸门和 {@code AiConfigStore.allowAdminCommands} 处被再次拒绝。</p>
 */
final class MinecraftCommandSupport {
	static final String COMMAND_PARAM = "command";

	private static final Set<String> BLOCKED_ROOTS = Set.of(
		"agent",
		"execute"
	);

	private MinecraftCommandSupport() {
	}

	static String normalizeCommand(String rawCommand) {
		String command = rawCommand == null ? "" : rawCommand.trim();
		while (command.startsWith("/")) {
			command = command.substring(1).trim();
		}
		return command.replaceAll("\\s+", " ");
	}

	static String rootCommand(String command) {
		String normalized = normalizeCommand(command);
		if (normalized.isBlank()) {
			return "";
		}
		int firstSpace = normalized.indexOf(' ');
		String root = firstSpace < 0 ? normalized : normalized.substring(0, firstSpace);
		int namespace = root.indexOf(':');
		if (namespace >= 0 && namespace + 1 < root.length()) {
			root = root.substring(namespace + 1);
		}
		return root.toLowerCase(Locale.ROOT);
	}

	static CommandValidationResult validate(ServerCommandSource source, String rawCommand) {
		String command = normalizeCommand(rawCommand);
		if (command.isBlank()) {
			return CommandValidationResult.invalid("命令为空。");
		}
		if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf(';') >= 0) {
			return CommandValidationResult.invalid("一次只能执行一条 Minecraft 命令。");
		}
		String root = rootCommand(command);
		if (BLOCKED_ROOTS.contains(root)) {
			return CommandValidationResult.invalid("不允许通过 AI 执行 /" + root + "。");
		}
		ParseResults<ServerCommandSource> parsed = source.getDispatcher().parse(new StringReader(command), source);
		try {
			net.minecraft.server.command.CommandManager.throwException(parsed);
		} catch (CommandSyntaxException e) {
			return CommandValidationResult.invalid("命令无法解析或权限不足: " + e.getMessage());
		}
		if (parsed.getReader().canRead()) {
			return CommandValidationResult.invalid("命令包含无法解析的内容: " + parsed.getReader().getRemaining());
		}
		return CommandValidationResult.valid(command, root);
	}

	record CommandValidationResult(boolean valid, String command, String rootCommand, String reason) {
		static CommandValidationResult valid(String command, String rootCommand) {
			return new CommandValidationResult(true, command, rootCommand, "");
		}

		static CommandValidationResult invalid(String reason) {
			return new CommandValidationResult(false, "", "", reason);
		}
	}
}
