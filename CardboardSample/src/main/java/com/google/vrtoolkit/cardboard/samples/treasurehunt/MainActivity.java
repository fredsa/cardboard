/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.EyeTransform;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * A Cardboard sample application.
 */
public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer {

    private static final String TAG = "MainActivity";

    private static final int CUBE_COUNT = 30;
    private static final int BYTES_PER_FLOAT = 4;

    private static final float CAMERA_Z = 0.01f;
    private static final float TIME_DELTA = .3f;

    private static final float YAW_LIMIT = 0.12f;
    private static final float PITCH_LIMIT = 0.12f;

    // We keep the light always position just above the user.
    private final float[] mLightPosInWorldSpace = new float[]{0.0f, 2.0f, 0.0f, 1.0f};
    private final float[] mLightPosInEyeSpace = new float[4];

    private static final int COORDS_PER_VERTEX = 3;

    private FloatBuffer mFloorVertices;
    private FloatBuffer mFloorColors;
    private FloatBuffer mFloorNormals;

    private FloatBuffer mCubeVertices;
    private FloatBuffer mCubeColors;
    private FloatBuffer mCubeFoundColors;
    private FloatBuffer mCubeNormals;

    private int mGlProgram;
    private int mPositionParam;
    private int mNormalParam;
    private int mColorParam;
    private int mModelViewProjectionParam;
    private int mLightPosParam;
    private int mModelViewParam;
    private int mModelParam;
    private int mIsFloorParam;

    private float[][] mModelCubes;
    private float[] mCamera;
    private float[] mView;
    private float[] mHeadView;
    private float[] mModelViewProjection;
    private float[] mModelView;

    private float[] mModelFloor;

    private int mScore = 0;
    private float[] mObjectDistances;
    private float mFloorDepth = 20f;

    private Vibrator mVibrator;

    private CardboardOverlayView mOverlayView;
    private long before = System.currentTimeMillis();
    private int fpsCount;
    private long fps;

    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader
     *
     * @param type  The type of shader we will be creating.
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return
     */
    private int loadGLShader(int type, int resId) {
        String code = readRawTextFile(resId);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param func
     */
    private static void checkGLError(String func) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, func + ": glError " + error);
            throw new RuntimeException(func + ": glError " + error);
        }
    }

    /**
     * Sets the view to our CardboardView and initializes the transformation matrices we will use
     * to render our scene.
     *
     * @param savedInstanceState
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.common_ui);
        CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
        cardboardView.setRenderer(this);
        setCardboardView(cardboardView);

        mModelCubes = new float[CUBE_COUNT][16];
        mObjectDistances = new float[CUBE_COUNT];
        mCamera = new float[16];
        mView = new float[16];
        mModelViewProjection = new float[16];
        mModelView = new float[16];
        mModelFloor = new float[16];
        mHeadView = new float[16];
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);


        mOverlayView = (CardboardOverlayView) findViewById(R.id.overlay);
        mOverlayView.show3DToast("Pull the magnet when you find an object.");
    }

    @Override
    public void onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.i(TAG, "onSurfaceChanged");
    }

    /**
     * Creates the buffers we use to store information about the 3D world. OpenGL doesn't use Java
     * arrays, but rather needs data in a format it can understand. Hence we use ByteBuffers.
     *
     * @param config The EGL configuration used when creating the surface.
     */
    @Override
    public void onSurfaceCreated(EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well

        final WorldLayoutData DATA = new WorldLayoutData();

        ByteBuffer bbVertices = ByteBuffer.allocateDirect(DATA.CUBE_COORDS.length * BYTES_PER_FLOAT);
        bbVertices.order(ByteOrder.nativeOrder());
        mCubeVertices = bbVertices.asFloatBuffer();
        mCubeVertices.put(DATA.CUBE_COORDS);
        mCubeVertices.position(0);

        ByteBuffer bbColors = ByteBuffer.allocateDirect(DATA.CUBE_COLORS.length * BYTES_PER_FLOAT);
        bbColors.order(ByteOrder.nativeOrder());
        mCubeColors = bbColors.asFloatBuffer();
        mCubeColors.put(DATA.CUBE_COLORS);
        mCubeColors.position(0);

        ByteBuffer bbFoundColors = ByteBuffer.allocateDirect(DATA.CUBE_FOUND_COLORS.length * BYTES_PER_FLOAT);
        bbFoundColors.order(ByteOrder.nativeOrder());
        mCubeFoundColors = bbFoundColors.asFloatBuffer();
        mCubeFoundColors.put(DATA.CUBE_FOUND_COLORS);
        mCubeFoundColors.position(0);

        ByteBuffer bbNormals = ByteBuffer.allocateDirect(DATA.CUBE_NORMALS.length * BYTES_PER_FLOAT);
        bbNormals.order(ByteOrder.nativeOrder());
        mCubeNormals = bbNormals.asFloatBuffer();
        mCubeNormals.put(DATA.CUBE_NORMALS);
        mCubeNormals.position(0);

        // make a floor
        ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(DATA.FLOOR_COORDS.length * BYTES_PER_FLOAT);
        bbFloorVertices.order(ByteOrder.nativeOrder());
        mFloorVertices = bbFloorVertices.asFloatBuffer();
        mFloorVertices.put(DATA.FLOOR_COORDS);
        mFloorVertices.position(0);

        ByteBuffer bbFloorNormals = ByteBuffer.allocateDirect(DATA.FLOOR_NORMALS.length * BYTES_PER_FLOAT);
        bbFloorNormals.order(ByteOrder.nativeOrder());
        mFloorNormals = bbFloorNormals.asFloatBuffer();
        mFloorNormals.put(DATA.FLOOR_NORMALS);
        mFloorNormals.position(0);

        ByteBuffer bbFloorColors = ByteBuffer.allocateDirect(DATA.FLOOR_COLORS.length * BYTES_PER_FLOAT);
        bbFloorColors.order(ByteOrder.nativeOrder());
        mFloorColors = bbFloorColors.asFloatBuffer();
        mFloorColors.put(DATA.FLOOR_COLORS);
        mFloorColors.position(0);

        int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.light_vertex);
        int gridShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.grid_fragment);

        mGlProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mGlProgram, vertexShader);
        GLES20.glAttachShader(mGlProgram, gridShader);
        GLES20.glLinkProgram(mGlProgram);

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        Matrix.setIdentityM(mModelFloor, 0);
        Matrix.translateM(mModelFloor, 0, 0, -mFloorDepth, 0); // Floor appears below user

        for (int cube = 0; cube < mModelCubes.length; cube++) {
            mObjectDistances[cube] = 16f;
            Matrix.setIdentityM(mModelCubes[cube], 0);
            Matrix.translateM(mModelCubes[cube], 0, 0, 0, -mObjectDistances[cube]);
            hideObject(cube);
        }

        checkGLError("onSurfaceCreated");
    }

    /**
     * Converts a raw text file into a string.
     *
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return
     */
    private String readRawTextFile(int resId) {
        InputStream inputStream = getResources().openRawResource(resId);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * Prepares OpenGL ES before we draw a frame.
     *
     * @param headTransform The head transformation in the new frame.
     */
    @Override
    public void onNewFrame(HeadTransform headTransform) {
        GLES20.glUseProgram(mGlProgram);

        mModelViewProjectionParam = GLES20.glGetUniformLocation(mGlProgram, "u_MVP");
        mLightPosParam = GLES20.glGetUniformLocation(mGlProgram, "u_LightPos");
        mModelViewParam = GLES20.glGetUniformLocation(mGlProgram, "u_MVMatrix");
        mModelParam = GLES20.glGetUniformLocation(mGlProgram, "u_Model");
        mIsFloorParam = GLES20.glGetUniformLocation(mGlProgram, "u_IsFloor");

        for (int cube = 0; cube < mModelCubes.length; cube++) {
            // Build the Model part of the ModelView matrix.
            Matrix.rotateM(mModelCubes[cube], 0, TIME_DELTA * cube, 0.5f, 0.5f, 1.0f);
        }

        // Build the camera matrix and apply it to the ModelView.
        Matrix.setLookAtM(mCamera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        headTransform.getHeadView(mHeadView, 0);

        checkGLError("onReadyToDraw");

        if (++fpsCount == 10) {
            fpsCount = 0;
            long now = System.currentTimeMillis();
            fps = (long) (1000f / (float)(now - before + 1) * 10f);
            before = now;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mOverlayView.show3DToast(fps + " FPS");
                }
            });
//            Log.i(TAG, "FPS = " + fps);
        }
    }

    /**
     * Draws a frame for an eye. The transformation for that eye (from the camera) is passed in as
     * a parameter.
     *
     * @param transform The transformations to apply to render this eye.
     */
    @Override
    public void onDrawEye(EyeTransform transform) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        mPositionParam = GLES20.glGetAttribLocation(mGlProgram, "a_Position");
        mNormalParam = GLES20.glGetAttribLocation(mGlProgram, "a_Normal");
        mColorParam = GLES20.glGetAttribLocation(mGlProgram, "a_Color");

        GLES20.glEnableVertexAttribArray(mPositionParam);
        GLES20.glEnableVertexAttribArray(mNormalParam);
        GLES20.glEnableVertexAttribArray(mColorParam);
        checkGLError("mColorParam");

        // Apply the eye transformation to the camera.
        Matrix.multiplyMM(mView, 0, transform.getEyeView(), 0, mCamera, 0);

        // Set the position of the light
        Matrix.multiplyMV(mLightPosInEyeSpace, 0, mView, 0, mLightPosInWorldSpace, 0);
        GLES20.glUniform3f(mLightPosParam, mLightPosInEyeSpace[0], mLightPosInEyeSpace[1],
                mLightPosInEyeSpace[2]);

        for (int cube = 0; cube < mModelCubes.length; cube++) {
            // Build the ModelView and ModelViewProjection matrices
            // for calculating cube position and light.
            Matrix.multiplyMM(mModelView, 0, mView, 0, mModelCubes[cube], 0);
            Matrix.multiplyMM(mModelViewProjection, 0, transform.getPerspective(), 0, mModelView, 0);
            drawCube(cube);
        }

        // Set mModelView for the floor, so we draw floor in the correct location
        Matrix.multiplyMM(mModelView, 0, mView, 0, mModelFloor, 0);
        Matrix.multiplyMM(mModelViewProjection, 0, transform.getPerspective(), 0,
                mModelView, 0);
        drawFloor(transform.getPerspective());
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
    }

    /**
     * Draw the cube. We've set all of our transformation matrices. Now we simply pass them into
     * the shader.
     *
     * @param cube which Cube to draw
     */
    public void drawCube(int cube) {
        // This is not the floor!
        GLES20.glUniform1f(mIsFloorParam, 0f);

        // Set the Model in the shader, used to calculate lighting
        GLES20.glUniformMatrix4fv(mModelParam, 1, false, mModelCubes[cube], 0);

        // Set the ModelView in the shader, used to calculate lighting
        GLES20.glUniformMatrix4fv(mModelViewParam, 1, false, mModelView, 0);

        // Set the position of the cube
        GLES20.glVertexAttribPointer(mPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, 0, mCubeVertices);

        // Set the ModelViewProjection matrix in the shader.
        GLES20.glUniformMatrix4fv(mModelViewProjectionParam, 1, false, mModelViewProjection, 0);

        // Set the normal positions of the cube, again for shading
        GLES20.glVertexAttribPointer(mNormalParam, 3, GLES20.GL_FLOAT,
                false, 0, mCubeNormals);


        if (isLookingAtObject(cube)) {
            GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false,
                    0, mCubeFoundColors);
        } else {
            GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false,
                    0, mCubeColors);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        checkGLError("Drawing cube");
    }

    /**
     * Draw the floor. This feeds in data for the floor into the shader. Note that this doesn't
     * feed in data about position of the light, so if we rewrite our code to draw the floor first,
     * the lighting might look strange.
     */
    public void drawFloor(float[] perspective) {
        // This is the floor!
        GLES20.glUniform1f(mIsFloorParam, 1f);

        // Set ModelView, MVP, position, normals, and color
        GLES20.glUniformMatrix4fv(mModelParam, 1, false, mModelFloor, 0);
        GLES20.glUniformMatrix4fv(mModelViewParam, 1, false, mModelView, 0);
        GLES20.glUniformMatrix4fv(mModelViewProjectionParam, 1, false, mModelViewProjection, 0);
        GLES20.glVertexAttribPointer(mPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, 0, mFloorVertices);
        GLES20.glVertexAttribPointer(mNormalParam, 3, GLES20.GL_FLOAT, false, 0, mFloorNormals);
        GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false, 0, mFloorColors);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        checkGLError("drawing floor");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Log.d(TAG, "onTouchEvent");
        onUserSees();
        return super.onTouchEvent(event);
    }

    @Override
    public void onCardboardTrigger() {
        Log.d(TAG, "onCardboardTrigger");
        onUserSees();
    }

    /**
     * Increment the score, hide the object, and give feedback if the user pulls the magnet while
     * looking at the object. Otherwise, remind the user what to do.
     */
    private void onUserSees() {
        for (int cube = 0; cube < mModelCubes.length; cube++) {
            if (isLookingAtObject(cube)) {
                mScore++;
                mOverlayView.show3DToast("Score = " + mScore);
                hideObject(cube);
            }
        }
    }

    /**
     * Find a new random position for the object.
     */
    private void hideObject(int cube) {
        float[] rotationMatrix = new float[16];
        float[] posVec = new float[4];

        // First rotate in XZ plane, between 90 and 270 deg away, and scale so that we vary
        // the object's distance from the user.

//        float angleXZ = (float) Math.random() * 180 + 90;
        float angleXZ = (float) Math.random() * 40 - 20;

        Matrix.setRotateM(rotationMatrix, 0, angleXZ, 0f, 1f, 0f);
        float oldObjectDistance = mObjectDistances[cube];
        mObjectDistances[cube] = (float) Math.random() * 10 + 10;
        float objectScalingFactor = mObjectDistances[cube] / oldObjectDistance;
        Matrix.scaleM(rotationMatrix, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
        Matrix.multiplyMV(posVec, 0, rotationMatrix, 0, mModelCubes[cube], 12);

        float angleY = (float) Math.random() * 40 - 40;
        angleY = (float) Math.toRadians(angleY);
        float newY = (float) Math.tan(angleY) * mObjectDistances[cube];

        Matrix.setIdentityM(mModelCubes[cube], 0);
        Matrix.translateM(mModelCubes[cube], 0, posVec[0], newY, posVec[2]);
    }

    /**
     * Check if user is looking at object by calculating where the object is in eye-space.
     *
     * @return
     */
    private boolean isLookingAtObject(int cube) {
        float[] initVec = {0, 0, 0, 1.0f};
        float[] objPositionVec = new float[4];

        // Convert object space to camera space. Use the headView from onNewFrame.
        Matrix.multiplyMM(mModelView, 0, mHeadView, 0, mModelCubes[cube], 0);
        Matrix.multiplyMV(objPositionVec, 0, mModelView, 0, initVec, 0);

        float pitch = (float) Math.atan2(objPositionVec[1], -objPositionVec[2]);
        float yaw = (float) Math.atan2(objPositionVec[0], -objPositionVec[2]);

//        Log.v(TAG, "Object position: X: " + objPositionVec[0]
//                + "  Y: " + objPositionVec[1] + " Z: " + objPositionVec[2]);
//        Log.v(TAG, "Object Pitch: " + pitch + "  Yaw: " + yaw);

        return (Math.abs(pitch) < PITCH_LIMIT) && (Math.abs(yaw) < YAW_LIMIT);
    }
}
