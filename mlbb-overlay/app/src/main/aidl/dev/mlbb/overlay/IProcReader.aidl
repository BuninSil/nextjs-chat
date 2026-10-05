package dev.mlbb.overlay;

// Сервис, который Shizuku запускает с правами adb-шелла
interface IProcReader {
    // Содержимое /proc/net/{udp,udp6,tcp,tcp6}; другие пути не отдаёт
    String readProcNet(String name) = 1;

    // Shizuku вызывает при отвязке (зарезервированный код транзакции)
    void destroy() = 16777114;
}
