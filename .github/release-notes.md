**More than one SimpleFIN server**, so a bank the hosted Bridge has never heard of can still
land in the same feed.

### Why

The Bridge is MX-backed and carries only US and Canadian institutions. Search it for Revolut
and it returns nothing. But SimpleFIN is a protocol, not a company — its own spec expects
institutions to run their own servers and treats the Bridge as the fallback for the ones that
do not. So the answer is not to bolt a second API onto the phone, it is to point the phone at
a second server.

[**simplefin-plaid**](https://github.com/gi-os/simplefin-plaid) is that server: it speaks
SimpleFIN v2 on the front and Plaid on the back, runs in Docker on your own machine, and makes
anything Plaid can reach look like an ordinary SimpleFIN account. BrightLedger needed no new
networking code for it at all.

### What changed

- **Connections screen.** Every server, its own last-sync line, its own errors, tap to sync
  just that one. Add and remove servers without touching the others.
- **An optional name per server**, so two Bridges are tellable apart.
- **Partial failures are normal now and behave like it.** One unreachable self-hosted broker
  no longer stops the Bridge accounts refreshing, and a failure names the server it came from.
  Only a sync where *nothing* got through interrupts you with a dialog.
- **Each connection keeps its own history window.** A shared last-success advanced by a
  healthy server would make a sick one skip everything that happened while it was down.

### The bug this release exists to prevent

SimpleFIN only guarantees an account id is unique **within one server**. The moment the phone
talks to two, the server's own id stops being a primary key: two Bridges each numbering an
account `1` collapse into one row. There is no crash and no error — the balance simply
flickers between two banks on every sync. Accounts now key on `connectionId|remoteId`, and the
transaction key inherits it.

### Upgrading

In place, with nothing to re-enter. Your existing Access URL is re-filed as the first
connection rather than re-claimed, and it inherits its last-sync so nothing re-downloads a
year. The cached transactions are dropped and re-fetched: the Room schema changed, and
inventing a connection id for rows that predate the concept would strand them as orphans no
sync ever touches. The cache is a copy of a server that still has everything.

### Still not here

Transfer detection — a card and its checking account both connected still double-count the
payment between them. No categories. No background sync, which matters more now: see the notes
on the repo about why an aggregator cannot tell you about a charge the moment it happens.
