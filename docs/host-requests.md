# Host requests

Nothing yet.

TunaReceive does not change TunaStream. If a later phase needs a PC-side endpoint, add it here first and wait. Do not invent a host API, and do not call TunaStream's web admin API from the app.

When a request is real, include method, path, request, response, and auth. Auth is the paired client certificate. Never a password typed on the TV.

Phase 1 needs no host change. Pairing and streaming use the existing GameStream protocol.

Phase 3 ideas, not requested yet: stream health, couch buttons, receive a file from the PC, QR pairing. Do not implement those against a guessed URL.
