# InstaEclipse 0.7.0-tomi.3

- Completely removes our inline follow-status label, its toggle, relationship cache, and profile hooks. Restores the upstream follower toast and its original setting.
- Replaces the profile-picture renderer. The previous version redrew Instagram's drawable, which can itself apply a circular mask. This version extracts the underlying bitmap and draws its entire rectangle directly, fitted without center cropping.
- Supports private/inherited bitmap fields in custom circular drawables, plus the bitmap passed to the image setter. It does not change the drawable's bounds or callbacks. A captured bitmap is never reused after the displayed drawable changes.
- Hooks inherited and concrete image-view draw methods and recognises unnamed image children inside the supported profile-picture wrappers. Disables outline clipping on the picture and named picture wrapper; disabling the feature restores clipping.
- Keeps **Miscellaneous → Full profile pictures** as the independent picture toggle. Force stop/reopen Instagram after activating the updated module.

## Download and installation

Download `InstaEclipse-0.7.0-tomi.3.apk` from Assets. This is an LSPosed/LSPatch module, not a patched Instagram APK. Back up module settings before replacing the old module: this experimental build uses a new debug signing key. For embedded LSPatch modules, update the module through your existing patch workflow. Do not uninstall Instagram just to replace the module.

## Verification and limits

The release pipeline runs tests, builds the APK, and verifies its signature before publishing. Android native-graphics regression tests check that previously transparent corners are visible, non-square images retain both edges, drawable state is preserved, recycled views do not reuse stale images, and outline clipping is restored when disabled.

Real-device compatibility with your Instagram build is not verified here. Only the full bitmap Instagram supplies can be shown; pixels already cropped out by the server cannot be recovered. Unrecognised image payloads fall back to Instagram's normal rendering, with a diagnostic entry in the module log.
