package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class AgentService {
	private static AgentService INSTANCE;

	private final AgentActionCatalog catalog = new AgentActionCatalog();
	private final List<IntentBackend> backends = List.of(new RuleBasedIntentBackend(), new ModelIntentBackend());
	private final PolicyEngine policyEngine = new PolicyEngine();
	private final AgentExecutionEngine executionEngine = new AgentExecutionEngine();
	private final AgentPersistentStore stateStore = new AgentPersistentStore();

	private AgentService() {
	}

	public static void initialize() {
		INSTANCE = new AgentService();
	}

	public static AgentService get() {
		return INSTANCE;
	}

	public void load(net.minecraft.server.MinecraftServer server) {
		stateStore.load(server);
	}

	public void save(net.minecraft.server.MinecraftServer server) {
		stateStore.save();
	}

	public void handleHelp(ServerCommandSource source) {
		source.sendFeedback(() -> Text.literal(catalog.helpText()).formatted(Formatting.AQUA), false);
	}

	public void submit(ServerCommandSource source, String rawText) {
		ServerPlayerEntity player = requirePlayer(source);
		AgentContext context = AgentContext.from(source);
		IntentParseResult parseResult = parse(rawText, context);
		if (parseResult.clarificationRequired()) {
			source.sendFeedback(() -> Text.literal(parseResult.clarificationPrompt()).formatted(Formatting.YELLOW), false);
			return;
		}

		IntentCandidate best = parseResult.candidates().stream()
			.max(java.util.Comparator.comparingDouble(IntentCandidate::confidence))
			.orElseThrow();

		if (isControlIntent(best.actionType())) {
			dispatchControlIntent(source, best.actionType());
			return;
		}

		UUID sessionId = UUID.randomUUID();
		ActionPlan plan = buildPlan(sessionId, player.getUuid(), rawText, parseResult.normalizedText(), best);
		PolicyDecision decision = policyEngine.evaluate(source, plan);
		AgentSession session = new AgentSession(
			sessionId,
			player.getUuid(),
			rawText,
			parseResult,
			plan,
			decision,
			decision.allowed() ? SessionStatus.PENDING_CONFIRMATION : SessionStatus.FAILED,
			decision.reason(),
			Instant.now(),
			Instant.now()
		);

		if (!decision.allowed()) {
			stateStore.appendHistory(new ExecutionRecord(
				UUID.randomUUID(),
				session.sessionId(),
				player.getUuid(),
				rawText,
				plan,
				ExecutionStatus.FAILED,
				"已拒绝: " + decision.reason(),
				List.of(),
				Instant.now()
			));
			source.sendFeedback(() -> Text.literal("已拒绝: " + decision.reason()).formatted(Formatting.RED), false);
			return;
		}

		if (decision.requiresConfirmation()) {
			stateStore.putPending(session);
			sendConfirmationPrompt(source, plan, decision);
			return;
		}

		executePlan(source, session);
	}

	public void confirm(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		Optional<AgentSession> pending = stateStore.getPending(player.getUuid());
		if (pending.isEmpty()) {
			source.sendFeedback(() -> Text.literal("没有待确认的计划。").formatted(Formatting.YELLOW), false);
			return;
		}
		executePlan(source, pending.get());
	}

	public void cancel(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		Optional<AgentSession> pending = stateStore.getPending(player.getUuid());
		if (pending.isEmpty()) {
			source.sendFeedback(() -> Text.literal("没有可取消的计划。").formatted(Formatting.YELLOW), false);
			return;
		}
		AgentSession current = pending.get();
		stateStore.clearPending(player.getUuid());
		stateStore.appendHistory(new ExecutionRecord(
			UUID.randomUUID(),
			current.sessionId(),
			player.getUuid(),
			current.rawText(),
			current.plan(),
			ExecutionStatus.CANCELLED,
			"已取消",
			List.of(),
			Instant.now()
		));
		source.sendFeedback(() -> Text.literal("已取消当前待确认计划。").formatted(Formatting.GRAY), false);
	}

	public void undo(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		Optional<ExecutionRecord> record = stateStore.findLastUndoable(player.getUuid());
		if (record.isEmpty()) {
			source.sendFeedback(() -> Text.literal("没有可撤销的历史记录。").formatted(Formatting.YELLOW), false);
			return;
		}
		ExecutionResult result = executionEngine.undo(source, record.get());
		stateStore.appendHistory(new ExecutionRecord(
			UUID.randomUUID(),
			record.get().sessionId(),
			player.getUuid(),
			"undo",
			record.get().plan(),
			result.status(),
			result.message(),
			List.of(),
			Instant.now()
		));
		source.sendFeedback(() -> Text.literal(result.message()).formatted(result.status() == ExecutionStatus.FAILED ? Formatting.RED : Formatting.GREEN), false);
	}

	public void status(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		StringBuilder builder = new StringBuilder();
		builder.append("当前状态: ").append(formatPos(player.getX(), player.getY(), player.getZ()));
		builder.append(" | 维度=").append(player.getWorld().getRegistryKey().getValue());
		builder.append(" | 模式=").append(player.interactionManager.getGameMode().getName());
		stateStore.getPending(player.getUuid()).ifPresentOrElse(
			session -> builder.append(" | 待确认=").append(session.plan().actionType().name()),
			() -> builder.append(" | 待确认=无")
		);
		source.sendFeedback(() -> Text.literal(builder.toString()).formatted(Formatting.AQUA), false);
	}

	public void history(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		Optional<ExecutionRecord> last = stateStore.findLastUndoable(player.getUuid());
		if (last.isEmpty()) {
			source.sendFeedback(() -> Text.literal("暂无执行历史。").formatted(Formatting.GRAY), false);
			return;
		}
		ExecutionRecord record = last.get();
		source.sendFeedback(() -> Text.literal("最近历史: " + record.plan().actionType().name() + " -> " + record.message()).formatted(Formatting.AQUA), false);
	}

	private IntentParseResult parse(String rawText, AgentContext context) {
		for (IntentBackend backend : backends) {
			IntentParseResult result = backend.parse(rawText, context, catalog);
			if (result.candidates() != null && !result.candidates().isEmpty()) {
				return result;
			}
		}
		return IntentParseResult.none(rawText, "未识别到可执行意图。输入“帮助”查看支持的表达。");
	}

	private static boolean isControlIntent(ActionType type) {
		return type == ActionType.HELP || type == ActionType.STATUS || type == ActionType.UNDO || type == ActionType.CANCEL;
	}

	private void dispatchControlIntent(ServerCommandSource source, ActionType type) {
		switch (type) {
			case HELP -> handleHelp(source);
			case STATUS -> status(source);
			case UNDO -> undo(source);
			case CANCEL -> cancel(source);
			default -> {
			}
		}
	}

	private ActionPlan buildPlan(UUID sessionId, UUID playerUuid, String rawText, String normalizedText, IntentCandidate candidate) {
		boolean reversible = switch (candidate.actionType()) {
			case TELEPORT_SELF, GIVE_ITEM, SET_TIME, SET_GAMEMODE -> true;
			default -> false;
		};
		return ActionPlan.singleStep(
			sessionId,
			playerUuid,
			rawText,
			normalizedText,
			candidate.actionType(),
			candidate.parameters(),
			candidate.riskLevel(),
			candidate.requiresConfirmation(),
			reversible,
			candidate.summary()
		);
	}

	private void executePlan(ServerCommandSource source, AgentSession session) {
		ServerPlayerEntity player = requirePlayer(source);
		ActionPlan plan = session.plan();
		ExecutionResult result = executionEngine.execute(source, plan);
		stateStore.clearPending(player.getUuid());
		stateStore.appendHistory(new ExecutionRecord(
			UUID.randomUUID(),
			session.sessionId(),
			player.getUuid(),
			session.rawText(),
			plan,
			result.status(),
			result.message(),
			result.undoActions(),
			Instant.now()
		));
		source.sendFeedback(() -> Text.literal(result.message()).formatted(result.status() == ExecutionStatus.FAILED ? Formatting.RED : Formatting.GREEN), false);
	}

	private void sendConfirmationPrompt(ServerCommandSource source, ActionPlan plan, PolicyDecision decision) {
		Text confirm = Text.literal("[确认执行]")
			.setStyle(Style.EMPTY.withColor(Formatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/agent confirm")));
		Text cancel = Text.literal("[取消]")
			.setStyle(Style.EMPTY.withColor(Formatting.RED).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/agent cancel")));
		Text summary = Text.literal(String.format(Locale.ROOT, "计划: %s | 风险=%s | 原因=%s ", plan.steps().getFirst().description(), decision.riskLevel().name(), decision.reason()));
		source.sendFeedback(() -> Text.empty().append(summary).append(confirm).append(Text.literal(" ")).append(cancel), false);
	}

	private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
		try {
			return source.getPlayerOrThrow();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Agent commands require a player source.", e);
		}
	}

	private static String formatPos(double x, double y, double z) {
		return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
	}
}
