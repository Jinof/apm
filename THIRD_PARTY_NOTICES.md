# Third-party notices

## OpenCV Android

- Component: `org.opencv:opencv:4.13.0`
- Project: https://github.com/opencv/opencv
- License: Apache License 2.0

## ONNX Runtime Android

- Component: `com.microsoft.onnxruntime:onnxruntime-android:1.24.3`
- Project: https://github.com/microsoft/onnxruntime
- License: MIT License
- Use: on-device DINOv2 ONNX inference; OpenCV remains in use for YuNet/SFace.

## OpenCV YuNet face detector

- Asset: `android/app/src/main/assets/models/face_detection_yunet_2023mar.onnx`
- Source: `opencv/face_detection_yunet` commit `3cc26e7f1014a5ee5d74a42acee58bafc9d0a310`
- SHA-256: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`
- License: MIT License
- Copyright: Copyright (c) 2020 Shiqi Yu <shiqi.yu@gmail.com>

## OpenCV SFace face recognizer

- Asset: `android/app/src/main/assets/models/face_recognition_sface_2021dec.onnx`
- Source: `opencv/face_recognition_sface` commit `3d7082438a6e4551e840c9b2bb60b71e8da4b524`
- SHA-256: `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`
- License: Apache License 2.0

## OpenCV SFace device-test image

- Asset: `android/app/src/androidTest/assets/opencv_sface_demo.jpg` (test APK only)
- Source: `opencv/opencv_zoo` commit `47534e27c9851bb1128ccc0102f1145e27f23f98`, `models/face_recognition_sface/example_outputs/demo.jpg`
- SHA-256: `0f879881a598fea6fec74e047e6a1d00e36d81de63bf0ed392b628e6ab6c2fc4`
- License: Apache License 2.0

## Google AI Edge LiteRT

- Component: `com.google.ai.edge.litert:litert:1.4.2`
- Project: https://github.com/google-ai-edge/LiteRT
- License: Apache License 2.0

## TensorFlow Lite Support SSD MobileNet V1 object detector

- Asset: `android/app/src/main/assets/models/pet_detection_ssd_mobilenet_v1_uint8.tflite`
- Official source: https://github.com/tensorflow/tflite-support/raw/master/tensorflow_lite_support/metadata/python/tests/testdata/object_detector/ssd_mobilenet_v1.tflite
- SHA-256: `e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13`
- Categories: 90 COCO object classes; APM uses `cat` and `dog` for pet identity and may retain non-person/non-cat/non-dog detections as local general-subject similarity evidence.
- Upstream repository license: Apache License 2.0.

## MediaPipe MobileNetV3 Small image embedder

- Asset: `android/app/src/main/assets/models/pet_embedding_mobilenet_v3_small_float32.tflite`
- Official source: https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite
- SHA-256: `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40`
- Training data: ImageNet, as documented by the Google MediaPipe Image Embedder model guide.
- Embedded metadata license: Apache License 2.0.

## Wikimedia Commons cat device-test image

- Asset: `android/app/src/androidTest/assets/cat_reference_test.jpg` (test APK only; resized to a 480-pixel maximum edge)
- Source: https://commons.wikimedia.org/wiki/File:Cat_August_2010-4.jpg
- Author: Alvesgaspar
- SHA-256: `17db70e5ec79af6b27b08cc8d6bb917430a29d582472d576f5db3e15bc0541a8`
- License: Creative Commons Attribution-ShareAlike 3.0 Unported, or GNU Free Documentation License 1.2 or later, at the user's choice. This test copy is used under CC BY-SA 3.0.

The app runs all bundled models on device. Reference images and person/pet crops are not retained; only app-private normalized embedding vectors and match metadata are stored.

## DINOv2 ViT-S/14 with registers

- Asset: `android/app/src/main/assets/models/dinov2_vits14_reg.onnx`
- Official checkpoint: `facebook/dinov2-with-registers-small`
- Fixed revision: `0d9846e56b43a21fa46d7f3f5070f0506a5795a9`
- Official source: https://huggingface.co/facebook/dinov2-with-registers-small
- Exported ONNX SHA-256: `18964f360347671c5313fddeed2617b7e8f212790cfd52a41fcc146562cf9dbd`
- Architecture: ViT-S/14, 384 dimensions, 4 register tokens, 256 patch tokens at 224×224.
- License: Apache License 2.0, https://github.com/facebookresearch/dinov2/blob/main/LICENSE

### MIT License text for YuNet

Copyright (c) 2020 Shiqi Yu <shiqi.yu@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Apache-licensed components are distributed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
