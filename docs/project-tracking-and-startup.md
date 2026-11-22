# Project tracking and launcher startup

## 2026-09-24 design

The default project card is the official LocalizeLimbusCompany GitHub **latest
published release**, not a completion percentage. The project detail panel lets
the user select GitHub updates or ParaTranz translation statistics, remembers
that choice, and keeps separate caches. Existing installs have no source
preference and therefore also default to GitHub. Switching views does not change
the translation package source or the check performed before every game launch.

Use only fixed public HTTPS endpoints, bounded responses/timeouts and no token.
Validate release identity, numeric tag, published timestamp, non-draft and
non-prerelease status; retain no author or account information. A failed refresh
keeps the last valid record with a visible stale indicator. A request for a
previously selected source must never overwrite the current source's UI.
Tracking work remains separate from game preparation, with throttling and a
15-minute cache lifetime. External links are limited to the official project
pages. Release dates are not translation completeness or in-game coverage.

Fresh anonymous checks: ParaTranz project 6860 returned HTTP 404; project 20115
returned the explicitly frozen `LLC - 2026/8/4 快照`. Neither provides verified
current progress. The official PRTS repository describes a replacement system,
but this investigation did not establish a working public LLC statistics
endpoint. GitHub latest release returned `2026092102`, published
`2026-09-21T04:27:24Z`. These are observations, not permanent availability claims.

Sources:
- https://www.zeroasso.top/docs/community/llcdev/
- https://github.com/LocalizeLimbusCompany/PRTS
- https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/latest
- https://docs.github.com/en/rest/releases/releases#get-the-latest-release

Android 12+ owns a mandatory starting window. Hide its icon with a transparent
drawable, use the homepage background, and remove its exit view immediately when
the first frame is ready. Remove the Compose homepage's initial 240 ms fade and
slide. Do not introduce a splash Activity, sleep, network readiness gate or
change VirtualApp's required process initialization order.
https://developer.android.com/develop/ui/views/launch/splash-screen

The separate warning-to-title game video transition is outside this launcher
starting-window change. It is not reported as fixed by removing the app splash.

## Game update investigation

The installed game is now 1.115.0 / 475. Its arm64 protection and IL2CPP library
hashes differ from 471. Historical process exits show repeated guest SIGKILL;
they alone do not identify the protection failure. Exact new binary sites,
runtime failure and full integrity values must be checked independently before
approving a new profile. Never accept an unknown build merely because offsets
or library sizes resemble the prior version.

Device reproduction on 475: native profile is unverified, then `50040` and guest
SIGKILL. Static inspection independently confirms both `w2=50040; blr x5`
sites at 0x54488/0x545e4, path branches at 0x4e6b0/0x99820 and their cleanup
targets, the bounded openat site, and the entire 0x70000..0x70f00 integrity
control-flow block. A temporary hash-qualified map-only observation profile is
used while validating the encrypted 50048 site and new integrity metadata; it
does not qualify those unverified paths. Temporary observation code must be
removed before delivery.

Launch preflight validates the installed PackageInfo and both native hashes
before importing or stopping the guest. Cache approval only in host memory,
keyed by version, lastUpdateTime and the complete base/split file identities.
Recheck that identity immediately before opening the guest; an update while
translation is being prepared must request a fresh synchronization instead of
mixing game generations. The native process still independently verifies its
profile. Unknown builds display a compatibility message before launch; this is
a guard against silent termination, not a claim to support unknown game updates.

475 runtime 14:40:43: `50048` reports through return address +0x4e568; the
decrypted +0x4e550..0x4e564 sequence is
`aa0003e1 910fe3e0 52987002 52800023 52800004 d63f0260`. Its preceding call
returns to +0x4e550. The independently inspected cleanup remains +0x4e998.
This identifies the caller and cleanup, but does not establish that writing
the dynamically encrypted instruction is safe; the subsequent fault below
required a stable-entry design instead.

After the scoped 50048 fix, 475 exits with SIGSEGV during early Unity startup.
The existing maintainer checks SIGILL every tick but SIGSEGV only every 25 ticks
(roughly 1.25 seconds); protection/ART can replace a handler inside that gap.
Maintain the SIGSEGV forwarding wrapper at the same bounded startup cadence,
without changing its hook prologue or treating unknown synchronous faults as
success. This preserves downstream ART context handling and improves fault
evidence; it does not authorize a new PC recovery exception.

475 runtime 14:52:30 isolated the new SIGSEGV at **+0x4e564**, LR +0x4e550,
fault address 0xfca. This is the dynamically encrypted report instruction, not
the historical unwinder fault. Periodically writing the cleanup branch into
that self-modifying region is not robust across encryption changes. For 475,
stop writing any bytes there. Interpose the stable report entry +0x22594, after
checking its five immutable entry instructions, and redirect only code=50048,
return address=+0x4e568 with the complete six-instruction decrypted call sequence
matching the observed trace. A register-preserving AArch64 bridge tail-calls the
original function for every other call. Record this static hook in the owned
patch ledger. Preserve the existing 471 path until independently migrated; do
not broaden SIGSEGV or SIGILL exceptions to hide the new fault.

The stable 50048 bridge reached PlayDone without that SIGSEGV. Its shared AArch64
bridge passed 1,000 native-device cycles checking integer/SIMD argument
preservation, original return values, cleanup continuation and stack restoration.
At 14:55:50, the 475 IL2CPP stack record and an independent full section
recalculation agreed: .text offset 0x3e56e50 size 0x5bea7c CRC da9e4db3;
il2cpp offset 0x44158cc size 0x564dbe4 CRC 0f8f19a2. All 86 owned patches were
covered. Move these build-specific constants into a typed layout and reuse the
same complete-byte validator and pre-JNI-detach continuation; never skip the
integrity report merely because its numeric code matches.

At 15:03:33, the 475 protection descriptor and independently recalculated whole
range CRC agreed: 179 entries, index 0/1, module +0, expected/actual 9a237967,
range fingerprint dd108bf95840fd56, 48 owned bytes substituted. The descriptor
shape and verified control flow match 471, while the baseline CRC is different.
Qualify 475 as a full profile only with its exact hashes, new section and range
CRCs, immutable hook entries and narrow runtime predicates. Remove temporary
observation branches and the launch preflight exception before delivery.

Full Release lint also exposed older minimum-SDK gaps: replace Path.of with
Paths.get, use API-appropriate microG version/signature access while retaining
certificate verification, and qualify the light-navigation-bar resource at
API 27. Resolve fixed public project links to an exported external Activity.
The legacy virtual foreground-service notification is optional and already
best-effort; check Android 13 notification permission before posting, retaining
service bookkeeping when denied. Device validation showed that declaring the
permission lets the guest request a new notification prompt through the host.
Remove that declaration to retain the existing permission boundary. Suppress
only the dependency lint message naming SetServiceForeground, whose optional
posting is permission-guarded and exception-contained; all other notification
lint diagnostics remain enabled.
https://developer.android.com/develop/ui/views/notifications/notification-permission

Device testing also found that Android's match=0 ResolverActivity cannot be
opened as if it were a concrete browser. When there is no resolved external
handler, use the system chooser with a fresh fixed HTTPS intent. If an explicit
handler is unavailable, retry through that same chooser. This preserves the
no-default-browser case without targeting the library's non-exported dialer.
