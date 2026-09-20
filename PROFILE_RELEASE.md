# InstaEclipse 0.7.0-tomi.2

- Reuses the existing follower toast's `/friendships/show/` response for the profile label. Both displays use the same `followed_by` value; `following` from that response distinguishes mutual follows (**Friends**).
- Removes the floating window overlay and the separate friendship-model detector.
- Displays a small label inside the scrolling profile header, beside the native Threads control when the row has room. On narrower layouts it uses the next line; custom containers use the existing native header text. The label inherits the surrounding text styling.
- Independent **Miscellaneous → Show follow status in profile** toggle, in both the companion app and Instagram's module menu. It works with the original follower toast switched off. The original toast remains independently selectable.
- Missing or unrecognised data stays hidden. A newer response takes precedence over an older callback. Profile changes remove the previous label; changing accounts clears cached relationship data.
- Fixes String, direct/sliced ByteBuffer, and wrapped response decoding without consuming Instagram's buffers. Uses no extra network requests.
- Keeps the full-profile-picture option from the first build.

## Installation

Download `InstaEclipse-0.7.0-tomi.2.apk` from Assets. This is the InstaEclipse module for LSPosed/LSPatch, not a patched Instagram app.

This experimental APK is debug-signed with a different key from tomi.1. Back up module settings, replace only the old **InstaEclipse module**, then enable this version and force stop/reopen Instagram. Do not uninstall Instagram for a module signature conflict. With an embedded LSPatch module, update the embedded module using your existing patch workflow.

## Validation

The release workflow runs the JVM tests (including response parsing, account/profile isolation, out-of-order callbacks, and Android view layout/removal tests), builds the APK, and verifies its signature before publication. Actual Instagram layout compatibility still needs device testing. Unsupported header layouts remain untouched, with no floating fallback. The label appears only when Instagram's existing friendship response is observed; reopen or refresh the profile after enabling it if necessary.
