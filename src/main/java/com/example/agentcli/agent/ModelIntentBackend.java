package com.example.agentcli.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调用 OpenAI 兼容 LLM 的 NLU 后端,提供:
 * <ul>
 *   <li>严格 JSON 输出的系统 prompt(见 {@link #buildSystemPrompt})</li>
 *   <li>HTTP 8s 超时,失败回退到 {@code RuleBasedIntentBackend}</li>
 *   <li>对玩家请求的澄清要求直接透传</li>
 * </ul>
 *
 * <p>{@code model} 端点读取自 {@link ModelBackendConfig};</p>
 */
public final class ModelIntentBackend implements IntentBackend {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModelIntentBackend.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(8);

	private final ModelBackendConfig config;
	private final HttpClient httpClient;

	public ModelIntentBackend() {
		this(ModelBackendConfig.load());
	}

	ModelIntentBackend(ModelBackendConfig config) {
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.build();
	}

	@Override
	public String name() {
		return "model";
	}

	@Override
	public IntentParseResult parse(String rawText, AgentContext context, AgentActionCatalog catalog) {
		if (!config.isAvailable()) {
			return IntentParseResult.none(
				rawText == null ? "" : rawText.trim(),
				"模型后端未配置 API Key，已回退到规则解析。"
			);
		}
		String trimmed = rawText == null ? "" : rawText.trim();
		if (trimmed.isEmpty()) {
			return IntentParseResult.none("", "输入为空。");
		}
		try {
			return callModel(trimmed, context);
		} catch (Exception e) {
			LOGGER.warn("Model backend call failed, falling back to rule engine", e);
			return IntentParseResult.none(trimmed, "模型调用失败，已回退到规则解析。");
		}
	}

	private IntentParseResult callModel(String rawText, AgentContext context) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(config.apiBaseUrl() + "/chat/completions"))
			.timeout(TIMEOUT)
			.header("Authorization", "Bearer " + config.apiKey())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				buildRequestBody(rawText, context), StandardCharsets.UTF_8))
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			LOGGER.warn("Model API returned status {}: {}", response.statusCode(), response.body());
			return IntentParseResult.none(rawText, "模型 API 返回错误 (" + response.statusCode() + ")，已回退到规则解析。");
		}

		return parseResponse(rawText, response.body());
	}

	private String buildRequestBody(String rawText, AgentContext context) {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.modelId());
		body.addProperty("max_tokens", 512);
		body.addProperty("temperature", 0.1);

		JsonArray messages = new JsonArray();

		JsonObject system = new JsonObject();
		system.addProperty("role", "system");
		system.addProperty("content", buildSystemPrompt(context));
		messages.add(system);

		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", rawText);
		messages.add(user);

		body.add("messages", messages);
		return body.toString();
	}

	private static String buildSystemPrompt(AgentContext context) {
		String contextLine = context == null
			? "玩家上下文未知。"
			: String.format(Locale.ROOT,
				"玩家上下文: 位置=(%.1f, %.1f, %.1f), 维度=%s, 游戏模式=%s, 生命=%.1f/20, 饥饿=%d/20, 快捷栏=[%s]",
				context.position().x, context.position().y, context.position().z,
				context.dimensionKey(), context.gameMode().getName(),
				context.health(), context.foodLevel(), context.inventorySummary());

		return """
			你是 Minecraft 游戏中的严谨指令助手。玩家使用精确、清晰的自然语言描述需求，你负责将其映射为具体动作。

			核心原则:
			- 精确执行：玩家说"给我1把钻石剑"，你就给钻石剑x1；说"传送到出生点"，你就传送到出生点
			- 不要猜测模糊需求：如果玩家的表达有歧义、信息不足或可能对应多个动作，不要自作主张选择，而是返回 clarification 要求澄清
			- 物品必须明确：玩家必须说出具体物品名（如"钻石剑"而非"武器"），否则要求澄清
			- 数量可以默认：明确说"给我钻石"没提数量 -> 默认1个；但说"来点钻石" -> 需要澄清要几个
			- 效果/实体必须明确：如"速度效果"而非"跑快点"、"生成僵尸"而非"来个怪"

			可用动作(按类别):

			【控制】
			- HELP: 显示帮助。无参数。
			- STATUS: 查看当前状态。无参数。
			- UNDO: 撤销上一步。无参数。
			- CANCEL: 取消当前计划。无参数。

			【玩家自身】
			- TELEPORT_SELF: 传送自己。参数: target=spawn|origin|coordinates;可设 x,y,z;或 relative=true。
			- TELEPORT_OTHER: 传送其他玩家。参数: target=玩家名, destination=self|coordinates+(x,y,z)。
			- GIVE_ITEM: 给予物品。参数: item=minecraft:物品ID, count=数量(默认"1")。
			- SET_GAMEMODE: 切换模式。参数: mode=creative|survival|adventure|spectator。
			- GIVE_EFFECT: 药水效果。参数: effect=效果名, duration=秒(默认30), amplifier=等级(默认0)。
			- EFFECT_CLEAR: 清除全部药水效果。无参数。
			- ENCHANT_ITEM: 附魔手持物品。参数: enchantment=附魔ID, level=等级(默认1)。
			- HEAL: 治疗。参数: target=health|hunger|all。
			- SET_HEALTH: 设置最大血量。参数: health=数值(1-1024)。
			- DAMAGE_SELF: 伤害自己。参数: amount=数值(1-20,默认1)。
			- GIVE_XP: 经验操作。参数: amount=整数, mode=add|set。
			- KILL_SELF: 自杀。无参数。
			- RESPAWN_SELF: 强制重生。无参数。
			- RIDE_DISMOUNT: 离开坐骑。无参数。
			- SPECTATE: 进入旁观模式。无参数。
			- TAG_ADD: 加标签。参数: tag=标签名。
			- TAG_REMOVE: 移除标签。参数: tag=标签名。
			- TITLE_SELF: 显示标题。参数: text=文本或JSON。
			- TELLRAW_SELF: 原始文本消息。参数: text=JSON文本。

			【查询】
			- LOCATE_STRUCTURE: 定位结构。参数: structure=village|desert_pyramid|monument|fortress 等。
			- SEED: 查看世界种子。无参数。

			【世界】
			- SET_TIME: 调整时间。参数: time=day|noon|night|midnight。
			- SET_WEATHER: 天气。参数: weather=clear|rain|thunder。
			- SET_SPAWNPOINT: 设置出生点为当前位置。无参数。
			- DIFFICULTY: 调整难度。参数: difficulty=peaceful|easy|normal|hard。
			- GAMERULE_SET: 设置游戏规则。参数: rule=规则名, value=新值。
			- WORLD_BORDER: 世界边界。参数: mode=set|center, value=数值。
			- SPAWN_ENTITY: 生成实体。参数: entity=minecraft:实体ID, count=数量(默认1)。
			- KILL_DROPS: 清除全部掉落物。无参数。
			- SET_BLOCK: 放置方块。参数: block=minecraft:方块ID, 可选 x,y,z 或 position=feet。
			- FILL_REGION: 填充区域。参数: block=方块ID, x1,y1,z1,x2,y2,z2。
			- CLONE_REGION: 克隆区域到当前位置。参数: x1,y1,z1,x2,y2,z2。
			- SET_BIOME: 设置生物群系。参数: biome=生物群系ID。

			【通信】
			- BROADCAST_SAY: 广播消息。参数: message=内容。
			- MSG_PLAYER: 私聊玩家。参数: player=玩家名, message=内容。

			【计分/队伍】
			- SCOREBOARD_ADD: 添加计分项。参数: name=名称, criteria=判定条件ID。
			- TEAM_ADD: 创建队伍。参数: name=队伍名。

			【危险(默认黑名单)】
			- CLEAR_INVENTORY: 清空背包。无参数。
			- RUN_COMMAND: 执行 Minecraft 1.21.1 服务端已注册的原生命令。参数: command=不带开头斜杠的单条命令字符串。

			RUN_COMMAND 使用规则:
			- 优先使用上面的结构化动作；只有请求超出结构化动作覆盖范围时才使用 RUN_COMMAND。
			- command 必须是一条完整 Minecraft 命令，不要带开头 "/"，不要包含换行或多条命令。
			- 不要生成 agent 或 execute 根命令。
			- 不要使用管理员命令(op/ban/kick/whitelist/stop/reload 等),这些走专用 typed action 且默认禁用。
			- 可以使用 Minecraft 1.21.1 的非管理原生命令，如 advancement, attribute, bossbar, clear, clone, damage, data, datapack, debug, defaultgamemode, difficulty, effect, enchant, experience, fill, fillbiome, forceload, function, gamerule, give, item, jfr, kill, list, locate, loot, me, msg, particle, playsound, random, recipe, return, ride, say, schedule, scoreboard, seed, setblock, setidletimeout, setworldspawn, spawnpoint, spectate, spreadplayers, stopsound, summon, tag, team, teammsg, teleport, tell, tellraw, tick, time, title, tm, tp, transfer, trigger, w, weather, worldborder, xp 等。
			- 如果目标、选择器、坐标、物品 ID、方块 ID、NBT 或数值不明确，返回 clarification，不要猜。

			%s

			何时需要澄清（返回 clarification 字段）:
			- 物品名称模糊："给我武器"、"来点吃的"、"给我工具" -> 要求具体说明想要什么物品
			- 数量不明确："来点钻石"、"给我一些石头" -> 要求指定具体数量
			- 效果名称模糊："跑快点"、"跳高点"、"不容易死" -> 要求说出具体效果名
			- 实体名称模糊："来个怪"、"生成动物"、"来点敌人" -> 要求指定具体实体
			- 意图不自明："我好无聊"、"这天气真烦"、"附近好暗" -> 要求明确说出想做什么
			- 存在多种可能解释："天亮了"（SET_TIME?SET_WEATHER?）-> 要求明确

			严谨自然语言示例（你应正确映射的）:
			"给我1个钻石" -> GIVE_ITEM(diamond, 1)
			"给我64个石头" -> GIVE_ITEM(stone, 64)
			"给我一把钻石剑" -> GIVE_ITEM(diamond_sword, 1)
			"在我脚下放一块钻石块" -> SET_BLOCK(block="minecraft:diamond_block", position="feet")
			"在100 64 200放一个黑曜石" -> SET_BLOCK(block="minecraft:obsidian", x=100, y=64, z=200)
			"把0 0 0到10 10 10的区域填满石头" -> FILL_REGION(block="minecraft:stone", x1=0, y1=0, z1=0, x2=10, y2=10, z2=10)
			"把Alice传送到我这里" -> TELEPORT_OTHER(target="Alice", destination="self")
			"把血量设为40" -> SET_HEALTH(health=40)
			"给我10点伤害" -> DAMAGE_SELF(amount=10)
			"给我10级经验" -> GIVE_XP(amount=10, mode="add")
			"清除我的经验" -> GIVE_XP(amount=0, mode="set")
			"自杀" -> KILL_SELF
			"下坐骑" -> RIDE_DISMOUNT
			"给我加glowing标签" -> TAG_ADD(tag="glowing")
			"显示标题hello" -> TITLE_SELF(text="hello")
			"最近的村庄在哪" -> LOCATE_STRUCTURE(structure="village")
			"世界种子" -> SEED
			"把难度改成和平" -> DIFFICULTY(peaceful)
			"设置死亡不掉落" -> GAMERULE_SET(rule="keepInventory", value="true")
			"把世界边界设为1000" -> WORLD_BORDER(mode="set", value=1000)
			"清掉地上所有物品" -> KILL_DROPS
			"广播:服务器即将重启" -> BROADCAST_SAY(message="服务器即将重启")
			"对Alice说你好" -> MSG_PLAYER(player="Alice", message="你好")
			"附魔锋利5级" -> ENCHANT_ITEM(enchantment="sharpness", level=5)
			"清除我的药水效果" -> EFFECT_CLEAR
			"把时间设为白天" / "切换到白天" -> SET_TIME(day)
			"把时间设为正午" -> SET_TIME(noon)
			"传送到出生点" -> TELEPORT_SELF(spawn)
			"传送到坐标100 64 200" -> TELEPORT_SELF(coordinates, 100, 64, 200)
			"往北传送100格" -> TELEPORT_SELF(relative, x=100)
			"恢复我的饥饿值" -> HEAL(hunger)
			"恢复我的血量" -> HEAL(health)
			"完全治疗我" -> HEAL(all)
			"把天气设为晴天" -> SET_WEATHER(clear)
			"把天气设为下雨" -> SET_WEATHER(rain)
			"把天气设为雷雨" -> SET_WEATHER(thunder)
			"给我速度效果30秒" -> GIVE_EFFECT(speed)
			"给我隐身效果60秒" -> GIVE_EFFECT(invisibility)
			"给我夜视效果" -> GIVE_EFFECT(night_vision)
			"给我力量效果2级" -> GIVE_EFFECT(strength, amplifier=2)
			"切换为创造模式" / "切创造" -> SET_GAMEMODE(creative)
			"切换为生存模式" / "切生存" -> SET_GAMEMODE(survival)
			"生成一只苦力怕" / "生成僵尸" -> SPAWN_ENTITY(creeper/zombie)
			"生成5只羊" -> SPAWN_ENTITY(sheep, 5)
			"清空我的背包" -> CLEAR_INVENTORY
			"撤销上一步" -> UNDO
			"查看我的状态" -> STATUS
			"显示帮助" -> HELP

			需要澄清的示例（不要执行，返回 clarification）:
			"给我武器" -> {"candidates":[],"clarification":"你想要什么武器？例如：钻石剑、弓、钻石镐。"}
			"来点吃的" -> {"candidates":[],"clarification":"你想要什么食物？例如：面包、牛排、金苹果。需要几个？"}
			"放个亮的东西" -> {"candidates":[],"clarification":"你想放什么方块？例如：海晶灯、红石灯、信标。"}
			"附个保护" -> {"candidates":[],"clarification":"哪种保护？保护(protection)、火焰保护(fire_protection)、弹射物保护(projectile_protection)?"}
			"我好无聊" -> {"candidates":[],"clarification":"你想做什么？例如：切换创造模式、生成实体、传送到新地点。"}

			物品别名: 钻石=diamond, 石头=stone, 木头=oak_log, 苹果=apple, 铁锭=iron_ingot, 金锭=gold_ingot, 煤炭=coal, 红石=redstone, 绿宝石=emerald, 下界合金锭=netherite_ingot, 钻石剑=diamond_sword, 钻石镐=diamond_pickaxe, 弓=bow, 圆石=cobblestone, 泥土=dirt, 沙子=sand, 玻璃=glass, 木板=oak_planks, 黑曜石=obsidian, 面包=bread, 牛排=cooked_beef, 金苹果=golden_apple, 末影珍珠=ender_pearl, 鞘翅=elytra, 烟花火箭=firework_rocket, 三叉戟=trident, 附魔金苹果=enchanted_golden_apple。
			方块别名: 钻石块=diamond_block, 金块=gold_block, 铁块=iron_block, 铜块=copper_block, 绿宝石块=emerald_block, 红石块=redstone_block, 基岩=bedrock, 海晶灯=sea_lantern, 信标=beacon, 刷怪笼=spawner, TNT=tnt, 水=water, 岩浆=lava, 火=fire。
			生物群系别名: 平原=plains, 沙漠=desert, 森林=forest, 丛林=jungle, 针叶林=taiga, 雪原=snowy_plains, 蘑菇岛=mushroom_fields, 沼泽=swamp, 沙滩=beach, 海洋=ocean, 下界=nether_wastes, 绯红森林=crimson_forest, 诡异森林=warped_forest, 末地=the_end。
			结构别名: 村庄=village, 掠夺者前哨站=pillager_outpost, 丛林神庙=jungle_temple, 沙漠神殿=desert_pyramid, 女巫小屋=swamp_hut, 雪屋=igloo, 海底神殿=monument, 末地城堡=end_city, 林地府邸=mansion, 下界要塞=fortress。
			附魔别名: 锋利=sharpness, 亡灵杀手=smite, 节肢杀手=bane_of_arthropods, 击退=knockback, 火焰附加=fire_aspect, 抢夺=looting, 效率=efficiency, 精准采集=silk_touch, 时运=fortune, 保护=protection, 火焰保护=fire_protection, 爆炸保护=blast_protection, 耐久=unbreaking, 经验修补=mending, 水下呼吸=respiration, 荆棘=thorns。
			实体别名: 苦力怕=creeper, 僵尸=zombie, 骷髅=skeleton, 猪=pig, 牛=cow, 羊=sheep, 马=horse, 村民=villager, 铁傀儡=iron_golem, 蜘蛛=spider, 末影人=enderman, 狼=wolf, 猫=cat, 鸡=chicken, 兔子=rabbit, 狐狸=fox, 蜜蜂=bee。
			效果别名: 速度=speed, 力量=strength, 再生=regeneration, 防火=fire_resistance, 夜视=night_vision, 隐身=invisibility, 跳跃=jump_boost, 水下呼吸=water_breathing, 急迫=haste, 抗性=resistance, 缓慢=slowness, 虚弱=weakness, 中毒=poison, 凋零=wither, 生命提升=health_boost, 伤害吸收=absorption。

			严格只返回一个 JSON 对象，不要输出任何其他文字。
			意图明确时: {"candidates":[{"action":"ACTION_TYPE","confidence":0.9,"summary":"简述","parameters":{}}]}
			意图模糊时: {"candidates":[],"clarification":"具体澄清问题，引导用户更精确地表达"}
			完全无关联时: {"candidates":[]}
			""".formatted(contextLine);
	}

	private static IntentParseResult parseResponse(String rawText, String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				return IntentParseResult.none(rawText, "模型返回空结果。");
			}
			String content = choices.get(0).getAsJsonObject()
				.getAsJsonObject("message")
				.get("content").getAsString().trim();
			content = stripCodeFences(content);

			JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();

			// 优先检查 clarification 字段：LLM 主动要求澄清
			String clarification = safeString(parsed, "clarification");
			if (!clarification.isEmpty()) {
				return IntentParseResult.none(rawText, clarification);
			}

			JsonArray candidatesJson = parsed.getAsJsonArray("candidates");
			if (candidatesJson == null || candidatesJson.isEmpty()) {
				return IntentParseResult.none(rawText, "模型未识别到意图。");
			}

			List<IntentCandidate> candidates = new ArrayList<>();
			for (JsonElement el : candidatesJson) {
				JsonObject obj = el.getAsJsonObject();
				ActionType actionType = parseActionType(safeString(obj, "action"));
				if (actionType == null) {
					continue;
				}
				Map<String, String> parameters = parseParameters(obj);
				if (actionType == ActionType.RUN_COMMAND) {
					parameters.computeIfPresent(MinecraftCommandSupport.COMMAND_PARAM, (_key, value) -> MinecraftCommandSupport.normalizeCommand(value));
				}
				candidates.add(new IntentCandidate(
					actionType,
					safeDouble(obj, "confidence", 0.8),
					safeString(obj, "summary"),
					parameters,
					parseRiskLevel(safeString(obj, "risk")),
					false
				));
			}
			if (candidates.isEmpty()) {
				return IntentParseResult.none(rawText, "模型返回的候选无法解析。");
			}
			return new IntentParseResult(rawText, candidates, false, null);
		} catch (Exception e) {
			LOGGER.warn("Failed to parse model response: {}", responseBody, e);
			return IntentParseResult.none(rawText, "模型响应解析失败。");
		}
	}

	private static String stripCodeFences(String text) {
		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			int lastFence = text.lastIndexOf("```");
			if (firstNewline > 0 && lastFence > firstNewline) {
				return text.substring(firstNewline + 1, lastFence).trim();
			}
		}
		return text;
	}

	private static ActionType parseActionType(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return ActionType.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static Map<String, String> parseParameters(JsonObject obj) {
		Map<String, String> params = new HashMap<>();
		JsonElement el = obj.get("parameters");
		if (el == null || !el.isJsonObject()) {
			return params;
		}
		JsonObject paramsObj = el.getAsJsonObject();
		for (String key : paramsObj.keySet()) {
			JsonElement val = paramsObj.get(key);
			params.put(key, val.isJsonNull() ? "" : val.getAsString());
		}
		return params;
	}

	private static RiskLevel parseRiskLevel(String name) {
		if (name == null || name.isBlank()) {
			return RiskLevel.LOW;
		}
		try {
			return RiskLevel.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return RiskLevel.LOW;
		}
	}

	private static String safeString(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return (el == null || el.isJsonNull()) ? "" : el.getAsString();
	}

	private static double safeDouble(JsonObject obj, String key, double fallback) {
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return fallback;
		}
		try {
			return el.getAsDouble();
		} catch (Exception e) {
			return fallback;
		}
	}
}
