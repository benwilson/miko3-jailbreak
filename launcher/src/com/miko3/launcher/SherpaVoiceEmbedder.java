package com.miko3.launcher;

import android.os.Process;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

/**
 * Owner 2026-10-02: VoiceId's embedder, sherpa-onnx's speaker embedding extractor over
 * the bundled 3D-Speaker CAM++ model (assets/voiceid/model.onnx). One ONNX thread, and
 * the calling thread (VoiceId's own) dropped to background priority, so an embedding
 * never competes with the ears' recogniser. Made and used only on the voice thread.
 */
final class SherpaVoiceEmbedder implements VoiceId.Embedder {
    private final SpeakerEmbeddingExtractor extractor;
    private boolean lowered;

    SherpaVoiceEmbedder(String modelPath) {
        lower();
        extractor = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
                .setModel(modelPath)
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
                .build());
    }

    int dim() {
        return extractor.getDim();
    }

    private void lower() {
        if (!lowered) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            lowered = true;
        }
    }

    @Override
    public float[] embed(float[] samples, int n) {
        lower();
        float[] audio = samples;
        if (n != samples.length) {
            audio = new float[n];
            System.arraycopy(samples, 0, audio, 0, n);
        }
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(audio, VoiceTuning.SAMPLE_RATE);
            stream.inputFinished();
            if (!extractor.isReady(stream)) {
                return null;
            }
            return extractor.compute(stream);
        } finally {
            stream.release();
        }
    }
}
