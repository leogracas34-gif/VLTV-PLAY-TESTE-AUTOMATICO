package com.vltv.play

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.vltv.play.download.VltvDownloadTracker
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.ArrayList

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.WatchHistoryEntity

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loading: View
    private lateinit var tvChannelName: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnAspect: ImageButton
    private lateinit var topBar: View

    private lateinit var nextEpisodeContainer: View
    private lateinit var tvNextEpisodeTitle: TextView
    private lateinit var tvSeasonEndWarning: TextView
    private lateinit var btnPlayNextEpisode: Button

    // ✅ NOVO: views do preview de miniatura (estilo Netflix) que aparece
    // acima da barra de progresso enquanto o usuário arrasta o dedo.
    private lateinit var thumbnailPreviewContainer: FrameLayout
    private lateinit var imgThumbnailPreview: ImageView
    private lateinit var tvThumbnailTime: TextView

    private var player: ExoPlayer? = null

    private var streamId = 0
    private var streamExtension = "ts"
    private var streamType = "live"
    private var nextStreamId: Int = 0
    private var nextChannelName: String? = null
    private var startPositionMs: Long = 0L

    private var currentProfile: String = "Padrao"

    private var offlineUri: String? = null
    private var offlineUrl: String? = null

    // MOCHILA DE EPISODIOS (agora cobre TODAS as temporadas da série, em
    // ordem — é o que permite navegar de uma temporada pra outra sem
    // travar no último episódio).
    private var episodeList = ArrayList<Int>()
    // ✅ NOVO: arrays paralelos ao episodeList — mesma posição/índice.
    private var episodeSeasons = ArrayList<Int>()   // temporada de cada episódio
    private var episodeTitles = ArrayList<String>() // título pronto ("T1E05 - Nome") de cada episódio
    private var episodeExts = ArrayList<String>()   // extensão do container de cada episódio

    // ✅ Lista de reserva agora vem da MESMA fonte do resto do app
    // (XtreamApi.SERVERS -> dns_config.json da VPS, com cópia salva no
    // aparelho e FALLBACK embutido). Não existe mais lista fixa aqui.
    private val serverBackupList: List<String>
        get() = XtreamApi.SERVERS

    // Lista Ativa
    private val activeServerList = mutableListOf<String>()

    private var serverIndex = 0
    private val extensoesTentativa = mutableListOf<String>()
    private var extIndex = 0

    // ✅ CENTRALIZADO: o User-Agent agora vem de NetworkConfig, o mesmo
    // valor usado em todas as telas.
    private val USER_AGENT = NetworkConfig.USER_AGENT

    private val database by lazy { AppDatabase.getDatabase(this) }

    private val handler = Handler(Looper.getMainLooper())

    // Botão "Próximo episódio" (ver nextChecker). Antes aparecia por
    // PORCENTAGEM (98,5%), o que dava tempos diferentes conforme a duração
    // do episódio. Agora aparece por SEGUNDOS RESTANTES:
    //  - sem dado da VPS pra essa série: faltando CREDITOS_PADRAO_SEG (50s);
    //  - com dado: ANTECEDENCIA_SEG (10s) antes do ponto em que os créditos
    //    começam, aprendido com os outros clientes e guardado na VPS.
    private var countdownAtivo = false
    private var countdownSegundos = CREDITOS_PADRAO_SEG
    private var nextEpisodeLaunched = false

    // ═══════════════════════════════════════════════════════════════
    //  CRÉDITOS APRENDIDOS (guardados na VPS, /credits)
    // ═══════════════════════════════════════════════════════════════
    // Quantos segundos faltavam pro fim do episódio quando os créditos
    // começam nessa série. null = ninguém ensinou ainda (usa o padrão).
    private var creditosRestantesSeg: Int? = null

    // Identidade da série no backend. O PlayerActivity só recebe o id do
    // EPISÓDIO, mas a mochila (episode_list) cobre a série inteira em
    // ordem — então o id do 1º episódio dela é uma chave estável por
    // série, e não precisou mexer em nenhuma outra tela. 0 = não se aplica
    // (filme, canal ao vivo ou episódio baixado).
    private var serieChaveCreditos = 0

    // Momento (elapsedRealtime) em que o botão apareceu — usado pra só
    // aprender com o toque de quem ESPEROU o botão (ver aprenderPontoCreditos).
    private var botaoVisivelDesdeMs = 0L

    // Garante um único envio por execução do player.
    private var creditosEnviados = false

    // ═══════════════════════════════════════════════════════════════
    //  PREVIEW DE MINIATURA (SCRUBBING) — estilo Netflix
    // ═══════════════════════════════════════════════════════════════
    // ✅ NOVO: referência à barra de progresso do controller padrão do
    // Media3 (exo_progress). É nela que escutamos o gesto de arrastar.
    private var timeBarView: TimeBar? = null

    // ✅ NOVO: um único MediaMetadataRetriever por "sessão de arrasto".
    // Criar e configurar (setDataSource) uma vez só e reaproveitar pra
    // cada frame pedido durante o arrasto é MUITO mais rápido do que
    // recriar a cada movimento do dedo — setDataSource é a parte cara
    // (abre conexão com o servidor), getFrameAtTime depois é rápido.
    // Usado pra fazer preview de arrasto em VOD online (streaming).
    // Serve como MODO DE RESERVA: só entra em ação pra um trecho que a
    // pré-geração (trickplayCache) ainda não alcançou.
    private var thumbnailRetriever: MediaMetadataRetriever? = null
    private var thumbnailSourceUrl: String? = null

    // ✅ NOVO: job da extração de frame em andamento — cancelado e
    // recriado a cada novo movimento do dedo (debounce), pra não
    // enfileirar dezenas de pedidos de frame se o usuário arrastar
    // rápido de um lado pro outro da barra. Só é usado no modo de
    // reserva (quando o trickplayCache ainda não tem nada pra aquele
    // ponto) — quando já tem, a exibição é instantânea e não passa por
    // aqui.
    private var thumbnailScrubJob: Job? = null

    // ✅ NOVO: cache de miniaturas pré-geradas em segundo plano assim que
    // o conteúdo começa a tocar (estilo "trickplay" da Netflix) — ver
    // ThumbnailTrickplayCache.kt. É o que torna o preview de arrasto
    // instantâneo: durante o arrasto, a gente só BUSCA nesse cache em vez
    // de decodificar o frame na hora.
    private val trickplayCache = ThumbnailTrickplayCache()

    // ✅ NOVO: garante que a pré-geração de miniaturas só é disparada uma
    // vez por reprodução bem-sucedida (não a cada tentativa de servidor
    // que falhou). Resetado no início de cada iniciarPlayer().
    private var pregeracaoDeThumbnailsDisparada = false

    // ✅ CORRIGIDO: preview de miniatura ao arrastar agora é só pra VOD em
    // streaming ONLINE (filme "movie" / série "series"). O motor offline
    // (player-fantasma lendo do cache do download) foi removido — não
    // conseguiu ficar pronto de forma confiável em teste real, então por
    // ora o preview de arrasto em conteúdo baixado fica desativado (a
    // barra de progresso continua funcionando normalmente, só sem a
    // mini-tela). Pra live também não faz sentido (sem seek).
    private fun scrubPreviewSuportado(): Boolean =
        streamType == "movie" || streamType == "series"

    private val nextChecker = object : Runnable {
        override fun run() {
            val p = player ?: return
            // ✅ CORRIGIDO: antes só rodava com streamType == "series"
            // (online). Agora também considera "series_offline", pra o
            // botão "Próximo Episódio" e o countdown aparecerem quando o
            // usuário está assistindo episódios baixados.
            if (!isSeriesType() || nextStreamId == 0) return
            if (nextEpisodeLaunched) return

            // Só executa se o player estiver realmente reproduzindo (evita interferir no buffering)
            if (p.playbackState != Player.STATE_READY || !p.isPlaying) {
                handler.postDelayed(this, 1000L)
                return
            }

            val dur = p.duration
            val pos = p.currentPosition
            // Ignora duração inválida (pode acontecer durante buffering)
            if (dur <= 0 || pos < 0) {
                handler.postDelayed(this, 1000L)
                return
            }

            // ✅ NOVO: decide por SEGUNDOS RESTANTES (não mais por % do
            // episódio) — ver limiarBotaoSeg().
            val restanteSeg = ((dur - pos) / 1000L).toInt()

            if (restanteSeg <= limiarBotaoSeg()) {
                if (!countdownAtivo) {
                    countdownAtivo = true
                    // calcula e exibe (se for o caso) o aviso de fim de
                    // temporada assim que o botão começa a aparecer — não
                    // precisa recalcular a cada tick.
                    atualizarAvisoTemporada()
                }

                // O número mostrado agora é o tempo REAL que falta pro
                // episódio acabar (antes era um contador de 50s que nunca
                // chegava a zero porque o episódio terminava antes).
                countdownSegundos = restanteSeg.coerceAtLeast(0)
                // Com créditos longos o botão pode aparecer com vários
                // minutos faltando — mostra m:ss em vez de "430s".
                tvNextEpisodeTitle.text = if (countdownSegundos > 60) {
                    "Próximo episódio em ${countdownSegundos / 60}:${"%02d".format(countdownSegundos % 60)}"
                } else {
                    "Próximo episódio em ${countdownSegundos}s"
                }

                if (nextEpisodeContainer.visibility != View.VISIBLE) {
                    nextEpisodeContainer.visibility = View.VISIBLE
                    botaoVisivelDesdeMs = SystemClock.elapsedRealtime()
                    btnPlayNextEpisode.requestFocus()
                }

                if (restanteSeg <= 0) {
                    nextEpisodeContainer.visibility = View.GONE
                    tvSeasonEndWarning.visibility = View.GONE
                    abrirProximoEpisodio()
                    return
                }

                handler.postDelayed(this, 1000L)
            } else {
                nextEpisodeContainer.visibility = View.GONE
                tvSeasonEndWarning.visibility = View.GONE
                countdownAtivo    = false
                countdownSegundos = CREDITOS_PADRAO_SEG
                botaoVisivelDesdeMs = 0L
                handler.postDelayed(this, 1000L)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CRÉDITOS APRENDIDOS — implementação
    // ═══════════════════════════════════════════════════════════════

    // Com quantos segundos restantes o botão deve aparecer agora.
    private fun limiarBotaoSeg(): Int {
        val aprendido = creditosRestantesSeg ?: return CREDITOS_PADRAO_SEG
        return aprendido + ANTECEDENCIA_SEG
    }

    // Chamado uma vez no onCreate. Só vale pra série ONLINE: episódio
    // baixado tem a mochila só com o que foi baixado (a chave mudaria) e
    // muitas vezes está sem internet — usa o padrão de 50s.
    private fun iniciarCreditosAprendidos() {
        if (streamType != "series") return
        serieChaveCreditos = episodeList.firstOrNull() ?: 0
        if (serieChaveCreditos == 0) return

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""
        val chavePref = "creditos_seg_$serieChaveCreditos"

        // 1) Cópia local (instantânea): vale pro episódio seguinte da
        //    mesma série já abrir com o botão no lugar certo, mesmo sem rede.
        val local = prefs.getInt(chavePref, -1)
        if (local in CREDITOS_MIN_SEG..CREDITOS_MAX_SEG) creditosRestantesSeg = local

        // 2) VPS (em segundo plano): o valor do backend é a MEDIANA das
        //    marcações de todos os clientes, então sobrescreve a cópia local.
        if (dns.isBlank()) return
        val chave = serieChaveCreditos
        lifecycleScope.launch {
            val daVps = HomeApiClient.buscarCreditos(dns, chave) ?: return@launch
            if (daVps in CREDITOS_MIN_SEG..CREDITOS_MAX_SEG) {
                creditosRestantesSeg = daVps
                prefs.edit().putInt(chavePref, daVps).apply()
            }
        }
    }

    // Aprende o ponto dos créditos com um SINAL CONFIÁVEL do cliente:
    //  - toque no botão "Próximo episódio" (toqueNoBotao = true): só vale
    //    se o botão já estava na tela há 6s ou mais. Quem toca no mesmo
    //    instante em que o botão aparece está só reagindo a ele, não
    //    mostrando onde os créditos começam — e aprender isso faria o
    //    botão aparecer 10s mais cedo a cada episódio.
    //  - sair pelo "voltar" na reta final (75%+ assistido, 20s a 10min restantes).
    // NÃO aprende com home/tela apagada/PiP (onStop), nem quando o próximo
    // episódio abre sozinho (o cliente não escolheu nada).
    private fun aprenderPontoCreditos(toqueNoBotao: Boolean) {
        if (streamType != "series" || serieChaveCreditos == 0) return
        if (creditosEnviados || nextEpisodeLaunched) return
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 0 || pos < 0) return

        val restanteSeg = ((dur - pos) / 1000L).toInt()
        if (toqueNoBotao) {
            val esperou = botaoVisivelDesdeMs != 0L &&
                SystemClock.elapsedRealtime() - botaoVisivelDesdeMs >= 6_000L
            if (!esperou) return
        } else {
            // Saída pelo "voltar": só conta se já estava na reta final do
            // episódio (75% ou mais assistido) e dentro da faixa aceita.
            // A trava dos 75% evita aprender com quem desistiu no meio do
            // episódio — sem ela, fechar aos 20min de um episódio de 1h
            // marcaria "40 min restantes" como ponto dos créditos.
            val progresso = pos.toFloat() / dur.toFloat()
            if (restanteSeg !in 20..CREDITOS_MAX_SEG || progresso < 0.75f) return
        }
        if (restanteSeg !in CREDITOS_MIN_SEG..CREDITOS_MAX_SEG) return

        creditosEnviados = true
        creditosRestantesSeg = restanteSeg

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("creditos_seg_$serieChaveCreditos", restanteSeg).apply()

        // historicoScope (e não lifecycleScope): sobrevive ao fechamento
        // da tela — no "voltar" a Activity morre em milissegundos e o
        // lifecycleScope cancelaria o envio no meio. Mesmo padrão já usado
        // pra gravar o histórico.
        val dns = prefs.getString("dns", "") ?: ""
        val chave = serieChaveCreditos
        if (dns.isNotBlank()) {
            historicoScope.launch { HomeApiClient.enviarCreditos(dns, chave, restanteSeg) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        playerView           = findViewById(R.id.playerView)
        loading              = findViewById(R.id.loading)
        tvChannelName        = findViewById(R.id.tvChannelName)
        tvNowPlaying         = findViewById(R.id.tvNowPlaying)
        btnAspect            = findViewById(R.id.btnAspect)
        topBar               = findViewById(R.id.topBar)
        nextEpisodeContainer = findViewById(R.id.nextEpisodeContainer)
        tvNextEpisodeTitle   = findViewById(R.id.tvNextEpisodeTitle)
        tvSeasonEndWarning   = findViewById(R.id.tvSeasonEndWarning)
        btnPlayNextEpisode   = findViewById(R.id.btnPlayNextEpisode)
        thumbnailPreviewContainer = findViewById(R.id.thumbnailPreviewContainer)
        imgThumbnailPreview       = findViewById(R.id.imgThumbnailPreview)
        tvThumbnailTime           = findViewById(R.id.tvThumbnailTime)

        btnPlayNextEpisode.isFocusable = true
        btnPlayNextEpisode.isFocusableInTouchMode = true

        btnPlayNextEpisode.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.setBackgroundResource(R.drawable.bg_focus_neon)
                btnPlayNextEpisode.setTextColor(Color.WHITE)
            } else {
                view.setBackgroundResource(0)
                btnPlayNextEpisode.setTextColor(Color.WHITE)
            }
        }

        streamId        = intent.getIntExtra("stream_id", 0)
        streamExtension = intent.getStringExtra("stream_ext") ?: "ts"
        streamType      = intent.getStringExtra("stream_type") ?: "live"
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        nextStreamId    = intent.getIntExtra("next_stream_id", 0)
        nextChannelName = intent.getStringExtra("next_channel_name")
        currentProfile  = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        val listaExtra = intent.getIntegerArrayListExtra("episode_list")
        if (listaExtra != null) episodeList = listaExtra

        // ✅ NOVO: lê os arrays paralelos (temporada/título/extensão) que
        // agora cobrem TODAS as temporadas da série.
        val listaSeasons = intent.getIntegerArrayListExtra("episode_seasons")
        if (listaSeasons != null) episodeSeasons = listaSeasons
        val listaTitles = intent.getStringArrayListExtra("episode_titles")
        if (listaTitles != null) episodeTitles = listaTitles
        val listaExts = intent.getStringArrayListExtra("episode_exts")
        if (listaExts != null) episodeExts = listaExts

        calcularProximoEpisodioAutomaticamente()
        iniciarCreditosAprendidos()

        // ✅ NOVO: offlineUri agora é o "content ID" do Media3 (gravado em
        // DownloadEntity.file_path), e offlineUrl é a URL original usada
        // pra baixar (gravada em DownloadEntity.download_url). As telas de
        // Downloads (DownloadsActivity/SeriesEpisodesActivity) precisam
        // passar os dois extras ao abrir o player em modo offline.
        offlineUri = intent.getStringExtra("offline_uri")
        offlineUrl = intent.getStringExtra("offline_url")

        val channelName = intent.getStringExtra("channel_name") ?: ""
        tvChannelName.text = if (channelName.isNotBlank()) channelName else "Canal"
        tvNowPlaying.text  = if (streamType == "live") "Carregando programacao..." else ""

        btnAspect.setOnClickListener {
            val current = playerView.resizeMode
            val next = when (current) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                    Toast.makeText(this, "Modo: Preencher", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                    Toast.makeText(this, "Modo: Zoom", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                else -> {
                    Toast.makeText(this, "Modo: Ajustar", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
            playerView.resizeMode = next
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
            }
        )

        // ✅ NOVO: liga o listener de arrasto da barra de progresso pro
        // preview de miniatura. Feito uma vez só, aqui no onCreate — a
        // TimeBar em si (view do controller padrão) não muda durante o
        // ciclo de vida da Activity.
        setupThumbnailScrubbing()

        btnPlayNextEpisode.setOnClickListener {
            if (nextStreamId != 0) {
                aprenderPontoCreditos(toqueNoBotao = true)
                abrirProximoEpisodio()
            } else {
                Toast.makeText(this, "Sem proximo episodio", Toast.LENGTH_SHORT).show()
            }
        }

        // Extensoes - logica original preservada
        if (streamType == "movie") {
            extensoesTentativa.add(streamExtension)
            extensoesTentativa.add("mp4")
            extensoesTentativa.add("mkv")
        } else {
            // ✅ OTIMIZADO: "ts" primeiro. O .ts direto abre mais rápido
            // que o .m3u8 (que precisa baixar a playlist e depois os
            // segmentos) — e é a mesma ordem usada na LiveTvActivity.
            extensoesTentativa.add("ts")
            extensoesTentativa.add("m3u8")
            extensoesTentativa.add("")
        }

        setupServerList()
        iniciarPlayer()

        if (streamType == "live" && streamId != 0) {
            carregarEpg()
        }

        // ✅ CORRIGIDO: antes só ativava pra série ONLINE. Episódios
        // baixados (series_offline) já têm a mochila de episódios própria
        // montada em SeriesEpisodesActivity, então o checker também deve
        // rodar aqui pra mostrar o botão "Próximo Episódio".
        if (isSeriesType() && nextStreamId != 0) {
            handler.post(nextChecker)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PREVIEW DE MINIATURA (SCRUBBING) — implementação
    // ═══════════════════════════════════════════════════════════════
    private fun setupThumbnailScrubbing() {
        val tb = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar
        timeBarView = tb
        tb?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                if (!scrubPreviewSuportado()) return
                iniciarSessaoDeThumbnail()
                mostrarPreviewNaPosicao(position)
                agendarExtracaoDeFrame(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                if (!scrubPreviewSuportado()) return
                mostrarPreviewNaPosicao(position)
                agendarExtracaoDeFrame(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                thumbnailPreviewContainer.visibility = View.GONE
                encerrarSessaoDeThumbnail()
            }
        })
    }

    // ✅ CORRIGIDO: motor offline removido — preview de arrasto só existe
    // pra VOD online (streaming), usando MediaMetadataRetriever apontado
    // pra URL que está tocando agora no player. Isso só prepara o motor
    // de RESERVA (sob demanda) — a maior parte das vezes nem chega a ser
    // usado, porque o trickplayCache (pré-gerado em segundo plano assim
    // que o vídeo começa) já tem a miniatura pronta. Ver
    // dispararPregeracaoDeThumbnails().
    private fun iniciarSessaoDeThumbnail() {
        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        if (thumbnailRetriever != null && thumbnailSourceUrl == uri.toString()) return
        encerrarSessaoDeThumbnailOnline()
        thumbnailSourceUrl = uri.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(uri.toString(), mapOf("User-Agent" to USER_AGENT))
                thumbnailRetriever = retriever
            } catch (e: Exception) {
                Log.e("THUMBNAIL_PREVIEW", "Erro ao preparar preview: ${e.message}")
                thumbnailRetriever = null
                thumbnailSourceUrl = null
            }
        }
    }

    // ✅ CORRIGIDO: agora o CAMINHO PRINCIPAL é instantâneo — busca a
    // miniatura mais próxima já pré-gerada em trickplayCache (só uma
    // busca em lista, sem decodificar nada). Isso resolve o delay que
    // existia antes, que vinha de decodificar o frame NA HORA do arrasto.
    //
    // Só cai no modo de reserva (decodificação sob demanda, com debounce
    // de 120ms) quando a pré-geração ainda não alcançou aquele trecho —
    // ex.: usuário arrasta muito rápido nos primeiros segundos de
    // reprodução, antes da pré-geração terminar.
    private fun agendarExtracaoDeFrame(positionMs: Long) {
        val cacheado = trickplayCache.buscarMaisProximo(positionMs)
        if (cacheado != null) {
            thumbnailScrubJob?.cancel()
            imgThumbnailPreview.setImageBitmap(cacheado)
            return
        }

        thumbnailScrubJob?.cancel()

        thumbnailScrubJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(120L)
            val retriever = thumbnailRetriever ?: return@launch
            try {
                val frame = retriever.getFrameAtTime(
                    positionMs * 1000L, // getFrameAtTime espera microssegundos
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    withContext(Dispatchers.Main) {
                        imgThumbnailPreview.setImageBitmap(frame)
                    }
                }
            } catch (e: Exception) {
                // Silencioso: se um frame específico falhar, mantém a
                // última miniatura já exibida em vez de mostrar erro.
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ NOVO: PRÉ-GERAÇÃO DE MINIATURAS (trickplay) EM SEGUNDO PLANO
    // ═══════════════════════════════════════════════════════════════
    // Disparada uma vez, assim que o player fica STATE_READY pela
    // primeira vez numa reprodução bem-sucedida. Roda em background sem
    // travar a reprodução, decodificando miniaturas pequenas espalhadas
    // pelo conteúdo inteiro e guardando em trickplayCache — é isso que
    // torna o preview de arrasto instantâneo na maior parte do tempo.

    private fun dispararPregeracaoDeThumbnails() {
        if (!scrubPreviewSuportado()) return
        val duration = player?.duration ?: return
        if (duration <= 0) return

        val posicoes = calcularPosicoesDeAmostragem(duration)
        val idGeracao = trickplayCache.iniciarNovaGeracao(posicoes)

        // ✅ CORRIGIDO: antes as miniaturas eram geradas em ordem, do
        // início pro fim do vídeo — então um ponto distante (ex.: 1h40
        // de um filme de 2h) só ficava pronto depois de todos os
        // anteriores, o que podia demorar demais. Agora a ordem de
        // geração é "espalhada": primeiro o meio do vídeo, depois o meio
        // de cada metade, depois o meio de cada quarto, e assim por
        // diante — em poucos segundos já existe cobertura de ponta a
        // ponta (mesmo que grosseira), refinando com o tempo.
        val ordemDeGeracao = calcularOrdemDeCoberturaProgressiva(posicoes)

        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(uri.toString(), mapOf("User-Agent" to USER_AGENT))
                for (posicao in ordemDeGeracao) {
                    // Se uma pré-geração mais nova começou nesse meio tempo
                    // (ex.: trocou de servidor), para essa antiga na hora.
                    if (idGeracao != trickplayCache.idAtual()) break
                    try {
                        val frame = retriever.getFrameAtTime(
                            posicao * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        if (frame != null) {
                            trickplayCache.definirFrame(idGeracao, posicao, escalarThumbnail(frame))
                        }
                    } catch (e: Exception) {
                        // Silencioso: pula esse ponto e segue pro próximo.
                    }
                }
            } catch (e: Exception) {
                Log.e("THUMBNAIL_PREGERACAO", "Erro ao pré-gerar miniaturas: ${e.message}")
            } finally {
                try { retriever.release() } catch (e: Exception) { /* silencioso */ }
            }
        }
    }

    // ✅ CORRIGIDO: dobrei a densidade — até 240 miniaturas (era 80), o
    // que dá aproximadamente uma a cada 30s num filme de 2h (era ~90s).
    // Combinado com a ordem de geração espalhada logo abaixo, qualquer
    // ponto do filme tem uma miniatura por perto rapidamente, mesmo
    // pulando direto pra 1h40 de um filme de 2h.
    private fun calcularPosicoesDeAmostragem(duration: Long): List<Long> {
        val maxMiniaturas = 240
        val intervaloMinimoMs = 2000L
        val intervalo = (duration / maxMiniaturas).coerceAtLeast(intervaloMinimoMs)
        val posicoes = ArrayList<Long>()
        var t = 0L
        while (t < duration) {
            posicoes.add(t)
            t += intervalo
        }
        return posicoes
    }

    // ✅ NOVO: reordena uma lista de posições (já ordenada do início pro
    // fim) numa ordem "de cobertura progressiva" — primeiro o ponto do
    // meio, depois o meio de cada metade, depois o meio de cada quarto,
    // e assim por diante (feito em largura, não em profundidade). O
    // resultado tem os MESMOS valores, só em ordem diferente: gerar
    // nessa ordem faz a cobertura se espalhar pelo vídeo inteiro desde
    // as primeiras miniaturas, em vez de avançar sequencialmente do
    // início — é o que resolve o preview não aparecer perto do fim do
    // vídeo enquanto a pré-geração ainda está nos primeiros minutos.
    private fun calcularOrdemDeCoberturaProgressiva(posicoes: List<Long>): List<Long> {
        if (posicoes.size <= 2) return posicoes
        val ordem = ArrayList<Long>(posicoes.size)
        val fila = ArrayDeque<IntRange>()
        fila.add(0 until posicoes.size)
        while (fila.isNotEmpty()) {
            val intervalo = fila.removeFirst()
            if (intervalo.isEmpty()) continue
            val meio = (intervalo.first + intervalo.last) / 2
            ordem.add(posicoes[meio])
            if (intervalo.first <= meio - 1) fila.add(intervalo.first..(meio - 1))
            if (meio + 1 <= intervalo.last) fila.add((meio + 1)..intervalo.last)
        }
        return ordem
    }

    // Reduz cada frame decodificado pro tamanho real de exibição (mesmo
    // tamanho do imgThumbnailPreview no XML: 160x90dp em pixels de
    // bitmap) — evita guardar dezenas de bitmaps em resolução cheia do
    // vídeo na memória.
    private fun escalarThumbnail(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val larguraAlvo = 160
        val alturaAlvo = 90
        if (bitmap.width == larguraAlvo && bitmap.height == alturaAlvo) return bitmap
        val escalado = android.graphics.Bitmap.createScaledBitmap(bitmap, larguraAlvo, alturaAlvo, true)
        if (escalado !== bitmap) {
            try { bitmap.recycle() } catch (e: Exception) { /* silencioso */ }
        }
        return escalado
    }

    // ✅ Move o card de preview pra ficar exatamente acima do ponto da
    // barra onde o dedo está, e atualiza o horário mostrado. Feito
    // sempre no thread principal, de forma instantânea (não espera a
    // imagem do frame chegar — só ela é assíncrona).
    private fun mostrarPreviewNaPosicao(positionMs: Long) {
        val tb = timeBarView as? View ?: return
        val duration = player?.duration ?: return
        if (duration <= 0) return

        if (thumbnailPreviewContainer.visibility != View.VISIBLE) {
            thumbnailPreviewContainer.visibility = View.VISIBLE
        }
        tvThumbnailTime.text = formatarTempo(positionMs)

        val parent = thumbnailPreviewContainer.parent as? View ?: return

        val tbLocation = IntArray(2)
        tb.getLocationOnScreen(tbLocation)
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

        val previewWidth = if (thumbnailPreviewContainer.width > 0)
            thumbnailPreviewContainer.width
        else
            (160 * resources.displayMetrics.density).toInt()
        val previewHeight = if (thumbnailPreviewContainer.height > 0)
            thumbnailPreviewContainer.height
        else
            (120 * resources.displayMetrics.density).toInt()

        var targetX = (tbLocation[0] - parentLocation[0]) + (tb.width * fraction) - (previewWidth / 2f)
        val maxX = (parent.width - previewWidth).coerceAtLeast(0)
        targetX = targetX.coerceIn(0f, maxX.toFloat())

        val margemDp = (12 * resources.displayMetrics.density)
        val targetY = (tbLocation[1] - parentLocation[1]) - previewHeight - margemDp

        thumbnailPreviewContainer.translationX = targetX
        thumbnailPreviewContainer.translationY = targetY
    }

    private fun formatarTempo(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // ✅ NOVO: libera SÓ o motor de miniatura ONLINE (MediaMetadataRetriever).
    // Separado de encerrarSessaoDeThumbnail() pra poder ser chamado
    // isoladamente quando o player online troca de servidor/URL, sem
    // mexer no player-fantasma offline (que nem está em uso nesse caso).
    private fun encerrarSessaoDeThumbnailOnline() {
        thumbnailRetriever?.let {
            try { it.release() } catch (e: Exception) { /* silencioso */ }
        }
        thumbnailRetriever = null
        thumbnailSourceUrl = null
    }

    // NÃO limpa o trickplayCache aqui de propósito — isso é chamado toda
    // vez que o usuário solta o dedo do arrasto, e o cache pré-gerado
    // deve continuar valendo pros próximos arrastos.
    private fun encerrarSessaoDeThumbnail() {
        thumbnailScrubJob?.cancel()
        thumbnailScrubJob = null
        encerrarSessaoDeThumbnailOnline()
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.useController = false
            topBar.visibility = View.GONE
            loading.visibility = View.GONE
            nextEpisodeContainer.visibility = View.GONE
            tvSeasonEndWarning.visibility = View.GONE
            thumbnailPreviewContainer.visibility = View.GONE
        } else {
            playerView.useController = true
            topBar.visibility = if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        }
    }

    private fun setupServerList() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedDns = prefs.getString("dns", "") ?: ""

        activeServerList.clear()

        if (savedDns.isNotEmpty()) {
            var cleanDns = savedDns
            if (cleanDns.endsWith("/")) cleanDns = cleanDns.dropLast(1)
            activeServerList.add(cleanDns)
        }

        for (server in serverBackupList) {
            var cleanServer = server
            if (cleanServer.endsWith("/")) cleanServer = cleanServer.dropLast(1)
            if (cleanServer != savedDns && !savedDns.contains(cleanServer)) {
                activeServerList.add(cleanServer)
            }
        }
    }

    // ✅ CORRIGIDO: agora que episodeList cobre TODAS as temporadas (em
    // ordem), essa busca do "próximo" funciona também no último episódio
    // de uma temporada — ele acha o primeiro episódio da temporada
    // seguinte automaticamente, sem precisar de nenhuma lógica especial.
    // O título do próximo já vem pronto do array episodeTitles (sem
    // depender de regex em cima do texto atual).
    private fun calcularProximoEpisodioAutomaticamente() {
        if (nextStreamId != 0) return
        // ✅ CORRIGIDO: isSeriesType() cobre "series" (online) e
        // "series_offline" (baixado) — a mochila de episódios baixados
        // agora vem da SeriesEpisodesActivity com só os que já tem no
        // dispositivo, então esse cálculo funciona igual pros dois casos.
        if (episodeList.isNotEmpty() && isSeriesType()) {
            val indexAtual = episodeList.indexOf(streamId)
            if (indexAtual != -1 && indexAtual < episodeList.size - 1) {
                nextStreamId = episodeList[indexAtual + 1]
                nextChannelName = episodeTitles.getOrNull(indexAtual + 1) ?: nextChannelName
            }
        }
    }

    // ✅ NOVO: quantos episódios restam na temporada atual, CONTANDO o
    // episódio que está passando agora. Ex.: numa temporada de 10
    // episódios, no episódio 8 retorna 3 (8, 9 e 10).
    private fun episodiosRestantesNaTemporada(): Int {
        if (episodeSeasons.isEmpty()) return -1
        val indexAtual = episodeList.indexOf(streamId)
        if (indexAtual == -1 || indexAtual >= episodeSeasons.size) return -1
        val seasonAtual = episodeSeasons[indexAtual]
        var count = 0
        var i = indexAtual
        while (i < episodeSeasons.size && episodeSeasons[i] == seasonAtual) {
            count++
            i++
        }
        return count
    }

    // ✅ NOVO: número da próxima temporada (se existir) depois da atual.
    private fun proximaTemporadaNumero(): Int? {
        if (episodeSeasons.isEmpty()) return null
        val indexAtual = episodeList.indexOf(streamId)
        if (indexAtual == -1) return null
        val seasonAtual = episodeSeasons.getOrNull(indexAtual) ?: return null
        var i = indexAtual + 1
        while (i < episodeSeasons.size) {
            if (episodeSeasons[i] != seasonAtual) return episodeSeasons[i]
            i++
        }
        return null
    }

    // ✅ NOVO: decide se mostra (e o que mostra) o aviso de fim de
    // temporada, com base em quantos episódios restam. Chamado só uma vez
    // quando o countdown do próximo episódio começa a aparecer.
    private fun atualizarAvisoTemporada() {
        val restantes = episodiosRestantesNaTemporada()
        val proxTemporada = proximaTemporadaNumero()

        when {
            restantes == 1 && proxTemporada != null -> {
                tvSeasonEndWarning.text = "Último episódio da temporada — a seguir: Temporada $proxTemporada"
                tvSeasonEndWarning.visibility = View.VISIBLE
            }
            restantes in 2..3 && proxTemporada != null -> {
                tvSeasonEndWarning.text = "Faltam $restantes episódios para a próxima temporada"
                tvSeasonEndWarning.visibility = View.VISIBLE
            }
            else -> {
                tvSeasonEndWarning.visibility = View.GONE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    // ✅ NOVO: garante que só existe UM ExoPlayer tocando áudio no app
    // inteiro por vez. Sem isso, se o usuário minimiza um vídeo em PiP
    // (que continua tocando de propósito) e abre outro conteúdo por trás
    // pela Home, a instância antiga (ainda viva dentro do PiP) e a nova
    // ficariam tocando áudio ao mesmo tempo — o mesmo bug de "dois áudios
    // juntos" de antes, só que agora pelo caminho do PiP em vez do caminho
    // antigo de troca de tela. Chamado sempre ANTES de criar um novo
    // ExoPlayer, tanto no fluxo online quanto no offline.
    private fun liberarPlayerGlobalDeOutraInstancia() {
        val outroPlayer = activePlayer
        if (outroPlayer != null && outroPlayer !== player) {
            try {
                outroPlayer.stop()
                outroPlayer.release()
            } catch (e: Exception) {
                Log.e("PLAYER_GLOBAL", "Erro ao liberar player de outra instância: ${e.message}")
            }
        }
        activePlayer = null
    }

    // ✅ NOVO: identifica se o streamType atual representa um FILME, seja
    // ele online ("movie") ou baixado ("vod_offline"). Antes, o código de
    // salvar/limpar a posição assistida (onPause/onStop/onDestroy) só
    // checava streamType == "movie" — como o MovieDownloadActivity abre o
    // player offline com streamType = "vod_offline", a posição nunca era
    // salva e a barra "Assistido" na tela de Download nunca aparecia.
    private fun isMovieType(): Boolean = streamType == "movie" || streamType == "vod_offline"

    // ✅ NOVO: mesma ideia, mas pra EPISÓDIO DE SÉRIE. Importante usar um
    // tipo diferente de "vod_offline" (aqui, "series_offline") pra não
    // misturar com filme offline — senão o episódio salvaria a posição na
    // chave "*_movie_resume_*" em vez de "*_series_resume_*", e a barra
    // "Assistido" em SeriesEpisodesActivity nunca apareceria.
    private fun isSeriesType(): Boolean = streamType == "series" || streamType == "series_offline"

    // ✅ NOVO: bloco de reprodução offline reescrito para usar o
    // CacheDataSource do Media3 (lendo do mesmo SimpleCache usado pelo
    // DownloadHelper pra baixar). "contentId" é a chave que localiza os
    // bytes já baixados; "urlOriginal" serve de referência pro MediaItem
    // e de fallback caso falte algo no cache (FLAG_IGNORE_CACHE_ON_ERROR).
    @OptIn(UnstableApi::class)
    private fun iniciarPlayer() {
        // ✅ NOVO: cada nova tentativa de reprodução reseta o gatilho da
        // pré-geração de miniaturas — assim, se a primeira tentativa de
        // servidor falhar e outra assumir, a pré-geração roda de novo pra
        // URL certa (e trickplayCache.iniciarNovaGeracao() descarta as
        // miniaturas da tentativa anterior).
        pregeracaoDeThumbnailsDisparada = false

        // ✅ CORRIGIDO: cobre tanto filme baixado ("vod_offline") quanto
        // episódio de série baixado ("series_offline").
        if (streamType == "vod_offline" || streamType == "series_offline" || !offlineUri.isNullOrBlank()) {
            val contentId = offlineUri
            val urlOriginal = offlineUrl

            if (contentId.isNullOrBlank() || urlOriginal.isNullOrBlank()) {
                Toast.makeText(this, "Arquivo offline nao encontrado.", Toast.LENGTH_LONG).show()
                loading.visibility = View.GONE
                return
            }

            liberarPlayerGlobalDeOutraInstancia()
            player?.release()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val cache = VltvDownloadTracker.getDownloadCache(this)
            val upstreamFactory = VltvDownloadTracker.getHttpDataSourceFactory()
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory { contentId } // sempre usa o mesmo content ID gravado no download
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .build()
            activePlayer = player

            playerView.player = player

            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(urlOriginal))
                player?.setMediaItem(mediaItem)
                player?.prepare()

                // ✅ NOVO: retoma o filme baixado de onde o usuário parou,
                // igual já acontecia no fluxo online. Sem isso, todo filme
                // offline sempre começava do zero mesmo tendo posição salva.
                if (startPositionMs > 0L) {
                    player?.seekTo(startPositionMs)
                }

                player?.playWhenReady = true

                player?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY     -> {
                                loading.visibility = View.GONE
                                // ✅ NOVO: assim que o player fica pronto,
                                // dispara a pré-geração das miniaturas em
                                // segundo plano (uma vez só).
                                if (!pregeracaoDeThumbnailsDisparada) {
                                    pregeracaoDeThumbnailsDisparada = true
                                    dispararPregeracaoDeThumbnails()
                                }
                            }
                            Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                            Player.STATE_ENDED     -> {
                                // ✅ NOVO: ao terminar de assistir offline,
                                // limpa a posição salva — mesmo
                                // comportamento do conteúdo online, mas
                                // usando a chave certa conforme o tipo.
                                if (streamType == "vod_offline") {
                                    clearMovieResume(streamId)
                                } else if (streamType == "series_offline") {
                                    clearSeriesResume(streamId)
                                    // ✅ NOVO: se tiver próximo episódio já
                                    // baixado, encadeia igual acontece no
                                    // fluxo online.
                                    if (nextStreamId != 0 && !nextEpisodeLaunched) abrirProximoEpisodio()
                                }
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, "Erro ao reproduzir arquivo: ${error.message}", Toast.LENGTH_LONG).show()
                        Log.e("PLAYER_OFFLINE", "Erro: ${error.message}", error)
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Erro critico ao carregar arquivo.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (activeServerList.isEmpty()) {
            Toast.makeText(this, "Erro: Sem servidor.", Toast.LENGTH_LONG).show()
            loading.visibility = View.GONE
            return
        }

        if (extIndex >= extensoesTentativa.size) {
            serverIndex++
            extIndex = 0
            if (serverIndex >= activeServerList.size) {
                serverIndex = 0
                Toast.makeText(this, "Reconectando...", Toast.LENGTH_SHORT).show()
            }
        }

        val currentServer = activeServerList[serverIndex]
        val currentExt    = extensoesTentativa[extIndex]

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user  = prefs.getString("username", "") ?: ""
        val pass  = prefs.getString("password", "") ?: ""

        val url = montarUrlStream(
            server     = currentServer,
            streamType = streamType,
            user       = user,
            pass       = pass,
            id         = streamId,
            ext        = currentExt
        )

        liberarPlayerGlobalDeOutraInstancia()
        player?.release()
        // ✅ NOVO: troca de servidor/URL significa que uma sessão antiga
        // de thumbnail (apontando pra URL anterior) ficaria inválida —
        // encerra aqui pra próxima extração já recomeçar com a URL certa.
        encerrarSessaoDeThumbnailOnline()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(NetworkConfig.CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(NetworkConfig.READ_TIMEOUT_MS)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val isLive         = streamType == "live"
        // minBufferMs precisa ser >= playRebufferMs, senão o ExoPlayer
        // lança IllegalArgumentException. Na live o rebuffer é 2500, então
        // o mínimo sobe pra 3000.
        val minBufferMs    = if (isLive) 3000 else 2000
        // ✅ OTIMIZADO: live com até 15s de buffer (era 5s) — o início
        // continua rápido (playBufferMs = 1s), mas a reprodução aguenta
        // melhor oscilações de rede sem travar.
        val maxBufferMs    = if (isLive) 15000 else 60000
        val playBufferMs   = 1000
        val playRebufferMs = if (isLive) 2500 else 2000

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, playBufferMs, playRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
        activePlayer = player

        playerView.player = player

        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            player?.setMediaItem(mediaItem)
            player?.prepare()

            if (startPositionMs > 0L && (streamType == "movie" || streamType == "series")) {
                player?.seekTo(startPositionMs)
            }

            player?.playWhenReady = true
        } catch (e: Exception) {
            tentarProximo()
            return
        }

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY     -> {
                        loading.visibility = View.GONE
                        // ✅ NOVO: mesma pré-geração de miniaturas, agora
                        // pro fluxo online.
                        if (!pregeracaoDeThumbnailsDisparada) {
                            pregeracaoDeThumbnailsDisparada = true
                            dispararPregeracaoDeThumbnails()
                        }
                    }
                    Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                    Player.STATE_ENDED     -> {
                        if (streamType == "movie") {
                            clearMovieResume(streamId)
                        } else if (streamType == "series") {
                            clearSeriesResume(streamId)
                            if (nextStreamId != 0 && !nextEpisodeLaunched) abrirProximoEpisodio()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.VISIBLE
                handler.postDelayed({ tentarProximo() }, 1000L)
            }
        })
    }

    private fun tentarProximo() {
        extIndex++
        iniciarPlayer()
    }

    // ✅ REESCRITO: nada mais de regex tentando "adivinhar" o número do
    // próximo episódio a partir do título atual (isso quebrava sempre que
    // cruzava de uma temporada pra outra). Agora usa direto os arrays
    // paralelos (episodeList/episodeSeasons/episodeTitles/episodeExts),
    // que já têm a informação certa de qual é o próximo episódio — mesmo
    // que seja o primeiro episódio da temporada seguinte. Os arrays
    // completos são propagados pra próxima instância do PlayerActivity,
    // então a "corrente" de próximos episódios continua funcionando
    // indefinidamente, atravessando quantas temporadas precisar.
    private fun abrirProximoEpisodio() {
        if (nextStreamId == 0 || nextEpisodeLaunched) return
        nextEpisodeLaunched = true

        handler.removeCallbacks(nextChecker)
        nextEpisodeContainer.visibility = View.GONE
        tvSeasonEndWarning.visibility = View.GONE
        botaoVisivelDesdeMs = 0L

        if (activePlayer === player) activePlayer = null
        player?.stop()
        player?.release()
        player = null

        // ✅ NOVO: se está assistindo um episódio BAIXADO, o próximo
        // também precisa ser aberto em modo offline (lendo do download
        // salvo no banco) — não faz sentido tentar streaming online aqui.
        if (streamType == "series_offline") {
            abrirProximoEpisodioOffline()
            return
        }

        val indexAtual = episodeList.indexOf(streamId)
        val indexProximo = if (indexAtual != -1) indexAtual + 1 else -1
        val extProximo = episodeExts.getOrNull(indexProximo) ?: "mp4"
        val novoTitulo = nextChannelName ?: tvChannelName.text.toString()

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id",    nextStreamId)
        intent.putExtra("stream_ext",   extProximo)
        intent.putExtra("stream_type",  "series")
        intent.putExtra("channel_name", novoTitulo)
        intent.putExtra("PROFILE_NAME", currentProfile)

        if (episodeList.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", episodeList)
            intent.putIntegerArrayListExtra("episode_seasons", episodeSeasons)
            intent.putStringArrayListExtra("episode_titles", episodeTitles)
            intent.putStringArrayListExtra("episode_exts", episodeExts)
        }
        startActivity(intent)
        finish()
    }

    // ✅ NOVO: versão offline de abrirProximoEpisodio(). Busca no Room o
    // DownloadEntity do próximo episódio (mesma tabela de downloads usada
    // em SeriesEpisodesActivity) pra pegar o file_path/download_url reais
    // — sem isso não tem como montar o CacheDataSource do episódio
    // seguinte. Se o próximo episódio ainda não foi baixado (ex.: usuário
    // baixou só alguns episódios da temporada), avisa e não força nada.
    private fun abrirProximoEpisodioOffline() {
        lifecycleScope.launch {
            val dl = withContext(Dispatchers.IO) {
                database.streamDao().getDownloadByStreamId(nextStreamId, "series")
            }
            if (dl == null || dl.file_path.isBlank() || dl.download_url.isBlank()) {
                Toast.makeText(this@PlayerActivity, "O próximo episódio ainda não foi baixado.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val novoTitulo = "${dl.name} - ${dl.episode_name}"

            val intent = Intent(this@PlayerActivity, PlayerActivity::class.java)
            intent.putExtra("stream_id",    nextStreamId)
            intent.putExtra("stream_type",  "series_offline")
            intent.putExtra("offline_uri",  dl.file_path)
            intent.putExtra("offline_url",  dl.download_url)
            intent.putExtra("channel_name", novoTitulo)
            intent.putExtra("icon",         dl.image_url)
            intent.putExtra("PROFILE_NAME", currentProfile)

            if (episodeList.isNotEmpty()) {
                intent.putIntegerArrayListExtra("episode_list", episodeList)
                intent.putIntegerArrayListExtra("episode_seasons", episodeSeasons)
                intent.putStringArrayListExtra("episode_titles", episodeTitles)
                intent.putStringArrayListExtra("episode_exts", episodeExts)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun getMovieKey(id: Int) = "${currentProfile}_movie_resume_$id"

    // CORREÇÃO (Continuar Assistindo sumindo / só aparecendo depois de
    // fechar e abrir o app): antes a gravação no Room usava
    // `lifecycleScope.launch(Dispatchers.IO) { ... }`. O problema é que
    // `lifecycleScope` é CANCELADO automaticamente assim que `onDestroy()`
    // roda. Quando o usuário aperta voltar logo depois de pausar (o
    // `onPause → onStop → onDestroy` acontece em poucos milissegundos),
    // a gravação no banco podia ser cancelada NO MEIO do caminho, antes de
    // terminar — e o catch vazio escondia isso, sem nem logar o erro.
    // Resultado: o registro nunca chegava no `watch_history`, e só reabrindo
    // o app é que teoricamente teria uma nova chance de gravar corretamente.
    //
    // A correção usa um escopo de corrotina PRÓPRIO (`historicoScope`),
    // independente do ciclo de vida da Activity — ele só é cancelado quando
    // o app inteiro é encerrado (processo morto), nunca só porque essa tela
    // fechou. Isso garante que a gravação sempre termina, não importa quão
    // rápido o usuário saia do player.
    private fun saveMovieResume(id: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val percent = positionMs.toDouble() / durationMs.toDouble()
        if (positionMs < 30_000L || percent > 0.95) { clearMovieResume(id); return }
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("${getMovieKey(id)}_pos", positionMs)
            .putLong("${getMovieKey(id)}_dur", durationMs)
            .apply()
        // ✅ Registro no Firebase/Room de histórico só faz sentido pra
        // filmes ONLINE (streamType == "movie"). Para filme baixado
        // (vod_offline) mantemos só o SharedPreferences local acima, que é
        // o que a tela de Download lê — evita gravar "assistindo offline"
        // como se fosse consumo de streaming normal.
        if (streamType == "movie") {
            salvarNoFirebase(id, positionMs, durationMs)
            salvarNoHistoricoLocal(id.toString())
            val nomeAtual = tvChannelName.text.toString()
            val iconeAtual = intent.getStringExtra("icon") ?: ""
            val profileAtual = currentProfile
            historicoScope.launch {
                try {
                    AppDatabase.getDatabase(applicationContext).streamDao().saveWatchHistory(WatchHistoryEntity(
                        stream_id     = id,
                        profile_name  = profileAtual,
                        name          = nomeAtual,
                        icon          = iconeAtual,
                        last_position = positionMs,
                        duration      = durationMs,
                        is_series     = false,
                        timestamp     = System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    Log.e("VLTV_WatchHistory", "Erro ao salvar histórico do filme $id: ${e.message}", e)
                }
            }
        }
    }

    private fun clearMovieResume(id: Int) {
        getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
            .remove("${getMovieKey(id)}_pos")
            .remove("${getMovieKey(id)}_dur")
            .apply()
    }

    private fun getSeriesKey(episodeStreamId: Int) = "${currentProfile}_series_resume_$episodeStreamId"

    // CORREÇÃO: mesmo motivo do saveMovieResume acima — escopo próprio
    // (historicoScope) em vez de lifecycleScope, pra gravação nunca ser
    // cancelada por causa da Activity fechar rápido demais.
    private fun saveSeriesResume(id: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val percent = positionMs.toDouble() / durationMs.toDouble()
        if (positionMs < 30_000L || percent > 0.95) { clearSeriesResume(id); return }
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("${getSeriesKey(id)}_pos", positionMs)
            .putLong("${getSeriesKey(id)}_dur", durationMs)
            .apply()
        // ✅ Mesmo critério do filme: histórico/Firebase só pra streaming
        // ONLINE (streamType == "series"). Episódio offline (series_offline)
        // grava só a chave local acima, que é o que a barra "Assistido" em
        // SeriesEpisodesActivity lê.
        if (streamType == "series") {
            salvarNoFirebase(id, positionMs, durationMs)
            salvarNoHistoricoLocal(id.toString())
            val nomeAtual = tvChannelName.text.toString()
            val iconeAtual = intent.getStringExtra("icon") ?: ""
            val profileAtual = currentProfile
            historicoScope.launch {
                try {
                    AppDatabase.getDatabase(applicationContext).streamDao().saveWatchHistory(WatchHistoryEntity(
                        stream_id     = id,
                        profile_name  = profileAtual,
                        name          = nomeAtual,
                        icon          = iconeAtual,
                        last_position = positionMs,
                        duration      = durationMs,
                        is_series     = true,
                        timestamp     = System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    Log.e("VLTV_WatchHistory", "Erro ao salvar histórico da série $id: ${e.message}", e)
                }
            }
        }
    }

    private fun clearSeriesResume(id: Int) {
        getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
            .remove("${getSeriesKey(id)}_pos")
            .remove("${getSeriesKey(id)}_dur")
            .apply()
    }

    private fun salvarNoHistoricoLocal(id: String) {
        val prefs  = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val keyIds = "${currentProfile}_local_history_ids"
        val ids    = prefs.getStringSet(keyIds, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        ids.add(id)
        prefs.edit().apply {
            putStringSet(keyIds, ids)
            putString("${currentProfile}_history_name_$id", tvChannelName.text.toString())
            putString("${currentProfile}_history_icon_$id", intent.getStringExtra("icon") ?: "")
            apply()
        }
    }

    private fun salvarNoFirebase(id: Int, positionMs: Long, durationMs: Long) {
        val prefs     = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val userEmail = prefs.getString("username", "") ?: ""
        if (userEmail.isBlank()) return
        val db   = FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "id"         to id.toString(),
            "name"       to tvChannelName.text.toString(),
            "streamIcon" to (intent.getStringExtra("icon") ?: ""),
            "positionMs" to positionMs,
            "durationMs" to durationMs,
            "timestamp"  to com.google.firebase.Timestamp.now()
        )
        db.collection("users")
            .document(userEmail)
            .collection("profiles")
            .document(currentProfile)
            .collection("history")
            .document(id.toString())
            .set(data, SetOptions.merge())
            .addOnFailureListener { e -> Log.e("FIREBASE_PLAYER", "Erro: ${e.message}") }
    }

    private fun decodeBase64(text: String?): String {
        return try {
            if (text.isNullOrEmpty()) ""
            else String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { text ?: "" }
    }

    private fun carregarEpg() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user  = prefs.getString("username", "") ?: ""
        val pass  = prefs.getString("password", "") ?: ""
        if (user.isBlank() || pass.isBlank()) {
            tvNowPlaying.text = "Sem informacao de programacao"
            return
        }
        XtreamApi.service.getShortEpg(
            user = user, pass = pass,
            streamId = streamId.toString(), limit = 2
        ).enqueue(object : Callback<EpgWrapper> {
            override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                if (!response.isSuccessful || response.body()?.epg_listings.isNullOrEmpty()) {
                    tvNowPlaying.text = "Sem informacao de programacao"
                    return
                }
                val epg    = response.body()!!.epg_listings!!.firstOrNull() ?: return
                val titulo = decodeBase64(epg.title)
                val inicio = epg.start ?: ""
                val fim    = epg.stop ?: epg.end.orEmpty()
                tvNowPlaying.text = if (inicio.isNotBlank()) "$titulo ($inicio - $fim)" else titulo
            }
            override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {
                tvNowPlaying.text = "Falha ao carregar programacao"
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action  = event.action
        val p       = player ?: return super.dispatchKeyEvent(event)

        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (nextEpisodeContainer.visibility == View.VISIBLE) {
                        btnPlayNextEpisode.performClick()
                        return true
                    }
                    if (playerView.isControllerFullyVisible) {
                        if (p.isPlaying) p.pause() else p.play()
                    } else {
                        playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (nextEpisodeContainer.visibility == View.VISIBLE) {
                        btnPlayNextEpisode.requestFocus()
                        return true
                    }
                    if (!playerView.isControllerFullyVisible) playerView.showController()
                    val seekBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    seekBar?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (streamType != "live") {
                        p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration))
                        playerView.showController()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (streamType != "live") {
                        p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                        playerView.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { aprenderPontoCreditos(toqueNoBotao = false); finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            val p = player ?: return
            // ✅ CORRIGIDO: isMovieType() cobre "movie" (online) E
            // "vod_offline" (baixado) — antes só "movie" era salvo, então
            // pausar/sair de um filme baixado nunca gravava a posição.
            if (isMovieType()) {
                saveMovieResume(streamId, p.currentPosition, p.duration)
            } else if (isSeriesType()) {
                saveSeriesResume(streamId, p.currentPosition, p.duration)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(nextChecker)
        val p = player
        if (p != null) {
            // ✅ CORRIGIDO: mesmo ajuste do onPause — cobre filme offline.
            if (isMovieType()) {
                saveMovieResume(streamId, p.currentPosition, p.duration)
            } else if (isSeriesType()) {
                saveSeriesResume(streamId, p.currentPosition, p.duration)
            }
        }
        // ✅ NOVO: libera o retriever do preview de miniatura junto com o
        // resto — evita segurar uma conexão HTTP aberta desnecessariamente
        // depois que a tela fecha. Cobre também o player-fantasma offline.
        encerrarSessaoDeThumbnail()
        // ✅ NOVO: aqui sim (tela fechando de vez) libera as miniaturas
        // pré-geradas guardadas em memória.
        trickplayCache.limpar()
        // ✅ NOVO: limpa a referência global ANTES de nulificar o player
        // local, e só se ainda for a mesma instância — evita que a
        // instância antiga, ao ser destruída, apague por engano a
        // referência de uma instância mais nova que já assumiu o áudio.
        if (activePlayer === player) activePlayer = null
        player?.release()
        player = null
    }

    private fun montarUrlStream(server: String, streamType: String, user: String, pass: String, id: Int, ext: String): String {
        val base = if (server.endsWith("/")) server.dropLast(1) else server
        return if (ext.isBlank()) "$base/$streamType/$user/$pass/$id"
               else "$base/$streamType/$user/$pass/$id.$ext"
    }

    // ✅ CORRIGIDO (áudio não parava ao fechar o PiP pelo X):
    // Segundo a documentação oficial do Android, enquanto a mini-janela do
    // PiP está genuinamente ativa (o usuário só minimizou, o vídeo continua
    // tocando por cima de outro app), o sistema NÃO chama onStop() — ele
    // chama só onPause(). O onStop() só é disparado quando a Activity REALMENTE
    // some da tela de vez: outro app cobrindo tudo, ou o usuário fechando a
    // janela do PiP pelo botão X.
    //
    // O bug: a versão antiga só liberava o player aqui "if (!isInPictureInPictureMode)".
    // Só que no instante exato em que o onStop() roda por causa do X, a flag
    // isInPictureInPictureMode AINDA está true (o Android não "sai" do PiP
    // antes de descartar a janela, ele só derruba) — então a checagem pulava
    // o release() e o player ficava vivo tocando áudio escondido. Se depois
    // disso você abria outro filme/série, os dois áudios tocavam juntos.
    //
    // A correção: como onStop() só roda quando a tela realmente precisa
    // sumir de vez (PiP fechado incluso), não faz mais sentido checar
    // isInPictureInPictureMode aqui — o player é sempre parado e liberado.
    // Também aproveitamos pra salvar a posição de "continuar assistindo"
    // aqui, porque fechar pelo X pode não disparar onDestroy().
    override fun onStop() {
        super.onStop()

        val p = player
        if (p != null) {
            // ✅ CORRIGIDO: mesmo ajuste do onPause/onDestroy — cobre
            // filme offline (vod_offline), que é o caso mais comum de
            // "usuário fecha pelo botão voltar" logo depois de assistir
            // um pouco do download.
            if (isMovieType()) {
                saveMovieResume(streamId, p.currentPosition, p.duration)
            } else if (isSeriesType()) {
                saveSeriesResume(streamId, p.currentPosition, p.duration)
            }
        }

        // ✅ NOVO: também encerra a sessão de thumbnail aqui — mesmo
        // raciocínio do player em si: se a tela sumiu de vez, não faz
        // sentido segurar o retriever/player-fantasma aberto.
        encerrarSessaoDeThumbnail()
        // ✅ NOVO: e libera as miniaturas pré-geradas — a tela sumiu de
        // vez, não faz sentido manter isso em memória.
        trickplayCache.limpar()

        if (activePlayer === player) activePlayer = null
        player?.stop()
        player?.release()
        player = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        aprenderPontoCreditos(toqueNoBotao = false)
        if (activePlayer === player) activePlayer = null
        player?.stop()
        player?.release()
        player = null
        super.onBackPressed()
        finish()
    }

    companion object {
        // CORREÇÃO (Continuar Assistindo): escopo de corrotina próprio pra
        // gravação do histórico de reprodução no Room. Usa SupervisorJob
        // pra um erro em uma gravação não cancelar as outras, e NÃO é
        // vinculado a nenhuma Activity — sobrevive ao fechamento rápido da
        // tela do player, diferente do lifecycleScope usado antes.
        private val historicoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // ✅ NOVO: referência estática ao ExoPlayer atualmente tocando, em
        // QUALQUER instância da PlayerActivity (inclusive uma que esteja
        // minimizada em Picture-in-Picture). Serve como trava única contra
        // dois áudios tocando ao mesmo tempo — antes de qualquer instância
        // criar um player novo, ela verifica e libera essa referência caso
        // pertença a uma instância diferente da sua.
        @Volatile
        private var activePlayer: ExoPlayer? = null

        // ✅ NOVO: botão "Próximo episódio" — segundos restantes em que
        // ele aparece quando a VPS ainda não sabe onde os créditos dessa
        // série começam.
        private const val CREDITOS_PADRAO_SEG = 50

        // Quantos segundos ANTES do ponto aprendido o botão aparece.
        private const val ANTECEDENCIA_SEG = 10

        // Faixa aceita pro ponto dos créditos (mesma validação do backend:
        // 10s a 10min — há séries com créditos bem longos).
        private const val CREDITOS_MIN_SEG = 10
        private const val CREDITOS_MAX_SEG = 600
    }
}
