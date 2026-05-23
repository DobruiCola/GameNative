package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.renderer.VulkanRenderer;
import com.winlator.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vulkan-backed X-server view, ported from Winlator-Ludashi
 * (StevenMXZ/Winlator-Ludashi).
 *
 * Hosts a {@link VulkanRenderer} that imports DXVK / Mesa AHardwareBuffers
 * straight into VkImages via VK_ANDROID_external_memory_android_hardware_buffer,
 * skipping the GL texture copy the legacy {@link XServerViewGL} path performs.
 *
 * Containers that need VirGL still use {@link XServerViewGL}; selection is
 * done at construction in the screen layer.
 */
@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private final VulkanRenderer renderer;
    private final XServer xServer;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private int frameRateLimit = 0;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        this.xServer = xServer;
        renderer = new VulkanRenderer(this, xServer);
    }

    public XServer getxServer() {
        return xServer;
    }

    public VulkanRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        renderer.onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        renderer.onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        renderer.onSurfaceDestroyed();
    }

    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    // No-ops to match the GLSurfaceView shape expected by some callers
    // (XServerScreen, Activity lifecycle wiring). VulkanRenderer manages
    // its own thread lifecycle via the SurfaceHolder.Callback above.
    public void onPause() {}
    public void onResume() {}

    // Pluvia callers (PluviaApp, XServerScreen) treat fps limit as a property
    // of the view; VulkanRenderer applies it via SurfaceControl.setFrameRate.
    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        renderer.setFpsLimit(this.frameRateLimit);
    }

    // Compatibility shim for GLSurfaceView's requestRender(); the Vulkan
    // renderer is push-driven, so we just kick a scene update.
    public void requestRender() {
        renderer.queueSceneUpdate();
    }
}
