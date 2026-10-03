package com.vltv.play

/**
 * Configuração de rede compartilhada pelas telas de reprodução.
 *
 * ATENÇÃO sobre User-Agent: o app usa DOIS de propósito.
 *  - STREAM_USER_AGENT: usado em quem BAIXA VÍDEO (PlayerActivity,
 *    LiveTvActivity e downloads em VltvDownloadTracker).
 *  - As chamadas de API (XtreamApi/VpnInterceptor) usam um Chrome completo,
 *    porque vários painéis rejeitam UA incompleto com 403 nessas rotas.
 * Não troque um pelo outro sem testar nos painéis.
 */
object NetworkConfig {
    const val STREAM_USER_AGENT = "IPTVSmartersPro"
    const val USER_AGENT = STREAM_USER_AGENT

    // Timeouts menores = failover mais rápido entre servidor/extensão
    // quando um DNS não responde (antes: 12s de espera por tentativa).
    const val CONNECT_TIMEOUT_MS = 8000
    const val READ_TIMEOUT_MS = 15000
}
