package com.example.agentcli.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.Test;

final class RuleBasedIntentBackendTest {
	private static AgentContext context() {
		return new AgentContext(
			null, null, UUID.randomUUID(), "Tester",
			new Vec3d(0, 64, 0), "minecraft:overworld", GameMode.SURVIVAL,
			2, 20.0F, 20, "(空)"
		);
	}

	private static IntentCandidate first(IntentParseResult result) {
		assertNotNull(result.candidates());
		assertFalse(result.candidates().isEmpty());
		return result.candidates().getFirst();
	}

	// ============================================================
	//  Tier A
	// ============================================================

	@Test
	void parsesKillSelf() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("自杀", context(), new AgentActionCatalog());
		assertEquals(ActionType.KILL_SELF, first(r).actionType());
	}

	@Test
	void parsesDamageSelf() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("给我10点伤害", context(), new AgentActionCatalog());
		assertEquals(ActionType.DAMAGE_SELF, first(r).actionType());
		assertEquals("10", first(r).parameters().get("amount"));
	}

	@Test
	void parsesGiveXpAdd() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("给我10级经验", context(), new AgentActionCatalog());
		assertEquals(ActionType.GIVE_XP, first(r).actionType());
		assertEquals("10", first(r).parameters().get("amount"));
	}

	@Test
	void parsesGiveXpSet() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("经验设为5级", context(), new AgentActionCatalog());
		assertEquals(ActionType.GIVE_XP, first(r).actionType());
		assertEquals("set", first(r).parameters().get("mode"));
	}

	@Test
	void parsesSetHealth() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("设置血量为40", context(), new AgentActionCatalog());
		assertEquals(ActionType.SET_HEALTH, first(r).actionType());
		assertEquals("40", first(r).parameters().get("health"));
	}

	@Test
	void parsesEffectClear() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("清除效果", context(), new AgentActionCatalog());
		assertEquals(ActionType.EFFECT_CLEAR, first(r).actionType());
	}

	@Test
	void parsesEnchantItem() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("附魔锋利", context(), new AgentActionCatalog());
		assertEquals(ActionType.ENCHANT_ITEM, first(r).actionType());
		assertEquals("sharpness", first(r).parameters().get("enchantment"));
	}

	@Test
	void parsesRideDismount() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("下坐骑", context(), new AgentActionCatalog());
		assertEquals(ActionType.RIDE_DISMOUNT, first(r).actionType());
	}

	@Test
	void parsesTagAdd() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("加标签glowing", context(), new AgentActionCatalog());
		assertEquals(ActionType.TAG_ADD, first(r).actionType());
		assertEquals("glowing", first(r).parameters().get("tag"));
	}

	@Test
	void parsesTeleportOther() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把Alice传送到我这里", context(), new AgentActionCatalog());
		assertEquals(ActionType.TELEPORT_OTHER, first(r).actionType());
		assertEquals("Alice", first(r).parameters().get("target"));
	}

	@Test
	void parsesTitleSelf() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("标题 hello world", context(), new AgentActionCatalog());
		assertEquals(ActionType.TITLE_SELF, first(r).actionType());
		assertTrue(first(r).parameters().get("text").contains("hello"));
	}

	// ============================================================
	//  Tier B
	// ============================================================

	@Test
	void parsesSetBlockAtFeet() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("在脚下放一个钻石块", context(), new AgentActionCatalog());
		assertEquals(ActionType.SET_BLOCK, first(r).actionType());
		assertEquals("minecraft:diamond_block", first(r).parameters().get("block"));
		assertEquals("feet", first(r).parameters().get("position"));
	}

	@Test
	void parsesFillRegion() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把0 0 0到10 10 10填满石头", context(), new AgentActionCatalog());
		assertEquals(ActionType.FILL_REGION, first(r).actionType());
		assertEquals("minecraft:stone", first(r).parameters().get("block"));
		assertEquals("0", first(r).parameters().get("x1"));
		assertEquals("10", first(r).parameters().get("x2"));
	}

	@Test
	void parsesDifficulty() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把难度改成困难", context(), new AgentActionCatalog());
		assertEquals(ActionType.DIFFICULTY, first(r).actionType());
		assertEquals("hard", first(r).parameters().get("difficulty"));
	}

	@Test
	void parsesGameruleSet() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("设置 keepInventory true", context(), new AgentActionCatalog());
		assertEquals(ActionType.GAMERULE_SET, first(r).actionType());
		assertEquals("keepInventory", first(r).parameters().get("rule"));
		assertEquals("true", first(r).parameters().get("value"));
	}

	@Test
	void parsesWorldBorder() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把世界边界设为1000", context(), new AgentActionCatalog());
		assertEquals(ActionType.WORLD_BORDER, first(r).actionType());
		assertEquals("set", first(r).parameters().get("mode"));
		assertEquals("1000", first(r).parameters().get("value"));
	}

	@Test
	void parsesSetBiome() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把附近生物群系设为平原", context(), new AgentActionCatalog());
		assertEquals(ActionType.SET_BIOME, first(r).actionType());
		assertEquals("minecraft:plains", first(r).parameters().get("biome"));
	}

	@Test
	void parsesKillDrops() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("清掉落物", context(), new AgentActionCatalog());
		assertEquals(ActionType.KILL_DROPS, first(r).actionType());
	}

	@Test
	void parsesCloneRegion() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("clone 0 0 0 10 10 10", context(), new AgentActionCatalog());
		assertEquals(ActionType.CLONE_REGION, first(r).actionType());
	}

	@Test
	void parsesSetSpawnpoint() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("设置出生点", context(), new AgentActionCatalog());
		assertEquals(ActionType.SET_SPAWNPOINT, first(r).actionType());
	}

	// ============================================================
	//  Tier C
	// ============================================================

	@Test
	void parsesBroadcastSay() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("广播: 服务器即将重启", context(), new AgentActionCatalog());
		assertEquals(ActionType.BROADCAST_SAY, first(r).actionType());
		assertTrue(first(r).parameters().get("message").contains("服务器"));
	}

	@Test
	void parsesMsgPlayer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("msg Alice 你好", context(), new AgentActionCatalog());
		assertEquals(ActionType.MSG_PLAYER, first(r).actionType());
		assertEquals("Alice", first(r).parameters().get("player"));
	}

	@Test
	void parsesLocateStructure() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("最近的村庄在哪", context(), new AgentActionCatalog());
		assertEquals(ActionType.LOCATE_STRUCTURE, first(r).actionType());
		assertEquals("village", first(r).parameters().get("structure"));
	}

	@Test
	void parsesSeed() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("种子", context(), new AgentActionCatalog());
		assertEquals(ActionType.SEED, first(r).actionType());
	}

	// ============================================================
	//  Tier D
	// ============================================================

	@Test
	void parsesScoreboardAdd() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("scoreboard objectives add deaths deathCount", context(), new AgentActionCatalog());
		assertEquals(ActionType.SCOREBOARD_ADD, first(r).actionType());
		assertEquals("deaths", first(r).parameters().get("name"));
		assertEquals("deathCount", first(r).parameters().get("criteria"));
	}

	@Test
	void parsesTeamAdd() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("添加队伍 red", context(), new AgentActionCatalog());
		assertEquals(ActionType.TEAM_ADD, first(r).actionType());
		assertEquals("red", first(r).parameters().get("name"));
	}

	// ============================================================
	//  Tier E (管理员)
	// ============================================================

	@Test
	void parsesOpPlayer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("op Alice", context(), new AgentActionCatalog());
		assertEquals(ActionType.OP_PLAYER, first(r).actionType());
		assertEquals("Alice", first(r).parameters().get("player"));
	}

	@Test
	void parsesKickPlayer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("kick Steve 违规", context(), new AgentActionCatalog());
		assertEquals(ActionType.KICK_PLAYER, first(r).actionType());
		assertEquals("Steve", first(r).parameters().get("player"));
		assertEquals("违规", first(r).parameters().get("reason"));
	}

	@Test
	void parsesBanPlayer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("ban Steve 作弊", context(), new AgentActionCatalog());
		assertEquals(ActionType.BAN_PLAYER, first(r).actionType());
		assertEquals("Steve", first(r).parameters().get("player"));
	}

	@Test
	void parsesPardonPlayer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("pardon Steve", context(), new AgentActionCatalog());
		assertEquals(ActionType.PARDON_PLAYER, first(r).actionType());
		assertEquals("Steve", first(r).parameters().get("player"));
	}

	@Test
	void parsesStopServer() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("关闭服务器", context(), new AgentActionCatalog());
		assertEquals(ActionType.STOP_SERVER, first(r).actionType());
	}

	@Test
	void parsesSaveAll() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("保存世界", context(), new AgentActionCatalog());
		assertEquals(ActionType.SAVE_ALL, first(r).actionType());
	}

	// ============================================================
	//  回归测试:既有用例仍可用
	// ============================================================

	@Test
	void parsesClassicGiveItem() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("给我3个钻石", context(), new AgentActionCatalog());
		assertEquals(ActionType.GIVE_ITEM, first(r).actionType());
		assertEquals("minecraft:diamond", first(r).parameters().get("item"));
		assertEquals("3", first(r).parameters().get("count"));
	}

	@Test
	void parsesClassicHelp() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("帮助", context(), new AgentActionCatalog());
		assertEquals(ActionType.HELP, first(r).actionType());
	}

	@Test
	void parsesClassicSetTime() {
		IntentParseResult r = new RuleBasedIntentBackend().parse("把时间设为白天", context(), new AgentActionCatalog());
		assertEquals(ActionType.SET_TIME, first(r).actionType());
		assertEquals("day", first(r).parameters().get("time"));
	}
}