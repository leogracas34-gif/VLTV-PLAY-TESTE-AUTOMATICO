package com.vltv.play

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
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
import java.util.concurrent.TimeUnit

// ---------------------
// Modelos de Dados
// ---------------------
data class XtreamLoginResponse(val user_info: UserInfo?, val server_info: ServerInfo?)
data class UserInfo(
    val username: String?,
    val status: String?,
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
class VpnInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
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
class DnsFailoverInterceptor : Interceptor {
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

        // 2ª tentativa em diante: percorre os outros servidores da lista
        for (servidor in XtreamApi.SERVERS) {
            val servidorUrl = try { servidor.toHttpUrl() } catch (e: Exception) { continue }
            if (servidorUrl.host == hostAtual) continue

            val novaUrl = original.url.newBuilder()
                .scheme(servidorUrl.scheme)
                .host(servidorUrl.host)
                .port(servidorUrl.port)
                .build()
            val novoRequest = original.newBuilder().url(novaUrl).build()

            try {
                val response = chain.proceed(novoRequest)
                if (response.isSuccessful) {
                    // Esse DNS respondeu — vira o novo DNS ativo do app
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
}

// ---------------------
// XtreamApi
// ---------------------
object XtreamApi {

    private const val PREFS_NAME = "vltv_prefs"
    private const val PREF_DNS_KEY = "dns"

    // ✅ MESMA lista de DNS usada no login (LoginActivity). Fica aqui
    // como fonte única de verdade, pra não haver risco de duas listas
    // desatualizadas em lugares diferentes. A LoginActivity pode passar
    // a referenciar XtreamApi.SERVERS em vez de manter a própria cópia.
    // ✅ ATUALIZADO: lista sincronizada com a mesma usada em LoginActivity.kt
    // e SettingsActivity.kt. Os 6 domínios antigos que estavam aqui
    // (zeroum.pro, shozcdn.site, edgelow.site, cdtune.site,
    // radiodiamond.site, gort2.site) não existem mais — o
    // DnsFailoverInterceptor perdia tempo tentando servidores mortos antes
    // de chegar num válido. fx12.sbs foi incluído: é o único da lista com
    // nameserver fora da Cloudflare (BunnyCDN), então continua resolvível
    // mesmo numa queda total do DNS da Cloudflare.
    val SERVERS = listOf(
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
        "http://cybertronplay.space"
    )

    private val lock = Any()
    private var baseUrl: String = ""
    private var _service: XtreamService? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(buildSafeDns())
            .addInterceptor(VpnInterceptor())
            .addInterceptor(DnsFailoverInterceptor())
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun buildSafeDns(): Dns {
        return try {
            val bootstrapClient = OkHttpClient.Builder().build()
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
            Dns.SYSTEM
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
