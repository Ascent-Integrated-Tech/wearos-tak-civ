# WearTAK-CIV

WearTAK-CIV is a standalone Wear OS application that connects a watch to the Team Awareness Kit (TAK) ecosystem. It is designed to collect location and on-watch sensor data, publish Cursor-on-Target (CoT) events, and provide a watch-native interface for operational awareness and alerts.

The app can connect directly to a TAK server and can optionally use SITX, Ditto mesh, BLE companion, and plugin integrations when those capabilities are configured. A phone is not required for the app's primary watch workflow.

### Received CoT marker symbols

TAK and Companion marker ingress accept generic `a-<affiliation>-...` atom types,
not only the watch's four drop-marker types. Friendly/assumed friend (`f`, `a`),
hostile/suspect/joker/faker (`h`, `s`, `j`, `k`), neutral (`n`), and
unknown/pending/other (`u`, `p`, `o`) use the corresponding affiliation icon and
label; unrecognized affiliations use Unknown. Callsigns remain the map labels.
Existing PLI routing, coordinate bounds, source isolation, and stale/replay
handling are unchanged.

UAV and SPI symbols take precedence over generic frames. Fixed-wing, rotary-wing,
surface/boat, and ground-vehicle hierarchies use the existing platform symbols,
tinted by affiliation in marker lists and details. Cleared markers retain gray
variants. ATOS tag types still select their specific symbols and GPS-off variants
without replacing the packet's affiliation. Friendly ATOS tags retain configured
team colors; nonfriendly tags use affiliation colors.

WEARTAK-280 also accepts spot points (`b-m-p-s-m`) and circle centers (`u-d-c-c`).
Additional atom/spot/shape points use affiliation-colored dots and zoom-dependent
callsign labels (UID when no callsign is supplied); existing drop-marker, UAV,
SPI and user symbols are preserved. Platform symbols remain in marker details
and lists. A circular `<detail><shape><ellipse major="100" minor="100"/></shape></detail>`
draws a geodesic outline with a 100-meter radius. Only equal positive radii up
to 100 km are drawn; other shapes retain their center point.

Generic received map copies are capped at 512 across sources (oldest received
evicted first), expire at sender `stale` or 15 minutes without a refresh,
whichever comes first, and are swept within approximately one second. Their
navigation copies are removed too. Existing user/drop-marker lifetimes are
unchanged. `t-x-d-d` removes the event UID (or a `link relation="none"` target)
only from its originating source, including its navigation/route copy; local
watch-created points and other sources are not deleted. Event-time watermarks
reject delayed resurrection, with bounded 2,048-entry removal histories.
Chat selection/actions require a nonblank `contact endpoint`; a callsign alone
does not advertise chat capability.

Configure Ditto Edge Sync under **Preferences > Tool Preferences > Plugins**. The switch enables or disables Ditto;
tap the Ditto Edge Sync row to select Community or configure a custom PDL in Ditto Edge Sync Identity. The row subtitle shows
Community or the saved custom PDL, truncated to fit. Ditto is off by default on fresh installs, and saved
enablement settings are preserved. PDL Help provides scrollable instructions for copying and pasting a custom identity.
Ditto pauses only while Companion is actually connected. An enabled or reconnecting
Companion service does not block Ditto, and Ditto never blocks Companion startup.
On link loss, enabled Ditto resumes; reconnecting pauses it again without changing
the saved toggle. Multicast fallback behavior is unchanged.
Ditto also requires a valid, unexpired SharedKey PDL (Community identity bundled at
build time via `dittoCommunityIdentity`, or the selected custom PDL). With no PDL,
initialization logs `Invalid PDL` with `Ditto Community license is missing`, shows
an `Invalid PDL` toast, and stops the service before sync starts. Selecting Custom
with an empty PDL does not fall back to Community. Do not commit private identities.

Ditto also syncs direct, group, and All Chat Rooms GeoChat using the `chat`
collection. Received messages use the normal chat inbox and deduplicate against
CoT transports by sender/message ID. Expired mesh messages are ignored and
locally evicted without deleting inbox history. See [the chat wire schema](DITTO_CHAT.md)
for the shared WearOS/iOS document contract and addressing rules.

Plugins are listed alphabetically, including installed plugins. Everywhere, GoTenna and Somewear Labs are
unfunded placeholders: each displays **Unfunded Request**, and their switches remain off without activating any integration.
Installed plugins use split chips: tap the row to open the plugin's launcher activity, or use the switch
to load/unload it. Swipe the row to access remove and info. Plugins without a launchable activity
(or whose activity cannot be opened) reveal those actions on tap instead. Opening an activity does not load the plugin.
Plugin authors can override the menu subtitle by placing this metadata **inside the plugin's
`<service>` element** (the service advertising `com.weartak.plugin.action.PLUGIN_SERVICE`):

```xml
<meta-data
    android:name="com.weartak.plugin.MENU_SUBTITLE"
    android:value="Unfunded Request" />
```

The host reads service metadata without requiring the plugin to be loaded. Application metadata
is not used for this override. Missing, empty, or whitespace-only values retain the loading-status
subtitle. This changes only the menu subtitle: the info popup still shows the actual loading status,
and the toggle, tap-to-open, and swipe remove/info actions are unchanged.

WearTAK Companion is a built-in split chip in Plugins, not Network Preferences,
with the same icon as the ATAK-side WearTAK Companion plugin.
Its switch uses the existing saved enable setting and service policies. Turning
it off stops Companion BLE advertising, pending starts, refreshes, and its GATT
connection; other BLE/beacon settings are independent and unchanged. Tap the
row for connection status, phone-side connection-method guidance, and a link to
normal Android Bluetooth settings. Opening the link does not pair, reset, or
enable Bluetooth automatically. Team Lead EUD Priority is a disabled
**Not available yet** placeholder, not a priority or failover setting.
Bonded Location Diagnostics is also configured on this details page, using its
existing saved preference. It compares watch and phone GNSS quality over a bonded
Companion connection. Developer Preferences retains the diagnostic readout and
export tools, but no longer duplicates the enable switch.
Status distinguishes Off, Bluetooth off/unavailable, missing permissions, waiting,
connecting/disconnecting, ready Connected, and connection errors. Connected means
the companion notification subscription is ready, not just that a Bluetooth bond
exists. The watch does not infer Samsung-versus-Traditional mode from a bond or
device brand; choose the method in the phone companion app.

### Companion BLE discovery (WEARTAK-269)

Legacy advertising keeps the Companion service UUID
`0000A11A-0000-1000-8000-00805F9B34FB` in the primary packet. Neither packet
includes the system Bluetooth name, and WearTAK never renames the device.
The scan response contains service data under the same UUID: a version byte
`0x01`, followed by **at most 12 UTF-8 bytes** of the chosen WearTAK callsign,
truncated at a complete Unicode code point. The 12-byte budget fits the 31-byte
legacy limit even with a full 128-bit service-data UUID (2-byte AD header +
16-byte UUID + 1-byte version + 12-byte callsign). Empty callsigns send only the
version byte. The advertised callsign is always the watch's **standalone**
callsign, including while phone-managed (WEARTAK-273). Standalone callsign changes refresh an active advertisement; no GATT
disconnect or system setting change is needed.

The ATAK Companion scanner on canonical develop
`3240a0d421bc9056676d83dd60b31c1853305250` discovers watches by the service UUID,
not their names. Its existing scan-result display reads the local/device name,
not service data. To display the callsign it must read
`ScanRecord.getServiceData(new ParcelUuid(COMPANION_SERVICE_UUID))`, accept
version 1, strictly decode the remaining bytes as UTF-8, and prefer a nonblank
callsign over device name or address. Missing, malformed, or unsupported-version
data must preserve those fallbacks; primary-only results should not erase a
previously received callsign. This watch-side change does not modify the plugin.

Companion JSON envelopes (`vers: 1`) also expose the full, untruncated standalone
identity as `payload.standaloneCallsign` in `watch_info`, `settings_request`,
and the initial/changed `settings_changed` payload. The existing `watch_info.cs`
and settings `callsign` remain the effective (possibly phone-managed) identity.
For example, while managed, `watch_info.payload` includes
`{"cs":"PHONE","standaloneCallsign":"ODIN-WEARTAK"}` and settings include
`{"callsign":"PHONE","standaloneCallsign":"ODIN-WEARTAK"}` alongside their other
fields. Settings deltas include `standaloneCallsign` only initially or when that
value changes. An empty standalone value stays empty, never the phone's callsign.
The saved watch identity survives repeated managed pushes and is restored on
disconnect; this adds no device-name or bond changes.

Advertising resource/internal failures retry after 1, 2, 4, 8, and 16 seconds
(five retries maximum), with named errors in logcat. Oversized data, unsupported
features, permission failures, and unknown failures stop without retrying.
Success resets the retry budget. After a terminal failure, resolve the cause and
restart Companion; BLE-state notifications cannot bypass the retry/terminal gate.

### Live Companion identity commands (WEARTAK-276)

A phone-managed Companion can update one effective identity field at a time:

```json
{"vers":1,"msgType":"set_callsign","payload":{"callsign":"ALPHA-1"}}
{"vers":1,"msgType":"set_team","payload":{"team":"Red"}}
{"vers":1,"msgType":"set_role","payload":{"role":"Medic"}}
```

Each payload must contain exactly its one string field. These use the same
validation and identity apply logic as chat `setprofile`/per-field commands:
callsigns are trimmed, 1-32 characters (letters, digits, spaces, `.`, `_`, `-`);
team and role names match the configured lists case-insensitively, and a role
also selects its category. Blank, invalid, null, wrong-type, or extra fields
fail without changing anything. Only the current bonded, subscribed peer that
owns an already active phone-managed profile may send them. Session generations,
management release, disconnects, and Gateway mode fence queued writes.

The response is the existing version-1 `settings_set_ack` envelope, with
`payload: {"ok":true}` on success or `payload: {"ok":false,"error":"..."}` on
failure. Standard `msgUid`, `t`, and `src:"wearos"` metadata remain present;
ended/unready sessions cannot receive an ack. Normal `settings_changed` deltas
also report changes. Identity changes refresh runtime display state and request
PLI through the existing identity-refresh path.

Servers, certificates, reporting interval, other preferences, and the saved
standalone identity/snapshot remain untouched. Chat identity commands still
refuse edits while the live phone-management lock is active. `set_identity`
and `setdittopdl` are not added; older watches ignore these new message types.

### Continuous Companion advertising (WEARTAK-270)

Confirmed idle advertising stays in one continuous, connectable **BALANCED**
registration. There is no 30-second refresh or two-minute mode-switch restart.
Stopping and recreating a legacy advertisement can give it a new private address,
making a phone's recently scanned entry stale during selection or bonding.
The former timer was a workaround for apparently silent advertising termination;
it is not a reliable health check and must not restart healthy advertising.

A 30-second watchdog applies only while awaiting the start callback. An unanswered
start is cancelled and uses the same five-retry backoff budget as an internal
failure. Success or `ALREADY_STARTED` cancels the watchdog. Explicit connection
errors/disconnects still recover through the existing GATT lifecycle; changing the
encoded callsign still refreshes the scan response. No device settings or bonds
are changed.

Android controls BLE private-address rotation; this does not pin an address or
promise a permanent hardware identity. Phone scan lists should expire stale
last-seen entries for natural privacy rotation or actual service restarts, rather
than merge watches by callsign (which may be shared or truncated). Legacy
advertising has no reliable callback for a silently terminated confirmed set;
this change deliberately removes speculative periodic recovery in favor of
observed start failures and connection lifecycle events.

### Bonded phone transport (WEARTAK-272)

An active bonded Companion BLE connection carries both inbound and outbound CoT
through the phone only. The transport becomes active after the bonded peer
subscribes to notifications, not merely because a bond or saved phone-managed
profile exists. Direct multicast receive/publish and TAK-server connections stop;
late direct packets and queued TAK writes are gated before delivery. SITX and
Ditto pause without changing their saved enable preferences, and direct plugin
CoT is excluded while the bonded phone transport is active. BLE CoT continues
through its existing authenticated receive path.

Notification unsubscribe, disconnect, bond loss, connection error, or Companion
shutdown immediately clears the runtime transport gate. Configured direct
networking then resumes automatically, even when the phone remains bonded or
phone-managed settings remain saved. Reconnecting and subscribing switches back
to phone-only routing. Standalone route selection and configured service
preferences are unchanged; this does not unpair or rename any device.

Commands uses original WearTAK megaphone artwork (`ic_commands_megaphone.xml`), not a vendor mark.
Commands and Data Sync display **Unfunded Request** in the plugin menu; their navigation and Commands toggle behavior are unchanged.
Opening Commands shows informational, wrapping command examples beneath Allowed Callsigns in the scrollable settings list.
The six integration rows use the artwork below, fitted proportionally into 24 dp without theme tint.
The user confirmed permission to use these identified assets on 2026-10-21; this is user-confirmed
permission, not a claim of a public redistribution license. Only Ditto is the exact ATAK plugin icon;
the other marks are approved official-company backups, not claimed as ATAK plugin artwork.

| Integration | Artwork and provenance | Preparation |
| --- | --- | --- |
| Somewear Labs | [Official 192 px symbol](https://somewearlabs.com/wp-content/uploads/2026/07/cropped-somewear-favicon-rounded-corners-192x192.png) | Unchanged PNG, `ic_integration_somewear.png`. |
| GoTenna | [Official original favicon](https://gotenna.com/cdn/shop/files/favicon.webp?v=1680207627) | Native 100 x 100 WebP, requested with `Accept: image/webp`; no enlargement. `ic_integration_gotenna.webp`. |
| Everywhere | [Official white logo](https://everywherecomms.com/cdn/shop/files/everywhere_logo_white.png?v=1727986047) | Left 232 x 232 globe crop from the 1517 x 232 logo, proportionally reduced to 192 px; no redrawing. `ic_integration_everywhere.png`. |
| DART and FALCON | [Official Certus Innovations symbol](https://images.squarespace-cdn.com/content/v1/687cf63b5a78985697612a02/8c2c76c6-79ac-4cf4-8dca-a3e9f8f0293c/CI-Icon-Arrow_white%2Bteal.png) | 1920 px artwork proportionally reduced to 192 px, retaining white/teal and transparency. Both use `ic_integration_certus.png`; labels distinguish the products. Product identity: [DART](https://www.certusinnovations.com/products/dart), [FALCON](https://www.certusinnovations.com/products/falcon). |
| Ditto Edge Sync | Installed `com.dittofederal.atak.plugin.ditto` APK, `drawable/ic_ditto_menu` (`0x7f07006b`), compiled `res/AC.xml` | `ic_integration_ditto.xml` preserves all four original paths and `#F6F6F6` fills. Transparent margins trimmed from the 376 x 416 viewport to the artwork bounds (284 x 324), with proportional intrinsic size. |

Sensor Manager displays **Unfunded Request** and has a saved enable/disable preference (off by default). Tap its row to open the preview
configuration showing **Unfunded Request**, **Button is Placeholder**, and a single inert **Scan** button.
The future workflow will list discovered sensors with per-sensor Pair controls and change the main action
to Unpair once paired. Enabling Sensor Manager does not start scanning or connect hardware yet.

## What it does

- Connects to TAK endpoints and sends and receives CoT traffic.
- Publishes position/location information (PLI), markers, SOS/manual alerts, sensor-driven alerts, and TAK chat payloads.
- Collects health, activity, and environmental data, including heart rate, location, activity, skin temperature (where supported), BLE beacons, pressure, and off-body state.
- Provides Wear OS activities, a Tile, and watch-face complications for common operations and status.
- Supports managed configuration, allowing an enterprise mobility/MDM provider to apply settings and service enablement.
- Includes a configurable complex-event-processing (CEP) engine for evaluating sensor/event streams and dispatching alerts.
- Provides optional audio detection pipelines for gunshot and UAS events, and optional CRM inference.
- Hosts supported WearTAK plugins through the included plugin SDK contract.

Some capabilities depend on hardware, permissions, credentials, configuration, and separately provisioned services or SDKs. In particular, mesh, companion, SITX, plugin, audio-detection, and managed-configuration features are optional.

### Ditto receive support and deletion boundary

With Ditto running, SDK **4.10.2** store observers consume initial local snapshots
(including reconnect/restart snapshots) and subsequent changes, not just sync
subscriptions. They reconstruct CoT from the v2 aliases already written by this
watch and use `TakServerCotIngestor` and the existing application handlers:

| Collection | Supported data | Application destination |
| --- | --- | --- |
| `track` | Supported PLI types | Source-aware received-user map state |
| `mapitem` | Supported PLI, GeoChat, emergency, point and `b-m-r` route CoT | Map, ChatService inbox, remote alerts/Bloodhound, route repository/UI |
| `alert` | Supported emergency CoT, including cancellation | Existing remote-alert/Bloodhound handler |

The supported document shape is `_v=2`, `_r=false`, a string `_id` equal to `d`
(event UID), string `a` (peer key), `g` (CoT version), `w` (CoT type), `p` (how),
`q` (access), and `r` (one complete `<detail>...</detail>` element). `AuthorId`
is optional. `b`, `n`, `o` are integral epoch milliseconds (time, start, stale);
`h`, `i`, `j`, `k`, `l` are finite numeric CE, HAE, latitude, LE, longitude.
Latitude/longitude must be in range. Unsupported versions, types, raw-XML-only
documents, missing aliases, malformed XML, DTDs/entities, and invalid timestamps
are rejected with diagnostics; there is no fallback parser. Compatibility with
other producers is limited to this shape, not a claim of general ATAK Ditto support.

Maximum reconstructed CoT is **256 KiB UTF-8**; maximum snapshot is **4,096
documents** and **2,097,152 detail characters** per collection. An oversized
snapshot is rejected as a whole. SDK next-snapshot signalling supplies
backpressure while ingestion is serialized. Native results and observer handles
are closed, queued work is cancelled on stop, and late callbacks are fenced.
An exception in one document does not discard other valid documents.

Live events over five minutes in the future are rejected. A private replay journal
stores hashed peer/UID keys, event times, stale times and SHA-256 payload digests,
not message bodies. It suppresses older revisions, repeated snapshots and
track/mapitem duplicates from the same peer; conflicting equal-time revisions
are first-accepted-wins. Expired input cannot introduce live points/routes.
Receiving an expired revision removes only the matching source's copy, never
another transport's copy. Routes expire at their original CoT stale time.
At restart, equal current live revisions can rehydrate the repositories; already
recorded chat documents are not re-notified or used as historical inbox restoration.
The journal holds **8,192 identities**; entries whose previously accepted
lifetimes have all expired are pruned when full,
otherwise new identities are rejected with diagnostics rather than silently
evicting replay protection. No exactly-once delivery guarantee is implied.

Replay retention covers **observed** lifetimes, not arbitrary unseen revisions.
After an identity's journal entry is pruned, an older still-synced PLI/route
document with an unobserved, longer stale time can be accepted again after
restart. Ordinary expired documents cannot become live again. Point ordering
and terminal deletion use the separate non-pruning ledger described below;
the time-bounded receive journal does not authorize deletion.

Repository/event delivery happens **before** the replay-journal commit. A failed
commit emits an explicit diagnostic but does not roll back already delivered
state; later documents continue. A crash before commit can replay the event
(the ChatService inbox merge still deduplicates by message identity when that
inbox survives). A crash after commit can rehydrate equal live point/PLI/route
state from the next snapshot, but recorded chat is suppressed rather than
restored into a fresh in-memory inbox. There is no atomic durable inbox/journal
transaction. Lint's `UseKtx` suggestion is style-only; explicit `commit()` and
its checked Boolean result are intentional.

Recipient/group membership, self-chat filtering and chat enablement use the shared
chat parser/settings. Local peer/author/UID echoes and locally owned/deleted points
are suppressed. Receive callbacks do not dispatch or relay inbound CoT to TAK,
companion, Ditto, multicast or plugins. This is intentionally **data-only**:
command text can appear as ordinary addressed chat, but no command runtime,
configuration/plugin control, geofence, channel-change or data-package handler is
installed. An active Companion connection pauses direct Ditto; a reconnecting
Companion service alone does not.

Across TAK server, multicast and Ditto ingress, PLI and point repositories retain
each source-owned copy but expose one logical item per event UID. Newer event
times win; an older arrival cannot replace a newer visible copy. Losing a source
can reveal another source's copy without creating a second entity. Source
metadata may legitimately change; secondary ingress is not discarded.
Bloodhound registers those copies without emitting a second unread/notification
event for the same UID and event time. Emergency cancellation remains
UID/version scoped across sources, so a delayed active revision cannot undo it.

GeoChat history uses sender UID plus `messageId`, independent of transport.
The same message produces one history entry/unread message; distinct message IDs
remain distinct even when their text and timestamps match. Legacy chat without
an ID retains the existing sender/time/text fallback and its inherent ambiguity.
These paths do not add a new operating-system notification mechanism.

CoT routes with an event `time` retain their source-owned copies and expose the
newest revision per UID. Identical copies do not duplicate route rows, and
identical-source fallback preserves active route navigation. Mission bookkeeping
still sees every source copy. Legacy routes lacking an event time keep their
existing source-specific identity rather than guessing a revision from geometry;
they cannot provide the same cross-transport version guarantee. Producers must
preserve the original UID/message ID and advance event time for updates.
Different UIDs are not merged merely because coordinates or text match. This
prevents local duplicate presentation, not loops or rewritten identities
introduced by external peers.

### WearTAK own-created-point deletion

**Trust model:** all members provisioned with the shared Ditto mission key are
trusted not to forge point ownership or deletion fields. `AuthorId`, peer alias
`a`, and revision hashes are writable data, **not authenticated individual
authorship**. A malicious provisioned writer can forge them. This is a
WearTAK-to-WearTAK cooperative contract, not an ATAK deletion interoperability
claim or hostile-peer protection.

The watch records local drop creation before adding the point to application
state; imports and received points cannot acquire local publication ownership by
claiming `AuthorId`. A supported point publication records its exact document
and revision before writing to Ditto. Local clear persists a terminal intent
before local removal, then the running Ditto service retries its pending store
write every ten seconds, including after service/process restart. Clearing an
unpublished or pre-ledger point remains local/eligible-companion only. There is
no new remote-delivery success status: a completed local clear or SDK store write
does not prove any peer received it. Existing companion-over-Ditto precedence
can defer mesh publication until Ditto runs.

The original `mapitem` keeps its `_id`, `d`, `a`, `AuthorId`, base timing,
coordinates, type and detail aliases. Live WearTAK-owned points add:

```json
"weartakPoint": {
  "version": 1,
  "owner": "<AuthorId>",
  "revision": "<SHA-256 of reconstructed live CoT>",
  "deleted": false
}
```

Clear updates **that same document** to `_r=true`, `visible=false`, `f=false`,
sets `weartakPoint.deleted=true`, and adds integral epoch-millisecond
`deletedAt >= b`. The revision still identifies the original live payload;
`deletedAt` is informational, not an ordering override. A tombstone can arrive
before its live revision, and remains meaningful after the original stale time.
Plain `_r=true` without this validated contract, hidden documents, query
disappearance, local eviction, and CoT `t-x-d-d` do not authorize deletion.
No SDK document removal, eviction or collection delete is used.

Receivers pin source/UID ownership and event-time/revision ordering before
mutating repositories. A valid tombstone removes only the matching Ditto
map/Bloodhound source copy, never watch-local, another peer's, TAK, multicast or
companion copies. Cross-owner, older and conflicting equal-time revisions are
rejected. Legacy points without an author cannot later be upgraded into
author-owned deletion authority. A deleted source/UID is **terminal**: repeated
deletion is idempotent; even a newer live revision cannot resurrect it. Recreating
a deleted point requires a new UID. Local origins are also never reused, and
pending writes are not replayed into a different native local-peer store.

Local ownership/pending publication records use app-private
`ditto-local-points-v1` preferences, including the retained point payload.
Limits are **4,096 local origins**, **384 KiB per serialized record**, and
**8 MiB total**. `ditto-point-watermarks-v1` holds **8,192 source/UID records**,
**8 MiB total**, separately from ordinary receive dedup. Neither point ledger
prunes or evicts records. Capacity exhaustion explicitly rejects new drops or
new inbound point identities rather than losing ownership/replay protection.
Deleting an already recorded point does not require another identity slot.

Checked persistence failures are logged and fail closed: failed local intent
keeps the point/clear queue, and failed inbound watermark storage cannot remove
the repository copy. Failed store writes or write-ack persistence keep a pending
publication for idempotent retry. An unacknowledged in-memory preference change
is repaired before another successful ledger write. A crash after durable
tombstone storage but before repository mutation is completed by replaying the
retained tombstone snapshot; a crash after store write but before acknowledgment
may repeat the same write. There is no atomic SDK/preferences/repository
transaction. The existing local dropped-marker UI list itself remains
in-memory; these ledgers provide ownership and publication recovery, not a new
point-history restoration UI. Clearing app storage or a failed storage device
loses these guarantees.

Local observer tests use mocked SDK callbacks and synthetic data. Native Android
SDK/peer interoperability and over-the-air receipt still require a separate,
authorized hardware check; passing these tests does not prove mesh delivery.

### PLI metadata compatibility

Watch-built PLI XML and Ditto track details include `takv`, `status`, and `__group`
as immediate children of `detail`. `takv` identifies WearTAK using the installed
app's build version, Android SDK level, and actual watch manufacturer/model, not
phone or ATAK metadata. Callsign, UID, team, role, and device strings are XML-escaped.
Structured `status battery` is emitted only for an available percentage in 0..100;
existing remarks and biometrics are retained.

Direct XML routes and the negotiated raw-XML companion gateway preserve those
fields. The legacy companion PLI JSON envelope is unchanged: it includes battery
and callsign but not team, role, or `takv`. Separate legacy settings snapshots and
`settings_changed` deltas carry team/role. The initial delta includes both; later
deltas include only changed fields, including team-only or role-only changes,
without changing the envelope version or notification queue.
Legacy phone-side PLI reconstruction still uses the phone/EUD identity and location;
these settings messages do not make it watch-source PLI or supply watch `takv`.
Companion observation/conversion requires a separate phone-side update. Passing
local tests does not establish phone consumption or server/ATAK receipt.

### WEARTAK-121: Bloodhound to a map user

Tap a user on the map, then **Start Bloodhound** immediately below **Open Chat** to
start the existing Bloodhound action and return to the map. The action uses the
selected user's UID and location; subsequent PLI from that same source refreshes
the target while preserving tracking state. Users without a valid position cannot
be selected for Bloodhound. If the user disappears, the last known target remains
until stopped or removed through the existing Bloodhound controls. This action is
also available on the map opened from Chat: it starts Bloodhound and returns to
that map without selecting a chat contact or sending a message. **Open Chat**
retains the chat picker's existing contact-selection behavior.
The user menu uses the standard Wear scrolling list and position indicator so both
actions remain reachable on round screens. The ordinary Bloodhound detail action
also reads **Start Bloodhound** instead of **RGR**; acknowledgement and stop behavior
are unchanged.

## GitHub pipeline release

Protected `develop` push pipelines can publish the signed WearOS APK to the rolling
**TAK.gov Forge CI/CD Pipeline Release** in the GitHub mirror. Project policy treats these
as stable builds, not prereleases. See [CI release setup and retention](ci/README.md)
for required administrator configuration, provenance, and verified replacement behavior.
Other numbered releases and unrelated assets are preserved.

## Platform and requirements

### WEARTAK-169: Bluetooth name is no longer changed

WearTAK no longer changes the watch's system Bluetooth name at startup, when applying
a callsign from the phone, or when restoring the watch's own settings. Companion BLE
discovery still uses the A11A service UUID, and advertising still includes the current
system device name; no `WT-` prefix is required. Bluetooth connection permissions remain
necessary for GATT and bond handling.

Callsigns are exchanged independently in `watch_info.payload.cs`, not inferred from
the system Bluetooth name.

An existing name such as `WT-ODIN-ATAK` is not automatically restored because the original
name is unknown. Rename the watch manually in watch Settings if desired.

### Companion bond retention

Transient missing-key, failed BLE setup, and pre-session disconnect retries retain
the Android system bond and the matching phone-managed settings. The watch never
invokes `removeBond` during a retry. An active-peer missing-key event resets transport
readiness, RX/queued work, and session state before advertising again; unrelated-peer
events cannot reset the current session. Retained pairing is not message authorization:
current-peer and bonded-device checks still reject stale/unbonded writes.

A genuine Android `BOND_NONE` transition releases management only through the
repository's matching-peer operation; an active unpair also resets BLE. Startup still
checks whether the managing peer is actually bonded. This does not change one-time
provisioning, explicit ongoing management opt-in, chat, marker deletion, or watch UI.

`CompanionBondRetentionTest` exercises transient retry versus explicit unpair,
management retention, unrelated-peer isolation, and stale/unbonded write rejection.
Paired-hardware missing-key recovery, repeated watch restart, and system unpair
acceptance remain pending. Do not install a feature APK before this change lands
in canonical `gov/develop`.

Validation (2026-10-12, base `702ee4a`): offline `:app:testDebugUnitTest`
with `*Companion*`, `*PhoneManagedSettings*`, `*CompanionChat*`, and
`*CompanionPoint*` selectors passed **75 tests**, including **6 new bond-retention
tests**, with no failures. Offline `:app:assembleDebug` passed. The APK was not
installed; paired hardware acceptance is pending.

### WEARTAK-160: Single-watch phone-user map relay

The single-watch companion link relays remote user PLI in
`phone_user_cot` payloads `{cot: XML, phoneUid: nonblank}`. A
`phone_user_remove` payload `{targetUid, phoneUid}` removes only the matching
companion-owned map copy. WearOS stores this copy separately
from network CoT with the same UID, expires stale positions, clears the
companion snapshot when its BLE generation ends, and caps it at 512 users.
Only live `a-*` PLI with a TAK user identity is accepted; dropped points,
emergencies, and the watch or phone's own identity are rejected.

This is a single-module Wear OS app (`:app`) with package ID `com.aitech.weartak_civ`.

- **Android API levels:** min SDK 34, target SDK 34, compile SDK 35
- **Java:** JDK 17
- **Device:** a Wear OS watch with GPS; BLE and telephony are optional
- **Target hardware:** Samsung Galaxy Watch 7/8 and Galaxy Watch Ultra 7/8 are the intended hardware family. Samsung-specific sensor features require compatible Samsung hardware and services.
- **Native libraries:** debug APKs are built for `armeabi-v7a`.

The project includes local binary dependencies for the Samsung Health Sensor API, HAIL SDK, and WearTAK plugin SDK. Keep these files available when building locally.

## Getting started

1. Install Android Studio and an Android SDK that includes API 35, build-tools 35.0.0, and the Android NDK 27.0.12077973.
2. Configure JDK 17 for Gradle.
3. If required for your environment, create `signing.properties` with the debug/release keystore values expected by `app/build.gradle`. The build also accepts the corresponding `WEARTAK_*` environment variables.
4. Build a debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   ```

5. Install the generated `armeabi-v7a` APK on a connected watch:

   ```bash
   adb install -r app/build/outputs/apk/debug/*armeabi-v7a-debug.apk
   ```

6. Launch the app:

   ```bash
   adb shell am start -n com.aitech.weartak_civ/.presentation.MainActivity
   ```

The app requests permissions at runtime. On a physical device, grant only the permissions required for the services you intend to enable (for example, location, body sensors, activity recognition, notifications, Bluetooth, and microphone access).

### Bonded Location Diagnostics (WEARTAK-165)

The **Bonded Location Diagnostics** switch is in Developer Preferences and is persisted independently of Developer Mode. Diagnostics run only after a versioned capability exchange over the authenticated, bonded, ready single-watch Companion BLE session; setup and gateway routes do not enable observation. Disabling the switch sends a session-scoped stop so both devices unregister diagnostics-only callbacks. An unsupported Companion peer receives no repeated capability probes, and a new connection negotiates a new session.

The watch observes fixes already delivered to WearTAK and registers GNSS status/raw-measurement callbacks only for an accepted diagnostics session. It does not request an additional location stream, change the authoritative PLI, or publish diagnostic data as CoT. The phone observes passive platform fixes plus GNSS callbacks; it does not take over ATAK location authority. Permission failures, disabled providers, inactive receivers, and unsupported callback data remain visible as Unavailable or Unknown rather than being treated as good. Quality states are conservative and are not a determination of jamming or spoofing.

The Developer Preferences diagnostics page shows watch and phone quality separately and provides diagnostic-only Entry/Exit markers, JSON export, and Clear. Local daily summaries retain no exact coordinates, device identifiers, or raw satellite identifiers; logs are bounded to 256 KiB per day and seven days of retention. Diagnostic messages are versioned, session-fenced, size-limited, rate-limited, and best-effort behind operational BLE traffic.

## Configuration and operation

The legacy `SettingsActivity` component redirects to `WearTAKSettingsActivity`.
Debug tools are a screen within settings, not a separately launchable activity.
ATOS CoT IPC authorizes the caller on every Binder transaction, before processing
the payload; service binding itself does not establish a trusted caller identity.

### Route point selection

TAK-server Data Package offers (`b-f-t-r`) can import routes from version 2
Mission Package ZIPs. WearTAK downloads the content hash from the **source server's
configured HTTPS host on port 8443**, using that server's client certificate and
certificate/hostname verification. Advertised hosts and redirects are never followed.
Non-route package content is not supported.
Offers are bound to the receiving TLS socket's current connection attempt, not
its asynchronously updated display status. Replaced/disabled/disconnected
sessions cannot borrow a new connection's credentials. A reconnect or busy importer
is reported as an operational import failure, not malformed sender XML.
Only manifest-listed, non-ignored `b-m-r` CoT entries are imported, through the
existing route parser; package commands and import/delete instructions are not executed.
Downloads are limited to 8 MiB, expanded archives to 16 MiB, individual entries to
4 MiB (manifest: 256 KiB), 256 entries, and 10,000 total route points.
SHA-256 and advertised size must match before import. Unsafe paths, DTDs/entities,
missing content and invalid routes reject the package without importing partial routes.
One package is processed at a time without blocking incoming CoT; failed imports
are logged and show a watch toast. Resend after an import failure.
When requested, transfer success/failure acknowledgements are routed back to the
sender through the source TAK server; success is sent only after route import.
Received routes with a valid CoT `stale` are stored on the watch and recovered
after process restart, including force-stop/relaunch, without a resend. Original
timestamps, source/mission ownership, method and checkpoint/control geometry are
preserved. Expired routes are not recovered; live routes also expire at their
original stale time. Legacy routes without `stale` remain memory-only rather
than being retained indefinitely. Source-scoped CoT delete watermarks survive
restart and reject older/equal replays. Local remove/remove-all and mission
removal also update the persisted catalog; restart alone cannot restore them.

Ordinary route geometry CoT (`b-m-r`) received on the configured Wi-Fi multicast
subscriptions uses the same route store, complete geometry and point actions as
server/Data Sync routes. A Mission Package ZIP is not raw route CoT over UDP:
its local `b-f-t-r` offer uses a separate source-bound peer download. Only a private
IPv4 UDP sender on the receiving Wi-Fi link, HTTPS port 8443 and `/getfile` with
exactly one numeric `file` ID and one `sender` parameter are accepted (CommonCommo
`MissionPackageManager::getLocalUrl`). The offered host must exactly match the UDP
sender. Downloads are bound to that Wi-Fi network/session with no proxy or redirects;
server credentials/client certificates are never used. ATAK serves `/getfile` with a
self-signed certificate that does not name the phone's IP, so the peer download accepts
the phone's certificate (HTTPS only, hostname must equal the UDP sender IP, no HTTP
fallback). Integrity comes from the offer's mandatory SHA-256 and size, which must match
the downloaded bytes before any route is imported. TAK server connections keep full
platform/enrolled-CA trust and hostname validation.
Peer imports share the same ZIP/hash/size bounds and route UI; identities are
scoped to the multicast source and peer IP. Peer ACK routing is not yet supported,
so a sender may report a timeout even after a verified watch import.
Live peer ZIP interoperability is not yet accepted; an observed local offer alone
does not establish successful HTTPS download or route import.
Debug multicast diagnostics record only the peer IP, offer URL scheme/port/path
host/source equality, query key names/count, and hash/size presence, not the full
offer, credentials, query values or hash values.

Tap a route line to view the whole route and select any of its points (including
unnamed vertices) for the existing point details and Bloodhound action. **Remove
from watch** requires confirmation and removes the selected route from local map
state, never the server copy. Versioned CoT copies of the same route UID are
removed together so an older transport copy cannot immediately restore the row;
legacy unversioned routes retain source ownership.
A later resend or Data Sync update can restore a locally removed route. Existing
single-point Bloodhound targets remain until stopped or removed using Bloodhound controls.

The Bloodhound picker also lists each received **whole route** once, with its total
point count and distance to the nearest vertex when a location fix is available.
Open a route and choose **Start nearest** (requires a location fix) or **Start from
beginning**. Standalone navigation now targets checkpoints (`b-m-p-w`), preserving
control vertices (`b-m-p-c`, also the default for links without a type) in route
geometry/distance. The final vertex remains the destination, including legacy
routes without typed checkpoints. Start nearest selects the next checkpoint
ahead of the closest projected position along the route, rather than the nearest
raw vertex. Arrival radii follow ATAK defaults: **Walking/Swimming 10 m, Driving
20 m, Watercraft 100 m, Flying 1000 m**; missing/unknown method defaults to Walking.
The current GNSS horizontal accuracy is added to that radius when available.
A nonterminal checkpoint must have been reached within the bubble and then
passed along the route projection before advancing. Reaching the final vertex's
arrival bubble completes the route. There are no bearing or timeout rules.
This replaces the old fixed-20-m/every-vertex behavior. The route
screen shows **Point X of N**, remaining distance along the route, the existing
Bloodhound compass, and **Stop route**. Reaching the final point shows **Route
complete** and clears the active target. Starting another Bloodhound stops route
following; removing the route from the watch also stops it. A received geometry
change stops following so an old point index cannot target a different path.
Standalone following sends no CoT/chat/acknowledgement to the sender. An active
standalone route with an unexpired persisted copy resumes its start mode,
checkpoint index and already-reached checkpoint set after restart. Range/bearing
wait for a fresh location fix. Recovery stays local and does not send a new
navigation start to ATAK on reconnection; explicitly starting a route again uses
the normal Companion handoff. Stop, completion, switching targets, removal,
expiry or changed geometry prevent recovery. Phone-owned navigation is never
auto-resumed: a fresh ready Companion snapshot remains authoritative.

Picker rows have small map, start/stop, and remove icons in a single vertical
column with separate 40 dp touch targets. Remove is local to the watch; **Remove
all** clears whole routes as well as marker/alert targets and any completed route
session. Routes can also be removed from their Bloodhound details screen. The
main-screen Bloodhound shortcut is hidden while a target is active.

Bonded, ready, single-watch Companion sessions accept `phone_route_navigation`
JSON envelopes. Version 1 payloads contain `sessionId`, monotonically increasing
`sequence`, `action` (`start`, `progress`, `stop`), and `routeUid`. Start/progress
also contain the full `b-m-r` XML in `routeCot` and a zero-based
`targetPointIndex` over every raw geometry vertex (including unnamed points).
The geometry UID must match, and malformed vertices or out-of-range indices
reject the snapshot rather than shifting its index. Stop optionally contains
`completed: true` when ATAK reports final arrival.

For phone-owned navigation, the watch follows exactly the phone's index; its
own location updates only range/bearing and remaining distance, not progression.
ATAK decides advancement and completion using its own arrival settings. A phone
completion clears the active target and retains the completed route details;
manual phone stop does not claim completion. Duplicate/older sequences and
ended sessions are ignored (bounded 128-session history). Disconnect clears the
active phone target; a fresh snapshot with a higher sequence may resume it.
Explicit watch stop, delete, Remove all, or switching targets sends a
session-scoped stop to the bonded phone and suppresses further snapshots for that
session. Removal never deletes the phone/server route geometry.

Watch-started routes on a ready bonded connection send `watch_route_navigation`
with version 1, `requestId`, proposed `sessionId`, `action: start`, `routeUid`,
`targetPointIndex`, and full `routeCot`. The phone validates/imports missing
geometry locally and starts native ATAK navigation; existing incompatible
geometry is rejected. Native snapshots use that same session ID. Watch-side
export retains raw order and marks the fallback final destination as a checkpoint
so a watch-only legacy route can be imported as navigable geometry. Watch-side
advancement pauses during takeover. `route_navigation_ack` includes `requestId`,
`sessionId`, `ok`, and optional `error`; a rejection or missing native snapshot
after **8 seconds** reports a sync failure and resumes local navigation without
an automatic retry loop. A timeout also sends a scoped stop to cancel a late
phone start. Stop uses the same message type with `action: stop`, a fresh
request ID, the session ID, and route UID. There is no competing watch progress
loop while ATAK owns navigation. Disconnect before takeover resumes local
navigation; disconnect after takeover clears the phone-owned active target.

Standalone rules are ATAK-aligned, not a reimplementation of its full engine:
the watch uses nearest-segment equirectangular projection with geodesic segment
lengths, not ATAK's spatial SQL engine. It does not share phone
`waypointBubble.<method>` preference overrides, navigation-cue-triggered
departure, engine-dirty/off-route reselection, or ATAK's navigation-loop cadence.
Bonded operation delegates these decisions to ATAK and therefore uses the native
phone's exact objective and completion events.

Route lines and named point markers remain visible on the map. Selecting a point
uses the standard marker range/bearing display, Bloodhound/In Position action,
and Back control. **Whole route** returns from point details to route removal
and the named point list. The list excludes blank names but keeps original point
indices; its count distinguishes named entries from all geometry points. Routes
without named points show an empty-state explanation; unnamed points remain
selectable on the map. Route taps have a 12 dp tolerance; individual vertex
selection is available above zoom 10, including unnamed vertices. Only nonblank
waypoint names produce map dots/labels, avoiding thousands of empty track markers
without dropping any line geometry. Hit testing runs on taps, not on each frame.
Selection and sender lookup use both the source server ID and route UID,
so matching route UIDs received from different servers cannot select each other's
points. Route parsing does not log raw CoT XML.

### Companion provisioning and optional phone management (WEARTAK-90)

The companion defaults to one-time provisioning: edit the watch callsign and server
settings, select a `.p12` certificate if needed, and tap **Send to Watch**. Wait for
the watch's successful save acknowledgement before disconnecting. Certificates and
server settings remain stored on the watch; a phone is not required afterwards.
One-time uploads do not lock watch settings or copy the phone's team and role.

**Manage watch settings from this phone** is opt-in. It copies phone identity and
resynchronizes on reconnect, with watch identity, reporting, and server edits locked.
The editing lock requires a live, successfully managed Companion session. Application-level
heartbeats run every 10 seconds; after 30 seconds without a matching response, the watch
unlocks local edits and resets the Companion transport, even if Samsung retains the shared
Bluetooth connection after ATAK exits or crashes. This detects loss of responsiveness,
not the cause of the outage. The saved management opt-in is retained, while the watch's
own identity, reporting, servers, and certificates are restored (WEARTAK-168).
A fresh successful managed-settings handshake re-applies the phone's values and restores
the lock; merely reconnecting or receiving an old heartbeat does not. Watch process/service
restart also requires a fresh handshake; if none arrives within 30 seconds, the watch
restores its own values. Older Companion builds without heartbeat support
cannot maintain the lock and must be updated alongside the watch. To return to
one-time provisioning, uncheck the option and send settings again; this releases
the lock, restores the watch's own values, and then applies the one-time upload, whose
servers and certificates stay. Unpairing also releases the lock and restores the watch's
own values.

### WEARTAK-161: Explicitly releasing phone management

Turning off **Companion App** in watch settings first clears the phone-managed
settings lock for its recorded companion peer, then disables the companion service.
Since WEARTAK-168, this stop action restores the watch's own callsign, team, role,
reporting settings, TAK servers, and certificates. A transient BLE
disconnect retains the editing lock only for the heartbeat grace period; service restart
requires a new handshake. Android unpairing also releases management and restores
the values saved before management began.
The release transaction serializes settings writes and fences managed updates for
that BLE generation, so a delayed settings push cannot relock the watch before its
release acknowledgement; management can be enabled again after reconnecting.
Settings snapshots, acknowledgements, and queued notifications retain their original
peer and connection generation. Work from an ended connection is discarded rather
than delivered to a replacement session. Management activation rechecks its session
and release fence inside the DataStore transaction, including after server refresh.
An accepted notification subscription starts a new logical companion generation:
Samsung may keep the shared physical Bluetooth link alive while ATAK closes and
reopens its GATT client. Reconnect therefore does not depend on a physical
disconnect callback to clear the previous session's management-release fence.

### WEARTAK-168: Restoring watch settings after phone management

This applies to the Companion's **Bond Single Watch** mode (managed settings). Before the
phone first overwrites the watch, the watch saves its own values for every setting the phone
writes: callsign, team (and team color), role, role category, the dynamic reporting strategy,
the constant reporting interval, and the TAK server list including `.p12` certificates and
passwords. Identity and reporting values are saved in DataStore. The server list is saved in
the same encrypted TAK server store as the live list; only a non-secret "snapshot saved" marker
is kept in DataStore. The snapshot survives a process restart, and repeated phone pushes during
management never overwrite it. Stationary, on-foot, vehicle, and alerting intervals are locked
but never written by the phone, so they are not changed.

**Configure Single Watch** (one-time upload) does not lock or snapshot anything; its servers,
certificates, and other settings stay on the watch. If a one-time upload arrives from the
bonded phone, the watch first restores its saved values and ends the bond, then applies the
upload, so the configured settings stick.

The saved values are restored without changing the system Bluetooth name, the TAK service
is asked to reconnect with the restored servers, and a PLI refresh is requested when any
of these happen:
- the phone releases management or sends a one-time (unmanaged) upload;
- **Companion App** is turned off on the watch;
- the managing phone is unpaired;
- the 30-second heartbeat lease expires, or no managed handshake arrives within
  30 seconds of a service restart.

A disconnect shorter than the lease grace keeps the phone's values, so the identity does
not switch back and forth. Releasing management, turning off Companion App, and unpairing
also clear the management opt-in. Lease expiry keeps the opt-in, so a later managed handshake
saves the watch values again and re-applies the phone's values.

Watch edits are blocked while the lock is active. If the user changes one of these settings
while management is enabled but unlocked (for example, during the restart grace), the edit
also updates the saved value, so it is not lost on restore. The same applies to TAK server
edits. Locked settings are labeled **Bonded** (previously "Phone managed").

If a managed handshake saves the servers but then fails, the saved servers are restored once
the watch is no longer locked. Installs whose bond began before identity or server snapshots
existed restore only what they saved: reporting and the current identity and servers stay.

### WEARTAK-66: Interaction-triggered PLI refresh and identity updates

Changing callsign, team, or role (but not saving the same value), opening/resuming or
recentering the map, opening own-position/distance details, sending chat, dropping/updating a point at self, starting Bloodhound
or route navigation, and activating or clearing manual/physiological alerts request a new
watch location. Actions do not wait for GPS: existing coordinates are used immediately
where available. Explicit-coordinate point drops do not move when the watch moves.
A self-point receives one same-UID location update if a qualifying fix arrives within
5 seconds; deleted points are never recreated. Map opening/recentering follows that fix
without overriding an explicit target/route camera focus. Navigation vectors follow the
normal location flow.

Identity changes publish promptly using the best available valid fix while requesting a
new one. An old fix keeps its timestamp in `weartak_location@timeMillis`; the identity
announcement itself has a current event time so servers do not discard it as expired.
Settings saved together coalesce over 150
milliseconds. PLI contains the latest persisted callsign, role and team. Without any fix,
identity PLI waits for the first usable position rather than inventing coordinates.

Overlapping interactions share a high-accuracy acquisition bounded to 5 seconds. The fix
must be usable and measured after the latest interaction; cached coordinates are not
re-stamped as a fresh fix. PLI uses persisted identity and is sent as soon as a qualifying
fix arrives, not literally instantly. Missing permission, provider failure, or no fix ends
the refresh without sending an extra PLI. This does not change periodic reporting,
reconnect behavior, or enable services/transports that are disabled.

Manual and physiological emergencies send immediately with the newest available valid
fix, even when it is older than the former 3-second cutoff. The last valid fix is retained
in memory against invalid/out-of-order callbacks. The original fix time is exposed as
`detail/weartak_location@timeMillis` (Unix milliseconds) and remains unchanged in the
Companion snapshot. For a fix older than 3 seconds, CE grows by at least 5 metres per
second (or the recorded speed if greater), capped at the unknown-error sentinel
9,999,999 metres; missing/invalid accuracy is unknown, never zero. This is a conservative
movement estimate, not a guarantee that an old fix is current.

If there has been no valid fix, no positional emergency is generated. Instead an immediate
GeoChat says `EMERGENCY from <callsign>: location unavailable, acquiring GPS`; the
positional emergency is queued until a real fix arrives. Clearing before that fix removes
the queued activation and sends a cancellation chat rather than replaying a cancelled
alert later. GeoChat's standard non-positional point placeholder is not an emergency
map location. SMS behavior remains independent.

Each alert activation and each cancellation is delivered once per route. Later location
fixes do not resend an active alert (receivers such as TAK Server bridges treat every copy
as a new notification); the user's position keeps updating through PLI. A bounded
in-memory receipt cache (1,024 uid/state/route entries) suppresses repeated submissions to
each configured route, including Companion/Gateway, while still sending the positional
alert once GPS arrives after a no-fix GeoChat, a cancellation, or a route that had not yet
accepted the alert. Receipts record local transport acceptance, not
remote-server acknowledgement. Independent routes can still converge on the same
downstream server; no watch-side dedupe can identify that topology. There is no special
repeated-send loop for 911.

Configure the TAK endpoint and identity/certificates in the app before attempting to connect. Service enablement is persisted in `DataRepository` and centrally evaluated by `ReactiveServiceManager` and `ServiceConfiguration`; a service will run only when its setting, permissions, and prerequisite conditions are satisfied.

The app starts essential behavior through its application/bootstrap receivers and coordinates most long-running work as foreground services. This is intentional: direct TAK connectivity, location tracking, sensor collection, and optional integrations are service-driven rather than tied to a single UI screen.

Chat is an online feature; the project does not provide an offline TAK chat queue.

Companion chat uses the existing watch inbox. Replayed message IDs are deduplicated
per sender, including duplicates within a batch. Messages without IDs use sender,
receipt timestamp, and text rather than timestamp alone.

### WEARTAK-118: Existing group conversations

Standalone TAK chat uses `__chat.id`/`chatroom` and the `chatgrp.uidN` member list:
named groups containing the watch merge all senders into one room, and replies keep
the room ID/title while targeting the most recently received member UIDs with
`marti/dest uid`. Groups without known reply recipients are rejected and logged,
never converted to a generic broadcast. Membership is learned from received chat,
not a group-management service; create/update groups in ATAK and include the watch's
device UID. Other private conversations and own-sender echoes are filtered out.
`All Chat Rooms` is a single explicit broadcast conversation, not a sender DM.

With a matching companion plugin, `chat_received` retains `conversationUid`,
`conversationTitle`, `isGroup`, and `participantUids`. Replies carry canonical `roomUid`,
`roomTitle`, `isGroup`, `msg`, and `cs`, plus legacy `rmUid`/`rmTitle` aliases.
Direct replies additionally carry `recipientUid`/`replyTo` with the same target UID;
group/broadcast replies omit these direct-only fields. The phone accepts `text` as
an alias of `msg`, rejects conflicting aliases, and resolves the existing ATAK group and its current
recipients. Missing/unwritable groups and empty recipient sets fail with phone logs,
without a broadcast fallback. Legacy envelopes without an explicit group flag remain
sender DMs. Existing direct chat and the explicit All Chat Rooms action remain supported.
Inbox navigation follows room UID rather than a changing list index.

The chat picker has two entry actions above the existing conversations:
**All Chat Rooms** opens the canonical broadcast room, including before any message
has arrived. **Select User for Chat** opens the map: tap a person and confirm **Chat with this
person** to create/open a private conversation in the original picker activity and
add it to the normal chat-room list. Cancel/back
creates no room and sends nothing. Selection temporarily shows the people layer
regardless of saved team/role filters without changing those preferences; markers
and alerts are not private-chat recipients. Live UID lookup prevents list reordering
from changing the selected person, and removed/self/invalid contacts cannot be selected.
Chat must be enabled and running; an unavailable service is reported.
Standalone initiated private chats route by recipient UID, not callsign. Companion
private sends resolve a real ATAK IndividualContact with a GeoChat endpoint and use
ATAK's native targeted chat API, preserving native phone conversation history.
Unknown/self/broadcast-like direct contacts are rejected, never broadcast.
Picker order is All Chat Rooms pinned first, Select User for Chat second, then
direct/named-group rooms by their latest received remote message descending.
Incoming messages update this order dynamically; local sends and older replayed
messages do not promote a room. Empty rooms follow rooms with received messages.
The broadcast room appears only in its pinned entry, not again in the sorted list.
BLE uses version-1 `msgType: chat_received` inbound and `msgType: chat` outbound,
with a `payload` object and message ID in `msgUid`/`payload.uid`. Inbound retains
`senderUid`, `senderCallsign`, `msg`, and `receivedAt` (epoch milliseconds).
`isGroup` absent/false stays direct-compatible; no group-owner field is needed.
The Android WearOS BLE path does not advertise Garmin `relay_status.chatGroups`:
only a verified group-aware Garmin transport adapter may announce that capability
for its matching session. BLE enqueue remains unacknowledged chat delivery.

This supports existing groups and initiating direct/broadcast chats, not named watch group creation, private-messenger support,
or historical ATAK chat synchronization. The companion relays incoming remote ATAK
messages while connected; phone-authored outgoing messages and phone-attributed watch
echoes are not mirrored back. BLE enqueue is not an end-to-end delivery acknowledgement.

Hardware acceptance (not yet performed): use two remote ATAK senders and one outsider.
In standalone mode include the watch UID in an existing named group, send from both
members, verify one room with both callsigns, and reply by preset and keyboard; only
members should receive the reply. Repeat with the paired phone UID in companion mode
and both updated builds. In each mode test direct chat alongside the group, All Chat
Rooms, replay/duplicate delivery, and a new room arriving while a group detail/reply
screen is open. Check room/read state and the reply destination. Remove/empty the ATAK
group before a companion reply and verify the phone logs a rejection with no unrelated
delivery. Disconnect/reconnect BLE and confirm existing chats resume without promising
offline history. Smoke-test paired-local point deletion to confirm WEARTAK-112 behavior.
In both modes, start from an empty inbox: open All Chat Rooms and send by keyboard
and preset, checking native send-to-all behavior. Tap Select User for Chat, cancel first, then
select one person (also with the saved people layer disabled), confirm, and send.
Only that person must receive the private chat; on companion also check ATAK's direct
conversation history. Reorder incoming contacts while the person detail is open,
then remove the selected contact and check that confirmation becomes unavailable.
Confirm existing room history/read state survives reopening either entry action.

Outgoing periodic and interaction-triggered PLI expires after twice the active reporting
interval plus 15 seconds (135 seconds for a 60-second interval). Interaction PLI retains
the location fix timestamp as the expiry baseline. Alert and marker expiry is unchanged.

The map's Channels button is greyed out and cannot open or change channels while
admin network settings are locked or a live Companion session manages settings.
Lock changes take effect immediately, closing any open channel selector. Unlocking
restores normal channel selection.

Channel HTTPS requests automatically discover a legacy TAK server's TLS name when the configured address does not match its certificate. For example, the watch can keep connecting to `takserver.nctak.org` while validating the certificate against its exact DNS SAN, `takserver2.nctak.org`. Discovery requires a certificate trusted by the imported/enrollment CA that is not also trusted by the device's public roots, and exactly one supported, non-wildcard DNS SAN. Normal hostname validation (including the existing exact Common Name fallback for certificates without SANs) remains unchanged. The discovered identity is reused for channel requests until the TAK connection is reset; a different identity is not silently substituted during that connection. Discovery failures appear in channel errors and diagnostic logs.

This fallback does not change the CoT stream or enrollment connection. A Data Sync API client is not currently implemented in this app.

### TAK certificate enrollment

Username/password enrollment uses HTTPS port **8446** (`tls/config`, then
`tls/signClient/v2`) with normal platform certificate and hostname validation.
Configuration failures stop enrollment before CSR generation/signing. Signing
responses may be JSON or XML with a required `signedCert`, OpenTAKServer's optional
unnumbered `ca`, and optional numbered `caN` certificates (Base64 DER or PEM).
Authorities are retained as trusted entries; chains are leaf-first, then `ca`,
then numbered CAs in numeric order. Malformed nonblank CA values are rejected.
Registration errors identify the failed stage and distinguish HTTP 401
authentication rejection, other HTTP statuses (including 403), TLS, connectivity,
and invalid certificate data. Retry clears the previous error for that server.
Add/edit registration waits for the saved configuration before starting an attempt.
The dialog follows a stable server-ID request across runtime configuration refreshes;
failures replace the spinner, and retry starts an attempt even when settings are unchanged.
Offline, disabled, companion-managed, cancelled, or stopped-service attempts also
terminate registration rather than leaving it loading; a completed TLS handshake
publishes success. Restarting the TAK foreground service creates a fresh connection
scope and retires the prior service's work. Late teardown of an old instance cannot
cancel the replacement's connections.
This does **not** repair rejected credentials: HTTP 401 still requires valid
credentials/server-side authorization. Response bodies and certificate material
are not logged by enrollment.

### Dev Mode Stay Awake

Debug and release APKs include one opt-in **Stay awake** split-toggle chip at the top of
**Developer Preferences** (tap the
version eight times in Preferences to reveal these tools). This standalone demo/development
session control does not require Wireless debugging or read its system setting. The switch
holds screen, CPU and Wi-Fi locks and keeps a Wi-Fi network actively requested, including
while viewing system settings. The request does not require Internet access, so local-only
hotspots can satisfy it. It shows an ongoing notification with a Stop action. It remains
enabled until you turn off the switch or tap notification Stop. Tap the chip body for
read-only behavior details. The switch reflects saved intent; **Running** and
**Enabled, not running** distinguish that intent from actual service activity.
Leaving the UI or swiping the recent task does not stop the foreground service.
There is no separate update-persistence option: enabled users retain their choice
regardless of the old checkbox. Explicitly disabled users stay disabled.
The service requests `START_STICKY` recovery after ordinary process death; the
package-update and post-unlock boot receiver restore only this service when enabled.
Opening WearTAK retries a previously blocked restore. Startup failure releases partial
locks and reports an error without turning the saved choice off. These hooks never
launch the main activity or enable the full WearTAK/Companion BLE service stack.
Updates and restarts temporarily release locks. Android can defer or deny service
recovery (including force-stop, system task-manager stop, battery policy or permission
restrictions), so recovery is best-effort, not uninterrupted operation. Boot restoration
is not direct-boot/lock-screen bypass. Saved intent survives reboot/power cycle, not
uninstall, app-data clearing or factory reset; no screen can stay awake while powered off.
There is no timed expiry. The warning **Extra battery use. May heat watch.** appears only while
enabled. The Wi-Fi request is released on stop, service destruction, or startup failure.
It does not force Wi-Fi on or guarantee connectivity against RF conditions, hotspot
disconnects, or system overrides. It does not prevent an explicit screen-off action or
guarantee ADB/multicast delivery. The switch
is only available in the hidden Dev Mode menu.

### TAK SA multicast

With TAK SA Multicast enabled and the watch directly connected to Wi-Fi, WearTAK sends and
receives position/other CoT on the configured multicast endpoint (default `239.2.3.1:6969`)
and GeoChat on `224.10.10.1:17012`. It also receives direct ATAK points/other CoT on
`224.10.10.1:6969`, since ATAK uses that port for non-chat traffic to the advertised UDP contact.
Incoming traffic uses the same contact, marker, and chat
handling as TAK-server traffic; receiving chat requires the chat service to be enabled.
Multicast status indicates that the sender and all receivers are ready, not that a peer
has been discovered. The Wi-Fi access point must permit multicast between clients.
Multicast tracks local Wi-Fi independently of Android's default internet connection,
so cellular routing changes do not stop receivers on a connected radio Wi-Fi network.
While multicast is enabled, WearTAK requests local Wi-Fi so Wear OS does not shut it down
for lack of network demand, and holds a high-performance Wi-Fi lock to reduce radio
power-saving interruptions where supported by the OS. The request does not require
internet. Both the request and lock are released when multicast is disabled or the TAK
connection service stops. This uses the existing multicast toggle, increases battery use,
and does not keep the screen or CPU awake. It does not pin an SSID/BSSID, prevent roaming,
override OS power restrictions, or guarantee screen-off delivery. Multicast sockets remain
bound to the Wi-Fi network and are recreated when that network changes.
On Android 14 and newer, the OS maps high-performance locks to low-latency mode,
which only takes effect with the screen on and the app in the foreground. The active
local Wi-Fi request still remains in place with the screen off; this is not an OS
power-policy bypass. Connection loss, validation/suspension capability changes and
interface changes are logged without including SSIDs, credentials or IP addresses.
Reception supports both XML CoT and binary TAK Protocol v1 datagrams, including GeoChat
and ATAK contacts using friendly equipment/vehicle position types.
Multicast position broadcasts advertise the GeoChat UDP endpoint so ATAK can send direct
messages to the watch without a TAK server; server-bound broadcasts retain their server endpoint.
The receiver ignores datagrams sent from this device's local addresses, including markers
whose event UID differs from the watch UID. Binary decoding preserves custom XML details
such as `t-x-meshcfg`/`__meshtastic_cfg`; a plugin declaring `configShareUrl` can receive that
configuration through its validated settings interface.

### Plugin CoT

Plugins can opt in through their settings schema and enabled boolean values:
`forwardOutboundCot` receives watch-generated CoT as `cot.outbound`, while
`receiveInboundCot` receives incoming CoT as `cot.inbound`. Both actions carry `xml`;
incoming actions also carry `source` and `receivedAt`. Incoming delivery covers direct
TAK, multicast, SITX, and companion CoT IPC without changing their existing processing.
Plugin-injected CoT is processed locally but is not sent back into plugin bearers,
preventing reinjection loops. Unsupported CoT types can still be observed by opted-in plugins.

These subscriptions do not start or enable any network transport, and observing inbound
CoT does not retransmit it. A BLE-only configuration remains BLE-only. The companion BLE
protocol itself is unchanged; it does not currently provide a raw incoming CoT feed.
Plugin `requestCotDispatch` retains its existing TAK-server relay by default; a plugin
can declare `relayInboundToNetwork` and set it false for local-only injection. That relay
does not enable disconnected TAK servers or fan out to other transports.

## Architecture

### Paired Bloodhound tracking

While the ATAK companion is bonded and BLE-ready, starting Bloodhound tracking on
either device starts the same CoT target on the other. Ending that shared session on
either device stops tracking on both, without cancelling the underlying emergency,
dismissing its alert, or deleting its marker. Closing a screen is not a tracking stop.
The watch must have its Bloodhound service enabled and running; companion commands
do not override disabled services or missing permissions.

Commands use `bloodhound_control` envelopes with an `action`, `session_id`, and
`target_uid`. Starts carry the target callsign and coordinates, allowing the watch to
create a companion-owned navigation target if the UID is not already available.
Existing locally received targets are preferred. Start/stop acknowledgements use
`bloodhound_control_result`; rejection or a missing acknowledgement is surfaced rather
than claiming synchronization succeeded. Session correlation, bounded ended-session
history keyed by session and target UID, and echo suppression prevent old commands
from stopping a replacement track. A repeated stop is acknowledged only for its
original session/target pair.
Simultaneous starts converge on the lexicographically smaller session ID.
An `update` action carries changed target coordinates within the same session, so a
companion-only target can move without restarting tracking. Updates do not overwrite
an independently received local CoT target; a real incoming source can replace the
companion snapshot without ending its tracking session.

Disconnect leaves each device's local tracking unchanged and forgets the shared session.
Reconnect does not replay an old start or stop; a new tracking action is required to
synchronize again. This does not depend on the separate phone-to-watch alert forwarding
feature and does not retransmit alert cancellations.
The Bloodhound picker has a **Remove All** button above its targets. Its confirmation
screen offers **Remove All** and **Cancel**; Cancel or dismissing the dialog leaves
the list unchanged. Remove All removes all
current source copies of ordinary targets and locally dismisses remote emergency alerts,
stopping navigation to removed targets. Alert dismissals survive restarts and suppress
ongoing refreshes until the sender cancels. This does not send cancellation or acknowledgement
messages, delete ordinary map markers, or prevent new ordinary targets from arriving.

### Phone-to-watch points

Paired companion BLE accepts `phone_marker_cot` envelopes with original marker CoT
in `payload.cot`. Valid points appear on the map and in Bloodhound under
`Companion ATAK`, without a separate watch TAK connection. Blank UIDs, invalid
timestamps, non-finite coordinates and out-of-range latitude/longitude are rejected.
Original event/start/stale times are retained; missing start or stale defaults to
event time, as with existing marker ingestion.

BLE, direct TAK and SITX share serialized map/navigation ingestion. Source-owned
copies are deduplicated by UID using event time before receipt time. A stale
incoming marker removes only its source's copy at or before that event time,
including navigation to that removed copy; another source's target remains intact.
Source-scoped removal watermarks reject older and equal-time replay during the
watch process lifetime, including BLE reconnects. Disconnecting does not clear
received points. There is no offline replay queue or new automatic expiry policy.
Asynchronous repository failures are logged; cancellation is not treated as an error.

### WEARTAK-130: chat commands

Tool Preferences > Plugins > **Commands** (off by default) lets another TAK user change watch settings by sending chat commands. **Allowed Callsigns** is a case-insensitive list shown alphabetically; add one callsign at a time or remove an entry. Existing comma-separated allow lists remain supported. All commands accept direct messages, All Chat Rooms broadcasts, and groups containing the watch UID. Only messages from allowed callsigns are acted on. Messages from other senders, stale messages (older than 5 minutes) and replays are dropped silently.

TAK-server and Wi-Fi multicast receive already share the same command runtime and
recipient checks. A server is not required for multicast commands. After a reset,
first open WearTAK, grant its required permissions and connect watch and sender to
the same multicast-capable Wi-Fi network. Enable **Network Preferences > TAK SA
Multicast** (with valid address/port), then **Plugins > Commands** and add the
sender's callsign to **Allowed Callsigns**. Keep Chat enabled for the normal inbox
and outcome replies. This is bootstrap configuration, not a full backup restore.
Enabling multicast also enables normal multicast publishing, not a receive-only
provisioning mode; use an appropriately scoped test network.

The receiver listens on the configured group/port plus standard GeoChat
`224.10.10.1:17012` and direct CoT `224.10.10.1:6969`. In the sender, choose the
actual watch contact for a **direct** chat, a group containing the watch UID, or
All Chat Rooms to provision multiple watches. Direct CoT must
identify the watch as a recipient: normally `__chat.id`/`chatgrp.id` is the watch UID
and `chatgrp.uid1` is that UID (`uid0` is the sender); `remarks.to` can name the watch
UID/current callsign. Watch UID membership also supports direct chats without
`remarks.to`. Missing or other-device recipients are not a provisioning shortcut.
Keep the device clocks within five minutes. Phone-managed identity/network locks
still apply. A factory reset may change the UID: discover the current contact
rather than replaying a saved recipient UID.

The shared process-local replay cache ignores repeat sender/message-ID pairs
across server and multicast, including either arrival order. It remembers the
last 128 accepted pairs, not a durable execution ledger across app restarts.
Consumed commands are not inserted as ordinary chat or rebroadcast; one normal
outcome reply is generated for a newly accepted command. With Commands disabled,
addressed command-looking text retains normal chat behavior.

Format: `#weartakcommand<name> key=value key2="value with spaces"`

| Command | Arguments | Effect |
| --- | --- | --- |
| `setcallsign` | `callsign` (1-32 letters, digits, space, `.`, `_`, `-`) | Sets the callsign. Refused when settings are managed by the phone. |
| `setpdl` | One positional argument: `community` or an encoded SharedKey v2 PDL | Validates the selected identity and expiry, atomically saves source/custom identity, then recreates Ditto with the new identity. Refused when network settings are locked or phone-managed. Does not change the Ditto enable toggle. |
| `enrollserver` | `name`, `url` (host name or IP), `port` (default 8089) | Posts a notification on the watch. Tapping it opens the New Server screen pre-filled; the user enters the username and password there and they are stored like any other TAK server. Credentials are never accepted in chat. Refused when network settings are locked or phone-managed. |

For example, `#weartakcommandsetpdl community` selects the unchanged built-in
Community identity; `#weartakcommandsetpdl <PDL>` selects a validated Custom PDL.
There is no key=value form or `enabled` argument. Custom PDL values may contain at most
16,384 characters (17,408 characters for this command); other command limits stay
unchanged. Replies contain only source, the first eight app-ID characters, UTC
expiry: `Ditto updated: source=community app=12345678 expiry=2027-01-01T00:00:00Z`.
`Ditto update failed` means saved settings could not be applied or the service
failed to start, not that the previous settings were restored. Normal companion,
permission and application-license gates still suppress sync when appropriate.
PDLs are credentials: use a trusted encrypted direct-chat transport; the sender,
TAK server and chat history on other clients may retain the original command.
WearTAK does not log or echo PDL values. Invalid/expired identities change nothing.
All commands also work in groups (with watch membership) and All Chat
Rooms. Outcome replies go back to the originating conversation,
including the verified original participant roster for named groups. Broadcast
or group provisioning exposes the original PDL to every recipient; only send a
custom identity this way when that entire audience is authorized to possess it.
Ditto chat replay remains data-only and cannot execute this or any other command.

Profile commands take the same values as the settings screens (case-insensitive) and validate everything before changing anything, so one bad value changes nothing:

| Command | Argument |
| --- | --- |
| `setteam` | `team=<name from the team list>` |
| `setrole` | `role=<role name>` (the role category is set automatically) |
| `setheight` | `height=5'8"` (feet 1-8, inches 0-11; `5'8` and `5-8` also work) |
| `setweight` | `weight=<1-1000>` |
| `setbirthyear` | `birthyear=<1900-current year>` |
| `setsex` | `sex=<Male, Female, N/A>` |
| `setusertype` | `usertype="Coalition Civilian"` |
| `setpants`, `setuniformpantslengthinches` | `pants=<20-50>` (inches) |
| `setwaist`, `setwaistsize` | `waist=<20-60>` (inches) |
| `setprofile` | any combination of the fields above plus `callsign` |

The callsign, team and role fields (including in `setprofile`) are refused when settings are managed by the phone.

Allowed Callsigns uses the same full-screen text-entry page as other settings
when adding an entry, with the standard confirm/cancel controls instead of a
dialog. Keyboard **Done** also validates and adds the callsign, so saving does
not depend on a button behind the keyboard. Invalid (outside the existing
1-32-character callsign format) or case-insensitive duplicate entries stay on the
entry page with an error. Cancel/back does not save.

`#weartakcommandloadprofile name=odin-weartak` applies a preset saved on the watch as `Android/data/<package>/files/presets/<name>.json`. Presets are provisioned with adb and are never part of this repository, because they can hold server credentials:

```json
{
  "profile": { "callsign": "ODIN-WEARTAK", "team": "Dark Green", "role": "K9", "height": "5'8\"", "weight": "150" },
  "servers": [ { "name": "NCTAK", "url": "takserver.nctak.gov", "port": 8089, "username": "user", "password": "secret" } ]
}
```

The profile is validated and applied, then each server is added through the normal add-server path (servers already present are skipped). Names are lowercase letters, digits and `-`. The reply says only how many servers were added. Refused when network settings are locked.

Presets may also carry `"unitPrefs": {"rab_rng_units_pref": "0"}` (display units, see
**Display units** below) and `"customAlerts": ["Pick me up", "Help"]` (saved Manual Alert
labels, merged into the watch's list; duplicates and built-in names are skipped, at most 20
labels of up to 60 characters). An invalid value in either field rejects the whole preset
before anything is applied.

**Settings > Tool Preferences > Plugins > Commands > Profiles** lists saved preset
files and opens a read-only view of their profile fields and server names, hosts,
and ports. Passwords, usernames, and unknown fields are omitted; opening a profile
does not apply it or connect to its servers. Missing presets show an empty state;
unreadable, malformed, or oversized (over 64 KiB) files show an error instead of
raw JSON or credential-bearing parser errors. Reopen Profiles after changing files.
The long command aliases use the same fields and ranges as their short forms:
`#weartakcommandsetuniformpantslengthinches pants=30` and
`#weartakcommandsetwaistsize waist=32`.

The watch replies in chat with the outcome. Callsigns are not authenticated:
another participant on the server or anyone able to inject multicast on the LAN
can impersonate an allowed callsign. Neither recipient UIDs nor replay filtering
prove origin; only enable this convenience feature on a trusted, scoped network.
Multicast is plaintext. Never put passwords, enrollment secrets or other
credentials in chat. Server enrollment still prompts on the watch and uses the
normal TLS/enrollment path; it does not accept credentials through either chat
transport. Command audit messages omit argument values, but ordinary chat and
packet diagnostics are not a secret-storage channel.
### WEARTAK-112: explicit shared-point deletion

**Local paired-device mirrors only.** A watch deletion removes the selected local
point and its eligible paired-phone copy; a phone deletion removes its local point
and its eligible paired-watch copy. It never sends a deletion to TAK servers,
multicast, Ditto, SITX, plugins/mesh, or any other devices. This also holds when
BLE is unavailable: delete locally immediately, without queuing a network clear.
Incoming mirrored deletion neither broadcasts nor re-echoes deletion.

Both peers add an opaque `payload.revision` (UUID) to live marker envelopes.
Existing marker fields and XML remain unchanged; peers without this addition can
still receive points but cannot authorize explicit deletion.

```json
{"msg_type":"marker_delete","payload":{"targetUid":"shared-point-uid","revision":"previously-exchanged-revision"}}
```

Deletion means **remove the exact shared revision**, not delete every copy of a UID.
Only the current bonded, services-ready BLE connection can send/apply this envelope.
There is no timestamp fabrication, CoT `t-x-d-d` importer invocation, network delete
broadcast, offline queue, startup snapshot, or reconnect replay.
The receiver answers each well-formed deletion with a `marker_delete_ack` envelope
(`targetUid`, `revision`, `result` of `applied` or `rejected`) on the same connection.
The sender matches it to the pending deletion for that exact uid, revision and
connection generation, and records the outcome (`PointDeletionAcks`). A deletion with
no matching ack within 10 seconds is recorded as timed out, and an unknown, duplicate or
previous-connection ack is ignored. An ack confirms only that the peer applied or
rejected the deletion, not delivery to any TAK server. Peers that predate this ack
never answer, so their deletions report a timeout.
The revision must match a supported marker exchanged in this connection. New
shared updates replace it; unknown, duplicate, superseded, malformed, and
previous-connection deletions are inert and logged without payload contents.

On phone deletion, the watch removes the `companion-phone` map/Bloodhound copy
using its stored event-time watermark. Other-source copies survive. An original
watch-dropped point with the matching shared revision is also removed unless a
local edit/location update is pending; its dispatch backlog is cleared without
echoing a delete. Navigation to a removed source copy stops.
On watch deletion, the phone removes only the observed, supported shared marker
whose exported content still matches the exchanged revision, using ATAK's local
`removeFromGroup()` API, with echo suppression. Unshared native items, alerts,
PLI, routes, shapes, and groups are not eligible.

The existing watch received-marker Delete UX still removes all local copies of
that UID; it relays only when the selected visible copy belongs to the companion.
Dropped-marker Clear/Last/All removes local points immediately and emits
per-point explicit BLE deletions only for points shared this connection.
Legacy expired marker clear CoT is no longer generated or dispatched on any
transport; the outbound `MARKER_CLEAR` policy rejects every CoT route defensively.
Bloodhound menu Remove remains local target dismissal, not map deletion.
Transport enqueue is best-effort, not delivery confirmation: both apps must be
updated together and hardware acceptance is still required.

Regression coverage: `CompanionPhoneMarkerLifecycleTest`, `SourceAwareCotStoreTest`,
and companion `PhoneMarkerRelayTest` cover revision/session rejection, source
preservation, navigation cleanup, replay watermarks, echo release, and write failures.
`CotDispatchServicePointDeletionTest` verifies local clear and dispatch-backlog
cleanup without any external/network/legacy companion CoT calls.
`CotEndpointDispatcherTest`/`CotDispatchPolicyTest` verify clear CoT never reaches
any transport even with all endpoints available, both with and without ready BLE.

Paired-device acceptance (not performed by JVM tests): create a phone point, start
watch Bloodhound, then delete in ATAK: the companion-source point/target disappears
and navigation stops. Repeat with a watch-dropped point deleted on the watch and
verify the ATAK marker/tracking disappears. Edit the watch-origin marker in ATAK
and repeat both directions. With a same-UID TAK copy present, verify deleting the
phone copy leaves the TAK copy intact. Replay the old deletion after a newer
shared edit or reconnect: the newer point must remain, with no echo deletion or
startup replay. Unsupported PLI/emergency/route/shape/group items must remain.
With a third TAK/network peer displaying the same point, delete on either paired
device and verify that third peer retains its copy and receives no deletion packet.
### Remote emergencies

Incoming `b-a-o`, `b-a-o-*`, and ATAK's standard `b-a-g` CoT emergencies share one active
alert state for the map and Bloodhound. A yellow warning symbol distinguishes them from
ordinary contacts and markers, remains visible regardless of those map filters, and opens
the alert's Bloodhound details. Alerts appear first in the picker, with the sender and
emergency category.
Choosing navigation does not send an assignment acknowledgement or automated chat.

TAK server, SITX, companion IPC, and paired companion BLE ingress use the same parser
and lifecycle handling. The ATAK companion plugin forwards incoming phone emergencies
and cancellations only while a bonded watch is connected, using a `phone_emergency_cot`
envelope with original CoT XML in `payload.cot`. BLE alerts appear under source
`Companion ATAK` and do not require a separate TAK connection on the watch.
Disconnected alerts are not queued for replay by the plugin.
Disconnecting does not clear alerts already received by the watch. A cancellation
received after reconnect removes matching alert copies from both BLE and direct TAK
ingress; older replayed alerts cannot resurrect them. Local dismissals remain local
and suppress refreshes until a matching cancellation ends that alert session.
Asynchronous ingestion failures are logged as processing errors, not successful delivery.
ATAK's `*-9-1-1` alert UID, parent link, emergency text/type and cancellation flag are
supported, as are WearTAK's biometric alert details. Own alerts are ignored. Source-owned
copies are deduplicated by CoT UID; cancellation removes only emergency copies at or before
its event time, not the sender's ordinary PLI or markers. Cancellation timestamps survive
process restarts to reject delayed replay. Passing the sender's CoT stale deadline marks
an alert **Stale** in both the map label and Bloodhound instead of removing it. Valid
already-stale incoming alerts are accepted as last-known emergencies. A newer refresh
updates the deadline and clears Stale while fresh; explicit cancellation removes the alert.
A missed cancellation can therefore leave a stale alert visible; Stale does not mean
the emergency ended or that its location is live. Explicit local dismissal is still available;
source disconnect/removal clears only that source and preserves other copies.

Alerts without a usable location remain readable in Bloodhound, but are not placed on
the map and cannot guide navigation. A location-less refresh can retain that source's
previous valid location, marked as last-known when stale. **Dismiss locally** hides the
current emergency from both map and picker without sending any cancellation to the sender.
The persisted dismissal suppresses ongoing repeats and refreshes, including after restart.
Only a sender cancellation at or after the latest observed emergency update ends that
dismissal; a newer reactivation may then reappear, even when ATAK reuses the same UID.
Delayed older cancellations do not clear a dismissal. Ordinary point deletion is unchanged.

Receive/apply/stale-transition logs record why an alert was ignored (self, cancelled, older),
distinguishing missing ingress from rejected alerts during hardware verification.

The manual alert picker also supports ATAK's standard `911 Alert`, `Ring The Bell`,
`Geofence Breached`, and `In Contact` types alongside the existing custom options. These
map to `b-a-o-tbl`, `b-a-o-pan`, `b-a-g`, and `b-a-o-opn`, respectively, so standard
alerts can be sent and received without changing custom alert behavior. This feature builds
on the remote-emergency reception and display work pending in gov MR!327.

Manual alerts appear in a scrollable, single-column list with one-line labels. Tap a
built-in or saved alert to send it immediately. The `+` row opens **Type Custom Alert**;
saving adds that alert to the list for future use. Hold a saved custom alert to open its
delete confirmation. Alerts without an available route continue to use the existing
pending-alert queue.

Alert kinds: the four ATAK standard types above use their own CoT types; every other built-in
(Gate Runner, Gunshot, Gunshot Injury, Injury, UAS, Vehicle) and every typed or saved custom
alert is sent as `b-a-o` (shown by TAK Server as kind `sos`), with `alertCategory` "Manual
Alert" and `alertPriority` 1. Cancels are `b-a-o-can` for every kind, reusing the alert UID.
Each alert UID is delivered once per route and state, even when later GPS fixes arrive.

### Display units

**Settings > Tool Preferences > Display Preferences > Units** selects **Metric**
(Meters/Kilometers, default) or **Imperial** (Feet/Miles), the labels ATAK uses for its Range
Units. It applies to Contacts and marker distances, Bloodhound distances and the radius
picker, routes, the Bloodhound complication and the map scale bar. Values are stored in
meters; the setting only changes display. Metric shows `m` below 2000 m and `km` above; Imperial
shows `ft` below 5280 ft and `mi` above (ATAK's default `rng_meters_display_pref` and
`rng_feet_display_pref` thresholds). Bearings are labelled true (`°T`).

The value is stored under ATAK's key `rab_rng_units_pref`: `"1"` Metric, `"0"` Imperial.
ATAK's `"2"` (Nautical Miles) is shown as Imperial. It can be set from presets (`unitPrefs`),
managed configuration (`rab_rng_units_pref` restriction), and the bonded Companion app: a full
`set_settings` may include `payload.unitPrefs`, and a live
`{"msg_type":"unit_prefs","payload":{...}}` message (also `msgType`) applies a change and is
answered with `unit_prefs_ack` (`ok`, `error`). Companion sends ATAK's seven Unit Display
Format keys as strings; the watch reads only `rab_rng_units_pref` and ignores the rest. The
settings snapshot echoes `unitPrefs: {"rab_rng_units_pref": "0"|"1"}`. Live changes are
accepted only from the current single-watch bond, not from gateway sessions; the last writer
wins.

Chat presets in the reply picker submit the selected text immediately to that conversation.
Typed chat text still uses the existing compose-and-Send flow. The picker keeps the
conversation UID so incoming list changes cannot redirect a preset to another recipient.

Saved chat presets follow the Manual Alert pattern in both the New chat and in-room preset
lists: one-line rows, built-in presets first, then saved ones. The `+` row opens **Type Custom
Message**; **Save message** stores it without sending. Saved presets are de-duplicated against
built-ins (case-insensitive), limited to 20 entries of 60 characters, and deleted by holding the
row and confirming.

PLI remarks report exertion as a whole percent (`Exert:45%`), matching the watch display. The
`<exert>` detail stays a 0..1 fraction for the ATAK physiology plugin.

The main-screen physio chip is always tappable. With Physiological Services off it shows
**Physio off** and opens settings; while the watch reports not worn, values show **Not worn**.
Worn-state transitions and their source are logged under the `WearTAK-DeviceWorn` tag.

When the companion phone BLE session is ready, outbound PLI, chat, markers, and alerts
use the phone uplink only; the phone handles onward TAK, SITX, and multicast delivery.
The watch resumes its directly configured routes when the companion session disconnects.
The connection complication reports **Phone uplink active** while that route is in use.

The phone's Multiwatch Gateway uses a transient, session-qualified BLE negotiation; it
does not add a watch mode selector or settings UI. During that session the watch keeps
its own UID, callsign, team, role, report interval, and server/P12 configuration and does
not accept phone profile writes. Gateway CoT is limited to supported map points, user
positions, emergency/cancellation events, and GeoChat. Incoming chats are checked against
the watch's own UID/callsign and explicit recipients; unsupported CoT and profile/control
messages are rejected. The Gateway session and duplicate-event ledger clear when BLE is
no longer services-ready, after which the existing directly configured routes resume.

`WearTakApplication` initializes shared repositories and the CEP/plugin infrastructure. `DataRepository` is the main persistent state and settings hub, based on DataStore and Kotlin Flows. Newer components use Flow/StateFlow for reactive updates; some existing components communicate through EventBus.

The important runtime path is:

```text
Watch sensors, user actions, and integrations
                 |
                 v
       repositories and event streams
                 |
                 v
  ReactiveServiceManager / service layer
                 |
                 v
    CotDispatchService -> TAK/SITX/Ditto endpoints
```

Key areas of the source tree:

```text
app/src/main/java/com/aitech/weartak_civ/
├── presentation/       Wear OS activities and Compose UI
├── service/            foreground services, connectivity, and sensor orchestration
├── data/               DataStore-backed state, repositories, models, and secure storage
├── cep/                CEP rules, configuration, event bus, and alert actions
├── integration/        managed configuration and plugin-host integration
├── ipc/                CoT IPC models and serializers
├── complication/       watch-face complication providers
├── tile/               Wear OS Tile provider
├── receiver/           boot, update, and alarm receivers
├── events/             legacy EventBus event types
└── WearTakApplication.kt
```

## Development notes

- Use `./gradlew :app:assembleDebug` for a focused compile/package check.
- Enable optional services through the app settings or managed configuration; do not start a service directly without considering its `ServiceConfiguration` prerequisites.
- Runtime configuration defaults live in `Constants.kt` and persistence keys/state are in `data/DataRepository.kt`.
- CEP rules and related runtime configuration are under `app/src/main/res/raw/` and `cep/`.
- The repository currently has unit and instrumentation test dependencies, but it does not contain a broadly established test suite. Add targeted tests with feature changes where practical.

For live Android logs, use:

```bash
adb logcat
```

To inspect app-private files on a debuggable install:

```bash
adb shell run-as com.aitech.weartak_civ ls files
```

## Security and data handling

WearTAK-CIV handles potentially sensitive location, health, communications, and credential data. Treat device logs, exported app files, managed configurations, and build artifacts accordingly. Configure secure TAK endpoints and credentials appropriate to your deployment, and review required Android permissions before field use.

## Contributing

Unlimited Rights granted to TPC

Keep changes focused and validate them with the narrowest relevant Gradle task or test. When adding or changing a service, account for its manifest declaration, permission requirements, persisted enablement, and registration with the service orchestration layer.

### Point of Contact: Alex Gorsuch on chat.tak.gov or Signal


#### Bloodhound picker controls and artwork

Each picker row has a type/name/subtitle, a grey crosshair (**Show on map**, no
navigation activation), and a green ATAK hound (**Bloodhound**). Routes open a
**Start nearest / Start from beginning / Cancel** chooser; other located targets
start immediately. Active rows turn amber, show route progress and remaining
distance, and replace the hound with a red **Stop** button. The row body still
opens details. Route crosshairs fit the whole geometry within the round watch;
point/contact/marker crosshairs center the map at the target.

The bundled `atak_nav_bloodhound.png` and `atak_ic_crosshair.png` artwork comes
from [deptofdefense/AndroidTacticalAssaultKit-CIV](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV)
(`atak/ATAK/app/src/main/res/drawable-xxxhdpi/nav_bloodhound.png` and
`drawable-hdpi/ic_crosshair.png`), licensed GPLv3. Distribution/licensing review
of this artwork is pending with the project owner.
