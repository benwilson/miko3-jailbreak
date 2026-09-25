# Robot voice (Piper, medium, 22050 Hz)

Made by scripts/voice/train-piper.py (voice plan U4): the rhasspy lessac medium
checkpoint (epoch 2164) fine-tuned with OHF-Voice/piper1-gpl on synthetic
speech from scripts/voice/synthesize-dataset.py, then exported to ONNX with
sherpa-onnx's Piper metadata. No training audio or transcripts live here; the
dataset stays in the gitignored voice-work/.

- Synthetic dataset: 1386 utterances (lines from scripts/voice/script-lines.txt)
- Fine-tuned to epoch 2599 (435 epochs past the base checkpoint)
- model.onnx: from last.ckpt
- tokens.txt: the voice config's phoneme_id_map
- espeak-ng-data/: sherpa-onnx's bundle
