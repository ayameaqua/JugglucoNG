package tk.glucodata.drivers.sibionics

/** Factory parameters only; transmitter connection codes are not probe calibration. */
internal object SibionicsProbeCalibration {
    fun sensitivity(code: String?): Float? {
        SibionicsProbeSensitivity.tryDecode(code)?.let { return it }
        // The established P2 serial uses the same checked calibration token as
        // setup's native identity window. Never derive one from an XPT prefix.
        if (code == null || code.length != 16 || !code.startsWith("P2") ||
            !code.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        val shortCode = ("1" + code.dropLast(1)).takeLast(11).take(8)
        return SibionicsSensitivity.tryDecode(shortCode)
    }
}
