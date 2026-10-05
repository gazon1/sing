# Tasks — entitlement-belongs-to-a-sync-scope

## Domain types

- [ ] Add `Entitlement` as a sealed interface over `SyncScope` — `Unknown` and `Known` — with
      `scope` on the interface so a consumer never has to carry it separately.
- [ ] Change `PurchaseState` to carry `hasAccount`, `hasPro`, and `provider: BillingProvider?`.
      Drop `hasSubscription`, which duplicated `hasPro` and could only ever agree with it.
- [ ] Delete `purchaseStateFor`. It reads a flow it does not own, and the read is the defect —
      nothing in the replacement API needs a function with that signature.

## Port

- [ ] Replace `val subscription: Flow<SubscriptionInfo?>` with
      `fun entitlement(scope: SyncScope): StateFlow<Entitlement>`. Read-only in the signature is
      the point: the cast that denied paying customers is not expressible against it.
- [ ] Replace `awaitVerification(): Boolean` with
      `suspend fun refresh(scope: SyncScope): Result<Entitlement>`. A `Boolean` cannot report the
      difference between "not entitled" and "could not check", and that difference is what stops a
      network failure from reading as a denial.
- [ ] Keep `getFormattedPrice(sku)` unchanged; it is a UI concern and does not carry entitlement.

## Implementation

- [ ] `NoopSubscriptionProvider` returns `Unknown` for every scope, backed by a `MutableStateFlow`
      it owns.
- [ ] Update the `SubscriptionProvider` binding in `CoreDiModule`; it is a `single` today and
      stays one, because a provider is stateless and the flow it hands out is per-scope.

## Tests

- [ ] The positive case that does not exist yet: a fake provider exposing `asStateFlow()` — the
      exact shape a real provider has and `NoopSubscriptionProvider` does not — must resolve to
      `Known(hasPro = true)`. This is the test whose absence let the defect ship.
- [ ] Account presence independent of subscription: signed in without a subscription reads
      `hasAccount = true, hasPro = false`.
- [ ] Profile switch: an entitlement held for `work` is not observable for `home`.
- [ ] Failed `refresh()` leaves the previously established value intact rather than writing
      `Known(hasPro = false)`.
- [ ] Blank `ownerId` or `profileId` is rejected the way `SyncScope` rejects it.

## Verification

- [ ] `:shared:jvmTest` green; detekt green in `:shared`.
- [ ] `check.sh` green — noting that `:shared:detekt` is currently blocked by #208, which is not
      this change's to fix.
- [ ] `find-unwired-surfaces.py` — the port gains no new unwired surface, and the retirement of
      `purchaseStateFor` is not a deletion the detector should report.