**Fixes a scanned setup token silently doing nothing.**

If you scanned a QR and the app just returned to the previous screen with the field still
empty, no error and nothing on the server, this is why.

`connect()` guarded on the same busy flag the background refresh uses. The home screen starts
a refresh every time the app comes to the front, that refresh can sit on the network for up to
ninety seconds, and walking to the scanner takes less than that. So a scan landing inside the
window hit a guard belonging to an unrelated job and returned with no request, no dialog, and
nothing written anywhere. From the outside it is indistinguishable from a broken camera. The
empty server log is what finally placed it.

Claiming a token now has its own flag and refuses only a second claim — and when it refuses,
it says so. The silence was the real defect. A guard that fails closed and quiet turns a
two-minute bug into an evening.

Also retires the scanner hint inherited from BrightAuthenticator, which was still telling you
to point the camera at a site's 2FA page.
