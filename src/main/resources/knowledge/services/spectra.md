---
type: Service
title: spectra
description: Python audio-analysis service that extracts musical features and a weak AI-generation check from a sound fragment and stores them on the row.
tags: [service, spectra, analysis, essentia, bpm, key, moods, genres, fastapi]
audience: [developer]
---

# spectra

spectra is the audio-analysis service. Given a sound fragment it extracts musical features and a weak
check for AI-generated audio, then persists them — the results are not returned in the HTTP response.

It is a FastAPI service written in Python 3.14, using Essentia together with the Discogs-EffNet
TensorFlow models, and it runs on host network port 38795 with no public route.

# What it extracts

Everything lands in the `add_info` jsonb column of `mixpla__sound_fragments` in the **moon** database:
BPM, key and scale, loudness, mood scores from 0 to 1 (happy, sad, party, relaxed, aggressive),
danceability, top genres scored against the Discogs taxonomy, and an `ai_generated_metadata_check`
holding `suspected_ai_generated` plus supporting evidence.

```json
{
  "bpm": 101.79, "key": "Ab", "scale": "minor", "loudness": 3205.87,
  "moods": {"happy": 0.304, "sad": 0.121, "party": 0.514, "relaxed": 0.659, "aggressive": 0.283},
  "danceability": 0.71,
  "top_genres": [{"genre": "Electronic---Berlin-School", "score": 0.227}],
  "ai_generated_metadata_check": {"suspected_ai_generated": false, "evidence": []}
}
```

# Flow

```
datanest, on save ──POST /analyze {soundFragmentId, path?}──▶ spectra
  → resolve the file: the local path if it is on disk, otherwise the original file_key from _files
    by parent_id (file_type <> 102 excludes opus, preferring 101 over legacy 0)
  → download from Hetzner object storage if needed
  → Essentia and TensorFlow analysis
  → UPDATE mixpla__sound_fragments.add_info (moon DB)

jesoos chat (assess_track / upload_song)
  ──POST /assess multipart file──▶ spectra
  → analyze in-process, return JSON (bpm, key, moods, genres, danceability, is_music, …)
  → writes nothing to the DB (no SoundFragment yet)
```

`POST /analyze` takes a required `soundFragmentId` and an optional `path`, answers 202 with
`{"status": "accepted", …}` and works in the background; `GET /health` is the liveness probe. The
optional `path` is a fast path that skips the download and is used only if the file exists on disk.
Temporary downloads are deleted immediately after analysis, while a shared local original is never
deleted.

`POST /assess` accepts a multipart `file` field and returns the analysis synchronously in the body,
including `is_music` (false when the top Discogs genre is under `Non-Music---*` or duration < 3s).
jesoos uses that verdict to reject speech / spoken-word uploads before save.

# Operations

Dependencies are managed with uv, and the TensorFlow models (~23 MB) are downloaded once:

```bash
uv sync
uv run python models_setup.py
uv run uvicorn service:app --host 0.0.0.0 --port 38795
```

`main.py` runs a one-off analysis of a single file for quick checks. In a container the models are
fetched at startup by `ensure_models()` when they are not already in the image.

The image is `python:3.14-slim` plus ffmpeg, published manually to `ghcr.io/kneoio/spectra:latest` by
the `Build and Push` workflow (`.github/workflows/deploy.yml`, `workflow_dispatch`), then deployed with
`docker compose pull spectra` and `docker compose up -d spectra` from `~/compose`. The service is
defined in `~/compose/docker-compose.yml` on the host network at port 38795.

Configuration covers the moon database (`SPECTRA_DB_HOST` and `SPECTRA_DB_PORT`, defaulting to
`127.0.0.1:8572`, plus `SPECTRA_DB_NAME` `moon`, `SPECTRA_DB_USER` `regolith` and
`SPECTRA_DB_PASSWORD`) and object storage (`HETZNER_STORAGE_ENDPOINT`
`https://hel1.your-objectstorage.com`, `HETZNER_STORAGE_BUCKET` `soundfragments`, and the access and
secret keys). On the server these live in `~/compose/env/spectra.env`.

Because the port has no public route, reaching it means tunnelling with
`ssh -L 38795:127.0.0.1:38795 kneo@65.108.49.217` and then calling plain HTTP without TLS:

```bash
curl http://127.0.0.1:38795/analyze -XPOST \
  -H 'Content-Type: application/json' \
  -d '{"soundFragmentId":"<uuid>"}'
```

# Note

spectra is easy to forget when reasoning about what happens to a track after it is saved: it has no
Quarkus stack, no queue binding and no frontend, and it was missing from every service roster until this
bundle listed it. Track analysis is not magic in datanest — it is this service.
