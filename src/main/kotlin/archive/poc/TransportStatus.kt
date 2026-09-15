package archive.poc

enum class TransportStatus {
    /** HTTP response received (status may still be 4xx/5xx). */
    HTTP_RESPONSE,

    /** DNS / connect / timeout / reset / incomplete body. */
    TRANSPORT_FAILURE,

    /** Local archive/filesystem failure after optional HTTP possession. */
    LOCAL_FAILURE,
}
