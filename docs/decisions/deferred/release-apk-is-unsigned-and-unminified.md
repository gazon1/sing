---
title: "Release Apk Is Unsigned And Unminified"
date: 2000-01-01
status: PARTIALLY
tags: ["deferred"]
---

**Found in:** 2026-10-06, while writing `release.yml`.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
ordering argument (minify before signing) is recorded there.

**Status: PARTIALLY CLOSED (signing done; minification remains OPEN).**

**Signing — DONE (2026-10-09):**
`androidApp/build.gradle.kts` now has a `signingConfig` block that reads
`SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD` from environment variables (populated from GitHub Actions
secrets in `release.yml`). `release.yml` fails-closed if any secret is missing,
and verifies the APK certificate SHA-256 against a repo variable
(`ANDROID_CERT_FINGERPRINT`). `VERSION_NAME` and `VERSION_CODE` are injected
from the tag at build time.

**Minification — OPEN.** R8 breaks Koin, Room and kotlinx-serialization on
their reflection, and nothing in CI exercises a minified build today: every CI
job assembles debug. So "does the shipped binary work" is a larger risk than
"who receives the file", and minification belongs before signing — a question
this entry's predecessor correctly framed. `isMinifyEnabled = false` remains.

**Try next:** (1) set `isMinifyEnabled = true`, write the
`proguard-rules.pro` entries for Room/Koin/kotlinx-serialization/Compose, and add
`assembleRelease` to the `android` matrix so R8 breakage surfaces on a PR;
(2) upload `mapping.txt` as a private artifact, which is meaningless until (1)
exists.

---
