# Test a debug APK on Android TV

The `TV debug APK` GitHub Actions workflow runs on pushes to non-`master`
branches. It runs the `common` unit tests, lints the beta app, builds a beta
debug APK, and uploads the universal APK as `SmartTube-TV-debug`. This keeps
the Android build toolchain on the GitHub runner.

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
   crashes. After adding translation, check start, pause/resume, seek, speed
   change, next video, and network loss. The video must remain playable if
   translation fails.

The debug APK has a different signing key from official SmartTube builds. If
`adb install -r` reports a signature mismatch, use a device without the
official beta installation; do not remove the user's existing app or data.
