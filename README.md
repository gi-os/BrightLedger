# BrightLedger

[**⬇ Download the latest APK**](https://github.com/gi-os/BrightLedger/releases/latest) · free, open source.

Bank balances and transactions on the **Light Phone III**, over the
[SimpleFIN](https://www.simplefin.org/protocol.html) protocol. Shows up on the phone as
**Ledger** (`com.gios.brightledger`).

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightLedger.png" alt="Scan to open BrightLedger in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightLedger there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

**Current version: v1.7.x.** See [Version history](#version-history).

Open it and the first thing on screen is what you spent today, then this month, then what
is in the accounts, then everything that has happened. That order is the design: "can I buy
this" is a more common question than "what did I buy", and on this panel the answer to the
common one should not require scrolling.

## There is no server

This is the part worth understanding before you connect a bank to anything.

BrightLedger has **no backend**. No account to create, no API key compiled into the APK, no
service in the middle holding a token on your behalf. The phone talks directly to the one
SimpleFIN server you chose, using a read-only credential that exists only on that phone.

That is possible because SimpleFIN's whole design is a single credential the *user* obtains
and hands to an application:

1. On a computer, go to [bridge.simplefin.org](https://bridge.simplefin.org) and connect
   your bank. You get a **setup token** — a long Base64 string.
2. Turn that string into a QR code and scan it with the app. (Or paste it. The token is a
   couple of hundred characters and this phone has a small keyboard, so scanning is the
   pleasant option.)
3. The app POSTs the token once to claim a permanent **Access URL**, encrypts that URL to
   the phone, and throws the token away.

From then on every sync is one authenticated `GET /accounts` from your phone to that
server. Nothing else, ever, to anywhere.

**Revoking access is done on the Bridge, not here.** Disconnecting in Settings erases the
credential and the cached data from the phone; it cannot revoke a grant that lives on
someone else's server. The confirm dialog says so, because a user who believes otherwise
leaves a live read credential behind.

The Bridge charges a small monthly fee, paid by you to them. BrightLedger has no billing
and never sees it.

## More than one server

The hosted Bridge is MX-backed and carries only US and Canadian institutions. Search it for
Revolut and it returns nothing.

SimpleFIN is a protocol rather than a company, though, and its own spec expects institutions
to run their own servers, treating the Bridge as the fallback for the ones that do not. So
BrightLedger holds a *list* of servers, and the fix for an unsupported bank is to point it at
another one.

[**simplefin-plaid**](https://github.com/gi-os/simplefin-plaid) is that other one: SimpleFIN v2
on the front, Plaid on the back, Docker on your own machine. Anything Plaid can reach becomes
an ordinary SimpleFIN account. The phone needed no new networking code for it — it already
speaks the protocol and does not care whose server is answering.

The one thing that had to change is the account key. SimpleFIN only guarantees an account id is
unique **within one server**, so with two servers the server's own id is not a primary key any
more. Two Bridges each numbering an account `1` would be one row, and the symptom is not a
crash but a balance that flickers between two banks on every sync. Accounts key on
`connectionId|remoteId`; the transaction key inherits it.

Connections sync one at a time and report per server, so an unreachable self-hosted broker
cannot stop the Bridge accounts refreshing, and each keeps its own history window — a shared
last-success advanced by a healthy server would make a sick one skip everything that happened
while it was down.

## Where the credential lives

The Access URL is the credential — it carries HTTP Basic auth inside the URL itself — so it
is treated as one everywhere:

- **Encrypted at rest** under a random vault key, which is itself wrapped by a
  non-exportable **AndroidKeyStore** key. Pulling `shared_prefs` off the phone yields a blob
  that nothing else can open.
- **Optionally PIN-locked** as well. Turn on PIN lock and the vault key is wrapped a second
  time by PBKDF2 over your PIN, and is absent from memory whenever the app is not in the
  foreground. Both layers are required, which is the point: four digits alone would be
  10,000 guesses, and the KeyStore layer is what stops those guesses being made off-device.
- **Split on parse.** `AccessUrl` tears the credentials out of the string on arrival and
  keeps them only as an `Authorization` header. `toString`, the logged base URL and the
  request URL never contain them. There is exactly one accessor that returns the whole
  string, it is called from one place, and what it returns goes straight into the vault.

There is a second reason for that split, and it is a trap worth naming: **OkHttp parses
userinfo in a URL and then does not send it.** Build a request from the raw Access URL and
it authenticates with nothing and comes back 403. The header is not tidiness, it is the only
thing that works.

`FLAG_SECURE` is set on the window, so balances and merchant names stay out of screenshots
and the recents thumbnail.

## What it shows

**Home** — spent today in display type, this month underneath, then a staleness line that is
never hidden. A finance app showing a confident number without saying how old it is has told
a small lie, and this phone is offline a lot. Below that: account balances, then a merged
transaction feed across every account, pending first.

**A transaction** — the fields the feed row had to drop: account, posted vs transacted date,
pending state, reference. It re-reads its own row from the database rather than taking a
snapshot, so a pending charge that settles under you updates instead of going stale.

**Repeats** — the subscriptions, and what they cost *per month in total*, which is the
number almost nobody knows. Worked out on the phone from your own history: same merchant,
about the same amount, at a steady interval, three times or more.

## Finding the repeats

The cadence maths is the easy half. The hard half is that banks decorate a descriptor
differently on every single charge — a store number, a city, a terminal id, an order
reference, a date — so twelve months of Spotify arrives as twelve distinct merchants and
nothing recurs at all.

So `Recurring.normalize` strips prefixes (`POS DEBIT`, `RECURRING PAYMENT`, `CHECKCARD`,
a dozen more) *before* dropping every non-letter, because otherwise the digit strip eats the
date out of `POS DEBIT 0412 NETFLIX` and leaves `POS DEBIT` as part of the merchant name for
every card transaction in the account — collapsing all of them into one enormous fictional
merchant. Then the first two words identify it, which is short enough that a trailing city
name does not split one merchant in two.

After that: cluster by amount, because one merchant can hold a monthly plan and a yearly
add-on and averaging them produces a number matching neither. Then require **every** gap to
match the median cadence, not just the average — without that check, three visits to the
same coffee shop at similar prices read as a subscription, and that was most of what the
screen showed before the check existed.

## Syncing

Every sync re-reads an overlapping **14-day window** rather than "everything since last
time". Banks post transactions dated several days before they appear, and pending charges
change amount when they settle, so a window starting at the last sync silently misses both.
The overlap costs a few kilobytes and makes the sync self-healing: every row in the window is
re-fetched and upserted, so one bad sync is repaired by the next good one.

Transactions are keyed on `(accountId, txnId)`. The protocol only guarantees a transaction id
is unique *within* an account and explicitly permits two accounts to reuse one, so keying on
the id alone is not a crash — it is one account's rows quietly overwriting another's, which
looks exactly like a bank dropping transactions at random.

Pending rows that vanish from the server get swept, scoped to the window that was actually
fetched. An authorization hold released, or a restaurant tip adjustment that reposts under a
new id, otherwise haunts the feed forever and inflates the month's spend.

The app is offline-first in the strict sense: the screens read the local database and only
the local database. A failed refresh changes nothing on screen except the staleness line.

## Money is never a float

Amounts arrive from SimpleFIN as *numeric strings* and are parsed straight into whole cents
via `BigDecimal`. Nothing in the app holds an amount as a `Double`. Summing a month of
`0.10` in floating point is how a spend total ends up reading `1847.9999999998`, and there is
a test that does exactly that a thousand times over.

Positive is money arriving — that is SimpleFIN's convention, not a choice made here, and it
is why the summary negates before calling anything "spent". Deposits are excluded from the
spend figures rather than netted against them: a paycheck landing on the 1st would otherwise
make the whole month read as though nothing had been spent.

## What it does not do yet

- **A card and its checking account both connected will double-count the payment** between
  them. There is no transfer detection in v1.
- No budgets, no categories, no charts. SimpleFIN's optional `extra.category` is not yet
  read.
- No background sync. It refreshes when you open it and when you press SYNC.
- Custom currencies (the protocol allows a URL in place of an ISO code) parse fine but are
  rendered without a symbol.

## Testing, and its limit

The money maths, the URL and token handling, the SimpleFIN parser, the recurring detector and
the month-boundary arithmetic are all plain JVM code with no Android imports, and all of them
are tested — `AccessUrl` deliberately uses `java.util.Base64` rather than the Android one so
that the credential handling, the part most worth testing, needs no device.

The fixtures are built from the **SimpleFIN v2.0.0 specification's own examples**, not from
recorded traffic, because the sandbox this was written in cannot reach
`bridge.simplefin.org`. Worth stating plainly: the tests prove the parser matches the
document, not that it matches the Bridge. The spec publishes a reusable demo token, and
running it against that on a real device is step one of trusting this.

Note that `org.json` ships in `android.jar` as stubs that return null under unit tests, so
the real library is on the test classpath — without it every parser test would pass
vacuously against an empty object.

## Why this isn't a LightOS SDK tool

The SDK's dependency allowlist has no OkHttp-with-arbitrary-hosts problem, but it does ban
`Context`, which `EncryptedSharedPreferences` and `AndroidKeyStore` both need. Storing a live
bank credential in plain DataStore was not an acceptable trade, so this is a plain APK — the
same reason [BrightPasses](https://github.com/gi-os/BrightPasses) and
[BrightTip](https://github.com/gi-os/BrightTip) are.

## Notes for the LPIII panel

- Greyscale on matte glass, so there is no red and green to lean on: the **sign character**
  is the only thing distinguishing money in from money out, and it is always drawn.
- Amounts are right-aligned in a column that has to line up down the whole screen, so every
  row's label block takes `weight(1f)` — never a fractional `fillMaxWidth`, which compounds
  against its parent and drifts the column as nesting changes.
- Pending amounts are dimmed rather than badged, because the amount is the thing most likely
  to change when the charge settles.
- The brightness wheel scrolls every list.
- Text uses Akkurat when LightOS provides it, so the app matches the system UI.

## The journal bus

Other apps on the phone can read three things without BrightLedger opening: what was
spent on a day, what is about to be charged, and how fresh those answers are. It is an
exported, **query-only** ContentProvider — authority `com.gios.brightledger.days` — built
for LightNotebook's journal and usable by anything else on the device:

| Path | Columns |
| --- | --- |
| `spending/<yyyy-MM-dd>` | `postedAt` (Long, epoch ms), `merchant` (String), `amountCents` (Long, charges negative), `account` (String), `pending` (Int 0/1) |
| `bills/upcoming` | `dueEpochDay` (Long), `merchant` (String), `amountCents` (Long, negative), `cadenceDays` (Int) — next 35 days, soonest first |
| `status` | `syncedAt` (Long, epoch ms of the last successful sync; 0 = never) |

The date in the path is a **local** calendar day, midnight to midnight in the phone's
zone — 23 or 25 hours around a DST change, never a flat 24. Inserts, updates and deletes
throw; any internal failure reads back as an empty cursor, because a journal page must
not crash over a ledger hiccup. Everything served is the on-phone Room cache — nothing a
consumer does here can reach a bank, and reads work (staler and staler, as `status`
admits) even while the app is PIN-locked.

## Version history

| Version | What changed |
| --- | --- |
| v1.7.x | **Demo mode removed**, as planned. `FLAG_SECURE` is unconditional again, so there is no way to photograph the app. The single feed source it forced stays: the totals header and the repeats screen used to subscribe to the transaction query independently |
| v1.6.x | **Demo mode**, temporary and for screenshots: a synthetic ledger replaces the real one at the single point every screen reads from, so the header, the recurring detector and the detail page all run their ordinary code on invented rows. `FLAG_SECURE` is dropped only while it is on, so the only way to photograph the app is to photograph fake figures |
| v1.5.x | **The journal bus, and a nightly sync to feed it.** A read-only ContentProvider at `content://com.gios.brightledger.days` that other Light apps — LightNotebook's journal first — can query: `spending/<yyyy-MM-dd>` for a local calendar day's transactions (`postedAt` ms, `merchant`, `amountCents` signed as stored, `account`, `pending`), `bills/upcoming` for the recurring detector's projected charges over the next 35 days (`dueEpochDay`, `merchant`, `amountCents`, `cadenceDays`), and `status` for `syncedAt`. Writes throw, reads never do — anything unexpected is an empty cursor — and day windows are local-midnight-to-local-midnight, so DST days keep their 23 or 25 hours. Plus a WorkManager sync that runs once a day while charging (Settings → "Nightly sync while charging", default on) so the journal reads this morning's numbers, not last-open's; with a PIN set the credential stays sealed and the overnight run quietly skips |
| v1.4.x | **Pin accounts, hide amounts, auto-hiding bottom bar.** Long-press an account to float it above the rest, with pins in preferences rather than the cache table that gets destructively migrated. A setting masks the spend total and balances, tap the header to reveal, re-hides on background. The bottom bar hides as you scroll down using BrightNotebook's `ChromeScroll` — including its fix for a list at its last pixel clamping its own scroll and oscillating the bar |
| v1.3.x | **Fixes a scanned setup token silently doing nothing.** `connect()` guarded on the same busy flag as the background refresh, so a scan landing inside a sync's ninety-second network window returned with no request, no dialog and nothing in the field — indistinguishable from a broken camera. Claiming has its own flag now, and says so when it refuses |
| v1.2.x | **Fixes the v1.1 upgrade**, which opened on "Connect a bank" as though the existing bank had been forgotten. Nothing was deleted: `isConnected` counted only migrated connections, the start destination was chosen from it, and the migration ran only inside a sync the setup screen never triggers. Migration now runs at startup and after unlock as well as in sync, an unmigrated v1.0 credential counts as connected, and a migration attempted with the vault shut declines rather than consuming it |
| v1.1.x | **More than one SimpleFIN server.** Connections screen with per-server status, errors and single-server retry; optional per-server name; partial sync failures no longer interrupt or block healthy servers; per-connection history windows. Accounts re-keyed on `connectionId|remoteId`, because a SimpleFIN account id is only unique within one server. Upgrades in place: the existing credential is re-filed as the first connection and keeps its last-sync |
| v1.0.x | **First release.** SimpleFIN v2 client with one-time token claim and `403` handling; balances and a merged transaction feed; spend-today and spend-this-month summary; per-transaction detail; recurring-charge detection with per-month total; Access URL sealed under AndroidKeyStore with an optional PIN layer; offline-first Room cache with a 14-day overlapping sync window and pending sweep |

## Licence

MIT.

<!-- bright-footer:begin -->
---

## Bright\*

**It's not Light, it's Bright.**

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightNotebook](https://github.com/gi-os/BrightNotebook) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->

## Support

These apps are free, open, and built on my own time. Sponsorship pays the bills that don't go away: build servers, test hardware, and the crash reporter that keeps them shipping. Donation or not my code is always free for the world to use.

[Sponsor on GitHub](https://github.com/sponsors/gi-os)
