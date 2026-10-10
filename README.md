# XPTempo RuneLite plugin

The companion plugin for [XPTempo](https://xptempo.com): live XP pace, wealth, goals and achievements for your own
Old School RuneScape character, on your phone and in the browser.

It is **opt-in and read-only**. Nothing is sent until you pair the plugin with your XPTempo account, every category
can be switched off, and the plugin never changes anything in the game. What it sends and how long it is kept is
in the [privacy policy](https://xptempo.com/privacy).

## Install

1. Install the plugin from the RuneLite Plugin Hub (search for "XPTempo").
2. Get the XPTempo app (or open [xptempo.com](https://xptempo.com)) and create a free account.
3. In the app, choose **Link a character** to get an 8-character pairing code.
4. In RuneLite: Configuration → XPTempo → paste the code into **Pairing code**. The plugin exchanges it for an API key
   once and clears the code; a chat message confirms the pairing.
5. Tick **Enable XPTempo**. The warning box lists exactly what is sent and where.

Pair each character you want to track from the same RuneLite install; the API key is stored per RuneLite profile.
Revoke a key from the app's Settings if a computer is lost.

## What is sent

Only for the character you are logged into, only while the plugin is enabled, and only to `api.xptempo.com`:

- **Skills and XP** — a full snapshot at login, then per-skill changes every 60 seconds, plus level-ups and deaths.
- **Activity** — a heartbeat every 60 seconds with what you are doing (banking, Grand Exchange, fighting X,
  training X, idle), the region id and the XP rate of the last ten minutes; session start and end.
- **Inventory and equipment** — item ids and quantities when they change (at most every 60 seconds).
- **Bank** — item ids and quantities while the bank is open and when it closes.
- **Grand Exchange offers** — every offer change, with a stable id per offer so restarts never duplicate one.
- **Drops and kill counts** — what monsters and chests drop you, the kill counts the game announces, and the
  collection log and combat achievement totals when you open those interfaces.
- **Progress** — quest points, quests finished, combat achievement tiers and the 48 achievement diary tier flags.

Everything except skills and session time has its own switch under "What to send". Items are valued on the
server with the OSRS Wiki's real-time prices. Batches retry with back-off when the API is unreachable.

**Never sent:** your RuneScape login or password, your chat, anything about other players, or anything from
characters you have not paired. The plugin reads your own client only.

## Develop

Requires JDK 11 (the Hub builds with 11 and the template's Lombok does not run on newer JDKs).

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew test          # unit tests (trackers and the plugin)
./gradlew run           # RuneLite dev client with the plugin loaded (-ea, --developer-mode)
```

The plugin always talks to `https://api.xptempo.com`; there is no URL setting. To develop against a local API, run
the dev client with `XPTEMPO_API=http://127.0.0.1:3000 ./gradlew run`. To log into a Jagex account in the dev
client, follow the [RuneLite wiki](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) once.

`build=standard`, BSD 2-Clause licence, no dependencies beyond the RuneLite client and Lombok.

XPTempo is a fan-made tool and is not affiliated with Jagex or RuneLite.
