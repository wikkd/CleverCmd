package com.example.agentcli.agent;

import com.example.agentcli.client.config.AiConfigStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 把 {@code /agent ...} 命令注册到 Minecraft 命令分派器。
 *
 * <p>支持的子命令:
 * <ul>
 *   <li>{@code /agent help}     - 列出可用意图</li>
 *   <li>{@code /agent status}   - 当前位置/模式/待确认</li>
 *   <li>{@code /agent undo}     - 撤销最近一次可回滚动作</li>
 *   <li>{@code /agent confirm | T} - 执行待确认动作</li>
 *   <li>{@code /agent cancel | F}  - 取消待确认</li>
 *   <li>{@code /agent history}  - 查看最近一次历史</li>
 *   <li>{@code /agent reload}   - 重新读取 AI 配置(需 permissionLevel >= 2)</li>
 *   <li>{@code /agent <自由文本>}   - 提交自然语言意图</li>
 * </ul>
 */
public final class AgentCommands {
	private AgentCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("agent")
			.then(CommandManager.literal("help").executes(context -> {
				AgentService.get().handleHelp(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("status").executes(context -> {
				AgentService.get().status(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("undo").executes(context -> {
				AgentService.get().undo(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("confirm").executes(context -> {
				AgentService.get().confirm(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("T").executes(context -> {
				AgentService.get().confirm(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("cancel").executes(context -> {
				AgentService.get().cancel(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("F").executes(context -> {
				AgentService.get().cancel(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("history").executes(context -> {
				AgentService.get().history(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("reload").requires(source -> source.hasPermissionLevel(2))
				.executes(context -> {
					AiConfigStore.reload();
					AgentConfirmationPolicy.loadFromClientConfig();
					context.getSource().sendFeedback(() -> Text.literal("已重新加载 AI 配置。").formatted(Formatting.GREEN), true);
					return 1;
				}))
			.then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(context -> {
				AgentService.get().submit(context.getSource(), StringArgumentType.getString(context, "text"));
				return 1;
			}))
		);
	}
}
