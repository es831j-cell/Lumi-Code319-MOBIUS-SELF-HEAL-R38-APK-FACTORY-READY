## Code 308 — Speech + Bridge Telemetry R27
- App 4.0.61 / code 308
- Guardian minimum: code 11
- Adaptive STT engine failover on audio-detected code 7.
- Full Guardian bridge stage telemetry in exported diagnostics.
- Möbius visual unchanged from Code 307.

# Lumi ZIP Update Runtime v2 — v3.3 / code 236

Lumi's normal update path is a user-selected ZIP imported from **Lumi Update Center**.
APK Factory is not required for normal Lumi updates.

## Package layout

```
lumi-update.json
lumi-update.sig          # optional for locally selected packages
payload/...
```

Each payload entry is SHA-256 checked before it is written. A package may also carry a manifest signature made with Lumi's app signing key.

## Manifest example

```json
{
  "formatVersion": 1,
  "updateId": "lumi-feature-001",
  "name": "Lumi Feature Update",
  "version": "1",
  "type": "content",
  "minAppVersionCode": 236,
  "maxAppVersionCode": 999999,
  "releaseNotes": "Adds a new Lumi skill and UI definition.",
  "preferences": {
    "runtime_module_epoch": 1
  },
  "files": [
    {
      "path": "payload/skills/example.json",
      "sha256": "<sha256>",
      "target": "skills/example.json"
    },
    {
      "path": "payload/ui/example.json",
      "sha256": "<sha256>",
      "target": "ui/example.json"
    }
  ]
}
```

## ZIP-update targets

- `avatar/home`, `avatar/public`, `avatar/work`, `avatar/travel`, `avatar/lockdown`, `avatar/private`, `avatar/mobius`, `avatar/preview`
- `asset/<relative path>`
- `config/<relative path>`
- `skills/<relative path>`
- `prompts/<relative path>`
- `ui/<relative path>`
- `voice/<relative path>`
- `home/<relative path>`
- `models/<relative path>`
- `migrations/<relative path>`
- `scripts/<relative path>`

All file destinations remain inside Lumi's private app storage. Absolute paths and path traversal are rejected.

## Transaction behavior

1. Import ZIP.
2. Read and validate `lumi-update.json`.
3. Verify optional manifest signature.
4. Verify every declared SHA-256.
5. Check installed Lumi core compatibility.
6. Create a rollback point for every setting/file that will change.
7. Apply the content transaction.
8. MainActivity runs Lumi's core self-test.
9. The Update Center exposes **Roll back last ZIP update** for the most recent successful content package.

If applying the content transaction itself fails, files and preferences are restored immediately.

## APK/core updates

A ZIP may still contain a newer signed Lumi APK for Android-level changes, but this is the exception rather than the normal path. Android requires its normal installer approval. The APK must use Lumi's package name, Lumi's signing certificate, and a newer versionCode.

## Security boundary

ZIP updates do not gain unrestricted access to Android. The runtime only writes to approved preference keys and approved app-private module directories. Android permissions, manifest declarations, native libraries, services, and compiled platform plumbing still require a core APK update.


## Code262 routing note

The compiled Talk router now uses one local-first hierarchy: Fast Brain for light turns, configured OpenAI for substantive work, then the optional remote open-model booster. Provider configuration and individual request failures are tracked separately so a transient inference failure is not mislabeled as an unconfigured provider.


## Code263 Home provider-state repair
- Android app upgrades preserve SharedPreferences, including `last_lumi_reply`.
- If that preserved Home subtitle claims no online provider is configured while the secure OpenAI key exists, Code263 replaces it with the current verified/configured state.
- This prevents a stale pre-fix sentence from masquerading as a fresh routing failure.


## Code264 Local-first background AI repair

- Normal conversation is terminally local unless the top-level router explicitly classifies the turn as heavy or the user asks for the stronger brain.
- Online provider checks run quietly beside the conversation runtime and never own a turn merely because OpenAI is configured.
- Background checks are single-flight and automatic retries are bounded, preventing indefinite retry/audio loops.
- Integration Center reports three separate facts: provider configured, provider available now, and provider actually used for the last reply.
- Diagnostics record the selected reply brain and routing reason for every local/online decision.

## Code 269 — AI State Truth Repair
Separates configured credential, provider reachability, and actual inference success/failure. The Integration Center now exposes the sanitized last inference error instead of showing a misleading single CONNECTED state.

## Code 271 — Context, Attention & Conversation UX
Adds ambient-speech attention gating, keyboard-owned conversation input, launch greetings, and Trusted Places/routine memory.

## Code 272 — Wake Phrase Keyboard Escape
- Typing mode still blocks normal speech turns and ambient chatter.
- Wake phrase detection remains armed while the keyboard is open.
- Saying “Lumi”, “Hey Lumi”, “OK Lumi”, or “Okay Lumi” releases keyboard ownership and resumes voice mode.
- Saying “Lumi, <request>” exits typing mode and routes the request immediately.
- Diagnostics record wake-phrase escape separately from ambient speech.

## Code 273 — Live State + Conversational Continuity Repair
- Social “How are you?” stays social.
- Explicit self-diagnostics phrases execute the real self-test.
- Provider-status follow-ups retain conversational subject.
- Connection wording is generated from current credential state instead of stale canned copy.


## Phase 1 Maintenance Bridge
Guardian now owns core APK installation from Lumi-staged updates: URI handoff, SHA-256 verification, identity/version verification, recovery checkpoint, PackageInstaller submission, transaction ledger, and post-install certification. APK Factory becomes emergency/build tooling rather than the normal update path.

## Phase 2 — Modular Runtime
- Explicit signed module declarations in update manifests.
- Atomic active-module registry and runtime epoch.
- Required-module health certification.
- versionCode 275 / 4.0.28-phase2-modular-runtime-r1.

## Phase 3 — Secure Maintenance Request Channel
- Core: 4.0.29-phase3-secure-maintenance-r1 / code 276
- Guardian: 1.4-maintenance-channel / code 5
- Adds signature-protected, freshness/replay checked Guardian maintenance request queue.
- Adds Fast Conversational Re-arm (220 ms reply guard, 140 ms cue guard) while retaining echo fingerprint suppression.

## Phase 4 — Automatic Post-Change Certification

Lumi code 277 / Guardian code 6. Guardian owns post-change pass/fail, retries transient certification failures up to three times, persists evidence, and exposes recovery-required state. Failed core certification restores the latest state checkpoint but does not falsely claim a silent APK downgrade, which Android does not allow for a normal app.

## Phase 5 — Conversational Maintenance Orchestration
Lumi 4.0.31 / code 278, Guardian 1.6 / code 7. Explicit Lumi maintenance requests now enter the bounded OpenAI + Guardian tool path from normal conversation, while current-turn owner approval and Guardian verification/certification remain mandatory. See PHASE5-CONVERSATIONAL-MAINTENANCE.txt.

## Phase 6 — Trusted Build & Package Relay
- Lumi 4.0.32 / code 279
- Guardian 1.7 / code 8
- Adds exact-host HTTPS relay enrollment and transaction-bound signed APK delivery.
- Guardian independently validates SHA-256, Lumi signing identity, package/version, recovery checkpoint, install submission, and post-change certification.
- Relay is transport only; it cannot replace Guardian's trust decisions.

Code 289 Responsive Routing R8 adds fast interactive failover, live-intent bypass, brain-state UI, richer Fast Brain diagnostics, and a longer conversation idle window.

## Code 292 factory-exit foundation
Code 292 makes APK Factory an emergency/recovery tool rather than the routine update path. Lumi Update Center imports signed `.zip`/`.lumi` packages; content modules apply transactionally in-app, while core APK payloads are independently verified and handed to Guardian for checkpoint/install/certification. Android's package security remains authoritative.

## Code 307 — Bridge Stage + Compact Glyph R26
- App 4.0.60 / code 307
- Guardian 2.0 / code 11
- Same-signature bridge trust, failed-stage diagnostics, compact 3D Möbius, sparse surface glyphs.

Code 310 / R29: voice recognition and conversation turn-loop repair. Guardian bridge and Code 309 Möbius preserved.
