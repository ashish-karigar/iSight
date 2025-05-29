/*
 * Copyright 2017-2022 Jiangdg
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
package com.jiangdg.ausbc.encode.muxer

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaMuxer for Mp4
 *
 * @author Created by jiangdg on 2022/2/10
 */
class Mp4Muxer(
    context: Context?,
    callBack: ICaptureCallBack?,
    private var initialPath: String? = null,
    private val durationInSec: Long = 0,
    private val isVideoOnly: Boolean = false
) {
    private var mContext: Context? = null
    private var mMediaMuxer: MediaMuxer? = null
    private var mFileSubIndex: Int = 0
    @Volatile
    private var mVideoTrackerIndex = -1
    @Volatile
    private var mAudioTrackerIndex = -1
    private var mVideoFormat: MediaFormat? = null
    private var mAudioFormat: MediaFormat? = null
    private var mBeginMillis: Long = 0
    private var mCaptureCallBack: ICaptureCallBack? = null
    private var mMainHandler: Handler = Handler(Looper.getMainLooper())
    private var mOriginalPathUsedForSplitting: String? = null
    private var mCurrentSegmentPath: String? = null
    private var mVideoPts: Long = 0L // <<<< Ensure this line is present
    private var mAudioPts: Long = 0L // <<<< Ensure this line is present
    private val mDateFormat by lazy {
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
    }
    private val mCameraDir by lazy {
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
    }

    init {
        this.mCaptureCallBack = callBack
        this.mContext = context
        try {
            var resolvedPath = initialPath
            if (resolvedPath.isNullOrEmpty()) {
                val date = mDateFormat.format(System.currentTimeMillis())
                resolvedPath = "$mCameraDir/VID_AUSBC_$date.mp4"
                Logger.i(TAG, "Mp4Muxer init: Path was null or empty, generated default: $resolvedPath")
            } else {
                if (!resolvedPath.endsWith(".mp4", ignoreCase = true)) {
                    resolvedPath = "$resolvedPath.mp4"
                }
            }
            mOriginalPathUsedForSplitting = resolvedPath.removeSuffix(".mp4")
            mCurrentSegmentPath = resolvedPath
            Logger.i(TAG, "Mp4Muxer init: initialPath = $initialPath, final mCurrentSegmentPath = $mCurrentSegmentPath")
            mMediaMuxer = MediaMuxer(mCurrentSegmentPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Logger.i(TAG, "Mp4Muxer successfully initialized for path: $mCurrentSegmentPath")
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Mp4Muxer init failed"
            Logger.e(TAG, "Mp4Muxer init: FAILED for path $initialPath, err = $errorMsg", e)
            mMainHandler.post {
                mCaptureCallBack?.onError(errorMsg)
            }
        }
    }

    @Synchronized
    fun addTracker(mediaFormat: MediaFormat?, isVideo: Boolean) {
        if (mMediaMuxer == null) {
            Logger.e(TAG, "addTracker: MediaMuxer is null, cannot add track. Init likely failed.")
            return
        }
        if (isMuxerStarter() || mediaFormat == null) {
            if (isMuxerStarter()) Logger.w(TAG, "addTracker: Muxer already started.")
            if (mediaFormat == null) Logger.w(TAG, "addTracker: mediaFormat is null.")
            return
        }
        try {
            mMediaMuxer?.apply {
                val tracker = addTrack(mediaFormat)
                if (Utils.debugCamera) {
                    Logger.i(TAG, "addTracker index = $tracker isVideo = $isVideo")
                }
                if (isVideo) {
                    mVideoFormat = mediaFormat
                    mVideoTrackerIndex = tracker
                    if (mAudioTrackerIndex != -1 || isVideoOnly) {
                        Logger.i(TAG, "addTracker: Starting MediaMuxer (video track added).")
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                    }
                } else {
                    mAudioFormat = mediaFormat
                    mAudioTrackerIndex = tracker
                    if (mVideoTrackerIndex != -1) {
                        Logger.i(TAG, "addTracker: Starting MediaMuxer (audio track added).")
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "addTracker failed"
            Logger.e(TAG, "addTracker failed for isVideo=$isVideo, err = $errorMsg", e)
            mMainHandler.post {
                mCaptureCallBack?.onError(errorMsg)
            }
            release()
        }
    }

    @Synchronized
    fun pumpStream(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isVideo: Boolean) {
        if (mMediaMuxer == null || !isMuxerStarter()) {
            return
        }
        try {
            if (bufferInfo.size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) ) {
                return
            }
            val index = if (isVideo) {
                if (mVideoPts == 0L && bufferInfo.presentationTimeUs > 0) {
                    mVideoPts = bufferInfo.presentationTimeUs
                }
                bufferInfo.presentationTimeUs -= mVideoPts
                if (bufferInfo.presentationTimeUs < 0) bufferInfo.presentationTimeUs = 0L
                mVideoTrackerIndex
            } else {
                if (mAudioPts == 0L && bufferInfo.presentationTimeUs > 0) {
                    mAudioPts = bufferInfo.presentationTimeUs
                }
                bufferInfo.presentationTimeUs -= mAudioPts
                if (bufferInfo.presentationTimeUs < 0) bufferInfo.presentationTimeUs = 0L
                mAudioTrackerIndex
            }

            if (index < 0) {
                Logger.w(TAG, "pumpStream: Invalid track index ($index) for isVideo=$isVideo. Skipping.")
                return
            }

            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
            mMediaMuxer?.writeSampleData(index, outputBuffer, bufferInfo)
            saveNewFileIfNeed()
        } catch (e: Exception) {
            Logger.e(TAG, "pumpStream failed for isVideo=$isVideo, err = ${e.localizedMessage}", e)
        }
    }

    private fun saveNewFileIfNeed() {
        if (durationInSec == 0L || mMediaMuxer == null) {
            return
        }
        try {
            val endMillis = System.currentTimeMillis()
            if (endMillis - mBeginMillis <= durationInSec * 1000) {
                return
            }

            Logger.i(TAG, "saveNewFileIfNeed: Segment duration reached for $mCurrentSegmentPath. Finalizing.")
            mMediaMuxer?.stop()
            mMediaMuxer?.release()
            mMediaMuxer = null

            val completedSegmentPath = mCurrentSegmentPath
            if (completedSegmentPath != null) {
                try {
                    Logger.d(TAG, "saveNewFileIfNeed: Attempting insertDCIM (best effort) for segment: $completedSegmentPath")
                    insertDCIM(mContext, completedSegmentPath)
                } catch (eInsert: Exception) {
                    Logger.e(TAG, "saveNewFileIfNeed: insertDCIM failed for segment $completedSegmentPath. Error: ${eInsert.localizedMessage}", eInsert)
                }
                Logger.i(TAG, "saveNewFileIfNeed: Segment $completedSegmentPath physically written.")
            }

            mAudioTrackerIndex = -1
            mVideoTrackerIndex = -1
            mAudioPts = 0L
            mVideoPts = 0L

            mCurrentSegmentPath = "${mOriginalPathUsedForSplitting}_${++mFileSubIndex}.mp4"
            Logger.i(TAG, "saveNewFileIfNeed: Starting new segment: $mCurrentSegmentPath")

            mMediaMuxer = MediaMuxer(mCurrentSegmentPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (mVideoFormat != null) addTracker(mVideoFormat, true)
            if (mAudioFormat != null && !isVideoOnly) addTracker(mAudioFormat, false)

        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Segment split processing error"
            Logger.e(TAG, "saveNewFileIfNeed: Error during segment split: $errorMsg", e)
            mMainHandler.post {
                mCaptureCallBack?.onError(errorMsg)
            }
            release()
        }
    }

    @Synchronized
    fun release() {
        Logger.i(TAG, "release: Starting Mp4Muxer release. Current active path: $mCurrentSegmentPath")
        var muxerFinalizedSuccessfully = false
        var muxerStopReleaseError: String? = null

        try {
            if (mMediaMuxer != null && isMuxerStarter()) {
                Logger.i(TAG, "release: Attempting to stop Android MediaMuxer for path: $mCurrentSegmentPath")
                mMediaMuxer?.stop()
                Logger.i(TAG, "release: Android MediaMuxer.stop() successful for path: $mCurrentSegmentPath")
            } else if (mMediaMuxer != null) {
                Logger.w(TAG, "release: MediaMuxer instance exists but was not started. Skipping stop().")
            }
            if (mMediaMuxer != null) {
                Logger.i(TAG, "release: Attempting to release Android MediaMuxer for path: $mCurrentSegmentPath")
                mMediaMuxer?.release()
                Logger.i(TAG, "release: Android MediaMuxer.release() successful for path: $mCurrentSegmentPath")
            }
            muxerFinalizedSuccessfully = true
        } catch (e: Exception) {
            muxerStopReleaseError = e.localizedMessage ?: "Muxer stop/release critical error"
            Logger.e(TAG, "release: CRITICAL Exception during Android MediaMuxer stop/release for $mCurrentSegmentPath: $muxerStopReleaseError", e)
        } finally {
            mMediaMuxer = null
            mVideoTrackerIndex = -1
            mAudioTrackerIndex = -1
            mVideoPts = 0L
            mAudioPts = 0L
        }

        val pathToReport = mCurrentSegmentPath

        if (muxerFinalizedSuccessfully && pathToReport != null) {
            Logger.i(TAG, "release: Muxer finalized successfully for $pathToReport. File should be valid on disk.")
            var insertDCIMError: String? = null
            try {
                Logger.i(TAG, "release: Attempting insertDCIM (best effort) for path: $pathToReport")
                insertDCIM(mContext, pathToReport)
            } catch (eInsert: Exception) {
                insertDCIMError = eInsert.localizedMessage ?: "insertDCIM failed (Unknown Error)"
                Logger.e(TAG, "release: insertDCIM (best effort) failed for $pathToReport. Error: $insertDCIMError", eInsert)
            }

            Logger.i(TAG, "release: Calling onComplete for successfully written file: $pathToReport. (insertDCIM status: ${insertDCIMError ?: "success or skipped"})")
            val finalPathForCallback = pathToReport
            mMainHandler.post {
                mCaptureCallBack?.onComplete(finalPathForCallback)
            }
        } else {
            val finalErrorMsg = muxerStopReleaseError ?: "Muxer operation failed (unknown reason, path: $pathToReport)"
            Logger.e(TAG, "release: Muxer did NOT finalize successfully or path was null. Calling onError: $finalErrorMsg")
            mMainHandler.post {
                mCaptureCallBack?.onError(finalErrorMsg)
            }
            if (pathToReport != null) {
                Logger.w(TAG, "release: Deleting potentially corrupt file due to MediaMuxer finalization error: $pathToReport")
                File(pathToReport).delete()
            }
        }
        mCurrentSegmentPath = null
        Logger.i(TAG, "Mp4Muxer release process fully finished.")
    }

    fun getSavePath(): String? = mCurrentSegmentPath

    private fun insertDCIM(context: Context?, videoPath: String?) {
        context ?: return
        if (videoPath.isNullOrEmpty()) {
            Logger.w(TAG, "insertDCIM: videoPath is null or empty, skipping.")
            return
        }
        val file = File(videoPath)
        if (!file.exists() || file.length() == 0L) {
            Logger.e(TAG, "insertDCIM: File does not exist or is empty. Cannot insert into MediaStore. Path: $videoPath")
            return
        }
        Logger.d(TAG, "insertDCIM: Attempting for videoPath: $videoPath (Size: ${file.length()})")
        try {
            val values = getVideoContentValues(videoPath)
            Logger.d(TAG, "insertDCIM: Inserting values: $values")
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                Logger.i(TAG, "insertDCIM: Successfully inserted $videoPath into MediaStore. New URI: $uri")
            } else {
                Logger.e(TAG, "insertDCIM: contentResolver.insert returned null URI for $videoPath")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "insertDCIM: Failed to insert $videoPath into MediaStore. Error: ${e.localizedMessage}", e)
        }
    }

    private fun getVideoContentValues(path: String): ContentValues {
        val file = File(path)
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DATA, path)
        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.SIZE, file.length())
        values.put(MediaStore.Video.Media.DURATION, getLocalVideoDuration(file.path))

        if (MediaUtils.isAboveQ()) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + "Camera")
            values.put(MediaStore.Video.Media.DATE_EXPIRES, (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS) / 1000)
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        return values
    }

    fun isMuxerStarter() = mVideoTrackerIndex != -1 && (mAudioTrackerIndex != -1 || isVideoOnly) && mMediaMuxer != null

    private fun getLocalVideoDuration(filePath: String?): Long {
        if (filePath.isNullOrEmpty() || !File(filePath).exists()) {
            Logger.w(TAG, "getLocalVideoDuration: filePath is null or file does not exist: $filePath")
            return 0L
        }
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLong() ?: 0L
        } catch (e: Exception) {
            Logger.e(TAG, "getLocalVideoDuration failed for $filePath: ${e.message}")
            0L
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Logger.e(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "Mp4Muxer"
    }
}