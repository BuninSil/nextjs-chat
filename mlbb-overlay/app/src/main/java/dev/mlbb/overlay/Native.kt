package dev.mlbb.overlay

/** JNI-обёртка над libtunbridge (zdtun + SOCKS5 UDP + ICMP ping). */
object Native {
    init {
        System.loadLibrary("tunbridge")
    }

    /**
     * Блокирующий цикл пересылки трафика. Возвращает 0 при штатной остановке.
     * chainMode: весь TCP/UDP уходит в SOCKS5 [socksHost]:[socksPort] (IPv4).
     */
    @JvmStatic
    external fun run(
        tunFd: Int,
        service: CaptureVpnService,
        gameUid: Int,
        chainMode: Boolean,
        socksHost: String?,
        socksPort: Int,
        socksUser: String?,
        socksPass: String?,
    ): Int

    @JvmStatic
    external fun stop()

    /** RTT в мс, либо < 0 если ответа нет. */
    @JvmStatic
    external fun icmpPing(ip: String, timeoutMs: Int): Int
}
