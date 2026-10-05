# entitlement — capability

## ADDED Requirements

### Requirement: Entitlement is scoped

The system SHALL record a user's entitlement against a `(ownerId, profileId)` pair, the same
identity sync state belongs to. It SHALL NOT store entitlement under an account-wide or
profile-wide key alone.

#### Scenario: A profile switch cannot silently carry entitlement

- **WHEN** a user holds an active entitlement for profile `work` and switches to profile `home`
- **THEN** the entitlement observed for `home` is `Unknown` until `refresh()` answers for that scope
- **AND** the entitlement observed for `work` is unchanged
- **AND** no code path grants or revokes `work`'s entitlement as a side effect of the switch

#### Scenario: A second account on the same device starts from nothing

- **WHEN** a device signed in as owner `a` is signed out and in as owner `b`
- **THEN** `b`'s entitlement is `Unknown` rather than `a`'s last known value
- **AND** no row, cursor, or entitlement written under `a` is readable as `b`'s

#### Scenario: An entitlement cannot be constructed for a scope that could not hold a row

- **WHEN** an entitlement is constructed with a blank `ownerId` or a blank `profileId`
- **THEN** construction fails
- **AND** the failure is the same one a `SyncScope` for that pair produces

### Requirement: Entitlement distinguishes "not asked" from "no"

The system SHALL represent an entitlement as one of `Unknown` or `Known`. It SHALL NOT encode
"not yet determined" as a negative answer.

#### Scenario: A read that has not completed is not a denial

- **WHEN** a scope's entitlement is observed before any provider has answered for it
- **THEN** the observed value is `Unknown`
- **AND** no field of a `PurchaseState` is reported for that scope

#### Scenario: A paying customer is not denied

- **WHEN** a provider holds an active subscription for a scope and is read
- **THEN** the observed value is `Known` with `hasPro = true`
- **AND** `refresh()` for that scope succeeds

#### Scenario: The provider's flow type does not change the answer

- **WHEN** the provider exposes its state as a read-only `StateFlow` — the shape `asStateFlow()`
  returns — and that flow holds an active subscription
- **THEN** the observed value is `Known` with `hasPro = true`
- **AND** no part of the read path casts the provider's flow to a concrete mutable type

### Requirement: Account presence is independent of subscription

The system SHALL report `hasAccount` from the signed-in identity and `hasPro` from the
entitlement, so that neither is derived from the other.

#### Scenario: A free-tier signed-in user has an account

- **WHEN** a user is signed in with no active subscription
- **THEN** `hasAccount = true`
- **AND** `hasPro = false`

#### Scenario: No account means no entitlement

- **WHEN** no account is signed in
- **THEN** `hasAccount = false`
- **AND** `hasPro = false`

### Requirement: Verification is an explicit, reported transition

The system SHALL expose verification as a separate operation returning a success or a failure,
and SHALL NOT treat a failed verification as a denial without distinguishing the two.

#### Scenario: A failed verification is reported as a failure

- **WHEN** `refresh()` cannot reach the provider
- **THEN** it returns a failure
- **AND** the scope's entitlement is not overwritten with `Known(hasPro = false)`
- **AND** the previously observed value remains the last one actually established

### Requirement: Entitlement records who granted it

The system SHALL record which billing provider granted an active entitlement, or `null` when
none did.

#### Scenario: The granting provider is reported, not re-derived

- **WHEN** an entitlement is granted through GitHub Sponsorship
- **THEN** `provider = BillingProvider.GITHUB_SPONSOR`
- **AND** the value is read from the entitlement rather than inferred from the SKU string