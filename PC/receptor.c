/* Receptor de audio para Windows 7 (sin dependencias).
   Compilar: gcc receptor.c -o receptor.exe -O2 -s -lws2_32 -lwinmm */
#include <winsock2.h>
#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PORT   7000
#define NBUF   8      /* 8 x 20 ms = 160 ms maximo de latencia */
#define BUFSZ  8192
#define PREBUF 3      /* 60 ms de colchon antes de sonar */

static HWAVEOUT hwo;
static WAVEHDR hdr[NBUF];
static char *bufs[NBUF];
static volatile float gain = 1.0f;
static int nextb = 0, queued = 0, started = 0;

static int recv_all(SOCKET s, char *p, int n) {
    while (n > 0) {
        int r = recv(s, p, n, 0);
        if (r <= 0) return 0;
        p += r; n -= r;
    }
    return 1;
}

static void set_volume(int pct) {
    float f;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    f = pct / 100.0f;
    gain = f * f; /* curva suave, parecida al volumen de Android */
    printf("\rVolumen: %3d%%   ", pct);
    fflush(stdout);
}

static int open_audio(void) {
    WAVEFORMATEX wf;
    int i;
    memset(&wf, 0, sizeof wf);
    wf.wFormatTag = WAVE_FORMAT_PCM;
    wf.nChannels = 2;
    wf.nSamplesPerSec = 48000;
    wf.wBitsPerSample = 16;
    wf.nBlockAlign = 4;
    wf.nAvgBytesPerSec = 48000 * 4;
    if (waveOutOpen(&hwo, WAVE_MAPPER, &wf, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR) return 0;
    for (i = 0; i < NBUF; i++) {
        bufs[i] = (char *)malloc(BUFSZ);
        memset(&hdr[i], 0, sizeof(WAVEHDR));
        hdr[i].lpData = bufs[i];
        hdr[i].dwBufferLength = BUFSZ;
        waveOutPrepareHeader(hwo, &hdr[i], sizeof(WAVEHDR));
    }
    return 1;
}

static void reset_audio(void) {
    waveOutReset(hwo);
    waveOutPause(hwo);
    nextb = 0; queued = 0; started = 0;
}

static void play(char *data, int len) {
    int i, k, j, n;
    short *src, *dst;
    if (len <= 0) return;
    for (k = 0; k < NBUF; k++) {
        i = (nextb + k) % NBUF;
        if (!(hdr[i].dwFlags & WHDR_INQUEUE)) break;
    }
    if (k == NBUF) return; /* cola llena: descartar para no acumular retraso */
    i = (nextb + k) % NBUF;
    src = (short *)data; dst = (short *)bufs[i];
    n = len / 2;
    for (j = 0; j < n; j++) dst[j] = (short)(src[j] * gain);
    hdr[i].dwBufferLength = len;
    waveOutWrite(hwo, &hdr[i], sizeof(WAVEHDR));
    nextb = (i + 1) % NBUF;
    if (!started && ++queued >= PREBUF) { waveOutRestart(hwo); started = 1; }
}

/* adb borra el reenvio de puerto cuando se desconecta el cable: hay que recrearlo */
static void ensure_forward(void) {
    system("adb forward --remove tcp:7000 >nul 2>&1 & adb forward tcp:7000 tcp:7000 >nul 2>&1");
}

static BOOL WINAPI on_close(DWORD ev) {
    /* Se llama al cerrar la ventana con la X, Ctrl+C, cerrar sesion, etc. */
    if (hwo) waveOutReset(hwo);
    system("adb forward --remove tcp:7000");
    ExitProcess(0);
    return TRUE;
}

int main(void) {
    WSADATA w;
    static char pl[BUFSZ];
    int got = 0, waiting = 0, fails = 0, waits = 0;
    WSAStartup(MAKEWORD(2, 2), &w);
    SetConsoleCtrlHandler(on_close, TRUE);
    printf("Receptor de audio del telefono\n");
    system("adb forward tcp:7000 tcp:7000");
    if (!open_audio()) { printf("No se pudo abrir la salida de audio.\n"); return 1; }
    waveOutPause(hwo);
    for (;;) {
        SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        struct sockaddr_in a;
        char hd[3];
        BOOL nd = 1;
        memset(&a, 0, sizeof a);
        a.sin_family = AF_INET;
        a.sin_port = htons(PORT);
        a.sin_addr.s_addr = inet_addr("127.0.0.1");
        if (connect(s, (struct sockaddr *)&a, sizeof a) != 0) {
            closesocket(s);
            if (fails++ % 3 == 0) ensure_forward();
            Sleep(1000);
            continue;
        }
        fails = 0;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (char *)&nd, sizeof nd);
        got = 0;
        while (recv_all(s, hd, 3)) {
            int type = (unsigned char)hd[0];
            int len = ((unsigned char)hd[1] << 8) | (unsigned char)hd[2];
            if (len > BUFSZ) break;
            if (len && !recv_all(s, pl, len)) break;
            if (!got) { got = 1; waiting = 0; printf("Conectado.\n"); reset_audio(); }
            if (type == 0) play(pl, len & ~3);
            else if (type == 1 && len >= 1) set_volume((unsigned char)pl[0]);
        }
        closesocket(s);
        if (got) {
            printf("\nDesconectado. Esperando al telefono...\n");
            waits = 0;
            ensure_forward();
        } else {
            if (!waiting) {
                printf("Esperando al telefono (abre la app AudioTx y pulsa Iniciar)...\n");
                waiting = 1;
            }
            if (waits++ % 5 == 4) ensure_forward(); /* recrea el reenvio si adb lo perdio */
        }
        Sleep(1000);
    }
    return 0;
}
