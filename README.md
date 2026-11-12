# Armor Restore

**Armor actually protects you again.**

Modern Minecraft armor gets weaker the harder you're hit — a full diamond set blocks about 70% of a small hit, but only 60% of a heavy one. Armor Restore puts back the pre-1.9 (pre-Combat Update) formula, where armor is a flat, predictable percentage: every armor point removes 4% of incoming damage, always.

A full diamond set blocks **80% at any hit size**. Iron gives 60%. The number on your armor bar finally means what it says.

---

## What it does

Replaces only the armor damage-reduction step with the old formula:

```
damage taken = damage × (1 − clamp(armor, 0, 20) × 0.04)
```

| Armor | Set | Damage blocked |
|---|---|---|
| 20 | Diamond / Netherite | 80% |
| 15 | Iron | 60% |
| 12 | Chainmail | 48% |
| 11 | Gold | 44% |
| 10 | Copper | 40% |
| 7 | Leather | 28% |

Armor Toughness is ignored entirely — the old system never had it. Since toughness no longer does anything, the mod also hides the "armor toughness" line from item tooltips so it stops advertising a stat that does nothing.

## What it does *not* do

This is deliberately a one-mechanic mod. Everything below stays exactly as vanilla:

- Shields, blocking, and the shield-block event
- Resistance, all other mob effects, absorption
- Armor durability
- Invulnerability frames, knockback, totems, death
- Attack cooldown, sweeping, and all other combat mechanics
- Damage types that bypass armor (fall, drowning, magic, fire, starvation...) still bypass it

No Mixin on NeoForge, no `setAmount()`, no event cancellation. Other combat mods keep working, and other mods that adjust armor reductions still run after this one and can override it.

## Breach still works

The 1.21 Breach enchantment is kept functional: it still lowers your effective armor fraction on top of the old formula. You can turn this off in the config if you'd rather have Breach do nothing.

## Compatibility

Available for **NeoForge** and **Fabric**. Each release targets a specific Minecraft version — check the version list on the download page before installing, since the game's damage pipeline has changed between major versions and a jar built for one will not run on another.

Both loaders behave identically and share the same config fields. Client and server side: the damage logic runs on the server; the tooltip cleanup runs on the client. Install it on the server for multiplayer (the client copy is optional and only affects tooltip text).

## Configuration

<details>
<summary>NeoForge — <code>config/armorrestore-local.toml</code></summary>

```toml
[oldArmor]
    # Restore the pre-1.9 armor damage reduction formula
    enabled = true
    # Lower bound of the armor value clamp used by the old formula
    armorClampMin = 0
    # Upper bound of the armor value clamp (vanilla full diamond set = 20)
    armorClampMax = 20
    # Let the modern Breach enchantment still reduce the old armor fraction
    breachIntegration = true
    # Hide the armor toughness line in item tooltips
    hideArmorToughnessTooltip = true
```
</details>

<details>
<summary>Fabric — <code>config/armorrestore.json</code></summary>

```json
{
  "enabled": true,
  "armorClampMin": 0,
  "armorClampMax": 20,
  "breachIntegration": true,
  "hideArmorToughness": true
}
```
</details>

`armorClampMax` is there if you run a modpack with armor values above 20 (some modded sets go higher). Raising it lets those sets benefit from the old formula instead of being capped.

## Why this exists

The 1.9 Combat Update replaced flat percentage armor with a toughness-based formula that degrades against large hits. That change is why late-game armor feels unreliable, and why shields became mandatory rather than optional. Restoring the old numbers makes armor the dependable defensive layer it used to be — without reverting anything else about modern combat.

## Repository layout

This is a single repository holding two independent Gradle projects, one per mod loader. They share no build configuration on purpose — each is its own Gradle root and can be opened in an IDE on its own.

```
mod/         NeoForge build  → [NeoForge] armorrestore-<version>-<mc>.jar
mod-fabric/  Fabric build    → [Fabric] armorrestore-<version>-<mc>.jar
docs/        Design and investigation notes
tools/       Icon generator (not needed to build the mod)
```

The damage logic (`OldArmorReduction`) and the tooltip filter (`ToughnessTooltipFilter`) exist as a copy in each project rather than in a shared module. That is deliberate: it keeps each project a self-contained Gradle build with no composite-build setup, at the cost of duplicating two small platform-free classes.

## Building

Requires the JDK version declared by the project's Gradle toolchain (`java.toolchain` in `build.gradle`). Gradle provisions it automatically via the toolchain resolver, so any matching JDK it can find on your machine is enough — you do not need to set one up by hand.

```bash
cd mod          # or mod-fabric
./gradlew build
```

The jar lands in `build/libs/`. Unit tests run as part of `build`:

```bash
./gradlew test
```

There is also an opt-in runtime self-test that asserts the reduction math inside a live server:

```bash
cd mod        && ./gradlew runServer -Pselftest
cd mod-fabric && ./gradlew runServer -Pselftest
```

It logs one `PASS`/`FAIL` line per assertion (armor 0 / 10 / 20, fall-damage regression, tooltip contents) and does not affect normal gameplay.

If you are behind the Great Firewall and the NeoForged maven is slow, add `-PcnMirror` to use the BMCLAPI mirror. It is off by default.

## Documentation

- [`docs/armor-mechanism-investigation.md`](docs/armor-mechanism-investigation.md) — how damage flows through the modern game, where armor reduction actually happens, and how it differs from 1.8.9.
- [`docs/implementation-contract.md`](docs/implementation-contract.md) — the implementation decisions, the NeoForge/Fabric hook points, and the change log.

These were written during development, in Chinese, and reference line numbers in decompiled sources that are not redistributed here (they would be copyright-infringing). They are published as a record of the reasoning, not as a tutorial.

## License

MIT. See [LICENSE](LICENSE).

Not affiliated with Mojang or Microsoft.
