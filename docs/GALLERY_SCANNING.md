# Scan a sensor code from a saved image

The embedded sensor scanner and the full-screen scanner offer **Select from Gallery**.
This includes adding an Anytime sensor and updating the current probe's QR from its
details page. Sibionics keeps its existing gallery buttons and uses the same decoder.

Select an image in Android's photo picker. Decoding runs on a worker thread, with
bounded image sizes, rotation and inverted-code support. QR, Data Matrix and the
barcode formats supported by the previous Sibionics image decoder are retained.
The app requests access only to the chosen image; no broad storage/media permission
is added. Gallery selection remains available if camera permission is denied or the
camera cannot start. Cancelling the picker or selecting an unreadable image keeps the
scanner open and leaves existing sensor data intact.

The decoded payload follows the same result/validation path as a camera scan. GS1
group separators are preserved. Choosing an image does not bypass sensor-type or
current-probe validation. The Anytime probe dialog still requires confirmation to
apply valid parameters. Updating parameters does not automatically rewrite history;
the separate confirmed History operation remains responsible for that.

Camera results from a previous camera binding are ignored while selecting/decoding
an image or after that binding is released. The full-screen scanner saves pending
picker/decoding state across activity recreation. Photo URIs and raw QR contents
are not included in decoder logs.

Host regression tests use real encoded PNG pixels and Android bitmap decoding:
QR, Data Matrix/GS separators, quarter-turn rotations, inverted QR, Code 128,
photo-picker content URIs, large images, blank/corrupt/missing/revoked images,
current-probe rejection/replacement with history preservation, and denying camera
permission followed by picker cancellation. Device-specific photo-picker and camera
behaviour still requires testing on a phone.
