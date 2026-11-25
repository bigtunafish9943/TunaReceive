package com.limelight.utils;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Sharpens the picture before the TV scales it up: the decoder draws each frame into a texture,
 * and this draws it again through a contrast-adaptive sharpening filter (the idea behind AMD's
 * FidelityFX CAS, which is free to use). It sharpens soft edges more than edges that are already
 * crisp, so lower-resolution streams look less blurry without the halos of a plain sharpen.
 *
 * The surface is kept at the stream's own size, so each texel is sampled exactly and the cost
 * stays small; the TV's scaler then enlarges the sharpened picture.
 */
public class SharpenRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** Told once the decoder's surface exists, or that this device can't do it. */
    public interface Listener {
        void onSharpenSurfaceReady(Surface surface);
        void onSharpenFailed();
    }

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTex = (uSt * aTexCoord).xy;\n" +
            "}\n";

    // Contrast-adaptive sharpening on the 3x3 neighbourhood
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform vec2 uStep;\n" +
            "uniform float uPeak;\n" +
            "varying vec2 vTex;\n" +
            "vec3 at(float x, float y) { return texture2D(uTex, vTex + vec2(x, y) * uStep).rgb; }\n" +
            "void main() {\n" +
            "  vec3 a = at(-1.0, -1.0); vec3 b = at(0.0, -1.0); vec3 c = at(1.0, -1.0);\n" +
            "  vec3 d = at(-1.0,  0.0); vec3 e = at(0.0,  0.0); vec3 f = at(1.0,  0.0);\n" +
            "  vec3 g = at(-1.0,  1.0); vec3 h = at(0.0,  1.0); vec3 i = at(1.0,  1.0);\n" +
            "  vec3 mn = min(min(min(d, e), min(f, b)), h);\n" +
            "  mn += min(mn, min(min(a, c), min(g, i)));\n" +
            "  vec3 mx = max(max(max(d, e), max(f, b)), h);\n" +
            "  mx += max(mx, max(max(a, c), max(g, i)));\n" +
            "  vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));\n" +
            "  vec3 w = amp * uPeak;\n" +
            "  vec3 outc = ((b + d + f + h) * w + e) / (1.0 + 4.0 * w);\n" +
            "  gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);\n" +
            "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final GLSurfaceView view;
    private final Listener listener;
    private final int videoWidth;
    private final int videoHeight;
    private final float peak;

    private final FloatBuffer quad;
    private final FloatBuffer tex;
    private final float[] st = new float[16];
    private final Object lock = new Object();
    private int framesWaiting;
    private boolean failed;

    private int program;
    private int texture;
    private int aPosition, aTexCoord, uSt, uStep, uPeak, uTex;
    private SurfaceTexture surfaceTexture;
    private Surface surface;

    /** @param strength from 0 (a little) to 1 (a lot) */
    public SharpenRenderer(GLSurfaceView view, Listener listener, int videoWidth, int videoHeight, float strength) {
        this.view = view;
        this.listener = listener;
        this.videoWidth = Math.max(1, videoWidth);
        this.videoHeight = Math.max(1, videoHeight);
        float s = Math.max(0f, Math.min(1f, strength));
        // CAS: peak = -1 / lerp(8, 5, sharpness)
        this.peak = -1f / (8f + (5f - 8f) * s);
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        tex = ByteBuffer.allocateDirect(TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tex.put(TEX).position(0);
    }

    /**
     * Whether this stream is sharpened: the setting is on, the picture is 2D, and it isn't HDR
     * (the filter works on ordinary colours) or 4K and up (nothing soft to sharpen there).
     */
    public static boolean appliesTo(com.limelight.preferences.PreferenceConfiguration config) {
        return strengthOf(config.sharpen) >= 0 && config.renderMode == 0 && !config.enableHdr && config.height < 2160;
    }

    /** Strength for a "tuna_sharpen" setting, or a negative number when it's off. */
    public static float strengthOf(String setting) {
        if (setting == null) {
            return -1f;
        }
        switch (setting) {
            case "light": return 0.3f;
            case "medium": return 0.6f;
            case "strong": return 1.0f;
            default: return -1f;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            program = link(VERTEX_SHADER, FRAGMENT_SHADER);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uSt = GLES20.glGetUniformLocation(program, "uSt");
            uStep = GLES20.glGetUniformLocation(program, "uStep");
            uPeak = GLES20.glGetUniformLocation(program, "uPeak");
            uTex = GLES20.glGetUniformLocation(program, "uTex");

            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            texture = ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            // Nearest: the filter reads whole texels, the TV's scaler does the smoothing
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(texture);
            surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
        } catch (RuntimeException e) {
            LimeLog.warning("Sharpening unavailable: " + e.getMessage());
            failed = true;
            listener.onSharpenFailed();
            return;
        }
        listener.onSharpenSurfaceReady(surface);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (lock) {
            framesWaiting++;
        }
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (failed || surfaceTexture == null) {
            return;
        }
        int waiting;
        synchronized (lock) {
            waiting = framesWaiting;
            framesWaiting = 0;
        }
        if (waiting == 0) {
            return;
        }
        try {
            // Several frames since the last draw: skip to the newest so the picture never lags
            for (int i = 0; i < waiting; i++) {
                surfaceTexture.updateTexImage();
            }
        } catch (RuntimeException e) {
            return;
        }
        surfaceTexture.getTransformMatrix(st);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0);
        // One texel in the texture's own coordinates (the matrix can crop or flip it)
        GLES20.glUniform2f(uStep, Math.abs(st[0]) / videoWidth, Math.abs(st[5]) / videoHeight);
        GLES20.glUniform1f(uPeak, peak);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, tex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    public void release() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        final int p = program;
        final int t = texture;
        program = 0;
        texture = 0;
        view.queueEvent(() -> {
            if (p != 0) {
                GLES20.glDeleteProgram(p);
            }
            if (t != 0) {
                GLES20.glDeleteTextures(1, new int[]{t}, 0);
            }
        });
    }

    private static int link(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("link: " + log);
        }
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("compile: " + log);
        }
        return s;
    }
}
