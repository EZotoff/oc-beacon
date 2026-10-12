# Beacon-side reply transport — read-only investigation

Date: 2026-10-12 · Branch: `agent/sisyphus/attention-ux-3-bugs` · Read-only per mission task 4 (no code changes).

## Question

The supervisor ledger recorded the operator's Beacon reply with `channelID: "console"` and duplicated every reply event. Which endpoint/transport does the app actually use, what dedup id does it send, and is delivery confirmed? Is the fault app-side?

## Findings

### 1. Transport: normal session prompt — NOT a console/PTY transport

`SupervisorRepositoryImpl.sendReply()` ([SupervisorRepositoryImpl.kt:105-128](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SupervisorRepositoryImpl.kt)):

1. Resolves the item's `root` directory and an inbox session titled `[Beacon replies] <projectName>` (`INBOX_TITLE_PREFIX`, line 175; title builder lines 130-131). The session is found by exact-title match in `listSessions(directory=root, limit=100)` or created via `createSession(title=…, directory=root)` (lines 114-116).
2. Sends the reply as **one top-level user message** whose text is the Beacon envelope JSON, via `ports.message.promptAsync(conn, sessionId=inbox.id, parts=[text part], directory=root)` (lines 117-123).

The concrete wire call is `POST {baseUrl}/session/{sessionId}/prompt_async` with body `{"parts":[{"type":"text","text":"<envelope JSON>"}]}` ([V1ApiClient.kt:400](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClient.kt); V2/DSH adapters have equivalent overrides at `V2ApiClient.kt:1250`, `DshApiClient.kt:1233`). No console/WebSocket/PTY path is involved anywhere in the reply flow.

**Implication for `channelID: "console"`**: the app never declares a channel; a plain `prompt_async` user message is indistinguishable from a console-typed message to any observer of the session. If the supervisor labels it `channelID: "console"`, that label is supervisor-side inference, not app-sent metadata. The envelope schema has no channel field ([BeaconReply.kt:44-56](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/BeaconReply.kt) — fields are `v, clientMessageID, kind, text, index, contextTag, explicitItemID, question, sessionTitle, note`).

### 2. Dedup id: fresh UUIDv7 per send, not persisted across attempts

`reply.envelopeJson(UuidV7.generate())` (line 117). The `clientMessageID` is generated at send time ([BeaconReply.kt:41](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/BeaconReply.kt)). The app has **no automatic retry**: failure marks `Phase.FAILED` in `SupervisorReplyStateStore` and the operator must re-send manually ([SupervisorDetailViewModel.kt:106-121](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/supervisor/SupervisorDetailViewModel.kt)). Each manual re-send generates a **new** clientMessageID.

**Implication for duplicated reply events**: app-side duplicates are impossible to explain this way — the app sends exactly one prompt per reply event, with a unique id, and never retransmits under the same id. If the supervisor sees the same reply twice (even with different clientMessageIDs, or labeling both `console`), the duplication happens supervisor-side — e.g. the reply-router and a console/session watcher both observing the same inbox-session message, or the ledger writer emitting at two levels. One genuine app-side quirk worth knowing (not a duplicate-event source): inbox lookup scans only the first 100 sessions (`limit=100`, line 114) and re-creates the session on title miss, so it can spawn multiple `[Beacon replies] *` sessions over time — but each reply is still a single message in exactly one session.

### 3. Delivery confirmation: none beyond HTTP acceptance

`promptAsync` is fire-and-forget: success = the server accepted the prompt (returns admission), at which point `markSent` flips the UI chip to "Replied" ([SupervisorDetailViewModel.kt:118](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/supervisor/SupervisorDetailViewModel.kt)). There is no ack that the supervisor's reply-router actually consumed the envelope, and no read-receipt. The envelope's `note` field ([BeaconReply.kt:70-73](file:///home/ezotoff/src/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/BeaconReply.kt), `TRANSPORT_NOTE`) explicitly instructs the inbox session's agent turn to treat the message as transport, not instruction — consumption depends on that agent turn running in the inbox session.

## Verdict

**Fault is not app-side.** The app sends the correct envelope to the correct endpoint (inbox session `prompt_async`), with a unique dedup id per send and no retransmission. Per mission instruction, I stop here and report; the `channelID: "console"` mislabel and per-reply duplication must be fixed in the supervisor (ez-omo-config session).

## Suggested supervisor-side checks (for that session)

1. Why inbox-session user messages get `channelID: "console"` — likely the ledger labels any non-app-channel session message as console.
2. Where the same inbox message is observed twice (reply-router vs session/console watcher) producing duplicate ledger events.
3. Whether dedup by `clientMessageID` is actually wired — the id is present on every envelope, but app behavior means duplicates can never share an id.
