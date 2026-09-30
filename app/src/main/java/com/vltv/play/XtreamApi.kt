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
        val isExpirado: Boolean
    )

    fun classificarPlano(expDateRaw: String?): InfoPlano {
        if (expDateRaw.isNullOrBlank() || expDateRaw == "0" || expDateRaw == "null") {
            return InfoPlano(
                nomePlano      = "Plano Vitalício",
                dataFormatada  = "Vitalício",
                diasRestantes  = Long.MAX_VALUE,
                isVitalicio    = true,
                isExpirado     = false
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
                mesesRestantes > 12         -> "Plano Anual"
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
                isExpirado    = diasRestantes < 0
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
// Se a chamada falhar no DNS atual (erro de rede ou resposta não-OK),
// tenta os outros DNS da lista XtreamApi.SERVERS, um por um, mantendo o
// mesmo caminho e os mesmos parâmetros (username/password/action). No
// primeiro que responder com sucesso, essa resposta é devolvida pro app
// normalmente e esse DNS passa a ser o novo "ativo" (persistido).
//
// ✅ CORREÇÃO (logout / DNS trocado sozinho): antes, qualquer servidor
// reserva que respondesse HTTP 200 era aceito — mesmo um painel onde o
// usuário NÃO existe (que responde 200 com "auth":0). Resultado: se o
// seu servidor ficasse fora do ar por alguns instantes, o app pulava pra
// outro painel, recebia "auth":0, gravava esse painel errado como DNS
// ativo e tratava a conta como inválida. Agora, antes de aceitar um
// servidor reserva, o interceptor confirma com uma chamada de login que
// o usuário/senha realmente são aceitos ali. Espelhos do mesmo painel
// continuam funcionando normalmente como failover; painéis de outros
// servidores são ignorados e nunca viram o DNS ativo.
class DnsFailoverInterceptor : Interceptor {

    companion object {
        // Antes: percorria TODOS os servidores da lista (até ~20), um por
        // um, cada um podendo gastar o timeout inteiro de conexão. Agora
        // tenta no máximo 3 reservas, com timeout curto de conexão.
        private const val MAX_TENTATIVAS_RESERVA = 3
        private const val CONNECT_TIMEOUT_RESERVA_S = 8
        private val REGEX_AUTH_ZERO = Regex("\"auth\"\\s*:\\s*\"?0\"?")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // 1ª tentativa: DNS atual
        try {
            val response = chain.proceed(original)
            if (response.isSuccessful) return response
            response.close()
        } catch (e: IOException) {
            // segue pro failover
        }

        val hostAtual = original.url.host
        var tentativasReserva = 0

        // 2ª tentativa em diante: percorre os outros servidores da lista
        for (servidor in XtreamApi.SERVERS) {
            val servidorUrl = try { servidor.toHttpUrl() } catch (e: Exception) { continue }
            if (servidorUrl.host == hostAtual) continue
            if (tentativasReserva >= MAX_TENTATIVAS_RESERVA) break
            tentativasReserva++

            val novaUrl = original.url.newBuilder()
                .scheme(servidorUrl.scheme)
                .host(servidorUrl.host)
                .port(servidorUrl.port)
                .build()

            // ✅ Só aceita este servidor reserva se ele reconhecer o
            // usuário/senha. Se não reconhecer, ignora e tenta o próximo.
            if (!usuarioAceitoNoServidor(chain, original, novaUrl)) continue

            val novoRequest = original.newBuilder().url(novaUrl).build()

            try {
                val response = chain
                    .withConnectTimeout(CONNECT_TIMEOUT_RESERVA_S, TimeUnit.SECONDS)
                    .proceed(novoRequest)
                if (response.isSuccessful) {
                    // Esse DNS respondeu E aceita o usuário — vira o novo
                    // DNS ativo do app
                    XtreamApi.atualizarDnsAtivo(servidorUrl.toString() + "/")
                    return response
                }
                response.close()
            } catch (e: IOException) {
                // tenta o próximo
            }
        }

        // Nenhum DNS respondeu — deixa o erro original estourar normalmente
        return chain.proceed(original)
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

        return try {
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
    @Volatile private var ultimoRefreshOk = 0L

    private fun getAppContext(): Context? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) { null }
    }

    // Lê e valida o JSON: {"servers": ["http://...", ...]}
    private fun parse(raw: String): List<String>? {
        return try {
            val arr = JSONObject(raw).optJSONArray("servers") ?: return null
            val lista = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.startsWith("http://") || s.startsWith("https://")) lista.add(s)
            }
            lista.distinct().takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    // Lista atual — rápida, sem rede. Sempre devolve algo utilizável.
    fun servers(): List<String> {
        cache?.let { return it }

        val salva = try {
            getAppContext()
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_JSON, null)
                ?.let { parse(it) }
        } catch (e: Exception) { null }

        if (salva != null) {
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(buildSafeDns())
            .addInterceptor(VpnInterceptor())
            .addInterceptor(DnsFailoverInterceptor())
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .build()
    }

    // ✅ NOVO: não é mais "private" — o LoginActivity agora reaproveita
    // este mesmo resolvedor DNS-over-HTTPS na fase de teste de login
    // (clientRapido/clientLento), pra contornar bloqueio de DNS feito
    // pela operadora em domínios específicos (ex.: supertv.red,
    // sivimcdn.click), sem duplicar a configuração do DoH em dois
    // lugares diferentes.
    // ✅ Uma única instância compartilhada (XtreamApi + LoginActivity).
    private val safeDns: Dns by lazy { criarSafeDns() }

    fun buildSafeDns(): Dns = safeDns

    // ✅ DoH continua sendo usado (contorna bloqueio de DNS da operadora),
    // mas agora com CACHE em memória de 5 min por domínio. Antes cada nova
    // conexão refazia a consulta HTTPS ao dns.google (sem cache nenhum),
    // coisa que XCIPTV/Smart Player não fazem. O cliente de bootstrap
    // também ganhou timeouts curtos (5s) pra uma consulta lenta não
    // segurar a conexão por 10s.
    private fun criarSafeDns(): Dns {
        val doh: Dns = try {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    listOf(
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("1.1.1.1")
                    )
                )
                .build()
        } catch (e: Exception) {
            return Dns.SYSTEM
        }

        val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
        val ttlMs = 5 * 60 * 1000L

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val agora = System.currentTimeMillis()
                val emCache = cache[hostname]
                if (emCache != null && agora - emCache.first < ttlMs) return emCache.second

                val lista = doh.lookup(hostname)
                if (lista.isNotEmpty()) cache[hostname] = agora to lista
                return lista
            }
        }
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

    fun <T> parseCategoryList(responseBody: ResponseBody?, clazz: Class<T>): List<T>? {
        return try {
            val json = responseBody?.string() ?: return null
            Gson().fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
        } catch (e: Exception) { null }
    }

    // ✅ evita repetir o "aquecimento" de conexão várias vezes seguidas.
    @Volatile
    private var dnsAquecido = false

    // ✅ "aquece" a resolução de DNS do servidor ativo em segundo plano,
    // chamado assim que uma tela abre (ex.: LiveTvActivity), ANTES do
    // usuário pedir pra tocar algo. Usa só o resolvedor do sistema
    // (InetAddress.getByName) — uma única resolução, sem disparar nada
    // em paralelo e sem chamada HTTPS extra pra um servidor de DoH. É
    // exatamente o mesmo caminho de DNS que o ExoPlayer vai reaproveitar
    // na hora de conectar no vídeo, então isso tira só a resolução de
    // DNS do caminho crítico do primeiro play, sem gerar tráfego de
    // fundo continuado.
    fun aquecerConexao() {
        if (dnsAquecido) return
        val hostAlvo = try {
            baseUrl.ifBlank { null }?.toHttpUrl()?.host
        } catch (e: Exception) { null } ?: return

        Thread {
            try {
                InetAddress.getByName(hostAlvo)
                dnsAquecido = true
            } catch (e: Exception) {
                // Silencioso — é só uma otimização.
            }
        }.start()
    }
}
