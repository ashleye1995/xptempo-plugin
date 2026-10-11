package com.xptempo;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.xptempo.api.Payloads;
import com.xptempo.api.XPTempoApi;
import com.xptempo.tracker.CollectionLogReader;
import com.xptempo.tracker.ContainerTracker;
import com.xptempo.tracker.GeOfferTracker;
import com.xptempo.tracker.SyncGuide;
import com.xptempo.ui.SyncGuideOverlay;
import com.xptempo.ui.SyncGuideSceneOverlay;
import com.xptempo.ui.SyncGuidePanel;
import com.xptempo.tracker.XpTracker;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
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
import net.runelite.client.config.RuneScapeProfileType;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

/**
 * Sends the logged-in character's own data to the XPTempo API: sessions, skills and XP deltas,
 * inventory/bank/equipment contents, GE offers, drops, deaths and level-ups, quest/diary/combat
 * achievement progress, plus an activity heartbeat. Read-only: it never changes anything in the game.
 * Nothing is sent until the player switches on "Enable XPTempo" and pairs with a code from the app.
 */
@Slf4j
@PluginDescriptor(
	name = "XPTempo",
	description = "Companion plugin for the XPTempo app: sends your own character's XP, wealth and activity to your XPTempo account (opt-in)",
	tags = {"xp", "tracker", "stats", "companion", "progress"}
)
public class XPTempoPlugin extends Plugin
{
	static final String VERSION = "0.7.0";
	/** "Your Vorkath kill count is: 178." and the chest / completion / harvest / lap variants. */
	/** The collection log's per-category entry lists, to tell which tab a page belongs to. */
	private static final int[] CLOG_LISTS = {
		InterfaceID.Collection.BOSS_TEXT, InterfaceID.Collection.RAID_TEXT, InterfaceID.Collection.CLUE_TEXT,
		InterfaceID.Collection.MINIGAME_TEXT, InterfaceID.Collection.OTHER_TEXT,
	};
	private static final String[] CLOG_CATEGORIES = {"BOSSES", "RAIDS", "CLUES", "MINIGAMES", "OTHER"};
	private static final int[] CLOG_TABS = {
		InterfaceID.Collection.BOSS_TAB, InterfaceID.Collection.RAID_TAB, InterfaceID.Collection.CLUE_TAB,
		InterfaceID.Collection.MINIGAME_TAB, InterfaceID.Collection.OTHER_TAB,
	};
	private static final int[] CLOG_CONTAINERS = {
		InterfaceID.Collection.BOSS_CONTAINER, InterfaceID.Collection.RAID_CONTAINER, InterfaceID.Collection.CLUE_CONTAINER,
		InterfaceID.Collection.MINIGAME_CONTAINER, InterfaceID.Collection.OTHER_CONTAINER,
	};
	private final CollectionLogReader clog = new CollectionLogReader();
	private static final Pattern KILL_COUNT = Pattern.compile("^Your (.+?) (kill|completion|chest|harvest|success|lap|rumour|catch) count is:? ([\\d,]+)");
	/** StatChanged and GE bursts for two ticks after LOGGED_IN; XP and the display name are unreliable until then. */
	private static final int INIT_TICKS = 2;
	private static final long ACTIVE_WINDOW_MS = 2 * 60_000L;
	private static final long COMBAT_WINDOW_MS = 30_000L;
	private static final long EQUIPMENT_DEBOUNCE_MS = 5_000L;
	private static final EnumSet<WorldType> SKIPPED_WORLDS = EnumSet.of(
		WorldType.DEADMAN, WorldType.SEASONAL, WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD);
	/** Achievement diary regions in the API's order; the tables below follow it, tiers Easy→Elite. */
	private static final String[] DIARY_REGIONS = {
		"ARDOUGNE", "DESERT", "FALADOR", "FREMENNIK", "KANDARIN", "KARAMJA", "KOUREND", "LUMBRIDGE", "MORYTANIA", "VARROCK", "WESTERN", "WILDERNESS"};
	private static final String[] DIARY_TIERS = {"EASY", "MEDIUM", "HARD", "ELITE"};
	/** Tier complete flags (every task done). Karamja Easy–Hard use ATJUN_*_DONE, which is 1 when started and 2 when complete. */
	private static final int[][] DIARY_COMPLETE_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};
	/** Tasks done per tier: the counts the in-game Achievement Diary tab shows. */
	private static final int[][] DIARY_COUNT_VARBITS = {
		{VarbitID.ARDOUGNE_EASY_COUNT, VarbitID.ARDOUGNE_MED_COUNT, VarbitID.ARDOUGNE_HARD_COUNT, VarbitID.ARDOUGNE_ELITE_COUNT},
		{VarbitID.DESERT_EASY_COUNT, VarbitID.DESERT_MED_COUNT, VarbitID.DESERT_HARD_COUNT, VarbitID.DESERT_ELITE_COUNT},
		{VarbitID.FALADOR_EASY_COUNT, VarbitID.FALADOR_MED_COUNT, VarbitID.FALADOR_HARD_COUNT, VarbitID.FALADOR_ELITE_COUNT},
		{VarbitID.FREMENNIK_EASY_COUNT, VarbitID.FREMENNIK_MED_COUNT, VarbitID.FREMENNIK_HARD_COUNT, VarbitID.FREMENNIK_ELITE_COUNT},
		{VarbitID.KANDARIN_EASY_COUNT, VarbitID.KANDARIN_MED_COUNT, VarbitID.KANDARIN_HARD_COUNT, VarbitID.KANDARIN_ELITE_COUNT},
		{VarbitID.KARAMJA_EASY_COUNT, VarbitID.KARAMJA_MED_COUNT, VarbitID.KARAMJA_HARD_COUNT, VarbitID.KARAMJA_ELITE_COUNT},
		{VarbitID.KOUREND_EASY_COUNT, VarbitID.KOUREND_MED_COUNT, VarbitID.KOUREND_HARD_COUNT, VarbitID.KOUREND_ELITE_COUNT},
		{VarbitID.LUMBRIDGE_EASY_COUNT, VarbitID.LUMBRIDGE_MED_COUNT, VarbitID.LUMBRIDGE_HARD_COUNT, VarbitID.LUMBRIDGE_ELITE_COUNT},
		{VarbitID.MORYTANIA_EASY_COUNT, VarbitID.MORYTANIA_MED_COUNT, VarbitID.MORYTANIA_HARD_COUNT, VarbitID.MORYTANIA_ELITE_COUNT},
		{VarbitID.VARROCK_EASY_COUNT, VarbitID.VARROCK_MED_COUNT, VarbitID.VARROCK_HARD_COUNT, VarbitID.VARROCK_ELITE_COUNT},
		{VarbitID.WESTERN_EASY_COUNT, VarbitID.WESTERN_MED_COUNT, VarbitID.WESTERN_HARD_COUNT, VarbitID.WESTERN_ELITE_COUNT},
		{VarbitID.WILDERNESS_EASY_COUNT, VarbitID.WILDERNESS_MED_COUNT, VarbitID.WILDERNESS_HARD_COUNT, VarbitID.WILDERNESS_ELITE_COUNT},
	};
	private static final String[] CA_TIERS = {"EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER"};
	private static final int[] CA_TIER_VARBITS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_TIER_STATUS_HARD,
		VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER};

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

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ModelOutlineRenderer modelOutlineRenderer;

	/** Bank booths, bank chests and bankers in the loaded scene, outlined during the guide's bank step. */
	private final Set<TileObject> bankObjects = new HashSet<>();
	private final Set<NPC> bankers = new HashSet<>();
	private SyncGuideSceneOverlay guideSceneOverlay;

	/** The "Get synced" walkthrough: progress per RuneScape profile, a side-bar checklist and an in-game outline. */
	private SyncGuide guide = new SyncGuide(null);
	private SyncGuidePanel guidePanel;
	private NavigationButton guideButton;
	private SyncGuideOverlay guideOverlay;

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
	/** Something from this session actually went to the server (so a session_end is owed when uploads stop). */
	private boolean sessionUploaded;
	/** Characters the player unlinked in the app (as the server reports them): nothing more is sent for them this run. */
	private final java.util.Set<Long> unlinkedHashes = ConcurrentHashMap.newKeySet();
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
			public void onAck(Payloads.Batch sent, Payloads.Ack ack)
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
				// The notice belongs to the character in the acknowledged batch, not whoever is logged in now.
				long hash = parseHash(sent);
				if ("account_unlinked".equals(ack.notice))
				{
					if (hash != -1L && unlinkedHashes.add(hash))
					{
						chat("XPTempo: a character on this profile was unlinked in the XPTempo app, so it is no longer synced. To track it again, clear the API key in the XPTempo settings and paste a new pairing code.");
					}
				}
				else if (sent.sessionId != null)
				{
					// Only now has this session reached the server (so a session_end is owed if uploads stop).
					clientThread.invoke(() ->
					{
						if (sent.sessionId.equals(sessionId))
						{
							sessionUploaded = true;
						}
					});
				}
			}

			@Override
			public void onUnauthorized()
			{
				// A rejected key is useless and would block the next pairing attempt, so forget it here.
				configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_API_KEY, "");
				chat("XPTempo: the server rejected this client's key, so it was cleared. Get a pairing code from xptempo.com and paste it into the plugin settings.");
				maybePair(); // a code that was waiting can be used now
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

		guidePanel = new SyncGuidePanel(() -> configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_SHOW_GUIDE, false));
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "guide_icon.png");
		guideButton = NavigationButton.builder().tooltip("XPTempo: get synced").icon(icon).priority(9).panel(guidePanel).build();
		guideOverlay = new SyncGuideOverlay(this::guideTarget);
		overlayManager.add(guideOverlay);
		guideSceneOverlay = new SyncGuideSceneOverlay(modelOutlineRenderer,
			() -> guideVisible() && sessionId != null && guide.current(config.uploadBank(), config.uploadLoot()) == SyncGuide.Step.BANK,
			() -> bankObjects, () -> bankers);
		overlayManager.add(guideSceneOverlay);
		updateGuideButton();
		refreshGuide();
	}

	@Override
	protected void shutDown()
	{
		// Turning the plugin off is an opt-out: drop what is not uploaded yet, send only the session_end, then stop
		// this instance's retries for good (startUp makes a new one).
		XPTempoApi current = api;
		clientThread.invoke(() ->
		{
			stopUploads();
			current.flush().whenComplete((v, e) -> current.close());
		});
		initTicks = -1;
		overlayManager.remove(guideOverlay);
		overlayManager.remove(guideSceneOverlay);
		bankObjects.clear();
		bankers.clear();
		clientToolbar.removeNavigation(guideButton);
	}

	// ---------------------------------------------------------------- sync guide

	private boolean guideVisible()
	{
		return config.showSyncGuide() && config.enabled() && !config.apiKey().trim().isEmpty();
	}

	/** The side-bar button is there while the guide is switched on and the plugin is paired. */
	private void updateGuideButton()
	{
		if (guideVisible())
		{
			clientToolbar.addNavigation(guideButton);
		}
		else
		{
			clientToolbar.removeNavigation(guideButton);
		}
	}

	private void refreshGuide()
	{
		if (guidePanel != null)
		{
			guidePanel.show(guide.steps(config.uploadBank(), config.uploadLoot()), guide.current(config.uploadBank(), config.uploadLoot()));
		}
	}

	private void loadGuide()
	{
		String json = configManager.getRSProfileConfiguration(XPTempoConfig.GROUP, "syncguide");
		SyncGuide.State state = null;
		try
		{
			state = json == null || json.isEmpty() ? null : gson.fromJson(json, SyncGuide.State.class);
		}
		catch (RuntimeException e)
		{
			log.debug("XPTempo: sync guide state unreadable, starting over", e);
		}
		guide = new SyncGuide(state);
		refreshGuide();
	}

	private void guideChanged()
	{
		configManager.setRSProfileConfiguration(XPTempoConfig.GROUP, "syncguide", gson.toJson(guide.state()));
		refreshGuide();
	}

	private static boolean visible(Widget w)
	{
		return w != null && !w.isHidden() && w.getBounds() != null && w.getBounds().width > 0;
	}

	// Bank booths and chests have a "Bank" (booths) or "Use" (chests) option; bankers a "Bank" option.
	private boolean isBankObject(int id)
	{
		ObjectComposition def = client.getObjectDefinition(id);
		if (def != null && def.getImpostorIds() != null && def.getImpostor() != null)
		{
			def = def.getImpostor();
		}
		if (def == null || def.getActions() == null)
		{
			return false;
		}
		String name = def.getName() == null ? "" : def.getName().toLowerCase();
		for (String a : def.getActions())
		{
			if ("Bank".equalsIgnoreCase(a) || ("Use".equalsIgnoreCase(a) && name.contains("bank")))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isBanker(NPC npc)
	{
		NPCComposition def = npc.getTransformedComposition();
		if (def == null || def.getActions() == null)
		{
			return false;
		}
		for (String a : def.getActions())
		{
			if ("Bank".equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		if (isBankObject(e.getGameObject().getId()))
		{
			bankObjects.add(e.getGameObject());
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		bankObjects.remove(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		if (isBankObject(e.getWallObject().getId()))
		{
			bankObjects.add(e.getWallObject());
		}
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		bankObjects.remove(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
	{
		if (isBankObject(e.getDecorativeObject().getId()))
		{
			bankObjects.add(e.getDecorativeObject());
		}
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
	{
		bankObjects.remove(e.getDecorativeObject());
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (isBanker(e.getNpc()))
		{
			bankers.add(e.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		bankers.remove(e.getNpc());
	}

	/** What to outline right now (called by the overlay on the client thread); null when there is nothing to point at. */
	private Rectangle guideTarget()
	{
		if (!guideVisible() || sessionId == null)
		{
			return null;
		}
		SyncGuide.Step step = guide.current(config.uploadBank(), config.uploadLoot());
		if (step == SyncGuide.Step.COMBAT_ACHIEVEMENTS)
		{
			return pathToSummaryRow(new String[]{"Tasks", "Bosses", "Rewards"}, "Combat tasks");
		}
		if (step != SyncGuide.Step.COLLECTION_LOG)
		{
			return null; // the bank has no button to point at: the checklist says what to do
		}
		if (!visible(client.getWidget(InterfaceID.Collection.FRAME)))
		{
			return pathToSummaryRow(new String[]{"Collection Log"}, "Collections logged");
		}
		String next = guide.nextCategory();
		int active = activeClogTab();
		int wanted = next == null ? -1 : SyncGuide.CATEGORIES.indexOf(next);
		if (wanted >= 0 && wanted != active)
		{
			Widget tab = client.getWidget(CLOG_TABS[wanted]);
			return visible(tab) ? tab.getBounds() : null;
		}
		String page = guide.nextPage();
		Widget list = active < 0 ? null : client.getWidget(CLOG_LISTS[active]);
		if (page == null || list == null || list.getDynamicChildren() == null)
		{
			return null;
		}
		Widget container = client.getWidget(CLOG_CONTAINERS[active]);
		for (Widget entry : list.getDynamicChildren())
		{
			if (entry != null && entry.getText() != null && Text.removeTags(entry.getText()).trim().equalsIgnoreCase(page))
			{
				Rectangle r = entry.getBounds();
				// Only while it is scrolled into view; otherwise the checklist names the page.
				return container != null && container.getBounds() != null && container.getBounds().intersects(r) ? r : null;
			}
		}
		return null;
	}

	/**
	 * Journal side tab, then its Character Summary sub-tab, then the row itself. Each summary row has a clickable
	 * box in the panel's click layer, recognised by its menu options (2026-10-11: combat tasks = Overview / Bosses /
	 * Tasks / Rewards, collection log = Collection Log / Collection Overview); outlining that box gives exactly one
	 * row. The row's label ("Combat tasks completed:", "Collections logged:") is the fallback.
	 */
	private Rectangle pathToSummaryRow(String[] actions, String label)
	{
		Widget summary = client.getWidget(InterfaceID.AccountSummarySidepanel.SUMMARY_CONTENTS);
		Widget clickLayer = client.getWidget(InterfaceID.AccountSummarySidepanel.SUMMARY_CLICK_LAYER);
		boolean summaryShown = (summary != null && !summary.isHidden()) || (clickLayer != null && !clickLayer.isHidden());
		if (summaryShown)
		{
			Widget row = rowWithAction(clickLayer, actions);
			if (row != null)
			{
				return row.getBounds();
			}
			Widget text = findText(summary, label, 0);
			if (text != null)
			{
				return text.getBounds();
			}
			logSummaryOnce(label);
			return null;
		}
		if (visible(client.getWidget(InterfaceID.SideJournal.UNIVERSE)))
		{
			Widget tab = client.getWidget(InterfaceID.SideJournal.TAB_ICON_1);
			return visible(tab) ? tab.getBounds() : null;
		}
		for (int stone : new int[]{InterfaceID.ToplevelOsrsStretch.STONE2, InterfaceID.ToplevelPreEoc.STONE2, InterfaceID.Toplevel.STONE2})
		{
			Widget w = client.getWidget(stone);
			if (visible(w))
			{
				return w.getBounds();
			}
		}
		return null;
	}

	/** The click-layer row whose menu options include one of these, exactly (case-insensitive). */
	private static Widget rowWithAction(Widget layer, String[] wanted)
	{
		if (layer == null)
		{
			return null;
		}
		for (Widget[] group : new Widget[][]{layer.getDynamicChildren(), layer.getStaticChildren()})
		{
			for (Widget w : group == null ? new Widget[0] : group)
			{
				String[] actions = w == null || w.isHidden() ? null : w.getActions();
				for (String a : actions == null ? new String[0] : actions)
				{
					for (String want : wanted)
					{
						if (a != null && Text.removeTags(a).trim().equalsIgnoreCase(want) && w.getBounds() != null && w.getBounds().height > 0)
						{
							return w;
						}
					}
				}
			}
		}
		return null;
	}

	/** Lower case, line breaks as spaces, tags removed, spaces collapsed: "Combat<br>Achievements" matches. */
	private static String plain(String t)
	{
		return t == null ? "" : Text.removeTags(t.replaceAll("(?i)<br>", " ")).replaceAll("\\s+", " ").trim().toLowerCase();
	}

	private static Widget findText(Widget w, String text, int depth)
	{
		if (w == null || depth > 5 || w.isHidden())
		{
			return null;
		}
		String want = text.toLowerCase();
		boolean match = plain(w.getText()).contains(want) || plain(w.getName()).contains(want);
		String[] actions = w.getActions();
		for (int i = 0; !match && actions != null && i < actions.length; i++)
		{
			match = plain(actions[i]).contains(want);
		}
		if (match && w.getBounds() != null && w.getBounds().height > 0)
		{
			return w;
		}
		for (Widget[] group : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
		{
			for (Widget c : group == null ? new Widget[0] : group)
			{
				Widget hit = findText(c, text, depth + 1);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}

	private long summaryLoggedAt;

	/** When a Character Summary row can't be found, write what the panel shows to the client log (once a minute). */
	private void logSummaryOnce(String row)
	{
		long now = System.currentTimeMillis();
		if (now - summaryLoggedAt < 60_000)
		{
			return;
		}
		summaryLoggedAt = now;
		List<String> seen = new ArrayList<>();
		describe(client.getWidget(InterfaceID.AccountSummarySidepanel.UNIVERSE), seen, 0);
		log.debug("XPTempo: guide could not find \"{}\" in Character Summary: {}", row, seen);
	}

	private static void describe(Widget w, List<String> out, int depth)
	{
		if (w == null || depth > 5 || out.size() > 60)
		{
			return;
		}
		String t = plain(w.getText());
		String[] a = w.getActions();
		if (!t.isEmpty() || (a != null && a.length > 0))
		{
			out.add(w.getId() + (w.getIndex() >= 0 ? "[" + w.getIndex() + "]" : "") + (t.isEmpty() ? "" : " text=" + t)
				+ (a == null ? "" : " actions=" + java.util.Arrays.toString(a)) + (w.isHidden() ? " hidden" : ""));
		}
		for (Widget[] group : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
		{
			for (Widget c : group == null ? new Widget[0] : group)
			{
				describe(c, out, depth + 1);
			}
		}
	}

	/** Which collection log tab is showing (its entry list is visible), or -1. */
	private int activeClogTab()
	{
		for (int i = 0; i < CLOG_CONTAINERS.length; i++)
		{
			if (visible(client.getWidget(CLOG_CONTAINERS[i])))
			{
				return i;
			}
		}
		return -1;
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
				bankObjects.clear();
				bankers.clear();
				initTicks = INIT_TICKS;
				bankOpen = false;
				geOpen = false;
				break;
			case LOGIN_SCREEN:
				bankObjects.clear();
				bankers.clear();
				endSession("LOGOUT");
				initTicks = -1;
				break;
			case LOADING:
				// A new area is loading: its objects spawn again afterwards (NPCs carry over and despawn themselves).
				bankObjects.clear();
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
		int varp = event.getVarpId();
		if (varp == VarPlayerID.QP || DiaryVarps.isTracked(varp)
			|| (event.getVarbitId() != -1 && isCaTierVarbit(event.getVarbitId())))
		{
			milestonesDirty = true; // several changes in one tick still make one snapshot
		}
	}

	private static boolean isCaTierVarbit(int id)
	{
		for (int v : CA_TIER_VARBITS)
		{
			if (v == id)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Quest points and list, completed combat-achievement tiers, every diary tier's flag and task count, and the
	 * raw diary varps the server decodes into individual tasks. Client thread only.
	 */
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
				int karamjaStaged = "KARAMJA".equals(DIARY_REGIONS[r]) && t < 3 ? 2 : 1;
				row.put("complete", client.getVarbitValue(DIARY_COMPLETE_VARBITS[r][t]) >= karamjaStaged);
				row.put("tasksDone", client.getVarbitValue(DIARY_COUNT_VARBITS[r][t]));
				diaries.add(row);
			}
		}
		// Raw signed values: the server decodes which task each bit belongs to, so a mapping fix never needs a plugin update.
		List<int[]> diaryVarps = new ArrayList<>();
		for (int varp : DiaryVarps.VARPS)
		{
			diaryVarps.add(new int[]{varp, client.getVarpValue(varp)});
		}
		pendingEvents.add(event("milestones", now,
			"questPoints", client.getVarpValue(VarPlayerID.QP),
			"questsDone", questsDone,
			"questsTotal", questsTotal,
			"caTiers", caTiers,
			"diaries", diaries,
			"diaryVarps", diaryVarps));
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
		if (kind == null || initTicks != 0 || sessionId == null)
		{
			return; // during the login/hop window seed() re-reads the containers (or skips the world entirely)
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
			if (config.uploadBank() && guide.bankOpened())
			{
				guideChanged();
			}
		}
		else if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			geOpen = true;
		}
		else if (event.getGroupId() == InterfaceID.COLLECTION_OVERVIEW)
		{
			readCounts("COLLECTION_LOG", InterfaceID.CollectionOverview.INFINITY, InterfaceID.CollectionOverview.UNIVERSE, InterfaceID.CollectionOverview.CONTENT);
		}
		else if (event.getGroupId() == InterfaceID.CA_OVERVIEW)
		{
			if (config.uploadLoot() && guide.combatAchievementsOpened())
			{
				guideChanged();
			}
			readCounts("COMBAT_ACHIEVEMENTS", InterfaceID.CaOverview.INFINITY, InterfaceID.CaOverview.UNIVERSE, InterfaceID.CaOverview.CA_GENERAL_CONTENT);
		}
	}

	// ---------------------------------------------------------------- collection log

	/** The game redraws the item list each time a collection log page is shown: read the page then. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
		{
			clientThread.invokeLater(this::readCollectionLogPage);
		}
	}

	private void readCollectionLogPage()
	{
		if (sessionId == null || !config.uploadLoot())
		{
			return;
		}
		// The header's lines and the item grid live in child widgets whose exact parent differs between game
		// versions, so look in both candidates (gameval HEADER / HEADER_TEXT, ITEMS / ITEMS_CONTENTS).
		List<String> headerLines = allTexts(client.getWidget(InterfaceID.Collection.HEADER_TEXT));
		if (headerLines.isEmpty())
		{
			headerLines = allTexts(client.getWidget(InterfaceID.Collection.HEADER));
		}
		CollectionLogReader.Header h = CollectionLogReader.parseHeader(headerLines);
		List<int[]> items = itemSlots(client.getWidget(InterfaceID.Collection.ITEMS));
		if (items.isEmpty())
		{
			items = itemSlots(client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS));
		}
		if (h == null)
		{
			return;
		}
		// Guide progress: this page, and the list of pages in the tab that is showing.
		boolean guideMoved = guide.pageSeen(h.title);
		int active = activeClogTab();
		if (active >= 0)
		{
			Widget list = client.getWidget(CLOG_LISTS[active]);
			guideMoved |= guide.listSeen(CLOG_CATEGORIES[active], texts(list == null ? null : list.getDynamicChildren()));
		}
		if (guideMoved)
		{
			guideChanged();
		}
		Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
		String logged = frame == null ? null : CollectionLogReader.parseLogged(texts(frame.getDynamicChildren()));
		Map<String, Object> fields = clog.page(h, clogCategory(h.title), items, logged);
		if (fields != null)
		{
			Map<String, Object> ev = event("clog_page", System.currentTimeMillis());
			ev.putAll(fields);
			pendingEvents.add(ev);
		}
	}

	/** Item slots under a widget (its dynamic children, or theirs one level down): [item id, quantity, 1 obtained / 0]. */
	private static List<int[]> itemSlots(Widget parent)
	{
		List<int[]> items = new ArrayList<>();
		if (parent == null)
		{
			return items;
		}
		Widget[] slots = parent.getDynamicChildren();
		for (Widget w : slots == null ? new Widget[0] : slots)
		{
			if (w != null && w.getItemId() > 0)
			{
				// Missing items are drawn faded; obtained ones at full opacity.
				items.add(new int[]{w.getItemId(), Math.max(0, w.getItemQuantity()), w.getOpacity() == 0 ? 1 : 0});
			}
		}
		return items;
	}

	/** Every non-empty text under a widget, two levels deep, in drawing order. */
	private static List<String> allTexts(Widget root)
	{
		List<String> out = new ArrayList<>();
		addTexts(root, out, 0);
		return out;
	}

	private static void addTexts(Widget w, List<String> out, int depth)
	{
		if (w == null || depth > 2 || out.size() >= 20)
		{
			return;
		}
		if (w.getText() != null && !w.getText().trim().isEmpty())
		{
			out.add(Text.removeTags(w.getText()).trim());
		}
		for (Widget[] group : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
		{
			for (Widget c : group == null ? new Widget[0] : group)
			{
				addTexts(c, out, depth + 1);
			}
		}
	}

	/** Which tab's entry list holds this page name; null when none does (the server then leaves it uncategorised). */
	private String clogCategory(String title)
	{
		for (int i = 0; i < CLOG_LISTS.length; i++)
		{
			Widget list = client.getWidget(CLOG_LISTS[i]);
			for (String t : texts(list == null ? null : list.getDynamicChildren()))
			{
				if (t.equalsIgnoreCase(title))
				{
					return CLOG_CATEGORIES[i];
				}
			}
		}
		return null;
	}

	private static List<String> texts(Widget[] widgets)
	{
		List<String> out = new ArrayList<>();
		if (widgets != null)
		{
			for (Widget w : widgets)
			{
				if (w != null && w.getText() != null && !w.getText().isEmpty())
				{
					out.add(Text.removeTags(w.getText()).trim());
				}
			}
		}
		return out;
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
		// Nothing about other players leaves the client: PvP loot is sent without the victim's name or level.
		addLoot("Player kill", "PLAYER", 0, event.getItems());
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
		// Not urgent: drops ride the next regular upload (at most a minute), so fast kills or pickpocketing
		// never turn into a request every few seconds.
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
		String plain = Text.removeTags(event.getMessage());
		String newItem = CollectionLogReader.parseNewItem(plain);
		if (newItem != null)
		{
			pendingEvents.add(event("clog_new", System.currentTimeMillis(), "itemName", newItem));
			return;
		}
		Matcher m = KILL_COUNT.matcher(plain);
		if (m.find())
		{
			pendingEvents.add(event("kill_count", System.currentTimeMillis(),
				"source", m.group(1),
				"kind", m.group(2),
				"count", Integer.parseInt(m.group(3).replace(",", ""))));
		}
	}

	/**
	 * One tick after an overview opens, send the texts it shows ("734/1,590", "Points: 300/2,000") for the server to
	 * parse, walking down from the interface's root components (gameval component ids).
	 */
	private void readCounts(String name, int... roots)
	{
		if (sessionId == null || !config.uploadLoot())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			List<String> texts = new ArrayList<>();
			for (int root : roots)
			{
				collectTexts(client.getWidget(root), texts, 0);
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
		if (w == null || depth > 7 || out.size() >= 40)
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
		// While uploads are off the tracker must not move on, or offers that fill meanwhile would never be reported.
		if (initTicks != 0 || sessionId == null || !config.uploadGe() || !uploading())
		{
			return;
		}
		handleOffer(event.getSlot(), event.getOffer(), System.currentTimeMillis(), false);
	}

	private void handleOffer(int slot, GrandExchangeOffer offer, long now, boolean force)
	{
		if (offer == null)
		{
			return;
		}
		Map<String, Object> fields = geOffers.onOffer(slot, offer, force);
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
		// Posted on the EDT while the client closes: build the final batch on the client thread (like every
		// other batch), then let the client wait for the upload, which never waits through a retry window.
		XPTempoApi current = api;
		CompletableFuture<Void> done = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			endSession("CLIENT_SHUTDOWN");
			current.flush().whenComplete((v, e) -> done.complete(null));
		});
		event.waitFor(done);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!XPTempoConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		api.configure(XPTempoApi.baseUrl(), config.apiKey());
		if (XPTempoConfig.KEY_API_KEY.equals(event.getKey()))
		{
			unlinkedHashes.clear(); // a new pairing may link an unlinked character back
		}
		if (XPTempoConfig.KEY_PAIRING_CODE.equals(event.getKey()))
		{
			maybePair();
		}
		if (XPTempoConfig.KEY_ENABLED.equals(event.getKey()) && config.enabled())
		{
			maybePair(); // a code pasted before switching on is used now
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
					stopUploads();
				}
			});
		}
		dropSwitchedOffCategory(event.getKey());
		updateGuideButton();
		refreshGuide();
	}

	/** A category switch was turned off: what is waiting for it is dropped, not uploaded. */
	private void dropSwitchedOffCategory(String key)
	{
		List<String> types = new ArrayList<>();
		List<ContainerTracker.Kind> kinds = new ArrayList<>();
		if ("uploadInventory".equals(key) && !config.uploadInventory())
		{
			kinds.add(ContainerTracker.Kind.INVENTORY);
			kinds.add(ContainerTracker.Kind.EQUIPMENT);
		}
		else if ("uploadBank".equals(key) && !config.uploadBank())
		{
			kinds.add(ContainerTracker.Kind.BANK);
		}
		else if ("uploadGe".equals(key) && !config.uploadGe())
		{
			types.add("ge_offer");
		}
		else if ("uploadLoot".equals(key) && !config.uploadLoot())
		{
			types.add("loot");
			types.add("kill_count");
			types.add("counts");
			types.add("clog_page");
			types.add("clog_new");
		}
		if (types.isEmpty() && kinds.isEmpty())
		{
			return;
		}
		List<String> kindNames = new ArrayList<>();
		for (ContainerTracker.Kind k : kinds)
		{
			kindNames.add(k.name());
		}
		java.util.function.Predicate<Map<String, Object>> withdrawn = ev -> types.contains(String.valueOf(ev.get("type")))
			|| ("container".equals(ev.get("type")) && kindNames.contains(String.valueOf(ev.get("container"))));
		clientThread.invoke(() ->
		{
			kinds.forEach(containers::forget);
			pendingEvents.removeIf(withdrawn);
			api.dropQueuedEvents(withdrawn); // also from batches waiting in the offline queue
		});
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
		// Only the normal game: Leagues, Deadman, beta, Quest Speedrunning, PvP Arena and tournament characters
		// share the main's account hash but are separate characters, so they would overwrite the main's data.
		EnumSet<WorldType> types = client.getWorldType();
		RuneScapeProfileType profile = RuneScapeProfileType.getCurrent(client);
		boolean skipped = profile != RuneScapeProfileType.STANDARD;
		for (WorldType t : SKIPPED_WORLDS)
		{
			skipped |= types.contains(t);
		}
		if (skipped)
		{
			log.debug("XPTempo: not tracking on a {} world {}", profile, types);
			endSession("LOGOUT");
			return;
		}
		displayName = local.getName();
		world = client.getWorld();
		accountType = accountType(client.getVarbitValue(VarbitID.IRONMAN));
		long now = System.currentTimeMillis();
		boolean newSession = hash != accountHash || sessionId == null;
		if (newSession)
		{
			endSession("LOGOUT"); // a stale session for another character, if any
			accountHash = hash;
			sessionId = UUID.randomUUID().toString();
			sessionUploaded = false;
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
			clog.reset();
			loadGuide();
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
		if (config.uploadGe() && uploading())
		{
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null)
			{
				for (int slot = 0; slot < offers.length; slot++)
				{
					handleOffer(slot, offers[slot], now, newSession);
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
		pendingEvents.add(event("heartbeat", now,
			"xpPerHour", tracker.xpPerHour(now),
			"activity", activity));
	}

	private synchronized void endSession(String reason)
	{
		if (sessionId == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		flushXp(now);
		// Inventory/bank changes still inside their debounce window would otherwise be lost at logout.
		emitContainer(containers.take(ContainerTracker.Kind.INVENTORY, now, 0, true), now);
		emitContainer(containers.take(ContainerTracker.Kind.EQUIPMENT, now, 0, true), now);
		emitContainer(containers.take(ContainerTracker.Kind.BANK, now, 0, true), now);
		pendingEvents.add(event("session_end", now, "reason", reason));
		sendBatch();
		sessionId = null;
		sessionUploaded = false;
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
		if (!uploading())
		{
			pendingEvents.clear(); // nothing leaves the client until the user opts in and pairs (or for an unlinked character)
			return;
		}
		Payloads.Batch batch = buildBatch(new ArrayList<>(pendingEvents));
		pendingEvents.clear();
		api.submit(batch);
	}

	/** Data can leave the client: the user switched XPTempo on, it is paired, and this character is not unlinked. */
	private boolean uploading()
	{
		return config.enabled() && api.isConfigured() && !unlinkedHashes.contains(accountHash);
	}

	private static long parseHash(Payloads.Batch batch)
	{
		try
		{
			return batch.account == null ? -1L : Long.parseLong(batch.account.hash);
		}
		catch (NumberFormatException e)
		{
			return -1L;
		}
	}

	private Payloads.Batch buildBatch(List<Map<String, Object>> events)
	{
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
		batch.events = events;
		return batch;
	}

	/**
	 * "Enable XPTempo" was switched off: withdraw everything not yet uploaded and send only a session_end,
	 * so the server closes the session instead of counting it as still playing. Client thread only.
	 */
	private synchronized void stopUploads()
	{
		pendingEvents.clear();
		Payloads.Batch last = null;
		// A session_end only for a session the server knows about; nothing at all if this session never uploaded
		// (for example a key typed in while uploads are off).
		if (sessionId != null && sessionUploaded && api.isConfigured())
		{
			List<Map<String, Object>> events = new ArrayList<>();
			events.add(event("session_end", System.currentTimeMillis(), "reason", "PLUGIN_DISABLED"));
			last = buildBatch(events);
		}
		api.discardAllExcept(last);
		sessionId = null;
		sessionUploaded = false;
		bankOpen = false;
		geOpen = false;
	}

	/** Exchanges a pasted pairing code for a key. Nothing is sent to the server until "Enable XPTempo" is on. */
	private void maybePair()
	{
		String code = config.pairingCode().trim();
		if (pairing || code.isEmpty())
		{
			return;
		}
		if (!config.apiKey().trim().isEmpty())
		{
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_PAIRING_CODE, "");
			chat("XPTempo: this RuneLite profile is already paired. To pair it with another account, clear the API key first.");
			return;
		}
		if (!config.enabled())
		{
			chat("XPTempo: turn on \"Enable XPTempo\" to pair with the code you pasted.");
			return;
		}
		pairing = true;
		api.pair(code, "RuneLite", response ->
		{
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_API_KEY, response.apiKey);
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_PAIRING_CODE, "");
			pairing = false;
			chat("XPTempo: paired with your XPTempo account. Every character you play on this RuneLite profile will sync to it.");
		}, error ->
		{
			pairing = false;
			chat("XPTempo: pairing failed (" + error + "). Get a fresh code from the app and try again.");
		});
	}

	private void chat(String message)
	{
		log.debug(message);
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
