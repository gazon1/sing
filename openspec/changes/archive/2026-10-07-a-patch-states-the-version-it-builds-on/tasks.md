# Tasks — a-patch-states-the-version-it-builds-on

Issue: #178 (version half). Spec: `offline-sync` REQ-OS-025.

- [x] `shared/` Add `server_version` to the shadow, with a default of 0 and an
      auto-migration. 0 is the honest reading for every existing row: the client never
      kept the version, so what it knows is nothing, and a non-zero default would be
      inventing a fact the server would then refuse. **Test:** the migration compiles and
      the schema exports.
- [x] `shared/` Take the patch's base version from the shadow rather than the entity.
      **Test:** the round-trip test — enqueue, push, enqueue, push — asserts the second
      patch states the version the server reported.
- [x] `shared/` Record the reported version on the same guarded statement that promotes
      the confirmed state, so a superseded answer cannot set it. **Test:** the version is
      on the shadow after a successful push.
- [x] `shared/` Carry the version through the builder's shadow write, which replaces the
      whole row. Found by a test failing: a fresh entity defaults it to 0, so the next
      local edit wiped what the push had just recorded.
- [x] `shared/` Use COALESCE in the confirm query so a response with no version leaves the
      stored one alone. Binding null would store a null. **Test:** enqueue, push with a
      version, push without one, enqueue — the next patch still states the version.
- [x] `shared/test/helpers` Give the transport fake a queue of answers for successive
      pushes. One fixed answer is enough for a single cycle and useless for anything
      spanning two, which is the only shape this behaviour has. **Test:** the tests script
      a different answer per push.

## Verification

- [x] `shared/` `./gw :shared:jvmTest`.
- [x] `shared/` `./gw detekt`.
- [x] `openspec validate --all --strict`.
- [x] The base version put back to the entity's dead field fails two tests.
