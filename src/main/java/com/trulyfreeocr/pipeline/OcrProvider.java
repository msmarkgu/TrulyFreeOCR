package com.trulyfreeocr.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;

import com.trulyfreeocr.model.PageResult;

@FunctionalInterface
public interface OcrProvider {
    PageResult ocr(BufferedImage pageImage, int pageIndex) throws IOException;
}
