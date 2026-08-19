# Bundled on-device vision models

APM runs these models on the Android device. The app does not download them at runtime.

- `face_detection_yunet_2023mar.onnx` and `face_recognition_sface_2021dec.onnx`: OpenCV Zoo YuNet and SFace models.
- `pet_detection_ssd_mobilenet_v1_uint8.tflite`: SSD MobileNet V1 uint8 COCO object detector from the official TensorFlow Lite Support repository. APM uses cat/dog results for pet identity and non-person/non-cat/non-dog results for general-subject similarity. SHA-256: `e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13`.
- `pet_embedding_mobilenet_v3_small_float32.tflite`: Google MediaPipe MobileNet-V3 Small image embedder, downloaded from the official MediaPipe model bucket. SHA-256: `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40`.

OpenCV Zoo, LiteRT, TensorFlow Lite Support, MediaPipe, and DINOv2 are Apache-2.0 licensed. DINOv2 ONNX inference uses the MIT-licensed ONNX Runtime Android package. See the repository third-party notices for exact asset provenance.

## DINOv2 visual similarity model

`dinov2_vits14_reg.onnx` is a reproducible export of the public
`facebook/dinov2-with-registers-small` revision
`0d9846e56b43a21fa46d7f3f5070f0506a5795a9`. Its output is the full
`[1,261,384]` last hidden state. `dinov2_vits14_reg.onnx.sha256` pins the exact exported
single-file ONNX before it is copied to app-private storage. The app never substitutes
another embedding model.
