package com.vltv.play

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    // ✅ AGORA DINÂMICA: a lista de DNS vem da VPS (dns_config.json), via
    // XtreamApi.SERVERS → DnsConfig. Pra trocar/remover/adicionar um DNS,
    // edite o arquivo na VPS — não precisa mais mexer aqui nem recompilar.
    // É um getter (sem "="), então cada uso lê a lista mais atual, inclusive
    // a que acabou de ser baixada por DnsConfig.refresh().
    private val SERVERS: List<String>
        get() = XtreamApi.SERVERS

    // ✅ NOVO: .dns(XtreamApi.buildSafeDns()) — resolve os domínios via
    // DNS-over-HTTPS (Google/Cloudflare) em vez do DNS padrão da rede do
    // aparelho. Sem isso, se a operadora do cliente bloquear a resolução
    // de algum domínio específico da lista (ex.: supertv.red,
    // sivimcdn.click), o teste de login falhava com "Servidor não
    // encontrado" mesmo com usuário/senha corretos — apesar de o mesmo
    // domínio funcionar normalmente em outro player ou em outra rede.
    private val clientRapido = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dns(XtreamApi.buildSafeDns())
        .build()

    private val clientLento = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(XtreamApi.buildSafeDns())
        .build()

    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsJob: Runnable? = null
    private var dotsCount = 0

    // ✅ NOVO: estado do "olho" de mostrar/ocultar senha. Começa sempre
    // false (senha oculta) a cada abertura da tela de login.
    private var senhaVisivel = false

    // ✅ NOVO: preenchidos por testarServidor() quando algum servidor
    // responde que a conta EXISTE mas está expirada/desativada. Antes essa
    // resposta era descartada em silêncio (o servidor só era considerado
    // "não encontrado"), então quem tentava entrar com uma conta vencida
    // via "Servidor não encontrado. Verifique login e senha." em vez de
    // saber que o plano expirou. Volatile porque testarServidor() roda em
    // várias threads ao mesmo tempo (fase rápida em paralelo).
    @Volatile private var contaExpiradaDetectada = false
    @Volatile private var contaExpiradaEhTeste = false

    // ✅ TEMPORÁRIO — diagnóstico do bug "supertv.red/sivimcdn.click não
    // conectam no app mas funcionam em outro player": mostra um Toast com
    // o código HTTP real (ou a exceção) só pra esses domínios, sem afetar
    // os outros. Remover DOMINIOS_DEBUG (ou esvaziar a lista) depois de
    // identificar a causa.
    private val DOMINIOS_DEBUG = listOf("supertv.red", "sivimcdn.click")

    private fun logDebugDominio(baseUrl: String, mensagem: String) {
        if (DOMINIOS_DEBUG.none { baseUrl.contains(it, ignoreCase = true) }) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("[DEBUG] $baseUrl")
                .setMessage(mensagem)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ REMOVIDO: installSplashScreen() saiu daqui. A LoginActivity não
        // é mais a porta de entrada do app — quem cobre esse papel agora é
        // a SplashActivity (splash própria e animada, com o wordmark "VLTV
        // PLAY" surgindo letra por letra). O tema desta Activity voltou a
        // ser o normal (Theme.VLTVPlay), configurado no AndroidManifest.
        super.onCreate(savedInstanceState)

        // ✅ NOVO: baixa a lista de DNS mais recente da VPS em segundo plano
        // assim que o app abre. Se a VPS não responder, segue com a lista
        // que já estava guardada no aparelho (ou a de emergência embutida).
        lifecycleScope.launch(Dispatchers.IO) {
            DnsConfig.refresh(applicationContext)
        }

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

        // ✅ NOVO: distingue "nunca teve login" (1ª instalação de verdade,
        // pode gerar teste automático) de "saiu da conta pelo botão de
        // Configurações" (logout_requested = true). Sem isso, ao clicar em
        // "Sair da Conta", o app caía de novo no teste automático e — pra um
        // aparelho que já tinha teste gerado antes — voltava a logar sozinho
        // em vez de deixar a pessoa entrar com outro usuário/senha.
        //
        // ✅ CORREÇÃO (Sair → gerava teste automático): antes esta flag era
        // APAGADA aqui mesmo, assim que a tela de login abria pela primeira
        // vez. Aí, se o app fosse fechado e aberto de novo (ou se esta
        // Activity fosse recriada por qualquer motivo) sem ter feito login,
        // a flag já não existia mais: sem login salvo e sem flag, o app
        // achava que era uma instalação nova e gerava outro teste
        // automático, mandando direto pra tela de Perfis. Agora a flag só é
        // apagada quando um login é de fato salvo (ver salvarCredenciais()),
        // então "saí de propósito" continua valendo até a pessoa entrar de
        // novo. A limpeza de instalação nova (limparLoginRestauradoSeInstalacaoNova)
        // continua removendo a flag quando ela veio de backup restaurado.
        val logoutSolicitado = prefs.getBoolean("logout_requested", false)

        // ✅ NOVO: presente quando a HomeActivity detectou (em segundo
        // plano, sem tela de espera) que a conta expirou e já deslogou o
        // usuário antes de abrir esta tela. Mostra a mensagem certa aqui.
        val contaExpiradaExtra = intent.getStringExtra("CONTA_EXPIRADA")

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            // ✅ REMOVIDO: checagem de validade + tela "Verificando sua
            // conta..." antes de entrar. Agora entra direto, igual era antes
            // de existir o teste automático — a checagem de expiração passou
            // a rodar em segundo plano dentro da HomeActivity (ver
            // verificarValidadeContaEmSegundoPlano), sem travar a abertura.
            verificarEIniciarRapido(savedDns, savedUser, savedPass)
        } else if (contaExpiradaExtra != null) {
            setupUI()
            val msg = if (contaExpiradaExtra == "teste")
                "Seu teste expirou. Entre em contato com o suporte para assinar o VLTV Play."
            else
                "Sua assinatura expirou. Entre em contato com o suporte para renovar."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else if (logoutSolicitado) {
            // Saiu da conta de propósito — mostra o login manual, sem
            // gerar teste automático de novo.
            setupUI()
        } else {
            // Sem login salvo, sem flag de expirado/logout — 1ª abertura
            // (ou instalação nova): mostra a tela de login manual.
            setupUI()
        }
    }

    // Extensão usada para converter dp em pixels nas telas montadas via
    // código (ex.: tela de "assinatura/teste expirado" abaixo).
    private val Int.dpToPx: Int get() = (this * resources.displayMetrics.density).toInt()

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
    //
    // ✅ CORREÇÃO (bug "reinstalei e não gerou teste automático"): faltava
    // remover "logout_requested" aqui também. Esse flag mora no MESMO
    // arquivo "vltv_prefs" — e esse arquivo inteiro é alvo do Auto Backup
    // (só "vltv_device_marker" é excluído). Então, se em algum momento
    // antes de desinstalar você tinha clicado em "Sair" (deixando
    // logout_requested = true salvo), o Android restaurava esse valor
    // junto no reinstall. O restante da função já limpava username/
    // password/dns corretamente (savedUser saía null), mas como
    // logout_requested continuava true, o onCreate caía direto no ramo
    // "saiu de propósito" (setupUI() manual) em vez de tentar o teste
    // automático — mesmo sendo, de fato, uma instalação nova. Removendo
    // essa chave junto com as demais, uma reinstalação de verdade sempre
    // passa a cair no ramo do login manual normalmente.
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
                .remove("logout_requested")
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

    // ── Fluxo para usuário já logado ──────────────────────────────────────────
    private fun verificarEIniciarRapido(dns: String, user: String, pass: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            XtreamApi.setBaseUrl(dns)

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

    // ============================================================================
    // ✅ NOVO: tela de bloqueio — assinatura ou teste expirado
    // ============================================================================
    // Cobre a tela inteira (via addContentView) e NÃO tem botão de fechar
    // nem de voltar — só o botão do WhatsApp. bloqueadoPorExpiracao trava o
    // botão físico/gesto de voltar do Android enquanto essa tela estiver em
    // cena (ver onBackPressed()).
    private var bloqueadoPorExpiracao = false

    private val WHATSAPP_SUPORTE_NUMERO = "5531998491711"

    private fun abrirTelaExpirado(ehTeste: Boolean) {
        if (bloqueadoPorExpiracao) return // já está bloqueado, não duplica a tela
        bloqueadoPorExpiracao = true

        val titulo = if (ehTeste) "Teste expirado" else "Assinatura expirada"
        val mensagem = if (ehTeste)
            "Seu teste expirou. Entre em contato com o suporte para assinar o VLTV Play."
        else
            "Sua assinatura expirou. Renove agora mesmo para continuar assistindo."

        val icone = TextView(this).apply {
            text = "⛔"
            textSize = 44f
            gravity = Gravity.CENTER
        }
        val tvTitulo = TextView(this).apply {
            text = titulo
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16.dpToPx, 0, 8.dpToPx)
        }
        val tvMensagem = TextView(this).apply {
            text = mensagem
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(36.dpToPx, 0, 36.dpToPx, 28.dpToPx)
        }
        val btnWhatsapp = TextView(this).apply {
            text = "Falar com o Suporte"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(36.dpToPx, 14.dpToPx, 36.dpToPx, 14.dpToPx)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#25D366"))
                cornerRadius = 10.dpToPx.toFloat()
            }
            isClickable = true; isFocusable = true
            setOnClickListener { abrirWhatsAppSuporteExpirado(ehTeste) }
        }

        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icone, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(tvTitulo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(tvMensagem, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(btnWhatsapp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#080810"))
            addView(conteudo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun abrirWhatsAppSuporteExpirado(ehTeste: Boolean) {
        val mensagem = if (ehTeste)
            "Olá! Meu teste do VLTV Play expirou e gostaria de assinar."
        else
            "Olá! Minha assinatura do VLTV Play expirou e gostaria de renovar."

        val uri = Uri.parse(
            "https://api.whatsapp.com/send?phone=$WHATSAPP_SUPORTE_NUMERO&text=${Uri.encode(mensagem)}"
        )
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.whatsapp")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                Toast.makeText(this, "Não foi possível abrir o WhatsApp", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // ✅ Trava o botão/gesto de voltar do Android enquanto a tela de
        // "expirado" estiver em cena — sem isso dava pra sair dela e voltar
        // pro resto do app normalmente.
        if (bloqueadoPorExpiracao) return
        super.onBackPressed()
    }

    // ── Fluxo de login novo ───────────────────────────────────────────────────
    private fun iniciarLoginTurbo(user: String, pass: String) {
        mostrarLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            contaExpiradaDetectada = false
            contaExpiradaEhTeste = false

            // ✅ NOVO: garante a lista de DNS mais recente da VPS antes de
            // testar os servidores (não baixa de novo se já baixou há
            // menos de 1 minuto, e nunca demora mais que ~8s).
            DnsConfig.refresh(applicationContext)

            var dnsVencedor: String? = null

            try {
                val canal = Channel<String>(Channel.UNLIMITED)
                val jobs = SERVERS.map { url ->
                    launch(Dispatchers.IO) {
                        val r = testarServidor(url, user, pass, clientRapido)
                        if (r != null) canal.trySend(r)
                    }
                }
                // ✅ CORREÇÃO: polling em fatias de 300ms em vez de esperar
                // o teto de 18s inteiro, saindo assim que a expiração é
                // confirmada.
                val inicioEspera = System.currentTimeMillis()
                while (System.currentTimeMillis() - inicioEspera < 18_000L) {
                    val recebido = withTimeoutOrNull(300L) { canal.receive() }
                    if (recebido != null) { dnsVencedor = recebido; break }
                    if (contaExpiradaDetectada) break
                }
                jobs.forEach { it.cancel() }
                canal.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ✅ Fallback agora usa a MESMA lista (SERVERS), só que com o
            // client mais tolerante (clientLento: timeout maior e retry
            // ativado) — pra dar uma segunda chance aos mesmos DNS
            // antes de desistir, em vez de testar servidores que você não
            // usa mais.
            // ✅ Se algum servidor já confirmou que a conta expirou, pula
            // o fallback — não adianta insistir.
            if (dnsVencedor == null && !contaExpiradaDetectada) {
                for (servidor in SERVERS) {
                    // ✅ CORREÇÃO: sai do loop assim que a expiração é
                    // confirmada, em vez de continuar testando os servidores
                    // restantes à toa.
                    if (contaExpiradaDetectada) break
                    val r = testarServidor(servidor, user, pass, clientLento)
                    if (r != null) { dnsVencedor = r; break }
                    if (contaExpiradaDetectada) break
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
                // ✅ NOVO: se o servidor confirmou que a conta existe mas
                // está expirada, mostra a MESMA tela de bloqueio (com botão
                // do WhatsApp) usada no fluxo de reabertura com login salvo —
                // antes aparecia só um Toast, inconsistente com o outro fluxo.
                val expirada = contaExpiradaDetectada
                val ehTeste = contaExpiradaEhTeste
                withContext(Dispatchers.Main) {
                    esconderLoading()
                    if (expirada) {
                        abrirTelaExpirado(ehTeste)
                    } else {
                        mostrarErro("Servidor não encontrado. Verifique login e senha.")
                    }
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
                // ✅ CORREÇÃO: UA completo (com AppleWebKit/Chrome/Safari) —
                // o UA anterior era um navegador incompleto, que o nginx de
                // supertv.red/sivimcdn.click rejeitava com 403 "Access
                // denied" por não bater no padrão de UA aceito.
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""

                    // ✅ NOVO: registra quando o servidor responde que a
                    // conta existe (auth diferente de 0) mas está
                    // Expired/Disabled. A conta continua sendo rejeitada
                    // abaixo (retorna null), mas quem chamou agora sabe
                    // que o motivo foi expiração e pode avisar o usuário.
                    val temUserInfo = body.contains("user_info") && body.contains("server_info")
                    val authZero = Regex("\"auth\"\\s*:\\s*\"?0\"?").containsMatchIn(body)
                    val bloqueadaNoServidor = Regex(
                        "\"status\"\\s*:\\s*\"(Expired|Disabled)\"",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(body)
                    if (temUserInfo && !authZero && bloqueadaNoServidor) {
                        contaExpiradaEhTeste = Regex(
                            "\"is_trial\"\\s*:\\s*\"?(1|true)\"?",
                            RegexOption.IGNORE_CASE
                        ).containsMatchIn(body)
                        contaExpiradaDetectada = true
                    }

                    val valido = body.contains("user_info") &&
                            body.contains("server_info") &&
                            !body.contains("\"auth\":0") &&
                            !body.contains("\"auth\": 0") &&
                            !body.contains("\"auth\":\"0\"") &&
                            !body.contains("\"status\":\"Disabled\"") &&
                            !body.contains("\"status\":\"Expired\"")
                    if (valido) {
                        urlBase
                    } else {
                        logDebugDominio(baseUrl, "HTTP ${response.code}, resposta: ${body.take(150)}")
                        null
                    }
                } else {
                    val corpoErro = try { response.body?.string()?.take(300) } catch (e: Exception) { null }
                    val server = response.header("Server")
                    val cfRay = response.header("cf-ray")
                    logDebugDominio(
                        baseUrl,
                        "HTTP ${response.code}\nServer: $server\ncf-ray: $cfRay\nCorpo: $corpoErro"
                    )
                    null
                }
            }
        } catch (e: Exception) {
            logDebugDominio(baseUrl, "Exceção: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
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
            // ✅ CORREÇÃO: agora que a flag de logout não é mais apagada ao
            // abrir a tela de login (ver onCreate), é AQUI — quando um login
            // é de fato salvo — que ela deixa de valer.
            remove("logout_requested")
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
