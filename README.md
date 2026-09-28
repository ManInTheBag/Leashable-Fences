# Leashable Fences

Leashable Fences is a small Paper plugin that lets players leash fences together with leads — purely decorative, no mobs required.

## Features

- Connect any two fences with a lead to create a visible rope between them.
- Attach **multiple leads to the same fence**.
- Fully decorative: the leads don't consume mobs and don't affect gameplay.
- Connections are stored in `plugins/leashable_fences/connections.yml` and survive server restarts and chunk reloads; entities lost during a reload are respawned automatically.
- Connections created by older versions of the plugin are detected and adopted automatically.
- Fence connections clean themselves up if a fence is broken.

## How to use

### Connecting two fences

1. Hold a **lead** and right-click the first fence.
2. Right-click the second fence (still holding the lead) to connect them.

The lead is consumed when the connection is completed. Fences can be at most **10 blocks** apart (the vanilla lead limit).

Clicking the first fence again (or its knot) before completing the connection cancels it.

### Attaching multiple leads to the same fence

When connecting to a fence that already has a leash knot, **right-click the fence block below the leash knot, not the knot itself**. If you accidentally click the knot or the rope while holding a lead, the plugin redirects the click to the fence block you are aiming at, so stacked fences work as expected.

### Removing a connection

Right-click the leash knot (or the rope's end point) with an empty hand. The lead is dropped back.

Breaking a fence also removes its connections and drops the lead, just like vanilla leash knots.

## Compatibility

- Paper 1.21+ (built against the Paper 1.21.4 API)
- No dependencies

## Building

```
./gradlew build
```

The plugin jar is written to `build/libs/`.

To spin up a local test server with the plugin installed:

```
./gradlew runServer
```

## License

MIT — see the [original project](https://github.com/ManInTheBag/Leashable-Fences) by ManInTheBag.
