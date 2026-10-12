# beacon-attention-ux - three operator-reported bugs + beacon-side reply transport check

Operator report 2026-10-12 (attention escalation screen, Supervisor UI):

## Mission
Three UI bugs on the supervisor attention/detail screens, then a read-only check of the app's outgoing reply transport.

## Tasks (implementation + verification = one task each; TDD where the repo has test harness)
- [x] 1. Text not selectable on escalation card/detail elements.
  Wrap the supervisor card/detail text in Compose `SelectionContainer` (or equivalent selection registrar) so long-press selects text. Screens: SupervisorScreen attention cards, SupervisorDetailScreen sections (`ui/screens/supervisor/`). Must NOT change layout/spacing/typography.
  Acceptance: long-press on the escalation body text starts a selection with system handles on a real device/emulator screenshot; regression: card click-to-detail still works (selection must not swallow taps — scope SelectionContainer to the Text, not the whole Card if that breaks click).
  Evidence: screenshot before/after in .omo/evidence/beacon-attention-ux/.
- [x] 2. No copy-text affordance on the boxes.
  Add a copy-to-clipboard action per detail section (What happened / judgment body / Why-premises): small `IconButton` (content-copy icon, string resource for a11y) using `LocalClipboardManager`/Android ClipboardManager + a brief confirmation (existing toast/snackbar pattern). Must NOT dump whole screens to clipboard on one button — per-section scope.
  Acceptance: tapping copy puts that section's exact text on the clipboard (verify via instrumented test or adb `am broadcast` clipboard dump in evidence); a11y labels present (i18n via string resources, incl. CJK-safe).
  Evidence: .omo/evidence/beacon-attention-ux/.
- [x] 3. Reply input hidden by keyboard — screen does not shift when IME opens.
  Root-cause the window insets handling: check `AndroidManifest.xml` `windowSoftInputMode` for the main activity (likely needs `adjustResize`) and the Compose side (`WindowInsets.ime`, `imePadding()`/`navigationBarsPadding()` interplay, edge-to-edge setup in MainActivity). Fix so the supervisor reply `TextField` stays visible above the keyboard while typing, without breaking the PTT/chat screens' existing inset behavior.
  Acceptance: on the detail Answer flow, focusing the reply field with the keyboard open shows the field and the Send action fully (screenshot evidence); chat screen inset behavior unchanged.
  Evidence: .omo/evidence/beacon-attention-ux/.
- [x] 4. READ-ONLY beacon-side transport check (investigation, report only — no code changes here for the supervisor side):
  Identify exactly which endpoint/transport the app's supervisor reply uses (Beacon inbox envelope with clientMessageID? console session prompt?), what dedup id it sends, and whether delivery is confirmed/acked. Context: the supervisor ledger recorded the operator's Beacon reply with `channelID: "console"` and duplicated every reply event — either the app sends via the console transport, or the supervisor mislabels it. Write findings + file:line to .omo/evidence/beacon-attention-ux/reply-transport.md. The supervisor-side investigation (separate session in ez-omo-config) owns the fix; if the fault is app-side (e.g. wrong envelope endpoint), STOP and report instead of fixing here.

## Constraints
- Branch: `agent/<you>/attention-ux-3-bugs`. Remote `origin` is `LeoNardo-LB/oc-beacon` (NOT operator-owned): commit locally, DO NOT push to master; report unpushed count at the end.
- Match repo conventions (Compose, string resources for ALL new chrome, SpacingTokens, no new dependencies).
- Tests: use the repo's existing test patterns for what's testable; manual/UI evidence via screenshots for the rest.
- Must NOT: change supervisor state files, touch anything under ~/.local/state, or edit other repos.

## Commit strategy
One atomic commit per bug. Report unpushed count explicitly.
Commits: 567ae6b95 bug1 selection · 1b76a49b8 bug2 copy · 64c04d97e bug3 imePadding · evidence + this record in the follow-up docs commit. Verified: compileDevDebugKotlin + supervisor unit tests green; emulator evidence (selection handles, per-section copy + exact clipboard text, reply field above IME incl. before-fix hidden-field capture) under .omo/evidence/beacon-attention-ux/. Task 4 verdict: fault NOT app-side — see reply-transport.md.
Execution baseline: 64c04d97ed316da972e2e99ac7d9ccac3219d19f
## Execution Record

