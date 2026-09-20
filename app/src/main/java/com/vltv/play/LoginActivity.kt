package com.vltv.play

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.databinding.ActivityLoginBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    // ✅ ÚNICA lista de DNS do app — os 7 servidores realmente em uso.
    // Antes existia uma segunda lista hardcoded só pro fallback
    // (dentro de iniciarLoginTurbo), com vários DNS antigos que você já
    // não usa mais (infiprotec.site, blackdns.shop, tlfp.fun,
    // telefunplay.xyz, tvblack.shop) e sem alguns que você usa
    // (cmdtv.casa, cmdtv.pro). Isso fazia a etapa rápida e a etapa de
    // fallback testarem listas diferentes entre si. Agora o fallback usa
    // esta mesma lista (SERVERS) — só um lugar pra manter atualizado daqui
    // pra frente.
    private val SERVERS = listOf(
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
        "http://cybertronplay.space"
    )

    private val clientRapido = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val clientLento = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsJob: Runnable? = null
    private var dotsCount = 0

    // ✅ NOVO: estado do "olho" de mostrar/ocultar senha. Começa sempre
    // false (senha oculta) a cada abertura da tela de login.
    private var senhaVisivel = false

    // ============================================================================
    // ✅ NOVO: TESTE AUTOMÁTICO — 1ª abertura do app, sem tela de login
    // ============================================================================
    // Cada build do app usa UMA lista fixa. Pro app principal (com.vltv.play):
    // "lista1". Pro segundo app (VLTV-PLAY-NOVA-HOME): trocar esta única
    // linha para "lista2" — o resto da lógica é idêntico nos dois apps.
    private val LISTA_TESTE = "lista1"

    // Mesmo domínio do site (vltvplay.tech) — a chave do provedor IPTV fica
    // só no servidor; o app nunca fala direto com o painel.
    private val AUTO_TRIAL_URL = "https://vltvplay.tech/api/app-auto-trial"

    private val clientAutoTrial = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ REMOVIDO: installSplashScreen() saiu daqui. A LoginActivity não
        // é mais a porta de entrada do app — quem cobre esse papel agora é
        // a SplashActivity (splash própria e animada, com o wordmark "VLTV
        // PLAY" surgindo letra por letra). O tema desta Activity voltou a
        // ser o normal (Theme.VLTVPlay), configurado no AndroidManifest.
        super.onCreate(savedInstanceState)

        // ✅ NOVO: precisa rodar ANTES de ler "vltv_prefs" logo abaixo.
        // Ver explicação completa na função.
        limparLoginRestauradoSeInstalacaoNova()

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        aplicarModoImersivo()

        requestedOrientation = if (isTelevisionDevice()) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val prefs     = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns  = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            binding.root.visibility = View.INVISIBLE
            verificarEIniciarRapido(savedDns, savedUser, savedPass)
        } else {
            // ✅ ANTES: caía direto em setupUI() (tela de login manual).
            // AGORA: tenta primeiro o teste automático em segundo plano;
            // só mostra a tela de login manual se o teste automático falhar
            // por qualquer motivo (sem internet, servidor fora do ar,
            // credenciais do teste inválidas etc.) — ver iniciarTesteAutomatico().
            binding.root.visibility = View.INVISIBLE
            iniciarTesteAutomatico()
        }
    }

    // ✅ NOVO: resolve o caso "desinstalei o app, reinstalei, e o login
    // antigo voltou sozinho". Isso acontece por causa do Auto Backup do
    // Android: ele tira snapshots periódicos de "vltv_prefs" (onde ficam
    // username/password/dns/last_profile_name) e os restaura sozinho ao
    // reinstalar o app na mesma conta Google — mesmo que o usuário tenha
    // desinstalado JUSTAMENTE pra trocar de login.
    //
    // A solução usa um SEGUNDO arquivo de preferências, "vltv_device_marker",
    // que é EXCLUÍDO do backup (ver res/xml/backup_rules.xml e
    // data_extraction_rules.xml — só esse arquivo é excluído, o resto do
    // backup continua normal). Esse marcador só existe fisicamente no
    // aparelho onde o app rodou pelo menos uma vez; ele nunca "volta" pelo
    // backup.
    //
    // Lógica: se o marcador NÃO existe, mas já existe login salvo em
    // "vltv_prefs", esse login só pode ter chegado ali via restauração de
    // backup (não tem como ser de uma sessão real, já que esse é o
    // primeiro onCreate desta instalação) — então apaga só as chaves de
    // login/perfil e deixa o app cair normalmente na tela de login. Depois
    // disso o marcador é gravado, e essa limpeza não roda de novo até a
    // próxima desinstalação/reinstalação.
    private fun limparLoginRestauradoSeInstalacaoNova() {
        val marcador = getSharedPreferences("vltv_device_marker", Context.MODE_PRIVATE)
        val jaRodouNesteAparelho = marcador.getBoolean("instalado", false)

        if (!jaRodouNesteAparelho) {
            getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                .remove("username")
                .remove("password")
                .remove("dns")
                .remove("last_profile_name")
                .remove("last_profile_icon")
                .apply()

            marcador.edit().putBoolean("instalado", true).apply()
        }
    }

    // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    private fun aplicarModoImersivo() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupUI() {
        binding.root.visibility = View.VISIBLE
        binding.btnLogin.isFocusableInTouchMode = false
        binding.btnLogin.isFocusable = false

        binding.etUsername.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etPassword.requestFocus(); true
            } else false
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                binding.btnLogin.callOnClick(); true
            } else false
        }

        var ultimoClique = 0L
        binding.btnLogin.setOnClickListener {
            val agora = System.currentTimeMillis()
            if (agora - ultimoClique < 800L) return@setOnClickListener
            ultimoClique = agora

            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha usuário e senha!", Toast.LENGTH_SHORT).show()
            } else {
                iniciarLoginTurbo(user, pass)
            }
        }

        // ✅ NOVO: toque no ícone de olho (drawableEnd do campo Senha)
        // alterna entre mostrar e ocultar a senha digitada. Ver
        // configurarToggleSenha() pra detalhes de como o toque na área
        // do ícone é identificado.
        configurarToggleSenha()

        binding.etUsername.requestFocus()
    }

    // ✅ NOVO: "olho" de mostrar/ocultar senha.
    //
    // EditText não tem um listener de "cliquei no drawableEnd" pronto —
    // o truque padrão é usar setOnTouchListener e, no ACTION_UP,
    // verificar se o toque aconteceu dentro da área ocupada pelo ícone
    // (da borda direita do campo pra dentro, na largura do drawable +
    // padding). Se sim, alterna a visibilidade e consome o toque (não
    // deixa abrir o teclado nem mover o cursor pra ali); se não, deixa o
    // toque seguir normal (foca o campo, abre o teclado etc.).
    private fun configurarToggleSenha() {
        binding.etPassword.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = binding.etPassword.compoundDrawables[2] // 0=start,1=top,2=end,3=bottom
                if (drawableEnd != null) {
                    val areaDoIcone = drawableEnd.bounds.width() + binding.etPassword.paddingEnd
                    val tocouNoIcone = event.rawX >= (v.right - areaDoIcone)
                    if (tocouNoIcone) {
                        alternarVisibilidadeSenha()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun alternarVisibilidadeSenha() {
        senhaVisivel = !senhaVisivel

        val cursorPos = binding.etPassword.selectionStart
        binding.etPassword.inputType = if (senhaVisivel) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Trocar o inputType reseta a fonte (monospace) e pode mover o
        // cursor pro início — restaura a posição de digitação de antes.
        binding.etPassword.setSelection(cursorPos.coerceIn(0, binding.etPassword.text?.length ?: 0))

        val icone = if (senhaVisivel) R.drawable.ic_eye_off else R.drawable.ic_eye
        binding.etPassword.setCompoundDrawablesWithIntrinsicBounds(
            R.drawable.ic_lock, 0, icone, 0
        )
    }

    private fun iniciarAnimacaoPontinhos() {
        dotsJob = object : Runnable {
            override fun run() {
                dotsCount = (dotsCount + 1) % 4
                try { binding.tvLoadingDots?.text = ".".repeat(dotsCount) } catch (e: Exception) {}
                dotsHandler.postDelayed(this, 400)
            }
        }
        dotsHandler.post(dotsJob!!)
    }

    private fun pararAnimacaoPontinhos() {
        dotsJob?.let { dotsHandler.removeCallbacks(it) }
        dotsJob = null
    }

    private fun mostrarLoading() {
        binding.btnLogin.isEnabled = false
        binding.etUsername.isEnabled = false
        binding.etPassword.isEnabled = false
        try { binding.layoutLoading?.visibility = View.VISIBLE } catch (e: Exception) {
            binding.progressBar.visibility = View.VISIBLE
        }
        iniciarAnimacaoPontinhos()
    }

    private fun esconderLoading() {
        pararAnimacaoPontinhos()
        try { binding.layoutLoading?.visibility = View.GONE } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
        }
        binding.btnLogin.isEnabled = true
        binding.etUsername.isEnabled = true
        binding.etPassword.isEnabled = true
    }

    // ============================================================================
    // ✅ NOVO: fluxo de teste automático (1ª abertura, sem login salvo)
    // ============================================================================
    // 1) Pega o ANDROID_ID do aparelho.
    // 2) Chama o backend (mesma lógica de geração de teste do site) pedindo
    //    um teste pra LISTA_TESTE, identificado por esse ANDROID_ID — o
    //    backend garante que o MESMO aparelho nunca recebe dois testes
    //    diferentes (reinstalar não gera teste novo).
    // 3) Com usuário/senha em mãos, testa os mesmos SERVERS já usados no
    //    login manual pra descobrir o DNS que responde (a Lista 1 nem
    //    devolve DNS — o app sempre descobriu por conta própria).
    // 4) Se tudo der certo, salva como se fosse um login manual normal e
    //    segue pra tela de Perfis.
    // 5) Qualquer falha em qualquer etapa (sem internet, backend fora do
    //    ar, credenciais inválidas) cai silenciosamente na tela de login
    //    manual (setupUI()) — nunca trava o app.
    private fun iniciarTesteAutomatico() {
        lifecycleScope.launch(Dispatchers.IO) {
            val androidId = obterAndroidIdParaTeste()

            val credenciais = solicitarTesteAutomatico(androidId)
            if (credenciais == null) {
                withContext(Dispatchers.Main) { setupUI() }
                return@launch
            }
            val (user, pass) = credenciais

            var dnsVencedor: String? = null
            for (servidor in SERVERS) {
                dnsVencedor = testarServidor(servidor, user, pass, clientRapido)
                if (dnsVencedor != null) break
            }
            if (dnsVencedor == null) {
                for (servidor in SERVERS) {
                    dnsVencedor = testarServidor(servidor, user, pass, clientLento)
                    if (dnsVencedor != null) break
                }
            }

            if (dnsVencedor == null) {
                // Teste recebido do servidor não bateu em nenhum DNS (pode
                // acontecer com um teste antigo/expirado reaproveitado após
                // logout) — não trava o cliente, só mostra o login manual.
                withContext(Dispatchers.Main) { setupUI() }
                return@launch
            }

            val dnsFinal = normalizarBaseUrl(dnsVencedor)
            salvarCredenciais(dnsFinal, user, pass)

            ContentRepository.recarregar(applicationContext)
            launch(Dispatchers.IO) { preCarregarLoteMinimo(dnsFinal, user, pass) }

            withContext(Dispatchers.Main) {
                val intent = Intent(this@LoginActivity, ProfilesActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    // ANDROID_ID: identificador do aparelho, único por app+dispositivo,
    // sobrevive a reinstalação do app (só muda com reset de fábrica) — é
    // exatamente o comportamento necessário pra travar abuso de reinstalar
    // pra ganhar teste novo.
    @SuppressWarnings("HardwareIds")
    private fun obterAndroidIdParaTeste(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: "sem_android_id"
        } catch (e: Exception) {
            "sem_android_id"
        }
    }

    private fun solicitarTesteAutomatico(androidId: String): Pair<String, String>? {
        return try {
            val bodyJson = JSONObject().apply {
                put("listId", LISTA_TESTE)
                put("deviceId", androidId)
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(AUTO_TRIAL_URL)
                .post(body)
                .build()

            clientAutoTrial.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful || raw.isNullOrBlank()) return null

                val json = JSONObject(raw)
                val user = json.optString("username", "")
                val pass = json.optString("password", "")
                if (user.isBlank() || pass.isBlank()) null else Pair(user, pass)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Fluxo para usuário já logado ──────────────────────────────────────────
    private fun verificarEIniciarRapido(dns: String, user: String, pass: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val temConteudo = db.streamDao().getVodCount() > 0

            withContext(Dispatchers.Main) {
                if (temConteudo) {
                    decidirProximaTela()
                    launch(Dispatchers.IO) {
                        preCarregarLoteMinimo(dns, user, pass)
                    }
                } else {
                    launch(Dispatchers.IO) {
                        preCarregarLoteMinimo(dns, user, pass)
                        withContext(Dispatchers.Main) { decidirProximaTela() }
                    }
                }
            }
        }
    }

    // ── Fluxo de login novo ───────────────────────────────────────────────────
    private fun iniciarLoginTurbo(user: String, pass: String) {
        mostrarLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            var dnsVencedor: String? = null

            try {
                val canal = Channel<String>(Channel.UNLIMITED)
                val jobs = SERVERS.map { url ->
                    launch(Dispatchers.IO) {
                        val r = testarServidor(url, user, pass, clientRapido)
                        if (r != null) canal.trySend(r)
                    }
                }
                dnsVencedor = withTimeoutOrNull(18_000L) { canal.receive() }
                jobs.forEach { it.cancel() }
                canal.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ✅ Fallback agora usa a MESMA lista (SERVERS), só que com o
            // client mais tolerante (clientLento: timeout maior e retry
            // ativado) — pra dar uma segunda chance aos mesmos 7 DNS reais
            // antes de desistir, em vez de testar servidores que você não
            // usa mais.
            if (dnsVencedor == null) {
                for (servidor in SERVERS) {
                    val r = testarServidor(servidor, user, pass, clientLento)
                    if (r != null) { dnsVencedor = r; break }
                }
            }

            if (dnsVencedor != null) {
                val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val usuarioAnterior = prefs.getString("username", null)
                if (usuarioAnterior != null && usuarioAnterior != user) {
                    limparBancoPorTrocaDeUsuario()
                }

                val dnsFinal = normalizarBaseUrl(dnsVencedor)
                salvarCredenciais(dnsFinal, user, pass)

                // ── CORREÇÃO: dispara ContentRepository ANTES de navegar ───────
                // O usuário verá a ProfilesActivity enquanto os dados carregam em
                // background. Quando ele clicar no perfil e a HomeActivity abrir,
                // ContentRepository.pronto já será true (ou estará muito próximo).
                ContentRepository.recarregar(applicationContext)

                // Pré-carrega lote mínimo no banco em paralelo (sem bloquear navegação)
                launch(Dispatchers.IO) {
                    preCarregarLoteMinimo(dnsFinal, user, pass)
                }

                withContext(Dispatchers.Main) {
                    pararAnimacaoPontinhos()
                    val intent = Intent(this@LoginActivity, ProfilesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }

            } else {
                withContext(Dispatchers.Main) {
                    esconderLoading()
                    mostrarErro("Servidor não encontrado. Verifique login e senha.")
                }
            }
        }
    }

    private fun testarServidor(baseUrl: String, user: String, pass: String, httpClient: OkHttpClient): String? {
        val urlBase = normalizarBaseUrl(baseUrl)
        val urlSemBarra = urlBase.removeSuffix("/")
        return try {
            val request = Request.Builder()
                .url("$urlSemBarra/player_api.php?username=$user&password=$pass")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val valido = body.contains("user_info") &&
                            body.contains("server_info") &&
                            !body.contains("\"auth\":0") &&
                            !body.contains("\"auth\": 0") &&
                            !body.contains("\"auth\":\"0\"") &&
                            !body.contains("\"status\":\"Disabled\"") &&
                            !body.contains("\"status\":\"Expired\"")
                    if (valido) urlBase else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun preCarregarLoteMinimo(dns: String, user: String, pass: String) {
        val base = normalizarBaseUrl(dns)

        withTimeoutOrNull(20_000L) {
            try {
                val db = AppDatabase.getDatabase(applicationContext)

                coroutineScope {
                    val jVod = async(Dispatchers.IO) {
                        buscarJsonLimitado(
                            url = "${base}player_api.php?username=$user&password=$pass&action=get_vod_streams",
                            maxBytes = 300_000
                        )?.let { json ->
                            try {
                                val arr = JSONArray(json)
                                val batch = mutableListOf<VodEntity>()
                                // ⚠️ Mesma correção do SyncManager.kt: preserva
                                // logo/selos já calculados em vez de zerá-los
                                // a cada login.
                                val existentes = try { db.streamDao().getAllVods().associateBy { it.stream_id } } catch (e: Exception) { emptyMap() }
                                for (i in 0 until minOf(12, arr.length())) {
                                    val o = arr.getJSONObject(i)
                                    val streamId = o.optInt("stream_id")
                                    val ex = existentes[streamId]
                                    batch.add(VodEntity(
                                        stream_id = streamId,
                                        name = o.optString("name"),
                                        title = o.optString("name"),
                                        stream_icon = o.optString("stream_icon"),
                                        container_extension = o.optString("container_extension"),
                                        rating = o.optString("rating"),
                                        category_id = o.optString("category_id"),
                                        added = o.optLong("added"),
                                        logo_url = ex?.logo_url,
                                        tmdb_rank = ex?.tmdb_rank ?: 0,
                                        tmdb_release_date = ex?.tmdb_release_date,
                                        is_top10 = ex?.is_top10 ?: 0,
                                        is_novidade = ex?.is_novidade ?: 0,
                                        tmdb_id = ex?.tmdb_id,
                                        backdrop_path = ex?.backdrop_path
                                    ))
                                }
                                if (batch.isNotEmpty()) {
                                    db.streamDao().insertVodStreams(batch)
                                    // Notifica o ContentRepository dos novos dados
                                    val atualizados = db.streamDao().getRecentVods(200)
                                    ContentRepository.atualizarVods(atualizados)
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    val jSeries = async(Dispatchers.IO) {
                        buscarJsonLimitado(
                            url = "${base}player_api.php?username=$user&password=$pass&action=get_series",
                            maxBytes = 300_000
                        )?.let { json ->
                            try {
                                val arr = JSONArray(json)
                                val batch = mutableListOf<SeriesEntity>()
                                // ⚠️ Mesma correção: preserva os selos já
                                // calculados em vez de zerá-los a cada login.
                                val existentes = try { db.streamDao().getAllSeries().associateBy { it.series_id } } catch (e: Exception) { emptyMap() }
                                for (i in 0 until minOf(12, arr.length())) {
                                    val o = arr.getJSONObject(i)
                                    val seriesId = o.optInt("series_id")
                                    val ex = existentes[seriesId]
                                    batch.add(SeriesEntity(
                                        series_id = seriesId,
                                        name = o.optString("name"),
                                        cover = o.optString("cover"),
                                        rating = o.optString("rating"),
                                        category_id = o.optString("category_id"),
                                        last_modified = o.optLong("last_modified"),
                                        logo_url = ex?.logo_url,
                                        tmdb_rank = ex?.tmdb_rank ?: 0,
                                        tmdb_release_date = ex?.tmdb_release_date,
                                        is_top10 = ex?.is_top10 ?: 0,
                                        is_novidade = ex?.is_novidade ?: 0,
                                        tmdb_id = ex?.tmdb_id,
                                        backdrop_path = ex?.backdrop_path,
                                        tmdb_ultima_temporada = ex?.tmdb_ultima_temporada ?: 0,
                                        tmdb_ultimo_episodio = ex?.tmdb_ultimo_episodio ?: 0,
                                        is_nova_temporada = ex?.is_nova_temporada ?: 0,
                                        is_novo_episodio = ex?.is_novo_episodio ?: 0,
                                        tmdb_flag_marcado_em = ex?.tmdb_flag_marcado_em ?: 0,
                                        tmdb_proxima_temporada_data = ex?.tmdb_proxima_temporada_data
                                    ))
                                }
                                if (batch.isNotEmpty()) {
                                    db.streamDao().insertSeriesStreams(batch)
                                    val atualizadas = db.streamDao().getRecentSeries(200)
                                    ContentRepository.atualizarSeries(atualizadas)
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    jVod.await()
                    jSeries.await()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun buscarJsonLimitado(url: String, maxBytes: Int = 300_000): String? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 12_000
                requestMethod  = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode != 200) { conn.disconnect(); return null }

            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var totalLido = 0
                var lido: Int
                while (reader.read(buffer).also { lido = it } != -1) {
                    sb.append(buffer, 0, lido)
                    totalLido += lido
                    if (totalLido >= maxBytes) break
                }
                conn.disconnect()
                sb.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun limparBancoPorTrocaDeUsuario() {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            withContext(Dispatchers.IO) {
                db.streamDao().clearLive()
                db.openHelper.writableDatabase.execSQL("DELETE FROM vod_streams")
                db.openHelper.writableDatabase.execSQL("DELETE FROM series_streams")
                db.openHelper.writableDatabase.execSQL("DELETE FROM watch_history")
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun normalizarBaseUrl(dns: String): String {
        var url = dns.trim()
        if (url.contains("player_api.php")) url = url.substringBefore("player_api.php")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        XtreamApi.salvarDns(this, dns)
    }

    // ✅ COMPORTAMENTO (estilo Netflix), usando SessionManager:
    //
    //   - Se a sessão ainda está ativa neste processo (ou seja, o app nunca
    //     chegou a ser encerrado de verdade desde a última vez que um perfil
    //     foi selecionado) E já existe um perfil salvo → pula direto pro
    //     Home... mas AGORA respeitando QUAL perfil estava ativo:
    //     se era o perfil Infantil, pula pra KidsActivity, não pra Home.
    //
    //   - Se o processo é novo (app foi fechado/matou/celular reiniciou),
    //     SessionManager.sessaoAtiva nasce `false` de novo → força passar
    //     pela tela de seleção de perfil, mesmo que já exista perfil salvo.
    //
    // ✅ CORREÇÃO CRÍTICA (perfil Infantil caindo no perfil adulto): antes,
    // esse método só verificava SE havia perfil salvo, mas sempre montava o
    // Intent apontando pra HomeActivity — mesmo quando o `perfilSalvo` era
    // "Infantil"/"Kids". Era esse o motivo de "fechar o app e reabrir" (ou
    // qualquer caminho que passasse de novo pela LoginActivity com a sessão
    // ainda viva) levar direto pro perfil adulto, mesmo tendo saído no
    // perfil das crianças. Agora, igual ao roteamento já usado em
    // SettingsActivity.executarTrocaPerfil() e ProfilesActivity, o nome do
    // perfil salvo é checado e o destino é escolhido de acordo.
    private fun decidirProximaTela() {
        val prefs       = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val perfilSalvo = prefs.getString("last_profile_name", null)
        val iconeSalvo  = prefs.getString("last_profile_icon", null)

        val ehPerfilInfantilSalvo = perfilSalvo?.contains("infantil", ignoreCase = true) == true ||
                                    perfilSalvo?.contains("kids", ignoreCase = true) == true

        val intent = when {
            isTelevisionDevice() -> Intent(this, HomeActivity::class.java).apply {
                putExtra("PROFILE_NAME", "TV_Box")
            }
            SessionManager.sessaoAtiva && !perfilSalvo.isNullOrBlank() -> {
                val destino = if (ehPerfilInfantilSalvo) KidsActivity::class.java else HomeActivity::class.java
                Intent(this, destino).apply {
                    putExtra("PROFILE_NAME", perfilSalvo)
                    putExtra("PROFILE_ICON", iconeSalvo ?: "")
                }
            }
            else -> Intent(this, ProfilesActivity::class.java)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun mostrarErro(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        pararAnimacaoPontinhos()
        super.onDestroy()
    }
}
