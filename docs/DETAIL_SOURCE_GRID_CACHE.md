# Process-local detail source grid cache candidate

Base e8d576cbc019520e316d42edae613a9edd3712ec, isolated tree `filmroom-gpu6-detail-source-cache`.
No build, tests, Android execution, device measurements, delivered-package changes, or fusion integration performed by this task.

## Scope and identity

PhotoSource owns exactly one reusable immutable detail grid, independent of processing parameters and viewport. The retained slot is at most 1536 x 1536 x 12 bytes = 27 MiB on disk plus marker/lease metadata. There is no retained full-resolution source spool or pixel buffer. Old slots can remain only while active session readers retain them; this is live-work ownership, not an extra idle cache slot.

The key includes canonical app root, this PhotoSource's process-local snapshot, original and optional linear input file stamps, MIME/original dimensions, actual target grid dimensions, and bilinear mapping version. Different maxEdge values that produce the same actual grid can share. Separate PhotoSource instances never share a slot, even for identical paths and bytes. There is no persistent/cross-process cache index and no new native/export identity.

Each request rechecks lexical path scope, symlink/hardlink rejection, regular-file identity, size, mtime and ctime for both inputs. A cache hit also rechecks grid, ownership marker and lease file identity. Missing/replaced/modified normal cache files invalidate the slot; links are rejected. ctime detects ordinary same-size edits that restore mtime. fileKey and unix:ctime support is optional for this optimization. If either identity is unavailable or the filesystem explicitly does not support that attribute, a narrow metadata-only fallback runs the original uncached path. Original path/link/read failures still propagate. Device behavior/performance remains to be verified; the existing mandatory path guard still uses unix:nlink.

This is still an app-owned immutable-file contract, not an adversarial content attestation. Stat checks cannot prove unchanged bytes if a privileged writer can restore all filesystem metadata. Source and grid SHA-256 strings are the exact original cold-path results; no synthetic identity replaces them. They are reused only in this object's validated immutable snapshot. The old decode/spool/linear-resize and hash operations remain unchanged on misses.

## Ownership and cancellation

- Cache slot holds one owner reference to the existing DetailSource and its original locked directory lease.
- Each prepare returns an independently idempotent reader lease. Session close releases only that reader.
- Replacement or close drops owner. Final reader release removes grid/marker/lease with the original non-link-following cleanup and unlocks/closes the file handle.
- No original-source lease is retained by the cache. Transient preparation still holds source leases, so source discard cannot deadlock waiting for itself. Once preparation ends, discard may delete the original while an existing session continues reading the independent grid.
- A per-source mutex gives single-flight construction. Publication is separately locked against close. Failure/cancellation before publication closes the complete unpublished grid. Dispatcher-return cancellation rolls back this request's new slot and reader; cancellation of a cache hit releases only its reader. Active readers of a rolled-back entry remain valid.
- clearLinearPreview and discard permanently close the cache, prevent later publication, and drop ownership. PhotoRepository.discard uses finally so cache cleanup errors do not skip original-source discard.
- Native planning/build failure after successful preparation leaves a valid cached grid owner. This is intentional; source lifecycle cleanup still deletes it. Existing provider tests now distinguish reader retirement from source-cache retirement.

Cache close synchronously removes the slot and forbids new publication. Final grid unlink and lease close are enqueued on one shared SupervisorJob + Dispatchers.IO cleanup scope, never done inline by clearLinearPreview/onCleared. Cleanup errors are contained; the original owned-tree cleanup still releases its file lock/channel in finally and leaves ownership proof until heavy children are removed, enabling later orphan recovery. Cold replacement drains already-scheduled retirements before another allocation; live reader grids are unaffected. Tests can inject the enqueue executor and await already-scheduled retirements with `awaitRetirements()` after releasing readers/owner. This waiter does not claim to await outstanding readers or unfinished builders.

## Integration surface

Only DetailSourceFactory, PhotoSource/PhotoRepository lifecycle, and new DetailGridCache change production behavior. No Provider/Controller/native/DetailTiming edits. `DetailSource.cacheHit` is an internal read-only Boolean for the parent to integrate with diagnostics; no hashes or paths are logged. Cold timing markers remain unchanged. Hits omit cold resize/hash markers honestly.

## Tests written, not run

`DetailGridCacheTest`: single-flight, independent/idempotent reader close, owner close, replacement with active old reader, invalid/throwing validators, failed build, close during build, cancelled waiter, cancellation after build, cancelled hit, dispatcher-return abandonment semantics, invalidation with another active reader, queued exactly-once retirement including cleanup failure, oversized cache entry rejection.

`DetailGridCacheHostTest`: new factory/same PhotoSource exact hash/grid reuse; actual-grid key; cross-object isolation; root key isolation; source edits with restored mtime; same-size/time replacement; missing/truncated/same-size modified grid; marker/lease replacement; source/grid symlink and hardlink rejection; clear/discard with active readers; idle-owner orphan protection; injected async unlink failure with actual lease unlock and orphan recovery; failed-build retry; source mutation during cold build; cancelled dispatcher return and clean retry.

Existing DetailSourceScopeHostTest and OwnedDetailProviderTest update cache-lifecycle assertions and release cache owners after tests. Their native contract and pixel assertions are unchanged.

## Serialized verification commands for parent-granted window only

```
./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest --tests dev.filmroom.io.DetailGridCacheTest --tests dev.filmroom.io.DetailGridCacheHostTest --tests dev.filmroom.io.DetailSourceScopeHostTest --tests dev.filmroom.engine.OwnedDetailProviderTest
./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest --tests dev.filmroom.engine.DetailMeteringIdentityHostTest --tests dev.filmroom.DetailLoadingHostTest --tests dev.filmroom.roi.EditorDetailControllerTest
```

Then applicable full test/lint checks, supported-Android fileKey/ctime/link verification, repeated open/parameter-change/close/memory-pressure flows, and actual phase measurements. These commands have NOT been run. Source stat validation and cache hits cannot be claimed faster until measured; no GPU.6 or other delivered APK includes this candidate from this task.

Integration adds two host cases proving optional-metadata fallback does not change grid bytes or bypass scope/hardlink rejection. These additional tests are not yet executed.
