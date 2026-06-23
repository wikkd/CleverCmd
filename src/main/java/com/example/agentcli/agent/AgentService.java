package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Agent 总入口:负责一次自然语言回合的全生命周期(提交 → 解析 → 策略 → 确认 → 执行 → 审计)。
 *
 * <p>通过 {@link #initialize()} 在 {@code ModInitializer} 阶段创建单例,
 * 通过 {@link #get()} 在 {@code AgentCommands} 中按命令类型分发调用。
 * 玩家上下文与已注册 session 通过方法参数和 {@code pendingSessions} map 持有,
 * 关闭服务端时通过 {@link #save(MinecraftServer)} 持久化历史。</p>
 */
public final class AgentService {
	private static AgentService INSTANCE;

	private final AgentActionCatalog catalog = new AgentActionCatalog();
	private final ModelIntentBackend modelBackend = new ModelIntentBackend();
	private final RuleBasedIntentBackend ruleBackend = new RuleBasedIntentBackend();
	private final PolicyEngine policyEngine = new PolicyEngine();
	private final AgentExecutionEngine executionEngine = new AgentExecutionEngine();
	private final AgentPersistentStore stateStore = new AgentPersistentStore();
	private final KnowledgeAssistant knowledgeAssistant = new KnowledgeAssistant();
	private final Map<UUID, AgentSession> pendingSessions = new HashMap<>();

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
			if (knowledgeAssistant.startAsyncLookup(source, rawText, context, parseResult.clarificationPrompt())) {
				source.sendFeedback(() -> Text.literal("正在检索 Wiki...").formatted(Formatting.YELLOW), false);
				return;
			}
			Optional<String> knowledgeReply = knowledgeAssistant.answerIfHelpful(rawText, context);
			if (knowledgeReply.isPresent()) {
				source.sendFeedback(() -> Text.literal(knowledgeReply.get()).formatted(Formatting.AQUA), false);
				return;
			}
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
		SessionStatus initialStatus;
		if (!decision.allowed()) {
			initialStatus = SessionStatus.FAILED;
		} else if (plan.requiresConfirmation()) {
			initialStatus = SessionStatus.PENDING_CONFIRMATION;
		} else {
			initialStatus = SessionStatus.EXECUTED;
		}
		AgentSession session = new AgentSession(
			sessionId,
			player.getUuid(),
			rawText,
			parseResult,
			plan,
			decision,
			initialStatus,
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

		if (plan.requiresConfirmation()) {
			pendingSessions.put(player.getUuid(), session);
			source.sendFeedback(() -> Text.literal(confirmationPrompt(session)).formatted(Formatting.YELLOW), false);
		} else {
			executePlan(source, session);
		}
	}

	public void confirm(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		AgentSession session = pendingSessions.remove(player.getUuid());
		if (session == null) {
			source.sendFeedback(() -> Text.literal("没有待确认的 AI 指令。").formatted(Formatting.YELLOW), false);
			return;
		}
		PolicyDecision latestDecision = policyEngine.evaluate(source, session.plan());
		if (!latestDecision.allowed()) {
			stateStore.appendHistory(new ExecutionRecord(
				UUID.randomUUID(),
				session.sessionId(),
				player.getUuid(),
				session.rawText(),
				session.plan(),
				ExecutionStatus.FAILED,
				"确认后已拒绝: " + latestDecision.reason(),
				List.of(),
				Instant.now()
			));
			source.sendFeedback(() -> Text.literal("确认后已拒绝: " + latestDecision.reason()).formatted(Formatting.RED), false);
			return;
		}
		executePlan(source, session);
	}

	public void cancel(ServerCommandSource source) {
		ServerPlayerEntity player = requirePlayer(source);
		AgentSession session = pendingSessions.remove(player.getUuid());
		if (session == null) {
			source.sendFeedback(() -> Text.literal("没有待取消的 AI 指令。").formatted(Formatting.YELLOW), false);
			return;
		}
		stateStore.appendHistory(new ExecutionRecord(
			UUID.randomUUID(),
			session.sessionId(),
			player.getUuid(),
			session.rawText(),
			session.plan(),
			ExecutionStatus.CANCELLED,
			"用户取消执行",
			List.of(),
			Instant.now()
		));
		source.sendFeedback(() -> Text.literal("已取消待确认的 AI 指令。").formatted(Formatting.GRAY), false);
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
		builder.append(" | wiki=").append(knowledgeAssistant.wikiState().name().toLowerCase(Locale.ROOT));
		AgentSession pending = pendingSessions.get(player.getUuid());
		if (pending != null) {
			builder.append(" | 待确认=").append(pending.plan().actionType().name());
		}
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
		// LLM 优先，规则引擎兜底
		IntentParseResult modelResult = modelBackend.parse(rawText, context, catalog);
		if (modelResult.candidates() != null && !modelResult.candidates().isEmpty()) {
			return modelResult;
		}
		IntentParseResult ruleResult = ruleBackend.parse(rawText, context, catalog);
		if (ruleResult.candidates() != null && !ruleResult.candidates().isEmpty()) {
			return ruleResult;
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
			// 原有可逆
			case TELEPORT_SELF, GIVE_ITEM, SET_TIME, SET_GAMEMODE,
				SET_WEATHER, GIVE_EFFECT, HEAL, CLEAR_INVENTORY, SPAWN_ENTITY -> true;
			// Tier A:玩家自身可逆
			case TELEPORT_OTHER, DAMAGE_SELF, SET_HEALTH, GIVE_XP, SPECTATE -> true;
			// Tier B:世界/方块可逆(全部可逆,通过 block NBT 快照)
			case SET_BLOCK, FILL_REGION, CLONE_REGION, SET_BIOME,
				SET_SPAWNPOINT, DIFFICULTY, GAMERULE_SET, WORLD_BORDER -> true;
			// Tier D:标签/队伍可逆
			case TAG_ADD, TAG_REMOVE, TEAM_ADD -> true;
			// RUN_COMMAND 与所有其他动作(含 Tier E 管理员)不可逆
			default -> false;
		};
		boolean requiresConfirmation = AgentConfirmationPolicy.get().requiresConfirmation(candidate.actionType());
		return ActionPlan.singleStep(
			sessionId,
			playerUuid,
			rawText,
			normalizedText,
			candidate.actionType(),
			candidate.parameters(),
			candidate.riskLevel(),
			requiresConfirmation,
			reversible,
			candidate.summary()
		);
	}

	private static String confirmationPrompt(AgentSession session) {
		ActionPlan plan = session.plan();
		ActionStep step = plan.steps().getFirst();
		String detail = step.parameters().isEmpty() ? "" : " 参数=" + step.parameters();
		return "请确认 AI 指令: " + plan.actionType().name() + detail
			+ " | 输入 /agent T 执行，或 /agent F 取消。";
	}

	private void executePlan(ServerCommandSource source, AgentSession session) {
		ActionPlan plan = session.plan();
		ExecutionResult result = executionEngine.execute(source, plan);
		stateStore.appendHistory(new ExecutionRecord(
			UUID.randomUUID(),
			session.sessionId(),
			session.playerUuid(),
			session.rawText(),
			plan,
			result.status(),
			result.message(),
			result.undoActions(),
			Instant.now()
		));
		source.sendFeedback(() -> Text.literal(result.message()).formatted(result.status() == ExecutionStatus.FAILED ? Formatting.RED : Formatting.GREEN), false);
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
