package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;

import app.gamenative.R;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.widget.XServerView;

import java.util.ArrayList;

public class VulkanRenderer implements WindowManager.OnWindowModificationListener,
                                       Pointer.OnPointerMotionListener {

    static { System.loadLibrary("vulkan_renderer"); }

    // Effect IDs that match the constants in window.frag / VulkanRendererContext.
    public static final int EFFECT_NONE    = 0;
    public static final int EFFECT_FSR     = 1;
    public static final int EFFECT_DLS     = 2;
    public static final int EFFECT_CRT     = 3;
    public static final int EFFECT_HDR     = 4;
    public static final int EFFECT_NATURAL = 5;

    public final XServerView xServerView;
    private final XServer xServer;

    // Long that holds the pointer to the native VulkanRendererContext on the heap.
    // 0 means the context has not been initialised yet.
    private long nativeHandle = 0;
    private final Object lock = new Object();

    public final ViewTransformation viewTransformation = new ViewTransformation();
    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;

    // WM class whose windows should be hidden from the compositor (e.g. explorer.exe).
    private String[] unviewableWMClasses = null;

    // When set, windows matching this WM class that are smaller than the screen but large
    // enough to be a game viewport are promoted to fullscreen in the render list.
    // This is a GameNative addition not present in the upstream Ludashi renderer.
    public String forceFullscreenWMClass = null;

    private boolean cursorVisible = false;
    private boolean nativeMode = false;
    private String driverPath = null;
    private java.util.concurrent.ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;


    // Called once after each composited frame — wired from XServerScreen to update the HUD.
    private Runnable onFrameRenderedListener = null;

    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong ID_GEN =
        new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.WeakHashMap<Drawable, Long> drawableIds =
        new java.util.WeakHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean scenePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // SurfaceControl children for direct-scanout / native mode (API 29+).
    private android.view.SurfaceControl scanoutGameSC;
    private android.view.SurfaceControl scanoutCursorSC;
    private android.view.Surface        scanoutGameSurface;
    private android.view.Surface        scanoutCursorSurface;

    // -------------------------------------------------------------------------
    // JNI declarations
    // All functions are implemented in vulkan_jni.cpp.
    // -------------------------------------------------------------------------
    private native long nativeInit(Surface surface, int screenWidth, int screenHeight,
        String driverPath, String libraryName, String nativeLibDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeUpdateWindowContent(long handle, long id,
        java.nio.ByteBuffer pixels, short width, short height, short stride, int x, int y);
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int x, int y);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);
    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int x, int y, int w, int h, int fenceFd);
    private native void nativeScanoutSetCursorImage(long handle,
        java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native void nativeSetScanoutWindow(long handle,
        android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetEffect(long handle, int effectId, float sharpness);
    private native void nativeSetColorAdjustment(long handle, float brightness, float contrast, float gamma);

    // Pending native state — held until nativeHandle is live so the first
    // nativeInit call can apply them immediately.
    private int     pendingPresentMode = 1; // 1 = MAILBOX
    private int     pendingFilterMode  = 0;
    private boolean pendingSwapRB      = false;
    private int     pendingEffectId    = EFFECT_NONE;
    private float   pendingSharpness   = 1.0f;
    private float   pendingBrightness  = 0.0f;
    private float   pendingContrast    = 0.0f;
    private float   pendingGamma       = 1.0f;

    private static volatile boolean gpuImageChecked = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public VulkanRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Drawable → stable 64-bit ID mapping
    // -------------------------------------------------------------------------

    /** Returns a stable numeric ID for this Drawable, creating one if needed. */
    private long did(Drawable d) {
        return drawableIds.computeIfAbsent(d, k -> ID_GEN.getAndIncrement());
    }

    // -------------------------------------------------------------------------
    // Scene update — called whenever the X11 window tree changes
    // -------------------------------------------------------------------------

    /**
     * Queues a scene rebuild on the event executor.  The atomic flag prevents
     * redundant rebuilds when multiple window modifications arrive in a burst.
     */
    public void queueSceneUpdate() {
        if (scenePending.compareAndSet(false, true)) {
            xServerView.queueEvent(() -> {
                scenePending.set(false);
                updateScene();
            });
        }
    }

    public void updateScene() {
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
        }
        synchronized (lock) {
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    /**
     * Recursively walks the X11 window tree, collecting visible windows into the
     * render list in paint order (back-to-front).
     *
     * <p>GameNative addition: if {@link #forceFullscreenWMClass} is set, sub-screen-sized
     * windows whose WM class matches are promoted to fullscreen so the game viewport fills
     * the display regardless of X11 desktop chrome around it.
     */
    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }
            if (viewable) {
                boolean forceFullscreen = false;
                if (forceFullscreenWMClass != null) {
                    short w = window.getWidth();
                    short h = window.getHeight();
                    if (w >= 320 && h >= 200
                            && w < xServer.screenInfo.width
                            && h < xServer.screenInfo.height) {
                        Window parent = window.getParent();
                        boolean parentHasClass = parent.getClassName()
                            .contains(forceFullscreenWMClass);
                        boolean hasClass = window.getClassName()
                            .contains(forceFullscreenWMClass);
                        if (hasClass) {
                            forceFullscreen = !parentHasClass && window.getChildCount() == 0;
                        } else {
                            short borderX = (short)(parent.getWidth() - w);
                            short borderY = (short)(parent.getHeight() - h);
                            if (parent.getChildCount() == 1
                                    && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                // Remove the wrapping parent from the list to avoid overdraw.
                                list.removeIf(rw -> rw.content == parent.getContent());
                            }
                        }
                    }
                }
                list.add(new RenderableWindow(window.getContent(), x, y, forceFullscreen));
            }
        }
        for (Window child : window.getChildren()) {
            collectWindows(list, child, child.getX() + x, child.getY() + y);
        }
    }

    /**
     * Converts the Java render list into the three parallel arrays expected by the native
     * side and submits them.  Windows that are fully offscreen are still included so the
     * native compositor can decide whether to skip them.
     *
     * <p>The "start index" skips over desktop background windows so that the compositor
     * does not waste time drawing layers that will be completely covered.
     */
    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width;
        int screenH = xServer.screenInfo.height;

        // Find the back-most window that fills the whole screen — no need to draw what
        // is fully behind it.
        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null
                    && rw.content.width >= screenW
                    && rw.content.height >= screenH) {
                start = i;
                break;
            }
        }

        if (nativeMode) {
            // In native/scanout mode the compositor still renders all windows (GameNative
            // does not implement direct AHB scanout bypass at the Drawable level).
            int n = list.size() - start;
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = did(list.get(start + i).content);
                xs[i]  = list.get(start + i).rootX;
                ys[i]  = list.get(start + i).rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content);
            xs[i]  = rw.rootX;
            ys[i]  = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
    }

    // -------------------------------------------------------------------------
    // Surface lifecycle — called by XServerView's SurfaceHolder.Callback
    // -------------------------------------------------------------------------

    /**
     * Called when a rendering surface becomes available (app start, resume after minimise).
     *
     * <p>Initialisation is moved off the main thread to an {@code initExecutor} so the UI
     * never blocks.  Once the native handle is ready, the first scene push is queued.
     */
    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) {
            GPUImage.checkIsSupported();
            gpuImageChecked = true;
        }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try {
                initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    // Surface was recreated (e.g. screen rotation) — try to reattach cheaply.
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {
                        initComplete = true;
                        xServerView.queueEvent(this::updateScene);
                        return;
                    }
                }
                nativeHandle = nativeInit(surface,
                    xServer.screenInfo.width, xServer.screenInfo.height,
                    driverPath, driverLibraryName, nativeLibDir);
                if (nativeHandle != 0) {
                    // Apply any settings that arrived before init completed.
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle,  pendingFilterMode);
                    nativeSetSwapRB(nativeHandle,          pendingSwapRB);
                    nativeSetEffect(nativeHandle,           pendingEffectId, pendingSharpness);
                    nativeSetColorAdjustment(nativeHandle, pendingBrightness, pendingContrast, pendingGamma);
                    updateTransform();
                    nativeSetCursorVisible(nativeHandle, cursorVisible);
                    if (nativeMode) {
                        xServerView.post(() -> {
                            releaseScanoutSurfaces();
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                try {
                                    android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_game")
                                        .setOpaque(true).build();
                                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_cursor")
                                        .setFormat(1).build();
                                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                    new android.view.SurfaceControl.Transaction()
                                        .setLayer(scanoutGameSC,   1)
                                        .setLayer(scanoutCursorSC, 2)
                                        .setVisibility(scanoutGameSC,   true)
                                        .setVisibility(scanoutCursorSC, true)
                                        .apply();
                                    synchronized (lock) {
                                        if (nativeHandle != 0) {
                                            nativeSetScanoutWindow(nativeHandle,
                                                scanoutGameSurface, scanoutCursorSurface);
                                            updateTransform();
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w("VulkanRenderer",
                                        "SC recreate failed on surface restore: " + e);
                                    synchronized (lock) {
                                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                    }
                                }
                            } else {
                                synchronized (lock) {
                                    if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                }
                            }
                        });
                    }
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                }
            }
            initComplete = true;
            xServerView.queueEvent(this::updateScene);
        });
    }

    /**
     * Called when the surface dimensions change.  Rebuilds the Vulkan swapchain and
     * updates the viewport-to-screen-coordinates transform.
     */
    public void onSurfaceChanged(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height,
            xServer.screenInfo.width, xServer.screenInfo.height);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeResize(nativeHandle, width, height);
                updateTransform();
            }
        }
    }

    /**
     * Called when the surface is destroyed (app is minimised or paused).
     * Detaches the surface from the native renderer without destroying its resources
     * so they can be reattached cheaply when the surface is recreated.
     */
    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try {
                initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (nativeMode) {
                    nativeDestroyScanout(nativeHandle);
                    nativeDestroy(nativeHandle);
                    nativeHandle = 0;
                } else {
                    nativeDetachSurface(nativeHandle);
                }
            }
        }
        if (nativeMode) xServerView.post(this::releaseScanoutSurfaces);
    }

    private void releaseScanoutSurfaces() {
        if (scanoutGameSurface   != null) {
            scanoutGameSurface.release();   scanoutGameSurface   = null;
        }
        if (scanoutCursorSurface != null) {
            scanoutCursorSurface.release(); scanoutCursorSurface = null;
        }
        if (scanoutGameSC        != null) {
            scanoutGameSC.release();        scanoutGameSC        = null;
        }
        if (scanoutCursorSC      != null) {
            scanoutCursorSC.release();      scanoutCursorSC      = null;
        }
    }

    // -------------------------------------------------------------------------
    // Coordinate transform
    // -------------------------------------------------------------------------

    /**
     * Pushes the current view-to-screen transform to the native renderer.
     * Called whenever surface dimensions or the fullscreen flag change.
     */
    private void updateTransform() {
        if (nativeHandle == 0) return;
        if (fullscreen) {
            nativeSetTransform(nativeHandle, 0, 0, 1.0f, 1.0f);
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(xServer.pointer.getY() - halfH / 2.0f, halfH));
            }
            nativeSetTransform(nativeHandle,
                viewTransformation.sceneOffsetX,
                viewTransformation.sceneOffsetY - py,
                viewTransformation.sceneScaleX,
                viewTransformation.sceneScaleY);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        }
    }

    // -------------------------------------------------------------------------
    // Pixel buffer delivery — called by PresentExtension / DRI3Extension
    // -------------------------------------------------------------------------

    /**
     * Delivers a completed game frame from the X11 Present extension directly to the
     * Vulkan compositor, bypassing the scene rebuild path for lower latency.
     *
     * <p>If the Drawable has an AHardwareBuffer, the GPU handle is passed directly
     * (zero-copy).  Otherwise the CPU pixel buffer is uploaded via a staging copy.
     */
    public void onUpdateWindowContentDirect(Window window, Drawable pixmap,
            short xOff, short yOff) {
        if (nativeHandle == 0 || pixmap == null) return;
        Drawable targetDrawable = window.getContent();
        long targetId = did(targetDrawable);
        int rx = window.getRootX() + xOff;
        int ry = window.getRootY() + yOff;
        synchronized (pixmap.renderLock) {
            Texture texture = pixmap.getTexture();
            if (texture instanceof GPUImage) {
                GPUImage g = (GPUImage) texture;
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, targetId, ahbPtr,
                        pixmap.width, pixmap.height, rx, ry);
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : pixmap.width;
                    nativeUpdateWindowContent(nativeHandle, targetId, vd,
                        pixmap.width, pixmap.height, s, rx, ry);
                    return;
                }
            }
            java.nio.ByteBuffer buf = pixmap.getData();
            if (buf == null) return;
            nativeUpdateWindowContent(nativeHandle, targetId, buf,
                pixmap.width, pixmap.height, pixmap.width, rx, ry);
        }
        notifyFrameRendered();
    }

    // -------------------------------------------------------------------------
    // WindowManager.OnWindowModificationListener
    // -------------------------------------------------------------------------

    @Override
    public void onUpdateWindowContent(Window window) {
        final long handle;
        synchronized (lock) { handle = nativeHandle; }
        if (handle == 0) return;

        Drawable drawable = window.getContent();
        if (drawable == null || !window.attributes.isMapped()) return;
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) {
                if (wc.contains(cls)) return;
            }
        }
        int rx = window.getRootX();
        int ry = window.getRootY();
        long drawableId = did(drawable);

        synchronized (drawable.renderLock) {
            if (drawable.getTexture() instanceof GPUImage) {
                GPUImage g = (GPUImage) drawable.getTexture();
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    nativeUpdateWindowContentAHB(handle, drawableId, ahbPtr,
                        drawable.width, drawable.height, rx, ry);
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : drawable.width;
                    nativeUpdateWindowContent(handle, drawableId, vd,
                        drawable.width, drawable.height, s, rx, ry);
                    return;
                }
            }
            java.nio.ByteBuffer buf = drawable.getData();
            if (buf == null) return;
            nativeUpdateWindowContent(handle, drawableId, buf,
                drawable.width, drawable.height, drawable.width, rx, ry);
        }
        notifyFrameRendered();
    }

    @Override public void onMapWindow(Window window) { queueSceneUpdate(); }

    @Override
    public void onUnmapWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id);
            }
            queueSceneUpdate();
        });
    }

    @Override public void onChangeWindowZOrder(Window window) { queueSceneUpdate(); }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        queueSceneUpdate();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) {
                    lastCursor = window.attributes.getCursor();
                    sendCursorToNative(lastCursor);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pointer.OnPointerMotionListener
    // -------------------------------------------------------------------------

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) {
                lastCursor = cursor;
                sendCursorToNative(cursor);
            }
            if (nativeMode) {
                short hotX = 0, hotY = 0;
                if (cursor != null) {
                    hotX = (short)cursor.hotSpotX;
                    hotY = (short)cursor.hotSpotY;
                }
                nativeScanoutSetCursorPos(nativeHandle, x, y, hotX, hotY);
            }
            if (screenOffsetYRelativeToCursor) updateTransform();
        }
    }

    // -------------------------------------------------------------------------
    // Cursor helpers
    // -------------------------------------------------------------------------

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd;
        short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd   = cursor.cursorImage;
            hotX = (short)cursor.hotSpotX;
            hotY = (short)cursor.hotSpotY;
        } else {
            cd = rootCursorDrawable;
        }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.getData() != null) {
            synchronized (cd.renderLock) {
                java.nio.ByteBuffer buf = cd.getData();
                nativeUpdateCursorImage(nativeHandle,
                    buf, cd.width, cd.height, hotX, hotY);
                if (nativeMode) {
                    nativeScanoutSetCursorImage(nativeHandle, buf, cd.width, cd.height, cd.width);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Frame notification
    // -------------------------------------------------------------------------

    /** Fires the frame-rendered callback.  Safe to call from any thread. */
    private void notifyFrameRendered() {
        Runnable listener = onFrameRenderedListener;
        if (listener != null) listener.run();
    }

    // -------------------------------------------------------------------------
    // Public API used by XServerScreen / QuickMenu
    // -------------------------------------------------------------------------

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetCursorVisible(nativeHandle, visible);
                if (visible) sendCursorToNative(lastCursor);
            }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    /** Registers a callback that fires once after each composited frame. */
    public void setOnFrameRenderedListener(Runnable listener) {
        this.onFrameRenderedListener = listener;
    }

    /**
     * No-op stub for API compatibility.  GLRenderer used a FrameRating view;
     * VulkanRenderer uses {@link #setOnFrameRenderedListener} instead.
     */
    public void setFrameRating(Object frameRating) {
        // Frame-rate tracking is wired via setOnFrameRenderedListener in XServerScreen.
        // This stub keeps binary compatibility with callers that haven't been updated.
    }

    public void setUnviewableWMClasses(String... classes) {
        this.unviewableWMClasses = classes;
    }

    /**
     * Updates only the visual cursor position without changing the XServer pointer state.
     * Used in relative mouse mode where movement is forwarded to Wine separately.
     */
    public void updateVisualCursorPosition(int x, int y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, (short) x, (short) y);
        }
    }

    public boolean isFullscreen() { return fullscreen; }
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        synchronized (lock) { updateTransform(); }
        xServerView.queueEvent(this::updateScene);
    }
    public void setScreenOffsetYRelativeToCursor(boolean b) {
        screenOffsetYRelativeToCursor = b;
        synchronized (lock) { updateTransform(); }
    }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    public void setMagnifierZoom(float zoom) { magnifierZoom = zoom; }
    public float getMagnifierZoom() { return magnifierZoom; }

    // -------------------------------------------------------------------------
    // Effect / presentation settings — used by QuickMenu and ScreenEffectsConfig
    // -------------------------------------------------------------------------

    /**
     * Sets the active post-processing effect and sharpness.
     * {@code effectId} must be one of the {@code EFFECT_*} constants.
     */
    public void setEffect(int effectId, float sharpness) {
        pendingEffectId = Math.max(EFFECT_NONE, Math.min(EFFECT_NATURAL, effectId));
        pendingSharpness = sharpness;
        synchronized (lock) {
            if (nativeHandle != 0)
                nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness);
        }
    }

    public int getEffectId() { return pendingEffectId; }
    public float getSharpness() { return pendingSharpness; }

    /** 0 = FIFO (VSync), 1 = MAILBOX (low-latency). Default is MAILBOX. */
    public void setPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode);
        }
    }

    /** 0 = linear, 1 = nearest-neighbour. */
    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode);
        }
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled);
        }
    }

    /**
     * Sets per-frame color adjustments applied after all effects.
     * {@code brightness} is an additive offset in [-1, 1].
     * {@code contrast} is a multiplier offset in [-1, 1] (0 = no change).
     * {@code gamma} is the gamma exponent (1.0 = no change, > 1.0 = brighter midtones).
     */
    public void setColorAdjustment(float brightness, float contrast, float gamma) {
        pendingBrightness = brightness;
        pendingContrast = contrast;
        pendingGamma = gamma;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetColorAdjustment(nativeHandle, brightness, contrast, gamma);
        }
    }

    // -------------------------------------------------------------------------
    // Adrenotools / custom Turnip driver (Phase B — Adreno only)
    // -------------------------------------------------------------------------

    /**
     * Sets the custom Vulkan driver path for adrenotools injection.
     * This is a Phase B feature — calling it on non-Adreno hardware is a safe no-op
     * because the native init falls back to the system Vulkan driver if adrenotools
     * fails to load.
     */
    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
    }

    // -------------------------------------------------------------------------
    // Native mode (direct scanout, API 29+)
    // -------------------------------------------------------------------------

    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        if (mode) {
            xServerView.post(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                        scanoutGameSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                        scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                        scanoutCursorSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                        scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                        new android.view.SurfaceControl.Transaction()
                            .setLayer(scanoutGameSC,   1)
                            .setLayer(scanoutCursorSC, 2)
                            .setVisibility(scanoutGameSC,   true)
                            .setVisibility(scanoutCursorSC, true)
                            .apply();
                        synchronized (lock) {
                            if (nativeHandle != 0) {
                                nativeSetScanoutWindow(nativeHandle,
                                    scanoutGameSurface, scanoutCursorSurface);
                                updateTransform();
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w("VulkanRenderer",
                            "Sibling SC failed, using child SC: " + e);
                        synchronized (lock) {
                            if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                        }
                    }
                } else {
                    synchronized (lock) {
                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                    }
                }
            });
        } else {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyScanout(nativeHandle);
            }
            xServerView.post(this::releaseScanoutSurfaces);
        }
        xServerView.queueEvent(this::updateScene);
    }

    public boolean isNativeMode() { return nativeMode; }
}
