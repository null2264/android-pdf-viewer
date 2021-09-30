/*
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.model.PagePart;

/**
 * A {@link Handler} that will process incoming {@link RenderingTask} messages
 * and alert {@link PDFView#onBitmapRendered(PagePart)} when the portion of the
 * PDF is ready to render.
 */
class RenderingHandler extends Handler {
    /**
     * {@link Message#what} kind of message this handler processes.
     */
    private static final int MSG_RENDER_TASK = 1;

    private static final String TAG = RenderingHandler.class.getName();

    private final PDFView pdfView;

    private final RectF renderBounds = new RectF();
    private final Rect roundedRenderBounds = new Rect();
    private final Matrix renderMatrix = new Matrix();
    private volatile boolean running = false;

    RenderingHandler(Looper looper, PDFView pdfView) {
        super(looper);
        this.pdfView = pdfView;
    }

    void addRenderingTask(PdfFile pdfFile, int page, float width, float height, RectF bounds, boolean thumbnail, int cacheOrder, boolean bestQuality, boolean annotationRendering) {
        RenderingTask task = new RenderingTask(pdfFile, width, height, bounds, page, thumbnail, cacheOrder, bestQuality, annotationRendering);
        Message msg = obtainMessage(MSG_RENDER_TASK, task);
        sendMessage(msg);
    }

    @Override
    public void handleMessage(Message message) {
        if (!running) {
            return;
        }
        RenderingTask task = (RenderingTask) message.obj;
        try {
            final PagePart part = proceed(task);
            if (part != null) {
                if (running) {
                    pdfView.post(() -> {
                        if (running) {
                            pdfView.onBitmapRendered(part);
                        } else {
                            part.getRenderedBitmap().recycle();
                        }
                    });
                } else {
                    part.getRenderedBitmap().recycle();
                }
            }
        } catch (final PageRenderingException ex) {
            pdfView.post(() -> {
                if (running) {
                    pdfView.onPageError(ex);
                }
            });
        }
    }

    private PagePart proceed(RenderingTask renderingTask) throws PageRenderingException {
        final PdfFile pdfFile = renderingTask.pdfFile;
        pdfFile.openPage(renderingTask.page);

        int w = Math.round(renderingTask.width);
        int h = Math.round(renderingTask.height);

        if (w == 0 || h == 0 || pdfFile.pageHasError(renderingTask.page)) {
            return null;
        }

        Bitmap render;
        try {
            render = Bitmap.createBitmap(w, h, renderingTask.bestQuality ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Cannot create bitmap", e);
            return null;
        }
        calculateBounds(w, h, renderingTask.bounds);

        pdfFile.renderPageBitmap(render, renderingTask.page, roundedRenderBounds, renderingTask.annotationRendering);

        return new PagePart(renderingTask.page, render,
                renderingTask.bounds, renderingTask.thumbnail,
                renderingTask.cacheOrder);
    }

    private void calculateBounds(int width, int height, RectF pageSliceBounds) {
        renderMatrix.reset();
        renderMatrix.postTranslate(-pageSliceBounds.left * width, -pageSliceBounds.top * height);
        renderMatrix.postScale(1 / pageSliceBounds.width(), 1 / pageSliceBounds.height());

        renderBounds.set(0, 0, width, height);
        renderMatrix.mapRect(renderBounds);
        renderBounds.round(roundedRenderBounds);
    }

    void stop() {
        running = false;
        removeMessages(MSG_RENDER_TASK);
    }

    void start() {
        running = true;
    }

    void purge() {
        removeMessages(MSG_RENDER_TASK);
    }

    private static class RenderingTask {

        final PdfFile pdfFile;

        final float width, height;

        final RectF bounds;

        final int page;

        final boolean thumbnail;

        final int cacheOrder;

        final boolean bestQuality;

        final boolean annotationRendering;

        RenderingTask(
                PdfFile pdfFile,
                float width,
                float height,
                RectF bounds,
                int page,
                boolean thumbnail,
                int cacheOrder,
                boolean bestQuality,
                boolean annotationRendering) {
            this.pdfFile = pdfFile;
            this.page = page;
            this.width = width;
            this.height = height;
            this.bounds = bounds;
            this.thumbnail = thumbnail;
            this.cacheOrder = cacheOrder;
            this.bestQuality = bestQuality;
            this.annotationRendering = annotationRendering;
        }
    }
}
