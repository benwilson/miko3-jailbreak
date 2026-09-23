package com.miko3.mode.explore;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Recognition boundary (KTD1): a frame in, plain detections out, highest
 * confidence first. OnnxRecognizer is the on-device one; a relay-backed one can
 * implement this later (R17). Called from the camera worker only.
 */
interface Recognizer {
    List<Detection> detect(Bitmap frame) throws Exception;

    void close();
}
