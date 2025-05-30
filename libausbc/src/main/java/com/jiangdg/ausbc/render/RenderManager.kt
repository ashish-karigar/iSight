/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14 // <<<< --- ADDED IMPORT --- >>>>
import android.opengl.EGLContext
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.internal.*
import com.jiangdg.ausbc.utils.*
import com.jiangdg.ausbc.utils.bus.BusKey
import com.jiangdg.ausbc.utils.bus.EventBus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RenderManager(
    context: Context,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val mPreviewDataCbList: CopyOnWriteArrayList<IPreviewDataCallBack>?=null
) : SurfaceTexture.OnFrameAvailableListener, Handler.Callback {
    private var mPreviewByteBuffer: ByteBuffer? = null
    private var mEOSTextureId: Int? = null
    private var mRenderThread: HandlerThread? = null
    private var mRenderHandler: Handler? = null
    private var mRenderCodecThread: HandlerThread? = null
    private var mRenderCodecHandler: Handler? = null
    private var mCameraRender: CameraRender? = null
    private var mScreenRender: ScreenRender? = null
    private var mEncodeRender: EncodeRender? = null
    private var mCaptureRender: CaptureRender? = null
    private var mCameraSurfaceTexture: SurfaceTexture? = null
    private var mTransformMatrix: FloatArray = FloatArray(16)
    private var mWidth: Int = 0
    private var mHeight: Int = 0
    private var mFBOBufferId: Int = 0
    private var mContext: Context = context
    private var mEffectList = arrayListOf<AbstractEffect>()
    private var mCacheEffectList = arrayListOf<AbstractEffect>()
    private var mCaptureDataCb: ICaptureCallBack? = null
    private var mFrameRate = 0
    private var mEndTime: Long = 0L
    private var mStartTime = System.currentTimeMillis()
    private val mStFuture by lazy {
        SettableFuture<SurfaceTexture>()
    }
    private val mMainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }
    private val mCaptureState: AtomicBoolean by lazy {
        AtomicBoolean(false)
    }
    private val mDateFormat by lazy {
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
    }
    private val mCameraDir by lazy {
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
    }

    init {
        this.mCameraRender = CameraRender(context)
        this.mScreenRender = ScreenRender(context)
        this.mCaptureRender = CaptureRender(context)
        Logger.i(TAG, "create RenderManager, Open ES version is ${Utils.getGLESVersion(context)}")
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_GL_INIT -> {
                (msg.obj as Triple<*, *, *>).apply {
                    val w = first as Int
                    val h = second as Int
                    val surface = third as? Surface
                    mScreenRender?.initEGLEvn()
                    mScreenRender?.setupSurface(surface, w, h)
                    mScreenRender?.initGLES()
                    mCameraRender?.initGLES()
                    mCaptureRender?.initGLES()
                    mEOSTextureId = mCameraRender?.getCameraTextureId()?.apply {
                        mStFuture.set(SurfaceTexture(this))
                    }
                    EventBus.with<Boolean>(BusKey.KEY_RENDER_READY).postMessage(true)
                }
            }
            MSG_GL_CHANGED_SIZE -> {
                (msg.obj as Pair<*, *>).apply {
                    mWidth = first as Int
                    mHeight = second as Int
                    mCameraRender?.setSize(mWidth, mHeight)
                    mScreenRender?.setSize(mWidth, mHeight)
                    mCaptureRender?.setSize(mWidth, mHeight)
                    mCameraSurfaceTexture?.setDefaultBufferSize(mWidth, mHeight)
                }
            }
            MSG_GL_SAVE_IMAGE -> {
                saveImageInternal(msg.obj as? String)
            }
            MSG_GL_START_RENDER_CODEC -> {
                (msg.obj as Triple<*, *, *>).apply {
                    val surface = first as Surface
                    val width = second as Int
                    val height = third as Int
                    startRenderCodecInternal(surface, width, height)
                }
            }
            MSG_GL_STOP_RENDER_CODEC -> {
                stopRenderCodecInternal()
            }
            MSG_GL_ROUTE_ANGLE -> {
                (msg.obj as? RotateType)?.apply {
                    mCameraRender?.setRotateAngle(this)
                }
            }
            MSG_GL_DRAW -> {
                mCameraSurfaceTexture?.updateTexImage()
                mCameraSurfaceTexture?.getTransformMatrix(mTransformMatrix)
                mCameraRender?.setTransformMatrix(mTransformMatrix)
                val textureId = mEOSTextureId?.let { mCameraRender?.drawFrame(it) }
                textureId?.let { fboId ->
                    var effectId = fboId
                    mEffectList.forEach { effectRender ->
                        effectId = effectRender.drawFrame(effectId)
                    }
                    effectId
                }?.also { id ->
                    mScreenRender?.drawFrame(id)
                    drawFrame2Capture(id)
                    drawFrame2Codec(id, mCameraSurfaceTexture?.timestamp ?: 0)
                }
                mScreenRender?.swapBuffers(mCameraSurfaceTexture?.timestamp ?: 0)
            }
            MSG_GL_ADD_EFFECT -> {
                (msg.obj as? AbstractEffect)?.let { effect->
                    if (mEffectList.contains(effect)) {
                        return@let
                    }
                    effect.initGLES()
                    effect.setSize(mWidth, mHeight)
                    mEffectList.add(effect)
                    mCacheEffectList.add(effect)
                    Logger.i(TAG, "add effect, name = ${effect.javaClass.simpleName}, size = ${mEffectList.size}")
                }
            }
            MSG_GL_REMOVE_EFFECT -> {
                (msg.obj as? AbstractEffect)?.let {
                    if (! mEffectList.contains(it)) {
                        return@let
                    }
                    it.releaseGLES()
                    mEffectList.remove(it)
                    mCacheEffectList.remove(it)
                    Logger.i(TAG, "remove effect, name = ${it.javaClass.simpleName}, size = ${mEffectList.size}")
                }
            }
            MSG_GL_RELEASE -> {
                EventBus.with<Boolean>(BusKey.KEY_RENDER_READY).postMessage(false)
                mEffectList.forEach { effect ->
                    effect.releaseGLES()
                }
                mEffectList.clear()
                mCameraRender?.releaseGLES()
                mScreenRender?.releaseGLES()
                mCaptureRender?.releaseGLES()
                mCameraSurfaceTexture?.setOnFrameAvailableListener(null)
                mCameraSurfaceTexture?.release()
                mCameraSurfaceTexture = null
            }
        }
        return true
    }

    private fun drawFrame2Capture(fboId: Int) {
        mCaptureRender?.drawFrame(fboId)?.let {
            mCaptureRender!!.getFrameBufferId()
        }?.also { id ->
            mFBOBufferId = id
            val renderWidth = mCaptureRender?.getRenderWidth() ?: mWidth
            val renderHeight = mCaptureRender?.getRenderHeight() ?: mHeight
            val rgbaLen = renderWidth * renderHeight * 4
            mPreviewDataCbList?.forEach { callback ->
                if (mPreviewByteBuffer==null || mPreviewByteBuffer?.remaining() != rgbaLen) {
                    mPreviewByteBuffer = ByteBuffer.allocateDirect(rgbaLen)
                    mPreviewByteBuffer?.order(ByteOrder.LITTLE_ENDIAN)
                }
                mPreviewByteBuffer?.let {
                    it.clear()
                    GLBitmapUtils.readPixelToByteBuffer(id,renderWidth, renderHeight, mPreviewByteBuffer)
                    callback.onPreviewData(it.array(),renderWidth, renderHeight, IPreviewDataCallBack.DataFormat.RGBA)
                }
            }
        }
    }

    fun startRenderScreen(w: Int, h: Int, outSurface: Surface?, listener: CameraSurfaceTextureListener? = null) {
        mRenderThread = HandlerThread(RENDER_THREAD)
        mRenderThread?.start()
        mRenderHandler = Handler(mRenderThread!!.looper, this@RenderManager)
        Triple(w, h, outSurface).apply {
            mRenderHandler?.obtainMessage(MSG_GL_INIT, this)?.sendToTarget()
        }
        try {
            mStFuture.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Logger.e(TAG, "wait for creating camera SurfaceTexture failed", e)
            listener?.onSurfaceTextureAvailable(null)
            return
        }?.apply {
            setDefaultBufferSize(w, h)
            setOnFrameAvailableListener(this@RenderManager)
            mCameraSurfaceTexture = this
        }.also {
            listener?.onSurfaceTextureAvailable(it)
            Logger.i(TAG, "create camera SurfaceTexture: $it")
        }
        setRenderSize(w, h)
    }

    fun stopRenderScreen() {
        mRenderHandler?.obtainMessage(MSG_GL_RELEASE)?.sendToTarget()
        try {
            mRenderThread?.quitSafely()
            mRenderThread?.join(500)
            if (mRenderThread?.isAlive == true) {
                Logger.w(TAG, "RenderThread did not finish in time.")
            }
        } catch (e: InterruptedException) {
            Logger.e(TAG, "Interrupted while waiting for RenderThread to finish.", e)
        }
        mRenderThread = null
        mRenderHandler = null
    }

    fun startRenderCodec(inputSurface: Surface, width: Int, height: Int) {
        Triple(inputSurface, width, height).apply {
            mRenderHandler?.obtainMessage(MSG_GL_START_RENDER_CODEC, this)?.sendToTarget()
        }
    }

    fun stopRenderCodec() {
        mRenderHandler?.obtainMessage(MSG_GL_STOP_RENDER_CODEC)?.sendToTarget()
    }

    fun setRenderSize(w: Int, h: Int) {
        mRenderHandler?.obtainMessage(MSG_GL_CHANGED_SIZE, Pair(w, h))?.sendToTarget()
    }

    fun addRenderEffect(effect: AbstractEffect?) {
        mRenderHandler?.obtainMessage(MSG_GL_ADD_EFFECT, effect)?.sendToTarget()
    }

    fun removeRenderEffect(effect: AbstractEffect?) {
        mRenderHandler?.obtainMessage(MSG_GL_REMOVE_EFFECT, effect)?.sendToTarget()
    }

    fun setRotateType(type: RotateType?) {
        mRenderHandler?.obtainMessage(MSG_GL_ROUTE_ANGLE, type)?.sendToTarget()
    }

    fun getCacheEffectList() = mCacheEffectList

    fun saveImage(callBack: ICaptureCallBack?, path: String?) {
        this.mCaptureDataCb = callBack
        mRenderHandler?.obtainMessage(MSG_GL_SAVE_IMAGE, path)?.sendToTarget()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        emitFrameRate()
        mRenderHandler?.obtainMessage(MSG_GL_DRAW)?.sendToTarget()
    }

    private fun startRenderCodecInternal(surface: Surface, w: Int, h: Int) {
        stopRenderCodecInternal()
        mRenderCodecThread = HandlerThread(RENDER_CODEC_THREAD)
        mRenderCodecThread?.start()
        mRenderCodecHandler = Handler(mRenderCodecThread!!.looper) { message ->
            when (message.what) {
                MSG_GL_RENDER_CODEC_INIT -> {
                    (message.obj as Pair<*, *>).apply {
                        val shareContext = first as EGLContext
                        val inputSurface = second as Surface
                        mEncodeRender = EncodeRender(mContext)
                        mEncodeRender?.initEGLEvn(shareContext)
                        mEncodeRender?.setupSurface(inputSurface)
                        mEncodeRender?.initGLES()
                    }
                }
                MSG_GL_RENDER_CODEC_CHANGED_SIZE -> {
                    (message.obj as Pair<*, *>).apply {
                        val width = first as Int
                        val height = second as Int
                        mEncodeRender?.setSize(width, height)
                    }
                }
                MSG_GL_RENDER_CODEC_DRAW -> {
                    (message.obj as Pair<*, *>).apply {
                        val textureId = first as Int
                        val timeStamps = second as Long
                        mEncodeRender?.drawFrame(textureId)
                        mEncodeRender?.swapBuffers(timeStamps)
                    }
                }
                MSG_GL_RENDER_CODEC_RELEASE -> {
                    mEncodeRender?.releaseGLES()
                    mEncodeRender = null
                }
            }
            true
        }
        mScreenRender?.getCurrentContext()?.let {
            if (it == EGL14.EGL_NO_CONTEXT) {
                Logger.e(TAG, "startRenderCodecInternal: Current EGLContext is EGL_NO_CONTEXT. Cannot share.")
                stopRenderCodecInternal()
                return@let
            }
            mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_INIT, Pair(it, surface))?.sendToTarget()
            mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_CHANGED_SIZE, Pair(w, h))?.sendToTarget()
        } ?: run {
            Logger.e(TAG, "startRenderCodecInternal: Current EGLContext is null. Cannot initialize codec renderer.")
        }
    }

    private fun drawFrame2Codec(textureId: Int, timeStamps: Long) {
        Pair(textureId, timeStamps).apply {
            mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_DRAW, this)?.sendToTarget()
        }
    }

    private fun stopRenderCodecInternal() {
        mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_RELEASE)?.sendToTarget()
        try {
            mRenderCodecThread?.quitSafely()
            mRenderCodecThread?.join(500)
            if (mRenderCodecThread?.isAlive == true) {
                Logger.w(TAG, "RenderCodecThread did not finish in time.")
            }
        } catch (e: InterruptedException) {
            Logger.e(TAG, "Interrupted while waiting for RenderCodecThread to finish.", e)
        }
        mRenderCodecThread = null
        mRenderCodecHandler = null
    }

    private fun saveImageInternal(savePathFromUser: String?) {
        if (mCaptureState.getAndSet(true)) {
            Logger.w(TAG, "saveImageInternal: Capture already in progress.")
            return
        }
        mMainHandler.post {
            mCaptureDataCb?.onBegin()
        }

        val actualSavePath: String
        val title: String
        val displayName: String

        if (savePathFromUser.isNullOrEmpty()) {
            val date = mDateFormat.format(System.currentTimeMillis())
            title = "IMG_AUSBC_$date"
            displayName = "$title.jpg"
            actualSavePath = "$mCameraDir/$displayName"
            Logger.i(TAG, "saveImageInternal: No path provided, generated default: $actualSavePath")
        } else {
            actualSavePath = savePathFromUser
            val tempFile = File(actualSavePath)
            displayName = tempFile.name
            title = tempFile.nameWithoutExtension
            Logger.i(TAG, "saveImageInternal: User provided path: $actualSavePath")
        }

        val dir = File(actualSavePath).parentFile
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Logger.e(TAG, "saveImageInternal: Failed to create directory: ${dir.absolutePath}")
                mMainHandler.post {
                    mCaptureDataCb?.onError("Failed to create image directory.")
                }
                mCaptureState.set(false)
                return
            }
        }

        val captureWidth = mWidth
        val captureHeight = mHeight
        var fos: FileOutputStream? = null
        var imagePhysicallySaved = false

        try {
            Logger.d(TAG, "saveImageInternal: Writing image to: $actualSavePath")
            fos = FileOutputStream(actualSavePath)
            GLBitmapUtils.transFrameBufferToBitmap(mFBOBufferId, captureWidth, captureHeight).apply {
                compress(Bitmap.CompressFormat.JPEG, 100, fos)
                recycle()
            }
            fos.flush()
            imagePhysicallySaved = true
            Logger.i(TAG, "saveImageInternal: Image physically saved to $actualSavePath")
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Failed to write image to file"
            Logger.e(TAG, "saveImageInternal: Failed to write image to $actualSavePath, err = $errorMsg", e)
            mMainHandler.post {
                mCaptureDataCb?.onError(errorMsg)
            }
        } finally {
            try {
                fos?.close()
            } catch (e: IOException) {
                Logger.e(TAG, "saveImageInternal: Failed to close FileOutputStream for $actualSavePath, err = ${e.localizedMessage}", e)
            }
        }

        if (!imagePhysicallySaved) {
            Logger.e(TAG, "saveImageInternal: Physical image save FAILED for $actualSavePath. Not proceeding further.")
            File(actualSavePath).delete()
            mCaptureState.set(false)
            return
        }

        val file = File(actualSavePath)
        if (!file.exists() || file.length() == 0L) {
            val errorDetail = if (!file.exists()) "File not found." else "File is empty."
            Logger.e(TAG, "saveImageInternal: Image file check failed after saving: $errorDetail Path: $actualSavePath")
            if (file.exists()) file.delete()
            mMainHandler.post {
                mCaptureDataCb?.onError("Image save resulted in missing or empty file.")
            }
            mCaptureState.set(false)
            return
        }

        var mediaStoreError: String? = null
        if (mContext != null) {
            try {
                Logger.d(TAG, "saveImageInternal: Attempting MediaStore insert for $actualSavePath")
                val values = ContentValues().apply {
                    put(MediaStore.Images.ImageColumns.TITLE, title)
                    put(MediaStore.Images.ImageColumns.DISPLAY_NAME, displayName) // Use resolved displayName
                    put(MediaStore.Images.ImageColumns.DATA, actualSavePath)
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.Images.ImageColumns.WIDTH, captureWidth)
                    put(MediaStore.Images.ImageColumns.HEIGHT, captureHeight)
                    put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/jpeg")
                    if (MediaUtils.isAboveQ()) {
                        // Use the parent directory name of actualSavePath for RELATIVE_PATH if it's meaningful
                        val parentDirName = file.parentFile?.name ?: "UVCCamera"
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + parentDirName)
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                }
                mContext!!.contentResolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                Logger.i(TAG, "saveImageInternal: MediaStore insert attempt finished for $actualSavePath")
            } catch (e: Exception) {
                mediaStoreError = e.localizedMessage ?: "MediaStore.insert failed"
                Logger.e(TAG, "saveImageInternal: MediaStore insert failed for $actualSavePath. Error: $mediaStoreError", e)
            }
        } else {
            Logger.w(TAG, "saveImageInternal: mContext is null, skipping MediaStore insert.")
            mediaStoreError = "Context was null"
        }

        mMainHandler.post {
            Logger.i(TAG, "saveImageInternal: Calling onComplete for physically saved image: $actualSavePath. MediaStore status: ${mediaStoreError ?: "Not attempted or success"}")
            mCaptureDataCb?.onComplete(actualSavePath)
        }
        mCaptureState.set(false)
        if (Utils.debugCamera) {
            Logger.i(TAG, "captureImageInternal finished for path = $actualSavePath")
        }
    }

    private fun emitFrameRate() {
        mFrameRate++
        mEndTime = System.currentTimeMillis()
        if (mEndTime - mStartTime >= 1000) {
            if (Utils.debugCamera) {
                Logger.i(TAG, "camera render frame rate is $mFrameRate fps-->${Thread.currentThread().name}")
            }
            EventBus.with<Int>(BusKey.KEY_FRAME_RATE).postMessage(mFrameRate)
            mStartTime = mEndTime
            mFrameRate = 0
        }
    }

    interface CameraSurfaceTextureListener {
        fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?)
    }

    companion object {
        private const val TAG = "RenderManager"
        private const val RENDER_THREAD = "gl_render"
        private const val RENDER_CODEC_THREAD = "gl_render_codec"
        private const val MSG_GL_INIT = 0x00
        private const val MSG_GL_DRAW = 0x01
        private const val MSG_GL_RELEASE = 0x02
        private const val MSG_GL_START_RENDER_CODEC = 0x03
        private const val MSG_GL_STOP_RENDER_CODEC = 0x04
        private const val MSG_GL_CHANGED_SIZE = 0x05
        private const val MSG_GL_ADD_EFFECT = 0x06
        private const val MSG_GL_REMOVE_EFFECT = 0x07
        private const val MSG_GL_SAVE_IMAGE = 0x08
        private const val MSG_GL_ROUTE_ANGLE = 0x09
        private const val MSG_GL_RENDER_CODEC_INIT = 0x11
        private const val MSG_GL_RENDER_CODEC_CHANGED_SIZE = 0x12
        private const val MSG_GL_RENDER_CODEC_DRAW = 0x13
        private const val MSG_GL_RENDER_CODEC_RELEASE = 0x14
    }
}