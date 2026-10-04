package com.vltv.play

import android.content.Context
import android.util.Log
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SyncManager — controla a sincronização de conteúdo com o servidor Xtream.
 *
 * ✅ CORREÇÃO (tela abre com nome antigo, só atualiza depois de fechar e
 * reabrir): a sincronização inicial (sincronizarSeNecessario) atualizava o
 * banco e o ContentRepository com os nomes oficiais vindos do TMDB
 * (TmdbSyncHelper.sincronizar), mas NUNCA avisava a Home que isso tinha
 * acontecido. O único aviso existente (notificarOuvintes) só disparava na
 * sincronização PERIÓDICA (a cada 10 min) e só quando a CONTAGEM de itens
 * mudava — só nome/logo mudando não contava. Por isso a tela só aparecia
 * atualizada depois de fechar e abrir o app de novo (quando
 * ContentRepository.preCarregar() rodava de novo já com o banco
 * atualizado). Agora, ao final da sincronização inicial, notificarOuvintes()
 * é chamado incondicionalmente — a Home se atualiza sozinha assim que os
 * nomes/logos oficiais chegarem, sem precisar fechar/reabrir o app.
 *
 * ✅ NOVO (backend / Opção B): antes de baixar QUALQUER coisa do Xtream,
 * o app agora pergunta pro vltv-backend via HomeApiClient.buscarCatalogo()
 * se ele já tem o catálogo inteiro (filmes + séries) desse domínio salvo
 * de uma sincronização anterior de outro cliente. Se tiver, o app usa
 * esse catálogo pronto direto — pulando completamente as chamadas
 * get_vod_streams/get_series no Xtream, que são as mais pesadas do
 * login. Só a lista de canais AO VIVO continua vindo sempre do Xtream
 * (o backend não guarda isso). Se o backend não tiver nada pra esse
 * domínio ainda (painel novo) ou estiver fora do ar, o app cai
 * automaticamente pro caminho de sempre: baixa do Xtream e, ao final,
 * ainda manda esse catálogo pro backend via enviarCatalogo() — pra que
 * o PRÓXIMO cliente desse mesmo painel já encontre tudo pronto.
 *
 * ✅ CORRIGIDO (Top10/Top10 Brasil/Novidade saindo zerados quando o
 * catálogo vem pronto do backend, numa instalação nova): sincronizarVod/
 * sincronizarSeries montavam esses campos calculados SEMPRE a partir do
 * que já existia localmente (vodsExistentes/seriesExistentes) — numa
 * instalação nova esse mapa está vazio, então tudo saía 0/null mesmo
 * quando o JSON vindo do backend (GET /catalog) já trazia os valores
 * prontos. Só reapareciam depois, quando buscarHome()+HomeBackendSync
 * terminassem de rodar por cima — daí o atraso perceptível na 1ª
 * abertura. Agora esses campos são lidos do PRÓPRIO JSON quando a chave
 * existe nele (é o caso do catálogo do backend); só caem pro que já
 * existia localmente quando a chave não existe no JSON (é o caso do
 * retorno cru do Xtream, que nunca tem esses campos calculados).
 *
 * Depois disso, como já acontecia: se o backend responder com
 * Top10/Novidades/selos prontos (HomeApiClient.buscarHome), o app aplica
 * ele direto (HomeBackendSync) e PULA o cálculo local — economiza
 * rede/bateria/tempo no celular. Se o backend estiver fora do ar, ainda
 * não tiver sido configurado, ou ainda não tiver processado esse
 * domínio, o app cai automaticamente de volta pro cálculo local do
 * TmdbSyncHelper — funciona exatamente como antes.
 *
 * PROBLEMA QUE RESOLVE (arquitetura original):
 * Antes, HomeActivity.onResume() chamava sincronizarConteudoSilenciosamente()
 * toda vez que o usuário voltava para a Home. Isso disparava downloads de
 * listas inteiras e travamentos de 15-30s. A solução usa Mutex real (não
 * Boolean solto), escopo próprio (sobrevive à troca de Activity, mas morre
 * com o processo), e uma flag de sessão que bloqueia novas sincronizações
 * completas até o app ser reaberto de verdade.
 */
object SyncManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var jaSincronizouNestaSessao = false

    @Volatile
    private var jobAtual: Job? = null

    // ── Sync periódica leve ───────────────────────────────────────────────────
    private val PERIODIC_INTERVAL_MS = 10 * 60 * 1000L // 10 minutos

    // ✅ NOVO: o download COMPLETO do catálogo (get_vod_streams +
    // get_series + canais ao vivo = dezenas de milhares de itens) agora só
    // acontece de novo depois desta janela, ou quando o banco está vazio,
    // ou quando o usuário força ("Atualizar" nas configurações). Antes
    // rodava em TODA abertura do app e a cada 10 minutos, o que deixava a
    // Home lenta em painel/rede lentos e martelava os painéis (que podem
    // banir o IP por excesso de requisições pesadas). Dentro da janela, a
    // sincronização continua rodando a parte LEVE (Top10/Novidades/selos
    // do backend).
    private const val JANELA_SYNC_COMPLETA_MS = 3 * 60 * 60 * 1000L // 3 horas

    @Volatile
    private var periodicJob: Job? = null

    @Volatile
    private var periodicoIniciado = false

    private val ouvintesNovidade = mutableListOf<() -> Unit>()

    // ✅ NOVO (evita "piscar" a tela várias vezes seguidas): o
    // TmdbSyncHelper avisa a Home ao final de CADA fase (Top10, Novidades,
    // Temporada/Episódio) — em catálogos grandes essas 3 fases + o aviso
    // final da sincronização inicial podiam disparar 4 redesenhos da tela
    // em poucos segundos, dando a sensação de app travando/piscando. Agora
    // os avisos passam por um debounce: se chegar outro aviso em menos de
    // 400ms, o redesenho anterior é cancelado e só o mais recente
    // acontece — várias fases terminando perto uma da outra viram UM único
    // redesenho, sem atrasar avisos que já vêm espaçados (sync periódica).
    @Volatile
    private var debounceJob: Job? = null
    private const val DEBOUNCE_NOTIFICACAO_MS = 400L

    /**
     * Registra um callback chamado (na Main thread) sempre que:
     *   a) a sincronização INICIAL terminar (nomes/logos oficiais do TMDB
     *      já aplicados), ou
     *   b) a sincronização periódica detectar itens novos no servidor.
     * Retorna uma função para remover o listener — chame no onDestroy().
     */
    fun registrarOuvinteNovidade(callback: () -> Unit): () -> Unit {
        ouvintesNovidade.add(callback)
        return { ouvintesNovidade.remove(callback) }
    }

    /**
     * ✅ NOVO: chamado pelo TmdbSyncHelper ao final de CADA fase da
     * sincronização (Top10, Novidades, Temporada/Episódio) — permite que
     * a Home atualize os selos progressivamente, fase por fase, em vez
     * de esperar a sincronização inteira terminar (que podia levar 1-2
     * min) pra só então mostrar qualquer selo.
     */
    fun notificarProgressoParcial() {
        notificarOuvintes()
    }

    private fun notificarOuvintes() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            kotlinx.coroutines.delay(DEBOUNCE_NOTIFICACAO_MS)
            withContext(Dispatchers.Main) {
                ouvintesNovidade.toList().forEach { it.invoke() }
            }
        }
    }

    fun iniciarSyncPeriodica(context: Context) {
        if (periodicoIniciado) return
        periodicoIniciado = true

        periodicJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PERIODIC_INTERVAL_MS)
                executarSyncPeriodicaLeve(context)
            }
        }
    }

    fun pararSyncPeriodica() {
        periodicJob?.cancel()
        periodicJob = null
        periodicoIniciado = false
    }

    private suspend fun executarSyncPeriodicaLeve(context: Context) {
        mutex.withLock {
            val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val dns = prefs.getString("dns", "") ?: ""
            val user = prefs.getString("username", "") ?: ""
            val pass = prefs.getString("password", "") ?: ""
            if (dns.isEmpty() || user.isEmpty()) return@withLock

            val db = AppDatabase.getDatabase(context)
            val contagemVodAntes = try { db.streamDao().getVodCount() } catch (e: Exception) { -1 }

            try {
                executarSincronizacao(context, dns, user, pass)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withLock
            }

            val contagemVodDepois = try { db.streamDao().getVodCount() } catch (e: Exception) { -1 }

            if (contagemVodAntes != -1 && contagemVodDepois != -1 && contagemVodAntes != contagemVodDepois) {
                notificarOuvintes()
            }
        }
    }

    /**
     * Ponto de entrada único. Chame isso de onCreate() ou onResume() da Home
     * sem medo — é idempotente. Só a primeira chamada por sessão do processo
     * realmente dispara a sincronização; as demais retornam imediatamente.
     */
    fun sincronizarSeNecessario(context: Context) {
        if (jaSincronizouNestaSessao) return

        scope.launch {
            mutex.withLock {
                if (jaSincronizouNestaSessao) return@withLock

                val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (dns.isEmpty() || user.isEmpty()) return@withLock

                try {
                    executarSincronizacao(context, dns, user, pass)
                } finally {
                    jaSincronizouNestaSessao = true
                    // ✅ NOVO: avisa quem estiver ouvindo (a Home) que a
                    // sincronização inicial — já com nomes/logos oficiais do
                    // TMDB aplicados — terminou. Sem isso, a tela só refletia
                    // essa atualização na próxima vez que o app fosse aberto
                    // do zero (quando ContentRepository.preCarregar() rodava
                    // de novo já com o banco atualizado).
                    notificarOuvintes()
                }
            }
        }
    }

    /**
     * Força uma nova sincronização mesmo que já tenha rodado nesta sessão.
     * Use apenas em ações explícitas do usuário (ex: botão "Atualizar" nas
     * configurações), nunca em ciclo de vida automático de Activity.
     */
    fun forcarResincronizacao(context: Context, onConcluido: (() -> Unit)? = null) {
        jobAtual?.cancel()
        jobAtual = scope.launch {
            mutex.withLock {
                val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (dns.isEmpty() || user.isEmpty()) return@withLock

                try {
                    executarSincronizacao(context, dns, user, pass, forcar = true)
                } finally {
                    jaSincronizouNestaSessao = true
                    notificarOuvintes()
                    withContext(Dispatchers.Main) { onConcluido?.invoke() }
                }
            }
        }
    }

    /** Reseta o estado — chamar apenas no logout, para a próxima sessão sincronizar de novo. */
    fun resetarSessao() {
        jobAtual?.cancel()
        debounceJob?.cancel()
        jaSincronizouNestaSessao = false
        pararSyncPeriodica()
        ouvintesNovidade.clear()
    }

    private suspend fun executarSincronizacao(context: Context, dnsRaw: String, user: String, pass: String, forcar: Boolean = false) {
        val dns = dnsRaw
        val db = AppDatabase.getDatabase(context)
        val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")
        val t0 = System.currentTimeMillis()
        // DIAGNÓSTICO (silencioso): junta os tempos de cada etapa da 1ª
        // sincronização da sessão e registra UMA linha no Logcat no fim (ex.:
        // "SYNC: catalogo 0.3s | baixou 41s | home 44s ..."). Não aparece
        // nada na tela do aplicativo.
        val primeiraDaSessao = !jaSincronizouNestaSessao
        val marcas = mutableListOf<String>()
        fun logTempo(etapa: String, rotulo: String? = null) {
            val ms = System.currentTimeMillis() - t0
            Log.d("SyncManager", "⏱ $etapa: ${ms}ms desde o início da sincronização")
            if (rotulo != null) marcas.add("$rotulo ${"%.1f".format(ms / 1000.0)}s")
        }

        // ✅ NOVO: a lista de canais AO VIVO roda em paralelo, mas FORA do
        // caminho crítico da Home (ver comentário mais abaixo).
        var liveJob: kotlinx.coroutines.Deferred<*>? = null

        try {
            // ⚠️ CORREÇÃO (selos somem sozinhos / só aparecem depois de
            // reinstalar): insertVodStreams/insertSeriesStreams usam
            // OnConflictStrategy.REPLACE, que substitui a LINHA INTEIRA no
            // banco — inclusive as colunas calculadas pelo TmdbSyncHelper
            // (logo_url, is_top10, is_novidade, is_nova_temporada etc.).
            // Como esta função roda a cada 10 minutos (sync periódica) E
            // reconstruía cada item do zero só com os campos crus do
            // Xtream (name, cover, rating...), toda sincronização apagava
            // os selos — e eles só voltavam depois que o TmdbSyncHelper,
            // mais lento, terminasse de recalcular tudo de novo (por isso
            // "aparece de vez em quando": é uma corrida entre o apagão e o
            // recálculo, repetida a cada ciclo). Agora, antes de montar a
            // lista nova, carregamos o que já existe no banco e
            // preservamos essas colunas calculadas — só os campos que
            // realmente vêm do Xtream são atualizados.
            val vodsExistentes = try { db.streamDao().getAllVods().associateBy { it.stream_id } } catch (e: Exception) { emptyMap() }
            val seriesExistentes = try { db.streamDao().getAllSeries().associateBy { it.series_id } } catch (e: Exception) { emptyMap() }
            logTempo("leitura local (vodsExistentes/seriesExistentes)")

            // ✅ CORRIGIDO (Home demorando 6-7s pra aparecer ao reabrir o
            // app, quando antes era quase instantâneo): buscarCatalogo()
            // baixa o CATÁLOGO INTEIRO do backend (17 mil+ filmes, 8 mil+
            // séries em painéis grandes) — só faz sentido pra um painel
            // que ESTE aparelho ainda não tem local (instalação nova, ou
            // troca de conta pra um painel nunca sincronizado aqui). Antes
            // dessa correção, essa chamada de rede pesada rodava TODA VEZ
            // que o app abria do zero (jaSincronizouNestaSessao reseta a
            // cada processo novo), mesmo com o Room já 100% populado de
            // uma sessão anterior — sempre pra descartar o resultado
            // (vodsExistentes/seriesExistentes já preservam tudo do jeito
            // que o app sempre fez, então o catálogo baixado nem era
            // realmente necessário nesse caso, só ficava competindo com
            // as outras chamadas na inicialização). Agora só pergunta pro
            // backend quando o Room LOCAL ainda estiver vazio — o resto do
            // fluxo (get_vod_streams/get_series direto no Xtream) continua
            // exatamente como sempre funcionou pra quem já tem o catálogo.
            //
            // ✅ CORRIGIDO (Home vazia/pela metade + Filmes/Séries levando ~1,5
            // min pra popular na 1ª abertura): a condição antiga era só
            // "Room vazio". Mas o LoginActivity.preCarregarLoteMinimo() grava
            // 12 filmes + 12 séries no Room ANTES da Home abrir — então o Room
            // nunca estava vazio aqui, o catálogo pronto da VPS era PULADO e
            // o app caía no download pesado direto do Xtream (get_vod_streams/
            // get_series). Agora o critério é "este aparelho ainda NUNCA
            // completou uma sincronização completa pra esse usuário"
            // (ultimaCompleta == 0, mesma chave que o pularDownloadCompleto já
            // usa) — os 12 itens do login não contam como catálogo.
            // Quem já tem catálogo completo e recente continua exatamente
            // como antes (não baixa nada pesado da VPS toda abertura).
            val prefsEstado = context.getSharedPreferences("vltv_sync_state", Context.MODE_PRIVATE)
            val chaveUltimaCompleta = "ultima_sync_completa_" + user
            val ultimaCompleta = prefsEstado.getLong(chaveUltimaCompleta, 0L)
            val localSemCatalogoCompleto =
                (vodsExistentes.isEmpty() && seriesExistentes.isEmpty()) || ultimaCompleta == 0L

            // ✅ NOVO (abertura "instantânea" na 1ª instalação): enquanto o
            // catálogo COMPLETO da VPS (~12 MB) baixa logo abaixo, pede um
            // PACOTE INICIAL pequeno (GET /catalog/inicial: os itens mais
            // recentes de cada categoria + tudo que tem selo) e já grava no
            // Room + memória, avisando a Home. Roda em PARALELO com o
            // buscarCatalogo e é esperado (com teto de 10s) antes de gravar o
            // catálogo completo — assim as duas gravações nunca se
            // atropelam. Se a VPS não responder, retorna sem fazer nada e o
            // fluxo segue exatamente como antes.
            val inicialJob = if (localSemCatalogoCompleto) {
                scope.async {
                    try {
                        withTimeoutOrNull(10_000L) {
                            aplicarPacoteInicial(db, dns, palavrasProibidas, vodsExistentes, seriesExistentes)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else null

            var catalogoBackend: HomeApiClient.CatalogoBackend? = null
            if (localSemCatalogoCompleto) {
                try {
                    catalogoBackend = HomeApiClient.buscarCatalogo(dns)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            logTempo("buscarCatalogo (catálogo pronto do backend, só quando local está vazio)", if (catalogoBackend != null) "catalogo-backend" else "catalogo-sem-backend")

            // ✅ CORREÇÃO (demora de 1-2 min pra aparecer o catálogo
            // correto na 1ª instalação): VOD, Séries e Live eram buscados
            // do Xtream em SEQUÊNCIA — cada `URL(...).readText()` é uma
            // chamada de rede bloqueante, e num catálogo grande a soma das
            // três (VOD + Séries + Live) facilmente passava de 1 minuto
            // sozinha, antes mesmo do TMDB entrar em ação. Agora as três
            // rodam em PARALELO com coroutineScope/async — o tempo total
            // passa a ser o da mais lenta das três, não a soma delas.
            // Canais AO VIVO continuam vindo sempre do Xtream — o backend
            // não guarda essa lista.
            val vodsCompletos: List<VodEntity>
            val seriesCompletos: List<SeriesEntity>

            // ✅ CORRIGIDO (abas "Filmes para você", "Séries para você" e
            // "Novidades" demorando muito na 1ª instalação): a Home só era
            // avisada quando a sincronização INTEIRA terminava — e a
            // sincronização inteira esperava a lista de canais AO VIVO
            // (baixada direto do Xtream, sem cache do backend, sem
            // timeout), que passou a ser a etapa mais lenta depois que o
            // catálogo de filmes/séries começou a vir pronto do backend.
            // Agora:
            //  1) Live roda em paralelo mas isolada (scope.async com
            //     SupervisorJob): não segura mais o resto, e se falhar não
            //     derruba a sincronização de filmes/séries.
            //  2) Assim que filmes e séries estão gravados, a Home é
            //     avisada NA HORA (as 3 fileiras leem exatamente isso).
            //  3) Só no fim esperamos o Live terminar.
            // (prefsEstado / chaveUltimaCompleta / ultimaCompleta já foram
            // lidos lá em cima, antes do buscarCatalogo — mesma leitura.)
            val pularDownloadCompleto = !forcar &&
                catalogoBackend == null &&
                vodsExistentes.isNotEmpty() && seriesExistentes.isNotEmpty() &&
                System.currentTimeMillis() - ultimaCompleta in 0L until JANELA_SYNC_COMPLETA_MS

            // O pacote inicial precisa terminar de gravar antes do catálogo
            // completo (que o sobrescreve com a versão inteira).
            try { inicialJob?.await() } catch (e: Exception) { e.printStackTrace() }

            if (pularDownloadCompleto) {
                // Banco local já está completo e recente: reaproveita e
                // vai direto pra parte leve (Top10/Novidades/selos).
                vodsCompletos = vodsExistentes.values.toList()
                seriesCompletos = seriesExistentes.values.toList()
                logTempo("Download completo PULADO (última sync completa há menos de 3h)", "download-pulado")
            } else {
                liveJob = scope.async { sincronizarLive(db, dns, user, pass) }

                // ✅ CORRIGIDO: filmes e séries agora são independentes —
                // se um download falhar (painel lento/timeout), o outro
                // continua e o que já estava no banco é mantido, em vez de
                // abortar a sincronização inteira e deixar a Home vazia.
                var baixouTudo = true
                coroutineScope {
                    val vodJob = async {
                        try {
                            val vodArray = catalogoBackend?.vodArray
                                ?: buscarArrayXtream(dns, user, pass, "get_vod_streams")
                            sincronizarVod(db, vodArray, palavrasProibidas, vodsExistentes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                            baixouTudo = false
                            vodsExistentes.values.toList()
                        }
                    }
                    val seriesJob = async {
                        try {
                            val seriesArray = catalogoBackend?.seriesArray
                                ?: buscarArrayXtream(dns, user, pass, "get_series")
                            sincronizarSeries(db, seriesArray, palavrasProibidas, seriesExistentes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                            baixouTudo = false
                            seriesExistentes.values.toList()
                        }
                    }

                    vodsCompletos = vodJob.await()
                    seriesCompletos = seriesJob.await()
                }
                if (baixouTudo) {
                    prefsEstado.edit().putLong(chaveUltimaCompleta, System.currentTimeMillis()).apply()
                }
                logTempo("VOD + Séries (Xtream ou catálogo do backend, em paralelo)", "baixou-filmes+series")
            }

            // ✅ CORREÇÃO PRINCIPAL (Home mostrando só 2 filmes/2 séries até
            // fechar e abrir o app de novo): a Home lê a lista da MEMÓRIA
            // (ContentRepository), e a memória só era atualizada no FIM de
            // tudo — depois do upload pro backend (até 40s), do buscarHome
            // e, se o backend não respondia, do cálculo local do TMDB
            // (minutos). Enquanto isso o aviso abaixo fazia a Home reler a
            // memória ainda velha (os ~12 itens do pré-carregamento do
            // login). Agora a memória é atualizada AQUI, assim que filmes
            // e séries estão prontos; selos/Top10 chegam depois.
            if (vodsCompletos.isNotEmpty()) ContentRepository.atualizarVods(vodsCompletos)
            if (seriesCompletos.isNotEmpty()) ContentRepository.atualizarSeries(seriesCompletos)
            notificarOuvintes()

            // ── TMDB (nomes oficiais + top10/novidades) ─────────────────────
            // ⚠️ CORRIGIDO: antes, "já enviei o catálogo pra esse domínio"
            // dependia de catalogoBackend != null — mas catalogoBackend só
            // é buscado agora quando o Room local está vazio (ver correção
            // acima). Pra um aparelho que JÁ sincronizou antes,
            // catalogoBackend sempre vem null, então "if (catalogoBackend
            // == null) enviarCatalogo(...)" reenviaria o catálogo INTEIRO
            // pro backend EM TODA ABERTURA do app — um upload pesado (até
            // 40s de timeout) que nem existia antes do backend, e que
            // seria uma causa de lentidão pior do que a que estamos
            // corrigindo. Agora o controle de "já mandei esse domínio pro
            // backend" é uma flag local por domínio (SharedPreferences),
            // marcada só depois de um enviarCatalogo() bem-sucedido —
            // então o upload acontece de verdade só a primeira vez que
            // ESTE app encontra um painel que o backend ainda não conhece,
            // exatamente como o comentário original pretendia.
            val prefsBackend = context.getSharedPreferences("vltv_backend_sync", Context.MODE_PRIVATE)
            val chaveJaEnviou = "catalogo_enviado_" + dns.hashCode()
            val jaEnviouCatalogoAntes = prefsBackend.getBoolean(chaveJaEnviou, false)

            var aplicadoPeloBackend = false
            try {
                // ✅ CORRIGIDO: se o upload falhou há pouco tempo, NÃO tenta
                // de novo em toda abertura do app (cada tentativa podia
                // segurar a Home por até 40s antes do buscarHome). Só
                // volta a tentar depois de 6 horas.
                val chaveUltimaTentativa = "ultima_tentativa_envio_" + dns.hashCode()
                val ultimaTentativa = prefsBackend.getLong(chaveUltimaTentativa, 0L)
                val podeTentarEnvio = System.currentTimeMillis() - ultimaTentativa > 6 * 60 * 60 * 1000L

                fun temDados(h: HomeApiClient.HomeCatalogo): Boolean =
                    h.top10FilmesRank.isNotEmpty() || h.top10SeriesRank.isNotEmpty() ||
                    h.top10FilmesBrasilRank.isNotEmpty() || h.top10SeriesBrasilRank.isNotEmpty() ||
                    h.novidadeFilmesData.isNotEmpty() || h.novidadeSeriesData.isNotEmpty()

                var resultadoBackend: HomeApiClient.HomeCatalogo? = null

                if (catalogoBackend == null && !jaEnviouCatalogoAntes && podeTentarEnvio) {
                    // ✅ NOVO: antes de subir o catálogo inteiro, pergunta se o
                    // backend JÁ tem esse painel pronto (agora ele guarda por
                    // servidor, então outro DNS/cliente do mesmo servidor já
                    // pode ter enviado). Se tiver, não faz upload nenhum.
                    resultadoBackend = HomeApiClient.buscarHome(dns)
                    if (resultadoBackend != null && temDados(resultadoBackend)) {
                        prefsBackend.edit().putBoolean(chaveJaEnviou, true).apply()
                    } else {
                        prefsBackend.edit().putLong(chaveUltimaTentativa, System.currentTimeMillis()).apply()
                        // ✅ CORRIGIDO: só marca "já enviei" se o backend realmente
                        // recebeu (enviarCatalogo devolve false em timeout/erro).
                        val enviou = HomeApiClient.enviarCatalogo(dns, vodsCompletos, seriesCompletos)
                        if (enviou) {
                            prefsBackend.edit().putBoolean(chaveJaEnviou, true).apply()
                        }
                        logTempo("enviarCatalogo (só roda na 1ª vez que o backend vê esse painel)", "upload")
                        resultadoBackend = HomeApiClient.buscarHome(dns)
                    }
                } else {
                    resultadoBackend = HomeApiClient.buscarHome(dns)
                }
                logTempo("buscarHome (Top10/Novidades/selos prontos do backend)", "buscarHome")

                // ✅ CORRIGIDO: só considera "aplicado pelo backend" se ele
                // devolveu dados de verdade. Antes, uma resposta vazia (painel
                // sem catálogo no backend) pulava o cálculo local e a Home
                // ficava sem Top10/selos.
                if (resultadoBackend != null && temDados(resultadoBackend)) {
                    HomeBackendSync.aplicar(db, resultadoBackend)
                    aplicadoPeloBackend = true
                    logTempo("HomeBackendSync.aplicar (grava Top10/Novidades/selos no Room)")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (!aplicadoPeloBackend) {
                // É AQUI que os nomes crus do provedor ("007", "Rambo") são
                // resolvidos para os nomes oficiais e logos são associados,
                // caso o backend não tenha respondido com nada pronto.
                try {
                    TmdbSyncHelper.sincronizar(db)
                    logTempo("TmdbSyncHelper.sincronizar (fallback local, backend não respondeu)", "TMDB-local")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // ⚠️ CORREÇÃO (categorias como "Cinema" mostrando só 1-2 filmes
            // depois da sincronização): atualizarVods()/atualizarSeries()
            // SUBSTITUEM POR INTEIRO o mapa vodsPorCategoria/seriesPorCategoria
            // usado por VodActivity/SeriesActivity (ContentRepository.
            // getVodsByCategory). Antes, esse trecho passava só os 200 itens
            // mais recentes (getRecentVods/getRecentSeries) — suficiente pra
            // Home (Top10/Novidades/Continuar assistindo), mas isso apagava
            // do mapa QUALQUER categoria que não tivesse nenhum item entre
            // esses 200 mais recentes, sobrando só 1-2 filmes "por acaso"
            // nela até o usuário reabrir aquela categoria (o que dispara um
            // novo fetch que corrige só ela). Agora recarregamos o catálogo
            // INTEIRO do banco pra memória — igual o ContentRepository.
            // recarregar() já faz no login — mantendo todas as categorias
            // completas. groupBy já é rápido mesmo com 10.000+ itens (ver
            // comentário em ContentRepository.carregarDoBanco).
            val vodsFinal = db.streamDao().getAllVods()
            val seriesFinal = db.streamDao().getAllSeries()
            ContentRepository.atualizarVods(vodsFinal)
            ContentRepository.atualizarSeries(seriesFinal)
            logTempo("FIM da sincronização (ContentRepository atualizado)", "FIM")
            notificarOuvintes()

            // ✅ SILENCIOSO: antes aparecia um Toast "SYNC: ..." na tela do
            // cliente. Agora o resumo dos tempos vai só pro Logcat (tag
            // "SyncManager"), sem nada visível no aplicativo.
            if (primeiraDaSessao) {
                val texto = "SYNC: " + marcas.joinToString(" | ") +
                    (if (aplicadoPeloBackend) " | Top10=backend" else " | Top10=local")
                Log.d("SyncManager", texto)
            }

            // Canais ao vivo: só agora esperamos (já rodava em paralelo desde
            // o início). Falha aqui não afeta filmes/séries.
            try {
                liveJob?.await()
                logTempo("Live (canais ao vivo) concluído")
            } catch (e: Exception) {
                e.printStackTrace()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            liveJob?.cancel()
        }
    }

    // ── Busca crua no Xtream (usada só quando o backend não tem o catálogo) ──
    //
    // ✅ CORRIGIDO (Home/abas demorando muito pra popular): antes isso era
    // `URL(url).readText()` — conexão "crua" do Java que (1) NÃO tem
    // timeout (se o painel ficar lento ou travar, esperava pra sempre),
    // (2) NÃO usa o DNS-over-HTTPS do app, (3) NÃO tem o failover
    // automático de DNS (se o DNS salvo caísse, não tentava outro) e
    // (4) manda o User-Agent padrão do Android ("Dalvik/..."), que vários
    // painéis Xtream rejeitam com 403 (o mesmo problema já corrigido no
    // resto do app). Agora usa um cliente OkHttp com timeouts, DoH,
    // User-Agent de navegador e o mesmo DnsFailoverInterceptor das
    // demais chamadas Xtream.
    private val clienteXtream: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(XtreamApi.buildSafeDns())
            .addInterceptor(VpnInterceptor())
            .addInterceptor(DnsFailoverInterceptor())
            .build()
    }

    /**
     * ✅ NOVO: aplica o PACOTE INICIAL do backend (poucos itens por
     * categoria + itens com selo). Reaproveita sincronizarVod/sincronizarSeries
     * (mesmo código do catálogo completo — grava no Room preservando os
     * campos calculados), mas esses dois helpers deixam a memória só com os
     * 200 mais recentes; por isso, depois deles, recarregamos a memória a
     * partir do Room (que nesse momento ainda é pequeno: poucas centenas de
     * itens), pra TODAS as categorias aparecerem nas abas. Depois avisa a
     * Home. As abas de Filmes/Séries completam cada categoria sozinhas em
     * segundo plano (atualizarEmBackground), como já faziam.
     */
    private suspend fun aplicarPacoteInicial(
        db: AppDatabase, dns: String, palavrasProibidas: List<String>,
        vodsExistentes: Map<Int, VodEntity>, seriesExistentes: Map<Int, SeriesEntity>
    ) {
        val pacote = HomeApiClient.buscarCatalogoInicial(dns) ?: return
        sincronizarVod(db, pacote.vodArray, palavrasProibidas, vodsExistentes)
        sincronizarSeries(db, pacote.seriesArray, palavrasProibidas, seriesExistentes)
        ContentRepository.atualizarVods(db.streamDao().getAllVods())
        ContentRepository.atualizarSeries(db.streamDao().getAllSeries())
        Log.d("SyncManager", "⚡ pacote inicial aplicado: ${pacote.vodArray.length()} filmes, ${pacote.seriesArray.length()} séries")
        notificarOuvintes()
    }

    private fun baixarJsonXtream(dns: String, user: String, pass: String, action: String): String {
        val base = dns.trim().removeSuffix("/")
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", action)
            .build()
        val request = Request.Builder().url(url).build()
        clienteXtream.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} em $action")
            return response.body?.string().orEmpty()
        }
    }

    private fun buscarArrayXtream(dns: String, user: String, pass: String, action: String): JSONArray {
        return JSONArray(baixarJsonXtream(dns, user, pass, action))
    }

    // ✅ NOVO — helpers de leitura "JSON tem prioridade, existente é
    // fallback": usados pelos campos calculados pelo TMDB (logo, top10,
    // top10 Brasil, novidade, badges de série). Quando o array vem do
    // GET /catalog do backend, o JSON já tem essas chaves com valor
    // pronto — usamos direto. Quando o array vem cru do Xtream
    // (get_vod_streams/get_series), essas chaves NUNCA existem — nesse
    // caso preservamos o que já estava salvo localmente (senão toda
    // sincronização periódica apagaria os selos calculados até o
    // TmdbSyncHelper/backend recalcular de novo).
    private fun campoTextoOuExistente(obj: JSONObject, chave: String, existente: String?): String? =
        if (obj.has(chave)) obj.optString(chave).takeIf { it.isNotEmpty() } else existente

    private fun campoIntOuExistente(obj: JSONObject, chave: String, existente: Int): Int =
        if (obj.has(chave)) obj.optInt(chave, existente) else existente

    private fun campoLongOuExistente(obj: JSONObject, chave: String, existente: Long): Long =
        if (obj.has(chave)) obj.optLong(chave, existente) else existente

    private fun campoIdOuExistente(obj: JSONObject, chave: String, existente: Int?): Int? = when {
        !obj.has(chave) -> existente
        obj.isNull(chave) -> null
        else -> obj.optInt(chave)
    }

    // ── VOD ────────────────────────────────────────────────────────────────
    // Recebe o array já pronto (vindo do backend OU recém-baixado do
    // Xtream via buscarArrayXtream) — o formato dos campos CRUS (name,
    // stream_icon, category_id...) é o mesmo nos dois casos, então o
    // processamento é idêntico independente da origem. Os campos
    // CALCULADOS (logo_url, top10, top10_brasil, novidade) é que mudam
    // de fonte dependendo se o JSON já os traz ou não — ver os helpers
    // campoXOuExistente acima.
    private suspend fun sincronizarVod(
        db: AppDatabase, vodArray: JSONArray,
        palavrasProibidas: List<String>, vodsExistentes: Map<Int, VodEntity>
    ): List<VodEntity> = withContext(Dispatchers.IO) {
        val vodBatch = mutableListOf<VodEntity>()
        // ✅ coleta TODOS os itens aceitos (não só o lote atual) pra
        // poder mandar o catálogo completo pro backend no final — sem isso
        // só teríamos acesso aos últimos 200 (vodBatch é limpo a cada flush).
        val todosVods = mutableListOf<VodEntity>()
        for (i in 0 until vodArray.length()) {
            val obj = vodArray.getJSONObject(i)
            val nome = obj.optString("name")
            if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                val streamId = obj.optInt("stream_id")
                val existente = vodsExistentes[streamId]
                val entity = VodEntity(
                    stream_id = streamId,
                    name = nome,
                    title = obj.optString("name"),
                    stream_icon = obj.optString("stream_icon"),
                    container_extension = obj.optString("container_extension"),
                    rating = obj.optString("rating"),
                    category_id = obj.optString("category_id"),
                    added = obj.optLong("added"),
                    // ✅ CORRIGIDO: antes vinha só de `existente` (sempre 0/
                    // null numa instalação nova, mesmo o JSON já trazendo
                    // pronto do backend). Agora usa o valor do próprio JSON
                    // quando a chave existe nele.
                    logo_url = campoTextoOuExistente(obj, "logo_url", existente?.logo_url),
                    tmdb_rank = campoIntOuExistente(obj, "tmdb_rank", existente?.tmdb_rank ?: 0),
                    tmdb_release_date = campoTextoOuExistente(obj, "tmdb_release_date", existente?.tmdb_release_date),
                    is_top10 = campoIntOuExistente(obj, "is_top10", existente?.is_top10 ?: 0),
                    is_novidade = campoIntOuExistente(obj, "is_novidade", existente?.is_novidade ?: 0),
                    tmdb_id = campoIdOuExistente(obj, "tmdb_id", existente?.tmdb_id),
                    backdrop_path = campoTextoOuExistente(obj, "backdrop_path", existente?.backdrop_path),
                    // ✅ Top 10 Brasil — mesma correção acima.
                    is_top10_brasil = campoIntOuExistente(obj, "is_top10_brasil", existente?.is_top10_brasil ?: 0),
                    tmdb_rank_brasil = campoIntOuExistente(obj, "tmdb_rank_brasil", existente?.tmdb_rank_brasil ?: 0)
                )
                vodBatch.add(entity)
                todosVods.add(entity)
            }
            if (vodBatch.size >= 200) {
                db.streamDao().insertVodStreams(vodBatch)
                vodBatch.clear()
            }
        }
        if (vodBatch.isNotEmpty()) db.streamDao().insertVodStreams(vodBatch)

        val vodsAtualizados = db.streamDao().getRecentVods(200)
        ContentRepository.atualizarVods(vodsAtualizados)
        todosVods
    }

    // ── SÉRIES ─────────────────────────────────────────────────────────────
    // Mesma ideia do sincronizarVod: recebe o array já pronto, seja do
    // backend ou recém-baixado do Xtream, e usa os helpers
    // campoXOuExistente pros campos calculados.
    private suspend fun sincronizarSeries(
        db: AppDatabase, seriesArray: JSONArray,
        palavrasProibidas: List<String>, seriesExistentes: Map<Int, SeriesEntity>
    ): List<SeriesEntity> = withContext(Dispatchers.IO) {
        val seriesBatch = mutableListOf<SeriesEntity>()
        // ✅ mesma ideia do sincronizarVod — coleta tudo pro upload.
        val todasSeries = mutableListOf<SeriesEntity>()
        for (i in 0 until seriesArray.length()) {
            val obj = seriesArray.getJSONObject(i)
            val nome = obj.optString("name")
            if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                val seriesId = obj.optInt("series_id")
                val existente = seriesExistentes[seriesId]
                val entity = SeriesEntity(
                    series_id = seriesId,
                    name = nome,
                    cover = obj.optString("cover"),
                    rating = obj.optString("rating"),
                    category_id = obj.optString("category_id"),
                    last_modified = obj.optLong("last_modified"),
                    // ✅ CORRIGIDO: mesma correção do sincronizarVod — usa
                    // o valor do JSON quando presente, senão preserva o
                    // que já existia localmente.
                    logo_url = campoTextoOuExistente(obj, "logo_url", existente?.logo_url),
                    tmdb_rank = campoIntOuExistente(obj, "tmdb_rank", existente?.tmdb_rank ?: 0),
                    tmdb_release_date = campoTextoOuExistente(obj, "tmdb_release_date", existente?.tmdb_release_date),
                    is_top10 = campoIntOuExistente(obj, "is_top10", existente?.is_top10 ?: 0),
                    is_novidade = campoIntOuExistente(obj, "is_novidade", existente?.is_novidade ?: 0),
                    tmdb_id = campoIdOuExistente(obj, "tmdb_id", existente?.tmdb_id),
                    backdrop_path = campoTextoOuExistente(obj, "backdrop_path", existente?.backdrop_path),
                    tmdb_ultima_temporada = campoIntOuExistente(obj, "tmdb_ultima_temporada", existente?.tmdb_ultima_temporada ?: 0),
                    tmdb_ultimo_episodio = campoIntOuExistente(obj, "tmdb_ultimo_episodio", existente?.tmdb_ultimo_episodio ?: 0),
                    is_nova_temporada = campoIntOuExistente(obj, "is_nova_temporada", existente?.is_nova_temporada ?: 0),
                    is_novo_episodio = campoIntOuExistente(obj, "is_novo_episodio", existente?.is_novo_episodio ?: 0),
                    tmdb_flag_marcado_em = campoLongOuExistente(obj, "tmdb_flag_marcado_em", existente?.tmdb_flag_marcado_em ?: 0),
                    tmdb_proxima_temporada_data = campoTextoOuExistente(obj, "tmdb_proxima_temporada_data", existente?.tmdb_proxima_temporada_data),
                    tmdb_proximo_episodio_data = campoTextoOuExistente(obj, "tmdb_proximo_episodio_data", existente?.tmdb_proximo_episodio_data),
                    // ✅ Top 10 Brasil — mesma correção acima.
                    is_top10_brasil = campoIntOuExistente(obj, "is_top10_brasil", existente?.is_top10_brasil ?: 0),
                    tmdb_rank_brasil = campoIntOuExistente(obj, "tmdb_rank_brasil", existente?.tmdb_rank_brasil ?: 0)
                )
                seriesBatch.add(entity)
                todasSeries.add(entity)
            }
            if (seriesBatch.size >= 200) {
                db.streamDao().insertSeriesStreams(seriesBatch)
                seriesBatch.clear()
            }
        }
        if (seriesBatch.isNotEmpty()) db.streamDao().insertSeriesStreams(seriesBatch)

        val seriesAtualizadas = db.streamDao().getRecentSeries(200)
        ContentRepository.atualizarSeries(seriesAtualizadas)
        todasSeries
    }

    // ── LIVE ───────────────────────────────────────────────────────────────
    // Continua vindo sempre do Xtream — o backend não guarda canais ao vivo.
    private suspend fun sincronizarLive(db: AppDatabase, dns: String, user: String, pass: String) = withContext(Dispatchers.IO) {
        val liveArray = buscarArrayXtream(dns, user, pass, "get_live_streams")
        val liveBatch = mutableListOf<LiveStreamEntity>()
        for (i in 0 until liveArray.length()) {
            val obj = liveArray.getJSONObject(i)
            liveBatch.add(LiveStreamEntity(
                stream_id = obj.optInt("stream_id"),
                name = obj.optString("name"),
                stream_icon = obj.optString("stream_icon"),
                epg_channel_id = obj.optString("epg_channel_id"),
                category_id = obj.optString("category_id")
            ))
            if (liveBatch.size >= 200) {
                db.streamDao().insertLiveStreams(liveBatch)
                liveBatch.clear()
            }
        }
        if (liveBatch.isNotEmpty()) db.streamDao().insertLiveStreams(liveBatch)
    }
}
