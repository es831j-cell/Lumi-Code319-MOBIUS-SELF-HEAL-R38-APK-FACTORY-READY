package com.distressedelk.lumi;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.View;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Code312 seamless real-time Möbius hologram.
 *
 * The ribbon is generated as a parameterized surface with an explicit duplicate
 * closure ring at u=2π. The closure ring occupies the exact same 3D positions
 * as the u=0 ring with the required Möbius V reversal, but keeps continuous
 * shader-U values (near 1.0 instead of jumping directly to 0.0).
 *
 * Animation never pushes along the surface normal. A Möbius strip is
 * non-orientable, so a continuous global normal field cannot exist; normal
 * displacement therefore exposes the closure as a moving crack. Motion is
 * instead applied as smooth periodic whole-surface breathing/deformation.
 */
public final class Mobius3DView extends GLSurfaceView {
    public enum VisualState { PAUSED, READY, LISTENING, THINKING, SPEAKING }

    private final MobiusRenderer renderer;
    private volatile boolean frameDriverRunning;
    private volatile boolean surfacePaused;
    private long frameDriverTicks;
    private long lastDriverTickMs;
    private long frameDriverRecoveries;
    private String lastDriverEvent = "init";

    private final Runnable animationWatchdog = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            boolean visible = getVisibility() == View.VISIBLE && getWindowVisibility() == View.VISIBLE;
            if (visible && !surfacePaused) {
                long now = SystemClock.elapsedRealtime();
                long age = lastDriverTickMs == 0 ? Long.MAX_VALUE : now - lastDriverTickMs;
                if (!frameDriverRunning || age > 750L) {
                    frameDriverRecoveries++;
                    startFrameDriver("watchdog");
                } else if (renderer.frameAgeMs() > 750L) {
                    // Driver is ticking but GL stopped producing frames. Nudge the render queue.
                    lastDriverEvent = "watchdog-render-nudge";
                    requestRender();
                }
            }
            postDelayed(this, 500L);
        }
    };

    private final Runnable frameDriver = new Runnable() {
        @Override public void run() {
            if (!frameDriverRunning || !isAttachedToWindow() || getVisibility() != View.VISIBLE) return;
            frameDriverTicks++;
            lastDriverTickMs = SystemClock.elapsedRealtime();
            requestRender();
            postOnAnimation(this);
        }
    };

    public Mobius3DView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new MobiusRenderer(context.getApplicationContext());
        setRenderer(renderer);
        // Code317: drive rendering from the UI display-vsync explicitly.
        // WHEN_DIRTY + postOnAnimation prevents vendor/GLSurface lifecycle stalls
        // from leaving a valid mesh frozen on screen.
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(false);
        setClickable(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        surfacePaused = false;
        removeCallbacks(animationWatchdog);
        post(animationWatchdog);
        startFrameDriver("attached");
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(animationWatchdog);
        stopFrameDriver("detached");
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            if (visibility == View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("view-visible");
            else if (visibility != View.VISIBLE) stopFrameDriver("view-hidden");
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("window-visible");
        else if (visibility != View.VISIBLE) stopFrameDriver("window-hidden");
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && isAttachedToWindow() && getVisibility() == View.VISIBLE && !surfacePaused) {
            startFrameDriver("window-focus");
        }
    }

    @Override public void onResume() {
        super.onResume();
        surfacePaused = false;
        startFrameDriver("gl-resume");
        requestRender();
    }

    @Override public void onPause() {
        surfacePaused = true;
        stopFrameDriver("gl-pause");
        super.onPause();
    }

    private void startFrameDriver() { startFrameDriver("start"); }

    private void startFrameDriver(String reason) {
        boolean wasRunning = frameDriverRunning;
        frameDriverRunning = true;
        lastDriverEvent = reason;
        removeCallbacks(frameDriver);
        postOnAnimation(frameDriver);
        if (!wasRunning) requestRender();
    }

    private void stopFrameDriver() { stopFrameDriver("stop"); }

    private void stopFrameDriver(String reason) {
        frameDriverRunning = false;
        lastDriverEvent = reason;
        removeCallbacks(frameDriver);
    }

    public String diagnosticSnapshot() {
        long now = SystemClock.elapsedRealtime();
        long driverAge = lastDriverTickMs == 0 ? -1 : Math.max(0, now - lastDriverTickMs);
        return "driver=" + (frameDriverRunning ? "RUNNING" : "STOPPED")
                + " • driverTicks=" + frameDriverTicks
                + " • driverAgeMs=" + driverAge
                + " • recoveries=" + frameDriverRecoveries
                + " • lastDriverEvent=" + lastDriverEvent
                + " • surfacePaused=" + surfacePaused
                + " • attached=" + isAttachedToWindow()
                + " • viewVisibility=" + getVisibility()
                + " • windowVisibility=" + getWindowVisibility()
                + " • frames=" + renderer.frameCount
                + " • fps=" + String.format(java.util.Locale.US, "%.1f", renderer.fps)
                + " • frameAgeMs=" + renderer.frameAgeMs()
                + " • phase=" + String.format(java.util.Locale.US, "%.2f", renderer.lastTimeSeconds)
                + " • state=" + renderer.state;
    }

    public void setVisualState(VisualState state) {
        final VisualState next = state == null ? VisualState.READY : state;
        queueEvent(() -> renderer.state = next);
        // Visual PAUSED means calm animation, not a stopped render loop.
        if (isAttachedToWindow() && getVisibility() == View.VISIBLE && getWindowVisibility() == View.VISIBLE && !surfacePaused) {
            startFrameDriver("visual-state-" + next.name().toLowerCase(java.util.Locale.US));
        }
    }

    private static final class MobiusRenderer implements GLSurfaceView.Renderer {
        private final android.content.SharedPreferences prefs;
        MobiusRenderer(Context context) { prefs=context.getSharedPreferences("lumi",Context.MODE_PRIVATE); }
        private static final int SEG_U = 160;
        private static final int SEG_V = 24;
        private static final float RADIUS = 1.22f;
        private static final float HALF_WIDTH = 0.34f;
        private static final int FPV = 8;

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] mv = new float[16];
        private final float[] mvp = new float[16];
        private final float[] normalMatrix = new float[16];

        private FloatBuffer vertices;
        private ShortBuffer indices;
        private int indexCount;
        private int program;
        private long startNanos;
        private volatile VisualState state = VisualState.READY;
        private volatile long frameCount;
        private volatile long lastFrameElapsedMs;
        private volatile float fps;
        private volatile float lastTimeSeconds;
        private long fpsWindowStartMs;
        private long fpsWindowFrames;

        long frameAgeMs() {
            long last = lastFrameElapsedMs;
            return last == 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - last);
        }

        @Override
        public void onSurfaceCreated(
                javax.microedition.khronos.opengles.GL10 gl,
                javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0.001f, 0.002f, 0.008f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            buildMesh();
            program = buildProgram(VS, FS);
            startNanos = System.nanoTime();
            fpsWindowStartMs = SystemClock.elapsedRealtime();
        }

        @Override
        public void onSurfaceChanged(
                javax.microedition.khronos.opengles.GL10 gl,
                int w,
                int h) {
            GLES20.glViewport(0, 0, w, h);
            float aspect = h == 0 ? 1f : (float) w / h;
            Matrix.perspectiveM(projection, 0, 27f, aspect, 0.1f, 30f);
            Matrix.setLookAtM(view, 0,
                    0f, -0.02f, 7.45f,
                    0f, 0.26f, 0f,
                    0f, 1f, 0f);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (program == 0) return;

            float t = (System.nanoTime() - startNanos) / 1_000_000_000f;
            lastTimeSeconds = t;
            frameCount++;
            fpsWindowFrames++;
            long frameNow = SystemClock.elapsedRealtime();
            lastFrameElapsedMs = frameNow;
            long fpsElapsed = frameNow - fpsWindowStartMs;
            if (fpsElapsed >= 1000L) {
                fps = fpsWindowFrames * 1000f / Math.max(1L, fpsElapsed);
                prefs.edit().putFloat("mobius_last_fps",fps).putLong("mobius_last_frame_age_ms",0L).putLong("mobius_frame_count",frameCount).apply();
                fpsWindowFrames = 0L;
                fpsWindowStartMs = frameNow;
            }
            float rotationSpeed;
            float motionAmp;
            float motionSpeed;
            float brightness;

            switch (state) {
                case PAUSED:
                    rotationSpeed = 1.2f;
                    motionAmp = .0015f;
                    motionSpeed = .22f;
                    brightness = .58f;
                    break;
                case LISTENING:
                    rotationSpeed = 4.2f;
                    motionAmp = .012f;
                    motionSpeed = .85f;
                    brightness = 1.00f;
                    break;
                case THINKING:
                    rotationSpeed = 6.6f;
                    motionAmp = .020f;
                    motionSpeed = 1.35f;
                    brightness = 1.12f;
                    break;
                case SPEAKING:
                    rotationSpeed = 5.2f;
                    motionAmp = .026f;
                    motionSpeed = 1.85f;
                    brightness = 1.16f;
                    break;
                default:
                    rotationSpeed = 2.5f;
                    motionAmp = .006f;
                    motionSpeed = .42f;
                    brightness = .82f;
                    break;
            }

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, 0f, .31f, 0f);
            Matrix.scaleM(model, 0, .70f, .70f, .70f);

            // Whole-object motion only. No per-segment transforms.
            Matrix.rotateM(model, 0, -7f, 1f, 0f, 0f);
            Matrix.rotateM(model, 0, -8f + t * rotationSpeed, 0f, 1f, 0f);
            Matrix.rotateM(model, 0,
                    2.2f * (float) Math.sin(t * .16f),
                    0f, 0f, 1f);

            Matrix.multiplyMM(mv, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0);
            System.arraycopy(model, 0, normalMatrix, 0, 16);

            GLES20.glUseProgram(program);

            int ap = GLES20.glGetAttribLocation(program, "aPosition");
            int an = GLES20.glGetAttribLocation(program, "aNormal");
            int au = GLES20.glGetAttribLocation(program, "aU");
            int av = GLES20.glGetAttribLocation(program, "aV");

            vertices.position(0);
            GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(ap);

            vertices.position(3);
            GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(an);

            vertices.position(6);
            GLES20.glVertexAttribPointer(au, 1, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(au);

            vertices.position(7);
            GLES20.glVertexAttribPointer(av, 1, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(av);

            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uMvp"),
                    1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uModel"),
                    1, false, model, 0);
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uNormalMatrix"),
                    1, false, normalMatrix, 0);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uTime"), t);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uMotionAmp"), motionAmp);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uMotionSpeed"), motionSpeed);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uBrightness"), brightness);

            indices.position(0);
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    indexCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    indices);

            GLES20.glDisableVertexAttribArray(ap);
            GLES20.glDisableVertexAttribArray(an);
            GLES20.glDisableVertexAttribArray(au);
            GLES20.glDisableVertexAttribArray(av);
        }

        /**
         * Build SEG_U rectangular surface strips plus one explicit closure ring.
         *
         * At u=2π the Möbius parameterization naturally produces the same
         * positions as u=0 with reversed v. Therefore there is no special
         * seam-index branch and no 0↔1 shader-coordinate interpolation.
         */
        private void buildMesh() {
            final int rows = SEG_V + 1;
            final int cols = SEG_U + 1; // explicit u=2π closure ring
            float[] data = new float[rows * cols * FPV];
            int p = 0;

            for (int i = 0; i <= SEG_U; i++) {
                double u = Math.PI * 2.0 * i / SEG_U;
                for (int j = 0; j <= SEG_V; j++) {
                    double v = -HALF_WIDTH + (HALF_WIDTH * 2.0 * j) / SEG_V;
                    float[] pos = position(u, v);
                    float[] du = derivativeU(u, v);
                    float[] dv = derivativeV(u, v);

                    float nx = du[1] * dv[2] - du[2] * dv[1];
                    float ny = du[2] * dv[0] - du[0] * dv[2];
                    float nz = du[0] * dv[1] - du[1] * dv[0];
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len < 1e-6f) len = 1f;

                    data[p++] = pos[0];
                    data[p++] = pos[1];
                    data[p++] = pos[2];
                    data[p++] = nx / len;
                    data[p++] = ny / len;
                    data[p++] = nz / len;
                    data[p++] = (float) (u / (Math.PI * 2.0)); // normalized 0..1, seam-safe
                    data[p++] = (float) j / SEG_V;
                }
            }

            short[] ix = new short[SEG_U * SEG_V * 6];
            int q = 0;
            for (int i = 0; i < SEG_U; i++) {
                for (int j = 0; j < SEG_V; j++) {
                    int a = i * rows + j;
                    int d = a + 1;
                    int b = (i + 1) * rows + j;
                    int c = b + 1;

                    ix[q++] = (short) a;
                    ix[q++] = (short) b;
                    ix[q++] = (short) c;
                    ix[q++] = (short) a;
                    ix[q++] = (short) c;
                    ix[q++] = (short) d;
                }
            }

            vertices = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(data).position(0);

            indices = ByteBuffer.allocateDirect(ix.length * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
            indices.put(ix).position(0);
            indexCount = ix.length;
        }

        private static float[] position(double u, double v) {
            double c = Math.cos(u * .5);
            double s = Math.sin(u * .5);
            double ring = RADIUS + v * c;
            return new float[]{
                    (float) (ring * Math.cos(u)),
                    (float) (ring * Math.sin(u)),
                    (float) (v * s)
            };
        }

        private static float[] derivativeU(double u, double v) {
            double e = .0008;
            float[] a = position(u - e, v);
            float[] b = position(u + e, v);
            return new float[]{
                    (float) ((b[0] - a[0]) / (2 * e)),
                    (float) ((b[1] - a[1]) / (2 * e)),
                    (float) ((b[2] - a[2]) / (2 * e))
            };
        }

        private static float[] derivativeV(double u, double v) {
            double e = .0008;
            float[] a = position(u, v - e);
            float[] b = position(u, v + e);
            return new float[]{
                    (float) ((b[0] - a[0]) / (2 * e)),
                    (float) ((b[1] - a[1]) / (2 * e)),
                    (float) ((b[2] - a[2]) / (2 * e))
            };
        }

        private static int buildProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;

            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);

            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteProgram(p);
                p = 0;
            }

            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);

            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteShader(s);
                return 0;
            }
            return s;
        }

        /**
         * Seam-safe deformation:
         * - aU is normalized 0..1.
         * - all waves use integer cycles, so values at 0 and 1 match exactly.
         * - no normal displacement.
         * - deformation is applied to the whole parameterized surface.
         */
        private static final String VS =
                "uniform mat4 uMvp; uniform mat4 uModel; uniform mat4 uNormalMatrix;"
              + "uniform float uTime; uniform float uMotionAmp; uniform float uMotionSpeed;"
              + "attribute vec3 aPosition; attribute vec3 aNormal; attribute float aU; attribute float aV;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vWave; varying float vU; varying float vV;"
              + "void main(){"
              + "float tau=6.28318530718;"
              + "float phase=aU*tau;"
              + "float wave=sin(phase*2.0-uTime*uMotionSpeed*2.0);"
              + "float wave2=sin(phase*3.0+uTime*uMotionSpeed*1.15);"
              + "float breathe=1.0+uMotionAmp*(0.55*wave+0.25*wave2);"
              + "vec3 pos=aPosition;"
              + "pos.xy*=breathe;"
              + "pos.z*=1.0+uMotionAmp*0.45*wave;"
              + "pos.z+=uMotionAmp*0.11*sin(phase*2.0-uTime*uMotionSpeed);"
              + "vec4 world=uModel*vec4(pos,1.0);"
              + "vWorld=world.xyz;"
              + "vNormal=normalize((uNormalMatrix*vec4(aNormal,0.0)).xyz);"
              + "vWave=.5+.5*wave;"
              + "vU=aU;"
              + "vV=aV;"
              + "gl_Position=uMvp*vec4(pos,1.0);"
              + "}";

        /**
         * Two-sided glass lighting is deliberate. A Möbius strip has no globally
         * consistent front normal, so abs(dot()) avoids a lighting flip at closure.
         */
        private static final String FS =
                "precision mediump float;"
              + "uniform float uBrightness;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vWave; varying float vU; varying float vV;"
              + "float line(float x,float w){return 1.0-step(w,abs(x));}"
              + "float hash(float n){return fract(sin(n*91.731)*43758.5453);}"
              + "void main(){"
              + "vec3 N=normalize(vNormal);"
              + "vec3 V=normalize(vec3(0.0,0.0,7.0)-vWorld);"
              + "vec3 L=normalize(vec3(-.45,-.2,1.0));"
              + "float ndl=abs(dot(N,L));"
              + "float ndv=abs(dot(N,V));"
              + "float diff=.18+.82*ndl;"
              + "float rim=pow(1.0-ndv,2.0);"
              + "float spec=pow(abs(dot(N,normalize(L+V))),64.0);"
              + "vec3 violet=vec3(.48,.06,.95),blue=vec3(.02,.38,1.0),cyan=vec3(.02,.95,1.0),gold=vec3(1.0,.48,.08);"
              + "float hue=.5+.5*sin(vU*6.2831853+1.1);"
              + "vec3 base=mix(violet,blue,hue);"
              + "base=mix(base,cyan,smoothstep(.55,1.0,vV)*.34);"
              + "base=mix(base,gold,smoothstep(.72,1.0,sin(vU*12.56637+2.0))*.18);"
              + "float gridU=line(fract(vU*28.0)-.5,.018);"
              + "float gridV=line(fract(vV*10.0)-.5,.025);"
              + "float circuitry=max(gridU*.23,gridV*.18);"
              + "float sector=floor(min(vU,.99999)*22.0);"
              + "float lu=fract(vU*22.0);"
              + "float lv=vV;"
              + "float active=step(.42,hash(sector));"
              + "float diagA=line((lv-.5)-(lu-.5)*1.45,.045);"
              + "float diagB=line((lv-.5)+(lu-.5)*1.45,.045);"
              + "float bar=line(lu-.50,.040)*step(.20,lv)*step(lv,.80);"
              + "float cap=line(lv-.30,.035)*step(.25,lu)*step(lu,.75);"
              + "float dotRune=(1.0-step(.055,length(vec2(lu-.50,lv-.50))));"
              + "float selector=hash(sector+17.0);"
              + "float rune=active*step(.14,lv)*step(lv,.86)*mix(max(diagA,diagB),max(bar,max(cap,dotRune)),step(.5,selector));"
              + "vec3 glow=mix(cyan,vec3(.95,.18,1.0),hash(sector+3.0));"
              + "vec3 color=base*(.20+.62*diff)+base*rim*1.25+vec3(1.0)*spec*.85;"
              + "color+=base*circuitry*.48;"
              + "color+=glow*rune*(1.10+.35*vWave);"
              + "color*=uBrightness;"
              + "float alpha=.74+rim*.18+rune*.06;"
              + "gl_FragColor=vec4(color,clamp(alpha,0.0,1.0));"
              + "}";
    }
}
