package com.vltv.play

import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fala com o vltv-backend (o serviço Node.js que agora roda na VPS
 * pré-processando Top10/Novidades/selos com o TMDB).
 *
 * ⚠️ POR QUE ISSO EXISTE: os painéis Xtream bloqueiam requisições vindas
 * de IP de VPS/datacenter (defesa comum contra scraping/revenda). Por
 * isso o backend NÃO consegue baixar o catálogo cru do Xtream sozinho —
 * quem continua fazendo isso é o app, na rede residencial/móvel do
 * cliente, exatamente como já fazia antes. O app só passou a AVISAR o
 * backend do que encontrou, pra ele cruzar com o TMDB e guardar pronto
 * (Top10/Novidades/selos) — assim qualquer cliente do mesmo painel lê
 * instantâneo, sem cada celular reprocessar o próprio TMDB do zero.
 *
 * ✅ NOVO (GET /catalog): além do Top10/Novidades/selos, o backend agora
 * também guarda e devolve o CATÁLOGO INTEIRO (todos os filmes e séries
 * do painel). Isso permite que um cliente NOVO, logando num painel que
 * outro cliente já sincronizou antes, pule completamente a etapa de
 * baixar get_vod_streams/get_series do Xtream (a parte mais pesada e
 * lenta do login) — ver buscarCatalogo() e o uso em SyncManager.
 *
 * Fluxo completo:
 *  1) SyncManager tenta buscarCatalogo(dns) ANTES de ir no Xtream. Se o
 *     backend já tiver esse painel pronto, o catálogo inteiro chega
 *     pronto daqui — nenhuma chamada de get_vod_streams/get_series é
 *     feita nesse caso.
 *  2) Se buscarCatalogo() vier null (painel novo pro backend, ou backend
 *     fora do ar), o SyncManager baixa do Xtream como sempre fez, e
 *     chama enviarCatalogo(...) — silencioso: se o backend estiver fora
 *     do ar, essa chamada só falha e loga; o app continua funcionando
 *     100% como antes, com o TmdbSyncHelper local calculando os selos.
 *  3) SyncManager então chama buscarHome(dns): se o backend já tiver
 *     processado esse domínio, o resultado é aplicado nas mesmas
 *     colunas que o TmdbSyncHelper local usa (via HomeBackendSync) e o
 *     cálculo local é pulado. Se vier null, o TmdbSyncHelper local roda
 *     normalmente — nada muda pra quem ainda não tem backend.
 */
object HomeApiClient {

    // ⚠️ Preencha com a URL real do seu backend — ex.:
    // "https://api.vltvplay.tech" (com Nginx+certbot) ou
    // "http://SEU_IP:3344" (direto na porta, sem domínio) — e a MESMA
    // APP_SHARED_KEY que você colocou no .env da VPS.
    private const val BASE_URL = "http://51.222.26.119:3344"
    private const val APP_SHARED_KEY = "L468983c@"

    // O upload demora mais porque o backend PROCESSA o TMDB inteiro
    // (Top10/Novidades/Temporadas) antes de responder — dê uma folga.
    private const val UPLOAD_TIMEOUT_MS = 40_000
    private const val HOME_TIMEOUT_MS = 10_000

    // ✅ CORREÇÃO (Home lenta/incompleta no Wi-Fi): o backend é acessado
    // por IP:porta (3344), e alguns Wi-Fi bloqueiam essa porta — a
    // conexão ficava pendurada até 15-40s antes de o app desistir e usar
    // o cálculo local. Agora a conexão desiste em 4s (leitura mantém o
    // tempo longo, pois o payload é grande).
    private const val CONNECT_TIMEOUT_MS = 4_000

    // O catálogo inteiro (17 mil+ filmes, 8 mil+ séries em painéis
    // grandes) é um payload bem maior que o /home — dá mais folga de
    // timeout que as outras chamadas, mas ainda assim é só JSON puro
    // (sem processamento do lado do backend), então tende a ser rápido.
    private const val CATALOG_TIMEOUT_MS = 25_000

    // ✅ NOVO: /credits é um JSON minúsculo e roda durante a reprodução —
    // timeout curto pra nunca segurar nada se a VPS estiver lenta.
    private const val CREDITS_TIMEOUT_MS = 6_000

    data class HomeCatalogo(
        val top10FilmesRank: Map<Int, Int>,       // stream_id -> rank
        val top10SeriesRank: Map<Int, Int>,       // series_id -> rank
        // ✅ NOVO: Top 10 BRASIL — ranking oficial da Netflix por país
        // (Tudum), calculado pelo backend SEM misturar com a tendência
        // TMDB (essa mistura é o que já vira top10FilmesRank/
        // top10SeriesRank acima, que é o Top 10 "Mundial"). Mapas vazios
        // (não null) quando o backend ainda não manda "top10_brasil" —
        // assim o app antigo continua funcionando normal contra um
        // backend antigo, e o backend novo funciona normal com um app
        // que já sabe ler isso.
        val top10FilmesBrasilRank: Map<Int, Int>, // stream_id -> rank
        val top10SeriesBrasilRank: Map<Int, Int>, // series_id -> rank
        val novidadeFilmesData: Map<Int, String>, // stream_id -> release_date
        val novidadeSeriesData: Map<Int, String>, // series_id -> release_date
        val badgesSeries: List<JSONObject>        // series_id, is_nova_temporada, is_novo_episodio, datas "em breve"
    )

    /**
     * Catálogo bruto (filmes + séries) devolvido pelo GET /catalog do
     * backend. Os JSONObject de cada array têm exatamente os mesmos
     * campos que o Xtream devolve em get_vod_streams/get_series
     * (stream_id, name, stream_icon, container_extension, rating,
     * category_id, added / series_id, name, cover, rating, category_id,
     * last_modified) — por isso SyncManager consegue processar esses
     * arrays com o mesmo código que já usava pro retorno do Xtream.
     */
    data class CatalogoBackend(
        val vodArray: JSONArray,
        val seriesArray: JSONArray
    )

    /**
     * Envia o catálogo cru (já no formato que o SyncManager monta a
     * partir do Xtream) pro backend processar. NUNCA lança exceção —
     * qualquer erro é só logado (printStackTrace), pra nunca atrasar ou
     * travar a sincronização normal do app se o backend estiver
     * indisponível ou ainda não tiver sido configurado.
     *
     * ✅ CORRIGIDO: agora devolve Boolean — true SÓ se o backend recebeu o
     * catálogo (HTTP 2xx, ou o corpo foi inteiro enviado e a resposta só
     * demorou mais que o timeout porque o backend ainda estava
     * processando o TMDB). Antes devolvia Unit, então o SyncManager
     * marcava "já enviei esse painel" mesmo quando o upload falhava
     * (timeout, conexão caindo, backend fora do ar) — e nunca mais
     * tentava de novo, deixando o backend sem catálogo desse painel e
     * toda instalação nova caindo no download lento direto do Xtream.
     */
    suspend fun enviarCatalogo(dns: String, vods: List<VodEntity>, series: List<SeriesEntity>): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            var corpoEnviado = false
            try {
                val vodArray = JSONArray()
                for (v in vods) {
                    vodArray.put(JSONObject().apply {
                        put("stream_id", v.stream_id)
                        put("name", v.name)
                        put("stream_icon", v.stream_icon ?: "")
                        put("container_extension", v.container_extension ?: "")
                        put("rating", v.rating ?: "")
                        put("category_id", v.category_id ?: "")
                        put("added", v.added)
                    })
                }
                val seriesArray = JSONArray()
                for (s in series) {
                    seriesArray.put(JSONObject().apply {
                        put("series_id", s.series_id)
                        put("name", s.name)
                        put("cover", s.cover ?: "")
                        put("rating", s.rating ?: "")
                        put("category_id", s.category_id ?: "")
                        put("last_modified", s.last_modified)
                    })
                }
                val body = JSONObject().apply {
                    put("domain", dns)
                    put("vod_streams", vodArray)
                    put("series_streams", seriesArray)
                }

                conn = (URL("$BASE_URL/catalog/upload").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = UPLOAD_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-app-key", APP_SHARED_KEY)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                corpoEnviado = true
                // força a requisição a completar e lê a resposta (mesmo sem usá-la).
                // inputStream lança IOException em HTTP >= 400, caindo no catch (false).
                val codigo = conn.responseCode
                conn.inputStream.bufferedReader().use { it.readText() }
                codigo in 200..299
            } catch (e: java.net.SocketTimeoutException) {
                e.printStackTrace()
                // Se o corpo já tinha sido enviado por inteiro, o backend TEM o
                // catálogo e só estava demorando pra responder (processa o TMDB
                // antes de responder). Não vale reenviar tudo de novo.
                corpoEnviado
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Busca o CATÁLOGO INTEIRO (todos os filmes e séries, não só
     * Top10/Novidades) já salvo pelo backend pra um domínio. Retorna
     * null se o backend não conhecer esse domínio ainda, não tiver
     * catálogo processado, ou estiver indisponível — quem chamar
     * (SyncManager) deve cair de volta pro download direto do Xtream
     * nesse caso, exatamente como fazia antes desta função existir.
     */
    suspend fun buscarCatalogo(dns: String): CatalogoBackend? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val urlDomain = URLEncoder.encode(dns, "UTF-8")
            conn = (URL("$BASE_URL/catalog?domain=$urlDomain").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CATALOG_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val vodArray = json.optJSONArray("vod_streams") ?: JSONArray()
            val seriesArray = json.optJSONArray("series_streams") ?: JSONArray()

            // Painel "conhecido" pelo backend mas ainda sem nenhum item
            // (ex.: cadastrado mas nunca recebeu upload de verdade) não
            // vale a pena tratar como catálogo pronto — deixa o
            // SyncManager cair pro Xtream normalmente.
            if (vodArray.length() == 0 && seriesArray.length() == 0) return@withContext null

            CatalogoBackend(vodArray, seriesArray)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Busca o Top10/Novidades/selos já processados pelo backend pra um
     * domínio. Retorna null se o backend não tiver nada pronto ainda
     * (ex.: primeiro upload ainda não terminou de processar) ou estiver
     * indisponível — quem chamar deve manter o cálculo local
     * (TmdbSyncHelper) como fallback nesse caso — ver SyncManager.
     */
    suspend fun buscarHome(dns: String): HomeCatalogo? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val urlDomain = URLEncoder.encode(dns, "UTF-8")
            conn = (URL("$BASE_URL/home?domain=$urlDomain").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = HOME_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val top10Filmes = json.getJSONObject("top10").getJSONArray("filmes")
            val top10FilmesRank = (0 until top10Filmes.length()).associate {
                val o = top10Filmes.getJSONObject(it)
                o.getInt("stream_id") to o.getInt("rank")
            }
            val top10Series = json.getJSONObject("top10").getJSONArray("series")
            val top10SeriesRank = (0 until top10Series.length()).associate {
                val o = top10Series.getJSONObject(it)
                o.getInt("series_id") to o.getInt("rank")
            }

            // ✅ NOVO: "top10_brasil" — usa optJSONObject (não getJSONObject)
            // de propósito, pra não quebrar contra um backend antigo que
            // ainda não tenha esse campo no /home; nesse caso os mapas
            // ficam vazios e a fileira de Top 10 Brasil simplesmente fica
            // escondida na Home (ver HomeActivity.esconderTop10FilmesBrasil).
            val top10BrasilObj = json.optJSONObject("top10_brasil")
            val top10FilmesBrasilArr = top10BrasilObj?.optJSONArray("filmes") ?: JSONArray()
            val top10FilmesBrasilRank = (0 until top10FilmesBrasilArr.length()).associate {
                val o = top10FilmesBrasilArr.getJSONObject(it)
                o.getInt("stream_id") to o.getInt("rank")
            }
            val top10SeriesBrasilArr = top10BrasilObj?.optJSONArray("series") ?: JSONArray()
            val top10SeriesBrasilRank = (0 until top10SeriesBrasilArr.length()).associate {
                val o = top10SeriesBrasilArr.getJSONObject(it)
                o.getInt("series_id") to o.getInt("rank")
            }

            val novFilmes = json.getJSONObject("novidades").getJSONArray("filmes")
            val novidadeFilmesData = (0 until novFilmes.length()).associate {
                val o = novFilmes.getJSONObject(it)
                o.getInt("stream_id") to o.getString("release_date")
            }
            val novSeries = json.getJSONObject("novidades").getJSONArray("series")
            val novidadeSeriesData = (0 until novSeries.length()).associate {
                val o = novSeries.getJSONObject(it)
                o.getInt("series_id") to o.getString("release_date")
            }
            val badgesArr = json.getJSONArray("badges_series")
            val badges = (0 until badgesArr.length()).map { badgesArr.getJSONObject(it) }

            HomeCatalogo(
                top10FilmesRank, top10SeriesRank,
                top10FilmesBrasilRank, top10SeriesBrasilRank,
                novidadeFilmesData, novidadeSeriesData, badges
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * ✅ NOVO: busca na VPS o ponto em que os créditos começam numa série
     * — em SEGUNDOS RESTANTES até o fim do episódio (ex.: 72 = os
     * créditos começam quando faltam 72s). É a mediana do que os clientes
     * desse painel já marcaram (toque em "Próximo episódio" ou saída pelo
     * "voltar" perto do fim). `serie` é o id do 1º episódio da série (ver
     * PlayerActivity.serieChaveCreditos). Retorna null se ninguém ensinou
     * ainda, se a VPS estiver fora do ar ou der qualquer erro — nesse caso
     * o PlayerActivity usa o padrão de 50s, exatamente como antes.
     */
    suspend fun buscarCreditos(dns: String, serie: Int): Int? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val urlDomain = URLEncoder.encode(dns, "UTF-8")
            conn = (URL("$BASE_URL/credits?domain=$urlDomain&series=$serie").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CREDITS_TIMEOUT_MS
                readTimeout = CREDITS_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val segundos = json.optInt("remaining_sec", -1)
            if (segundos > 0) segundos else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * ✅ NOVO: manda pra VPS uma marcação do ponto dos créditos (segundos
     * restantes até o fim do episódio). Silencioso: nunca lança exceção —
     * se a VPS estiver fora do ar, só loga; o app continua normal e o
     * valor já ficou salvo no aparelho (SharedPreferences) de qualquer jeito.
     */
    suspend fun enviarCreditos(dns: String, serie: Int, restanteSeg: Int): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject().apply {
                    put("domain", dns)
                    put("series", serie)
                    put("remaining_sec", restanteSeg)
                }
                conn = (URL("$BASE_URL/credits").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CREDITS_TIMEOUT_MS
                    readTimeout = CREDITS_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-app-key", APP_SHARED_KEY)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val codigo = conn.responseCode
                conn.inputStream.bufferedReader().use { it.readText() }
                codigo in 200..299
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                conn?.disconnect()
            }
        }
}
