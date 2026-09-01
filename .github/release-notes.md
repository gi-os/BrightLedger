**First release.** Bank balances and transactions on the Light Phone III, over
[SimpleFIN](https://www.simplefin.org/protocol.html). No backend, no API key, no account to
create — the phone talks straight to the SimpleFIN server you connected.

### What's in it

- **Connect by QR or paste.** Create a setup token at
  [bridge.simplefin.org](https://bridge.simplefin.org), scan it, done. The token is claimed
  once for a permanent read-only Access URL; a `403` on claim is reported as what it means —
  the token may be compromised — rather than as a generic failure.
- **Home.** Spent today in display type, spent this month underneath, then account balances
  and a merged transaction feed across every account, pending first. The staleness line is
  never hidden.
- **Transaction detail.** Account, posted vs transacted date, pending state, reference. Reads
  its own row live, so a pending charge that settles updates instead of going stale.
- **Repeats.** Subscriptions found on the phone from your own history, and what they cost per
  month in total. Same merchant, about the same amount, a steady interval, three times or
  more.
- **The credential is sealed.** The Access URL carries HTTP Basic auth, so it is encrypted
  under a random vault key wrapped by a non-exportable AndroidKeyStore key. Optional PIN lock
  adds a PBKDF2 layer and drops the key from memory whenever the app leaves the foreground.
  `FLAG_SECURE` keeps balances out of screenshots and the recents thumbnail.
- **Offline-first.** Screens read the local Room cache and only that, so a failed refresh
  changes nothing but the staleness line.

### Three things worth knowing

**OkHttp parses userinfo in a URL and then does not send it.** Build a request from the raw
Access URL and it authenticates with nothing and returns 403. `AccessUrl` splits the
credentials into an `Authorization` header on arrival, which is also what keeps them out of
every log line and out of `toString`.

**Transactions are keyed on `(accountId, txnId)`.** The protocol only guarantees a
transaction id is unique *within* an account and explicitly permits two accounts to reuse
one. Keying on the id alone is not a crash — it is one account's rows silently overwriting
another's, which looks exactly like a bank dropping transactions at random.

**Every sync re-reads a 14-day overlapping window**, not "everything since last time". Banks
post transactions dated days before they appear and pending charges change amount when they
settle, so a window starting at the last sync misses both. The overlap makes a bad sync
self-repairing.

### Not yet

A card and its checking account both connected will double-count the payment between them —
there is no transfer detection in v1. No budgets, no categories, no background sync.

### Tested, with a caveat

44 unit tests over the money maths, token and URL handling, the parser, the recurring
detector and the month boundary. The fixtures come from the SimpleFIN v2.0.0 spec's own
examples rather than recorded traffic, so they prove the parser matches the document, not
that it matches the Bridge. The spec publishes a reusable demo token — running against that
on device is step one.
