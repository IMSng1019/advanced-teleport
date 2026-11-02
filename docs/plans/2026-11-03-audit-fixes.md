# ATP audit corrections

Goal: Correct the concrete requirement gaps found after the initial implementation, preserve existing world data, and verify the fixes with focused regressions and real Fabric GameTests.

1. Require continuous paired rail approaches into powered railway on both sides of a bend; preserve valid loaded candidates when another possible partner is unloaded. Validate uploaded segment IDs and geometry against world blocks.
2. Retain entire portal surfaces in the graph and SQLite (migrate schema v1), discover loaded portals at their actual block positions, and use surface distance for road attachment.
3. Keep strict mode's nearest-target-region rule. Break geometric ties using the shortest sum of within-dimension straight distances through reachable paired portals (portal crossings cost zero), so renaming stations cannot override a closer geometric route.
4. Apply client road proposals only after bounded authoritative validation, provide a client station submission API with explicitly registered server-side provider validators, and preserve protected registrations.
5. Recompute existing automatic roads on detection-configuration reload. Process pending edits relevant to a teleport's connected geometry before resolving it; unrelated construction and unrelated graph revisions must not cancel the request.
6. Preserve vanilla rotation/facing data. Make safe-landing adjustment an explicit opt-in server setting, default false; the default uses the graph's actual selected point.
7. Use compressed snapshot/delta synchronization, actually read client caches while requiring fresh server confirmation, and use coalesced background SQLite writes with transactional per-entry updates.
8. Add unit and GameTest regressions for each corrected rule, run the complete build and tests, document protocol/API changes, and commit the resulting revision.

Constraints: server authority, bounded packet sizes, no deletion based on unloaded blocks, no automatic deletion of protected API entries, backward-compatible four-argument PortalLink construction, and migration of existing graph.sqlite databases.
