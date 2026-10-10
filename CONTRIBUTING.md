# Building and testing XPTempo from source

Requires JDK 11 (the Plugin Hub builds with 11, and the template's Lombok does not run on newer JDKs).

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew test          # unit tests
./gradlew run           # RuneLite dev client with the plugin loaded (-ea, --developer-mode)
```

The plugin always talks to `https://api.xptempo.com`; there is no URL setting. To develop against an API on your
own machine, run the dev client with `XPTEMPO_API=http://127.0.0.1:3000 ./gradlew run`. Only `localhost` and
`127.0.0.1` are accepted as overrides; anything else falls back to the production address.

To log into a Jagex account in the dev client, follow the
[RuneLite wiki](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) once.

Please test in game yourself; do not automate the game client.
