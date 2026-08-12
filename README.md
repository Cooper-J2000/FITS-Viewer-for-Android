# FITS Viewer for Android

A native Android app inspired by NASA fv and SAO ds9, written in Kotlin with no
third-party FITS dependencies — the parser is implemented from scratch following
the FITS Standard 4.0.

## Features

| Module | Features |
|---|---|
| HDU browsing | Open .fits/.fit/.fts files; list all extensions (Primary / Image / BinTable / ASCII Table) with dimensions |
| Header | View complete 80-character cards; live search/filter by keyword, value, or comment |
| Image | BITPIX 8/16/32/64/-32/-64 with BSCALE/BZERO/BLANK support; pinch-to-zoom, double-tap zoom, pan; Linear/Log/Sqrt/Asinh stretches; brightness/contrast sliders; Gray/Gray-Inv/Heat/Viridis colormaps; Gaussian smoothing (σ=1/2); automatic downsampling of large images to prevent OOM |
| WCS | Reads CD / PC+CDELT / CDELT+CROTA2 matrices, TAN/SIN projections; touch readout of pixel value and RA/Dec (sexagesimal); one-tap "north-up, east-left" (north/east direction vectors derived numerically from the WCS, with automatic rotation + flip — works with any projection) |
| Regions | Load standard ds9 .reg files (image/physical/fk5/icrs coordinate systems; circle/ellipse/box/point/line/text/annulus; sexagesimal positions and "/'/d size units; color/text attributes); long-press to create a circular region; save back to standard .reg (fk5 output when WCS is available, image otherwise) |
| Tables | BINTABLE (L X B I J K A E D C M P/Q, including TSCAL/TZERO, vector columns, variable-length array markers) and ASCII TABLE (TBCOL + Aw/Iw/Fw.d/Ew/Dw); displays column names, units, and row numbers |
| Plotting | Plot by column (X = row number or any numeric column, Y = numeric column) or by row (all numeric columns of that row) as line/scatter/bar charts, with axis labels showing units |

## Build

1. Open this repository in **Android Studio** (Hedgehog 2023.1 or newer);
2. Gradle and dependencies are downloaded automatically on first open (internet
   required) — wait for the sync to finish;
3. Menu `Build → Build Bundle(s)/APK(s) → Build APK(s)`; the output is at
   `app/build/outputs/apk/debug/app-debug.apk`;
4. Or from the command line (with ANDROID_HOME configured): `./gradlew assembleDebug`.

- minSdk 24 (Android 7.0), targetSdk 35.
- No storage permissions required (files are accessed via the system file picker, SAF).
- FITS files can be opened directly from a file manager via "Open with → FITS Viewer".

## Code structure

```
app/src/main/java/com/fitsviewer/app/
├── fits/     FitsHeader.kt  card/header parsing
│             FitsFile.kt    2880-byte block HDU scanning, image reading
│             FitsTable.kt   on-demand BINTABLE / ASCII TABLE reading
├── wcs/      Wcs.kt         linear matrix + TAN/SIN projection, forward & inverse
├── region/   Ds9Region.kt   ds9 .reg parsing and serialization
├── render/   ImageRenderer.kt stretch/colormap/brightness-contrast/Gaussian smoothing
├── view/     FitsImageView.kt gesture zoom/north-up-east-left/region overlay/coordinate readout
│             ChartView.kt   line/scatter/bar charts
└── *Activity.kt             Main (HDU list) / Header / Image / Table / Chart
```

## Verification

`verify_wcs.py` replicates the exact formulas of Wcs.kt in Python and cross-checks
them against astropy: for TAN/SIN in various orientations (rotation, southern
sky, RA crossing 0°), the forward error is ~1e-10 arcsec and the inverse error
is < 0.003 pixels.

## Known simplifications

- Data cubes with NAXIS > 2: only the first slice is displayed;
- Variable-length array columns (P/Q): only the element count is shown, not expanded;
- Regions in galactic/ecliptic coordinates are skipped (with a count notice);
- For sky-coordinate regions, box/ellipse angles are approximated in image
  coordinates (no WCS rotation compensation).

## License

[MIT](LICENSE)
