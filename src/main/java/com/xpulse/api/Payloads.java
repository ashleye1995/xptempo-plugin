package com.xpulse.api;

import java.util.List;
import java.util.Map;

/** JSON shapes of the ingest contract (Vault/research/data-model.md §13), serialised with RuneLite's Gson. */
public final class Payloads
{
	private Payloads()
	{
	}

	public static final class Batch
	{
		public String batchId;
		public String sentAt;
		public PluginInfo plugin;
		public AccountInfo account;
		public String sessionId;
		public List<Map<String, Object>> events;
	}

	public static final class PluginInfo
	{
		public String version;
		public String runelite;
	}

	public static final class AccountInfo
	{
		/** Client.getAccountHash() as a decimal string (a Java long does not survive JSON number parsing). */
		public String hash;
		public String displayName;
		public String accountType;
		public Integer world;
	}

	public static final class PairRequest
	{
		public String code;
		public String clientName;
	}

	public static final class PairResponse
	{
		public String apiKey;
		public String keyPrefix;
		public String userEmail;
	}

	public static final class Ack
	{
		public String batchId;
		public int accepted;
		public int duplicates;
		public List<Rejection> rejected;
		public String serverTime;
		public CadenceConfig config;
	}

	public static final class Rejection
	{
		public int seq;
		public String code;
		public String message;
	}

	public static final class CadenceConfig
	{
		public int heartbeatSeconds;
		public int xpFlushSeconds;
		public int inventoryDebounceSeconds;
		public int bankMaxEverySeconds;
		public int maxBatchEvents;
		public int maxBatchBytes;
	}

	public static final class ApiError
	{
		public ErrorBody error;
	}

	public static final class ErrorBody
	{
		public String code;
		public String message;
	}
}
