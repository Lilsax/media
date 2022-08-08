/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkState;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Pair;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.FrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Arrays;

/**
 * Applies a sequence of transformation matrices in the vertex shader, and copies input pixels into
 * an output frame based on their locations after applying the sequence of transformation matrices.
 *
 * <p>Operations are done on normalized device coordinates (-1 to 1 on x, y, and z axes).
 * Transformed vertices that are moved outside of this range after any of the transformation
 * matrices are clipped to the NDC range.
 *
 * <p>The background color of the output frame will be (r=0, g=0, b=0, a=0).
 *
 * <p>Can copy frames from an external texture and apply color transformations for HDR if needed.
 */
@UnstableApi
@SuppressWarnings("FunctionalInterfaceClash") // b/228192298
/* package */ final class MatrixTransformationProcessor extends SingleFrameGlTextureProcessor
    implements ExternalTextureProcessor {

  private static final String VERTEX_SHADER_TRANSFORMATION_PATH =
      "shaders/vertex_shader_transformation_es2.glsl";
  private static final String VERTEX_SHADER_TRANSFORMATION_ES3_PATH =
      "shaders/vertex_shader_transformation_es3.glsl";
  private static final String FRAGMENT_SHADER_COPY_PATH = "shaders/fragment_shader_copy_es2.glsl";
  private static final String FRAGMENT_SHADER_HLG_EOTF_ES3_PATH =
      "shaders/fragment_shader_hlg_eotf_es3.glsl";
  private static final String FRAGMENT_SHADER_COPY_EXTERNAL_PATH =
      "shaders/fragment_shader_copy_external_es2.glsl";
  private static final String FRAGMENT_SHADER_COPY_EXTERNAL_YUV_ES3_PATH =
      "shaders/fragment_shader_copy_external_yuv_es3.glsl";
  private static final ImmutableList<float[]> NDC_SQUARE =
      ImmutableList.of(
          new float[] {-1, -1, 0, 1},
          new float[] {-1, 1, 0, 1},
          new float[] {1, 1, 0, 1},
          new float[] {1, -1, 0, 1});
  // Color transform coefficients from
  // https://cs.android.com/android/platform/superproject/+/master:frameworks/av/media/libstagefright/colorconversion/ColorConverter.cpp;l=668-670;drc=487adf977a50cac3929eba15fad0d0f461c7ff0f.
  private static final float[] MATRIX_YUV_TO_BT2020_COLOR_TRANSFORM = {
    1.168f, 1.168f, 1.168f,
    0.0f, -0.188f, 2.148f,
    1.683f, -0.652f, 0.0f,
  };

  /** The {@link MatrixTransformation MatrixTransformations} to apply. */
  private final ImmutableList<GlMatrixTransformation> matrixTransformations;
  /**
   * The transformation matrices provided by the {@link MatrixTransformation MatrixTransformations}
   * for the most recent frame.
   */
  private final float[][] transformationMatrixCache;
  /**
   * The product of the {@link #transformationMatrixCache} for the most recent frame, to be applied
   * in the vertex shader.
   */
  private final float[] compositeTransformationMatrix;
  /** Matrix for storing an intermediate calculation result. */
  private final float[] tempResultMatrix;

  /**
   * A polygon in the input space chosen such that no additional clipping is needed to keep vertices
   * inside the NDC range when applying each of the {@link #matrixTransformations}.
   *
   * <p>This means that this polygon and {@link #compositeTransformationMatrix} can be used instead
   * of applying each of the {@link #matrixTransformations} to {@link #NDC_SQUARE} in separate
   * shaders.
   */
  private ImmutableList<float[]> visiblePolygon;

  private final GlProgram glProgram;

  /**
   * Creates a new instance.
   *
   * @param context The {@link Context}.
   * @param useHdr Whether input textures come from an HDR source. If {@code true}, colors will be
   *     in linear RGB BT.2020. If {@code false}, colors will be in gamma RGB BT.709.
   * @param matrixTransformation A {@link MatrixTransformation} that specifies the transformation
   *     matrix to use for each frame.
   * @throws FrameProcessingException If a problem occurs while reading shader files.
   */
  public MatrixTransformationProcessor(
      Context context, boolean useHdr, MatrixTransformation matrixTransformation)
      throws FrameProcessingException {
    this(
        createGlProgram(
            context,
            /* inputOpticalColorsFromExternalTexture= */ false,
            useHdr,
            /* outputOpticalColors= */ false),
        ImmutableList.of(matrixTransformation),
        useHdr);
  }

  /**
   * Creates a new instance.
   *
   * @param context The {@link Context}.
   * @param useHdr Whether input textures come from an HDR source. If {@code true}, colors will be
   *     in linear RGB BT.2020. If {@code false}, colors will be in gamma RGB BT.709.
   * @param matrixTransformation A {@link GlMatrixTransformation} that specifies the transformation
   *     matrix to use for each frame.
   * @throws FrameProcessingException If a problem occurs while reading shader files.
   */
  public MatrixTransformationProcessor(
      Context context, boolean useHdr, GlMatrixTransformation matrixTransformation)
      throws FrameProcessingException {
    this(
        createGlProgram(
            context,
            /* inputOpticalColorsFromExternalTexture= */ false,
            useHdr,
            /* outputOpticalColors= */ false),
        ImmutableList.of(matrixTransformation),
        useHdr);
  }

  /**
   * Creates a new instance.
   *
   * <p>Able to convert optical {@link ColorInfo} inputs and outputs to and from the intermediate
   * {@link GlTextureProcessor} colors of linear RGB BT.2020 for HDR, and gamma RGB BT.709 for SDR.
   *
   * @param context The {@link Context}.
   * @param matrixTransformations The {@link GlMatrixTransformation GlMatrixTransformations} to
   *     apply to each frame in order.
   * @param inputOpticalColorsFromExternalTexture Whether optical color input will be provided using
   *     an external texture. If {@code true}, the caller should use {@link
   *     #setTextureTransformMatrix(float[])} to provide the transformation matrix associated with
   *     the external texture.
   * @param opticalColorInfo The optical {@link ColorInfo}, only used to transform between color
   *     spaces and transfers, when {@code inputOpticalColorsFromExternalTexture} or {@code
   *     outputOpticalColors} are {@code true}. If it {@link ColorInfo#isHdr(ColorInfo)},
   *     intermediate {@link GlTextureProcessor} colors will be in linear RGB BT.2020. Otherwise,
   *     these colors will be in gamma RGB BT.709.
   * @param outputOpticalColors If {@code true}, outputs {@code opticalColorInfo}. If {@code false},
   *     outputs intermediate colors of linear RGB BT.2020 if {@code opticalColorInfo} {@link
   *     ColorInfo#isHdr(ColorInfo)}, and gamma RGB BT.709 otherwise.
   * @throws FrameProcessingException If a problem occurs while reading shader files or an OpenGL
   *     operation fails or is unsupported.
   */
  public MatrixTransformationProcessor(
      Context context,
      ImmutableList<GlMatrixTransformation> matrixTransformations,
      boolean inputOpticalColorsFromExternalTexture,
      ColorInfo opticalColorInfo,
      boolean outputOpticalColors)
      throws FrameProcessingException {
    this(
        createGlProgram(
            context,
            inputOpticalColorsFromExternalTexture,
            ColorInfo.isHdr(opticalColorInfo),
            outputOpticalColors),
        matrixTransformations,
        ColorInfo.isHdr(opticalColorInfo));
    if (!ColorInfo.isHdr(opticalColorInfo) || !inputOpticalColorsFromExternalTexture) {
      return;
    }
    // TODO(b/227624622): Implement YUV to RGB conversions in COLOR_RANGE_LIMITED as well, using
    //  opticalColorInfo.colorRange to select between them.

    // In HDR editing mode the decoder output is sampled in YUV.
    if (!GlUtil.isYuvTargetExtensionSupported()) {
      throw new FrameProcessingException(
          "The EXT_YUV_target extension is required for HDR editing input.");
    }
    glProgram.setFloatsUniform("uColorTransform", MATRIX_YUV_TO_BT2020_COLOR_TRANSFORM);
    // TODO(b/227624622): Implement PQ and gamma TFs, and use an @IntDef to select between HLG,
    //  PQ, and gamma, coming from opticalColorInfo.colorTransfer.

    // Applying the OETF will output a linear signal. Not applying the OETF will output an optical
    // signal.
    glProgram.setFloatUniform("uApplyHlgOetf", outputOpticalColors ? 0.0f : 1.0f);
  }

  /**
   * Creates a new instance.
   *
   * @param glProgram The {@link GlProgram}.
   * @param matrixTransformations The {@link GlMatrixTransformation GlMatrixTransformations} to
   *     apply to each frame in order.
   * @param useHdr Whether to process the input as an HDR signal. Using HDR requires the {@code
   *     EXT_YUV_target} OpenGL extension.
   */
  private MatrixTransformationProcessor(
      GlProgram glProgram,
      ImmutableList<GlMatrixTransformation> matrixTransformations,
      boolean useHdr) {
    super(useHdr);
    this.glProgram = glProgram;
    this.matrixTransformations = matrixTransformations;

    transformationMatrixCache = new float[matrixTransformations.size()][16];
    compositeTransformationMatrix = new float[16];
    tempResultMatrix = new float[16];
    Matrix.setIdentityM(compositeTransformationMatrix, /* smOffset= */ 0);
    visiblePolygon = NDC_SQUARE;
  }

  private static GlProgram createGlProgram(
      Context context,
      boolean inputOpticalColorsFromExternalTexture,
      boolean useHdr,
      boolean outputOpticalColors)
      throws FrameProcessingException {

    String vertexShaderFilePath;
    String fragmentShaderFilePath;
    if (inputOpticalColorsFromExternalTexture) {
      if (useHdr) {
        vertexShaderFilePath = VERTEX_SHADER_TRANSFORMATION_ES3_PATH;
        fragmentShaderFilePath = FRAGMENT_SHADER_COPY_EXTERNAL_YUV_ES3_PATH;
      } else {
        vertexShaderFilePath = VERTEX_SHADER_TRANSFORMATION_PATH;
        fragmentShaderFilePath = FRAGMENT_SHADER_COPY_EXTERNAL_PATH;
      }
    } else if (outputOpticalColors && useHdr) {
      vertexShaderFilePath = VERTEX_SHADER_TRANSFORMATION_ES3_PATH;
      fragmentShaderFilePath = FRAGMENT_SHADER_HLG_EOTF_ES3_PATH;
    } else {
      vertexShaderFilePath = VERTEX_SHADER_TRANSFORMATION_PATH;
      fragmentShaderFilePath = FRAGMENT_SHADER_COPY_PATH;
    }

    GlProgram glProgram;
    try {
      glProgram = new GlProgram(context, vertexShaderFilePath, fragmentShaderFilePath);
    } catch (IOException | GlUtil.GlException e) {
      throw new FrameProcessingException(e);
    }

    float[] identityMatrix = new float[16];
    Matrix.setIdentityM(identityMatrix, /* smOffset= */ 0);
    glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix);
    return glProgram;
  }

  @Override
  public void setTextureTransformMatrix(float[] textureTransformMatrix) {
    glProgram.setFloatsUniform("uTexTransformationMatrix", textureTransformMatrix);
  }

  @Override
  public Pair<Integer, Integer> configure(int inputWidth, int inputHeight) {
    return MatrixUtils.configureAndGetOutputSize(inputWidth, inputHeight, matrixTransformations);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs) throws FrameProcessingException {
    updateCompositeTransformationMatrixAndVisiblePolygon(presentationTimeUs);
    if (visiblePolygon.size() < 3) {
      return; // Need at least three visible vertices for a triangle.
    }

    try {
      glProgram.use();
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
      glProgram.setFloatsUniform("uTransformationMatrix", compositeTransformationMatrix);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.createVertexBuffer(visiblePolygon),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.bindAttributesAndUniforms();
      GLES20.glDrawArrays(
          GLES20.GL_TRIANGLE_FAN, /* first= */ 0, /* count= */ visiblePolygon.size());
      GlUtil.checkGlError();
    } catch (GlUtil.GlException e) {
      throw new FrameProcessingException(e, presentationTimeUs);
    }
  }

  @Override
  public void release() throws FrameProcessingException {
    super.release();
    if (glProgram != null) {
      try {
        glProgram.delete();
      } catch (GlUtil.GlException e) {
        throw new FrameProcessingException(e);
      }
    }
  }

  /**
   * Updates {@link #compositeTransformationMatrix} and {@link #visiblePolygon} based on the given
   * frame timestamp.
   */
  private void updateCompositeTransformationMatrixAndVisiblePolygon(long presentationTimeUs) {
    if (!updateTransformationMatrixCache(presentationTimeUs)) {
      return;
    }

    // Compute the compositeTransformationMatrix and transform and clip the visiblePolygon for each
    // MatrixTransformation's matrix.
    Matrix.setIdentityM(compositeTransformationMatrix, /* smOffset= */ 0);
    visiblePolygon = NDC_SQUARE;
    for (float[] transformationMatrix : transformationMatrixCache) {
      Matrix.multiplyMM(
          /* result= */ tempResultMatrix,
          /* resultOffset= */ 0,
          /* lhs= */ transformationMatrix,
          /* lhsOffset= */ 0,
          /* rhs= */ compositeTransformationMatrix,
          /* rhsOffset= */ 0);
      System.arraycopy(
          /* src= */ tempResultMatrix,
          /* srcPos= */ 0,
          /* dest= */ compositeTransformationMatrix,
          /* destPost= */ 0,
          /* length= */ tempResultMatrix.length);
      visiblePolygon =
          MatrixUtils.clipConvexPolygonToNdcRange(
              MatrixUtils.transformPoints(transformationMatrix, visiblePolygon));
      if (visiblePolygon.size() < 3) {
        // Can ignore remaining matrices as there are not enough vertices left to form a polygon.
        return;
      }
    }
    // Calculate the input frame vertices corresponding to the output frame's visible polygon.
    Matrix.invertM(
        tempResultMatrix, /* mInvOffset= */ 0, compositeTransformationMatrix, /* mOffset= */ 0);
    visiblePolygon = MatrixUtils.transformPoints(tempResultMatrix, visiblePolygon);
  }

  /**
   * Updates {@link #transformationMatrixCache} with the transformation matrices provided by the
   * {@link #matrixTransformations} for the given frame timestamp and returns whether any matrix in
   * {@link #transformationMatrixCache} changed.
   */
  private boolean updateTransformationMatrixCache(long presentationTimeUs) {
    boolean matrixChanged = false;
    for (int i = 0; i < matrixTransformations.size(); i++) {
      float[] cachedMatrix = transformationMatrixCache[i];
      float[] matrix = matrixTransformations.get(i).getGlMatrixArray(presentationTimeUs);
      if (!Arrays.equals(cachedMatrix, matrix)) {
        checkState(matrix.length == 16, "A 4x4 transformation matrix must have 16 elements");
        System.arraycopy(
            /* src= */ matrix,
            /* srcPos= */ 0,
            /* dest= */ cachedMatrix,
            /* destPost= */ 0,
            /* length= */ matrix.length);
        matrixChanged = true;
      }
    }
    return matrixChanged;
  }
}