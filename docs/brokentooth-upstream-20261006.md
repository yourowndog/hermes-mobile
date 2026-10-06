# Brokentooth upstream reconciliation — 2026-10-06 UTC

This branch merges upstream dev `840f5708` with fork dev `750d751b` and ports the
proven staged marketplace/font changes from worktree `t_acee63c3` at `d6575963`.
The original dirty clone and original staged worktree are preserved.

## Preserved behavior

- Server-profile bootstrap before first WebSocket dial.
- Cyberpunk dark-only preset, adapted to the current complete palette contract.
- Public VS Code Gallery marketplace, bounded VSIX parsing, custom theme persistence,
  recovery banner, and font-family selection with bundled Droid Sans Mono.
- Current upstream server-backed Speak/Stop using `/api/audio/speak` and ExoPlayer.
  The historical TTS implementation was not duplicated.
- Existing `.dev` app identity and stable upstream debug signing key.

## Physical deployment boundary

The production app `com.m57.hermescontrol` uses upstream's private release signer.
This checkout has no compatible production release key. The existing
`com.m57.hermescontrol.dev` app uses the committed debug signer, so it can receive
a data-preserving update. Never uninstall either package or copy private credentials.
Production is not updated by this development APK.

## Human acceptance after install

Open **Hermes Dev** while the phone is unlocked. Confirm the existing connection is
retained, chat loads, Settings → Appearance offers the marketplace and font selector,
and a selected theme survives closing/reopening. Try Speak then Stop on one response.
Instrumented UI, large-font, RTL, and live audible TTS acceptance remain explicit
physical gates unless observed and recorded. Installation/package version alone does
not establish these behaviors.
