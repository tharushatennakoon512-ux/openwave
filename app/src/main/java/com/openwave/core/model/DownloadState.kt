package com.openwave.core.model

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long?) : DownloadState
    data object Paused : DownloadState
    data class Completed(val filePath: String, val sizeBytes: Long) : DownloadState
    data class Failed(val reason: String, val retryable: Boolean = true) : DownloadState
}
