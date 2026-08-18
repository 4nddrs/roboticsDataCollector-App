MediaPipe Hands model
=====================

Expected asset:
  app/src/main/assets/hand_landmarker.task

Official download (float16, latest):
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

If the file is missing or HandLandmarker fails to load (common on emulators
without GPU/NNAPI), DataCollectionGuardian falls back to a luma/ROI heuristic
so recording still works.
