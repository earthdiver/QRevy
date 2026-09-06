# The bridge deliberately lives in ZXing's package to call its package-private payload parser.
# Keep both sides stable across R8 changes; dependency upgrades are covered by regression tests.
-keep class com.google.zxing.qrcode.decoder.ZxingPayloadBridge { *; }
-keep class com.google.zxing.qrcode.decoder.DecodedBitStreamParser { *; }
