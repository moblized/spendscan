# SpendScan

Scan your receipts and see where your money goes. An Android app built with Kotlin and Jetpack Compose.

## What it does

- **Scan a receipt.** Google's document scanner finds the edges and flattens the photo, and long receipts can span several pages. Text is read on the phone.
- **Check it, then save it.** The store, date, total, tax, tip, payment and each line item are filled in for you. Anything the app wasn't sure of gets the yellow highlighter, and it tells you if the items don't add up to the total.
- **See your month.** Your spending by category and by store, compared with the same point last month, with a bar for each day. Tap a day, store or category to see those receipts.
- **Set budgets.** One for the whole month, one per category, or both. You get a note when a save takes a category past 80% or over budget.
- **Search your receipts.** Finds stores and the items on them, so "milk" shows how often you bought it and what you paid each time.
- **Everything else.** Add a receipt by hand, share a screenshot of a receipt to the app, catch duplicates, export to CSV, and long-press the icon for a scan shortcut.
- **Dark mode**, with a dark version of the launcher icon to match.

## Privacy

Receipts and photos are kept in the app's private storage, and the only thing the app does online is check this repo's Releases for updates. The scanner and text reader run through Google Play services, on the device.

If your phone backs up to Google, Android includes the receipt list in that backup. Photos are left out, because they'd overrun the 25 MB limit and cancel the whole backup. They do move to a new phone in a direct transfer.

## Install

Every push to `main` builds a signed APK. Download it from the [Releases](../../releases) page on your phone and install it (allow "Install unknown apps" for your browser the first time). After that the app offers updates itself. Updates install over the previous version because every build uses the same signing key in `keystore/`.

## How the build works

`.github/workflows/build.yml` runs the unit tests, renders every screen to PNGs with Roborazzi (published to the `screenshots` prerelease), builds and signs the APK, and attaches it to a GitHub release.

To build locally you need JDK 17 and the Android SDK:

```sh
./gradlew testDebugUnitTest assembleRelease
```

## Licenses

The app bundles [Sometype Mono](https://github.com/googlefonts/sometype-mono) and [Doto](https://github.com/oliverlalan/Doto), both under the SIL Open Font License. Copies are in `licenses/`.
