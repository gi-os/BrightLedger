**Demo mode is gone**, as planned. It existed to take screenshots for a post, the screenshots
are taken, and it was never meant to stay.

Removed: the synthetic data set, its tests, the preference behind it, the Settings toggle, and
the three call sites that swapped fake rows in for real ones.

**`FLAG_SECURE` is unconditional again.** Demo mode was the only exception to it, so there is
no longer any way to photograph this app. Balances and merchant names stay out of screenshots
and out of the recents thumbnail, full time.

If you left demo mode on, this update simply shows your real accounts again. Nothing to
migrate. A one-line call at startup also drops the retired `demo_mode` key from preferences —
it was already ignored, so nothing changes, but a key by that name loitering in a finance app
reads badly to whoever looks next.

### One thing stayed

The single feed source. Before demo mode, the totals header and the repeats screen each
subscribed to the transaction query independently, which meant three subscriptions to the same
data and three places to keep in step. Demo mode forced them into one source with three
derivations, and that is better regardless of why it got written.
