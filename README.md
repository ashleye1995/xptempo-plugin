# XPTempo RuneLite plugin

Sends your own character's XP, activity and session time (later: bank, inventory and GE offers) to your
XPTempo account. Opt-in, read-only, written against the rules in `../Vault/research/runelite-plugin.md`.

## Develop

Requires JDK 11 (the Hub builds with 11 and the template's Lombok does not run on newer JDKs). This machine has
Temurin 11 at `~/.jdks/temurin-11` (IntelliJ: File → Project Structure → SDK → add it from that folder).

```bash
export JAVA_HOME=~/.jdks/temurin-11/Contents/Home
./gradlew test          # unit tests (XpTracker)
./gradlew run           # dev client with the plugin loaded (-ea, --developer-mode)
```

To log into a Jagex account in the dev client, follow https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
once (launch via the Jagex launcher with `--insecure-write-credentials`).

## Pair with the API

1. Run the API locally (`../API`, `npm run dev`) and get a pairing code (`npm run seed:dev`, or later from the app).
2. In RuneLite: Configuration → XPTempo → paste the code into **Pairing code**. The plugin exchanges it for an API key,
   stores the key in the **API key** field and clears the code. A chat message confirms the pairing.
3. Tick **Send data to XPTempo** (the warning explains exactly what is sent).

The plugin always talks to `https://api.xptempo.com`; there is no URL setting. To develop against a local API, run the dev client with `XPTEMPO_API=http://127.0.0.1:3000 ./gradlew run`.

## What is sent

- `session_start` + a full `skills` snapshot two ticks after login; `session_end` on logout, client shutdown or
  disabling the plugin.
- `xp`: per-skill deltas every 60 s (server-tunable), `LEVEL_UP` and `DEATH` events immediately.
- `heartbeat` every 60 s with an activity label (banking, Grand Exchange, fighting X, training X, idle), region id
  and XP/hr over the last ten minutes.
- `container`: full inventory (at most every 60 s when changed), equipment (on change), bank (every 30 s while
  open and on close). Item ids are canonicalised (notes → items, placeholders dropped) and only sent when the
  contents changed.
- `ge_offer`: every offer change, with a stable `clientOfferId` per offer kept in RuneLite's per-character config
  (`geoffer.<slot>`), so the login burst and restarts never duplicate an offer; a slot going empty is sent as
  `collected`.

Each category has its own switch under "What to send". Batches retry with back-off and merge when the API is
unreachable.

## Hub notes

`build=standard`, BSD-2 licence, no dependencies beyond the client. The Hub needs this directory to be the root of
its own public GitHub repository.
