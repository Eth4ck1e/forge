
## 2026-11-01 — Throughput knobs (runs/m0/opt; 40K pod, rotated, stop-file at 60 games, quiet)

| Config | Workers | AI budget | JVM | Games/min | AI-budget timeouts | Rounds mean |
|---|---|---|---|---|---|---|
| D baseline | 8 | 5 s | default (G1) | 13.8 | 24 | 12.5 |
| A | 10 | 5 s | default | 13.2 | 0 | 12.2 |
| B | 8 | 2 s | default | 15.6 | 15 | 12.3 |
| C | 8 | 5 s | `-XX:+UseParallelGC` | 15.9 | 0 | 12.3 |

ParallelGC is the free win: +15% throughput at the same AI budget, and A2's 15 games/min target is met at 8 workers. 10 workers is no better than 8 (the performance cores are the limit). A 2 s decision budget is about as fast as ParallelGC but changes what the AI plays (evaluations cut short), so it stays out of baseline data generation; it is a lever for later. Win spreads at 60 games are within noise of each other. AI-budget timeout counts vary a lot run to run (0 to 24), which says they come from a few specific board states rather than a steady rate.

Overnight configuration chosen: 8 workers, `-XX:+UseParallelGC -Xmx2g`, AI budget 5 s, 30-round cap, 30-minute wall clock with exit-and-restart, full logs, stop-file pod capped at 11.5 h.

Operational lesson from this evening: a `pkill` pattern that matched only the build-tree jar path missed workers running the pinned jar copy, which left 34 orphaned JVMs from a failed test run loading the machine for 12 minutes. Process detection in the dashboard, status and scaling scripts now matches any `java ... sim` command line.
