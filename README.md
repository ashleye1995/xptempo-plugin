# XPTempo RuneLite plugin

The companion plugin for [XPTempo](https://xptempo.com): live XP pace, wealth, goals and achievements for your own
Old School RuneScape characters, on your phone and in the browser.

It is **opt-in and read-only**. Nothing is sent until you switch it on and pair it with your XPTempo account, and
the plugin never changes anything in the game. What it sends and how long it is kept is in the
[privacy policy](https://xptempo.com/privacy).

## Install

1. Install the plugin from the RuneLite Plugin Hub (search for "XPTempo").
2. Get the XPTempo app (or open [xptempo.com](https://xptempo.com)) and create a free account.
3. In the app, choose **Link a character** to get an 8-character pairing code.
4. In RuneLite: Configuration → XPTempo → tick **Enable XPTempo** (the warning lists exactly what is sent and where),
   then paste the code into **Pairing code**. The plugin exchanges it for an API key once and clears the code; a
   chat message confirms the pairing.

One pairing covers **every character you play on that RuneLite profile**: each one appears in your XPTempo account
when it logs in. If other people play on your computer, give them their own RuneLite profile. Revoke a key from the
app's Settings if a computer is lost, or unlink a character there to stop tracking it: the server stores nothing
more for it and the plugin stops sending it. To track it again, clear the API key in the plugin settings and paste
a new pairing code.

## What is sent

Only while **Enable XPTempo** is on, only to `api.xptempo.com`, and only about the character you are logged into:

Always sent while enabled:

- **Character** — name, the account ID RuneLite uses to tell characters apart, account type (normal, ironman,
  hardcore and so on), world, and the plugin and RuneLite versions.
- **Skills and XP** — a full snapshot at login, then per-skill changes every 60 seconds, plus level-ups.
- **Activity and sessions** — every 60 seconds, what you are doing (banking, Grand Exchange, fighting a named
  monster, training a skill, idle) and your XP rate over the last ten minutes; session start and end; deaths.
- **Progress** — quest points and quests finished, combat achievement tiers, and achievement diary progress: each
  tier's completion and task count, plus the game's diary task variables so the app can tick individual tasks.

Each with its own switch under "What to send":

- **Inventory and equipment** — item ids and quantities when they change (at most every 60 seconds).
- **Bank** — item ids and quantities while the bank is open and when it closes.
- **Grand Exchange offers** — every offer change, with a stable id per offer so restarts never duplicate one.
- **Drops and kill counts** — what monsters, chests and pickpockets give you, the kill counts the game announces,
  and the collection log and combat achievement totals when you open those interfaces.

Items are valued on the server with the OSRS Wiki's real-time prices. Uploads are batched, retried with back-off
when the server is unreachable, and switching the plugin off stops them at once.

**Never sent:** your RuneScape login or password, your chat, anything about other players (PvP drops are sent
without the other player's name), your location, or anything from Leagues, Deadman, beta, Quest Speedrunning,
PvP Arena or tournament worlds. The plugin reads your own client only.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building and testing from source.

`build=standard`, BSD 2-Clause licence, no dependencies beyond the RuneLite client and Lombok.

XPTempo is a fan-made tool and is not affiliated with Jagex or RuneLite.
