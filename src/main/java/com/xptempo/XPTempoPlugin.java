package com.xptempo;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.xptempo.api.Payloads;
import com.xptempo.api.XPTempoApi;
import com.xptempo.tracker.ContainerTracker;
import com.xptempo.tracker.GeOfferTracker;
import com.xptempo.tracker.XpTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

/**
 * Sends the logged-in character's own data to the XPTempo API: sessions, skills and XP deltas,
 * inventory/bank/equipment contents, GE offers, deaths and level-ups, plus an activity heartbeat.
 * Design notes: Vault/research/runelite-plugin.md.
 */
@Slf4j
@PluginDescriptor(
	name = "XPTempo",
	description = "Companion plugin for the XPTempo app: sends your own character's XP, wealth and activity to your XPTempo account (opt-in)",
	tags = {"xp", "tracker", "stats", "companion", "progress"}
)
public class XPTempoPlugin extends Plugin
{
	static final String VERSION = "0.5.0";
	/** "Your Vorkath kill count is: 178." and the chest / completion / harvest / lap variants. */
	private static final Pattern KILL_COUNT = Pattern.compile("^Your (.+?) (kill|completion|chest|harvest|success|lap|rumour|catch) count is:? ([\\d,]+)");
	/** StatChanged and GE bursts for two ticks after LOGGED_IN; XP and the display name are unreliable until then. */
	private static final int INIT_TICKS = 2;
	private static final long ACTIVE_WINDOW_MS = 2 * 60_000L;
	private static final long COMBAT_WINDOW_MS = 30_000L;
	private static final long EQUIPMENT_DEBOUNCE_MS = 5_000L;
	private static final EnumSet<WorldType> SKIPPED_WORLDS = EnumSet.of(
		WorldType.DEADMAN, WorldType.SEASONAL, WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD);
	/** Achievement diary completion varbits, one row per region in the API's region order, tiers Easy→Elite. */
	private static final String[] DIARY_REGIONS = {
		"ARDOUGNE", "DESERT", "FALADOR", "FREMENNIK", "KANDARIN", "KARAMJA", "KOUREND", "LUMBRIDGE", "MORYTANIA", "VARROCK", "WESTERN", "WILDERNESS"};
	private static final String[] DIARY_TIERS = {"EASY", "MEDIUM", "HARD", "ELITE"};
	private static final int[][] DIARY_VARBITS = {
		{Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE},
		{Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE},
		{Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE},
		{Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
		{Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE},
		{Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE},
		{Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE},
		{Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
		{Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
		{Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE},
		{Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE},
		{Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE},
	};
	private static final String[] CA_TIERS = {"EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER"};
	private static final int[] CA_TIER_VARBITS = {
		Varbits.COMBAT_ACHIEVEMENT_TIER_EASY, Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM, Varbits.COMBAT_ACHIEVEMENT_TIER_HARD,
		Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE, Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER, Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private XPTempoConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private XPTempoApi api;
	private final XpTracker tracker = new XpTracker();
	private final ContainerTracker containers = new ContainerTracker();
	private GeOfferTracker geOffers;
	private final List<Map<String, Object>> pendingEvents = new ArrayList<>();

	private int initTicks = -1;
	private long accountHash = -1L;
	private String displayName;
	private String accountType = "UNKNOWN";
	private int world;
	private String sessionId;
	private long lastFlushMs;
	private long lastHeartbeatMs;
	private int seq;
	private boolean urgent;
	private boolean milestonesDirty;
	private boolean pairing;
	private boolean bankOpen;
	private boolean geOpen;
	private long lastCombatMs;
	private String combatTarget;
	private volatile int xpFlushSeconds = 60;
	private volatile int heartbeatSeconds = 60;
	private volatile int inventoryDebounceSeconds = 60;
	private volatile int bankMaxEverySeconds = 30;

	@Provides
	XPTempoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(XPTempoConfig.class);
	}

	@Override
	protected void startUp()
	{
		geOffers = new GeOfferTracker(new GeOfferTracker.Store()
		{
			@Override
			public GeOfferTracker.SavedOffer load(int slot)
			{
				String json = configManager.getRSProfileConfiguration(XPTempoConfig.GROUP, "geoffer." + slot);
				return json == null || json.isEmpty() ? null : gson.fromJson(json, GeOfferTracker.SavedOffer.class);
			}

			@Override
			public void save(int slot, GeOfferTracker.SavedOffer offer)
			{
				configManager.setRSProfileConfiguration(XPTempoConfig.GROUP, "geoffer." + slot, gson.toJson(offer));
			}

			@Override
			public void clear(int slot)
			{
				configManager.unsetRSProfileConfiguration(XPTempoConfig.GROUP, "geoffer." + slot);
			}
		}, () -> UUID.randomUUID().toString());

		api = new XPTempoApi(okHttpClient, gson, executor, "XPTempo/" + VERSION, new XPTempoApi.Listener()
		{
			@Override
			public void onAck(Payloads.Ack ack)
			{
				if (ack.config != null)
				{
					if (ack.config.xpFlushSeconds >= 10)
					{
						xpFlushSeconds = ack.config.xpFlushSeconds;
					}
					if (ack.config.heartbeatSeconds >= 10)
					{
						heartbeatSeconds = ack.config.heartbeatSeconds;
					}
					if (ack.config.inventoryDebounceSeconds >= 5)
					{
						inventoryDebounceSeconds = ack.config.inventoryDebounceSeconds;
					}
					if (ack.config.bankMaxEverySeconds >= 5)
					{
						bankMaxEverySeconds = ack.config.bankMaxEverySeconds;
					}
				}
				if (ack.rejected != null)
				{
					for (Payloads.Rejection r : ack.rejected)
					{
						log.warn("XPTempo: event {} rejected: {} {}", r.seq, r.code, r.message);
					}
				}
				log.debug("XPTempo: batch {} accepted={} duplicates={}", ack.batchId, ack.accepted, ack.duplicates);
			}

			@Override
			public void onUnauthorized()
			{
				// A rejected key is useless and would block the next pairing attempt, so forget it here.
				configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_API_KEY, "");
				chat("XPTempo: the server rejected this client's key, so it was cleared. Get a pairing code from xptempo.com and paste it into the plugin settings.");
			}

			@Override
			public void onFailure(String message, boolean willRetry)
			{
				if (willRetry)
				{
					log.debug("XPTempo: {}", message);
				}
				else
				{
					log.warn("XPTempo: {}", message);
				}
			}
		});
		api.configure(XPTempoApi.baseUrl(), config.apiKey());
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				initTicks = INIT_TICKS;
			}
		});
		maybePair();
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() -> endSession("PLUGIN_DISABLED"));
		initTicks = -1;
	}

	// ---------------------------------------------------------------- game state

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
				initTicks = INIT_TICKS;
				bankOpen = false;
				geOpen = false;
				break;
			case LOGIN_SCREEN:
				endSession("LOGOUT");
				initTicks = -1;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (initTicks > 0 && --initTicks == 0)
		{
			seed();
		}
		if (sessionId == null || initTicks != 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean due = false;
		if (now - lastFlushMs >= xpFlushSeconds * 1000L)
		{
			flushXp(now);
			lastFlushMs = now;
			due = true;
		}
		if (now - lastHeartbeatMs >= heartbeatSeconds * 1000L)
		{
			addHeartbeat(now);
			lastHeartbeatMs = now;
			due = true;
		}
		drainContainers(now, false);
		if (milestonesDirty)
		{
			addMilestones(now);
			milestonesDirty = false;
			due = true;
		}
		if ((due || urgent) && !pendingEvents.isEmpty())
		{
			sendBatch();
		}
	}

	// ---------------------------------------------------------------- quests, diaries, combat achievements

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (initTicks != 0 || sessionId == null)
		{
			return; // the login burst is covered by the seed snapshot
		}
		if (event.getVarbitId() == -1)
		{
			if (event.getVarpId() == VarPlayerID.QP)
			{
				milestonesDirty = true;
			}
			return;
		}
		if (isMilestoneVarbit(event.getVarbitId()))
		{
			milestonesDirty = true;
		}
	}

	private static boolean isMilestoneVarbit(int id)
	{
		for (int v : CA_TIER_VARBITS)
		{
			if (v == id)
			{
				return true;
			}
		}
		for (int[] region : DIARY_VARBITS)
		{
			for (int v : region)
			{
				if (v == id)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Quest points and list, completed combat-achievement tiers and every diary tier flag. Client thread only. */
	private void addMilestones(long now)
	{
		int questsDone = 0;
		int questsTotal = 0;
		for (Quest quest : Quest.values())
		{
			questsTotal++;
			if (quest.getState(client) == QuestState.FINISHED)
			{
				questsDone++;
			}
		}
		List<String> caTiers = new ArrayList<>();
		for (int i = 0; i < CA_TIER_VARBITS.length; i++)
		{
			if (client.getVarbitValue(CA_TIER_VARBITS[i]) >= 1)
			{
				caTiers.add(CA_TIERS[i]);
			}
		}
		List<Map<String, Object>> diaries = new ArrayList<>();
		for (int r = 0; r < DIARY_REGIONS.length; r++)
		{
			for (int t = 0; t < DIARY_TIERS.length; t++)
			{
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("region", DIARY_REGIONS[r]);
				row.put("tier", DIARY_TIERS[t]);
				row.put("complete", client.getVarbitValue(DIARY_VARBITS[r][t]) >= 1);
				diaries.add(row);
			}
		}
		pendingEvents.add(event("milestones", now,
			"questPoints", client.getVarpValue(VarPlayerID.QP),
			"questsDone", questsDone,
			"questsTotal", questsTotal,
			"caTiers", caTiers,
			"diaries", diaries));
	}

	// ---------------------------------------------------------------- skills

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (initTicks != 0 || sessionId == null)
		{
			return; // login/hop burst, or no session: the seed re-baselines everything
		}
		long now = System.currentTimeMillis();
		XpTracker.LevelUp up = tracker.record(event.getSkill(), event.getXp(), event.getLevel(), now);
		if (up != null)
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("skill", up.skill.name());
			data.put("level", up.level);
			data.put("xp", up.xp);
			pendingEvents.add(event("event", now,
				"eventType", "LEVEL_UP",
				"dedupeKey", "LEVEL_UP:" + up.skill.name() + ":" + up.level,
				"data", data));
			urgent = true;
		}
	}

	// ---------------------------------------------------------------- containers

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ContainerTracker.Kind kind = kindOf(event.getContainerId());
		if (kind == null || sessionId == null)
		{
			return;
		}
		offerContainer(kind, event.getItemContainer());
	}

	private ContainerTracker.Kind kindOf(int containerId)
	{
		switch (containerId)
		{
			case InventoryID.INV:
				return config.uploadInventory() ? ContainerTracker.Kind.INVENTORY : null;
			case InventoryID.WORN:
				return config.uploadInventory() ? ContainerTracker.Kind.EQUIPMENT : null;
			case InventoryID.BANK:
				return config.uploadBank() ? ContainerTracker.Kind.BANK : null;
			default:
				return null;
		}
	}

	/** Canonicalises ids (notes → item, placeholders dropped by quantity 0) and hands the snapshot to the tracker. */
	private void offerContainer(ContainerTracker.Kind kind, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] quantities = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			ids[i] = itemManager.canonicalize(item.getId());
			quantities[i] = item.getQuantity();
		}
		containers.offer(ContainerTracker.snapshot(kind, ids, quantities));
	}

	/** Emits pending container payloads that are due: inventory on its debounce, equipment quickly, bank while open or on close. */
	private void drainContainers(long now, boolean bankForce)
	{
		emitContainer(containers.take(ContainerTracker.Kind.INVENTORY, now, inventoryDebounceSeconds * 1000L, false), now);
		emitContainer(containers.take(ContainerTracker.Kind.EQUIPMENT, now, EQUIPMENT_DEBOUNCE_MS, false), now);
		if (bankOpen || bankForce)
		{
			emitContainer(containers.take(ContainerTracker.Kind.BANK, now, bankMaxEverySeconds * 1000L, bankForce), now);
		}
	}

	private void emitContainer(ContainerTracker.Snapshot snapshot, long now)
	{
		if (snapshot == null)
		{
			return;
		}
		pendingEvents.add(event("container", now,
			"container", snapshot.kind.name(),
			"hash", snapshot.hash,
			"items", snapshot.items));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
		}
		else if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			geOpen = true;
		}
		else if (event.getGroupId() == InterfaceID.COLLECTION || event.getGroupId() == InterfaceID.COLLECTION_OVERVIEW)
		{
			readCounts(event.getGroupId(), "COLLECTION_LOG");
		}
		else if (event.getGroupId() == InterfaceID.CA_OVERVIEW)
		{
			readCounts(event.getGroupId(), "COMBAT_ACHIEVEMENTS");
		}
	}

	// ---------------------------------------------------------------- loot, kill counts, overview totals

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		addLoot(npc.getName(), "NPC", npc.getCombatLevel(), event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		addLoot(event.getPlayer().getName(), "PLAYER", event.getPlayer().getCombatLevel(), event.getItems());
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC and player kills already arrive through the two events above; this adds chests, caskets and pickpockets.
		String type = event.getType() == null ? "UNKNOWN" : event.getType().name();
		if ("NPC".equals(type) || "PLAYER".equals(type))
		{
			return;
		}
		addLoot(event.getName(), type, event.getCombatLevel(), event.getItems());
	}

	private void addLoot(String source, String sourceType, int combatLevel, Collection<ItemStack> items)
	{
		if (initTicks != 0 || sessionId == null || !config.uploadLoot() || source == null || items == null || items.isEmpty())
		{
			return;
		}
		Map<Integer, Integer> byId = new LinkedHashMap<>();
		for (ItemStack stack : items)
		{
			int id = itemManager.canonicalize(stack.getId());
			byId.merge(id, stack.getQuantity(), Integer::sum);
		}
		List<int[]> rows = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : byId.entrySet())
		{
			rows.add(new int[]{e.getKey(), e.getValue()});
		}
		pendingEvents.add(event("loot", System.currentTimeMillis(),
			"source", Text.removeTags(source),
			"sourceType", sourceType,
			"combatLevel", Math.max(0, combatLevel),
			"items", rows));
		urgent = true;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (initTicks != 0 || sessionId == null || !config.uploadLoot())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		Matcher m = KILL_COUNT.matcher(Text.removeTags(event.getMessage()));
		if (m.find())
		{
			pendingEvents.add(event("kill_count", System.currentTimeMillis(),
				"source", m.group(1),
				"kind", m.group(2),
				"count", Integer.parseInt(m.group(3).replace(",", ""))));
		}
	}

	/** One tick after an overview opens, send the texts it shows ("734/1,590", "Points: 300/2,000") for the server to parse. */
	private void readCounts(int groupId, String name)
	{
		if (sessionId == null || !config.uploadLoot())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			List<String> texts = new ArrayList<>();
			for (int child = 0; child < 60 && texts.size() < 40; child++)
			{
				collectTexts(client.getWidget(groupId, child), texts, 0);
			}
			if (!texts.isEmpty())
			{
				pendingEvents.add(event("counts", System.currentTimeMillis(), "interface", name, "texts", texts));
				urgent = true;
			}
		});
	}

	private static void collectTexts(Widget w, List<String> out, int depth)
	{
		if (w == null || depth > 4 || out.size() >= 40)
		{
			return;
		}
		String t = w.getText();
		if (t != null)
		{
			t = Text.removeTags(t).trim();
			if (t.matches(".*\\d[\\d,]*\\s*/\\s*\\d[\\d,]*.*") && !out.contains(t))
			{
				out.add(t.length() > 120 ? t.substring(0, 120) : t);
			}
		}
		for (Widget[] group : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (group != null)
			{
				for (Widget c : group)
				{
					collectTexts(c, out, depth + 1);
				}
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
			if (sessionId != null)
			{
				drainContainers(System.currentTimeMillis(), true);
				urgent = true;
			}
		}
		else if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			geOpen = false;
		}
	}

	// ---------------------------------------------------------------- grand exchange

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// The login burst (EMPTY ×8, then the real offers) lands inside the init guard; seed() replays the slots.
		if (initTicks != 0 || sessionId == null || !config.uploadGe())
		{
			return;
		}
		handleOffer(event.getSlot(), event.getOffer(), System.currentTimeMillis());
	}

	private void handleOffer(int slot, GrandExchangeOffer offer, long now)
	{
		if (offer == null)
		{
			return;
		}
		Map<String, Object> fields = geOffers.onOffer(slot, offer);
		if (fields != null)
		{
			Map<String, Object> ev = event("ge_offer", now);
			ev.putAll(fields);
			pendingEvents.add(ev);
			urgent = true;
		}
	}

	// ---------------------------------------------------------------- deaths and combat

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (sessionId == null || local == null || event.getActor() != local)
		{
			return;
		}
		long now = System.currentTimeMillis();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("regionId", regionId(local));
		data.put("world", world);
		data.put("hardcore", accountType.startsWith("HARDCORE"));
		pendingEvents.add(event("event", now,
			"eventType", "DEATH",
			"dedupeKey", "DEATH:" + sessionId + ":" + seq,
			"data", data));
		urgent = true;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		Actor actor = event.getActor();
		Actor target = local.getInteracting();
		if (actor == local || (target != null && actor == target) || event.getHitsplat().isMine())
		{
			lastCombatMs = System.currentTimeMillis();
			Actor named = actor == local ? target : actor;
			if (named instanceof NPC && named.getName() != null)
			{
				combatTarget = named.getName();
			}
		}
	}

	// ---------------------------------------------------------------- lifecycle plumbing

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// Posted on the EDT while the client closes. The final batch is built from plugin state only.
		endSession("CLIENT_SHUTDOWN");
		event.waitFor(api.flush());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!XPTempoConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		api.configure(XPTempoApi.baseUrl(), config.apiKey());
		if (XPTempoConfig.KEY_PAIRING_CODE.equals(event.getKey()))
		{
			maybePair();
		}
		if (XPTempoConfig.KEY_ENABLED.equals(event.getKey()) || XPTempoConfig.KEY_API_KEY.equals(event.getKey()))
		{
			// Turning uploads on (or finishing pairing) mid-session: start a fresh session so the server gets the seed.
			clientThread.invoke(() ->
			{
				if (config.enabled() && api.isConfigured() && client.getGameState() == GameState.LOGGED_IN)
				{
					sessionId = null;
					initTicks = INIT_TICKS;
				}
				else if (!config.enabled())
				{
					endSession("PLUGIN_DISABLED");
				}
			});
		}
	}

	/** Runs on the client thread two ticks after login/hop, when XP, the display name and GE slots are reliable. */
	private void seed()
	{
		long hash = client.getAccountHash();
		Player local = client.getLocalPlayer();
		if (hash == -1L || local == null || local.getName() == null)
		{
			initTicks = 1; // try again next tick
			return;
		}
		EnumSet<WorldType> types = client.getWorldType();
		for (WorldType t : SKIPPED_WORLDS)
		{
			if (types.contains(t))
			{
				log.debug("XPTempo: not tracking on a {} world", t);
				endSession("LOGOUT");
				return;
			}
		}
		displayName = local.getName();
		world = client.getWorld();
		accountType = accountType(client.getVarbitValue(VarbitID.IRONMAN));
		long now = System.currentTimeMillis();
		if (hash != accountHash || sessionId == null)
		{
			endSession("LOGOUT"); // a stale session for another character, if any
			accountHash = hash;
			sessionId = UUID.randomUUID().toString();
			seq = 0;
			tracker.reset();
			containers.reset();
			pendingEvents.clear();
			pendingEvents.add(event("session_start", now, "world", world));
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Skill skill : Skill.values())
			{
				int xp = client.getSkillExperience(skill);
				int level = client.getRealSkillLevel(skill);
				tracker.seed(skill, xp, level);
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("skill", skill.name());
				row.put("xp", xp);
				row.put("level", level);
				row.put("boosted", client.getBoostedSkillLevel(skill));
				rows.add(row);
			}
			pendingEvents.add(event("skills", now, "skills", rows));
			addMilestones(now);
			lastFlushMs = now;
			lastHeartbeatMs = now;
			urgent = true;
			log.debug("XPTempo: session {} started for {} on world {}", sessionId, displayName, world);
		}
		else
		{
			// World hop or reconnect: same character, same session; re-baseline silently.
			for (Skill skill : Skill.values())
			{
				tracker.seed(skill, client.getSkillExperience(skill), client.getRealSkillLevel(skill));
			}
		}
		// Current holdings and offers (the events for these were skipped during the init guard).
		if (config.uploadInventory())
		{
			offerContainer(ContainerTracker.Kind.INVENTORY, client.getItemContainer(InventoryID.INV));
			offerContainer(ContainerTracker.Kind.EQUIPMENT, client.getItemContainer(InventoryID.WORN));
		}
		if (config.uploadGe())
		{
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null)
			{
				for (int slot = 0; slot < offers.length; slot++)
				{
					handleOffer(slot, offers[slot], now);
				}
			}
		}
	}

	private void flushXp(long now)
	{
		if (!tracker.hasPending())
		{
			return;
		}
		List<Map<String, Object>> deltas = new ArrayList<>();
		for (XpTracker.Delta d : tracker.drain())
		{
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("skill", d.skill.name());
			row.put("xpDelta", d.xpDelta);
			row.put("xp", d.xp);
			row.put("level", d.level);
			deltas.add(row);
		}
		pendingEvents.add(event("xp", now,
			"windowStart", iso(lastFlushMs),
			"windowEnd", iso(now),
			"deltas", deltas));
	}

	private void addHeartbeat(long now)
	{
		Map<String, Object> activity = new LinkedHashMap<>();
		Skill active = tracker.getActiveSkill();
		if (bankOpen)
		{
			activity.put("kind", "BANKING");
			activity.put("label", "Banking");
		}
		else if (geOpen)
		{
			activity.put("kind", "GRAND_EXCHANGE");
			activity.put("label", "At the Grand Exchange");
		}
		else if (now - lastCombatMs <= COMBAT_WINDOW_MS)
		{
			activity.put("kind", "COMBAT");
			activity.put("label", combatTarget != null ? "Fighting " + combatTarget : "In combat");
			if (active != null && now - tracker.getLastGainMs() <= ACTIVE_WINDOW_MS)
			{
				activity.put("skill", active.name());
			}
		}
		else if (active != null && now - tracker.getLastGainMs() <= ACTIVE_WINDOW_MS)
		{
			activity.put("kind", "SKILLING");
			activity.put("label", "Training " + active.getName());
			activity.put("skill", active.name());
		}
		else
		{
			activity.put("kind", "IDLE");
			activity.put("label", "Idle");
		}
		Player local = client.getLocalPlayer();
		if (local != null)
		{
			activity.put("regionId", regionId(local));
		}
		pendingEvents.add(event("heartbeat", now,
			"xpPerHour", tracker.xpPerHour(now),
			"activity", activity));
	}

	private static int regionId(Player local)
	{
		WorldPoint wp = local.getWorldLocation();
		return wp == null ? 0 : wp.getRegionID();
	}

	private synchronized void endSession(String reason)
	{
		if (sessionId == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		flushXp(now);
		pendingEvents.add(event("session_end", now, "reason", reason));
		sendBatch();
		sessionId = null;
		bankOpen = false;
		geOpen = false;
	}

	private synchronized void sendBatch()
	{
		urgent = false;
		if (pendingEvents.isEmpty())
		{
			return;
		}
		if (!config.enabled() || !api.isConfigured())
		{
			pendingEvents.clear(); // nothing leaves the client until the user opts in and pairs
			return;
		}
		Payloads.Batch batch = new Payloads.Batch();
		batch.batchId = UUID.randomUUID().toString();
		batch.sentAt = iso(System.currentTimeMillis());
		batch.plugin = new Payloads.PluginInfo();
		batch.plugin.version = VERSION;
		batch.plugin.runelite = RuneLiteProperties.getVersion();
		batch.account = new Payloads.AccountInfo();
		batch.account.hash = Long.toString(accountHash);
		batch.account.displayName = displayName;
		batch.account.accountType = accountType;
		batch.account.world = world > 0 ? world : null;
		batch.sessionId = sessionId;
		batch.events = new ArrayList<>(pendingEvents);
		pendingEvents.clear();
		api.submit(batch);
	}

	private void maybePair()
	{
		String code = config.pairingCode().trim();
		if (pairing || code.isEmpty() || !config.apiKey().trim().isEmpty())
		{
			return;
		}
		pairing = true;
		api.pair(code, "RuneLite on " + System.getProperty("os.name"), response ->
		{
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_API_KEY, response.apiKey);
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_PAIRING_CODE, "");
			pairing = false;
			chat("XPTempo: paired with " + response.userEmail + ". Turn on \"Enable XPTempo\" to start uploading.");
		}, error ->
		{
			pairing = false;
			chat("XPTempo: pairing failed (" + error + "). Get a fresh code from the app and try again.");
		});
	}

	private void chat(String message)
	{
		log.info(message);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
			}
		});
	}

	private Map<String, Object> event(String type, long atMs, Object... keyValues)
	{
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("seq", ++seq);
		ev.put("at", iso(atMs));
		ev.put("type", type);
		for (int i = 0; i + 1 < keyValues.length; i += 2)
		{
			ev.put((String) keyValues[i], keyValues[i + 1]);
		}
		return ev;
	}

	private static String iso(long epochMs)
	{
		return Instant.ofEpochMilli(epochMs).toString();
	}

	/** VarbitID.IRONMAN values; 4–6 follow the Dink plugin's mapping (not defined by RuneLite itself). */
	private static String accountType(int ironman)
	{
		switch (ironman)
		{
			case 0:
				return "NORMAL";
			case 1:
				return "IRONMAN";
			case 2:
				return "ULTIMATE_IRONMAN";
			case 3:
				return "HARDCORE_IRONMAN";
			case 4:
				return "GROUP_IRONMAN";
			case 5:
				return "HARDCORE_GROUP_IRONMAN";
			case 6:
				return "UNRANKED_GROUP_IRONMAN";
			default:
				return "UNKNOWN";
		}
	}
}
