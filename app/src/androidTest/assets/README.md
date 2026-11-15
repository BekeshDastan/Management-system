# Synthetic codec fixture

synthetic-first-frame.webp is a locally generated32×24 two-frame image (solid red, then solid blue), created with Pillow12/WebpAnimEncoder for the Android first-frame regression. No person, camera, third-party photograph or metadata source is used. It is included only in the instrumentation APK.

SHA256: a669035f74de598a56f82b8e41e2f3d9d8ccde95fd9d84b5d64869c00a674e89. Two frames were independently decoded with Pillow before the Android test. Expected result: a fresh opaque sRGB JPEG of the first red frame, without animation/private metadata.
