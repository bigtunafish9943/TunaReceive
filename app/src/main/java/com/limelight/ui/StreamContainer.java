package com.limelight.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.SharpenRenderer;
import com.limelight.utils.Stereo3DRenderer;

/**
 * A container that manages different stream display modes and now correctly
 * handles all input callbacks, aspect ratio scaling, and a robust surface lifecycle.
 * It uses SurfaceView for 2D and GLSurfaceView for both 3D modes.
 */
public class StreamContainer extends FrameLayout implements SurfaceHolder.Callback, Stereo3DRenderer.OnSurfaceReadyListener,
        SharpenRenderer.Listener {

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    public enum StreamMode {
        MODE_2D,
        MODE_AI_3D,
        MODE_AI_3D_MOVIE
    }

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    // Optional sharpening of the 2D picture: drawn through OpenGL instead of straight to the screen
    private SharpenRenderer mSharpenRenderer;
    private SurfaceView mPlainSurfaceView;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private Runnable onSurfaceAvailable;
    private StreamMode renderMode = null;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private double desiredAspectRatio;
    private boolean fillDisplay = false;

    private boolean isSurfaceReady = false;

    public StreamContainer(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return;
        }

        this.game = game;
        this.prefConfig = prefConfig;
        this.renderMode = mapIntToStreamMode(prefConfig.renderMode);

        Stereo3DRenderer.isMovieMode = renderMode == StreamMode.MODE_AI_3D_MOVIE;

        isSurfaceReady = false;
        mCurrentSurface = null;

        Context context = getContext();
        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        // Always craete a surface view as a Workaround for the sizing issue of GLSurfaceView
        mSurfaceView = new SurfaceView(context);
        addView(mSurfaceView, childParams);

        if (renderMode != StreamMode.MODE_2D) {
            GLSurfaceView glSurfaceView = new GLSurfaceView(context);
            glSurfaceView.setEGLContextClientVersion(3);
            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, this, context, prefConfig);
            glSurfaceView.setRenderer(mStereoRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            mSurfaceView = glSurfaceView;
            addView(mSurfaceView, childParams);
        } else {
            float strength = SharpenRenderer.strengthOf(prefConfig.sharpen);
            if (SharpenRenderer.appliesTo(prefConfig)) {
                mPlainSurfaceView = mSurfaceView;
                // Kept in the layout but without a surface of its own, so it can't cover the picture
                mPlainSurfaceView.setVisibility(INVISIBLE);
                GLSurfaceView glSurfaceView = new GLSurfaceView(context);
                glSurfaceView.setEGLContextClientVersion(2);
                mSharpenRenderer = new SharpenRenderer(glSurfaceView, this, prefConfig.width, prefConfig.height, strength);
                glSurfaceView.setRenderer(mSharpenRenderer);
                glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                // Draw at the stream's own size; the TV scales the sharpened picture to the screen
                glSurfaceView.getHolder().setFixedSize(prefConfig.width, prefConfig.height);
                mSurfaceView = glSurfaceView;
                addView(mSurfaceView, childParams);
                LimeLog.info("Sharpening the picture (" + prefConfig.sharpen + ")");
            }
        }

        mSurfaceView.getHolder().addCallback(this);
        if (mSurfaceView.getHolder().getSurface() != null && mSurfaceView.getHolder().getSurface().isValid()) {
            surfaceChanged(mSurfaceView.getHolder(), PixelFormat.RGBA_8888, mSurfaceView.getWidth(), mSurfaceView.getHeight());
        }
    }

    // --- Aspect Ratio and Scaling Logic ---
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
        requestLayout();
    }

    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (renderMode != StreamMode.MODE_2D) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight, measuredWidth;

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(measuredWidth / desiredAspectRatio);
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY);
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.mInputCallbacks = callbacks;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mInputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mInputCallbacks.handleKeyDown(event)) return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mInputCallbacks.handleKeyUp(event)) return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mInputCallbacks != null) {
            mInputCallbacks.handleFocusChange(hasWindowFocus);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return mInputCallbacks != null && mInputCallbacks.handleCommitText(text) || super.commitText(text, newCursorPosition);
            }
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return mInputCallbacks != null && mInputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength) || super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    public void setOnSurfaceAvailable(Runnable callback) {
        this.onSurfaceAvailable = callback;
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    public Surface getSurface() {
        return mCurrentSurface;
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }

    public StreamMode mapIntToStreamMode(int modeIndex) {
        StreamContainer.StreamMode[] modes = StreamContainer.StreamMode.values();
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        } else {
            return StreamContainer.StreamMode.MODE_2D;
        }
    }

    private void notifySurfaceReady() {
        isSurfaceReady = true;
        if (onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        game.surfaceCreated(holder);
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (renderMode == StreamMode.MODE_2D && mSharpenRenderer == null && width > 0 && height > 0) {
            mCurrentSurface = holder.getSurface();
            notifySurfaceReady();
        }

        game.surfaceChanged(holder, format, width, height);
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (holder != mSurfaceView.getHolder()) {
            // The sharpening view being taken away after it failed
            return;
        }
        if (mSharpenRenderer != null) {
            isSurfaceReady = false;
            mCurrentSurface = null;
            mSharpenRenderer.release();
        } else if (renderMode == StreamMode.MODE_2D) {
            isSurfaceReady = false;
            mCurrentSurface = null;
        } else if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }

        game.surfaceDestroyed(holder);
    }

    @Override
    public void onStereo3DSurfaceReady(Surface surface) {
        if (renderMode != StreamMode.MODE_2D) {
            mCurrentSurface = surface;
            notifySurfaceReady();
        }
    }

    @Override
    public void onSharpenSurfaceReady(final Surface surface) {
        // Called on the drawing thread
        post(() -> {
            if (mSharpenRenderer != null) {
                mCurrentSurface = surface;
                notifySurfaceReady();
            }
        });
    }

    @Override
    public void onSharpenFailed() {
        // This device can't run the filter: show the picture the ordinary way instead
        post(() -> {
            if (mSharpenRenderer == null || mPlainSurfaceView == null) {
                return;
            }
            SurfaceView glView = mSurfaceView;
            mSharpenRenderer = null;
            mSurfaceView = mPlainSurfaceView;
            mPlainSurfaceView = null;
            glView.getHolder().removeCallback(this);
            removeView(glView);
            // Showing it creates its surface, which starts the stream as usual
            mSurfaceView.getHolder().addCallback(this);
            mSurfaceView.setVisibility(VISIBLE);
        });
    }

    public void onDestroy() {
        if (mSharpenRenderer != null) {
            mSharpenRenderer.release();
        }
        if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }
    }
}
