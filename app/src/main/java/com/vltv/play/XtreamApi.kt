package com.vltv.play

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ---------------------
// Modelos de Dados
// ---------------------
data class XtreamLoginResponse(val user_info: UserInfo?, val server_info: ServerInfo?)
data class UserInfo(
    val username: String?,
    val status: String?,
    val auth: String?,
    val exp_date: String?,
    val max_connections: String?,
    val active_cons: String?,
    val created_at: String?,
    val is_trial: String?
)
data class ServerInfo(val url: String?, val port: String?, val server_protocol: String?)

data class LiveCategory(val category_id: String, val category_name: String) {
    val id: String get() = category_id
    val name: String get() = category_name
}

data class LiveStream(val stream_id: Int, val name: String, val stream_icon: String?, val epg_channel_id: String?, val category_id: String? = null) {
    val id: Int get() = stream_id
    val icon: String? get() = stream_icon
}

data class VodStream(val stream_id: Int, val name: String, val title: String?, val stream_icon: String?, val container_extension: String?, val rating: String?, val added: Long = 0, val tmdb_release_date: String? = null) {
    val id: Int get() = stream_id
    val icon: String? get() = stream_icon
    val extension: String? get() = container_extension
}

data class SeriesStream(
    val series_id: Int, val name: String, val cover: String?, val rating: String?,
    val last_modified: Long = 0,
    val tmdb_release_date: String? = null,
    val is_nova_temporada: Boolean = false,
    val is_novo_episodio: Boolean = false,
    val tmdb_proxima_temporada_data: String? = null
) {
    val id: Int get() = series_id
    val icon: String? get() = cover
}

data class EpgWrapper(val epg_listings: List<EpgResponseItem>?)
data class EpgResponseItem(val id: String?, val epg_id: String?, val title: String?, val lang: String?, val start: String?, val end: String?, val stop: String?, val description: String?, val channel_id: String?, val start_timestamp: String?, val stop_timestamp: String?)
data class SeriesInfoResponse(val episodes: Map<String, List<EpisodeStream>>?)
data class EpisodeStream(val id: String, val title: String, val container_extension: String?, val season: Int, val episode_num: Int, val info: EpisodeInfo?)
data class EpisodeInfo(val plot: String?, val duration: String?, val movie_image: String?)
data class VodInfoResponse(val info: VodInfoData?)
data class VodInfoData(val plot: String?, val genre: String?, val director: String?, val cast: String?, val releasedate: String?, val rating: String?, val movie_image: String?)

// ---------------------
// Utilitário de Plano
// ---------------------
object PlanoUtils {

    private const val MESES_VITALICIO = 15L

    data class InfoPlano(
        val nomePlano: String,
        val dataFormatada: String,
        val diasRestantes: Long,
        val isVitalicio: Boolean,
        val isExpirado: Boolean,
        // ✅ NOVO: texto pronto pra tela ("Vence em 5 meses", "Vence em 12
        // dias", "Vence hoje"...). Tem valor padrão, então nenhum lugar que
        // já cria InfoPlano(...) precisa mudar.
        val tempoRestante: String = ""
    )

    // ✅ NOVO: transforma os dias restantes em texto curto pra tela.
    private fun textoTempoRestante(dias: Long): String = when {
        dias < -1L  -> "Venceu há ${-dias} dias"
        dias < 0L   -> "Venceu há 1 dia"
        dias == 0L  -> "Vence hoje"
        dias == 1L  -> "Vence amanhã"
        dias < 30L  -> "Vence em $dias dias"
        dias / 30L == 1L -> "Vence em 1 mês"
        else        -> "Vence em ${dias / 30L} meses"
    }

    fun classificarPlano(expDateRaw: String?): InfoPlano {
        if (expDateRaw.isNullOrBlank() || expDateRaw == "0" || expDateRaw == "null") {
            return InfoPlano(
                nomePlano      = "Plano Vitalício",
                dataFormatada  = "Vitalício",
                diasRestantes  = Long.MAX_VALUE,
                isVitalicio    = true,
                isExpirado     = false,
                tempoRestante  = "Sem vencimento"
            )
        }

        return try {
            val hoje = Date()

            val expDate: Date = if (expDateRaw.all { it.isDigit() || it == '-' }) {
                Date(expDateRaw.toLong() * 1000L)
            } else {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.parse(expDateRaw) ?: return InfoPlano(
                    "Data inválida", expDateRaw, 0, false, false
                )
            }

            val diffMs      = expDate.time - hoje.time

            // ✅ CORREÇÃO (aviso de expirado não aparecia): antes era
            // TimeUnit.MILLISECONDS.toDays(diffMs), que TRUNCA em direção
            // ao zero — então uma conta que venceu há, por exemplo, 5
            // horas dava diasRestantes = 0 (e não -1), e o teste
            // "diasRestantes < 0" abaixo NUNCA considerava expirada
            // nenhuma conta vencida há menos de 24h. Como testes
            // automáticos duram poucas horas, quase todo teste expirado
            // caía nesse buraco. floorDiv arredonda pra baixo: 5h no
            // passado vira -1 (expirado), e valores positivos continuam
            // exatamente iguais aos de antes.
            val diasRestantes = diffMs.floorDiv(TimeUnit.DAYS.toMillis(1))
            val mesesRestantes = diasRestantes / 30L

            val sdfOut = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val dataFormatada = sdfOut.format(expDate)

            val nomePlano = when {
                diasRestantes < 0           -> "Plano Expirado"
                mesesRestantes > MESES_VITALICIO -> "Plano Vitalício"
                // Mais de 6 meses faltando = Anual (as duas faixas antigas
                // "> 12" e "> 6" davam o mesmo resultado, foram unidas).
                // Cliente semestral que renova e passa de 6 meses vira
                // Anual sozinho; se ficar em até 6, continua Semestral.
                mesesRestantes > 6          -> "Plano Anual"
                mesesRestantes > 3          -> "Plano Semestral"
                mesesRestantes > 1          -> "Plano Trimestral"
                else                        -> "Plano Mensal"
            }

            val isVitalicio = nomePlano == "Plano Vitalício"

            InfoPlano(
                nomePlano     = nomePlano,
                dataFormatada = if (isVitalicio) "Vitalício" else "Válido até $dataFormatada",
                diasRestantes = diasRestantes,
                isVitalicio   = isVitalicio,
                isExpirado    = diasRestantes < 0,
                tempoRestante = textoTempoRestante(diasRestantes)
            )
        } catch (e: Exception) {
            InfoPlano("Sem informação", "", 0, false, false)
        }
    }

    fun corPlano(info: InfoPlano): String = when {
        info.isExpirado  -> "#FF5252"
        info.isVitalicio -> "#FFD700"
        info.diasRestantes <= 7  -> "#FF9800"
        info.diasRestantes <= 30 -> "#FFC107"
        else             -> "#4CAF50"
    }
}

// ---------------------
// Interface Retrofit
// ---------------------
interface XtreamService {

    @GET("player_api.php")
    fun login(@Query("username") user: String, @Query("password") pass: String): Call<XtreamLoginResponse>

    @GET("player_api.php")
    fun getLiveCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getLiveStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_streams", @Query("category_id") categoryId: String): Call<List<LiveStream>>

    // ✅ NOVO: mesma action, mas SEM category_id — o provedor Xtream
    // devolve os canais de TODAS as categorias numa única resposta.
    // Usado pelo LiveTvActivity pra "adiantar" o cache de canais com
    // UMA chamada HTTP só, em vez de uma chamada por categoria (que foi
    // a causa dos travamentos no prefetch antigo).
    @GET("player_api.php")
    fun getAllLiveStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_streams"): Call<List<LiveStream>>

    @GET("player_api.php")
    fun getVodCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getVodStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_streams", @Query("category_id") categoryId: String): Call<List<VodStream>>

    @GET("player_api.php")
    fun getAllVodStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_streams"): Call<List<VodStream>>

    @GET("player_api.php")
    fun getVodInfo(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_info", @Query("vod_id") vodId: Int): Call<VodInfoResponse>

    @GET("player_api.php")
    fun getSeriesCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getSeries(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series", @Query("category_id") categoryId: String): Call<List<SeriesStream>>

    @GET("player_api.php")
    fun getAllSeries(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series"): Call<List<SeriesStream>>

    @GET("player_api.php")
    fun getSeriesInfoV2(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series_info", @Query("series_id") seriesId: Int): Call<SeriesInfoResponse>

    @GET("player_api.php")
    fun getShortEpg(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_short_epg", @Query("stream_id") streamId: String, @Query("limit") limit: Int = 2): Call<EpgWrapper>
}

// ---------------------
// Interceptor de headers
// ---------------------
// ✅ CORREÇÃO CRÍTICA (2ª causa do bug "loga mas não popula nada"): o
// User-Agent enviado aqui era um navegador INCOMPLETO — só
// "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", faltando
// "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36". Vários painéis
// Xtream (ex.: supertv.red, sivimcdn.click) têm um bloco "if" no nginx que
// libera só User-Agents de navegador/player completos e rejeita qualquer
// coisa fora do padrão com "403 Access Denied" em texto puro. O login em
// si passava (testarServidor usa outro OkHttpClient, já com UA completo),
// mas TODA chamada de conteúdo (Home, VOD, Séries, EPG) passa por este
// interceptor, dentro do okHttpClient usado pelo Retrofit — por isso a
// conta logava normalmente, mas nada era baixado depois. Trocado para o
// mesmo UA completo de Chrome já usado no login, mais Accept-Language pra
// ficar o mais parecido possível com um navegador/player real.
class VpnInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return chain.proceed(request)
    }
}

// ---------------------
// Interceptor de Failover automático de DNS
// ---------------------
// Se a chamada falhar no DNS atual (erro de rede ou resposta de erro de
// servidor), tenta os outros DNS, um por um, mantendo o mesmo caminho e
// os mesmos parâmetros (username/password/action). No primeiro que
// responder com sucesso, essa resposta é devolvida pro app normalmente e
// esse DNS passa a ser o novo "ativo" (persistido).
//
// ✅ CORREÇÃO (logout / DNS trocado sozinho): antes, qualquer servidor
// reserva que respondesse HTTP 200 era aceito — mesmo um painel onde o
// usuário NÃO existe (que responde 200 com "auth":0). Agora, antes de
// aceitar um servidor reserva, o interceptor confirma com uma chamada de
// login que o usuário/senha realmente são aceitos ali. Espelhos do mesmo
// painel continuam funcionando normalmente como failover; painéis de
// outros servidores são ignorados e nunca viram o DNS ativo.
//
// ✅ MELHORIAS desta versão:
//  1) Só dispara failover quando o erro indica problema DO SERVIDOR/DNS
//     (exceção de rede, 5xx, 403, 404, 408, 429). Antes, qualquer código
//     não-2xx (ex.: 400/401) rodava até 3 reservas à toa, deixando a
//     tela lenta sem nenhuma chance de melhorar.
//  2) Quando TODOS falham, devolve o erro ORIGINAL que já tinha ocorrido
//     em vez de repetir a requisição original uma 3ª vez (antes eram
//     até 15s de connect timeout a mais pro usuário esperar).
//  3) A validação de login do servidor reserva fica em cache por 60s —
//     várias chamadas seguidas durante uma queda não refazem o login
//     de validação a cada uma.
//  4) Quando a conexão falha com o IP que o DNS do sistema deu, avisa o
//     SmartDns pra tentar o DoH primeiro nesse domínio (cobre IP falso/
//     bloqueado pela operadora).
class DnsFailoverInterceptor : Interceptor {

    companion object {
        // Antes: percorria TODOS os servidores da lista (até ~20), um por
        // um, cada um podendo gastar o timeout inteiro de conexão. Agora
        // tenta no máximo 3 reservas, com timeout curto de conexão.
        private const val MAX_TENTATIVAS_RESERVA = 3
        private const val CONNECT_TIMEOUT_RESERVA_S = 8
        private const val VALIDACAO_TTL_MS = 60_000L
        private val REGEX_AUTH_ZERO = Regex("\"auth\"\\s*:\\s*\"?0\"?")

        // "host|usuario" -> momento em que o login foi confirmado.
        private val validados = ConcurrentHashMap<String, Long>()

        // Códigos que indicam problema do servidor/espelho (vale tentar outro).
        // 400/401 etc. são erro do pedido/conta: outro espelho não resolve.
        private fun codigoPedeFailover(code: Int): Boolean =
            code >= 500 || code == 403 || code == 404 || code == 408 || code == 429
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val hostAtual = original.url.host

        // Guardamos a 1ª falha pra devolver no fim se nenhum reserva servir.
        var falhaHttp: Response? = null
        var falhaIo: IOException? = null

        // 1ª tentativa: DNS atual
        try {
            val response = chain.proceed(original)
            if (response.isSuccessful || !codigoPedeFailover(response.code)) return response
            // ✅ CORREÇÃO (IllegalStateException "previous response is still
            // open"): o OkHttp NÃO deixa fazer outra chamada na mesma chain
            // enquanto a resposta anterior estiver aberta. Antes ela ficava
            // aberta aqui pra devolver no fim, e o primeiro reserva já
            // quebrava. Agora o corpo (página de erro, pequena) é copiado
            // pra memória, a resposta original é FECHADA, e devolvemos uma
            // cópia equivalente se nenhum reserva funcionar.
            val corpoEmMemoria = response.peekBody(1024L * 1024L)
            falhaHttp = response.newBuilder().body(corpoEmMemoria).build()
            response.close()
        } catch (e: IOException) {
            falhaIo = e
            // IP do sistema pode estar falso/bloqueado: próxima resolução
            // desse domínio tenta o DoH primeiro.
            XtreamApi.dnsSugerirDoh(hostAtual)
        }

        // ✅ Se o dns_config.json diz a qual LÂMINA (mesmo painel) o DNS
        // atual pertence, o failover tenta SÓ os DNS irmãos dessa lâmina,
        // na ordem do arquivo. Eles são o mesmo painel, então o
        // usuário/senha já vale neles — não precisa testar login em
        // painel nenhum. Se o DNS não estiver em nenhuma lâmina
        // (irmaos == null), cai no comportamento de percorrer SERVERS.
        val irmaos = DnsConfig.irmaos(hostAtual)
        val candidatos: List<String> = irmaos ?: XtreamApi.SERVERS
        val precisaValidar = irmaos == null

        var tentativasReserva = 0

        for (servidor in candidatos) {
            val servidorUrl = try { servidor.toHttpUrl() } catch (e: Exception) { continue }
            if (servidorUrl.host == hostAtual) continue
            if (precisaValidar) {
                if (tentativasReserva >= MAX_TENTATIVAS_RESERVA) break
                tentativasReserva++
            }

            val novaUrl = original.url.newBuilder()
                .scheme(servidorUrl.scheme)
                .host(servidorUrl.host)
                .port(servidorUrl.port)
                .build()

            // ✅ Só aceita servidor de OUTRA lista se ele reconhecer o
            // usuário/senha. Se não reconhecer, ignora e tenta o próximo.
            if (precisaValidar && !usuarioAceitoNoServidor(chain, original, novaUrl)) continue

            try {
                val response = chain
                    .withConnectTimeout(CONNECT_TIMEOUT_RESERVA_S, TimeUnit.SECONDS)
                    .proceed(original.newBuilder().url(novaUrl).build())
                if (response.isSuccessful) {
                    // Esse DNS respondeu E aceita o usuário — vira o novo
                    // DNS ativo do app
                    falhaHttp?.close()
                    XtreamApi.atualizarDnsAtivo(servidorUrl.toString() + "/")
                    return response
                }
                response.close()
            } catch (e: IOException) {
                if (falhaIo == null) falhaIo = e
                // tenta o próximo
            }
        }

        // Nenhum reserva respondeu — devolve o erro original (sem refazer
        // a requisição à toa).
        falhaHttp?.let { return it }
        throw falhaIo ?: IOException("Nenhum servidor respondeu")
    }

    // Faz uma chamada de login (player_api.php sem "action") no servidor
    // reserva e confirma que ele conhece o usuário. Devolve false se o
    // servidor não responde, responde erro, não devolve user_info ou
    // devolve "auth":0 (usuário inexistente naquele painel).
    private fun usuarioAceitoNoServidor(
        chain: Interceptor.Chain,
        original: Request,
        novaUrl: HttpUrl
    ): Boolean {
        val user = original.url.queryParameter("username")
        val pass = original.url.queryParameter("password")
        // Chamada sem credenciais na URL: não tem como validar, mantém
        // o comportamento antigo.
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return true

        // ✅ Cache de validação (só guarda host+usuário, nunca a senha).
        val chaveCache = "${novaUrl.host}|$user"
        val agora = System.currentTimeMillis()
        val ultimo = validados[chaveCache]
        if (ultimo != null && agora - ultimo < VALIDACAO_TTL_MS) return true

        val aceito = try {
            val urlLogin = novaUrl.newBuilder()
                .query(null)
                .addQueryParameter("username", user)
                .addQueryParameter("password", pass)
                .build()
            val reqLogin = original.newBuilder().url(urlLogin).build()

            chain
                .withConnectTimeout(CONNECT_TIMEOUT_RESERVA_S, TimeUnit.SECONDS)
                .proceed(reqLogin)
                .use { r ->
                    if (!r.isSuccessful) return@use false
                    val corpo = r.body?.string().orEmpty()
                    corpo.contains("user_info") && !REGEX_AUTH_ZERO.containsMatchIn(corpo)
                }
        } catch (e: Exception) {
            false
        }

        if (aceito) validados[chaveCache] = agora
        return aceito
    }
}

// ---------------------
// ✅ NOVO: SmartDns — resolvedor inteligente
// ---------------------
// Problema que resolve: o app resolvia TUDO só por DNS-over-HTTPS
// (dns.google / 1.1.1.1). Muitos roteadores/Wi-Fi bloqueiam ou travam o
// DoH, então a consulta falhava e o login não entrava mesmo com usuário,
// senha e DNS certos (no 4G o DoH passa, por isso funcionava lá).
//
// Ordem de resolução agora:
//   1) Cache em memória (5 min) — zero custo nas chamadas seguintes.
//   2) DNS do SISTEMA (rápido, e é o que o XCIPTV/Smart Player usam).
//   3) DoH (Google, depois Cloudflare) — só se o sistema falhar OU
//      devolver IP falso (0.0.0.0 / 127.x / link-local, que é como
//      várias operadoras "bloqueiam" um domínio).
//   4) Se tudo falhar: lembra a falha por 20s (não fica esperando timeout
//      de novo a cada tentativa) e, se existir um IP antigo guardado,
//      usa ele como último recurso ("serve-stale").
//
// Se a CONEXÃO falhar usando o IP do sistema (IP falso "público" que
// passou pelo filtro), o DnsFailoverInterceptor chama penalizarSistema():
// por 5 min esse domínio passa a tentar o DoH primeiro.
class SmartDns(private val resolvedoresDoh: List<Dns>) : Dns {

    private class Entrada(val tempo: Long, val ips: List<InetAddress>)

    companion object {
        private const val TTL_OK_MS = 5 * 60 * 1000L
        private const val TTL_FALHA_MS = 20 * 1000L
        private const val TTL_PREFERIR_DOH_MS = 5 * 60 * 1000L
    }

    private val cachePositivo = ConcurrentHashMap<String, Entrada>()
    private val cacheFalha = ConcurrentHashMap<String, Long>()
    private val preferirDoh = ConcurrentHashMap<String, Long>()

    // IP "de bloqueio" típico: 0.0.0.0, 127.x, link-local, multicast.
    // IPs privados (192.168.x etc.) continuam válidos de propósito, pra
    // não quebrar quem usa painel em rede local.
    private fun ipValido(ip: InetAddress): Boolean =
        !(ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isMulticastAddress)

    private fun ehIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    private fun viaSistema(host: String): List<InetAddress> =
        try { Dns.SYSTEM.lookup(host).filter { ipValido(it) } } catch (e: Exception) { emptyList() }

    private fun viaDoh(host: String): List<InetAddress> {
        for (resolvedor in resolvedoresDoh) {
            try {
                val lista = resolvedor.lookup(host).filter { ipValido(it) }
                if (lista.isNotEmpty()) return lista
            } catch (e: Exception) {
                // tenta o próximo resolvedor DoH
            }
        }
        return emptyList()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Host que já é um IP não precisa de resolução nem de filtro.
        if (ehIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)

        val agora = System.currentTimeMillis()

        cachePositivo[hostname]?.let { if (agora - it.tempo < TTL_OK_MS) return it.ips }

        // Falhou há pouco: não gasta timeout de novo.
        val falhouEm = cacheFalha[hostname]
        if (falhouEm != null && agora - falhouEm < TTL_FALHA_MS) {
            cachePositivo[hostname]?.let { return it.ips }
            throw UnknownHostException("DNS falhou há pouco para $hostname")
        }

        val dohPrimeiro = agora - (preferirDoh[hostname] ?: 0L) < TTL_PREFERIR_DOH_MS
        val ips = if (dohPrimeiro) {
            viaDoh(hostname).ifEmpty { viaSistema(hostname) }
        } else {
            viaSistema(hostname).ifEmpty { viaDoh(hostname) }
        }

        if (ips.isNotEmpty()) {
            cachePositivo[hostname] = Entrada(agora, ips)
            cacheFalha.remove(hostname)
            return ips
        }

        cacheFalha[hostname] = agora
        // Último recurso: IP antigo (expirado) é melhor que erro.
        cachePositivo[hostname]?.let { return it.ips }
        throw UnknownHostException("Não foi possível resolver $hostname")
    }

    // Chamado quando a conexão falhou com o IP resolvido: descarta o cache
    // desse domínio e faz a próxima resolução começar pelo DoH.
    fun penalizarSistema(hostname: String) {
        if (ehIpLiteral(hostname)) return
        cachePositivo.remove(hostname)
        cacheFalha.remove(hostname)
        preferirDoh[hostname] = System.currentTimeMillis()
    }
}

// ---------------------
// ✅ NOVO: DnsConfig — lista de DNS controlada pela VPS
// ---------------------
// A lista de servidores agora mora no arquivo dns_config.json da VPS
// (https://vltvplay.tech/dns_config.json). Pra trocar/remover/adicionar
// um DNS, basta editar esse arquivo na VPS — o app baixa a lista nova
// sozinho (ao abrir e antes de cada login), sem precisar recompilar.
//
// Ordem de prioridade da lista usada pelo app:
//   1) última lista baixada da VPS (guardada no aparelho)
//   2) FALLBACK abaixo — só vale na 1ª abertura do app sem internet ou
//      se a VPS estiver fora do ar. Mesmo assim, a lista baixada uma vez
//      continua valendo nas próximas aberturas.
object DnsConfig {

    private const val CONFIG_URL = "https://vltvplay.tech/dns_config.json"
    private const val PREFS_NAME = "vltv_dns_config"
    private const val KEY_JSON = "servers_json"
    private const val INTERVALO_MIN_MS = 60_000L

    // Lista de emergência embutida no app (mesma que está hoje na VPS).
    private val FALLBACK = listOf(
        "http://fibercdn.sbs",
        "http://ranos.sbs",
        "http://cmdtv.casa",
        "http://cmdtv.pro",
        "http://cmdtv.sbs",
        "http://cmdtv.top",
        "http://cmdbr.life",
        "http://supertv.red",
        "http://kodexk.click",
        "http://maisplaytech.space",
        "http://pthdtv.sbs",
        "http://pthdtv.top",
        "http://cdnsec.cyou",
        "http://fx12.sbs",
        "http://anotaai.lol",
        "http://brtx.beauty",
        "http://fuiali.vip",
        "http://dogshow.club",
        "http://cdnsec.click",
        "http://sivimcdn.click",
        "http://cybertronplay.space"
    )

    // Client próprio e simples (sem DoH, sem failover) — só pra baixar o
    // JSON do próprio site. Timeouts curtos pra nunca atrasar o login.
    // ✅ CORREÇÃO: User-Agent trocado pro mesmo Chrome completo usado no
    // resto do app — mesmo sendo uma chamada pro próprio servidor (VPS),
    // manter o padrão evita qualquer bloqueio por UA incompleto caso o
    // domínio vltvplay.tech passe a ter regra de UA no futuro (ex.: atrás
    // de um proxy/CDN/WAF).
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Volatile private var cache: List<String>? = null
    // ✅ NOVO: lâminas do dns_config.json (cada lâmina = lista de DNS do
    // MESMO painel). Vem de servidores[].laminas[].dns.
    @Volatile private var laminas: List<List<String>>? = null
    @Volatile private var ultimoRefreshOk = 0L

    private fun getAppContext(): Context? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) { null }
    }

    // Lê e valida o JSON.
    // ✅ CORREÇÃO: o arquivo da VPS agora é {"versao": 2, "dns": [...]}
    // e o app só lia a chave "servers" — então ignorava o arquivo novo e
    // ficava com a lista antiga embutida (FALLBACK). Agora aceita as duas
    // chaves: "dns" (formato novo) e "servers" (formato antigo).
    private fun parse(raw: String): List<String>? {
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("dns")
                ?: obj.optJSONArray("servers")
                ?: return null
            val lista = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.startsWith("http://") || s.startsWith("https://")) lista.add(s)
            }
            lista.distinct().takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    // ✅ NOVO: lê as lâminas do JSON ({"servidores":[{"laminas":[{"dns":[...]}]}]}).
    private fun parseLaminas(raw: String): List<List<String>> {
        return try {
            val servidores = JSONObject(raw).optJSONArray("servidores") ?: return emptyList()
            val out = mutableListOf<List<String>>()
            for (i in 0 until servidores.length()) {
                val lams = servidores.optJSONObject(i)?.optJSONArray("laminas") ?: continue
                for (j in 0 until lams.length()) {
                    val dnsArr = lams.optJSONObject(j)?.optJSONArray("dns") ?: continue
                    val lista = mutableListOf<String>()
                    for (k in 0 until dnsArr.length()) {
                        val u = dnsArr.optString(k, "").trim()
                        if (u.startsWith("http://") || u.startsWith("https://")) lista.add(u)
                    }
                    if (lista.isNotEmpty()) out.add(lista)
                }
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    private fun hostDe(url: String): String? =
        try { url.toHttpUrl().host } catch (e: Exception) { null }

    // ✅ NOVO: devolve os OUTROS DNS da mesma lâmina do host informado, na
    // ordem do arquivo. Devolve null se o host não está em nenhuma lâmina
    // (ou se o app ainda não tem as lâminas) — aí vale o comportamento
    // antigo do failover.
    fun irmaos(host: String): List<String>? {
        servers() // garante que a lista salva já foi carregada
        val todas = laminas ?: return null
        val alvo = host.lowercase()
        val lamina = todas.firstOrNull { l -> l.any { hostDe(it) == alvo } } ?: return null
        return lamina.filter { hostDe(it) != alvo }
    }

    // Lista atual — rápida, sem rede. Sempre devolve algo utilizável.
    fun servers(): List<String> {
        cache?.let { return it }

        val raw = try {
            getAppContext()
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_JSON, null)
        } catch (e: Exception) { null }
        val salva = raw?.let { parse(it) }

        if (salva != null) {
            // ✅ Ordem trocada: lâminas ANTES do cache. Quem lê "cache"
            // pronto e chama irmaos() agora nunca pega lâminas vazias.
            laminas = parseLaminas(raw)
            cache = salva
            return salva
        }
        return FALLBACK
    }

    // Baixa a lista da VPS. BLOQUEANTE (chamar em thread de fundo/IO).
    // Devolve true se a lista está atualizada. Se a VPS não responder ou
    // devolver algo inválido, mantém a lista que já estava valendo.
    // Não baixa de novo se já deu certo há menos de 1 minuto.
    @Synchronized
    fun refresh(context: Context, force: Boolean = false): Boolean {
        val agora = System.currentTimeMillis()
        if (!force && agora - ultimoRefreshOk < INTERVALO_MIN_MS) return true

        return try {
            val request = Request.Builder()
                .url(CONFIG_URL)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val raw = response.body?.string().orEmpty()
                val lista = parse(raw) ?: return false

                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, raw).apply()

                // ✅ lâminas antes do cache (mesmo motivo de servers())
                laminas = parseLaminas(raw)
                cache = lista
                ultimoRefreshOk = agora
                true
            }
        } catch (e: Exception) { false }
    }
}

// ---------------------
// XtreamApi
// ---------------------
object XtreamApi {

    private const val PREFS_NAME = "vltv_prefs"
    private const val PREF_DNS_KEY = "dns"

    // ✅ AGORA DINÂMICA: a lista vem do DnsConfig (arquivo dns_config.json
    // na VPS, com cópia guardada no aparelho e lista de emergência
    // embutida). Continua sendo a fonte única de verdade — LoginActivity
    // e SettingsActivity leem daqui. Pra mudar DNS, edite o arquivo na
    // VPS; não precisa mais mexer neste código.
    val SERVERS: List<String>
        get() = DnsConfig.servers()

    private val lock = Any()
    private var baseUrl: String = ""
    private var _service: XtreamService? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // ✅ OTIMIZADO: 8s (era 15s). Quando o DNS ativo está fora do
            // ar, o failover entra bem mais rápido. Se algum dia der
            // timeout em 4G muito fraco, suba de volta pra 10–12s.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(buildSafeDns())
            .addInterceptor(VpnInterceptor())
            .addInterceptor(DnsFailoverInterceptor())
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .build()
    }

    // ✅ Uma única instância compartilhada (XtreamApi + LoginActivity).
    // O LoginActivity reaproveita este mesmo resolvedor na fase de teste
    // de login (clientRapido/clientLento), sem duplicar a configuração.
    // Continua devolvendo "Dns" em buildSafeDns(), então quem já chama
    // não precisa mudar nada.
    private val safeDns: SmartDns by lazy { criarSafeDns() }

    fun buildSafeDns(): Dns = safeDns

    // ✅ Chamado pelo DnsFailoverInterceptor quando a conexão falha com o
    // IP que o DNS do sistema deu: esse domínio passa a tentar o DoH
    // primeiro por 5 min.
    fun dnsSugerirDoh(host: String) = safeDns.penalizarSistema(host)

    // Monta um resolvedor DoH. Devolve null se não conseguir montar
    // (aí o SmartDns segue só com o DNS do sistema).
    private fun criarDoh(url: String, bootstrapIps: List<String>): Dns? {
        return try {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
                .build()
        } catch (e: Exception) {
            null
        }
    }

    // ✅ DNS do sistema PRIMEIRO; DoH (Google e depois Cloudflare — se uma
    // rede bloqueia um, o outro pode passar) só como reserva. Ver SmartDns.
    private fun criarSafeDns(): SmartDns {
        val resolvedoresDoh = listOfNotNull(
            criarDoh("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
            criarDoh("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1"))
        )
        return SmartDns(resolvedoresDoh)
    }

    init {
        carregarDnsSalvo()
    }

    private fun getAppContext(): Context? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) { null }
    }

    private fun carregarDnsSalvo() {
        val context = getAppContext() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDns = prefs.getString(PREF_DNS_KEY, null)
        if (!savedDns.isNullOrBlank()) setBaseUrl(savedDns)
    }

    fun salvarDns(context: Context, dns: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_DNS_KEY, dns).apply()
        setBaseUrl(dns)
    }

    // ✅ Chamado automaticamente pelo DnsFailoverInterceptor quando um DNS
    // de reserva responde com sucesso. Persiste esse DNS como o novo
    // ativo, igual ao salvarDns, mas sem precisar de login novo.
    fun atualizarDnsAtivo(novoDns: String) {
        val context = getAppContext()
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_DNS_KEY, novoDns).apply()
        }
        setBaseUrl(novoDns)
    }

    // ✅ Monta e valida a URL antes de aplicar — evita salvar/usar uma
    // baseUrl mal formada (sem esquema, com "player_api.php" grudado,
    // sem barra final etc.), que faria toda chamada seguinte falhar
    // silenciosamente. Se a URL montada não for válida, simplesmente não
    // aplica nada e mantém a baseUrl anterior. Essa checagem é só
    // parsing local (HttpUrl.toHttpUrl()) — não faz nenhuma chamada de
    // rede, então não tem custo de performance.
    fun setBaseUrl(newUrl: String) {
        if (newUrl.isBlank()) return

        var urlClean = newUrl.trim()
        if (urlClean.contains("player_api.php")) urlClean = urlClean.substringBefore("player_api.php")
        if (!urlClean.startsWith("http://") && !urlClean.startsWith("https://")) urlClean = "http://$urlClean"
        if (!urlClean.endsWith("/")) urlClean += "/"

        val urlValida = try {
            urlClean.toHttpUrl()
            true
        } catch (e: Exception) {
            false
        }
        if (!urlValida) return

        synchronized(lock) {
            if (baseUrl != urlClean) {
                baseUrl = urlClean
                _service = null
            }
        }
    }

    val service: XtreamService
        get() = synchronized(lock) {
            _service ?: run {
                val url = baseUrl.ifBlank { "http://localhost/" }
                val newService = Retrofit.Builder()
                    .baseUrl(url)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(XtreamService::class.java)
                _service = newService
                newService
            }
        }

    // ✅ Uma instância só de Gson (criar uma por chamada é caro).
    private val gson = Gson()

    // ✅ CORREÇÃO: antes o TypeToken usava "List<T>" com T genérico, que
    // some em tempo de execução (type erasure) — o Gson devolvia uma lista
    // de mapas soltos e o parâmetro "clazz" nunca era usado. Agora o tipo
    // concreto é montado a partir do "clazz", então vem List<LiveCategory>
    // (ou o que o chamador pedir) de verdade.
    fun <T> parseCategoryList(responseBody: ResponseBody?, clazz: Class<T>): List<T>? {
        return try {
            val json = responseBody?.string() ?: return null
            val tipo = TypeToken.getParameterized(List::class.java, clazz).type
            gson.fromJson<List<T>>(json, tipo)
        } catch (e: Exception) { null }
    }

    // ✅ evita repetir o "aquecimento" pro MESMO host. Antes era um
    // boolean global: se o failover trocasse de DNS, o novo host nunca
    // era aquecido.
    @Volatile
    private var hostAquecido: String? = null

    // ✅ "aquece" a resolução de DNS do servidor ativo em segundo plano,
    // chamado assim que uma tela abre (ex.: LiveTvActivity), ANTES do
    // usuário pedir pra tocar algo. Agora passa pelo mesmo SmartDns que o
    // OkHttp usa — então o resultado já fica no cache dele (a primeira
    // chamada real não paga resolução) e, como o SmartDns consulta o DNS
    // do sistema primeiro, o cache do Android que o ExoPlayer reaproveita
    // também fica aquecido. Uma única resolução, sem tráfego de fundo.
    fun aquecerConexao() {
        val hostAlvo = try {
            baseUrl.ifBlank { null }?.toHttpUrl()?.host
        } catch (e: Exception) { null } ?: return
        if (hostAquecido == hostAlvo) return
        hostAquecido = hostAlvo

        Thread {
            try {
                safeDns.lookup(hostAlvo)
            } catch (e: Exception) {
                // Silencioso — é só uma otimização. Libera pra tentar de novo.
                hostAquecido = null
            }
        }.start()
    }
}
