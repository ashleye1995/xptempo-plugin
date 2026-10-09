package com.xpulse;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.xpulse.api.Payloads;
import com.xpulse.api.XPulseApi;
import com.xpulse.tracker.ContainerTracker;
import com.xpulse.tracker.GeOfferTracker;
import com.xpulse.tracker.XpTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
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
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;

/**
 * Sends the logged-in character's own data to the XPulse API: sessions, skills and XP deltas,
 * inventory/bank/equipment contents, GE offers, deaths and level-ups, plus an activity heartbeat.
 * Design notes: Vault/research/runelite-plugin.md.
 */
@Slf4j
@PluginDescriptor(
	name = "XPulse",
	description = "Companion plugin for the XPulse app: sends your own character's XP, wealth and activity to your XPulse account (opt-in)",
	tags = {"xp", "tracker", "stats", "companion", "progress"}
)
public class XPulsePlugin extends Plugin
{
	static final String VERSION = "0.2.0";
	/** StatChanged and GE bursts for two ticks after LOGGED_IN; XP and the display name are unreliable until then. */
	private static final int INIT_TICKS = 2;
	private static final long ACTIVE_WINDOW_MS = 2 * 60_000L;
	private static final long COMBAT_WINDOW_MS = 30_000L;
	private static final long EQUIPMENT_DEBOUNCE_MS = 5_000L;
	private static final EnumSet<WorldType> SKIPPED_WORLDS = EnumSet.of(
		WorldType.DEADMAN, WorldType.SEASONAL, WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private XPulseConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private XPulseApi api;
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
	XPulseConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(XPulseConfig.class);
	}

	@Override
	protected void startUp()
	{
		geOffers = new GeOfferTracker(new GeOfferTracker.Store()
		{
			@Override
			public GeOfferTracker.SavedOffer load(int slot)
			{
				String json = configManager.getRSProfileConfiguration(XPulseConfig.GROUP, "geoffer." + slot);
				return json == null || json.isEmpty() ? null : gson.fromJson(json, GeOfferTracker.SavedOffer.class);
			}

			@Override
			public void save(int slot, GeOfferTracker.SavedOffer offer)
			{
				configManager.setRSProfileConfiguration(XPulseConfig.GROUP, "geoffer." + slot, gson.toJson(offer));
			}

			@Override
			public void clear(int slot)
			{
				configManager.unsetRSProfileConfiguration(XPulseConfig.GROUP, "geoffer." + slot);
			}
		}, () -> UUID.randomUUID().toString());

		api = new XPulseApi(okHttpClient, gson, executor, "XPulse/" + VERSION, new XPulseApi.Listener()
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
						log.warn("XPulse: event {} rejected: {} {}", r.seq, r.code, r.message);
					}
				}
				log.debug("XPulse: batch {} accepted={} duplicates={}", ack.batchId, ack.accepted, ack.duplicates);
			}

			@Override
			public void onUnauthorized()
			{
				chat("XPulse: your API key was rejected. Open the plugin settings and pair again.");
			}

			@Override
			public void onFailure(String message, boolean willRetry)
			{
				if (willRetry)
				{
					log.debug("XPulse: {}", message);
				}
				else
				{
					log.warn("XPulse: {}", message);
				}
			}
		});
		api.configure(config.apiBaseUrl(), config.apiKey());
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
		if ((due || urgent) && !pendingEvents.isEmpty())
		{
			sendBatch();
		}
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
		if (!XPulseConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		api.configure(config.apiBaseUrl(), config.apiKey());
		if (XPulseConfig.KEY_PAIRING_CODE.equals(event.getKey()))
		{
			maybePair();
		}
		if (XPulseConfig.KEY_ENABLED.equals(event.getKey()) || XPulseConfig.KEY_API_KEY.equals(event.getKey()))
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
				log.debug("XPulse: not tracking on a {} world", t);
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
			lastFlushMs = now;
			lastHeartbeatMs = now;
			urgent = true;
			log.debug("XPulse: session {} started for {} on world {}", sessionId, displayName, world);
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
			configManager.setConfiguration(XPulseConfig.GROUP, XPulseConfig.KEY_API_KEY, response.apiKey);
			configManager.setConfiguration(XPulseConfig.GROUP, XPulseConfig.KEY_PAIRING_CODE, "");
			pairing = false;
			chat("XPulse: paired with " + response.userEmail + ". Enable \"Send data to XPulse\" to start uploading.");
		}, error ->
		{
			pairing = false;
			chat("XPulse: pairing failed (" + error + "). Get a fresh code from the app and try again.");
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
