LUMI STRONG BOOTSTRAP V1 - CODE 247

PURPOSE
This is the candidate source package for the final routine APK Factory install. It is based on Lumi v3.8.2/code243 and preserves Lumi's applicationId and signing certificate.

BOOTSTRAP COMPONENTS
- Existing Lumi content ZIP updater and rollback foundation.
- Separate Lumi Guardian Android companion app.
- Guardian package identity/signature verification.
- Guardian PackageInstaller session-based Lumi core installs.
- Signature-protected Lumi <-> Guardian ContentProvider bridge.
- Guardian-requested pre-update recovery checkpoints with SHA-256 integrity files.
- Stronger bootstrap health certification that fails when Fast Brain is quarantined or prompt-mismatch degraded.
- First-run embedded Guardian installation flow.
- API-key migration into Android Keystore-backed private storage.
- Portable backups exclude API credentials and update-transient state.
- Release-oriented GitHub build/certification workflow.

IMPORTANT TRANSITIONAL SIGNING NOTE
The current Lumi keystore is retained in this APK Factory source bundle solely so the one final factory build preserves the existing Lumi signing identity. After this bootstrap is proven on-device, move signing material out of ordinary source/update archives and into the protected long-term custody arrangement. Do not publish this source repository publicly.

CURRENT LIMITS
- The independent Guardian core-install path is implemented, but automated forward-recovery requires a future update package to provide a correctly signed higher-version recovery APK. Do not claim that native rollback is proven until the deliberate bad-build recovery test passes on the actual phone.
- Persistent custom wake-word behavior is not newly implemented here. This build prioritizes the permanent update/recovery foundation.
- Cleartext networking remains enabled in Lumi for compatibility with the user's local remote-AI endpoint; this remains a hardening item after bootstrap stability is proven.
- APK Factory should remain installed permanently as catastrophic fallback.
