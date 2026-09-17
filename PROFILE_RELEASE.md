# InstaEclipse profile enhancements

Personal experimental fork of ReSo7200/InstaEclipse 0.7.0.

- **Full profile pictures:** draws the complete image supplied by Instagram, fitted without the circular mask, on supported profile-header and expanded-profile image views. Does not recover pixels already cropped by Instagram's server.
- **Profile relationship badge:** shows **Friends** for mutual follows, **Follows you**, **You follow them**, or **Does not follow you**. Unknown or unsupported profile data is shown as **Follow status unavailable**, never guessed. Friends means mutual following, not Instagram Close Friends.
- Both settings are enabled by default and available under **Miscellaneous** in the module and in-app menu. Reopen the profile after changing photo settings.
- English and Hungarian labels. Settings are included in backup/restore.

## Install

Download `InstaEclipse-0.7.0-tomi.1.apk`. This is the **module APK**, not a patched Instagram APK. Enable it for Instagram using LSPosed, or patch Instagram using JingMatrix LSPatch on non-rooted phones, following the upstream README. Force stop and reopen Instagram.

This personal prerelease is debug-signed and cannot update the upstream signed module in place. Back up your module settings before replacing the old **InstaEclipse module**; do not uninstall Instagram for a module-signature conflict. Future debug builds may also need module replacement because their signing key can change.

## Verification and limitations

The release workflow runs all JVM unit tests, builds the APK, and verifies its signature before publishing. Android/Instagram device behavior has **not** been verified. The hooks target named Instagram view and profile classes; Instagram updates or alternate profile layouts may be unsupported. The relationship label uses the displayed profile's own model, requires a matching username, and sends no additional requests. If data is missing, it reports unavailable. The badge appears at the upper right below the profile toolbar.
