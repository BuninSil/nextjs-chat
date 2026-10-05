/*
 * JNI-мост между CaptureVpnService и zdtun.
 *
 * zdtun делает всю грязную работу: разбирает IP-пакеты из tun, открывает
 * настоящие TCP/UDP-сокеты наружу и собирает ответы обратно в IP-пакеты.
 * Здесь только цикл select(), защита сокетов через VpnService.protect()
 * и учёт статистики по каждому соединению.
 */
#include <jni.h>
#include <android/log.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "zdtun.h"

#define TAG "tunbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define VPN_MTU 1500
#define SELECT_TIMEOUT_MS 250
#define REPORT_INTERVAL_MS 1000
#define PURGE_INTERVAL_MS 2000
/* Сколько пакетов из tun подряд обработать, прежде чем дать шанс сокетам */
#define MAX_TUN_BURST 32

typedef struct conn_data {
    int id;
    int proto;
    int src_port;
    int dst_port;
    char dst_ip[INET6_ADDRSTRLEN];

    uint64_t bytes_out, bytes_in;
    uint64_t pkts_out, pkts_in;
    int64_t first_ms;      /* wall clock, время первого пакета */
    int64_t last_ms;       /* wall clock, время последнего пакета */

    int64_t syn_mono_ms;   /* когда игра отправила SYN */
    int hs_rtt_ms;         /* SYN -> SYN/ACK (zdtun шлёт SYN/ACK после реального connect) */

    int closed;
    int dirty;
    struct conn_data *next;
} conn_data_t;

typedef struct {
    JNIEnv *env;
    jobject service;
    jmethodID mid_protect;
    jmethodID mid_update;
    int tunfd;
    int next_id;
    conn_data_t *head;
} bridge_t;

static volatile int g_running = 0;

static int64_t now_ms(clockid_t clk) {
    struct timespec ts;
    clock_gettime(clk, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* ---------------------------- zdtun callbacks ---------------------------- */

static int cb_send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    bridge_t *br = (bridge_t *) zdtun_userdata(tun);
    const char *p = pkt->buf;
    int left = pkt->len;

    while (left > 0) {
        ssize_t n = write(br->tunfd, p, left);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            LOGE("tun write failed: %s", strerror(errno));
            return -1;
        }
        p += n;
        left -= (int) n;
    }
    return 0;
}

static void cb_account_packet(zdtun_t *tun, const zdtun_pkt_t *pkt, uint8_t to_zdtun,
                              const zdtun_conn_t *conn) {
    conn_data_t *cd = (conn_data_t *) zdtun_conn_get_userdata(conn);
    if (!cd)
        return;

    cd->last_ms = now_ms(CLOCK_REALTIME);
    cd->dirty = 1;

    if (to_zdtun) {
        cd->pkts_out++;
        cd->bytes_out += pkt->len;
        if (cd->proto == IPPROTO_TCP && cd->syn_mono_ms == 0)
            cd->syn_mono_ms = now_ms(CLOCK_MONOTONIC);
    } else {
        cd->pkts_in++;
        cd->bytes_in += pkt->len;
        if (cd->proto == IPPROTO_TCP && cd->hs_rtt_ms < 0 && cd->syn_mono_ms > 0)
            cd->hs_rtt_ms = (int) (now_ms(CLOCK_MONOTONIC) - cd->syn_mono_ms);
    }
}

static void cb_on_socket_open(zdtun_t *tun, socket_t sock) {
    bridge_t *br = (bridge_t *) zdtun_userdata(tun);
    /* Без protect() сокет zdtun уйдёт обратно в наш же VPN -> петля */
    jboolean ok = (*br->env)->CallBooleanMethod(br->env, br->service, br->mid_protect, (jint) sock);
    clear_exception(br->env);
    if (!ok)
        LOGE("VpnService.protect(%d) failed", sock);
}

static int cb_on_connection_open(zdtun_t *tun, zdtun_conn_t *conn) {
    bridge_t *br = (bridge_t *) zdtun_userdata(tun);
    const zdtun_5tuple_t *t = zdtun_conn_get_5tuple(conn);
    conn_data_t *cd = calloc(1, sizeof(conn_data_t));

    if (!cd)
        return 0; /* без статистики, но соединение всё равно пропускаем */

    cd->id = ++br->next_id;
    cd->proto = t->ipproto;
    cd->src_port = ntohs(t->src_port);
    cd->dst_port = ntohs(t->dst_port);
    if (t->ipver == 4)
        inet_ntop(AF_INET, &t->dst_ip.ip4, cd->dst_ip, sizeof(cd->dst_ip));
    else
        inet_ntop(AF_INET6, &t->dst_ip.ip6, cd->dst_ip, sizeof(cd->dst_ip));
    cd->first_ms = cd->last_ms = now_ms(CLOCK_REALTIME);
    cd->hs_rtt_ms = -1;
    cd->dirty = 1;

    cd->next = br->head;
    br->head = cd;

    zdtun_conn_set_userdata(conn, cd);
    return 0;
}

static void cb_on_connection_close(zdtun_t *tun, const zdtun_conn_t *conn) {
    conn_data_t *cd = (conn_data_t *) zdtun_conn_get_userdata(conn);
    if (!cd)
        return;

    /* Память освободим после отправки финальной статистики в Java */
    cd->closed = 1;
    cd->dirty = 1;
    zdtun_conn_set_userdata((zdtun_conn_t *) conn, NULL);
}

/* ------------------------------------------------------------------------ */

static void report_connections(bridge_t *br) {
    JNIEnv *env = br->env;
    conn_data_t *cd = br->head, *prev = NULL;

    while (cd) {
        conn_data_t *next = cd->next;

        if (cd->dirty) {
            jstring ip = (*env)->NewStringUTF(env, cd->dst_ip);
            (*env)->CallVoidMethod(env, br->service, br->mid_update,
                                   (jint) cd->id, (jint) cd->proto, (jint) cd->src_port,
                                   ip, (jint) cd->dst_port,
                                   (jlong) cd->bytes_out, (jlong) cd->bytes_in,
                                   (jlong) cd->pkts_out, (jlong) cd->pkts_in,
                                   (jlong) cd->first_ms, (jlong) cd->last_ms,
                                   (jint) cd->hs_rtt_ms, (jboolean) (cd->closed ? 1 : 0));
            clear_exception(env);
            (*env)->DeleteLocalRef(env, ip);
            cd->dirty = 0;
        }

        if (cd->closed) {
            if (prev)
                prev->next = next;
            else
                br->head = next;
            free(cd);
        } else {
            prev = cd;
        }
        cd = next;
    }
}

JNIEXPORT jint JNICALL
Java_dev_mlbb_overlay_Native_run(JNIEnv *env, jclass clazz, jint tunfd, jobject service) {
    bridge_t br;
    zdtun_callbacks_t callbacks;
    static char buf[65536];
    int rv = 0;

    memset(&br, 0, sizeof(br));
    br.env = env;
    br.service = service;
    br.tunfd = tunfd;

    jclass svc_class = (*env)->GetObjectClass(env, service);
    br.mid_protect = (*env)->GetMethodID(env, svc_class, "protect", "(I)Z");
    br.mid_update = (*env)->GetMethodID(env, svc_class, "onConnUpdate",
                                        "(IIILjava/lang/String;IJJJJJJIZ)V");
    if (!br.mid_protect || !br.mid_update) {
        clear_exception(env);
        LOGE("JNI methods not found");
        return -1;
    }

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.send_client = cb_send_client;
    callbacks.account_packet = cb_account_packet;
    callbacks.on_socket_open = cb_on_socket_open;
    callbacks.on_connection_open = cb_on_connection_open;
    callbacks.on_connection_close = cb_on_connection_close;

    zdtun_t *tun = zdtun_init(&callbacks, &br);
    if (!tun) {
        LOGE("zdtun_init failed");
        return -2;
    }
    zdtun_set_mtu(tun, VPN_MTU);

    g_running = 1;
    LOGI("capture loop started, tunfd=%d", tunfd);

    int64_t next_report = now_ms(CLOCK_MONOTONIC) + REPORT_INTERVAL_MS;
    int64_t next_purge = now_ms(CLOCK_MONOTONIC) + PURGE_INTERVAL_MS;
    int tun_burst = 0;

    while (g_running) {
        fd_set rd, wr;
        int max_fd = 0;
        /* Если tun долго был занят, один раунд обслуживаем только сокеты */
        int watch_tun = (tun_burst < MAX_TUN_BURST);

        zdtun_fds(tun, &max_fd, &rd, &wr);
        if (watch_tun) {
            FD_SET(tunfd, &rd);
            if (tunfd > max_fd)
                max_fd = tunfd;
        }

        struct timeval tv = {0, SELECT_TIMEOUT_MS * 1000};
        int sel = select(max_fd + 1, &rd, &wr, NULL, &tv);

        if (sel < 0) {
            if (errno == EINTR)
                continue;
            LOGE("select failed: %s", strerror(errno));
            rv = -3;
            break;
        }

        if (sel > 0) {
            if (watch_tun && FD_ISSET(tunfd, &rd)) {
                ssize_t n = read(tunfd, buf, sizeof(buf));

                if (n > 0) {
                    zdtun_easy_forward(tun, buf, (int) n);
                } else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
                    LOGE("tun read failed: %s", n == 0 ? "EOF" : strerror(errno));
                    rv = -4;
                    break;
                }
                /* easy_forward мог закрыть/открыть сокеты: fd_set'ы устарели,
                 * поэтому сокеты обработаем на следующем select() */
                tun_burst++;
            } else {
                tun_burst = 0;
                zdtun_handle_fd(tun, &rd, &wr);
            }
        } else {
            tun_burst = 0;
        }

        int64_t now = now_ms(CLOCK_MONOTONIC);
        if (now >= next_purge) {
            zdtun_purge_expired(tun);
            next_purge = now + PURGE_INTERVAL_MS;
        }
        if (now >= next_report) {
            report_connections(&br);
            next_report = now + REPORT_INTERVAL_MS;
        }
    }

    LOGI("capture loop stopping (rv=%d)", rv);
    zdtun_finalize(tun); /* закрывает все соединения -> cb_on_connection_close */
    report_connections(&br);
    g_running = 0;
    return rv;
}

JNIEXPORT void JNICALL
Java_dev_mlbb_overlay_Native_stop(JNIEnv *env, jclass clazz) {
    g_running = 0;
}

/* ------------------------------------------------------------------------ */

struct echo_hdr {
    uint8_t type;
    uint8_t code;
    uint16_t cksum;
    uint16_t id;
    uint16_t seq;
};

static uint16_t inet_cksum(const void *data, int len) {
    const uint16_t *p = data;
    uint32_t sum = 0;
    while (len > 1) {
        sum += *p++;
        len -= 2;
    }
    if (len)
        sum += *(const uint8_t *) p;
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t) ~sum;
}

/*
 * ICMP echo через непривилегированный "ping socket" (SOCK_DGRAM + IPPROTO_ICMP),
 * Android разрешает его обычным приложениям. Наше приложение не входит в VPN
 * (там только игра), так что пинг идёт напрямую.
 * Возвращает RTT в мс или отрицательное значение при ошибке/таймауте.
 */
JNIEXPORT jint JNICALL
Java_dev_mlbb_overlay_Native_icmpPing(JNIEnv *env, jclass clazz, jstring jip, jint timeout_ms) {
    static uint16_t seq = 0;
    struct sockaddr_in addr;
    const char *ip = (*env)->GetStringUTFChars(env, jip, NULL);
    int ok;

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    ok = inet_pton(AF_INET, ip, &addr.sin_addr);
    (*env)->ReleaseStringUTFChars(env, jip, ip);
    if (ok != 1)
        return -2;

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
    if (sock < 0)
        return -3;

    unsigned char pkt[sizeof(struct echo_hdr) + 32];
    struct echo_hdr *h = (struct echo_hdr *) pkt;
    memset(pkt, 0, sizeof(pkt));
    for (int i = sizeof(struct echo_hdr); i < (int) sizeof(pkt); i++)
        pkt[i] = (unsigned char) i;
    h->type = 8; /* echo request */
    h->seq = htons(++seq);
    h->cksum = inet_cksum(pkt, sizeof(pkt)); /* ядро всё равно пересчитает с учётом id */

    int64_t start = now_ms(CLOCK_MONOTONIC);
    if (sendto(sock, pkt, sizeof(pkt), 0, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(sock);
        return -4;
    }

    int rv = -1;
    for (;;) {
        int elapsed = (int) (now_ms(CLOCK_MONOTONIC) - start);
        if (elapsed >= timeout_ms)
            break;

        struct pollfd pfd = {sock, POLLIN, 0};
        if (poll(&pfd, 1, timeout_ms - elapsed) <= 0)
            break;

        unsigned char reply[512];
        ssize_t n = recv(sock, reply, sizeof(reply), 0);
        if (n < (ssize_t) sizeof(struct echo_hdr))
            continue;

        struct echo_hdr *r = (struct echo_hdr *) reply;
        if (r->type == 0 /* echo reply */ && r->seq == h->seq) {
            rv = (int) (now_ms(CLOCK_MONOTONIC) - start);
            if (rv == 0)
                rv = 1;
            break;
        }
    }

    close(sock);
    return rv;
}
