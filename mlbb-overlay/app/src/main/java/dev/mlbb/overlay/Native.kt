package dev.mlbb.overlay

/** JNI-обёртка над libtunbridge (zdtun + ICMP ping). */
object Native {
    init {
        System.loadLibrary("tunbridge")
    }

    /** Блокирующий цикл пересылки трафика. Возвращает 0 при штатной остановке. */
    @JvmStatic
    external fun run(tunFd: Int, service: CaptureVpnService): Int

    @JvmStatic
    external fun stop()

    /** RTT в мс, либо < 0 если ответа нет. */
    @JvmStatic
    external fun icmpPing(ip: String, timeoutMs: Int): Int
}
