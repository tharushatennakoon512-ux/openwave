package com.openwave.extractor

sealed class ExtractionError(message: String, cause: Throwable? = null) : Exception(message, cause)

class NetworkError(message: String = "Network error", cause: Throwable? = null) : ExtractionError(message, cause)
class StreamExpiredError(val trackId: String, message: String = "Stream URL expired") : ExtractionError(message)
class ExtractionFailedError(message: String = "Extraction failed", cause: Throwable? = null) : ExtractionError(message, cause)
class UnsupportedSourceError(val url: String) : ExtractionError("Unsupported source: $url")
