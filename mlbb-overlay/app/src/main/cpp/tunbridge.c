/*
 * JNI-мост между CaptureVpnService и zdtun.
 *
 * zdtun делает всю грязную работу: разбирает IP-пакеты из tun, открывает
 * настоящие TCP/UDP-сокеты наружу и собирает ответы обратно в IP-пакеты.
 * Здесь цикл select(), защита сокетов через VpnService.protect()
 * и учёт статистики по каждому соединению игры.
 *
 * Режим цепочки (сторонний VPN-клиент в режиме «только прокси»):
 *  - TCP всех приложений zdtun отправляет в SOCKS5 клиента (zdtun_conn_proxy);
 *  - UDP zdtun через SOCKS5 не умеет, поэтому UDP обрабатывается здесь же:
 *    на каждый поток открывается SOCKS5 UDP ASSOCIATE (RFC 1928, раздел 7),
 *    датаграммы оборачиваются в заголовок SOCKS5 и уходят на relay клиента.
 */
#include <jni.h>
#include <android/log.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "zdtun.h"
#include "third_party/uthash.h"

#define TAG "tunbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define VPN_MTU 1500
#define SELECT_TIMEOUT_MS 250
#define REPORT_INTERVAL_MS 1000
#define PURGE_INTERVAL_MS 2000
/* Сколько пакетов из tun подряд обработать, прежде чем дать шанс сокетам */
#define MAX_TUN_BURST 32

#define SOCKS_TIMEOUT_MS 3000
#define UDP_IDLE_MS 60000
#define DNS_IDLE_MS 10000
#define MAX_UDP_FLOWS 256

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
    int track_hs;          /* при проксировании SYN/ACK приходит от прокси — не меряем */

    int closed;
    int dirty;
    struct conn_data *next;
} conn_data_t;

/* UDP-поток, идущий через SOCKS5 UDP ASSOCIATE */
typedef struct udp_key {
    uint32_t src_ip, dst_ip;   /* network order */
    uint16_t src_port, dst_port;
} udp_key_t;

typedef struct udp_flow {
    udp_key_t key;
    int ctrl_sock;             /* TCP-соединение ассоциации: пока оно живо, жив и relay */
    int udp_sock;              /* connect()ed на relay-адрес прокси */
    int64_t last_mono_ms;
    conn_data_t *cd;           /* != NULL только для потоков игры */
    UT_hash_handle hh;
} udp_flow_t;

typedef struct {
    JNIEnv *env;
    jobject service;
    jmethodID mid_protect;
    jmethodID mid_update;
    jmethodID mid_get_uid;
    int tunfd;
    int next_id;
    conn_data_t *head;

    int game_uid;
    int chain_mode;
    int socks_enabled;
    struct sockaddr_in socks_addr;
    char socks_user[256];
    char socks_pass[256];

    udp_flow_t *udp_flows;
    int num_udp_flows;
    uint16_t ip_id;
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

static void protect_socket(bridge_t *br, int sock) {
    /* Без protect() сокет уйдёт обратно в наш же VPN -> петля */
    jboolean ok = (*br->env)->CallBooleanMethod(br->env, br->service, br->mid_protect, (jint) sock);
    clear_exception(br->env);
    if (!ok)
        LOGE("VpnService.protect(%d) failed", sock);
}

/* Владелец соединения по uid (через ConnectivityManager на стороне Kotlin) */
static int lookup_uid(bridge_t *br, int ipver, int proto,
                      const char *src_ip, int src_port, const char *dst_ip, int dst_port) {
    JNIEnv *env = br->env;
    jstring jsrc = (*env)->NewStringUTF(env, src_ip);
    jstring jdst = (*env)->NewStringUTF(env, dst_ip);
    jint uid = (*env)->CallIntMethod(env, br->service, br->mid_get_uid,
                                     (jint) ipver, (jint) proto,
                                     jsrc, (jint) src_port, jdst, (jint) dst_port);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jsrc);
    (*env)->DeleteLocalRef(env, jdst);
    return uid;
}

static conn_data_t *new_conn_data(bridge_t *br, int proto, int src_port,
                                  const char *dst_ip, int dst_port) {
    conn_data_t *cd = calloc(1, sizeof(conn_data_t));
    if (!cd)
        return NULL;

    cd->id = ++br->next_id;
    cd->proto = proto;
    cd->src_port = src_port;
    cd->dst_port = dst_port;
    strncpy(cd->dst_ip, dst_ip, sizeof(cd->dst_ip) - 1);
    cd->first_ms = cd->last_ms = now_ms(CLOCK_REALTIME);
    cd->hs_rtt_ms = -1;
    cd->dirty = 1;

    cd->next = br->head;
    br->head = cd;
    return cd;
}

static void account(conn_data_t *cd, int len, int outgoing) {
    cd->last_ms = now_ms(CLOCK_REALTIME);
    cd->dirty = 1;
    if (outgoing) {
        cd->pkts_out++;
        cd->bytes_out += len;
    } else {
        cd->pkts_in++;
        cd->bytes_in += len;
    }
}

/* ---------------------------- zdtun callbacks ---------------------------- */

static int write_tun(bridge_t *br, const char *p, int left) {
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

static int cb_send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    return write_tun((bridge_t *) zdtun_userdata(tun), pkt->buf, pkt->len);
}

static void cb_account_packet(zdtun_t *tun, const zdtun_pkt_t *pkt, uint8_t to_zdtun,
                              const zdtun_conn_t *conn) {
    conn_data_t *cd = (conn_data_t *) zdtun_conn_get_userdata(conn);
    if (!cd)
        return;

    account(cd, pkt->len, to_zdtun);

    if (cd->proto == IPPROTO_TCP && cd->track_hs) {
        if (to_zdtun && cd->syn_mono_ms == 0)
            cd->syn_mono_ms = now_ms(CLOCK_MONOTONIC);
        else if (!to_zdtun && cd->hs_rtt_ms < 0 && cd->syn_mono_ms > 0)
            cd->hs_rtt_ms = (int) (now_ms(CLOCK_MONOTONIC) - cd->syn_mono_ms);
    }
}

static void cb_on_socket_open(zdtun_t *tun, socket_t sock) {
    protect_socket((bridge_t *) zdtun_userdata(tun), sock);
}

static void ip2str(int ipver, zdtun_ip_t ip, char *buf, size_t len) {
    if (ipver == 4)
        inet_ntop(AF_INET, &ip.ip4, buf, len);
    else
        inet_ntop(AF_INET6, &ip.ip6, buf, len);
}

static int cb_on_connection_open(zdtun_t *tun, zdtun_conn_t *conn) {
    bridge_t *br = (bridge_t *) zdtun_userdata(tun);
    const zdtun_5tuple_t *t = zdtun_conn_get_5tuple(conn);
    char src_ip[INET6_ADDRSTRLEN], dst_ip[INET6_ADDRSTRLEN];
    int src_port = ntohs(t->src_port);
    int dst_port = ntohs(t->dst_port);
    int is_game = 1;

    ip2str(t->ipver, t->src_ip, src_ip, sizeof(src_ip));
    ip2str(t->ipver, t->dst_ip, dst_ip, sizeof(dst_ip));

    if (br->chain_mode) {
        /* В VPN все приложения: логируем только игру. Владельца ICMP не узнать. */
        if (t->ipproto == IPPROTO_TCP || t->ipproto == IPPROTO_UDP)
            is_game = (lookup_uid(br, t->ipver, t->ipproto, src_ip, src_port, dst_ip, dst_port) == br->game_uid);
        else
            is_game = 0;

        if (br->socks_enabled && t->ipproto == IPPROTO_TCP)
            zdtun_conn_proxy(conn);
    }

    if (!is_game)
        return 0;

    conn_data_t *cd = new_conn_data(br, t->ipproto, src_port, dst_ip, dst_port);
    if (!cd)
        return 0; /* без статистики, но соединение всё равно пропускаем */

    cd->track_hs = !(br->chain_mode && br->socks_enabled);
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

/* ------------------------- SOCKS5 UDP ASSOCIATE ------------------------- */

static int io_full(int sock, void *buf, int len, int is_send) {
    char *p = buf;
    int done = 0;

    while (done < len) {
        struct pollfd pfd = {sock, is_send ? POLLOUT : POLLIN, 0};
        if (poll(&pfd, 1, SOCKS_TIMEOUT_MS) <= 0)
            return -1;
        ssize_t n = is_send ? send(sock, p + done, len - done, MSG_NOSIGNAL)
                            : recv(sock, p + done, len - done, 0);
        if (n < 0 && (errno == EINTR || errno == EAGAIN))
            continue;
        if (n <= 0)
            return -1;
        done += (int) n;
    }
    return 0;
}

#define send_full(s, b, l) io_full((s), (void *) (b), (l), 1)
#define recv_full(s, b, l) io_full((s), (void *) (b), (l), 0)

/*
 * Открывает ассоциацию. Прокси локальный, поэтому рукопожатие делаем
 * синхронно с таймаутом: на loopback это доли миллисекунды.
 */
static int socks5_udp_associate(bridge_t *br, int *out_ctrl, int *out_udp) {
    unsigned char buf[600];
    int ctrl = socket(AF_INET, SOCK_STREAM, 0);
    int udp = -1;

    if (ctrl < 0 || ctrl >= FD_SETSIZE)
        goto fail;
    protect_socket(br, ctrl);
    fcntl(ctrl, F_SETFL, fcntl(ctrl, F_GETFL) | O_NONBLOCK);

    if (connect(ctrl, (struct sockaddr *) &br->socks_addr, sizeof(br->socks_addr)) < 0) {
        if (errno != EINPROGRESS)
            goto fail;
        struct pollfd pfd = {ctrl, POLLOUT, 0};
        int err = 0;
        socklen_t elen = sizeof(err);
        if (poll(&pfd, 1, SOCKS_TIMEOUT_MS) <= 0 ||
            getsockopt(ctrl, SOL_SOCKET, SO_ERROR, &err, &elen) < 0 || err != 0)
            goto fail;
    }

    int with_auth = br->socks_user[0] != '\0';
    unsigned char hello[4] = {5, (unsigned char) (with_auth ? 2 : 1), 0, 2};
    if (send_full(ctrl, hello, with_auth ? 4 : 3) || recv_full(ctrl, buf, 2) || buf[0] != 5)
        goto fail;

    if (buf[1] == 2) {
        int ul = (int) strlen(br->socks_user), pl = (int) strlen(br->socks_pass);
        int n = 0;
        buf[n++] = 1;
        buf[n++] = (unsigned char) ul;
        memcpy(buf + n, br->socks_user, ul);
        n += ul;
        buf[n++] = (unsigned char) pl;
        memcpy(buf + n, br->socks_pass, pl);
        n += pl;
        if (send_full(ctrl, buf, n) || recv_full(ctrl, buf, 2) || buf[1] != 0)
            goto fail;
    } else if (buf[1] != 0) {
        goto fail;
    }

    /* UDP ASSOCIATE, адрес клиента 0.0.0.0:0 — «пришлю с любого» */
    unsigned char req[10] = {5, 3, 0, 1, 0, 0, 0, 0, 0, 0};
    if (send_full(ctrl, req, sizeof(req)) || recv_full(ctrl, buf, 4) || buf[1] != 0)
        goto fail;

    struct sockaddr_in relay;
    memset(&relay, 0, sizeof(relay));
    relay.sin_family = AF_INET;

    if (buf[3] == 1) {
        if (recv_full(ctrl, buf + 4, 6))
            goto fail;
        memcpy(&relay.sin_addr, buf + 4, 4);
        memcpy(&relay.sin_port, buf + 8, 2);
    } else if (buf[3] == 4) {
        /* IPv6 relay — используем адрес прокси с тем же портом */
        if (recv_full(ctrl, buf + 4, 18))
            goto fail;
        memcpy(&relay.sin_port, buf + 20, 2);
    } else if (buf[3] == 3) {
        if (recv_full(ctrl, buf + 4, 1) || recv_full(ctrl, buf + 5, buf[4] + 2))
            goto fail;
        memcpy(&relay.sin_port, buf + 5 + buf[4], 2);
    } else {
        goto fail;
    }
    if (relay.sin_addr.s_addr == 0)
        relay.sin_addr = br->socks_addr.sin_addr;

    udp = socket(AF_INET, SOCK_DGRAM, 0);
    if (udp < 0 || udp >= FD_SETSIZE)
        goto fail;
    protect_socket(br, udp);
    fcntl(udp, F_SETFL, fcntl(udp, F_GETFL) | O_NONBLOCK);
    if (connect(udp, (struct sockaddr *) &relay, sizeof(relay)) < 0)
        goto fail;

    *out_ctrl = ctrl;
    *out_udp = udp;
    return 0;

fail:
    LOGE("SOCKS5 UDP ASSOCIATE failed (errno=%d)", errno);
    if (ctrl >= 0)
        close(ctrl);
    if (udp >= 0)
        close(udp);
    return -1;
}

static void udp_flow_destroy(bridge_t *br, udp_flow_t *f) {
    HASH_DEL(br->udp_flows, f);
    br->num_udp_flows--;
    close(f->ctrl_sock);
    close(f->udp_sock);
    if (f->cd) {
        f->cd->closed = 1;
        f->cd->dirty = 1;
    }
    free(f);
}

static void udp_flows_purge(bridge_t *br, int all) {
    udp_flow_t *f, *tmp;
    int64_t now = now_ms(CLOCK_MONOTONIC);

    HASH_ITER(hh, br->udp_flows, f, tmp) {
        int64_t idle = (ntohs(f->key.dst_port) == 53) ? DNS_IDLE_MS : UDP_IDLE_MS;
        if (all || now - f->last_mono_ms > idle)
            udp_flow_destroy(br, f);
    }
}

static uint16_t csum_add(uint32_t sum, const void *data, int len) {
    const uint8_t *p = data;
    while (len > 1) {
        sum += (p[0] << 8) | p[1];
        p += 2;
        len -= 2;
    }
    if (len)
        sum += p[0] << 8;
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t) sum;
}

/* Собирает IPv4/UDP-пакет от удалённой стороны к клиенту и пишет в tun */
static void udp_reply_to_client(bridge_t *br, const udp_flow_t *f, uint32_t from_ip, uint16_t from_port,
                                const unsigned char *data, int len) {
    static unsigned char pkt[65536];
    int total = 20 + 8 + len;

    if (total > (int) sizeof(pkt))
        return;

    unsigned char *ip = pkt, *udp = pkt + 20;
    memset(pkt, 0, 28);
    ip[0] = 0x45;
    ip[2] = (unsigned char) (total >> 8);
    ip[3] = (unsigned char) total;
    uint16_t id = htons(++br->ip_id);
    memcpy(ip + 4, &id, 2);
    ip[8] = 64;
    ip[9] = IPPROTO_UDP;
    memcpy(ip + 12, &from_ip, 4);
    memcpy(ip + 16, &f->key.src_ip, 4);
    uint16_t ipsum = htons((uint16_t) ~csum_add(0, ip, 20));
    memcpy(ip + 10, &ipsum, 2);

    memcpy(udp, &from_port, 2);
    memcpy(udp + 2, &f->key.src_port, 2);
    udp[4] = (unsigned char) ((8 + len) >> 8);
    udp[5] = (unsigned char) (8 + len);
    memcpy(udp + 8, data, len);

    /* псевдозаголовок: src, dst, 0, proto, udp len */
    unsigned char pseudo[12];
    memcpy(pseudo, ip + 12, 8);
    pseudo[8] = 0;
    pseudo[9] = IPPROTO_UDP;
    pseudo[10] = udp[4];
    pseudo[11] = udp[5];
    uint16_t s = csum_add(csum_add(0, pseudo, 12), udp, 8 + len);
    uint16_t usum = htons((uint16_t) ~s);
    if (usum == 0)
        usum = 0xffff;
    memcpy(udp + 6, &usum, 2);

    if (write_tun(br, (const char *) pkt, total) == 0 && f->cd)
        account(f->cd, total, 0);
}

static void udp_flow_read(bridge_t *br, udp_flow_t *f) {
    static unsigned char buf[65536];

    for (;;) {
        ssize_t n = recv(f->udp_sock, buf, sizeof(buf), 0);
        if (n < 0)
            return; /* EAGAIN */
        /* RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA; фрагменты SOCKS не поддерживаем */
        if (n < 10 || buf[2] != 0 || buf[3] != 1)
            continue;
        uint32_t from_ip;
        uint16_t from_port;
        memcpy(&from_ip, buf + 4, 4);
        memcpy(&from_port, buf + 8, 2);
        f->last_mono_ms = now_ms(CLOCK_MONOTONIC);
        udp_reply_to_client(br, f, from_ip, from_port, buf + 10, (int) n - 10);
    }
}

/*
 * UDP-пакет клиента в режиме цепочки. Возвращает 1, если пакет обработан
 * (или намеренно отброшен), 0 — если его надо отдать zdtun.
 */
static int udp_from_client(bridge_t *br, const unsigned char *pkt, int len) {
    static unsigned char out[65536];

    if (len < 28 || (pkt[0] >> 4) != 4 || pkt[9] != IPPROTO_UDP)
        return 0;
    int ihl = (pkt[0] & 0x0f) * 4;
    int frag = ((pkt[6] & 0x3f) << 8) | pkt[7];
    if (frag != 0 || (pkt[6] & 0x20)) /* фрагменты через SOCKS не пустить — отбрасываем */
        return 1;
    if (len < ihl + 8)
        return 1;

    const unsigned char *udp = pkt + ihl;
    int udp_len = (udp[4] << 8) | udp[5];
    if (udp_len < 8 || ihl + udp_len > len)
        return 1;

    udp_key_t key;
    memset(&key, 0, sizeof(key));
    memcpy(&key.src_ip, pkt + 12, 4);
    memcpy(&key.dst_ip, pkt + 16, 4);
    memcpy(&key.src_port, udp, 2);
    memcpy(&key.dst_port, udp + 2, 2);

    udp_flow_t *f = NULL;
    HASH_FIND(hh, br->udp_flows, &key, sizeof(key), f);

    if (!f) {
        if (br->num_udp_flows >= MAX_UDP_FLOWS)
            udp_flows_purge(br, 0);
        if (br->num_udp_flows >= MAX_UDP_FLOWS)
            return 1;

        int ctrl, usock;
        if (socks5_udp_associate(br, &ctrl, &usock) < 0)
            return 1;

        f = calloc(1, sizeof(udp_flow_t));
        if (!f) {
            close(ctrl);
            close(usock);
            return 1;
        }
        f->key = key;
        f->ctrl_sock = ctrl;
        f->udp_sock = usock;

        char src_ip[INET_ADDRSTRLEN], dst_ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &key.src_ip, src_ip, sizeof(src_ip));
        inet_ntop(AF_INET, &key.dst_ip, dst_ip, sizeof(dst_ip));
        if (lookup_uid(br, 4, IPPROTO_UDP, src_ip, ntohs(key.src_port), dst_ip, ntohs(key.dst_port)) == br->game_uid)
            f->cd = new_conn_data(br, IPPROTO_UDP, ntohs(key.src_port), dst_ip, ntohs(key.dst_port));

        HASH_ADD(hh, br->udp_flows, key, sizeof(key), f);
        br->num_udp_flows++;
    }

    int payload = udp_len - 8;
    if (10 + payload > (int) sizeof(out))
        return 1;
    out[0] = out[1] = out[2] = 0;
    out[3] = 1;
    memcpy(out + 4, &key.dst_ip, 4);
    memcpy(out + 8, &key.dst_port, 2);
    memcpy(out + 10, udp + 8, payload);

    f->last_mono_ms = now_ms(CLOCK_MONOTONIC);
    if (send(f->udp_sock, out, 10 + payload, 0) >= 0 && f->cd)
        account(f->cd, len, 1);
    return 1;
}

static void udp_flows_fds(bridge_t *br, int *max_fd, fd_set *rd) {
    udp_flow_t *f, *tmp;
    HASH_ITER(hh, br->udp_flows, f, tmp) {
        FD_SET(f->udp_sock, rd);
        FD_SET(f->ctrl_sock, rd);
        if (f->udp_sock > *max_fd)
            *max_fd = f->udp_sock;
        if (f->ctrl_sock > *max_fd)
            *max_fd = f->ctrl_sock;
    }
}

static void udp_flows_handle(bridge_t *br, fd_set *rd) {
    udp_flow_t *f, *tmp;
    HASH_ITER(hh, br->udp_flows, f, tmp) {
        if (FD_ISSET(f->udp_sock, rd))
            udp_flow_read(br, f);
        if (FD_ISSET(f->ctrl_sock, rd)) {
            /* По управляющему соединению данных не ждём: это закрытие ассоциации */
            char c;
            if (recv(f->ctrl_sock, &c, 1, MSG_DONTWAIT) <= 0 && errno != EAGAIN)
                udp_flow_destroy(br, f);
        }
    }
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
Java_dev_mlbb_overlay_Native_run(JNIEnv *env, jclass clazz, jint tunfd, jobject service,
                                 jint game_uid, jboolean chain_mode,
                                 jstring socks_host, jint socks_port,
                                 jstring socks_user, jstring socks_pass) {
    static bridge_t br;
    zdtun_callbacks_t callbacks;
    static char buf[65536];
    int rv = 0;

    memset(&br, 0, sizeof(br));
    br.env = env;
    br.service = service;
    br.tunfd = tunfd;
    br.game_uid = game_uid;
    br.chain_mode = chain_mode ? 1 : 0;

    jclass svc_class = (*env)->GetObjectClass(env, service);
    br.mid_protect = (*env)->GetMethodID(env, svc_class, "protect", "(I)Z");
    br.mid_update = (*env)->GetMethodID(env, svc_class, "onConnUpdate",
                                        "(IIILjava/lang/String;IJJJJJJIZ)V");
    br.mid_get_uid = (*env)->GetMethodID(env, svc_class, "getConnUid",
                                         "(IILjava/lang/String;ILjava/lang/String;I)I");
    if (!br.mid_protect || !br.mid_update || !br.mid_get_uid) {
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

    if (br.chain_mode && socks_host) {
        const char *host = (*env)->GetStringUTFChars(env, socks_host, NULL);
        zdtun_ip_t sip;

        if (zdtun_parse_ip(host, &sip) == 4) {
            zdtun_set_socks5_proxy(tun, &sip, htons((uint16_t) socks_port), 4);
            br.socks_addr.sin_family = AF_INET;
            br.socks_addr.sin_addr.s_addr = sip.ip4;
            br.socks_addr.sin_port = htons((uint16_t) socks_port);
            br.socks_enabled = 1;
            LOGI("SOCKS5 upstream %s:%d", host, socks_port);
        } else {
            LOGE("SOCKS5 host must be an IPv4 address: %s", host);
        }
        (*env)->ReleaseStringUTFChars(env, socks_host, host);

        if (br.socks_enabled && socks_user && socks_pass) {
            const char *u = (*env)->GetStringUTFChars(env, socks_user, NULL);
            const char *p = (*env)->GetStringUTFChars(env, socks_pass, NULL);
            if (u[0]) {
                strncpy(br.socks_user, u, sizeof(br.socks_user) - 1);
                strncpy(br.socks_pass, p, sizeof(br.socks_pass) - 1);
                zdtun_set_socks5_userpass(tun, u, p);
            }
            (*env)->ReleaseStringUTFChars(env, socks_user, u);
            (*env)->ReleaseStringUTFChars(env, socks_pass, p);
        }

        if (!br.socks_enabled) {
            zdtun_finalize(tun);
            return -5;
        }
    }

    g_running = 1;
    LOGI("capture loop started, tunfd=%d chain=%d", tunfd, br.chain_mode);

    int64_t next_report = now_ms(CLOCK_MONOTONIC) + REPORT_INTERVAL_MS;
    int64_t next_purge = now_ms(CLOCK_MONOTONIC) + PURGE_INTERVAL_MS;
    int tun_burst = 0;

    while (g_running) {
        fd_set rd, wr;
        int max_fd = 0;
        /* Если tun долго был занят, один раунд обслуживаем только сокеты */
        int watch_tun = (tun_burst < MAX_TUN_BURST);

        zdtun_fds(tun, &max_fd, &rd, &wr);
        udp_flows_fds(&br, &max_fd, &rd);
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
                    if (!(br.socks_enabled && udp_from_client(&br, (unsigned char *) buf, (int) n)))
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
                udp_flows_handle(&br, &rd);
            }
        } else {
            tun_burst = 0;
        }

        int64_t now = now_ms(CLOCK_MONOTONIC);
        if (now >= next_purge) {
            zdtun_purge_expired(tun);
            udp_flows_purge(&br, 0);
            next_purge = now + PURGE_INTERVAL_MS;
        }
        if (now >= next_report) {
            report_connections(&br);
            next_report = now + REPORT_INTERVAL_MS;
        }
    }

    LOGI("capture loop stopping (rv=%d)", rv);
    udp_flows_purge(&br, 1);
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
 * Android разрешает его обычным приложениям. Наше приложение не входит в VPN,
 * так что пинг идёт напрямую.
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
