# ATP audit regression fixes

**Goal:** Correct the three defects confirmed in the prompt audit and commit verified fixes.

**Architecture:** Extend existing bounded ice-road detection to recognize guarded junctions; derive world identity from its normalized logical save path instead of its basename; retain vanilla player teleport lifecycle after server validation. Keep the current graph, database schema and network format.

**Tech Stack:** Java 17 bytecode, Minecraft 1.20.4, Fabric, JUnit 5 and Fabric GameTest.

## Tasks

1. Add failing `RoadDetectorTest` regressions for guarded T/cross junctions, rejection of damaged junctions, and local invalidation. Update `RoadDetector` so scans and uploaded candidate validation agree, with existing width, clearance and unloaded-block rules preserved.
2. Add `DataPathsTest` coverage for nested world isolation, normalized paths and compatibility with ordinary existing world directories. Update `DataPaths` and the `AtpServer` identity source. Do not automatically migrate ambiguous data previously shared by multiple nested worlds.
3. Add a real command/request/reply GameTest for teleporting a sleeping player and vanilla velocity handling. Update `AtpServer.reply` to use the vanilla teleport overload and completion behavior while retaining graph-selected coordinates and facing.
4. Run the new tests against the old behavior, then verify the fixes with `gradlew.bat test build --offline --console=plain` and `gradlew.bat runGametest --offline --console=plain`. Review the complete diff, document compatibility and results, and commit only task changes.

## Acceptance

- All connected branches of a supported guarded blue-ice junction are reachable without increasing roadConnectionDistance.
- Different nested save paths never share authoritative graph data; normal flat worlds keep their existing location.
- Approved restricted TP wakes a sleeping player before relocation, keeps the selected landing point, and preserves vanilla vertical-velocity/elytra behavior.
- Existing tests still pass; `prompt.txt` remains an untracked user input.
