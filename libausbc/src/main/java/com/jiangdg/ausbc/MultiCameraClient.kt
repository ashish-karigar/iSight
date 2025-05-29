package com.jiangdg.ausbc

import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.*
import android.view.Surface
import com.jiangdg.ausbc.callback.*
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.encode.AACEncodeProcessor
import com.jiangdg.ausbc.encode.AbstractProcessor
import com.jiangdg.ausbc.encode.H264EncodeProcessor
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.AudioStrategyUAC
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.ausbc.encode.muxer.Mp4Muxer
import com.jiangdg.ausbc.render.RenderManager
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.CameraUtils.isFilterDevice
import com.jiangdg.ausbc.utils.CameraUtils.isUsbCamera
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.OpenGLUtils
import com.jiangdg.ausbc.utils.SettableFuture
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.*
import com.jiangdg.usb.DeviceFilter
import com.jiangdg.uvc.UVCCamera
import java.io.File // <<<< ADDED IMPORT >>>>
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Multi-road camera client
 *
 * @author Created by jiangdg on 2022/7/18
 * Modified for v3.3.0 by jiangdg on 2023/1/15
 */
class MultiCameraClient(ctx: Context, callback: IDeviceConnectCallBack?) {
    private var mUsbMonitor: USBMonitor? = null
    private val mMainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    init {
        mUsbMonitor = USBMonitor(ctx, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "attach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onAttachDev(device)
                }
            }

            override fun onDetach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "detach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDetachDec(device)
                }
            }

            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "connect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onConnectDev(device, ctrlBlock)
                }
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "disconnect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDisConnectDec(device, ctrlBlock)
                }
            }

            override fun onCancel(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "cancel device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onCancelDev(device)
                }
            }
        })
    }

    fun register() {
        if (isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "register...")
        }
        mUsbMonitor?.register()
    }

    fun unRegister() {
        if (!isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "unRegister...")
        }
        mUsbMonitor?.unregister()
    }

    fun requestPermission(device: UsbDevice?): Boolean {
        if (!isMonitorRegistered()) {
            Logger.w(TAG, "Usb monitor haven't been registered.")
            return false
        }
        mUsbMonitor?.requestPermission(device)
        return true
    }

    fun hasPermission(device: UsbDevice?) = mUsbMonitor?.hasPermission(device)

    fun getDeviceList(list: List<DeviceFilter>? = null): MutableList<UsbDevice>? {
        list?.let {
            addDeviceFilters(it)
        }
        return mUsbMonitor?.deviceList
    }

    fun addDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.addDeviceFilter(list)
    }

    fun removeDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.removeDeviceFilter(list)
    }

    fun destroy() {
        mUsbMonitor?.destroy()
    }

    fun openDebug(debug: Boolean) {
        Utils.debugCamera = debug
        USBMonitor.DEBUG = debug
        UVCCamera.DEBUG = debug
    }

    private fun isMonitorRegistered() = mUsbMonitor?.isRegistered == true

    abstract class ICamera(val ctx: Context, val device: UsbDevice): Handler.Callback,
        H264EncodeProcessor.OnEncodeReadyListener {
        private var isCaptureStream: Boolean = false
        private var mMediaMuxer: Mp4Muxer? = null
        private var mEncodeDataCallBack: IEncodeDataCallBack? = null
        private var mCameraThread: HandlerThread? = null
        private var mAudioProcess: AbstractProcessor? = null
        private var mVideoProcess: AbstractProcessor? = null
        private var mRenderManager: RenderManager?  = null
        private var mCameraView: Any? = null
        private var mCameraStateCallback: ICameraStateCallBack? = null
        private var mSizeChangedFuture: SettableFuture<Pair<Int, Int>>? = null
        protected var mContext = ctx
        protected var mCameraRequest: CameraRequest? = null
        protected var mCameraHandler: Handler? = null
        protected var isPreviewed: Boolean = false
        protected var isNeedGLESRender: Boolean = false
        protected var mCtrlBlock: USBMonitor.UsbControlBlock? = null
        protected var mPreviewDataCbList = CopyOnWriteArrayList<IPreviewDataCallBack>()
        private val mCacheEffectList by lazy {
            arrayListOf<AbstractEffect>()
        }
        protected val mMainHandler: Handler by lazy {
            Handler(Looper.getMainLooper())
        }
        protected val mNV21DataQueue: LinkedBlockingDeque<ByteArray> by lazy {
            LinkedBlockingDeque(MAX_NV21_DATA)
        }
        protected val mSaveImageExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(10)
        }
        protected val mDateFormat by lazy {
            SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
        }
        protected val mCameraDir by lazy {
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
        }

        private var mCurrentRecordPath: String? = null // <<<< ADDED TO STORE CURRENT RECORDING PATH >>>>

        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MSG_START_PREVIEW -> {
                    val previewWidth = mCameraRequest!!.previewWidth
                    val previewHeight = mCameraRequest!!.previewHeight
                    val renderMode = mCameraRequest!!.renderMode
                    val isRawPreviewData = mCameraRequest!!.isRawPreviewData
                    when (val cameraView = mCameraView) {
                        is IAspectRatio -> {
                            if (mCameraRequest!!.isAspectRatioShow) {
                                cameraView.setAspectRatio(previewWidth, previewHeight)
                            }
                            cameraView
                        }
                        else -> {
                            null
                        }
                    }.also { view->
                        isNeedGLESRender = isGLESRender(renderMode == CameraRequest.RenderMode.OPENGL)
                        if (! isNeedGLESRender && view != null) {
                            openCameraInternal(view)
                            return true
                        }
                        val measureSize = try {
                            mSizeChangedFuture = SettableFuture()
                            mSizeChangedFuture?.get(2000, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                        Logger.i(TAG, "surface measure size $measureSize")
                        mCameraRequest!!.renderMode = CameraRequest.RenderMode.OPENGL
                        val screenWidth = view?.getSurfaceWidth() ?: previewWidth
                        val screenHeight = view?.getSurfaceHeight() ?: previewHeight
                        val surface = view?.getSurface()
                        val previewCb = if (isRawPreviewData) {
                            null
                        } else {
                            mPreviewDataCbList
                        }
                        mRenderManager = RenderManager(ctx, previewWidth, previewHeight, previewCb)
                        mRenderManager?.startRenderScreen(screenWidth, screenHeight, surface, object : RenderManager.CameraSurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?) {
                                if (surfaceTexture == null) {
                                    closeCamera()
                                    postStateEvent(ICameraStateCallBack.State.ERROR, "create camera surface failed")
                                    return
                                }
                                openCameraInternal(surfaceTexture)
                            }
                        })
                        mRenderManager?.setRotateType(mCameraRequest!!.defaultRotateType)
                        if (mCacheEffectList.isNotEmpty()) {
                            mCacheEffectList.forEach { effect ->
                                mRenderManager?.addRenderEffect(effect)
                            }
                            return@also
                        }
                        mCameraRequest?.defaultEffect?.apply {
                            mRenderManager?.addRenderEffect(this)
                        }
                    }
                }
                MSG_STOP_PREVIEW -> {
                    try {
                        mSizeChangedFuture?.cancel(true)
                        mSizeChangedFuture = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    closeCameraInternal()
                    mRenderManager?.getCacheEffectList()?.apply {
                        mCacheEffectList.clear()
                        mCacheEffectList.addAll(this)
                    }
                    mRenderManager?.stopRenderScreen()
                    mRenderManager = null
                }
                MSG_CAPTURE_IMAGE -> {
                    (msg.obj as Pair<*, *>).apply {
                        val path = first as? String
                        val cb = second as ICaptureCallBack
                        if (isNeedGLESRender && !mCameraRequest!!.isCaptureRawImage) {
                            mRenderManager?.saveImage(cb, path)
                            return@apply
                        }
                        captureImageInternal(path, second as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_START -> {
                    (msg.obj as Triple<*, *, *>).apply {
                        captureVideoStartInternal(first as? String, second as Long, third as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_STOP -> {
                    captureVideoStopInternal()
                }
                MSG_CAPTURE_STREAM_START -> {
                    isCaptureStream = true
                    captureStreamStartInternal()
                }
                MSG_CAPTURE_STREAM_STOP -> {
                    isCaptureStream = false
                    if (isRecording()) {
                        return true
                    }
                    captureStreamStopInternal()
                }
            }
            return true
        }

        protected abstract fun <T> openCameraInternal(cameraView: T)
        protected abstract fun closeCameraInternal()
        protected abstract fun captureImageInternal(savePath: String?, callback: ICaptureCallBack)

        protected open fun getAudioStrategy(): IAudioStrategy? {
            return when(mCameraRequest?.audioSource) {
                CameraRequest.AudioSource.SOURCE_AUTO -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        AudioStrategyUAC(mCtrlBlock!!)
                    } else {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using system mic")
                        }
                        AudioStrategySystem()
                    }
                }
                CameraRequest.AudioSource.SOURCE_DEV_MIC -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        return AudioStrategyUAC(mCtrlBlock!!)
                    }
                    return null
                }
                CameraRequest.AudioSource.SOURCE_SYS_MIC -> {
                    if (Utils.debugCamera) {
                        Logger.i(TAG, "Audio record by using system mic")
                    }
                    AudioStrategySystem()
                }
                else -> {
                    null
                }
            }
        }

        private fun isGLESRender(isGlesRenderOpen: Boolean): Boolean =isGlesRenderOpen && OpenGLUtils.isGlEsSupported(ctx)

        protected fun initEncodeProcessor(previewWidth: Int, previewHeight: Int) {
            releaseEncodeProcessor()
            getAudioStrategy()?.let { audio->
                AACEncodeProcessor(audio)
            }?.also { processor ->
                mAudioProcess = processor
            }
            mContext.resources.configuration.orientation.let { orientation ->
                orientation == Configuration.ORIENTATION_PORTRAIT
            }.also { isPortrait ->
                mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, isPortrait)
            }
        }

        protected fun releaseEncodeProcessor() {
            try {
                // <<<< MODIFIED: Log before releasing muxer in this context as well >>>>
                mCurrentRecordPath?.let { path ->
                    val file = File(path)
                    Logger.d(TAG, "releaseEncodeProcessor: About to release/nullify muxer. Current path = $path, File exists = ${file.exists()}, Size = ${if(file.exists()) file.length() else "N/A"}")
                } ?: Logger.d(TAG, "releaseEncodeProcessor: About to release/nullify muxer. mCurrentRecordPath is null.")
                mMediaMuxer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
                Logger.e(TAG, "releaseEncodeProcessor: release muxer failed, err is ${e.localizedMessage}")
            }
            mVideoProcess?.stopEncode()
            mAudioProcess?.stopEncode()
            mVideoProcess = null
            mMediaMuxer = null
            mAudioProcess = null
            isCaptureStream = false
            mCurrentRecordPath = null // <<<< MODIFIED: Clear path when encoders are released >>>>
        }

        protected fun putVideoData(data: ByteArray) {
            mVideoProcess?.putRawData(RawData(data, data.size))
        }

        fun captureAudioStart(callBack: ICaptureCallBack, mp3Path: String?=null) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack.onError("Has no audio permission")
                return
            }
            if (! CameraUtils.hasStoragePermission(mContext)) {
                callBack.onError("Has no storage permission")
                return
            }
            val path = if (mp3Path.isNullOrEmpty()) {
                "${mContext.getExternalFilesDir(null)?.path}/${System.currentTimeMillis()}.mp3"
            } else {
                mp3Path
            }
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Start(path, callBack)
        }

        fun captureAudioStop() {
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Stop()
        }

        fun startPlayMic(callBack: IPlayCallBack?) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack?.onError("Has no audio permission")
                return
            }
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStart(callBack)
        }

        fun stopPlayMic() {
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStop()
        }

        fun setRotateType(type: RotateType?) {
            mRenderManager?.setRotateType(type)
        }

        fun setRenderSize(width: Int, height: Int) {
            mSizeChangedFuture?.set(Pair(width, height))
        }

        fun addRenderEffect(effect: AbstractEffect) {
            mRenderManager?.addRenderEffect(effect)
        }

        fun removeRenderEffect(effect: AbstractEffect) {
            val defaultId =  mCameraRequest?.defaultEffect?.getId()
            if (effect.getId() == defaultId) {
                mCameraRequest?.defaultEffect = null
            }
            mRenderManager?.removeRenderEffect(effect)
        }

        fun getDefaultEffect() = mCameraRequest?.defaultEffect

        fun updateRenderEffect(classifyId: Int, effect: AbstractEffect?) {
            mRenderManager?.getCacheEffectList()?.find {
                it.getClassifyId() == classifyId
            }?.also {
                removeRenderEffect(it)
            }
            effect ?: return
            addRenderEffect(effect)
        }

        protected fun postStateEvent(state: ICameraStateCallBack.State, msg: String? = null) {
            mMainHandler.post {
                mCameraStateCallback?.onCameraState(this, state, msg)
            }
        }

        fun setUsbControlBlock(ctrlBlock: USBMonitor.UsbControlBlock?) {
            this.mCtrlBlock = ctrlBlock
        }

        fun getUsbDevice() = device

        fun isMicSupported(device: UsbDevice?) = CameraUtils.isCameraContainsMic(device)

        abstract fun getAllPreviewSizes(aspectRatio: Double? = null): MutableList<PreviewSize>

        fun <T> openCamera(cameraView: T? = null, cameraRequest: CameraRequest? = null) {
            mCameraView = cameraView ?: mCameraView
            mCameraRequest = cameraRequest ?: getDefaultCameraRequest()
            HandlerThread("camera-${System.currentTimeMillis()}").apply {
                start()
            }.let { thread ->
                this.mCameraThread = thread
                thread
            }.also {
                mCameraHandler = Handler(it.looper, this)
                mCameraHandler?.obtainMessage(MSG_START_PREVIEW)?.sendToTarget()
            }
        }

        fun closeCamera() {
            mCameraHandler?.obtainMessage(MSG_STOP_PREVIEW)?.sendToTarget()
            mCameraThread?.quitSafely()
            mCameraThread = null
            mCameraHandler = null
            mCurrentRecordPath = null // <<<< MODIFIED: Ensure path is cleared on camera close >>>>
        }

        fun isCameraOpened() = isPreviewed

        fun getCameraRequest() = mCameraRequest

        fun captureImage(callBack: ICaptureCallBack, path: String? = null) {
            Pair(path, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_IMAGE, this)?.sendToTarget()
            }
        }

        fun captureVideoStart(callBack: ICaptureCallBack, path: String? = null, durationInSec: Long = 0L) {
            Triple(path, durationInSec, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_START, this)?.sendToTarget()
            }
        }

        fun captureVideoStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_STOP)?.sendToTarget()
        }

        fun captureStreamStart() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_START)?.sendToTarget()
        }

        fun captureStreamStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_STOP)?.sendToTarget()
        }

        fun updateResolution(width: Int, height: Int) {
            if (mCameraRequest == null) {
                Logger.w(TAG, "updateResolution failed, please open camera first.")
                return
            }
            if (isStreaming() || isRecording()) {
                Logger.e(TAG, "updateResolution failed, video recording...")
                return
            }
            mCameraRequest?.apply {
                if (previewWidth == width && previewHeight == height) {
                    return@apply
                }
                Logger.i(TAG, "updateResolution: width = $width, height = $height")
                closeCamera()
                mMainHandler.postDelayed({
                    previewWidth = width
                    previewHeight = height
                    openCamera(mCameraView, mCameraRequest)
                }, 1000)
            }
        }

        fun setCameraStateCallBack(callback: ICameraStateCallBack?) {
            this.mCameraStateCallback = callback
        }

        fun setEncodeDataCallBack(callBack: IEncodeDataCallBack?) {
            this.mEncodeDataCallBack = callBack
        }

        fun addPreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.add(callBack)
        }

        fun removePreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (! mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.remove(callBack)
        }

        fun getSuitableSize(maxWidth: Int, maxHeight: Int): PreviewSize {
            val sizeList = getAllPreviewSizes()
            if (sizeList.isNullOrEmpty()) {
                return PreviewSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
            }
            sizeList.find {
                (it.width == maxWidth && it.height == maxHeight)
            }.also { size ->
                size ?: return@also
                return size
            }
            val aspectRatio = maxWidth.toFloat() / maxHeight
            sizeList.find {
                val w = it.width
                val h = it.height
                val ratio = w.toFloat() / h
                ratio == aspectRatio && w <= maxWidth && h <= maxHeight
            }.also { size ->
                size ?: return@also
                return size
            }
            var minDistance: Int = maxWidth
            var closetSize = sizeList[0]
            sizeList.forEach { size ->
                if (minDistance >= abs((maxWidth - size.width))) {
                    minDistance = abs(maxWidth - size.width)
                    closetSize = size
                }
            }
            sizeList.find {
                (it.width == DEFAULT_PREVIEW_WIDTH || it.height == DEFAULT_PREVIEW_HEIGHT)
            }.also { size ->
                size ?: return@also
                return size
            }
            return closetSize
        }

        fun isPreviewSizeSupported(previewSize: PreviewSize): Boolean {
            return getAllPreviewSizes().find {
                it.width == previewSize.width && it.height == previewSize.height
            } != null
        }

        fun isRecording(): Boolean = mMediaMuxer?.isMuxerStarter() == true

        fun isStreaming(): Boolean = isEncoding() && isCaptureStream

        private fun isEncoding(): Boolean = mVideoProcess?.isEncoding() == true

        private fun captureVideoStartInternal(path: String?, durationInSec: Long, callBack: ICaptureCallBack) {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture video failed, camera not opened")
                mCurrentRecordPath = null // <<<< MODIFIED: Clear path >>>>
                return
            }
            if (isRecording()) {
                Logger.w(TAG, "capturing video already running")
                // Not clearing mCurrentRecordPath here as a recording is already in progress with its own path
                return
            }
            mCurrentRecordPath = path // <<<< MODIFIED: Store the path >>>>
            Logger.i(TAG, "captureVideoStartInternal: Stored mCurrentRecordPath = $mCurrentRecordPath")

            captureStreamStartInternal()
            // <<<< MODIFIED: Log path being sent to Mp4Muxer >>>>
            Logger.d(TAG, "captureVideoStartInternal: Instantiating Mp4Muxer with path = $path, callBack = $callBack")
            if (path == null) {
                Logger.e(TAG, "captureVideoStartInternal: Path is NULL when creating Mp4Muxer. This will likely cause issues.")
            }
            Mp4Muxer(mContext, callBack, path, durationInSec, mAudioProcess==null).apply {
                mVideoProcess?.setMp4Muxer(this)
                mAudioProcess?.setMp4Muxer(this)
            }.also { muxer ->
                mMediaMuxer = muxer
            }
            Logger.i(TAG, "capturing video start (finished internal setup)")
        }

        private fun captureVideoStopInternal() {
            if (!isStreaming() && !isRecording()) { // <<<< MODIFIED: only stop stream if not streaming AND not recording (or just not streaming if stop is for stream only)
                // This logic might need refinement based on when captureStreamStopInternal is desired.
                // For now, if we are stopping a recording, we also stop the stream.
                Logger.i(TAG, "captureVideoStopInternal: Not streaming/recording or already stopped, calling captureStreamStopInternal.")
                captureStreamStopInternal()
            } else {
                Logger.i(TAG, "captureVideoStopInternal: Was streaming or recording.")
            }

            // <<<< MODIFIED: Diagnostic logging for file state BEFORE muxer release >>>>
            val pathToCheckBeforeRelease = mCurrentRecordPath
            if (pathToCheckBeforeRelease != null) {
                val file = File(pathToCheckBeforeRelease)
                Logger.d(TAG, "captureVideoStopInternal: BEFORE mMediaMuxer.release(). Path = $pathToCheckBeforeRelease, Exists = ${file.exists()}, Size = ${if(file.exists()) file.length() else "N/A"}")
            } else {
                Logger.w(TAG, "captureVideoStopInternal: BEFORE mMediaMuxer.release(). mCurrentRecordPath is NULL.")
            }

            try {
                Logger.i(TAG, "captureVideoStopInternal: Attempting mMediaMuxer.release()")
                mMediaMuxer?.release() // This is the call to Mp4Muxer.release()
                Logger.i(TAG, "captureVideoStopInternal: mMediaMuxer.release() completed (or threw exception handled within Mp4Muxer.release itself).")
            } catch (e: Exception) {
                // This catch block is for exceptions thrown by mMediaMuxer.release() that were NOT caught internally by Mp4Muxer's release.
                e.printStackTrace()
                Logger.e(TAG, "captureVideoStopInternal: Exception during mMediaMuxer.release(): ${e.localizedMessage}")
                // The callback to MainActivity (onError) should be handled by Mp4Muxer.release() if it catches the "Mutation" error.
            } finally {
                Logger.i(TAG, "captureVideoStopInternal: In finally block. Setting mMediaMuxer to null.")
                mMediaMuxer = null
            }

            // <<<< MODIFIED: Diagnostic logging for file state AFTER muxer release >>>>
            if (pathToCheckBeforeRelease != null) { // Use the same path variable for consistency in logging
                val file = File(pathToCheckBeforeRelease)
                Logger.d(TAG, "captureVideoStopInternal: AFTER mMediaMuxer.release() attempt. Path = $pathToCheckBeforeRelease, Exists = ${file.exists()}, Size = ${if(file.exists()) file.length() else "N/A"}")
            } else {
                Logger.w(TAG, "captureVideoStopInternal: AFTER mMediaMuxer.release() attempt. mCurrentRecordPath was NULL.")
            }
            // mCurrentRecordPath is typically cleared in the ICaptureCallBack (onComplete/onError) in MainActivity
            // or when a new recording starts, or camera closes.
            // For safety, if we know the stop operation is fully done from this class's perspective:
            // mCurrentRecordPath = null; // Consider if this is the right place or if MainActivity's callbacks are sufficient.
            // The logs suggest MainActivity's onError is called, which now handles mCurrentRecordPath.

            Logger.i(TAG, "capture video stop internal finished")
        }

        private fun captureStreamStartInternal() {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture stream failed, camera not opened")
                return
            }
            if (isEncoding()) {
                Logger.w(TAG, "capturing stream canceled, already running")
                return
            }
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                if (mVideoProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
                setOnEncodeReadyListener(this@ICamera)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                if (mAudioProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
            }
            Logger.i(TAG, "capturing stream start")
        }

        private fun captureStreamStopInternal() {
            mRenderManager?.stopRenderCodec()
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                if (! isEncoding()) {
                    return@apply
                }
                stopEncode()
                setEncodeDataCallBack(null)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                if (! isEncoding()) {
                    return@apply
                }
                stopEncode()
                setEncodeDataCallBack(null)
            }
            Logger.i(TAG, "capturing stream stop")
        }

        override fun onReady(surface: Surface?) {
            if (surface == null) {
                Logger.e(TAG, "start encode failed, input surface is null")
                return
            }
            mCameraRequest?.apply {
                mRenderManager?.startRenderCodec(surface, previewWidth, previewHeight)
            }
        }

        private fun getDefaultCameraRequest(): CameraRequest {
            return CameraRequest.Builder()
                .setPreviewWidth(1280) // Default in library, MainActivity uses 320x240
                .setPreviewHeight(720)
                .create()
        }
    }

    companion object {
        private const val TAG = "MultiCameraClient" // Consider changing to "MultiCameraClientLog" for this file's specific logs
        private const val MSG_START_PREVIEW = 0x01
        private const val MSG_STOP_PREVIEW = 0x02
        private const val MSG_CAPTURE_IMAGE = 0x03
        private const val MSG_CAPTURE_VIDEO_START = 0x04
        private const val MSG_CAPTURE_VIDEO_STOP = 0x05
        private const val MSG_CAPTURE_STREAM_START = 0x06
        private const val MSG_CAPTURE_STREAM_STOP = 0x07
        private const val DEFAULT_PREVIEW_WIDTH = 640
        private const val DEFAULT_PREVIEW_HEIGHT = 480
        const val MAX_NV21_DATA = 5
        const val CAPTURE_TIMES_OUT_SEC = 3L
    }
}