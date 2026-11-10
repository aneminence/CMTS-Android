# Smart LAN Android review / implementation — 2026-11-09

Base: 6b2893f. Existing remote protocol and vehicle sampling remain unchanged.

## Findings and changes
- Existing control broadcast leaked device.status/stream.reset to unauthenticated sockets. Broadcast now requires authentication; direct hello/discover replies remain allowed.
- Added DNS-SD `_cmts._tcp.` via public Android NsdManager, with metadata-only TXT and versioned device.discover. No token, sample or recording state in discovery.
- Added reactive connection panel: service, discovery status, authorized count, subscription count, interface addresses, start/stop. Addresses explicitly require reachability verification; hotspot state remains unknown rather than guessed.
- QR contains versioned connection metadata only. ZXing core 3.5.3 generates QR; Windows scanner remains out of scope.
- Manual pairing and existing tokens remain compatible. Cellular rmnet/ccmni and VPN tun candidates no longer become the preferred LAN address.
- Added 16 simultaneous socket slots including handshakes, 5-second unauthenticated read timeout, 16 KiB HTTP header bound; negative WS frame length rejected.

## Lifecycle and remaining acceptance
- Bridge is process singleton started by Activity, not stopped with Activity. Subscribed remote holds CaptureService separately from local recording. Stopping communication releases only remote hold.
- No new always-on foreground communication service. Idle process can still be reclaimed; reopening app starts listener. Registration failure shown; service restart retries.
- NSD uses generation guard to ignore stale asynchronous registration callbacks.
- Samsung hotspot / no cellular / locked-screen two-hour operation and USB GNSS remain physical acceptance, not unit-test claims.
- TLS and per-client revocable credentials are future scope; existing shared pairing token over ws is retained. Discovery never establishes cryptographic identity.

API reference: https://developer.android.com/develop/connectivity/wifi/use-nsd

## 2026-11-10 model metadata
Discovery, hello, NSD TXT model and QR now carry deviceModel derived from manufacturer/model without changing deviceLabel. Unknown values fall back to Android; duplicate manufacturer prefixes are omitted. This is additive metadata and does not change authentication or sampling.

## 2026-11-10 user-authorized open LAN mode
User explicitly requested no token entry or phone confirmation. device.hello now requires only compatible protocol; discovery advertises authRequired=false and pairingSupported=false. Same-LAN peers can subscribe/control recording after hello. Pre-hello connections still receive no broadcasts or samples. Token UI and pairing-dialog implementation removed; legacy stored tokens are unused compatibility data. SM-N9860 maps to Samsung Galaxy Note20 Ultra 5G.
