package com.example.agentcli.agent;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

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
			.then(CommandManager.literal("confirm").executes(context -> {
				AgentService.get().confirm(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("cancel").executes(context -> {
				AgentService.get().cancel(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("undo").executes(context -> {
				AgentService.get().undo(context.getSource());
				return 1;
			}))
			.then(CommandManager.literal("history").executes(context -> {
				AgentService.get().history(context.getSource());
				return 1;
			}))
			.then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(context -> {
				AgentService.get().submit(context.getSource(), StringArgumentType.getString(context, "text"));
				return 1;
			}))
		);
	}
}
