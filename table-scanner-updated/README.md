<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/7bcd58aa-2ebc-4764-b9cd-b9bed9b3f294

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Measurement pipeline

The app now includes an on-device, classical-CV measurement pipeline under
`app/src/main/java/com/example/measurement/`:

- `CaptureQuality.kt` — blur (Laplacian variance), low-pass-filtered tilt
  gating, hard-shadow detection, and an in-frame check for the reference card.
- `CardDetector.kt` — contour-based card detection with subpixel corner
  refinement (`cornerSubPix`); supports finding one or two cards.
- `Rectifier.kt` — lens-distortion correction from `CameraCharacteristics`,
  homography computation, and perspective rectification to a known-scale
  top-down image.
- `TableMeasurer.kt` — table-edge measurement in the rectified image and
  cross-validation across shots/cards into a reported range, never a bare
  point estimate.
- `MeasurementPipeline.kt` — orchestrates the above over a `CaptureSession`.
- `CaptureSession.kt` — in-memory holder for the 2-3 shots of a measurement
  run.

`CameraScreen.kt` and `ReviewScreen.kt` were updated to drive the multi-shot
capture flow, the post-capture quality gate (with plain-language retake
prompts), and to display the rectified image with a confidence range instead
of a decorative fixed-pixel grid.

This pass intentionally does **not** call the Gemini API (see
`metadata.json`'s `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`) or add ARCore
plane detection — both are noted as possible future signals, not implemented
here. The `org.opencv:opencv` Maven Central coordinate is used for OpenCV; if
it doesn't resolve in your environment, use the OpenCV Android SDK's
`sdk/java` module instead (see opencv.org/releases).

The validation harness (`app/src/test/java/com/example/measurement/TableMeasurementValidationTest.kt`)
covers the pure-Kotlin cross-validation/aggregation logic against
tape-measured ground-truth fixtures; a matching `androidTest` harness (not
included yet) would be needed to validate the OpenCV-backed detection and
rectification stages end-to-end from real photos, since those require native
OpenCV bindings that only load on a device/emulator.
