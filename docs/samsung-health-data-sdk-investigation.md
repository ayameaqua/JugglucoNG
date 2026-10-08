# Samsung Health blood glucose integration

Official documentation checked on 2026-10-08.

## Feasibility decision

Samsung Health Data SDK supports inserting `DataTypes.BLOOD_GLUCOSE` using
`insertDataRequestBuilder` and `HealthDataStore.insertData`. The restriction is
application authorization, not absence of a blood glucose write API.

Writing requires an approved Samsung partnership and approved data scope. The
registered package name and release certificate SHA-256 must match the running
application. User consent for `Permission.of(DataTypes.BLOOD_GLUCOSE,
AccessType.WRITE)` is also required; ordinary Android permissions or Health
Connect authorization do not substitute for it. Samsung Health must be installed
and sufficiently recent, and the current Data SDK requires Android 10 or newer.

Developer mode does not provide a non-partner write path: even developer-mode
write testing requires an access code issued after partnership approval. Samsung
states that developer mode is for development/testing, not app users. The old
Samsung Health SDK for Android is deprecated; its examples are not evidence that
this unregistered fork can write with the current SDK.

No approval, data-scope registration, or write access code has been established
for this fork. Therefore this build does not bundle the SDK or expose a switch
that claims direct Samsung Health synchronization. No direct insert was executed
or verified on a Samsung device.

## Safe available path

Continue the existing local-native-write-triggered, asynchronous Health Connect
export. Stable client record IDs make gap replay idempotent, and cursor/revision
validation protects concurrent history writes. This path does not depend on
Samsung Health installation or Samsung SDK credentials.

Samsung Health can be configured separately to access Health Connect. Whether
the installed Samsung Health version actually imports/displays these glucose
records must be checked on the user's phone; that end-to-end behavior is not
claimed as verified by this build.

## Implementation after official approval

Register the final application ID and its real release certificate (the public
repository's default test certificate is not a private production identity),
obtain approval for blood glucose writes, then integrate the approved SDK version.
Use a separate default-off preference, installation/version checks and explicit
user consent. Dispatch from the same successful local write notifications as
Health Connect, with independent queue, persistent deduplication ledger and
cursor. Samsung failures or denied permissions must never gate Health Connect.
Use epoch instants and the SDK's correct CGM specimen/measurement representation;
do not copy the sample's fasting/whole-blood classifications for CGM readings.
Test replay, concurrent backfill, permission revocation, process restart, and
partial success against a real approved Samsung Health installation before
enabling the distributed feature.

## Official sources

- [Blood glucose read/write examples in the migration guide](https://developer.samsung.com/health/data/migration-guide/overview.html)
- [Partnership and app creation process](https://developer.samsung.com/health/data/process.html)
- [Package and certificate verification](https://developer.samsung.com/health/data/guide/app-verification.html)
- [Developer mode and write access codes](https://developer.samsung.com/health/data/guide/developer-mode.html)
- [FAQ: permissions, installation, Android version and writing](https://developer.samsung.com/health/data/faq.html)
