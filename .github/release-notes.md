**Demo mode**, so the app can be photographed without photographing a bank account.

> **Temporary.** This is here to take screenshots with and is scheduled for removal. All of
> it is one file, one preference and four call sites, so taking it out later is a delete
> rather than an unpick.

### Using it

**Settings → Demo → Demo mode.** Every figure on screen becomes invented, and screenshots
start working. Turn it off when you are done — it survives backgrounding on purpose, because
a screenshot session is a dozen trips in and out of the app and a flag that reset on its own
is how a real balance ends up in a published image.

### It replaces the data, it does not obscure it

The first version of this transformed real rows — renamed merchants, scaled amounts — and
that is wrong twice over. It leaks the shape of the real data: how many accounts you have,
how often you shop, roughly what you are worth. And it produces a bad screenshot whenever
reality is dull, which is most days: nothing spent today, one account, a quiet week.

Generating from nothing always looks like the app working, and cannot leak what it never read.

### What you get

Three accounts including a card with a negative balance, about three months of plausible
spending with a pending charge at the top, two paychecks, and four genuine monthly
subscriptions.

The swap happens at the single point every screen reads from, so the totals header, the
recurring detector and the transaction detail page all run their ordinary code against the
synthetic rows. What gets photographed is the real app with different numbers in it, not a
mock-up that will quietly drift away from the app.

That also forced a small refactor worth mentioning: the totals and the repeats each
subscribed to the feed independently, and three separate subscriptions would each have needed
the swap applied. Any one of them missing it would have put real numbers on screen next to
fake ones. There is one feed source now.

### Screenshots

`FLAG_SECURE` is dropped **only** while demo mode is on. Real balances and merchant names stay
out of screenshots and out of the recents thumbnail, and the only way to photograph this app
is to photograph invented figures. That is not a coincidence in the design; it is the point.

### On the tests

The synthetic subscriptions were originally planted 17 to 19 days apart, and the recurring
detector — correctly — called them all biweekly. The Repeats screen would have been
confidently wrong in a screenshot, and nobody would have found out until the camera was out.
A monthly charge needs sixty days of history before three occurrences exist, so the feed now
reaches back further than anyone will scroll. The tests name the four subscriptions and check
each one's cadence.
