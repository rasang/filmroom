# v5 Portra arrow shape revision

Only the lower-rail arrow heights of Portra160, Portra400 and Portra800 are changed. All other stocks, upper rails, numbers, positions and spacing remain frozen to the preceding reviewed candidate. The revision is selected for the beta.3 development release; it is not a claim of factory-calibrated dimensions.

Each stock is measured separately against its own actual-film reference. Source image-native height/width medians are about0.52,0.54 and0.60 respectively, with blur, threshold and photographic-projection uncertainty. Portra160 and800 were substantially too tall;400 receives only a conservative small reduction. The source values are image-relative evidence, not factory dimensional measurements.

The compiler applies vertical-only bilinear resampling to the existing authored arrow atoms at scales0.50/0.92/0.56. The x mapping is identity, and every visible45%-contrast x edge and vertical center remains fixed. Portra160 uses a quarter-pixel resampling phase to retain its low-resolution tip and visible center; the glyph is not translated. Bilinear interpolation creates no color extrema beyond the original arrow RGB range. Source photographs are never used as program pixels.

At the explicit45% measurement threshold, before→after readable boxes are:
- Portra160:7×8→7×4 pixels.
- Portra400:16×11→16×9 pixels. Weak antialias fringes leave the R>80 bbox at17×11; at40% contrast the new box is16×10 and at45–60% it is16×9. The reference comparison uses the stated contrast threshold, not an arbitrary fixed RGB cutoff.
- Portra800:12×13→12×7 pixels.

Only pixels inside declared arrow boxes may differ. The manifest records every old/new readable/full bbox and allowed change box. Numbers and all other pixels are checked byte-for-byte. Absolute film pitch, number positions, finite coverage and crop center are unchanged.

Portable inputs: Portra160 reads its original root v1 path; Portra400/800 read their frozen v4 paths. Other six current versions are copied byte-for-byte. Run:

python3 tools/medium120/v5/flatten_portra_arrows.py --asset-root app/src/main/assets/medium120 --output /tmp/filmroom120-v5

Only the three changed PNGs need new versioned asset paths. Preserve all prior published/accepted definitions for frozen queues. Do not include review images, reference photographs, private research paths or preparation scripts in the application package.
