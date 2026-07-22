# Song Selection Redesign — Design Doc

> **Status: proposal, for review.** Nothing implemented yet. This replaces the *song-selection*
> half of the radio build (`ScheduleSongSupplier` + the SQL bucket queries + the reuse ladder in
> `AbstractAgendaService`). Timeline building, mixing, emission, and metrics are **unchanged**.
> **OTS is not touched** — see §6.

---

## 1. Goal

Radio song selection that rotates fairly and never feels repetitive: songs cycle by how long ago they
aired, respect boost and the "new" tag, differ by sourcing mode, and never play the same track twice
in a row. OTS stays untouched.

---

## 2. Requirements

- **R0 — NEVER the same song twice in a row. This is the hard invariant, above all others.**
  The worst on-air failure is a song playing back-to-back with itself. This must hold at **every**
  seam, not just within one scene's fill:
  - within a scene's reuse rung (already "never adjacent");
  - **across a scene boundary** — last song of scene *N* ≠ first song of scene *N+1*;
  - **inside a 2-song crossfade entry** — the two songs in one `TimelineEntry` must differ;
  - **after priority force-placement / `repositionPastPrioritySongs` / `replacePrioritySong`** — a
    forced "new" song must not land next to a copy of itself.
  Enforced by a single final **adjacency guard** pass over the fully-assembled, cross-scene timeline
  (§3b): if entry *k*'s lead song equals entry *k−1*'s trailing song, swap in the next
  recency-ordered candidate that breaks the tie. It runs *after* selection, widening, priority
  placement, and scene concatenation — so nothing downstream can reintroduce an adjacency. If the
  brand truly owns a single song, adjacency is physically unavoidable and is logged, not hidden.

- **R1 — Rotation.** A song that just played must not come back until the rest of the eligible pool
  has had its turn. Least-recently-played returns first.
- **R12 — New catalog additions get a head start (old vs new).** "Old/new" has two axes: *play
  recency* (`last_time_played_by_brand`) and *catalog age* (`reg_date` — when the song entered the
  brand). Rotation (R1) is driven by play-recency. Catalog age only breaks the tie **within the
  never-played (NULL) block**: newest additions air before the old never-played back-catalog
  (`reg_date DESC`). After a song's first spin, catalog age no longer matters — pure fair rotation.
  This is a stable tiebreak, **not** a persistent freshness bias.
- **R2 — Cross-build / cross-midnight memory.** Rotation survives rebuilds, restarts, and the
  day boundary. (→ persist play state, §4.)
- **R3 — No duplication within a build, across scenes too.** One build never schedules the same song
  twice while unplayed songs remain. Reuse is a *defined last rung*, ordered by recency, never
  adjacent — not an emergency "reset everything".
- **R4 — Catalog Boost respected.** `SUPER_BOOST` ~4×, `BOOST` ~2×, normal 1×, `QUARANTINE`
  suppressed. Boost biases *probability*, never *breaks rotation into determinism*.
- **R5 — "new" label = temporary, one-shot priority.** A shared fragment tagged `"new"` (datanest,
  via `sharedInfo.priority`) must be **guaranteed** to play on the next build, then the tag is
  **cleared exactly once** so a later build doesn't re-float it. It jumps the rotation queue by
  design, one-song-for-one-song so no schedule time shifts (`repositionPastPrioritySongs` /
  `replacePrioritySong` preserved). The contributor-email flow depends on this guarantee
  (`SongEmitter → notifyContributorPlaying`), so "eligible" is not enough — it must be *force-placed*.
- **R6 — Talkativity unchanged.** It governs intros in `TimelineBuilder`, not song identity. Left
  alone. Selection only guarantees *enough* songs to fill the budget.
- **R7 — Cross-midnight: leave the existing time-math as-is (OUT OF SCOPE).** A late start (e.g.
  23:30) is rare. Today's behaviour — build the day from 00:00, skip the past entries, air only the
  23:30→00:00 tail, then the 00:00 daily rebuild takes over — is *acceptable*. It's wasteful for that
  one rare case, and that waste is explicitly fine (decided). Do **not** rewrite the scheduling
  time-math for it; not worth the complexity. The redesign must simply **not make it worse**. §7.
- **R8 — Fast rebuild.** One query per scene. No per-song extra round-trips.
- **R9 — No fallbacks / no legacy.** No silent reset-and-retry paths; reuse is the single defined
  rung (R3). Clean, direct selection.
- **R10 — OTS untouched and isolated** (§6).
- **R11 — Sourcing mode drives behaviour, not one-size-fits-all** (§3a). `RANDOM`/default → full
  recency+boost rotation. `QUERY` (search/genre/label) → filter is a **preference, not a
  constraint**: matched songs are the priority head, then widen to **any** fresh song rather than
  ever replaying a match (non-repetition beats matching the search). `STATIC_LIST` → **exempt** from
  rotation/boost, pinned order kept literal. `GENERATED` → empty pool, untouched.

---

## 3. The new selection model

**One idea: recency gate, then boost-weighted random.**

Per scene, for the scene's scope (brand) and sourcing mode:

```
1. Candidate query (single SQL):
     WHERE brand matches, archived = 0, boost > -1 (quarantine excluded),
           id NOT IN (usedIds)                       -- cross-scene dedup, R3
           [+ genre/label/search conditions if QUERY sourcing]
     ORDER BY last_time_played_by_brand ASC NULLS FIRST,  -- R1: stale & never-played first
              reg_date DESC                           -- R12: newest catalog additions lead the NULL block
     LIMIT K = ceil(needed * OVERSAMPLE)              -- e.g. OVERSAMPLE = 3

2. Boost-weighted random pick of `needed` from that stale window:
     weight = CASE boost WHEN 2 THEN 4 WHEN 1 THEN 2 ELSE 1 END
     (R4 — boost biases which of the *stale* songs get picked, rotation still bounds the set)

3. Priority ("new"-label) shared songs are **force-placed** at the head, bypassing the gate (R5),
   then their label is cleared (one-shot).

     **Subtlety the new model introduces:** a "new" song has never played, so its
     `last_time_played_by_brand IS NULL` and NULLS-FIRST already sorts it to the very front of the
     candidate window — so it is always *eligible*. But eligibility ≠ guarantee: the step-2
     boost-weighted random pick could still skip over it. So priority is **not** left to the recency
     order; it is force-included ahead of the random pick, exactly as `floatPriorityToFront` does
     today. Recency ordering makes new songs surface naturally *on later builds too* (nice), but the
     one-shot guarantee stays explicit.

4. If the query returns fewer than `needed` (catalog smaller than the day):
     reuse rung — take from the same recency order again, least-recently-played first,
     never placing a song adjacent to itself. This is R3's defined last rung, NOT a reset.
```

### 3b. Adjacency guard (R0 — runs last, over the whole timeline)

Selection/widening/priority all operate per scene, so none of them can *see* a scene boundary. R0 is
therefore enforced by one final pass over the concatenated cross-scene timeline, after everything
else:

```
flatten all scenes' entries in emission order → sequence of songs
for each adjacent pair (prev_trailing_song, next_leading_song):
    if same id:
        replace next_leading_song with the next recency-ordered candidate
        (from that scene's remaining pool) whose id != prev_trailing_song
        and != the song that would follow it        -- don't create a new adjacency
    if no such candidate exists (single-song catalog):
        log adjacency_unavoidable, leave as-is
also check the two songs inside every 2-song entry differ (swap the second if not)
```

This is the *only* place adjacency is guaranteed, and it is the last thing to touch the timeline —
priority placement and scene concatenation happen before it, so a forced "new" song or a boundary
join cannot slip an adjacency past it.

Why this satisfies everything:
- **R1/R2**: the `ORDER BY last_time_played` *is* the rotation, and it reads a column we now keep
  current (§4). Never-played songs (`NULL`) always lead, so a fresh catalog fully cycles before
  anything repeats.
- **R3**: `usedIds` excluded in SQL; reuse only when the pool is genuinely too small, and then in
  recency order.
- **R4**: boost is the weight inside the stale window, not a deterministic pre-sort.
- **R8**: one query per scene (plus the shared-fragment fetch), no per-song round-trips.

`OVERSAMPLE` (stale-window multiplier) is the one tuning knob: `1` = pure LRP determinism, higher =
more variety among stale songs. Start at **3**. Documented as the single lever.

### 3a. Per-sourcing behaviour (`WayOfSourcing`) — the model is NOT uniform

The §3 recency+boost query is the engine, but each sourcing mode plugs into it differently.

| Sourcing | Rotation (recency)? | Boost weight? | Query conditions | Notes |
|---|---|---|---|---|
| **RANDOM / default** | **Yes** | Yes | type only | The §3 query verbatim. This is the common case. |
| **QUERY** (search / genre / label) | **Yes** | Yes | filter is a **preference, not a constraint** | Matched songs are used as a **priority head** (recency-ordered, each once), then the pool is **widened with ANY song** rather than replaying a match. Non-repetition outranks the filter. See below. |
| **STATIC_LIST** | **No — exempt** | No | explicit ids, pinned order | An author's fixed curation. Return every pinned fragment in pinned order, untouched. Rotation/boost must **not** reorder it. No play-count filtering either. |
| **GENERATED** | N/A | N/A | none | Empty song pool; content produced at emit. Untouched. |

**QUERY is a preference, not a hard filter (important — this is the anti-repetition rule).**
A QUERY scene must **never** replay one of its own matched songs while any unplayed song exists in
the brand, matched or not. So the filter never *restricts* the pool to the matches — it only *ranks*
them first:
- **Rung 1:** filter-matched songs, recency-ordered, each used at most once → priority head.
- **Rung 2:** **any** song (filter dropped), recency-ordered → fills the entire remainder. A narrow
  filter therefore pulls in unmatched-but-fresh songs instead of looping the few it matched.
- **Rung 3:** recency-ordered reuse, never adjacent → only when the whole brand catalog is spent.

So avoiding repetition is treated as **more important than matching the search** — exactly the
existing `widenToFill` philosophy, now made the explicit default for QUERY rather than only kicking
in when the match count is short. Even when the filter matches "enough" songs, we still prefer fresh
unmatched songs over replaying matched ones.

Ladder interaction (now recency-aware):
- **RANDOM** short of budget → **widen** with any song, recency-ordered (`getAnySongs` reworked).
- **QUERY** → widen is the *normal* path, not a fallback (see above).
- **STATIC_LIST** does **not** rotate but still widens if the pinned list is shorter than the budget
  (same as today) — the widening fill *is* recency-ordered even though the pinned head is not.
- **Shared "new"-label priority** (R5) floats to the head on **RANDOM/default** as today; on QUERY it
  arrives via widening (shared catalog is type-only — the known genre/label gap in RADIO_WORKFLOW
  §2a still stands, out of scope here).

So "does it rotate?" is decided by sourcing mode, not applied blindly. STATIC_LIST staying literal is
a hard requirement — it's the one mode where a listener *wants* the exact same songs in the exact
same order.

---

## 4. Play persistence (R2 — the piece that makes rotation real)

Columns already exist on `mixpla__brand_sound_fragments` (`played_by_brand_count`,
`last_time_played_by_brand`). **No DDL, no schema change** — we just start writing them.

- **Where:** `SongEmitter.send`, after `queueSupplier.sendSongsToQueue` succeeds (the same
  success point where `notifyContributors` already runs). Both DJ-on (line ~113) and DJ-off
  (line ~134) branches. Record per song in the entry, skipping `SourceType.STREAM`.
- **What:** `UPDATE ... SET played_by_brand_count = COALESCE(played_by_brand_count,0)+1,
  last_time_played_by_brand = now() WHERE brand_id = ? AND sound_fragment_id = ?`.
- **Consistency:** fire-and-forget with a logged failure (like play-history today); a missed write
  just means that song looks slightly staler than it is — self-healing on next play, never fatal.
- Needs `brandId` at emit time — `SongEmitter` has the `ILiveStream`; confirm it exposes brandId
  (radio streams do). OTS streams don't emit through this recording (owner-scoped, and OTS is
  ephemeral) — gate the write on radio/brand scope so OTS stays clean (R10).

This is the only emit-path change in the whole redesign.

---

## 5. What gets deleted

- `ScheduleSongSupplier.getSongsRandomly` three-bucket split (newest/oldest/random merge).
- `SoundFragmentBrandRepository.findByFilter` + `findByFilterOldest` (the two dead-column sorts).
  Keep one recency-ordered query (rework `findByFilterRandom` into the §3 candidate query, or add a
  single `findCandidatesByRecency`).
- The exhaustion-reset branch in `RadioAgendaService.buildAgenda` (`state.usedIds().clear()`),
  replaced by the recency reuse rung (R3/R9).
- The misleading names. New names say what they do (`findRotationCandidates`, etc.).

`widenToFill` / `getAnySongs` (drop-the-filter widening) — **keep the concept** but it now shares the
same recency-ordered query with the filter conditions removed. Ladder rungs stay: matched → widened
→ recency-reuse.

---

## 6. OTS isolation (R10) — "shared base, radio overrides"

Chosen approach: keep the shared base, radio overrides the selection strategy only.

- `AbstractAgendaService` keeps the current `fetchSongsForSceneWithDuration` /
  `selectDistinctSongsToFillDuration` **as the OTS path, unchanged**. `OtsAgendaService` keeps
  calling exactly what it calls today — byte-for-byte behaviour, zero risk.
- `RadioAgendaService` gets a new selection strategy (new method / small collaborator) that uses the
  §3 recency+boost query and the §4 persistence. Radio stops calling the old bucket path.
- Concretely: extract selection behind one seam (e.g. a `SongSelectionStrategy` with a `Radio` impl
  and a `Legacy`/`Ots` impl), or simply give `RadioAgendaService` its own supplier method and leave
  OTS on the old one. The old bucket SQL that OTS still needs stays; only radio's queries are new.
- **Test guard:** OTS build output (song identity given a fixed catalog) must be identical
  before/after. That's the isolation acceptance check.

> Trade-off accepted: the old bucket code lives on solely for OTS rather than being deleted
> outright. That's the cost of not touching OTS. Revisit only if OTS is later redesigned too.

---

## 7. Cross-midnight — OUT OF SCOPE (decided)

Not part of this redesign. Selection changes *which songs* fill entries; it does not touch the
scheduling time-math, and we are deliberately **not** rewriting that time-math.

**Worked example — station starts at 23:30 (the case that prompted this):**
1. Build lays out a full 24h day from the loop's 00:00 anchor (time-of-day slots, not "now").
2. `TimelineBuilder` schedules the loop's entries today 00:00 → tomorrow 00:00.
3. It's 23:30, so ~23.5h of entries are already past → `StaggeredSongScheduler` skips them; only the
   **23:30→00:00 tail airs**.
4. At **00:00 the daily rebuild** replaces it with a fresh, correct full day.

This is **accepted as-is.** Yes, it wastefully builds a day of past entries for that one rare late
start — that waste is **explicitly fine** (decided); not worth the complexity of an absolute-datetime
rewrite. Known pre-existing quirks left untouched: the `>12h ⇒ shift back a day` heuristic
(`TimelineBuilder` lines 91–100) and overnight-loop chopping at the rebuild boundary. The selection
redesign must only **not make any of this worse** — it doesn't, because it changes song identity, not
entry timing.

> If cross-midnight ever becomes a real problem (not a rare late start), the fix would be to build
> against absolute datetime like OTS's `buildOtsTimeline` (`explicitSceneStart`). That is a separate,
> separately-approved scheduling change — **not** in this redesign.

---

## 8. Scope of work (single change)

Selection + persistence only — §3, §3a, §3b, §4, §5, §6. Cross-midnight (§7) is **out of scope**.
Then fold the final behaviour into `RADIO_WORKFLOW.md` §2/§2a and delete this doc.

## 9. Decisions (all settled)

- **`OVERSAMPLE = 3.`** Single constant; the only tuning knob.
- **OTS seam = separate radio method.** Radio gets its own `selectSongsForScene(...)` using the new
  recency+boost query; OTS keeps calling the existing `fetchSongsForSceneWithDuration` unchanged. No
  new interface/abstraction — two impls that never swap at runtime isn't worth the layer.
- Play memory persisted on emit (no DDL); recency rotation, no dayparts; catalog-age tiebreak (R12);
  cross-midnight out of scope.
```
