/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.os.Trace;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;

import java.io.PrintWriter;

/**
 * Hardware renderer that proxies the rendering to a render thread. Most calls
 * are currently synchronous.
 * TODO: Make draw() async.
 * TODO: Figure out how to share the DisplayList between two threads (global lock?)
 *
 * The UI thread can block on the RenderThread, but RenderThread must never
 * block on the UI thread.
 *
 * ThreadedRenderer creates an instance of RenderProxy. RenderProxy in turn creates
 * and manages a CanvasContext on the RenderThread. The CanvasContext is fully managed
 * by the lifecycle of the RenderProxy.
 *
 * Note that although currently the EGL context & surfaces are created & managed
 * by the render thread, the goal is to move that into a shared structure that can
 * be managed by both threads. EGLSurface creation & deletion should ideally be
 * done on the UI thread and not the RenderThread to avoid stalling the
 * RenderThread with surface buffer allocation.
 *
 * @hide
 */
public class ThreadedRenderer extends HardwareRenderer {
    private static final String LOGTAG = "ThreadedRenderer";

    private static final Rect NULL_RECT = new Rect(-1, -1, -1, -1);

    private int mWidth, mHeight;
    private long mNativeProxy;

    ThreadedRenderer(boolean translucent) {
        mNativeProxy = nCreateProxy(translucent);
        setEnabled(mNativeProxy != 0);
    }

    @Override
    void destroy(boolean full) {
        nDestroyCanvas(mNativeProxy);
    }

    @Override
    boolean initialize(Surface surface) throws OutOfResourcesException {
        return nInitialize(mNativeProxy, surface);
    }

    @Override
    void updateSurface(Surface surface) throws OutOfResourcesException {
        nUpdateSurface(mNativeProxy, surface);
    }

    @Override
    void destroyLayers(View view) {
        throw new NoSuchMethodError();
    }

    @Override
    void destroyHardwareResources(View view) {
        // TODO: canvas.clearLayerUpdates()
        destroyResources(view);
        // TODO: GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
    }

    private static void destroyResources(View view) {
        view.destroyHardwareResources();

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                destroyResources(group.getChildAt(i));
            }
        }
    }

    @Override
    void invalidate(Surface surface) {
        updateSurface(surface);
    }

    @Override
    boolean validate() {
        // TODO Remove users of this API
        return false;
    }

    @Override
    boolean safelyRun(Runnable action) {
        // TODO:
        return false;
    }

    @Override
    void setup(int width, int height) {
        mWidth = width;
        mHeight = height;
        nSetup(mNativeProxy, width, height);
    }

    @Override
    int getWidth() {
        return mWidth;
    }

    @Override
    int getHeight() {
        return mHeight;
    }

    @Override
    void dumpGfxInfo(PrintWriter pw) {
        // TODO Auto-generated method stub
    }

    @Override
    long getFrameCount() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    boolean loadSystemProperties() {
        return false;
    }

    @Override
    void pushLayerUpdate(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void onLayerCreated(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void onLayerDestroyed(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void flushLayerUpdates() {
        throw new NoSuchMethodError();
    }

    /**
     * TODO: Remove
     * Temporary hack to allow RenderThreadTest prototype app to trigger
     * replaying a DisplayList after modifying the displaylist properties
     *
     *  @hide */
    public void repeatLastDraw() {
    }

    @Override
    void draw(View view, AttachInfo attachInfo, HardwareDrawCallbacks callbacks, Rect dirty) {
        attachInfo.mIgnoreDirtyState = true;
        attachInfo.mDrawingTime = SystemClock.uptimeMillis();
        view.mPrivateFlags |= View.PFLAG_DRAWN;

        view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                == View.PFLAG_INVALIDATED;
        view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "getDisplayList");
        DisplayList displayList = view.getDisplayList();
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);

        view.mRecreateDisplayList = false;

        if (dirty == null) {
            dirty = NULL_RECT;
        }
        nDrawDisplayList(mNativeProxy, displayList.getNativeDisplayList(),
                dirty.left, dirty.top, dirty.right, dirty.bottom);
    }

    @Override
    HardwareLayer createTextureLayer() {
        throw new NoSuchMethodError();
    }

    @Override
    HardwareLayer createDisplayListLayer(int width, int height) {
        throw new NoSuchMethodError();
    }

    @Override
    SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    boolean copyLayerInto(HardwareLayer layer, Bitmap bitmap) {
        throw new NoSuchMethodError();
    }

    @Override
    void detachFunctor(long functor) {
        nDetachFunctor(mNativeProxy, functor);
    }

    @Override
    void attachFunctor(AttachInfo attachInfo, long functor) {
        nAttachFunctor(mNativeProxy, functor);
    }

    @Override
    void setName(String name) {
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nDeleteProxy(mNativeProxy);
        } finally {
            super.finalize();
        }
    }

    /** @hide */
    public static native void postToRenderThread(Runnable runnable);

    private static native long nCreateProxy(boolean translucent);
    private static native void nDeleteProxy(long nativeProxy);

    private static native boolean nInitialize(long nativeProxy, Surface window);
    private static native void nUpdateSurface(long nativeProxy, Surface window);
    private static native void nSetup(long nativeProxy, int width, int height);
    private static native void nDrawDisplayList(long nativeProxy, long displayList,
            int dirtyLeft, int dirtyTop, int dirtyRight, int dirtyBottom);
    private static native void nDestroyCanvas(long nativeProxy);

    private static native void nAttachFunctor(long nativeProxy, long functor);
    private static native void nDetachFunctor(long nativeProxy, long functor);
}
