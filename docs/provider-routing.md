# Provider acquisition across Android variants

Issue [#56](https://github.com/gravere/BYBUS/issues/56) reports a Google login
crash before the UI appears. ColorOS 17 invokes `getOplusContentProvider` while
microG reads its settings. The physical caller is BYBUS, but the request names
the virtual Google package. The existing standard ActivityManager proxy does
not receive this OEM request. The older ColorOS null-service fallback addresses
window appearance and does not fix provider identity.

## Routing boundary

Install a content-resolver context on virtual Applications before attachment,
and on Activities and Services before `onCreate`. The application retains
its virtual package, resources, files and account identity. Only the resolver's
transport context belongs to the real host, so package, UID and attribution
source describe the same physical caller on every Android variant.

For an enabled provider registered in the same virtual user, acquire through
the existing standard `IActivityManager.getContentProvider` proxy, then install
the returned holder through `ActivityThread.installProvider`. Cached provider
acquisition, stable/unstable references, release and death handling remain owned
by Android. This avoids OEM acquisition entry points for virtual configuration
data without cloning microG's SettingsProvider or changing account data.

System providers retain the original platform resolver, with the host transport
context. Explicit requests for another Android user do not enter virtual routing.
Disabled or other-virtual-user providers do not fall through to a physical provider
with the same authority. Framework cache keys use the virtual ProviderInfo user;
system acquisition uses the physical Android user.
Issue #67 confirms that physical profiles other than user 0 also reach legacy
provider and UserManager Binder calls. At these system boundaries map only the
current virtual user's ID to the captured physical host user. Keep provider
registration/cache keys virtual; preserve other explicit user requests and real
permission failures. Read-only user-unlock checks must query the actual host
profile, never return a constant unlocked result or request cross-user grants.
Do not catch permission failures as successful results, change permission grants,
retry mutations, or replace a working OEM service with an empty implementation.

The same boundary applies to real ActivityManager PendingIntents, notifications,
accessibility clients, AppOps and temporary usage exemptions. Match the complete
AIDL parameter signature before mapping the current virtual user to the physical
host user. Do not infer a user from an arbitrary integer (request codes, flags,
notification IDs and UIDs are not users). Keep PendingIntent destination records,
notification namespaces and virtual package lookups in the virtual user. Preserve
explicit requests for other profiles and negative platform user constants.
Profile regression checks must include actual PendingIntent creation through the
production redirector; successful PackageManager queries alone do not cover it.

Reflection is restricted to checked platform method signatures and fields.
Missing virtual-routing capabilities produce an explicit error and a bounded
diagnostic; they must not fall through to a physical Google provider. System
providers use the original resolver with the host identity. No OEM name, phone model or single API version enables
the normal route. A compile-only `IContentProvider` declaration supplies the
hidden platform type; it must never be packaged in the app or test APK.

## Connectivity identity

Google components retain their virtual package for account and billing operations.
For the real `IConnectivityManager.getNetworkCapabilities` call, the caller package
must instead belong to the actual Binder UID. A manager obtained from a derived
Context can outlive the Context fix, so this mapping belongs in the connectivity
service proxy, not just in the game's cached manager. Match the Android 11
`(Network, String)` or Android 12+ `(Network, String, String)` signature and replace
only the current guest's calling-package argument. Preserve the network,
attribution tag, returned capabilities and system exceptions. Older and unknown
signatures, other callers and explicit foreign packages pass through unchanged.

`ConnectivityCallerIdentityTest` exercises the actual platform service: a Google
package under the host UID is rejected, while the same query with the mapped host
package returns real capabilities. This does not establish completed Google login
on every OEM WebView.

The game and Google Activity processes bind the private `GuestRuntimeService`
before Activity creation. The system can then keep the engine at the importance
of its visible clients, including a standalone Google login opened from settings.
Without this dependency the cached-process freezer can freeze the engine while
the login page is still visible; its next synchronous settings query kills the
frozen server and fails with `DeadObjectException`. Use the existing bound
service, with no permanent foreground notification or global freezer exemption.

## Verification boundaries

Exercise stable and unstable clients, cached acquisition, release, system and
virtual routes, permission failures, derived contexts and missing capabilities.
Use an instrumented fixture that rejects a virtual calling package in the OEM
path while serving the same provider over the real platform route. This proves
the routing contract, not physical-device validation for every vendor ROM.
`ProviderRoutingTest` covers these contracts on Android; `ProviderRoutePolicyTest`
covers authority and caller scope. The `provider-login` cloud inspection also
exercises bundled microG configuration, service capability and its login entry on
Android 17 without an account or purchase. Android 11 runs the framework contract
fixture: its x86 emulator ARM translator fails before the guest Application is
created, so it cannot establish full-container login behavior. Release APK verification rejects
packaged hidden-interface stubs and checks that the resolver overrides survive R8.

References: AOSP [ContentResolver](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentResolver.java),
[ContextImpl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java),
[ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java),
and [ContentProviderHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ContentProviderHelper.java).
