# BYBUS development lines

## Stable: main / 1.0.x

Normal fixes and public releases continue on `main` with the existing 1.0.x version sequence.
The automation work does not replace this line.

## Local development: feature/1.1.0-automation / 1.1.0

The user requested an AALC-compatible Android automation feature, validated on their vivo Pad
over Tailscale and wireless debugging. The development version is 1.1.0. It keeps the existing
application ID and signing identity so an authorized upgrade preserves the user's container,
game resources and settings. Do not uninstall or clear data for a test.

Do not push this development branch, tags, APKs or releases to GitHub unless the user later
authorizes publication. Keep its in-app update channel local so a newer 1.0.x version code
does not silently replace the development build.

Android versionCode is independent of versionName. Allocate a code above the installed build
when needed; do not reserve an extremely high code that prevents returning to a future stable
release. Local rebuilds with the same code may replace a same-signed local build.

## Keeping fixes synchronized

For each general fix on main:

1. Finish the affected regression checks and commit the fix independently of release/version changes.
2. Apply that fix to `feature/1.1.0-automation` with traceable source attribution (`git cherry-pick -x`
   for an isolated fix; otherwise apply only the relevant paths and record the original commit).
3. Resolve against the development implementation. Preserve its version, update channel, feature
   files and the user's game data. Do not reset the branch or copy an entire checkout.
4. Run the affected checks on the development branch. Record both commits and evidence in the
   local development record. A clean build alone is not a gameplay verification.
5. Check `git cherry feature/1.1.0-automation main` for outstanding main commits. Classify release-only
   changes as excluded rather than blindly copying version numbers or release metadata.

Shared branch policy is committed to both lines. Private device addresses, pairing codes and task
progress remain under `D:/CodexData/outputs/LimbusZhLauncher`, outside the public repository.
