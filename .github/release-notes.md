Three things: **pin accounts, hide amounts, and the bottom bar gets out of the way.**

### Pin an account

**Long-press it.** A pinned account floats above the rest and carries a small square before
its name.

Accounts were sorted by balance descending, which is a reasonable guess and usually the wrong
one — the account you actually check is your current account, not your biggest.

Pins are stored in preferences, not in the accounts table. That table is a *cache*: it gets
destructively migrated on any schema change and dropped entirely on disconnect, because the
server still has everything. A pin is the one thing on that screen the server does not know
and cannot send back, so storing it there would have made it the only local data in the app
and guaranteed the next migration quietly ate it.

### Hide amounts

New setting under **Settings → Security**. With it on, the spend total and account balances
start masked. **Tap the header on the home screen** to reveal them, and they re-hide the
moment the app leaves the foreground — the same rule the PIN lock already follows. A reveal
that survived backgrounding would make the setting decorative.

Two deliberate limits:

- **Transaction amounts are never masked.** Hiding is for the numbers someone can read at a
  glance and learn something from. A line item only means anything if you are already reading
  the screen properly.
- **The mask is fixed width**, not one bullet per digit. Digit-for-digit masking leaks the
  magnitude, and the difference between three figures and five is most of what a
  shoulder-reader is after.

### The bottom bar hides while you scroll

Scroll down and it goes; scroll up and it comes back. Direction of travel, not position, so a
long feed does not permanently hide its own controls once you are past the top.

This is BrightNotebook's `ChromeScroll` taken verbatim, tests and all, and it was worth taking
rather than rewriting. The non-obvious part: hiding the bar makes the list *taller*, and a
list already at its last pixel answers a taller viewport by clamping its own scroll — the
offset drops with no finger involved. A naive direction rule reads that as an up-scroll, shows
the bar, which shrinks the viewport, which pushes the content back down, and the bottom of the
list oscillates every time you reach it. So an up-scroll only counts while the list still has
somewhere forward to go.

The bar also paints its own background now and animates height only. `AnimatedVisibility`
defaults include a fade, and a bar sitting at partial alpha with the list moving underneath
reads as a rendering fault rather than an animation.
