# Sky Up

Offline Android sky viewer. Point the phone at the sky; the view follows the Android rotation-vector sensor and uses the device location to calculate sky positions and magnetic declination.

## Features

- Sun, Moon, seven planets, and 46 bright named stars, calculated on-device.
- Faint background stars, soft star glows, and a blue sky-map interface.
- Pinch-to-zoom and on-screen zoom buttons. Planet discs become larger and reveal illustrated surface details as you zoom in; the view remains a 2D sky view (there is no top-down 3D solar-system mode).
- Settings for star names, planet names, planet detail, and field of view.
- Tabbed settings with a dedicated star section. Selecting a star opens a swipe-to-dismiss detail page with a schematic illustration and rounded reference values for selected well-studied stars.
- Compass reset/recalibration action with figure-eight guidance. Android controls the sensor's hardware calibration; the app resets its orientation filter and guides the user through the usual movement.
- Location permission is used for local sky calculations. The app does not request internet access.

## Performance and calibration update (0.3)

- Reuses the sky background gradient and ephemeris body positions between sensor-driven frames.
- Reuses matrix and projection buffers rather than allocating them during each frame.
- Batches horizon haze and ground-band quads into a small number of canvas paths and caches fixed azimuth trigonometry/skyline samples.
- Checks the night/day UI state once per minute instead of recalculating the Sun position every second.
- The compass reset now shows a figure-eight calibration guide, tracks movement across orientation axes, and gives a short vibration when the movement check completes. Android still owns the physical sensor calibration; this UI does not directly recalibrate hardware.
- Updates the launcher artwork to the supplied Sky Up logo for legacy and adaptive launcher icons.
- Keeps the ground and realistic daylight sky off by default, and caps sensor-driven scene redraws at about 30 frames per second to reduce rendering work.

## Build an APK automatically with GitHub Actions

Every push starts the **Build Android APK** workflow. On any branch, the APK is available under **Actions → latest workflow run → Artifacts → Sky-Up-debug-apk**. A push to `main` also updates the `latest` GitHub Release with the APK and its SHA-256 checksum.

The workflow uses Java 17, Gradle 8.7, Android Gradle Plugin 8.5.2, and Kotlin 2.0.21. It runs `:app:assembleDebug`. The produced debug APK is installable for testing. It is debug-signed, not a Play Store production release.

## Publish this project

Create an empty GitHub repository named `Sky-Up` (do not initialize it with a README or license), then run these commands from the project folder:

```bash
git init
git add .
git commit -m "feat: refresh Sky Up interface and add zoomable planet details"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/Sky-Up.git
git push -u origin main
```

For later changes:

```bash
git add .
git commit -m "feat: describe your change"
git push
```

In GitHub, open **Actions** to see the build. On the `main` branch, open **Releases → latest** to download the newest APK.

## Android installation and Play Protect

This app declares only foreground location permissions (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`) and does not declare SMS, notification-listener, accessibility, or internet permissions. Location is needed to calculate the local sky position. The debug APK is signed with the standard Android debug key.

A warning about installing an APK from outside Google Play is different from a **“Harmful app blocked”** Play Protect detection. A debug signature cannot make a sideloaded APK Play-verified, and a build setting cannot safely bypass a malware warning. For broad distribution without ordinary sideload prompts, publish it through Google Play using a Play testing track and Play App Signing. If Play Protect specifically says the app is harmful, review its developer guidance and submit an appeal if the detection is mistaken; do not disable Play Protect to get around it.
