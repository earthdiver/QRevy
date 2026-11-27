// Copyright 2026 QRevy contributors. SPDX-License-Identifier: Apache-2.0
#include <ReadBarcode.h>
#include <ReaderOptions.h>
#include <ImageView.h>
#include <Barcode.h>
#include <cstdint>
#include <string>
#include <vector>

static bool decoded = false;
static std::string decodedText;
static std::vector<uint8_t> decodedBytes;
extern "C" int hasDecoded() { return decoded; }
extern "C" const char* textPointer() { return decodedText.data(); }
extern "C" int textLength() { return decodedText.size(); }
extern "C" const uint8_t* bytesPointer() { return decodedBytes.data(); }
extern "C" int bytesLength() { return decodedBytes.size(); }

// Native payloads are for ordinary reading only, never evidence for recovery candidates.
extern "C" int detectBoundaries(const uint8_t* rgba, int width, int height, int* coordinates)
{
    decoded = false;
    decodedText.clear();
    decodedBytes.clear();
    if (!rgba || !coordinates || width <= 0 || height <= 0 ||
        width > 4096 || height > 4096 || int64_t(width) * height > 8000000) return 0;
    try {
        const auto options = ZXing::ReaderOptions()
            .formats(ZXing::BarcodeFormat::QRCodeModel2)
            .tryHarder(true).tryRotate(true).tryInvert(false).tryDownscale(true)
            .returnErrors(true).maxNumberOfSymbols(16).textMode(ZXing::TextMode::Plain);
        const auto results = ZXing::ReadBarcodes(ZXing::ImageView(rgba, width, height, ZXing::ImageFormat::RGBA), options);
        int count = 0;
        for (const auto& result : results) {
            if (count == 16) break;
            if (!decoded && result.isValid()) {
                decoded = true;
                decodedText = result.text();
                decodedBytes = result.bytes();
            }
            const auto& p = result.position();
            for (const auto& point : {p.topLeft(), p.topRight(), p.bottomRight(), p.bottomLeft()}) {
                *coordinates++ = point.x;
                *coordinates++ = point.y;
            }
            ++count;
        }
        return count;
    } catch (...) { return 0; }
}
