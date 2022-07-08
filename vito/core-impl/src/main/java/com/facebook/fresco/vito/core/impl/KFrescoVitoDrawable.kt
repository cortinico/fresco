/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.facebook.common.closeables.AutoCleanupDelegate
import com.facebook.datasource.DataSource
import com.facebook.drawee.drawable.VisibilityCallback
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.VitoImagePerfListener
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.renderer.DrawableImageDataModel
import java.io.Closeable
import java.io.IOException

class KFrescoVitoDrawable(val _imagePerfListener: VitoImagePerfListener = NopImagePerfListener()) :
    Drawable(), FrescoDrawableInterface {

  var _imageId: Long = 0
  var _isLoading: Boolean = false
  override var callerContext: Any? = null
  var _visibilityCallback: VisibilityCallback? = null
  var _fetchSubmitted: Boolean = false
  val listenerManager: CombinedImageListenerImpl = CombinedImageListenerImpl()
  override var extras: Any? = null
  var viewportDimensions: Rect? = null
  var dataSource: DataSource<out Any>? by DataSourceCleanupDelegate()

  val releaseState = ImageReleaseScheduler.createReleaseState(this)
  private var hasBoundsSet = false

  override var imageRequest: VitoImageRequest? = null

  private val closeableCleanupFunction: (Closeable) -> Unit = {
    ImageReleaseScheduler.cancelAllReleasing(this)
    try {
      it.close()
    } catch (e: IOException) {
      // swallow
    }
  }

  var closeable: Closeable? by AutoCleanupDelegate(null, closeableCleanupFunction)

  override var refetchRunnable: Runnable? = null

  override val imageId: Long
    get() = _imageId

  override val imagePerfListener: VitoImagePerfListener
    get() = _imagePerfListener

  override fun setMutateDrawables(mutateDrawables: Boolean) {
    // No-op since we never mutate Drawables
  }

  override val actualImageDrawable: Drawable?
    get() {
      return when (val model = actualImageLayer.getDataModel()) {
        is DrawableImageDataModel -> model.drawable
        else -> null
      }
    }

  override fun hasImage(): Boolean = actualImageLayer.getDataModel() != null

  fun setFetchSubmitted(fetchSubmitted: Boolean) {
    _fetchSubmitted = fetchSubmitted
  }

  override val isFetchSubmitted: Boolean
    get() = _fetchSubmitted

  override fun setVisibilityCallback(visibilityCallback: VisibilityCallback?) {
    _visibilityCallback = visibilityCallback
  }

  override var imageListener: ImageListener?
    get() = listenerManager.imageListener
    set(value) {
      listenerManager.imageListener = value
    }

  override fun setOverlayDrawable(drawable: Drawable?): Drawable? {
    overlayImageLayer.apply {
      configure(
          dataModel = if (drawable == null) null else DrawableImageDataModel(drawable),
          roundingOptions = null,
          borderOptions = null)
    }
    return drawable
  }

  override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
    _visibilityCallback?.onVisibilityChange(visible)
    return super.setVisible(visible, restart)
  }

  fun reset() {
    imageRequest?.let { listenerManager.onRelease(imageId, it, obtainExtras()) }
    imagePerfListener.onImageRelease(this)
    ImageReleaseScheduler.cancelAllReleasing(this)
    _imageId = 0
    closeable = null
    dataSource = null
    imageRequest = null
    _isLoading = false
    callerContext = null

    placeholderLayer.reset()
    actualImageLayer.reset()
    progressLayer?.reset()
    overlayImageLayer.reset()
    debugOverlayImageLayer?.reset()
    hasBoundsSet = false

    listenerManager.onReset()
    listenerManager.imageListener = null
  }

  private var drawableAlpha: Int = 255
  private var drawableColorFilter: ColorFilter? = null

  val callbackProvider: (() -> Callback?) = { callback }

  val placeholderLayer = ImageLayerDataModel(callbackProvider)
  val actualImageLayer = ImageLayerDataModel(callbackProvider)
  var progressLayer: ImageLayerDataModel? = null
  val overlayImageLayer = ImageLayerDataModel(callbackProvider)
  var debugOverlayImageLayer: ImageLayerDataModel? = null

  override fun draw(canvas: Canvas) {
    if (!hasBoundsSet) {
      setLayerBounds(bounds)
    }
    placeholderLayer.draw(canvas)
    actualImageLayer.draw(canvas)
    progressLayer?.draw(canvas)
    overlayImageLayer.draw(canvas)
    debugOverlayImageLayer?.draw(canvas)
  }

  override fun onBoundsChange(bounds: Rect) {
    super.onBoundsChange(bounds)
    setLayerBounds(bounds)
  }

  private fun setLayerBounds(bounds: Rect?) {
    if (bounds != null) {
      placeholderLayer.configure(bounds = bounds)
      actualImageLayer.configure(bounds = bounds)
      progressLayer?.configure(bounds = bounds)
      overlayImageLayer.configure(bounds = bounds)
      debugOverlayImageLayer?.configure(bounds = bounds)
      hasBoundsSet = true
    }
  }

  override fun setAlpha(alpha: Int) {
    drawableAlpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    drawableColorFilter = colorFilter
  }

  // TODO(T105148151) Calculate opacity based on layers
  override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}
