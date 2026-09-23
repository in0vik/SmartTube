# Test a debug APK on Android TV

The `TV debug APK` GitHub Actions workflow runs on pushes to non-`master`
branches. It runs the `common` unit tests, lints the beta app, builds a beta
debug APK, and uploads the universal APK as `SmartTube-TV-debug`. This keeps
the Android build toolchain on the GitHub runner. The runner uses Java 11 for
the repository's Robolectric 4.6.1 tests.

1. Download the APK from the workflow run's **Artifacts** section.
2. On the Android TV or Google TV device, enable developer options and network
   debugging. Pair with `adb pair DEVICE_IP:PAIR_PORT` if the device offers
   wireless pairing; then connect with `adb connect DEVICE_IP:DEBUG_PORT`.
   Older devices may use `adb connect DEVICE_IP:5555` directly. Accept the
   debugging authorization prompt on the TV.
3. Confirm the device with `adb devices -l` and install the APK with
   `adb install -r PATH_TO_APK`.
4. Run `scrcpy` to view and control the device from the Mac. If audio forwarding
   is unavailable, run `scrcpy --no-audio` and listen on the TV.
5. During playback, use `adb logcat -v time AndroidRuntime:E '*:S'` to catch
   crashes. To test voice-over, open the English talk
   `https://www.youtube.com/watch?v=arj7oStGLkU`, then choose **Russian
   voice-over** in the player controls. Check pause/resume, seek, speed change,
   Home/return, and next video. The original volume must recover exactly when
   voice-over stops. The video must remain playable if translation fails.

Voice-over currently uses Yandex Browser's unofficial translation endpoint.
It requests English-to-Russian audio for on-demand YouTube videos. If the
service requests a source-audio upload or login, the control reports that
translation is unavailable and leaves playback untouched. The returned audio
URL is short-lived and is never stored. Protocol behavior is based on
[`voice-over-translation`](https://github.com/ilyhalight/voice-over-translation)
and [vot.js](https://github.com/FOSWLY/vot.js).

The debug APK has a different signing key from official SmartTube builds. If
`adb install -r` reports a signature mismatch, use a device without the
official beta installation; do not remove the user's existing app or data.
