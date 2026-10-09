package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.DownloadEntity
import com.vltv.play.retro.RetroGamesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = TmdbConfig.API_KEY

    // ✅ Selos dos cards (Novidade / Nova temporada / Novo episódio / Em breve):
    // ligado/desligado pelo painel /admin do gateway (GET /config). O último
    // valor fica guardado no aparelho; sem resposta do gateway, usa ele.
    @Volatile private var selosAtivos: Boolean = true
    @Volatile private var seloNovidade: Boolean = true
    @Volatile private var seloTop10: Boolean = true
    @Volatile private var seloEpisodio: Boolean = true
    @Volatile private var seloTemporada: Boolean = true
    @Volatile private var seloEmBreve: Boolean = true

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    private var listaCompletaParaSorteio: List<Any> = emptyList()
    private lateinit var bannerAdapter: BannerAdapter

    private val bannerFila = mutableListOf<Any>()
    private var bannerFilaIndex = 0
    private var bannerCarregado = false
    private var bannerItemAtual: Any? = null
    private var bannerBuscaJob: kotlinx.coroutines.Job? = null
    private val bannerHandler = Handler(Looper.getMainLooper())
    // ✅ Carrossel do banner de destaque mais lento (estilo Netflix — era
    // 8s, ficava rápido demais). 10s dá tempo de ler título/sinopse antes
    // de trocar de item.
    private val BANNER_INTERVALO_MS = 10000L

    private var bannerRequestId = 0

    private var gameBannerCrestJob: kotlinx.coroutines.Job? = null

    private data class GameInfo(
        val competition: String = "",
        val team_home: String = "",
        val team_away: String = "",
        val date: String = "",
        val time: String = "",
        val channel: String = "",
        val image_url: String = "",
        val is_live: Boolean = false
    )

    private data class GameDisplayReady(
        val info: GameInfo,
        val crestHome: Bitmap?,
        val crestAway: Bitmap?,
        val bitmapFundo: Bitmap? = null
    )

    private val gameRotationHandler = Handler(Looper.getMainLooper())
    private var gameRotationList: List<GameDisplayReady> = emptyList()
    private var gameRotationIndex = 0
    private var gameRotationFetchJob: kotlinx.coroutines.Job? = null
    private val GAME_ROTATION_INTERVALO_MS = 6000L

    private val escudoBitmapCache = mutableMapOf<String, Bitmap?>()
    private val confrontoBitmapCache = mutableMapOf<String, Bitmap?>()
    private var ultimoGamesJsonAplicado: String? = null
    private var ultimoFeaturedTitleAplicado: String? = null
    private var featuredBannerEncontrado = false

    private val featuredResolvedIdCache = mutableMapOf<String, Int>()

    private data class BannerAssets(
        val backdropUrl: String?,
        val logoUrl: String?,
        val cleanTitle: String
    )
    private val bannerAssetsCache = mutableMapOf<String, BannerAssets>()

    private var top10FilmesJob: kotlinx.coroutines.Job? = null
    private var top10SeriesJob: kotlinx.coroutines.Job? = null
    private var removerOuvinteSync: (() -> Unit)? = null

    private var popularSectionsJob: kotlinx.coroutines.Job? = null
    private var popularSectionsPendente: Triple<List<VodItem>, List<VodEntity>, List<SeriesEntity>>? = null

    private var top10FilmesTmdbCache: List<VodEntity>? = null
    private var top10SeriesTmdbCache: List<SeriesEntity>? = null

    private var top10MoviesAdapterRef: Top10Adapter? = null
    private var top10SeriesAdapterRef: Top10Adapter? = null

    // ✅ NOVO: Top 10 Brasil (ranking oficial Netflix por país, calculado
    // pelo vltv-backend) — jobs/adapters separados dos do Top 10 Mundial
    // acima, já que agora são duas fileiras independentes.
    private var top10FilmesBrasilJob: kotlinx.coroutines.Job? = null
    private var top10SeriesBrasilJob: kotlinx.coroutines.Job? = null
    private var top10MoviesBrasilAdapterRef: Top10Adapter? = null
    private var top10SeriesBrasilAdapterRef: Top10Adapter? = null

    // ✅ NOVO: cache (só em memória, por abertura da Home) do preenchimento
    // do Top 10 Brasil com tendência TMDB. Sem isso, toda vez que a Home
    // era redesenhada (abertura + cada aviso do SyncManager) e o ranking
    // do backend vinha incompleto, o app refazia a chamada de rede ao TMDB
    // e até ~120 buscas LIKE '%...%' no catálogo inteiro — o Top 10 Mundial
    // já tinha esse cache (top10FilmesTmdbCache), o Brasil não. A chave é
    // o conjunto de ids já usados, então o resultado é o mesmo de antes.
    private val top10FilmesBrasilExtrasCache = java.util.concurrent.ConcurrentHashMap<String, List<VodEntity>>()
    private val top10SeriesBrasilExtrasCache = java.util.concurrent.ConcurrentHashMap<String, List<SeriesEntity>>()

    // ✅ NOVO: pra cada série do Top 10 Séries Brasil, guarda (só em
    // memória) o tmdb_id e o NOME OFICIAL em pt-BR que o TMDB devolveu.
    // Chave = series_id do catálogo. Só guarda resultado encontrado — se
    // a rede falhar, tenta de novo no próximo redesenho.
    private val serieTmdbBrCache = java.util.concurrent.ConcurrentHashMap<Int, SerieTmdbBr>()

    private var ultimoIconeAplicadoNoNav: String? = null

    // ✅ NOVO: controle do fade da logo "VLTV" fixa no topo da Home. Antes
    // ela ficava sempre visível por cima de tudo (inclusive dos pôsteres e
    // cards quando a página era rolada). Agora ela some suavemente assim
    // que o usuário começa a rolar a tela, e volta a aparecer ao voltar
    // pro topo — sem depender do id do container de rolagem, usando o
    // banner principal (bannerViewPager) como referência de posição.
    private var wordmarkRef: TextView? = null
    private var wordmarkAnchorInitialTop: Int = -1
    private var wordmarkScrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    companion object {
        // ✅ As listas de tarjas (REGEX_EXIBICAO_TAGS, REGEX_EXIBICAO_BRACKETS,
        // REGEX_EXIBICAO_YEAR, REGEX_TMDB_TAGS, REGEX_TMDB_BRACKETS,
        // REGEX_TMDB_SPACES) foram movidas pro TituloCleaner.kt (fonte
        // única, usada em 9 outras telas). Só ficou aqui o que é específico
        // desta tela: corte de traço/bullet sobrando no final do nome.
        private val REGEX_EXIBICAO_TRAILING = Regex("[-|•·]+\\s*$")

        private const val WORDMARK_TAG = "vltv_home_wordmark"

        private const val JANELA_NOVIDADE_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile private var ultimoFetchRemoteConfigMs = 0L
        private const val INTERVALO_MINIMO_FETCH_MS = 30_000L

        private const val TMDB_TIMEOUT_MS = 8000
    }

    private class UntintableDrawable(private val base: Drawable) : Drawable() {
        override fun draw(canvas: Canvas) = base.draw(canvas)
        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) {
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = base.intrinsicWidth
        override fun getIntrinsicHeight(): Int = base.intrinsicHeight
        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            super.setBounds(left, top, right, bottom)
            base.setBounds(left, top, right, bottom)
        }
    }

    private fun fetchUrlComTimeout(urlStr: String, timeoutMs: Int = TMDB_TIMEOUT_MS): String {
        val connection = URL(urlStr).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // Pergunta ao gateway se os selos estão ligados. Se mudou, grava e redesenha a Home.
    private fun atualizarConfigSelos() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                    .getString("dns", "")?.trim()?.removeSuffix("/") ?: ""
                if (base.isEmpty()) return@launch
                val cfg = JSONObject(fetchUrlComTimeout("$base/config", 8000))
                val nGeral = cfg.optBoolean("selos", true)
                val nNovidade = cfg.optBoolean("novidade", true)
                val nTop10 = cfg.optBoolean("top10", true)
                val nEpisodio = cfg.optBoolean("episodio", true)
                val nTemporada = cfg.optBoolean("temporada", true)
                val nEmBreve = cfg.optBoolean("embreve", true)
                val mudou = nGeral != selosAtivos || nNovidade != seloNovidade || nTop10 != seloTop10 ||
                    nEpisodio != seloEpisodio || nTemporada != seloTemporada || nEmBreve != seloEmBreve
                if (mudou) {
                    selosAtivos = nGeral
                    seloNovidade = nNovidade
                    seloTop10 = nTop10
                    seloEpisodio = nEpisodio
                    seloTemporada = nTemporada
                    seloEmBreve = nEmBreve
                    getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("selos_ativos", nGeral)
                        .putBoolean("selo_novidade", nNovidade)
                        .putBoolean("selo_top10", nTop10)
                        .putBoolean("selo_episodio", nEpisodio)
                        .putBoolean("selo_temporada", nTemporada)
                        .putBoolean("selo_embreve", nEmBreve)
                        .apply()
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) popularTelaDoRepositorio()
                    }
                }
            } catch (e: Exception) {
                // sem resposta: mantém o último valor guardado
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            configurarOrientacaoAutomatica()

            binding = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("last_profile_name", null)
            val savedIcon = prefs.getString("last_profile_icon", null)

            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: savedName ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
                ?.takeIf { it.isNotEmpty() }
                ?: savedIcon?.takeIf { it.isNotEmpty() }

            prefs.edit().apply {
                putString("last_profile_name", currentProfile)
                if (currentProfileIcon != null) putString("last_profile_icon", currentProfileIcon)
                apply()
            }

            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false

            getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE).let { sp ->
                selosAtivos = sp.getBoolean("selos_ativos", true)
                seloNovidade = sp.getBoolean("selo_novidade", true)
                seloTop10 = sp.getBoolean("selo_top10", true)
                seloEpisodio = sp.getBoolean("selo_episodio", true)
                seloTemporada = sp.getBoolean("selo_temporada", true)
                seloEmBreve = sp.getBoolean("selo_embreve", true)
            }

            setupSingleBanner()
            setupBottomNavigation()
            setupClicks()

            // ✅ NOVO: badge circular no botão de Downloads (header),
            // mostrando quantos downloads ainda não terminaram (na fila +
            // baixando + pausado) do perfil atual. Usa a LiveData que já
            // existe no StreamDao (getDownloadsByProfile) — atualiza
            // sozinho, sem polling, toda vez que a tabela "downloads" muda.
            observarBadgeDownloads()

            adicionarWordmarkVLTV()
            ajustarHeaderParaStatusBar()

            if (ContentRepository.pronto) {
                popularTelaDoRepositorio()
            } else {
                ContentRepository.aoFicarPronto {
                    popularTelaDoRepositorio()
                }
                carregarDadosLocaisImediato()
            }

            // ✅ REMOVIDO: Toast de diagnóstico ("BANCO — Filmes: Top10=...")
            // que aparecia toda vez que a Home era notificada de uma
            // atualização parcial/total dos selos. Era só um debug interno
            // que ficou esquecido em produção e clientes estavam vendo essa
            // mensagem. O diagnóstico continua sendo calculado internamente
            // em TmdbSyncHelper.ultimoDiagnostico (útil em logcat), só não
            // é mais exibido na tela.

            removerOuvinteSync = SyncManager.registrarOuvinteNovidade {
                if (!isFinishing && !isDestroyed) {
                    popularTelaDoRepositorio()
                }
            }
            SyncManager.sincronizarSeNecessario(applicationContext)
            SyncManager.iniciarSyncPeriodica(applicationContext)
            atualizarConfigSelos()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ✅ CORRIGIDO: o botão de Downloads no header flutuante (novo layout
    // estilo Netflix) ficava embaixo da status bar, sobrepondo o relógio
    // e a bateria — o headerLayout tinha altura fixa e nenhum respiro pra
    // status bar. Agora empurramos o conteúdo do header pra baixo pela
    // altura real da status bar do aparelho (varia por notch/furo de
    // câmera) e aumentamos a altura do header na mesma medida, mantendo
    // os 60dp visuais de antes abaixo da status bar.
    private fun ajustarHeaderParaStatusBar() {
        val header = binding.headerLayout ?: return
        val statusBarHeightPx = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
        }
        header.setPadding(header.paddingLeft, statusBarHeightPx, header.paddingRight, header.paddingBottom)
        val params = header.layoutParams
        if (params != null && params.height > 0) {
            params.height += statusBarHeightPx
            header.layoutParams = params
        }
    }

    private fun adicionarWordmarkVLTV() {
        val contentRoot = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        if (contentRoot.findViewWithTag<View>(WORDMARK_TAG) != null) return

        val statusBarHeightPx = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
        }

        val wordmark = TextView(this).apply {
            tag = WORDMARK_TAG
            text = "VLTV"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setShadowLayer(8f, 0f, 2f, Color.parseColor("#B3000000"))
            setPadding(20.dp, 0, 20.dp, 0)
            isClickable = false
            isFocusable = false
            elevation = 24f
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = statusBarHeightPx + 10.dp
        }

        contentRoot.addView(wordmark, params)
        wordmarkRef = wordmark

        // ✅ AJUSTADO A PEDIDO: antes a logo "VLTV" ia sumindo aos poucos
        // conforme rolava (acompanhando a rolagem por ~160dp), o que
        // fazia ela "viajar" junto com a tela por cima de botões e capas
        // por um instante. Agora é um corte seco: ao menor sinal de
        // rolagem (poucos pixels) ela some na hora; ao voltar pro topo
        // exato, reaparece na hora. Não depende do id do container de
        // rolagem (seja NestedScrollView, RecyclerView etc).
        val anchor = binding.bannerViewPager ?: return
        val limiarSumicoPx = 6.dp.toFloat()

        anchor.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (wordmarkAnchorInitialTop == -1) {
                    val loc = IntArray(2)
                    anchor.getLocationOnScreen(loc)
                    if (loc[1] != 0) {
                        wordmarkAnchorInitialTop = loc[1]
                        anchor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        })

        val listener = ViewTreeObserver.OnScrollChangedListener {
            val wm = wordmarkRef ?: return@OnScrollChangedListener
            if (wordmarkAnchorInitialTop == -1) return@OnScrollChangedListener
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val scrolled = (wordmarkAnchorInitialTop - loc[1]).coerceAtLeast(0)
            val deveAparecer = scrolled <= limiarSumicoPx
            wm.alpha = if (deveAparecer) 1f else 0f
            wm.visibility = if (deveAparecer) View.VISIBLE else View.INVISIBLE
        }
        wordmarkScrollListener = listener
        binding.root.viewTreeObserver.addOnScrollChangedListener(listener)
    }

    private fun iniciarCarrosselBanner() {
        bannerHandler.removeCallbacksAndMessages(null)
        if (bannerFila.size < 2) return
        bannerHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                avancarBanner()
                bannerHandler.postDelayed(this, BANNER_INTERVALO_MS)
            }
        }, BANNER_INTERVALO_MS)
    }

    private fun avancarBanner() {
        if (bannerFila.isEmpty()) return
        bannerFilaIndex = (bannerFilaIndex + 1) % bannerFila.size
        val proximoItem = bannerFila[bannerFilaIndex]
        mostrarItemNoBanner(proximoItem)
    }

    private fun mostrarItemNoBanner(item: Any) {
        if (isFinishing || isDestroyed) return
        bannerItemAtual = item
        bannerAdapter.updateItem(item)
    }

    private fun construirFilaBannerEIniciar() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val top10Vods   = database.streamDao().getTop10Vods()
                val top10Series = database.streamDao().getTop10Series()

                val fila = mutableListOf<Any>()
                val maxLen = maxOf(top10Vods.size, top10Series.size)
                for (i in 0 until maxLen) {
                    if (i < top10Vods.size)   fila.add(top10Vods[i])
                    if (i < top10Series.size)  fila.add(top10Series[i])
                }

                val filaFinal: List<Any> = if (fila.isNotEmpty()) fila
                else listaCompletaParaSorteio.shuffled().take(20)

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (filaFinal.isEmpty()) return@withContext

                    bannerFila.clear()
                    bannerFila.addAll(filaFinal)
                    bannerFilaIndex = 0

                    if (!bannerCarregado) {
                        bannerCarregado = true
                        mostrarItemNoBanner(bannerFila[0])
                    }

                    iniciarCarrosselBanner()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (!bannerCarregado && listaCompletaParaSorteio.isNotEmpty()) {
                        bannerCarregado = true
                        mostrarItemNoBanner(listaCompletaParaSorteio.first())
                    }
                }
            }
        }
    }

    private fun popularTelaDoRepositorio() {
        val localMovies = ContentRepository.vods
        val localSeries = ContentRepository.series

        if (localMovies.isEmpty() && localSeries.isEmpty()) {
            carregarDadosLocaisImediato()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            // ✅ CORRIGIDO (Home vazia por ~5s ao reabrir o app): antes, TODO
            // o catálogo (17 mil+ filmes e 8 mil+ séries) era convertido em
            // VodItem aqui — cada conversão roda a limpeza de nome (várias
            // Regex) e, nas séries, cria um SimpleDateFormat — e só DEPOIS
            // disso a tela era desenhada (banner e fileiras são montados em
            // popularSections). Só que popularSections usa essas listas
            // completas apenas pra 2 coisas: as 20 primeiras (pré-carga de
            // capas) e as 10 últimas (fallback de Novidades). Então
            // convertemos só essas pontas — resultado idêntico, sem esperar
            // 25 mil conversões antes de mostrar a Home.
            val movieItems = amostraPontas(localMovies).map {
                it.paraItem()
            }
            val seriesItems = amostraPontas(localSeries).map {
                it.paraItem()
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                agendarPopularSections(movieItems, seriesItems, localMovies, localSeries)
            }
        }
    }

    // ✅ NOVO: devolve só o que popularSections realmente lê das listas
    // completas — as 20 primeiras (take(20) em ativarModoSupersonico) e as
    // 10 últimas (takeLast(10) no fallback de Novidades). Se a lista tiver
    // 30 itens ou menos, devolve ela inteira (nada muda).
    private fun <T> amostraPontas(lista: List<T>): List<T> =
        if (lista.size <= 30) lista else lista.take(20) + lista.takeLast(10)

    private fun agendarPopularSections(
        movieItems: List<VodItem>,
        seriesItems: List<VodItem>,
        localMovies: List<VodEntity>,
        localSeries: List<SeriesEntity>
    ) {
        popularSectionsJob?.cancel()
        popularSectionsJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(300)
            if (isFinishing || isDestroyed) return@launch
            popularSections(movieItems, seriesItems, localMovies, localSeries)
        }
    }

    private fun popularSections(
        movieItems: List<VodItem>,
        seriesItems: List<VodItem>,
        localMovies: List<VodEntity>,
        localSeries: List<SeriesEntity>
    ) {
        val filmesOrdenadosItems = if (localMovies.isNotEmpty()) {
            localMovies.sortedWith(
                compareByDescending<VodEntity> { it.is_novidade }
                    .thenByDescending { it.tmdb_release_date ?: "" }
                    .thenByDescending { it.added }
            ).take(20).map {
                it.paraItem()
            }
        } else emptyList()

        // ✅ CORRIGIDO: antes a ordenação só olhava pra is_novidade (que só
        // vale pra lançamento com ano ≥ 2025) e datas — uma série antiga
        // (ex: 2022) que ganhou temporada/episódio novo agora nunca subia
        // ao topo, porque nada aqui checava os selos de atividade. Agora
        // "tem Nova Temporada ou Novo Episódio ativo" vem ANTES de
        // is_novidade — atividade real (algo saiu AGORA) pesa mais do que
        // só a data de lançamento original do título.
        val seriesOrdenadasItems = if (localSeries.isNotEmpty()) {
            localSeries.sortedWith(
                compareByDescending<SeriesEntity> { it.is_nova_temporada == 1 || it.is_novo_episodio == 1 }
                    .thenByDescending { it.is_novidade }
                    .thenByDescending { it.tmdb_flag_marcado_em }
                    .thenByDescending { it.tmdb_release_date ?: "" }
                    .thenByDescending { it.last_modified }
            ).take(20).map {
                it.paraItem()
            }
        } else emptyList()

        if (localMovies.isNotEmpty()) {
            val listaExibicaoFilmes = filmesOrdenadosItems.take(20)

            binding.rvRecentlyAdded.setItemViewCacheSize(20)
            binding.rvRecentlyAdded.adapter = HomeRowAdapter(listaExibicaoFilmes) { selectedItem ->
                val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                intent.putExtra("name", selectedItem.name)
                intent.putExtra("icon", selectedItem.streamIcon)
                intent.putExtra("PROFILE_NAME", currentProfile)
                intent.putExtra("is_series", false)
                startActivity(intent)
            }
        }

        if (localSeries.isNotEmpty()) {
            val listaExibicaoSeries = seriesOrdenadasItems.take(20)

            binding.rvRecentSeries.setItemViewCacheSize(20)
            binding.rvRecentSeries.adapter = HomeRowAdapter(listaExibicaoSeries) { selectedItem ->
                val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                intent.putExtra("name", selectedItem.name)
                intent.putExtra("icon", selectedItem.streamIcon)
                intent.putExtra("PROFILE_NAME", currentProfile)
                intent.putExtra("is_series", true)
                startActivity(intent)
            }
        }

        top10FilmesJob?.cancel()
        top10FilmesJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                var top10DbVods = database.streamDao().getTop10Vods()
                // ✅ CORRIGIDO: antes caía no fallback (TMDB trending, sem
                // curadoria de atualidade) sempre que o banco tivesse MENOS
                // DE 10 itens — descartando um Top 10 real parcial (ex: 6
                // dos 10 títulos da Netflix bateram no catálogo) por um
                // fallback pior. Agora só usa o fallback quando o banco não
                // tem NENHUM item — confia no Top 10 real sempre que ele
                // encontrar pelo menos um título.
                if (top10DbVods.isEmpty()) {
                    val cache = top10FilmesTmdbCache
                    top10DbVods = if (cache != null) {
                        cache
                    } else {
                        val resultado = buscarTop10FilmesAgora()
                        if (resultado.isNotEmpty()) top10FilmesTmdbCache = resultado
                        resultado
                    }
                }
                val top10Items = top10DbVods.map {
                    it.paraItem()
                }
                val top10Final = if (top10Items.isNotEmpty()) top10Items else filmesOrdenadosItems.take(10)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (top10Final.isNotEmpty()) {
                        aplicarTop10Filmes(top10Final)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val fallback = filmesOrdenadosItems.take(10)
                    if (fallback.isNotEmpty()) {
                        aplicarTop10Filmes(fallback)
                    }
                }
            }
        }

        top10SeriesJob?.cancel()
        top10SeriesJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                var top10DbSeries = database.streamDao().getTop10Series()
                // ✅ CORRIGIDO: mesma mudança acima, agora pras séries.
                if (top10DbSeries.isEmpty()) {
                    val cache = top10SeriesTmdbCache
                    top10DbSeries = if (cache != null) {
                        cache
                    } else {
                        val resultado = buscarTop10SeriesAgora()
                        if (resultado.isNotEmpty()) top10SeriesTmdbCache = resultado
                        resultado
                    }
                }
                val top10Items = top10DbSeries.map {
                    it.paraItem()
                }
                val top10Final = if (top10Items.isNotEmpty()) top10Items else seriesOrdenadasItems.take(10)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (top10Final.isNotEmpty()) {
                        aplicarTop10Series(top10Final)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val fallback = seriesOrdenadasItems.take(10)
                    if (fallback.isNotEmpty()) {
                        aplicarTop10Series(fallback)
                    }
                }
            }
        }

        // ✅ Top 10 Brasil (ranking oficial Netflix por país, vindo do
        // vltv-backend). O ranking em si continua sendo o oficial, sem
        // mistura — mas agora passa por completarTop10BrasilFilmes()/
        // completarTop10BrasilSeries() antes de exibir: 1) tira qualquer
        // duplicata (mesmo título em 2 ranks diferentes), 2) se sobrar
        // menos de 10 (rank oficial incompleto porque nem tudo bateu no
        // catálogo do painel), completa com tendência TMDB — sempre no
        // FINAL da lista. Só esconde a fileira inteira se não sobrar
        // NADA depois disso (backend não configurado, ou nada bateu).
        top10FilmesBrasilJob?.cancel()
        top10FilmesBrasilJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val top10DbVodsBrasil = database.streamDao().getTop10VodsBrasil()
                val itens = completarTop10BrasilFilmes(top10DbVodsBrasil)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (itens.isNotEmpty()) aplicarTop10FilmesBrasil(itens) else esconderTop10FilmesBrasil()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) esconderTop10FilmesBrasil()
                }
            }
        }

        top10SeriesBrasilJob?.cancel()
        top10SeriesBrasilJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val top10DbSeriesBrasil = database.streamDao().getTop10SeriesBrasil()
                val itens = completarTop10BrasilSeries(top10DbSeriesBrasil)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (itens.isNotEmpty()) aplicarTop10SeriesBrasil(itens) else esconderTop10SeriesBrasil()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) esconderTop10SeriesBrasil()
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val novidadesDbFilmes = database.streamDao().getNovidadesVods()
                val novidadesDbSeries = database.streamDao().getNovidadesSeries()
                val novidades: List<VodItem>
                val seriesIds: Set<String>
                if (novidadesDbFilmes.isNotEmpty() || novidadesDbSeries.isNotEmpty()) {
                    val filmeItems = novidadesDbFilmes.map {
                        it.paraItem()
                    }
                    val serieItems = novidadesDbSeries.map {
                        it.paraItem()
                    }
                    novidades = (filmeItems + serieItems).take(20)
                    seriesIds = novidadesDbSeries.map { it.series_id.toString() }.toSet()
                } else {
                    val novidadesFilmes = movieItems.takeLast(10)
                    val novidadesSeries = seriesItems.takeLast(10)
                    novidades = (novidadesFilmes + novidadesSeries).shuffled().take(20)
                    seriesIds = novidadesSeries.map { it.id }.toSet()
                }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (novidades.isNotEmpty()) {
                        binding.rvNovidades?.itemAnimator = null
                        binding.rvNovidades?.adapter = HomeRowAdapter(novidades, compact = true) { selectedItem ->
                            val ehSerie = seriesIds.contains(selectedItem.id)
                            val intent = if (ehSerie)
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0) }
                            else
                                Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0) }
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        listaCompletaParaSorteio = (localMovies + localSeries)
        construirFilaBannerEIniciar()
        ativarModoSupersonico(movieItems, seriesItems)
        carregarContinuarAssistindoLocal()
    }

    private fun aplicarTop10Filmes(lista: List<VodItem>) {
        val onClick: (VodItem) -> Unit = { selectedItem ->
            val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
            intent.putExtra("name", selectedItem.name)
            intent.putExtra("icon", selectedItem.streamIcon)
            intent.putExtra("PROFILE_NAME", currentProfile)
            intent.putExtra("is_series", false)
            startActivity(intent)
        }
        val existente = top10MoviesAdapterRef
        if (existente != null) {
            existente.updateList(lista)
        } else {
            binding.rvTop10Movies?.itemAnimator = null
            val novoAdapter = Top10Adapter(lista, onClick)
            top10MoviesAdapterRef = novoAdapter
            binding.rvTop10Movies?.adapter = novoAdapter
        }
    }

    private fun aplicarTop10Series(lista: List<VodItem>) {
        val onClick: (VodItem) -> Unit = { selectedItem ->
            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
            intent.putExtra("name", selectedItem.name)
            intent.putExtra("icon", selectedItem.streamIcon)
            intent.putExtra("PROFILE_NAME", currentProfile)
            intent.putExtra("is_series", true)
            startActivity(intent)
        }
        val existente = top10SeriesAdapterRef
        if (existente != null) {
            existente.updateList(lista)
        } else {
            binding.rvTop10Series?.itemAnimator = null
            val novoAdapter = Top10Adapter(lista, onClick)
            top10SeriesAdapterRef = novoAdapter
            binding.rvTop10Series?.adapter = novoAdapter
        }
    }

    // ✅ NOVO: Top 10 Filmes BRASIL — mesmo padrão de aplicarTop10Filmes(),
    // mostrando o header + RecyclerView (ambos começam GONE no layout).
    private fun aplicarTop10FilmesBrasil(lista: List<VodItem>) {
        binding.llTop10FilmesBrasilHeader?.visibility = View.VISIBLE
        binding.rvTop10MoviesBrasil?.visibility = View.VISIBLE
        val onClick: (VodItem) -> Unit = { selectedItem ->
            val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
            intent.putExtra("name", selectedItem.name)
            intent.putExtra("icon", selectedItem.streamIcon)
            intent.putExtra("PROFILE_NAME", currentProfile)
            intent.putExtra("is_series", false)
            startActivity(intent)
        }
        val existente = top10MoviesBrasilAdapterRef
        if (existente != null) {
            existente.updateList(lista)
        } else {
            binding.rvTop10MoviesBrasil?.itemAnimator = null
            val novoAdapter = Top10Adapter(lista, onClick)
            top10MoviesBrasilAdapterRef = novoAdapter
            binding.rvTop10MoviesBrasil?.adapter = novoAdapter
        }
    }

    private fun esconderTop10FilmesBrasil() {
        binding.llTop10FilmesBrasilHeader?.visibility = View.GONE
        binding.rvTop10MoviesBrasil?.visibility = View.GONE
    }

    // ✅ NOVO: Top 10 Séries BRASIL — mesma ideia acima, agora pra séries.
    private fun aplicarTop10SeriesBrasil(lista: List<VodItem>) {
        binding.llTop10SeriesBrasilHeader?.visibility = View.VISIBLE
        binding.rvTop10SeriesBrasil?.visibility = View.VISIBLE
        val onClick: (VodItem) -> Unit = { selectedItem ->
            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
            intent.putExtra("name", selectedItem.name)
            intent.putExtra("icon", selectedItem.streamIcon)
            intent.putExtra("PROFILE_NAME", currentProfile)
            intent.putExtra("is_series", true)
            startActivity(intent)
        }
        val existente = top10SeriesBrasilAdapterRef
        if (existente != null) {
            existente.updateList(lista)
        } else {
            binding.rvTop10SeriesBrasil?.itemAnimator = null
            val novoAdapter = Top10Adapter(lista, onClick)
            top10SeriesBrasilAdapterRef = novoAdapter
            binding.rvTop10SeriesBrasil?.adapter = novoAdapter
        }
    }

    private fun esconderTop10SeriesBrasil() {
        binding.llTop10SeriesBrasilHeader?.visibility = View.GONE
        binding.rvTop10SeriesBrasil?.visibility = View.GONE
    }

    // ✅ NOVO: aceita um conjunto de stream_id pra EXCLUIR da busca (usado
    // pelo preenchimento do Top 10 Brasil — ver completarTop10BrasilFilmes
    // — pra não repetir um filme que já apareceu na própria fileira).
    // Comportamento antigo preservado: chamar sem argumento (excluir
    // vazio) continua igual ao Top 10 Mundial.
    private suspend fun buscarTop10FilmesAgora(excluir: Set<Int> = emptySet()): List<VodEntity> {
        return try {
            val tmdbUrl = "https://api.themoviedb.org/3/trending/movie/week?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"
            val tmdbResults = JSONObject(fetchUrlComTimeout(tmdbUrl)).getJSONArray("results")
            val limite = minOf(tmdbResults.length(), 20)
            val candidatos = (0 until limite).map { tmdbResults.getJSONObject(it) }

            val vodsEncontrados = coroutineScope {
                candidatos.map { obj ->
                    async {
                        val tituloPt   = obj.optString("title", "")
                        val tituloOrig = obj.optString("original_title", "")
                        queryVodEntityExato(tituloOrig, excluir)
                            ?: queryVodEntityExato(tituloPt, excluir)
                            ?: queryVodEntity(likeExato(tituloOrig), excluir)
                            ?: queryVodEntity(likeExato(tituloPt), excluir)
                            ?: palavraMaisLonga(tituloOrig)?.let { queryVodEntity("%$it%", excluir) }
                            ?: palavraMaisLonga(tituloPt)?.let { queryVodEntity("%$it%", excluir) }
                    }
                }.awaitAll()
            }

            val idsVistos = mutableSetOf<Int>()
            val resultado = mutableListOf<VodEntity>()
            for (vod in vodsEncontrados) {
                if (vod == null) continue
                if (idsVistos.add(vod.stream_id)) {
                    resultado.add(vod)
                    if (resultado.size >= 10) break
                }
            }
            resultado
        } catch (e: Exception) { emptyList() }
    }

    // ✅ NOVO: mesma ideia acima, pra série.
    private suspend fun buscarTop10SeriesAgora(excluir: Set<Int> = emptySet()): List<SeriesEntity> {
        return try {
            val tmdbUrl = "https://api.themoviedb.org/3/trending/tv/week?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"
            val tmdbResults = JSONObject(fetchUrlComTimeout(tmdbUrl)).getJSONArray("results")
            val limite = minOf(tmdbResults.length(), 20)
            val candidatos = (0 until limite).map { tmdbResults.getJSONObject(it) }

            val seriesEncontradas = coroutineScope {
                candidatos.map { obj ->
                    async {
                        val tituloPt   = obj.optString("name", "")
                        val tituloOrig = obj.optString("original_name", "")
                        querySerieEntityExato(tituloOrig, excluir)
                            ?: querySerieEntityExato(tituloPt, excluir)
                            ?: querySerieEntity(likeExato(tituloOrig), excluir)
                            ?: querySerieEntity(likeExato(tituloPt), excluir)
                            ?: palavraMaisLonga(tituloOrig)?.let { querySerieEntity("%$it%", excluir) }
                            ?: palavraMaisLonga(tituloPt)?.let { querySerieEntity("%$it%", excluir) }
                    }
                }.awaitAll()
            }

            val idsVistos = mutableSetOf<Int>()
            val resultado = mutableListOf<SeriesEntity>()
            for (serie in seriesEncontradas) {
                if (serie == null) continue
                if (idsVistos.add(serie.series_id)) {
                    resultado.add(serie)
                    if (resultado.size >= 10) break
                }
            }
            resultado
        } catch (e: Exception) { emptyList() }
    }

    // ✅ NOVO: monta a versão final da fileira "Top 10 Filmes Brasil" —
    // 1) tira duplicata (mesmo tmdb_id, ou mesmo título normalizado
    //    quando não tem tmdb_id ainda) mantendo a ordem de rank oficial
    //    que veio do vltv-backend; 2) se sobrar menos de 10 depois de
    //    tirar duplicata (ou se o backend só encontrou 8/9 no catálogo
    //    do painel), completa o resto com a MESMA tendência TMDB usada
    //    no Top 10 Mundial — sempre no FINAL da lista, sem embaralhar o
    //    ranking oficial já validado.
    private suspend fun completarTop10BrasilFilmes(brutos: List<VodEntity>): List<VodItem> {
        // ✅ CORRIGIDO: mesma regra de duplicata das séries (mesmo
        // stream_id, mesmo tmdb_id OU mesmo título normalizado).
        val idsTmdbVistos = mutableSetOf<Int>()
        val titulosVistos = mutableSetOf<String>()
        val semDuplicata = mutableListOf<VodEntity>()

        fun tentarAdicionar(vod: VodEntity) {
            val titulo = TituloCleaner.normalizarParaComparacao(vod.name)
            val tmdbId = vod.tmdb_id
            val repetido = semDuplicata.any { it.stream_id == vod.stream_id } ||
                (tmdbId != null && tmdbId in idsTmdbVistos) ||
                (titulo.isNotBlank() && titulo in titulosVistos)
            if (repetido) return
            semDuplicata.add(vod)
            if (tmdbId != null) idsTmdbVistos.add(tmdbId)
            if (titulo.isNotBlank()) titulosVistos.add(titulo)
        }

        for (vod in brutos) tentarAdicionar(vod)

        if (semDuplicata.size < 10) {
            try {
                val idsUsados = semDuplicata.map { it.stream_id }.toSet()
                val chaveCache = idsUsados.sorted().joinToString(",")
                val extras = top10FilmesBrasilExtrasCache[chaveCache]
                    ?: buscarTop10FilmesAgora(idsUsados).also {
                        if (it.isNotEmpty()) top10FilmesBrasilExtrasCache[chaveCache] = it
                    }
                for (extra in extras) {
                    if (semDuplicata.size >= 10) break
                    tentarAdicionar(extra)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return semDuplicata.take(10).map { it.paraItem() }
    }

    // ✅ NOVO: mesma ideia acima, pra "Top 10 Séries Brasil" — com dois
    // acertos a mais (ver mesclarSeriesBr e resolverSerieTmdbBr):
    //  1) a MESMA série cadastrada 2x no painel com nomes DIFERENTES (ex:
    //     "Adim Farah" — título original — e "Meu Nome e Farah" — título
    //     brasileiro) vira UM card só, na posição da melhor colocada; e
    //  2) o nome exibido passa a ser o nome OFICIAL em português do
    //     Brasil que o TMDB devolve (ex: "Meu Nome é Farah").
    // Se o TMDB não responder (sem internet, série não encontrada), cai
    // exatamente no comportamento anterior: nome do catálogo + tirar
    // duplicata por id / tmdb_id / título igual.
    private data class SerieTmdbBr(val id: Int, val nomePt: String, val nomeOriginal: String)

    private data class SerieBrCandidata(
        val serie: SeriesEntity,
        val tmdbId: Int?,
        val nomePt: String?,
        val nomeOriginal: String?
    )

    private suspend fun completarTop10BrasilSeries(brutos: List<SeriesEntity>): List<VodItem> {
        val finais = mutableListOf<SerieBrCandidata>()
        mesclarSeriesBr(resolverCandidatasBr(brutos), finais, permitirTrocar = true)

        if (finais.size < 10) {
            try {
                // Exclui TODAS as séries que já vieram do ranking (inclusive
                // a "gêmea" que foi descartada como duplicata), senão o
                // preenchimento por tendência podia trazê-la de volta.
                val idsExcluidos = brutos.map { it.series_id }.toSet()
                val chaveCache = idsExcluidos.sorted().joinToString(",")
                val extras = top10SeriesBrasilExtrasCache[chaveCache]
                    ?: buscarTop10SeriesAgora(idsExcluidos).also {
                        if (it.isNotEmpty()) top10SeriesBrasilExtrasCache[chaveCache] = it
                    }
                val extrasCandidatas = resolverCandidatasBr(extras)
                mesclarSeriesBr(extrasCandidatas, finais, permitirTrocar = false)
            } catch (e: Exception) { e.printStackTrace() }
        }

        return finais.take(10).map { c ->
            val item = c.serie.paraItem()
            c.nomePt?.takeIf { it.isNotBlank() }?.let { nome -> item.copy(name = nome) } ?: item
        }
    }

    // Consulta o TMDB (em paralelo, com cache em memória) e devolve cada
    // série junto com tmdb_id e nome pt-BR quando foi possível descobrir.
    private suspend fun resolverCandidatasBr(lista: List<SeriesEntity>): List<SerieBrCandidata> {
        try {
            coroutineScope {
                lista.map { s -> async { resolverSerieTmdbBr(s) } }.awaitAll()
            }
        } catch (e: Exception) { e.printStackTrace() }
        return lista.map { s ->
            val info = serieTmdbBrCache[s.series_id]
            SerieBrCandidata(s, info?.id ?: s.tmdb_id, info?.nomePt, info?.nomeOriginal)
        }
    }

    // Só aceita resultado do TMDB cujo título (pt-BR ou original) seja
    // IGUAL ao nome do catálogo depois de normalizar (sem acento, caixa,
    // tarjas) — bem conservador de propósito, pra nunca juntar duas séries
    // diferentes. Se a série já tem tmdb_id no banco, só busca o nome pt-BR.
    private suspend fun resolverSerieTmdbBr(serie: SeriesEntity): SerieTmdbBr? {
        serieTmdbBrCache[serie.series_id]?.let { return it }

        val resolvida: SerieTmdbBr? = withContext(Dispatchers.IO) {
            try {
                val tmdbIdLocal = serie.tmdb_id
                if (tmdbIdLocal != null) {
                    val url = "https://api.themoviedb.org/3/tv/$tmdbIdLocal" +
                        "?api_key=$TMDB_API_KEY&language=pt-BR"
                    val detalhes = JSONObject(fetchUrlComTimeout(url, 5000))
                    val nome = detalhes.optString("name", "")
                    if (nome.isNotBlank()) {
                        SerieTmdbBr(tmdbIdLocal, nome, detalhes.optString("original_name", ""))
                    } else null
                } else {
                    val limpo = TituloCleaner.limparParaBusca(serie.name)
                    val alvo = normalizarTituloTmdbBr(limpo)
                    if (alvo.isBlank()) return@withContext null

                    val query = URLEncoder.encode(limpo, "UTF-8")
                    val url = "https://api.themoviedb.org/3/search/tv" +
                        "?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&page=1"
                    val results = JSONObject(fetchUrlComTimeout(url, 5000)).optJSONArray("results")
                        ?: return@withContext null

                    var melhor: SerieTmdbBr? = null
                    var melhorPontuacao = 0
                    for (i in 0 until minOf(results.length(), 10)) {
                        val obj = results.getJSONObject(i)
                        val id = obj.optInt("id", 0)
                        val nome = obj.optString("name", "")
                        if (id <= 0 || nome.isBlank()) continue
                        val score = when {
                            alvo == normalizarTituloTmdbBr(nome) -> 100
                            alvo == normalizarTituloTmdbBr(obj.optString("original_name", "")) -> 95
                            else -> 0
                        }
                        if (score > melhorPontuacao) {
                            melhorPontuacao = score
                            melhor = SerieTmdbBr(id, nome, obj.optString("original_name", ""))
                        }
                    }
                    melhor
                }
            } catch (e: Exception) { null }
        }

        if (resolvida != null) serieTmdbBrCache[serie.series_id] = resolvida
        return resolvida
    }

    // Normaliza pra comparar títulos. O "ı" turco (sem ponto) não é
    // tratado pelo normalizador padrão e sumiria da palavra — ex: "Adım
    // Farah" viraria "adm farah" e nunca bateria com "Adim Farah".
    private fun normalizarTituloTmdbBr(titulo: String): String =
        TituloCleaner.normalizarParaComparacao(titulo.replace('ı', 'i').replace('İ', 'I'))

    // "É a mesma série?" — mesmo series_id, mesmo tmdb_id, mesmo título de
    // catálogo, OU o nome oficial pt-BR de uma bate com o título (ou o
    // nome oficial) da outra.
    private fun mesmaSerieBr(a: SerieBrCandidata, b: SerieBrCandidata): Boolean {
        if (a.serie.series_id == b.serie.series_id) return true
        val ta = a.tmdbId
        val tb = b.tmdbId
        if (ta != null && ta == tb) return true

        val catalogoA = normalizarTituloTmdbBr(a.serie.name)
        val catalogoB = normalizarTituloTmdbBr(b.serie.name)
        if (catalogoA.isNotBlank() && catalogoA == catalogoB) return true

        val ptA = a.nomePt?.let { normalizarTituloTmdbBr(it) }.orEmpty()
        val ptB = b.nomePt?.let { normalizarTituloTmdbBr(it) }.orEmpty()
        if (ptA.isNotBlank() && (ptA == ptB || ptA == catalogoB)) return true
        if (ptB.isNotBlank() && ptB == catalogoA) return true

        // Título ORIGINAL do TMDB (ex: "Adım Farah") bate com o nome de
        // catálogo da outra — cobre o caso em que o TMDB só achou uma das
        // duas entradas (a de nome em português) e a outra tem o nome
        // original no catálogo ("Adim Farah").
        val origA = a.nomeOriginal?.let { normalizarTituloTmdbBr(it) }.orEmpty()
        val origB = b.nomeOriginal?.let { normalizarTituloTmdbBr(it) }.orEmpty()
        if (origA.isNotBlank() && origA == catalogoB) return true
        if (origB.isNotBlank() && origB == catalogoA) return true
        return false
    }

    // A entrada "brasileira" é a que já se chama, no catálogo, igual ao
    // nome oficial pt-BR (ex: "Meu Nome e Farah" = "Meu Nome é Farah").
    private fun ehEntradaPtBr(c: SerieBrCandidata): Boolean {
        val pt = c.nomePt?.let { normalizarTituloTmdbBr(it) }.orEmpty()
        return pt.isNotBlank() && pt == normalizarTituloTmdbBr(c.serie.name)
    }

    // Junta as candidatas em `base` (que já é a lista final, em ordem de
    // ranking) tirando repetidas. A repetida ocupa a posição da primeira
    // que apareceu; se permitirTrocar, a entrada "brasileira" assume o
    // lugar da outra (mesma posição, mesmo rank).
    private fun mesclarSeriesBr(
        candidatas: List<SerieBrCandidata>,
        base: MutableList<SerieBrCandidata>,
        permitirTrocar: Boolean
    ) {
        for (c in candidatas) {
            val idx = base.indexOfFirst { mesmaSerieBr(it, c) }
            if (idx < 0) {
                base.add(c)
            } else if (permitirTrocar && !ehEntradaPtBr(base[idx]) && ehEntradaPtBr(c)) {
                base[idx] = c
            }
        }
    }

    // ✅ Delega a limpeza de tags pro TituloCleaner (fonte única). Essa
    // função não tem ligação nenhuma com o banner — só é usada no fallback
    // de busca do Top 10.
    private fun normalizarTituloParaMatch(titulo: String): String =
        TituloCleaner.limparParaBusca(titulo)

    private suspend fun queryVodEntityExato(titulo: String, excluir: Set<Int>): VodEntity? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(titulo)
            if (tituloLimpo.isBlank()) return@withContext null
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM vod_streams WHERE name = ? COLLATE NOCASE LIMIT 10",
                arrayOf(tituloLimpo)
            )
            var resultado: VodEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = VodEntity(
                        stream_id           = id,
                        name                = cursor.getString(1),
                        title               = cursor.getString(2),
                        stream_icon         = cursor.getString(3),
                        container_extension = cursor.getString(4),
                        rating              = cursor.getString(5),
                        category_id         = cursor.getString(6),
                        added               = cursor.getLong(7),
                        logo_url            = cursor.getString(8),
                        tmdb_rank           = cursor.getInt(9),
                        tmdb_release_date   = cursor.getString(10),
                        is_top10            = cursor.getInt(11),
                        is_novidade         = cursor.getInt(12),
                        tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                        backdrop_path       = cursor.getString(14)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun querySerieEntityExato(titulo: String, excluir: Set<Int>): SeriesEntity? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(titulo)
            if (tituloLimpo.isBlank()) return@withContext null
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM series_streams WHERE name = ? COLLATE NOCASE LIMIT 10",
                arrayOf(tituloLimpo)
            )
            var resultado: SeriesEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = SeriesEntity(
                        series_id         = id,
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun querySerieEntidadesExatoTodos(titulo: String): List<SeriesEntity> =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(titulo)
            if (tituloLimpo.isBlank()) return@withContext emptyList()
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM series_streams WHERE name = ? COLLATE NOCASE",
                arrayOf(tituloLimpo)
            )
            val resultado = mutableListOf<SeriesEntity>()
            while (cursor.moveToNext()) {
                resultado.add(
                    SeriesEntity(
                        series_id         = cursor.getInt(0),
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                )
            }
            cursor.close()
            resultado
        }

    private suspend fun buscarSeriesPorNomeTolerante(titulo: String): List<SeriesEntity> =
        withContext(Dispatchers.IO) {
            val alvoNormalizado = normalizarParaComparacaoTitulo(limparNomeParaTMDB(titulo))
            if (alvoNormalizado.isBlank()) return@withContext emptyList()
            val termoBusca = palavraMaisLonga(titulo) ?: titulo.trim().take(6)
            if (termoBusca.isBlank()) return@withContext emptyList()

            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path FROM series_streams WHERE name LIKE ? LIMIT 100",
                arrayOf("%$termoBusca%")
            )
            val candidatos = mutableListOf<SeriesEntity>()
            while (cursor.moveToNext()) {
                candidatos.add(
                    SeriesEntity(
                        series_id         = cursor.getInt(0),
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                )
            }
            cursor.close()

            candidatos.filter {
                normalizarParaComparacaoTitulo(limparNomeParaTMDB(it.name)) == alvoNormalizado
            }
        }

    private suspend fun buscarVodsPorNomeTolerante(titulo: String): List<VodEntity> =
        withContext(Dispatchers.IO) {
            val alvoNormalizado = normalizarParaComparacaoTitulo(limparNomeParaTMDB(titulo))
            if (alvoNormalizado.isBlank()) return@withContext emptyList()
            val termoBusca = palavraMaisLonga(titulo) ?: titulo.trim().take(6)
            if (termoBusca.isBlank()) return@withContext emptyList()

            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path FROM vod_streams WHERE name LIKE ? LIMIT 100",
                arrayOf("%$termoBusca%")
            )
            val candidatos = mutableListOf<VodEntity>()
            while (cursor.moveToNext()) {
                candidatos.add(
                    VodEntity(
                        stream_id           = cursor.getInt(0),
                        name                = cursor.getString(1),
                        title               = cursor.getString(2),
                        stream_icon         = cursor.getString(3),
                        container_extension = cursor.getString(4),
                        rating              = cursor.getString(5),
                        category_id         = cursor.getString(6),
                        added               = cursor.getLong(7),
                        logo_url            = cursor.getString(8),
                        tmdb_rank           = cursor.getInt(9),
                        tmdb_release_date   = cursor.getString(10),
                        is_top10            = cursor.getInt(11),
                        is_novidade         = cursor.getInt(12),
                        tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                        backdrop_path       = cursor.getString(14)
                    )
                )
            }
            cursor.close()

            candidatos.filter {
                normalizarParaComparacaoTitulo(limparNomeParaTMDB(it.name)) == alvoNormalizado
            }
        }

    private suspend fun serieTemEpisodiosValidos(seriesId: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val username = prefs.getString("username", "") ?: ""
                val password = prefs.getString("password", "") ?: ""
                val response = XtreamApi.service.getSeriesInfoV2(username, password, seriesId = seriesId).execute()
                val episodios = response.body()?.episodes
                !episodios.isNullOrEmpty() && episodios.values.any { it.isNotEmpty() }
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun queryVodEntity(pattern: String, excluir: Set<Int>): VodEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM vod_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 10",
                arrayOf(pattern)
            )
            var resultado: VodEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = VodEntity(
                        stream_id           = id,
                        name                = cursor.getString(1),
                        title               = cursor.getString(2),
                        stream_icon         = cursor.getString(3),
                        container_extension = cursor.getString(4),
                        rating              = cursor.getString(5),
                        category_id         = cursor.getString(6),
                        added               = cursor.getLong(7),
                        logo_url            = cursor.getString(8),
                        tmdb_rank           = cursor.getInt(9),
                        tmdb_release_date   = cursor.getString(10),
                        is_top10            = cursor.getInt(11),
                        is_novidade         = cursor.getInt(12),
                        tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                        backdrop_path       = cursor.getString(14)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun querySerieEntity(pattern: String, excluir: Set<Int>): SeriesEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM series_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 10",
                arrayOf(pattern)
            )
            var resultado: SeriesEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = SeriesEntity(
                        series_id         = id,
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private fun likeExato(titulo: String): String {
        val limpo = titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
        return "%" + limpo
            .replace(Regex("[àáâãäå]"), "_")
            .replace(Regex("[èéêë]"), "_")
            .replace(Regex("[ìíîï]"), "_")
            .replace(Regex("[òóôõö]"), "_")
            .replace(Regex("[ùúûü]"), "_")
            .replace(Regex("[ç]"), "_")
            .replace(Regex("[ñ]"), "_") + "%"
    }

    private fun palavraMaisLonga(titulo: String): String? {
        if (titulo.isBlank()) return null
        return titulo.split(" ").filter { it.length >= 5 }.maxByOrNull { it.length }
            ?.replace(Regex("[àáâãäå]"), "a")
            ?.replace(Regex("[èéêë]"), "e")
            ?.replace(Regex("[ìíîï]"), "i")
            ?.replace(Regex("[òóôõö]"), "o")
            ?.replace(Regex("[ùúûü]"), "u")
            ?.replace(Regex("[ç]"), "c")
            ?.replace(Regex("[ñ]"), "n")
    }

    private fun configurarOrientacaoAutomatica() {
        requestedOrientation = if (isTelevisionDevice()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setupSingleBanner() {
        bannerAdapter = BannerAdapter(null)
        binding.bannerViewPager?.adapter = bannerAdapter
        binding.bannerViewPager?.isUserInputEnabled = false
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation?.let { nav ->
            val profileItem = nav.menu.findItem(R.id.nav_profile)
            profileItem?.title = currentProfile

            if (!currentProfileIcon.isNullOrEmpty() && currentProfileIcon != ultimoIconeAplicadoNoNav) {
                val iconValue = currentProfileIcon!!
                val ehUrlRemota = iconValue.startsWith("http://") || iconValue.startsWith("https://")
                val resId = if (!ehUrlRemota) {
                    resources.getIdentifier(iconValue, "drawable", packageName)
                } else {
                    0
                }

                val requestBuilder = if (resId != 0) {
                    Glide.with(this).asBitmap().load(resId)
                } else {
                    Glide.with(this).asBitmap().load(iconValue)
                }

                requestBuilder
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(object : CustomTarget<Bitmap>(96, 96) {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            if (!isFinishing && !isDestroyed) {
                                profileItem?.icon = UntintableDrawable(BitmapDrawable(resources, resource))
                                ultimoIconeAplicadoNoNav = iconValue
                            }
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }

        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                R.id.nav_novidades -> {
                    val intent = Intent(this, NovidadesActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }
    }

    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(60)
                val movieItems = localMovies.map { it.paraItem() }
                val localSeries = database.streamDao().getRecentSeries(60)
                val seriesItems = localSeries.map { it.paraItem() }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    agendarPopularSections(movieItems, seriesItems, localMovies, localSeries)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun ativarModoSupersonico(filmes: List<VodItem>, series: List<VodItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            val preloadList = filmes.take(20) + series.take(20)
            for (item in preloadList) {
                try {
                    if (!item.streamIcon.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            Glide.with(applicationContext)
                                .load(item.streamIcon)
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .onlyRetrieveFromCache(true)
                                .preload(180, 270)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    // ✅ Delega a limpeza de tags pro TituloCleaner (fonte única, usada em
    // 9 outras telas). Mantém o corte de traço/bullet sobrando no final
    // (ex: "Nome do Filme -"), que é específico dessa função.
    private fun limparNomeExibicao(nome: String): String {
        return TituloCleaner.limparParaBusca(nome)
            .replace(REGEX_EXIBICAO_TRAILING, "")
            .trim()
    }

    private fun VodEntity.paraItem(): VodItem = VodItem(
        id = stream_id.toString(),
        name = limparNomeExibicao(name),
        streamIcon = stream_icon ?: "",
        isSerie = false,
        isTop10 = selosAtivos && seloTop10 && is_top10 == 1,
        isNovidade = selosAtivos && seloNovidade && is_novidade == 1,
        logoUrl = logo_url
    )

    private fun SeriesEntity.paraItem(): VodItem {
        val dentroDaJanela = (System.currentTimeMillis() - tmdb_flag_marcado_em) < JANELA_NOVIDADE_MS
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return VodItem(
            id = series_id.toString(),
            name = limparNomeExibicao(name),
            streamIcon = cover ?: "",
            isSerie = true,
            isTop10 = selosAtivos && seloTop10 && is_top10 == 1,
            isNovidade = selosAtivos && seloNovidade && is_novidade == 1,
            isNovaTemporada = selosAtivos && seloTemporada && is_nova_temporada == 1 && dentroDaJanela,
            isNovoEpisodio = selosAtivos && seloEpisodio && is_novo_episodio == 1 && dentroDaJanela,
            isNovaTemporadaEmBreve = selosAtivos && seloEmBreve && !tmdb_proxima_temporada_data.isNullOrEmpty() &&
                tmdb_proxima_temporada_data > hoje,
            isNovoEpisodioEmBreve = selosAtivos && seloEmBreve && !tmdb_proximo_episodio_data.isNullOrEmpty() &&
                tmdb_proximo_episodio_data > hoje,
            logoUrl = logo_url
        )
    }

    // ✅ Delega a limpeza de tags pro TituloCleaner (fonte única).
    private fun limparNomeParaTMDB(nome: String): String {
        return TituloCleaner.limparParaBusca(nome).take(50)
    }

    private fun aplicarBannerCompleto(
        imgBanner: ImageView,
        imgLogo: ImageView,
        tvTitle: TextView,
        backdropUrl: String,
        fallbackIcon: String,
        logoUrl: String?,
        cleanTitle: String
    ) {
        imgBanner.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            // ✅ Cadeia de fallback: tenta o backdrop do TMDB/VPS primeiro; se
            // essa imagem falhar (URL quebrada, caminho salvo errado, proxy
            // fora do ar), cai pra capa do Xtream (a mesma que já funciona
            // nas abas de Filmes/Séries) em vez de deixar o banner vazio.
            // Só mostra o placeholder cinza se nenhuma das duas existir.
            val fallbackRequest = Glide.with(this@HomeActivity)
                .load(fallbackIcon.takeIf { it.isNotBlank() })
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(R.drawable.bg_logo_placeholder)

            Glide.with(this@HomeActivity)
                .load(backdropUrl.takeIf { it.isNotBlank() })
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.bg_logo_placeholder)
                .error(fallbackRequest)
                .dontAnimate()
                .into(imgBanner)
        } catch (e: Exception) {}

        if (!logoUrl.isNullOrEmpty()) {
            tvTitle.visibility = View.GONE
            imgLogo.visibility = View.VISIBLE
            try {
                Glide.with(this@HomeActivity)
                    .load(logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .into(imgLogo)
            } catch (e: Exception) {}
        } else {
            imgLogo.visibility = View.GONE
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = cleanTitle
        }
    }

    private fun resolverEAplicarBannerCompleto(
        titulo: String,
        cleanTitle: String,
        isSeries: Boolean,
        id: Int,
        fallbackIcon: String,
        chaveCache: String,
        requestId: Int,
        imgBanner: ImageView,
        imgLogo: ImageView,
        tvTitle: TextView,
        logoSalvoNoBanco: String?,
        tmdbIdSalvo: Int?,
        backdropPathSalvo: String?
    ) {
        bannerBuscaJob?.cancel()
        bannerBuscaJob = lifecycleScope.launch(Dispatchers.IO) {
            var backdropUrl: String? = null
            var logoUrl: String? = logoSalvoNoBanco?.takeIf { it.isNotEmpty() }
            val tipo = if (isSeries) "tv" else "movie"

            try {
                val temResolucaoConfiavel = tmdbIdSalvo != null && !backdropPathSalvo.isNullOrEmpty()

                if (temResolucaoConfiavel) {
                    backdropUrl = VpsConfig.tmdbImage(backdropPathSalvo!!, "original")
                    if (logoUrl == null) {
                        logoUrl = buscarMelhorLogoTmdb(tmdbIdSalvo.toString(), tipo, "")
                        if (logoUrl != null) {
                            try {
                                if (isSeries) database.streamDao().updateSeriesTmdbAssets(id, tmdbIdSalvo, backdropPathSalvo, logoUrl)
                                else database.streamDao().updateVodTmdbAssets(id, tmdbIdSalvo, backdropPathSalvo, logoUrl)
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    val nomeLimpo = limparNomeParaTMDB(titulo)
                    val query = URLEncoder.encode(nomeLimpo, "UTF-8")
                    val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR"
                    val response = fetchUrlComTimeout(url)
                    val results = JSONObject(response).getJSONArray("results")

                    if (results.length() > 0) {
                        val obj = escolherMelhorResultadoTmdb(results, titulo, isSeries)

                        if (obj != null) {
                            val backdropPath = obj.optString("backdrop_path")
                            val tmdbIdStr = obj.optString("id")
                            val tmdbIdInt = tmdbIdStr.toIntOrNull()
                            val originalLanguage = obj.optString("original_language", "")

                            val backdropValido = backdropPath.isNotEmpty() && backdropPath != "null"
                            if (backdropValido) {
                                backdropUrl = VpsConfig.tmdbImage(backdropPath, "original")
                            }

                            logoUrl = buscarMelhorLogoTmdb(tmdbIdStr, tipo, originalLanguage)

                            if (tmdbIdInt != null && backdropValido) {
                                try {
                                    if (isSeries) database.streamDao().updateSeriesTmdbAssets(id, tmdbIdInt, backdropPath, logoUrl)
                                    else database.streamDao().updateVodTmdbAssets(id, tmdbIdInt, backdropPath, logoUrl)
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {}

            val backdropFinal = backdropUrl ?: ""

            bannerAssetsCache[chaveCache] = BannerAssets(backdropUrl, logoUrl, cleanTitle)

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (requestId != bannerRequestId) return@withContext
                aplicarBannerCompleto(imgBanner, imgLogo, tvTitle, backdropFinal, fallbackIcon, logoUrl, cleanTitle)
            }
        }
    }

    private fun normalizarParaComparacaoTitulo(s: String): String {
        return s.lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tituloEhCompativel(alvo: String, candidato: String): Boolean {
        val a = normalizarParaComparacaoTitulo(limparNomeParaTMDB(alvo))
        val c = normalizarParaComparacaoTitulo(limparNomeParaTMDB(candidato))
        if (a.isBlank() || c.isBlank()) return false
        if (a == c) return true
        val regexFronteira = Regex("(?<![a-z0-9])" + Regex.escape(a) + "(?![a-z0-9])")
        return regexFronteira.containsMatchIn(c)
    }

    private fun escolherMelhorResultadoTmdb(results: org.json.JSONArray, tituloLocal: String, isSeries: Boolean): JSONObject? {
        val alvo = normalizarParaComparacaoTitulo(limparNomeParaTMDB(tituloLocal))
        if (alvo.isBlank()) return null

        val limite = minOf(results.length(), 10)

        for (i in 0 until limite) {
            val cand = results.getJSONObject(i)
            val nomeCand     = if (isSeries) cand.optString("name") else cand.optString("title")
            val nomeOrigCand = if (isSeries) cand.optString("original_name") else cand.optString("original_title")
            val nCand     = normalizarParaComparacaoTitulo(nomeCand)
            val nOrigCand = normalizarParaComparacaoTitulo(nomeOrigCand)
            if (nCand == alvo || nOrigCand == alvo) return cand
        }

        for (i in 0 until limite) {
            val cand = results.getJSONObject(i)
            val nomeCand     = if (isSeries) cand.optString("name") else cand.optString("title")
            val nomeOrigCand = if (isSeries) cand.optString("original_name") else cand.optString("original_title")
            val nCand     = normalizarParaComparacaoTitulo(nomeCand)
            val nOrigCand = normalizarParaComparacaoTitulo(nomeOrigCand)
            val bateParcial = alvo.length >= 4 && (
                nCand.startsWith(alvo) || alvo.startsWith(nCand) ||
                nOrigCand.startsWith(alvo) || alvo.startsWith(nOrigCand)
            )
            if (bateParcial) return cand
        }

        return null
    }

    private fun buscarMelhorLogoTmdb(tmdbId: String, tipo: String, originalLanguage: String): String? {
        return try {
            val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,$originalLanguage,null"
            val imagesJson = fetchUrlComTimeout(imagesUrl)
            val imagesObj = JSONObject(imagesJson)
            if (!imagesObj.has("logos")) return null
            val logos = imagesObj.getJSONArray("logos")
            if (logos.length() == 0) return null

            var bestPath: String? = null

            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "pt") { bestPath = logo.getString("file_path"); break }
            }
            if (bestPath == null && originalLanguage.isNotBlank() && originalLanguage != "pt") {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == originalLanguage) { bestPath = logo.getString("file_path"); break }
                }
            }
            if (bestPath == null) {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    val lang = logo.optString("iso_639_1")
                    if (lang == "null" || lang == "xx" || lang.isEmpty()) { bestPath = logo.getString("file_path"); break }
                }
            }
            if (bestPath == null && logos.length() > 0) {
                bestPath = logos.getJSONObject(0).getString("file_path")
            }

            bestPath?.let { VpsConfig.tmdbImage(it, "w500") }
        } catch (e: Exception) { null }
    }

    private suspend fun buscarEscudoBitmap(nomeTime: String, tamanhoPx: Int): Bitmap? {
        if (nomeTime.isBlank()) return null
        escudoBitmapCache[nomeTime]?.let { return it }
        return try {
            val badgeUrl = EscudoHelper.buscarEscudoUrl(nomeTime) ?: return null
            val bitmap = withContext(Dispatchers.IO) {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(badgeUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit(tamanhoPx, tamanhoPx)
                    .get(4, TimeUnit.SECONDS)
            }
            escudoBitmapCache[nomeTime] = bitmap
            bitmap
        } catch (e: Exception) { null }
    }

    private fun montarTituloComEscudos(
        timeCasa: String,
        timeFora: String,
        escudoCasa: Bitmap?,
        escudoFora: Bitmap?,
        tamanhoPx: Int
    ): CharSequence {
        val sb = SpannableStringBuilder()
        if (escudoCasa != null) {
            val start = sb.length
            sb.append("*")
            val drawable = BitmapDrawable(resources, escudoCasa)
            drawable.setBounds(0, 0, tamanhoPx, tamanhoPx)
            sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        sb.append(timeCasa)
        sb.append("  ×  ")
        if (escudoFora != null) {
            val start = sb.length
            sb.append("*")
            val drawable = BitmapDrawable(resources, escudoFora)
            drawable.setBounds(0, 0, tamanhoPx, tamanhoPx)
            sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        sb.append(timeFora)
        return sb
    }

    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        remoteConfig.setDefaultsAsync(mapOf(
            "show_copa_icon"        to false,
            // ✅ NOVO: chave única de tema de ÍCONE do launcher. Valores aceitos:
            // "normal" | "copa" | "halloween" | "criancas" | "natal" | "ano_novo"
            // Se vazia, cai no comportamento antigo (show_copa_icon). Ver TemaSazonalHelper.kt.
            "tema_icone_ativo"      to "",
            // ✅ NOVO: banner de tema sazonal (imagem hospedada na VPS) exibido
            // na Home, independente do ícone. Ver aplicarTemaBanner() abaixo.
            "show_tema_banner"      to false,
            "tema_banner_image_url" to "",
            // ✅ NOVO: tema visual sazonal (cantos decorativos) aplicado em
            // Home, Filmes/Séries, Canais, Detalhes, Novidades e Busca.
            // Ver TemaVisualManager.kt. Valores: "" | "halloween" |
            // "criancas" | "natal" | "ano_novo" | "carnaval"
            "tema_app_ativo"        to "",
            "show_game_banner"      to false,
            "game_banner_title"     to "",
            "game_banner_date"      to "",
            "game_banner_time"      to "",
            "game_banner_channel"   to "",
            "game_banner_image_url" to "",
            "game_banner_is_live"   to false,
            "game_banner_competition" to "",
            "game_banner_mode"      to "copa",
            "game_banner_team_home"   to "",
            "game_banner_team_away"   to "",
            "games_today_json"       to "",
            "show_featured_banner"  to false,
            "featured_title"        to "",
            "featured_synopsis"     to "",
            "featured_image_url"    to "",
            "featured_is_series"    to false,
            "featured_content_id"   to "",
            "show_retro_games"      to true
        ))
        remoteConfig.setConfigSettingsAsync(remoteConfigSettings {
            minimumFetchIntervalInSeconds = 30
        })

        val agora = System.currentTimeMillis()
        val jaBuscouRecente = (agora - ultimoFetchRemoteConfigMs) < INTERVALO_MINIMO_FETCH_MS

        if (jaBuscouRecente) {
            val restanteS = (INTERVALO_MINIMO_FETCH_MS - (agora - ultimoFetchRemoteConfigMs)) / 1000
            android.util.Log.d(
                "VLTV_RemoteConfig",
                "Pulando fetch (guard interno) — buscou há menos de ${INTERVALO_MINIMO_FETCH_MS / 1000}s, " +
                "faltam ~${restanteS}s. Reaplicando última config já ativada."
            )
            IconeSazonalHelper.aplicar(this)
            aplicarGameBanner(remoteConfig)
            aplicarFeaturedBanner(remoteConfig)
            aplicarRetroGamesCard(remoteConfig)
            aplicarTemaBanner(remoteConfig)
            TemaVisualManager.aplicarEm(this, binding.overlayTemaSazonal.root)
            return
        }

        ultimoFetchRemoteConfigMs = agora
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                android.util.Log.d(
                    "VLTV_RemoteConfig",
                    "fetchAndActivate OK — games_today_json='${remoteConfig.getString("games_today_json")}' " +
                    "show_game_banner=${remoteConfig.getBoolean("show_game_banner")} " +
                    "show_featured_banner=${remoteConfig.getBoolean("show_featured_banner")} " +
                    "featured_title='${remoteConfig.getString("featured_title")}' " +
                    "show_retro_games=${remoteConfig.getBoolean("show_retro_games")}"
                )
            } else {
                val erro = task.exception
                if (erro is com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException) {
                    val liberaEmS = (erro.throttleEndTimeMillis - System.currentTimeMillis()) / 1000
                    android.util.Log.w(
                        "VLTV_RemoteConfig",
                        "⚠️ FETCH THROTTLADO PELO FIREBASE — libera em ~${liberaEmS}s. " +
                        "A tela vai continuar mostrando a ÚLTIMA config que foi ativada com sucesso " +
                        "até esse tempo passar. Evite reabrir o app repetidamente enquanto testa."
                    )
                    // ✅ Aviso visível na tela (sem depender de Logcat): mostra
                    // por quanto tempo o Firebase vai bloquear novas buscas de
                    // Remote Config. Enquanto durar, mudanças feitas no console
                    // (ex: nova URL de imagem do banner) não vão aparecer.
                    if (!isFinishing && !isDestroyed) {
                        val minutos = liberaEmS / 60
                        val msg = if (minutos >= 1)
                            "Firebase bloqueou novas atualizações por ~${minutos}min (muitos testes seguidos). A config antiga continua na tela até liberar."
                        else
                            "Firebase bloqueou novas atualizações por ~${liberaEmS}s (muitos testes seguidos)."
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                } else {
                    android.util.Log.w(
                        "VLTV_RemoteConfig",
                        "fetchAndActivate FALHOU: ${erro?.javaClass?.simpleName} - ${erro?.message}"
                    )
                }
            }
            IconeSazonalHelper.aplicar(this)
            aplicarGameBanner(remoteConfig)
            aplicarFeaturedBanner(remoteConfig)
            aplicarRetroGamesCard(remoteConfig)
            aplicarTemaBanner(remoteConfig)
            TemaVisualManager.aplicarEm(this, binding.overlayTemaSazonal.root)
        }
    }

    private fun aplicarRetroGamesCard(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val show = remoteConfig.getBoolean("show_retro_games")
        binding.cardRetroGames?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * ✅ NOVO: Banner de tema sazonal (Halloween, Dia das Crianças, Natal,
     * Ano Novo etc.) exibido na Home. A imagem fica hospedada na VPS
     * (não vai dentro do APK) e a URL é controlada pelo Firebase Remote Config:
     *
     *   show_tema_banner      (Boolean) → true/false liga ou desliga o banner
     *   tema_banner_image_url (String)  → ex: https://cdn.vltvplay.tech/temas/halloween.jpg
     *
     * Para trocar de tema: só suba a imagem nova pra VPS (pode até manter o
     * mesmo nome de arquivo, o Glide já busca a versão mais recente por causa
     * do diskCacheStrategy) e ajuste a URL/flag no console do Firebase.
     * Não precisa gerar APK novo nem passar pelo GitHub Actions.
     */
    private fun aplicarTemaBanner(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val show     = remoteConfig.getBoolean("show_tema_banner")
        val imageUrl = remoteConfig.getString("tema_banner_image_url")
        val card = binding.cardTemaSazonal ?: return

        if (!show || imageUrl.isBlank()) {
            card.visibility = View.GONE
            return
        }

        try {
            Glide.with(this)
                .load(imageUrl)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(1080, 220)
                .into(binding.imgTemaSazonal)
            card.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
            card.visibility = View.GONE
        }
    }

    private fun aplicarGameBanner(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val gamesJson = remoteConfig.getString("games_today_json")
        aplicarGameBannerRotacao(remoteConfig, gamesJson)
    }

    private fun pararRotacaoJogos() {
        gameRotationHandler.removeCallbacksAndMessages(null)
        gameRotationFetchJob?.cancel()
        gameRotationList = emptyList()
        gameRotationIndex = 0
    }

    private fun aplicarGameBannerRotacao(
        remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig,
        gamesJson: String
    ) {
        val show = remoteConfig.getBoolean("show_game_banner")
        val card = binding.cardGameBanner ?: return

        if (!show) {
            card.visibility = View.GONE
            pararRotacaoJogos()
            ultimoGamesJsonAplicado = null
            return
        }

        if (gamesJson == ultimoGamesJsonAplicado && gameRotationList.isNotEmpty()) {
            card.visibility = View.VISIBLE
            if (gameRotationIndex !in gameRotationList.indices) gameRotationIndex = 0
            mostrarJogoRotacao(gameRotationIndex)
            iniciarRotacaoJogos()
            return
        }

        gameRotationFetchJob?.cancel()
        gameRotationFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jogos = try {
                    parseJogosDoDia(gamesJson)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }

                if (jogos.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) card.visibility = View.GONE
                    }
                    return@launch
                }

                val tamanhoPx = 28.dp

                val prontos = coroutineScope {
                    jogos.map { info ->
                        async {
                            val casa = try { buscarEscudoBitmap(info.team_home, tamanhoPx) } catch (e: Exception) { null }
                            val fora = try { buscarEscudoBitmap(info.team_away, tamanhoPx) } catch (e: Exception) { null }
                            val fundo = if (info.image_url.isBlank()) {
                                val chaveFundo = "${info.team_home}|${info.team_away}"
                                if (confrontoBitmapCache.containsKey(chaveFundo)) {
                                    confrontoBitmapCache[chaveFundo]
                                } else {
                                    val gerado = try {
                                        ConfrontoImageHelper.gerarImagemFundo(info.team_home, info.team_away)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }
                                    confrontoBitmapCache[chaveFundo] = gerado
                                    gerado
                                }
                            } else {
                                null
                            }
                            GameDisplayReady(info, casa, fora, fundo)
                        }
                    }.awaitAll()
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    card.visibility = View.VISIBLE
                    gameRotationList = prontos
                    gameRotationIndex = 0
                    mostrarJogoRotacao(0)
                    iniciarRotacaoJogos()
                    ultimoGamesJsonAplicado = gamesJson

                    card.setOnClickListener {
                        val intent = Intent(this@HomeActivity, LiveTvActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", true)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) card.visibility = View.GONE
                }
            }
        }
    }

    private fun parseJogosDoDia(json: String): List<GameInfo> {
        val arr = org.json.JSONArray(json)
        val lista = mutableListOf<GameInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            lista.add(
                GameInfo(
                    competition = obj.optString("competition", ""),
                    team_home   = obj.optString("team_home", ""),
                    team_away   = obj.optString("team_away", ""),
                    date        = obj.optString("date", ""),
                    time        = obj.optString("time", ""),
                    channel     = obj.optString("channel", ""),
                    image_url   = obj.optString("image_url", ""),
                    is_live     = obj.optBoolean("is_live", false)
                )
            )
        }
        return lista.filter { it.team_home.isNotBlank() && it.team_away.isNotBlank() }
    }

    private fun iniciarRotacaoJogos() {
        gameRotationHandler.removeCallbacksAndMessages(null)
        if (gameRotationList.size < 2) return
        gameRotationHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                gameRotationIndex = (gameRotationIndex + 1) % gameRotationList.size
                mostrarJogoRotacao(gameRotationIndex)
                gameRotationHandler.postDelayed(this, GAME_ROTATION_INTERVALO_MS)
            }
        }, GAME_ROTATION_INTERVALO_MS)
    }

    private fun mostrarJogoRotacao(index: Int) {
        if (isFinishing || isDestroyed) return
        val card = binding.cardGameBanner ?: return
        if (index !in gameRotationList.indices) return
        val jogo = gameRotationList[index]
        val info = jogo.info

        val tvBadge = card.findViewById<TextView>(R.id.tvGameBadge)
        val statusTexto = if (info.is_live) "Ao vivo" else "Em breve"
        tvBadge.text = if (info.competition.isNotBlank()) "⚽  ${info.competition}  •  $statusTexto" else "⚽  $statusTexto"
        if (info.is_live) {
            tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#CC1B5E20"))
            tvBadge.setTextColor(android.graphics.Color.parseColor("#00FF88"))
        } else {
            tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#402C2C2A"))
            tvBadge.setTextColor(android.graphics.Color.parseColor("#F5F3EF"))
        }

        val tvGameTitle = card.findViewById<TextView>(R.id.tvGameTitle)
        val tamanhoPx = 28.dp
        tvGameTitle.text = if (jogo.crestHome != null || jogo.crestAway != null)
            montarTituloComEscudos(info.team_home, info.team_away, jogo.crestHome, jogo.crestAway, tamanhoPx)
        else
            "${info.team_home}  ×  ${info.team_away}"

        val datetime = buildString {
            if (info.date.isNotBlank()) append(info.date)
            if (info.date.isNotBlank() && info.time.isNotBlank()) append("  •  ")
            if (info.time.isNotBlank()) append(info.time)
            if (info.time.isNotBlank()) append(" (Brasília)")
        }
        card.findViewById<TextView>(R.id.tvGameDatetime).text = datetime

        val tvChannel = card.findViewById<TextView>(R.id.tvGameChannel)
        if (info.channel.isNotBlank()) {
            tvChannel.text = "📺  ${info.channel}"
            tvChannel.visibility = View.VISIBLE
        } else {
            tvChannel.visibility = View.GONE
        }

        val imgFundo = card.findViewById<ImageView>(R.id.imgGameBanner)
        imgFundo.scaleType = ImageView.ScaleType.CENTER_CROP
        if (info.image_url.isNotBlank()) {
            try {
                Glide.with(this).load(info.image_url).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .into(imgFundo)
            } catch (e: Exception) { e.printStackTrace() }
        } else if (jogo.bitmapFundo != null) {
            Glide.with(this).clear(imgFundo)
            imgFundo.setImageBitmap(jogo.bitmapFundo)
        } else {
            Glide.with(this).clear(imgFundo)
            imgFundo.setImageDrawable(null)
        }
    }

    private fun aplicarFeaturedBanner(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val show        = remoteConfig.getBoolean("show_featured_banner")
        val title       = remoteConfig.getString("featured_title")
        val synopsis    = remoteConfig.getString("featured_synopsis")
        val imageUrl    = remoteConfig.getString("featured_image_url")
        val isSeriesRC  = remoteConfig.getBoolean("featured_is_series")
        val contentIdRC = remoteConfig.getString("featured_content_id")

        val card = binding.cardFeaturedBanner ?: return
        if (!show || title.isBlank()) {
            card.visibility = View.GONE
            ultimoFeaturedTitleAplicado = null
            featuredBannerEncontrado = false
            return
        }

        card.findViewById<TextView>(R.id.tvFeaturedTitle).text = title

        val tvSynopsis = card.findViewById<TextView>(R.id.tvFeaturedSynopsis)
        if (synopsis.isNotBlank()) {
            tvSynopsis.text = synopsis
            tvSynopsis.visibility = View.VISIBLE
        } else {
            tvSynopsis.visibility = View.GONE
        }

        if (imageUrl.isNotBlank()) {
    try {
        Glide.with(this)
            .load(imageUrl)
            .centerCrop()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .priority(com.bumptech.glide.Priority.IMMEDIATE)
            .override(720, 405)
            .dontAnimate()
            .into(card.findViewById(R.id.imgFeaturedBanner))
    } catch (e: Exception) { e.printStackTrace() }
        }

        val jaResolvidoAntes = title == ultimoFeaturedTitleAplicado && featuredBannerEncontrado
        if (jaResolvidoAntes) {
            card.visibility = View.VISIBLE
            return
        }

        buscarIdFeaturedBanner(card, title, isSeriesRC, contentIdRC)
        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (!isFinishing && !isDestroyed) {
                    buscarIdFeaturedBanner(card, title, isSeriesRC, contentIdRC)
                }
            }
        }
    }

    private suspend fun buscarSeriePorId(id: Int): SeriesEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path FROM series_streams WHERE series_id = ? LIMIT 1",
                arrayOf(id.toString())
            )
            var resultado: SeriesEntity? = null
            if (cursor.moveToFirst()) {
                resultado = SeriesEntity(
                    series_id         = cursor.getInt(0),
                    name              = cursor.getString(1),
                    cover             = cursor.getString(2),
                    rating            = cursor.getString(3),
                    category_id       = cursor.getString(4),
                    last_modified     = cursor.getLong(5),
                    logo_url          = cursor.getString(6),
                    tmdb_rank         = cursor.getInt(7),
                    tmdb_release_date = cursor.getString(8),
                    is_top10          = cursor.getInt(9),
                    is_novidade       = cursor.getInt(10),
                    tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                    backdrop_path     = cursor.getString(12)
                )
            }
            cursor.close()
            resultado
        }

    private suspend fun buscarVodPorId(id: Int): VodEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path FROM vod_streams WHERE stream_id = ? LIMIT 1",
                arrayOf(id.toString())
            )
            var resultado: VodEntity? = null
            if (cursor.moveToFirst()) {
                resultado = VodEntity(
                    stream_id           = cursor.getInt(0),
                    name                = cursor.getString(1),
                    title               = cursor.getString(2),
                    stream_icon         = cursor.getString(3),
                    container_extension = cursor.getString(4),
                    rating              = cursor.getString(5),
                    category_id         = cursor.getString(6),
                    added               = cursor.getLong(7),
                    logo_url            = cursor.getString(8),
                    tmdb_rank           = cursor.getInt(9),
                    tmdb_release_date   = cursor.getString(10),
                    is_top10            = cursor.getInt(11),
                    is_novidade         = cursor.getInt(12),
                    tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                    backdrop_path       = cursor.getString(14)
                )
            }
            cursor.close()
            resultado
        }

    private fun buscarIdFeaturedBanner(
        card: View,
        title: String,
        isSeriesRC: Boolean,
        contentIdRC: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var streamId: Int? = null
                var seriesId: Int? = null
                var iconResolvido = ""

                val idForcado = contentIdRC.trim().toIntOrNull()?.takeIf { it > 0 }

                if (idForcado != null) {
                    if (isSeriesRC) {
                        val serie = buscarSeriePorId(idForcado)
                        seriesId = idForcado
                        iconResolvido = serie?.cover ?: ""
                    } else {
                        val vod = buscarVodPorId(idForcado)
                        streamId = idForcado
                        iconResolvido = vod?.stream_icon ?: ""
                    }
                } else if (isSeriesRC) {
                    val idCacheado = featuredResolvedIdCache[title]
                    val candidatos = buscarSeriesPorNomeTolerante(title)

                    val serieResolvida = when {
                        idCacheado != null -> candidatos.firstOrNull { it.series_id == idCacheado }
                            ?: candidatos.firstOrNull()
                        candidatos.size == 1 -> candidatos.first()
                        candidatos.size > 1 -> {
                            candidatos.firstOrNull { serieTemEpisodiosValidos(it.series_id) }
                                ?: candidatos.first()
                        }
                        else -> null
                    }

                    if (serieResolvida != null) {
                        seriesId = serieResolvida.series_id
                        iconResolvido = serieResolvida.cover ?: ""
                        featuredResolvedIdCache[title] = serieResolvida.series_id
                    }
                } else {
                    val candidatosVod = buscarVodsPorNomeTolerante(title)
                    val vod = candidatosVod.firstOrNull()
                    if (vod != null) {
                        streamId = vod.stream_id
                        iconResolvido = vod.stream_icon ?: ""
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    val encontrado = idForcado != null || streamId != null || seriesId != null
                    if (!encontrado) {
                        if (ContentRepository.pronto) {
                            card.visibility = View.GONE
                        }
                        return@withContext
                    }

                    card.visibility = View.VISIBLE
                    ultimoFeaturedTitleAplicado = title
                    featuredBannerEncontrado = true

                    val launchDetail: () -> Unit = {
                        val intent = if (isSeriesRC)
                            Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", seriesId ?: 0) }
                        else
                            Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", streamId ?: 0) }
                        intent.putExtra("name", title)
                        intent.putExtra("icon", iconResolvido)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("is_series", isSeriesRC)
                        startActivity(intent)
                    }

                    card.findViewById<View>(R.id.btnFeaturedAssistir).setOnClickListener { launchDetail() }
                    card.findViewById<View>(R.id.btnFeaturedDetalhes).setOnClickListener { launchDetail() }
                    card.setOnClickListener { launchDetail() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && ContentRepository.pronto) card.visibility = View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        atualizarConfigSelos()
        try {
            lifecycleScope.launch(Dispatchers.Main) {
                delay(400)
                if (!isFinishing && !isDestroyed) {
                    setupFirebaseRemoteConfig()
                }
            }

            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            currentProfile = prefs.getString("last_profile_name", currentProfile) ?: "Padrao"
            currentProfileIcon = prefs.getString("last_profile_icon", currentProfileIcon)
                ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon

            if (bannerFila.size >= 2) {
                bannerFilaIndex = (bannerFilaIndex + 1) % bannerFila.size
                mostrarItemNoBanner(bannerFila[bannerFilaIndex])
                iniciarCarrosselBanner()
            } else if (bannerFila.size == 1) {
                iniciarCarrosselBanner()
            }

            if (gameRotationList.size >= 2) {
                iniciarRotacaoJogos()
            }

            carregarContinuarAssistindoLocal()
            setupBottomNavigation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacksAndMessages(null)
        gameRotationHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerHandler.removeCallbacksAndMessages(null)
        bannerBuscaJob?.cancel()
        gameBannerCrestJob?.cancel()
        gameRotationHandler.removeCallbacksAndMessages(null)
        gameRotationFetchJob?.cancel()
        top10FilmesJob?.cancel()
        top10SeriesJob?.cancel()
        top10FilmesBrasilJob?.cancel()
        top10SeriesBrasilJob?.cancel()
        removerOuvinteSync?.invoke()
        removerOuvinteSync = null
        wordmarkScrollListener?.let {
            binding.root.viewTreeObserver.removeOnScrollChangedListener(it)
        }
        wordmarkScrollListener = null
        wordmarkRef = null
    }

    // ✅ NOVO: observa a LiveData que já existe no StreamDao
    // (getDownloadsByProfile) pra manter o badge circular do botão de
    // Downloads sempre em dia — sem polling, sem consulta manual. O Room
    // já notifica essa LiveData sozinho toda vez que a tabela "downloads"
    // muda (novo download, progresso, pausa, exclusão, conclusão), e a
    // LiveData só entrega os valores quando a Home está em STARTED/RESUMED
    // — ou seja, nada é consultado à toa com a tela em segundo plano.
    //
    // Registrado uma única vez no onCreate, com o currentProfile já
    // resolvido; se o usuário trocar de perfil, a HomeActivity de hoje é
    // recriada (mesmo padrão já usado no resto do app), então não precisa
    // re-registrar em onResume.
    private fun observarBadgeDownloads() {
        database.streamDao().getDownloadsByProfile(currentProfile).observe(this) { lista ->
            atualizarBadgeDownloads(lista)
        }
    }

    // Conta só o que ainda não terminou (na fila + baixando + pausado) —
    // BAIXADO e ERRO não entram na contagem, então o número reflete
    // exatamente o que ainda está em andamento.
    private fun atualizarBadgeDownloads(lista: List<DownloadEntity>) {
        if (isFinishing || isDestroyed) return
        val total = lista.count {
            it.status == DownloadHelper.STATE_NA_FILA ||
                it.status == DownloadHelper.STATE_BAIXANDO ||
                it.status == DownloadHelper.STATE_PAUSADO
        }
        val badge = binding.root.findViewById<TextView>(R.id.tvDownloadsBadge) ?: return
        if (total > 0) {
            badge.text = if (total > 99) "99+" else total.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun setupClicks() {
        val cards = listOfNotNull(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardDownloads, binding.cardRetroGames)
        cards.forEach { card ->
            card.isFocusable = true
            card.isClickable = true
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                } else {
                    card.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            card.setOnClickListener {
                when (card.id) {
                    R.id.cardLiveTv -> {
                        val intent = Intent(this, LiveTvActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", true)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardMovies -> {
                        val intent = Intent(this, VodActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardSeries -> {
                        val intent = Intent(this, SeriesActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardDownloads -> {
                        startActivity(Intent(this, DownloadsActivity::class.java))
                    }
                    R.id.cardRetroGames -> {
                        startActivity(Intent(this, RetroGamesActivity::class.java))
                    }
                }
            }
        }

        if (isTelevisionDevice()) {
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardMovies.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardLiveTv.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardSeries.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardDownloads.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardDownloads.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
        }

        setupBannerFocusParaTv()
    }

    private fun setupBannerFocusParaTv() {
        if (!isTelevisionDevice()) return

        binding.cardFeaturedBanner?.let { featured ->
            featured.isFocusable = true
            featured.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    featured.animate().scaleX(1.04f).scaleY(1.04f).translationZ(10f).setDuration(200).start()
                } else {
                    featured.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            featured.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    (binding.cardGameBanner ?: binding.cardLiveTv).requestFocus(); true
                } else false
            }
        }

        binding.cardGameBanner?.let { game ->
            game.isFocusable = true
            game.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    game.animate().scaleX(1.04f).scaleY(1.04f).translationZ(10f).setDuration(200).start()
                } else {
                    game.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            game.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        (binding.cardFeaturedBanner ?: binding.bannerViewPager)?.requestFocus(); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.cardLiveTv.requestFocus(); true
                    }
                    else -> false
                }
            }
        }

        binding.bannerViewPager?.let { pager ->
            val destinoAbaixo = binding.cardFeaturedBanner ?: binding.cardGameBanner ?: binding.cardLiveTv
            pager.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    destinoAbaixo.requestFocus(); true
                } else false
            }
        }
        if (binding.cardFeaturedBanner != null || binding.cardGameBanner != null) {
            val origemAcima = binding.cardFeaturedBanner ?: binding.cardGameBanner
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                when {
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN -> {
                        binding.cardMovies.requestFocus(); true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                        origemAcima?.requestFocus(); true
                    }
                    else -> false
                }
            }
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair e desconectar?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit().clear().apply()
                ContentRepository.limpar()
                SyncManager.resetarSessao()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Não", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mostrarDialogoSair()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                val vodItems = mutableListOf<VodItem>()
                val seriesMap = mutableMapOf<String, Boolean>()
                val seriesJaAdicionadas = mutableSetOf<String>()

                for (item in historyList) {
                    var finalId = item.stream_id.toString()
                    var finalName = limparNomeExibicao(item.name)
                    var finalIcon = item.icon ?: ""
                    val isSeries = item.is_series
                    var finalLogo: String? = null

                    val progresso = if (item.duration > 0) {
                        ((item.last_position * 100) / item.duration).toInt().coerceIn(0, 100)
                    } else -1

                    if (isSeries) {
                        try {
                            var cleanName = item.name.replace(Regex("(?i)^(S\\d+E\\d+|T\\d+E\\d+|\\d+x\\d+|E\\d+)\\s*(-|:)?\\s*"), "")
                            if (cleanName.contains(":")) cleanName = cleanName.substringBefore(":")
                            cleanName = cleanName.replace(Regex("(?i)\\s+(S\\d+|T\\d+|E\\d+|Ep\\d+|Temporada|Season|Episode|Capitulo|\\d+x\\d+).*"), "")
                            if (cleanName.contains(" - ")) cleanName = cleanName.substringBefore(" - ")
                            cleanName = cleanName.trim()

                            val semExclusao = emptySet<Int>()
                            val serieResolvida = querySerieEntityExato(cleanName, semExclusao)
                                ?: querySerieEntity(likeExato(cleanName), semExclusao)
                                ?: palavraMaisLonga(cleanName)?.let { querySerieEntity("%$it%", semExclusao) }

                            if (serieResolvida != null) {
                                val realSeriesId = serieResolvida.series_id.toString()
                                if (seriesJaAdicionadas.contains(realSeriesId)) {
                                    continue
                                }
                                finalId = realSeriesId
                                finalName = limparNomeExibicao(serieResolvida.name)
                                finalIcon = serieResolvida.cover ?: ""
                                finalLogo = serieResolvida.logo_url
                                seriesJaAdicionadas.add(realSeriesId)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    } else {
                        try {
                            finalLogo = database.streamDao().getVodByStreamId(item.stream_id)?.logo_url
                        } catch (e: Exception) { e.printStackTrace() }
                    }

                    vodItems.add(
                        VodItem(
                            id = finalId,
                            name = finalName,
                            streamIcon = finalIcon,
                            isSerie = isSeries,
                            logoUrl = finalLogo,
                            progressoAssistido = progresso
                        )
                    )
                    seriesMap[finalId] = isSeries
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val tvTitle = binding.root.findViewById<TextView>(R.id.tvContinueWatching)
                    if (vodItems.isNotEmpty()) {
                        binding.layoutContinueHeader?.visibility = View.VISIBLE
                        tvTitle?.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems, useWideLayout = true) { selected ->
                            val isSeries = seriesMap[selected.id] ?: false
                            val intent = if (isSeries) {
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", selected.id.toIntOrNull() ?: 0)
                                }
                            } else {
                                Intent(this@HomeActivity, DetailsActivity::class.java).apply {
                                    putExtra("stream_id", selected.id.toIntOrNull() ?: 0)
                                }
                            }
                            intent.putExtra("name", selected.name)
                            intent.putExtra("icon", selected.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        binding.layoutContinueHeader?.visibility = View.GONE
                        tvTitle?.visibility = View.GONE
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    inner class BannerAdapter(private var currentItem: Any?) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

        fun updateItem(newItem: Any) {
            currentItem = newItem
            notifyItemChanged(0)
        }

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false)
            return BannerViewHolder(view)
        }

        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
            currentItem?.let { holder.bind(it) }
        }

        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView    = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView   = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View        = itemView.findViewById(R.id.btnBannerPlay)
            private val btnInfo: View?       = try { itemView.findViewById(R.id.btnBannerInfo) } catch (e: Exception) { null }

            fun bind(item: Any) {
                bannerRequestId++
                val meuRequestId = bannerRequestId

                var title    = ""
                var icon     = ""
                var id       = 0
                var isSeries = false
                var logoSalva: String? = null
                var tmdbIdSalvo: Int? = null
                var backdropPathSalvo: String? = null

                when (item) {
                    is VodEntity    -> {
                        title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false
                        logoSalva = item.logo_url; tmdbIdSalvo = item.tmdb_id; backdropPathSalvo = item.backdrop_path
                    }
                    is SeriesEntity -> {
                        title = item.name; icon = item.cover ?: "";       id = item.series_id; isSeries = true
                        logoSalva = item.logo_url; tmdbIdSalvo = item.tmdb_id; backdropPathSalvo = item.backdrop_path
                    }
                }

                val cleanTitle = limparNomeExibicao(title)
                val chaveCache = "${if (isSeries) "tv" else "movie"}_$id"

                val launchDetail: () -> Unit = {
                    val intent = if (isSeries)
                        Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
                    else
                        Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                    intent.putExtra("name", title)
                    intent.putExtra("icon", icon)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("is_series", isSeries)
                    startActivity(intent)
                }
                btnPlay.setOnClickListener { launchDetail() }
                btnInfo?.setOnClickListener { launchDetail() }
                itemView.setOnClickListener { launchDetail() }

                val cacheado = bannerAssetsCache[chaveCache]
                if (cacheado != null) {
                    aplicarBannerCompleto(imgBanner, imgLogo, tvTitle, cacheado.backdropUrl ?: "", icon, cacheado.logoUrl, cacheado.cleanTitle)
                    return
                }

                aplicarBannerCompleto(imgBanner, imgLogo, tvTitle, icon, icon, null, cleanTitle)

                resolverEAplicarBannerCompleto(
                    titulo = title, cleanTitle = cleanTitle, isSeries = isSeries, id = id,
                    fallbackIcon = icon, chaveCache = chaveCache, requestId = meuRequestId,
                    imgBanner = imgBanner, imgLogo = imgLogo, tvTitle = tvTitle,
                    logoSalvoNoBanco = logoSalva,
                    tmdbIdSalvo = tmdbIdSalvo,
                    backdropPathSalvo = backdropPathSalvo
                )
            }
        }
    }

    inner class Top10Adapter(
        private var list: List<VodItem>,
        private val onItemClick: (VodItem) -> Unit
    ) : RecyclerView.Adapter<Top10Adapter.ViewHolder>() {

        fun updateList(newList: List<VodItem>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = list.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    list[oldPos].id == newList[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    list[oldPos] == newList[newPos]
            })
            list = newList
            diff.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
            val tvRank: TextView    = view.findViewById(R.id.tvRankNumber)
            val tvTitle: TextView   = view.findViewById(R.id.tvTitle)
            // ✅ NOVO: selo de status (Novidade/Nova Temporada/Novo
            // Episódio/Em Breve) — mesmas views/mesma lógica de
            // prioridade do HomeRowAdapter, pra este card bater com os
            // outros ("Filmes/Séries Para Você").
            val llBadgeStatus: LinearLayout? = view.findViewById(R.id.llBadgeStatus)
            val tvBadgeStatus: TextView?     = view.findViewById(R.id.tvBadgeNew)
            val tvBadgeStatusLine2: TextView? = view.findViewById(R.id.tvBadgeNewLine2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top10_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvRank.text  = (position + 1).toString()
            holder.tvTitle.text = item.name

            // Mesma prioridade do HomeRowAdapter: Nova Temporada > Novo
            // Episódio > Nova Temporada Em Breve > Novo Episódio Em
            // Breve > Novidade > nenhum.
            val llBadge = holder.llBadgeStatus
            val tvBadge = holder.tvBadgeStatus
            val tvBadgeLinha2 = holder.tvBadgeStatusLine2
            if (llBadge != null && tvBadge != null && tvBadgeLinha2 != null) {
                when {
                    item.isNovaTemporada -> {
                        tvBadge.text = "NOVA TEMPORADA"
                        tvBadgeLinha2.visibility = View.GONE
                        llBadge.visibility = View.VISIBLE
                    }
                    item.isNovoEpisodio -> {
                        tvBadge.text = "NOVO EPISÓDIO"
                        tvBadgeLinha2.visibility = View.GONE
                        llBadge.visibility = View.VISIBLE
                    }
                    item.isNovaTemporadaEmBreve -> {
                        tvBadge.text = "NOVA TEMPORADA"
                        tvBadgeLinha2.visibility = View.VISIBLE
                        llBadge.visibility = View.VISIBLE
                    }
                    item.isNovoEpisodioEmBreve -> {
                        tvBadge.text = "NOVO EPISÓDIO"
                        tvBadgeLinha2.visibility = View.VISIBLE
                        llBadge.visibility = View.VISIBLE
                    }
                    item.isNovidade -> {
                        tvBadge.text = "NOVIDADE"
                        tvBadgeLinha2.visibility = View.GONE
                        llBadge.visibility = View.VISIBLE
                    }
                    else -> {
                        llBadge.visibility = View.GONE
                    }
                }
            }

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(item.streamIcon)
                .override(160, 240)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate()
                .placeholder(R.drawable.ic_launcher)
                .into(holder.ivPoster)

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX    = if (hasFocus) 1.08f else 1.0f
                v.scaleY    = if (hasFocus) 1.08f else 1.0f
                v.elevation = if (hasFocus) 12f else 0f
            }
        }

        override fun getItemCount() = list.size
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}