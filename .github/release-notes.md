**Fixes the v1.1 upgrade, which opened on "Connect a bank" as though your bank had been
forgotten.** It had not been. Install this and it comes back on its own — no new token.

### What actually happened

Nothing was deleted. The sealed credential sat in preferences the whole time. Three pieces
that each looked right deadlocked around it:

- `isConnected` counted only connections that had been migrated to the new list.
- The navigation graph chose its start destination from `isConnected`.
- The migration that fills that list ran only inside a sync — and a sync is triggered by the
  home screen, which you never reached.

So the app asked to connect a bank, and connecting one was the only thing that could not fix
it. From the outside that is indistinguishable from a finance app losing your bank, which is
about the worst thing this app could appear to do.

### The fix

`isConnected` now counts an unmigrated v1.0 credential, and the migration runs at startup as
well as inside sync. Both are needed and neither is redundant: startup misses the PIN-locked
case, where no key exists yet, and sync alone was the bug. With a PIN set it also runs
immediately after unlock, before the graph composes, and the view model re-reads the list at
that point — it was built at construction, which for a locked install is before any
credential is readable.

A migration attempted with the vault shut now declines instead of consuming the credential,
so the retry after unlock still has something to re-file. There is a test for that, and for
the stranded state itself.

### If you already tried to fix it by hand

If you pasted a fresh token at that setup screen, you will have **two rows** under Settings →
Servers after this update: the one you added, and your original coming back from migration.
Same server, same accounts, and the balances and month total will read double until you
remove one. Either is fine to keep — remove the other with Settings → Servers → Remove.

**This is a one-time upgrade path, not something that recurs.** Once the credential is on the
new list the legacy key is gone and the migration never runs again. Ordinary updates from
here do not touch your connections.
