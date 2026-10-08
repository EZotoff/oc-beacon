# Portable Mode

## Design contract
The authoritative portable-supervisor proposal owns density and navigation.
Existing OC Beacon Material 3 primitives own styling: semantic colorScheme,
titleMedium/bodyLarge/labelLarge typography and SpacingTokens (4/8/12/16/24/32dp).
No new palette, motion system, or dependency is introduced.

## Layout and states
A centered, single-column mini-display, at most 360dp wide. Home presents at most
two attention summaries, a divider, and one current project/session. Neighbor
labels are single-line, ellipsized; selection is bold and uses primary color.
Interactive rows are at least 48dp high. PTT remains 72dp in the thumb zone.
Supervisor presentation replaces the home body, never adds a second pane.
Its list/choice/table/progress/diff content is paged in groups of three; text is
bounded. Selection indices remain those of the original frame. Last show is
retained in the voice repository and can be revisited after returning to Home.
Offline/empty/error/stale states are explicit; frozen attention cannot be opened.
All new app chrome uses string resources. No keyboard or transcript in this mode.

## Data boundary
Attention comes only from the validated supervisor snapshot cache. Sessions come
from SessionRepository on currently connected servers (initial REST recent page
plus live session/status flows). Projects are server-scoped roots, ordered by
latest session activity. Directory maps to the longest enclosing published root
(separator-aware); otherwise directory itself is the project. Missing directories
remain unassigned, not guessed from titles. Operator cards have no session IDs:
attention taps ground the root and attention selection, with an explicitly empty
session, then enter the existing Detail -> Answer flow. No label-to-ID matching.
Recent session access is limited to the loaded server page/live cache; no claim of
an exhaustive cross-root session inventory or last prompt/answer is made.
