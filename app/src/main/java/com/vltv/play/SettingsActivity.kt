package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Bitmap

class SettingsActivity : AppCompatActivity() {

    private lateinit var rvProfiles: RecyclerView
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentProfileName: String = "Padrao"
    private var currentProfileIcon: String? = null
    private val tmdbApiKey = "9b73f5dd15b8165b1b57419be2f29128"

    // ✅ NOVO: guarda a última lista de perfis carregada do banco (usada por
    // perfilAtualEhKids() pra saber se o perfil ATUALMENTE ativo é Kids —
    // sem isso, só teríamos o NOME do perfil ativo vindo do Intent, e
    // voltaríamos a cair no bug de checar por nome).
    private var listaPerfisAtual: List<ProfileEntity> = emptyList()

    // ✅ Links oficiais exibidos na tela "Sobre o Aplicativo"
    private val SITE_URL = "https://vltvplay.tech"
    private val INSTAGRAM_USERNAME = "vltv_play"

    // ✅ NOVO: Suporte via WhatsApp — número com DDI (55) + DDD (31) + número.
    // Mensagem pré-pronta já vai preenchida quando o cliente toca no botão.
    private val WHATSAPP_NUMBER = "5531998491711"
    private val WHATSAPP_MENSAGEM_PADRAO = "Olá! Preciso de ajuda com o VLTV Play 👋"

    // ✅ NOVO: lista de perguntas frequentes exibidas na tela de FAQ.
    private data class FaqItem(val pergunta: String, val resposta: String)

    private val FAQ_LIST = listOf(
        FaqItem(
            "Como troco minha senha ou usuário?",
            "Vá em Configurações → Trocar Credenciais, informe o novo usuário e senha e confirme. Atenção: isso apaga o histórico e favoritos do usuário atual neste aparelho."
        ),
        FaqItem(
            "Um canal, filme ou série não está carregando, o que eu faço?",
            "Feche e abra o app novamente. Se persistir, tente outro conteúdo para verificar se o problema é específico dele. Se continuar, fale com o suporte pelo WhatsApp."
        ),
        FaqItem(
            "Como funciona o Perfil Infantil?",
            "O Perfil Infantil mostra apenas conteúdo apropriado para crianças e pode ser protegido com PIN de Perfis, para impedir que a criança saia dele sozinha."
        ),
        FaqItem(
            "Como faço para baixar filmes e séries?",
            "Na tela de detalhes do filme ou do episódio, toque no ícone de download. Você pode acompanhar o progresso e gerenciar os downloads na tela de Downloads."
        ),
        FaqItem(
            "Esqueci o PIN do Controle Parental, e agora?",
            "Em Configurações → PIN, toque em \"Esqueci o PIN\" e responda a pergunta secreta que você cadastrou ao criar o PIN."
        ),
        FaqItem(
            "Posso usar minha conta em mais de um aparelho ao mesmo tempo?",
            "Isso depende do seu plano de assinatura. Consulte o suporte pelo WhatsApp para confirmar o limite de telas simultâneas da sua conta."
        )
    )

    // Views do card de plano
    private lateinit var tvNomePlano: TextView
    private lateinit var tvValidadePlano: TextView
    private lateinit var tvPlanoBadge: TextView
    private lateinit var tvPlanoIcone: TextView
    private lateinit var layoutProgressoPlano: LinearLayout
    private lateinit var layoutInfoExtra: LinearLayout
    private lateinit var tvDiasRestantes: TextView
    private lateinit var tvDataExpiracao: TextView
    private lateinit var progressPlano: ProgressBar
    private lateinit var tvUsuarioConta: TextView
    private lateinit var tvStatusConta: TextView

    private var tvCredenciaisSubtitle: TextView? = null

    // ✅ NOVO: referência ao subtítulo do card "PIN de Perfis" injetado
    // dinamicamente na lista de Configurações, pra atualizar o texto
    // ("Ativado"/"Desativado") sem precisar reconstruir o card inteiro.
    private var tvSubtituloPinPerfis: TextView? = null

    // ✅ AGORA DINÂMICA: a lista de DNS vem da VPS (dns_config.json), via
    // XtreamApi.SERVERS → DnsConfig — a MESMA lista usada na LoginActivity.
    // Pra trocar/remover/adicionar um DNS, edite o arquivo na VPS: não
    // precisa mais mexer aqui nem recompilar o app. É um getter (sem "="),
    // então cada uso lê a lista mais atual.
    private val SERVERS: List<String>
        get() = XtreamApi.SERVERS

    // ✅ CORREÇÃO: agora usa o mesmo resolvedor de DNS da LoginActivity
    // (DNS do sistema primeiro, DoH só se o sistema falhar).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfileName = intent.getStringExtra("PROFILE_NAME")
            ?: prefs.getString("last_profile_name", "Padrao") ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?: prefs.getString("last_profile_icon", null)

        val switchParental  = findViewById<Switch>(R.id.switchParental)
        val layoutPin       = findViewById<LinearLayout>(R.id.layoutPin)
        layoutPinDynamic    = findViewById(R.id.layoutPinDynamic)
        tvPinSectionLabel   = findViewById(R.id.tvPinSectionLabel)
        val tvVersion       = findViewById<TextView?>(R.id.tvVersion)
        val cardClearCache  = findViewById<LinearLayout?>(R.id.cardClearCache)
        val cardAbout       = findViewById<LinearLayout?>(R.id.cardAbout)
        val cardLogout      = findViewById<LinearLayout?>(R.id.cardLogout)
        val cardTrocarLogin = findViewById<LinearLayout?>(R.id.cardTrocarLogin)
        val btnBack         = findViewById<TextView?>(R.id.btnBackSettings)
        tvCredenciaisSubtitle = findViewById(R.id.tvCredenciaisSubtitle)
        rvProfiles = findViewById(R.id.rvProfilesSettings)

        tvNomePlano          = findViewById(R.id.tvNomePlano)
        tvValidadePlano      = findViewById(R.id.tvValidadePlano)
        tvPlanoBadge         = findViewById(R.id.tvPlanoBadge)
        tvPlanoIcone         = findViewById(R.id.tvPlanoIcone)
        layoutProgressoPlano = findViewById(R.id.layoutProgressoPlano)
        layoutInfoExtra      = findViewById(R.id.layoutInfoExtra)
        tvDiasRestantes      = findViewById(R.id.tvDiasRestantes)
        tvDataExpiracao      = findViewById(R.id.tvDataExpiracao)
        progressPlano        = findViewById(R.id.progressPlano)
        tvUsuarioConta       = findViewById(R.id.tvUsuarioConta)
        tvStatusConta        = findViewById(R.id.tvStatusConta)

        btnBack?.setOnClickListener { finish() }

        tvVersion?.text = try {
            "Versão ${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (e: Exception) { "Versão 1.0.0" }

        val usernameAtual = prefs.getString("username", "") ?: ""
        if (usernameAtual.isNotBlank()) {
            tvCredenciaisSubtitle?.text = "Conectado como: $usernameAtual"
        }

        val parentalAtivo = ParentalControlManager.isEnabled(this)
        switchParental.isChecked = parentalAtivo
        layoutPin.visibility = if (parentalAtivo) View.VISIBLE else View.GONE
        // ✅ Desenha a seção de PIN de acordo com o estado atual (criar / alterar / esqueci)
        renderPinSection()

        switchParental.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                mostrarDialogConfirmacao(
                    titulo      = "Ativar Controle Parental",
                    mensagem    = "O controle parental bloqueará conteúdo adulto em todas as telas. Defina um PIN de 4 dígitos para proteger as configurações.",
                    btnPositivo = "Ativar",
                    corPositivo = "#FFFFFF"
                ) {
                    ParentalControlManager.setEnabled(this, true)
                    layoutPin.visibility = View.VISIBLE
                    renderPinSection()
                    mostrarToastPremium("Controle parental ativado ✓")
                    // ✅ Se ainda estiver no PIN padrão (0000), já guia o usuário
                    // a criar um PIN pessoal na hora, em vez de deixar exposto.
                    if (!ParentalControlManager.hasCustomPin(this)) {
                        mostrarFluxoCriarPin(primeiraVez = true)
                    }
                }
            } else {
                verificarPinParaAcao("Desativar controle parental?") {
                    ParentalControlManager.setEnabled(this, false)
                    layoutPin.visibility = View.GONE
                    mostrarToastPremium("Controle parental desativado")
                }
            }
        }

        setupProfilesSection()
        carregarInfoPlano()

        cardClearCache?.setOnClickListener {
            mostrarDialogConfirmacao(
                titulo      = "Limpar Cache",
                mensagem    = "Isso remove imagens e dados temporários. O app pode ficar mais lento na próxima abertura enquanto recarrega.",
                btnPositivo = "Limpar",
                corPositivo = "#FFFFFF"
            ) {
                Thread { Glide.get(this).clearDiskCache() }.start()
                Glide.get(this).clearMemory()
                mostrarToastPremium("Cache limpo com sucesso ✓")
            }
        }

        cardAbout?.setOnClickListener { mostrarDialogSobre() }

        // ✅ NOVO: cards de "Falar com Suporte" (WhatsApp) e "Perguntas
        // Frequentes" (FAQ), injetados dinamicamente logo abaixo de "Sobre o
        // Aplicativo" — mesmo padrão já usado pro card de PIN de Perfis mais
        // abaixo nesta Activity. Não precisa mexer no activity_settings.xml.
        cardAbout?.let { about ->
            (about.parent as? ViewGroup)?.let { parent ->
                val indexAbout = parent.indexOfChild(about)
                parent.addView(criarCardFaq(), indexAbout + 1)
                parent.addView(criarCardSuporteWhatsapp(), indexAbout + 1)
            }
        }

        cardTrocarLogin?.setOnClickListener { mostrarDialogTrocarCredenciais() }

        // ✅ NOVO: card "Meu Acesso" — mostra usuário, senha e vencimento do
        // login atual (seja um login manual ou um teste gerado automaticamente
        // na 1ª abertura do app). Injetado logo ACIMA de "Trocar Credenciais",
        // na mesma seção CREDENCIAIS, sem precisar mexer no XML.
        cardTrocarLogin?.let { trocar ->
            (trocar.parent as? ViewGroup)?.let { parent ->
                val indexTrocar = parent.indexOfChild(trocar)
                parent.addView(criarCardMeuAcesso(), indexTrocar)
            }
        }

        cardLogout?.setOnClickListener {
            mostrarDialogConfirmacao(
                titulo      = "Sair da Conta",
                mensagem    = "Você será desconectado e redirecionado para a tela de login. Perfis, favoritos, downloads e histórico deste usuário serão apagados deste aparelho.",
                btnPositivo = "Sair",
                corPositivo = "#FF5252"
            ) {
                // ✅ CORREÇÃO: antes o logout só removia username/password/dns
                // do SharedPreferences "vltv_prefs" — a lista de PERFIS (tabela
                // ProfileEntity no Room) e os caches por-perfil (favoritos,
                // logos, texto) ficavam intactos. Como essa tabela e esses
                // caches não são por-conta, ao logar com outro usuário
                // (login B), a ProfilesActivity encontrava os perfis do
                // login ANTERIOR (A) ainda salvos e os reaproveitava, em vez
                // de criar perfis novos — misturando contas. Favoritos e
                // histórico de assistir (chaveados por NOME de perfil, ex:
                // "Perfil 1_favoritos") também vazavam pro próximo login se
                // o nome do perfil coincidisse (o que é comum, já que
                // "Perfil 1"/"Perfil 2"/"Infantil" são os nomes padrão).
                // Agora o logout faz a MESMA limpeza completa que já era
                // feita ao trocar de credenciais (executarTrocaDeCredenciais).
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val dao = database.streamDao()
                        dao.deleteAllProfiles()
                        dao.deleteAllDownloads()

                        // ✅ CORREÇÃO (resquício do login antigo após "Sair"):
                        // faltava apagar aqui o catálogo em si — vod_streams,
                        // series_streams, watch_history e os canais ao vivo
                        // (clearLive()). Só a LoginActivity fazia essa limpeza
                        // (limparBancoPorTrocaDeUsuario), e mesmo lá só rodava
                        // quando o "username" salvo era diferente do novo — só
                        // que o próprio botão "Sair" já apaga o vltv_prefs
                        // INTEIRO (incluindo o username) alguns passos abaixo,
                        // então na próxima vez que alguém logasse não havia
                        // mais username antigo pra comparar, e essa limpeza
                        // nunca rodava. Resultado: filmes/séries do servidor
                        // anterior continuavam no Room e apareciam como
                        // "indisponível" depois de logar em outro servidor.
                        // Mesma limpeza usada em limparBancoPorTrocaDeUsuario().
                        dao.clearLive()
                        database.openHelper.writableDatabase.execSQL("DELETE FROM vod_streams")
                        database.openHelper.writableDatabase.execSQL("DELETE FROM series_streams")
                        database.openHelper.writableDatabase.execSQL("DELETE FROM watch_history")
                    } catch (e: Exception) {
                        Log.e("VLTV_SETTINGS", "Erro ao limpar perfis/downloads/catálogo no logout: ${e.message}")
                    }

                    withContext(Dispatchers.Main) {
                        getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit().clear().apply()
                        Thread { Glide.get(this@SettingsActivity).clearDiskCache() }.start()
                        Glide.get(this@SettingsActivity).clearMemory()

                        // ✅ Limpa TODO o vltv_prefs (não só algumas chaves) —
                        // garante que nenhum resquício da conta anterior
                        // (posições de "continuar assistindo", categoria
                        // salva, etc., todas chaveadas por nome de perfil)
                        // sobreviva pro próximo login.
                        getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                            .clear()
                            .putBoolean("logout_requested", true)
                            .commit()

                        // ✅ Encerra a sessão em memória — garante que, mesmo se
                        // o processo continuar vivo depois do logout, a próxima
                        // entrada seja tratada como "sessão nova" (tela de
                        // perfil de novo).
                        SessionManager.encerrarSessao()

                        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            }
        }

        // ✅ NOVO: card "PIN de Perfis" — injetado dinamicamente logo ACIMA do
        // card "Sair da Conta", sem precisar mexer no activity_settings.xml.
        // Usa o pai do cardLogout (seja lá qual for o container real da tela)
        // e insere o novo card na mesma posição do cardLogout, empurrando-o
        // pra baixo — assim não depende de nenhum id novo no layout.
        cardLogout?.let { logout ->
            (logout.parent as? ViewGroup)?.let { parent ->
                val indexLogout = parent.indexOfChild(logout)
                parent.addView(criarCardPinPerfis(), indexLogout)
            }
        }
    }

    // ============================================================================
    // ✅ NOVO: "Meu Acesso" — usuário, senha e vencimento do login atual
    // ============================================================================
    // Serve tanto pra quem logou manualmente quanto pra quem entrou pelo
    // teste automático (gerado sozinho na 1ª abertura, sem tela de login) —
    // nos dois casos o usuário/senha ficam salvos nas mesmas chaves de
    // "vltv_prefs", então a lógica é idêntica.

    private fun criarCardMeuAcesso(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
                marginStart = 16.dp
                marginEnd = 16.dp
            }

            addView(criarIconeCircular("🔑", "#2A2A2A", 36))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp
                }
                addView(TextView(context).apply {
                    text = "Meu Acesso"
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = "Ver usuário, senha e vencimento"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#555555"))
            })
            setOnClickListener { mostrarDialogMeuAcesso() }
        }
    }

    // ✅ Copia texto pra área de transferência (usado nos botões de copiar
    // usuário/senha do dialog "Meu Acesso").
    private fun copiarParaClipboard(label: String, valor: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, valor))
        mostrarToastPremium("$label copiado ✓")
    }

    // ✅ Uma linha "rótulo + valor (mascarável) + botão copiar" — reaproveitada
    // pra usuário e senha dentro do dialog "Meu Acesso". A senha começa
    // oculta (••••••••) e pode ser revelada tocando no próprio valor.
    private fun criarLinhaCredencial(
        rotulo: String,
        valor: String,
        mascaravel: Boolean
    ): LinearLayout {
        var revelado = !mascaravel

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp }

            addView(TextView(context).apply {
                text = rotulo
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dp }
            })

            val linhaValor = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 12.dp, 10.dp, 12.dp)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 8.dp.toFloat()
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
            }

            val tvValor = TextView(context).apply {
                text = if (revelado) valor else "•".repeat(valor.length.coerceIn(6, 14))
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            if (mascaravel) {
                tvValor.isClickable = true
                tvValor.isFocusable = true
                tvValor.setOnClickListener {
                    revelado = !revelado
                    tvValor.text = if (revelado) valor else "•".repeat(valor.length.coerceIn(6, 14))
                }
            }

            linhaValor.addView(tvValor)
            linhaValor.addView(TextView(context).apply {
                text = "⧉"
                textSize = 16f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(10.dp, 0, 0, 0)
                isClickable = true; isFocusable = true
                setOnClickListener { copiarParaClipboard(rotulo, valor) }
            })

            addView(linhaValor)
        }
    }

    private fun mostrarDialogMeuAcesso() {
        val prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        if (username.isBlank() || password.isBlank()) {
            mostrarDialogInfo(
                titulo   = "Sem acesso salvo",
                mensagem = "Não encontramos usuário e senha salvos neste aparelho."
            )
            return
        }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }

        root.addView(TextView(this).apply {
            text = "🔑 Meu Acesso"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
        })
        root.addView(TextView(this).apply {
            text = "Use estes dados para acessar em outro aparelho ou renovar seu plano."
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 18.dp }
        })

        root.addView(criarLinhaCredencial("Usuário", username, mascaravel = false))
        root.addView(criarLinhaCredencial("Senha", password, mascaravel = true))

        // ✅ Vencimento reaproveita o mesmo texto já calculado por
        // carregarInfoPlano()/aplicarInfoPlano() para o card de Assinatura —
        // não faz uma segunda chamada de rede, só lê o que já está na tela.
        val vencimentoTexto = tvValidadePlano.text?.toString().orEmpty()
        if (vencimentoTexto.isNotBlank() && vencimentoTexto != "Carregando..." && vencimentoTexto != "Sincronizando com o servidor...") {
            root.addView(TextView(this).apply {
                text = "Vencimento: $vencimentoTexto"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 2.dp; bottomMargin = 16.dp }
            })
        }

        root.addView(TextView(this).apply {
            text = "Fechar"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48.dp
            ).apply { topMargin = 4.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.88).toInt()
            attributes = p
        }
        dialog.show()
    }

    // ============================================================================
    // ✅ NOVO: Suporte via WhatsApp + FAQ
    // ============================================================================

    // ✅ Desenha um ícone circular colorido com um emoji no centro — usado nos
    // cards de Suporte/FAQ pra não depender de nenhum ícone nativo do Android
    // (que costuma destoar visualmente do resto do app).
    private fun criarIconeCircular(emoji: String, corFundoHex: String, sizeDp: Int = 36): TextView {
        return TextView(this).apply {
            text = emoji
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(corFundoHex))
            }
        }
    }

    private fun abrirWhatsAppSuporte() {
        val uri = Uri.parse(
            "https://api.whatsapp.com/send?phone=$WHATSAPP_NUMBER&text=${Uri.encode(WHATSAPP_MENSAGEM_PADRAO)}"
        )
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.whatsapp")
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: WhatsApp não instalado, ou app WhatsApp Business
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                mostrarToastPremium("Não foi possível abrir o WhatsApp")
            }
        }
    }

    private fun criarCardSuporteWhatsapp(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
                marginStart = 16.dp
                marginEnd = 16.dp
            }

            addView(criarIconeCircular("💬", "#25D366", 36))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp
                }
                addView(TextView(context).apply {
                    text = "Falar com Suporte"
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = "Atendimento via WhatsApp"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#555555"))
            })
            setOnClickListener { abrirWhatsAppSuporte() }
        }
    }

    private fun criarCardFaq(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
                marginStart = 16.dp
                marginEnd = 16.dp
            }

            addView(criarIconeCircular("❓", "#2A2A2A", 36))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp
                }
                addView(TextView(context).apply {
                    text = "Perguntas Frequentes"
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = "Dúvidas comuns sobre o app"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#555555"))
            })
            setOnClickListener { mostrarDialogFaq() }
        }
    }

    // ✅ Dialog com lista de perguntas expansíveis (toque na pergunta pra
    // abrir/fechar a resposta) e um atalho no final direto pro WhatsApp,
    // caso a dúvida do cliente não esteja na lista.
    private fun mostrarDialogFaq() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val scrollView = ScrollView(this).apply { overScrollMode = ScrollView.OVER_SCROLL_NEVER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(0, 0, 0, 0)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 16.dp)
            addView(criarIconeCircular("❓", "#2A2A2A", 40))
            addView(TextView(context).apply {
                text = "Perguntas Frequentes"
                textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp
                }
            })
        })

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        root.addView(divider())

        FAQ_LIST.forEach { item ->
            val tvResposta = TextView(this).apply {
                text = item.resposta
                textSize = 12.5f
                setTextColor(Color.parseColor("#AAAAAA"))
                setLineSpacing(0f, 1.4f)
                visibility = View.GONE
                setPadding(24.dp, 0, 24.dp, 16.dp)
            }
            val tvSinal = TextView(this).apply {
                text = "+"
                textSize = 18f
                setTextColor(Color.parseColor("#555555"))
                layoutParams = LinearLayout.LayoutParams(24.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, null
                )
                addView(TextView(context).apply {
                    text = item.pergunta
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(tvSinal)
                setOnClickListener {
                    val vaiAbrir = tvResposta.visibility == View.GONE
                    tvResposta.visibility = if (vaiAbrir) View.VISIBLE else View.GONE
                    tvSinal.text = if (vaiAbrir) "−" else "+"
                }
            }
            root.addView(headerRow)
            root.addView(tvResposta)
            root.addView(divider())
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 18.dp, 24.dp, 8.dp)
            isClickable = true; isFocusable = true
            addView(criarIconeCircular("💬", "#25D366", 34))
            addView(TextView(context).apply {
                text = "Não encontrou sua dúvida? Fale com o suporte"
                textSize = 13f
                setTextColor(Color.parseColor("#4FC3F7"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp
                }
            })
            setOnClickListener { dialog.dismiss(); abrirWhatsAppSuporte() }
        })

        root.addView(TextView(this).apply {
            text = "Fechar"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48.dp
            ).apply { setMargins(24.dp, 12.dp, 24.dp, 20.dp) }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        scrollView.addView(root)
        dialog.setContentView(scrollView)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width  = (resources.displayMetrics.widthPixels * 0.9).toInt()
            p.height = (resources.displayMetrics.heightPixels * 0.75).toInt()
            attributes = p
        }
        dialog.show()
    }

    // ============================================================================
    // ✅ Dialog "Sobre o Aplicativo" com wordmark profissional (VLTV + Play)
    // e links clicáveis para o site e o Instagram oficiais.
    // ============================================================================

    private fun abrirUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            mostrarToastPremium("Não foi possível abrir o link")
        }
    }

    private fun abrirInstagram(username: String) {
        try {
            // Tenta abrir direto no app do Instagram
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=$username"))
            startActivity(appIntent)
        } catch (e: Exception) {
            // Fallback: navegador
            abrirUrl("https://instagram.com/$username")
        }
    }

    // ✅ Monta "VLTV" em branco/negrito + "Play" menor em vermelho, lado a lado —
    // wordmark simples, sem depender de imagem/logo.
    private fun criarWordmark(): TextView {
        val texto = "VLTV Play"
        val spannable = SpannableString(texto)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.WHITE), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#E50914")), 4, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(this).apply {
            text = spannable
            textSize = 24f
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun criarLinkRow(icone: String, titulo: String, subtitulo: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp }

            addView(TextView(context).apply {
                text = icone
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 10.dp
                }
                addView(TextView(context).apply {
                    text = titulo; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = subtitulo; textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#555555"))
            })
            setOnClickListener { onClick() }
        }
    }

    private fun mostrarDialogSobre() {
        val versao = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "1.0.0" }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 28.dp, 24.dp, 20.dp)
        }

        root.addView(criarWordmark().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
        })

        root.addView(TextView(this).apply {
            text = "Versão $versao"
            textSize = 12f
            setTextColor(Color.parseColor("#777777"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 18.dp }
        })

        root.addView(TextView(this).apply {
            text = "Seu entretenimento premium em um só lugar.\nFilmes, séries, canais ao vivo e muito mais."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setLineSpacing(0f, 1.4f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20.dp }
        })

        root.addView(criarLinkRow("🌐", "Site Oficial", SITE_URL.removePrefix("https://")) {
            dialog.dismiss()
            abrirUrl(SITE_URL)
        })

        root.addView(criarLinkRow("📷", "Instagram", "@$INSTAGRAM_USERNAME") {
            dialog.dismiss()
            abrirInstagram(INSTAGRAM_USERNAME)
        })

        root.addView(TextView(this).apply {
            text = "Fechar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48.dp
            ).apply { topMargin = 10.dp }
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 8.dp.toFloat()
            }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            attributes = p
        }
        dialog.show()
    }

    private fun mostrarDialogTrocarCredenciais() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val scrollView = ScrollView(this).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }

        root.addView(TextView(this).apply {
            text = "🔑 Trocar Credenciais"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFAA00"))
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#33FFAA00"))
            }
            addView(TextView(context).apply {
                text = "⚠️"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10.dp }
            })
            addView(TextView(context).apply {
                text = "Ao salvar, todo o histórico, favoritos e perfis do usuário atual serão apagados."
                textSize = 12f
                setTextColor(Color.parseColor("#FFAA00"))
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })

        fun addLabel(texto: String) {
            root.addView(TextView(this).apply {
                text = texto
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dp }
            })
        }

        fun criarCampo(hintText: String, valorInicial: String = "", isPassword: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(valorInicial)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                setSingleLine(true)
                inputType = if (isPassword)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 8.dp.toFloat()
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
                setPadding(14.dp, 13.dp, 14.dp, 13.dp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14.dp }
            }
        }

        addLabel("Usuário")
        val etUsuario = criarCampo("Digite o usuário", prefs.getString("username", "") ?: "")
        addLabel("Senha")
        val etSenha = criarCampo("Digite a senha", "", isPassword = true)

        root.addView(etUsuario)
        root.addView(etSenha)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { bottomMargin = 14.dp }
            setBackgroundColor(Color.parseColor("#222222"))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRow.addView(TextView(this).apply {
            text = "Cancelar"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        btnRow.addView(TextView(this).apply {
            text = "Salvar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 8.dp.toFloat()
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val novoUsuario = etUsuario.text.toString().trim()
                val novaSenha   = etSenha.text.toString().trim()
                if (novoUsuario.isBlank()) { etUsuario.error = "Informe o usuário"; etUsuario.requestFocus(); return@setOnClickListener }
                if (novaSenha.isBlank()) { etSenha.error = "Informe a senha"; etSenha.requestFocus(); return@setOnClickListener }
                dialog.dismiss()
                mostrarDialogConfirmacao(
                    titulo      = "Confirmar Troca",
                    mensagem    = "Isso irá apagar TODO o histórico, favoritos e perfis do usuário atual.\n\nEsta ação não pode ser desfeita. Confirmar?",
                    btnPositivo = "Confirmar",
                    corPositivo = "#FF5252"
                ) { buscarDnsEExecutarTroca(novoUsuario, novaSenha) }
            }
        })

        root.addView(btnRow)
        scrollView.addView(root)
        dialog.setContentView(scrollView)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width  = (resources.displayMetrics.widthPixels * 0.88).toInt()
            p.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            attributes = p
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()
        etUsuario.requestFocus()
    }

    // ✅ CORRIGIDO: fase rápida (paralela, clientRapido) e fase de fallback
    // (sequencial, clientLento) agora testam a MESMA lista única (SERVERS) —
    // igual ao padrão já usado na LoginActivity. Antes a fase de fallback
    // usava SERVERS_FALLBACK, uma lista à parte com vários DNS mortos, então
    // um login cujo DNS só respondia num terceiro ou quarto servidor da
    // lista de verdade nunca era encontrado por aqui.
    private fun buscarDnsEExecutarTroca(novoUsuario: String, novaSenha: String) {
        val progressDialog = android.app.Dialog(this)
        progressDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val progressRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(32.dp, 28.dp, 32.dp, 28.dp)
        }
        val pbBusca = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { bottomMargin = 14.dp }
        }
        val tvStatus = TextView(this).apply {
            text = "Localizando servidor..."
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
        }
        val tvSubStatus = TextView(this).apply {
            text = "Testando conexões disponíveis"
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp }
        }
        progressRoot.addView(pbBusca)
        progressRoot.addView(tvStatus)
        progressRoot.addView(tvSubStatus)
        progressDialog.setContentView(progressRoot)
        progressDialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 14.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.75).toInt()
            attributes = p
        }
        progressDialog.setCancelable(false)
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            // ✅ NOVO: garante a lista de DNS mais recente da VPS antes de
            // testar os servidores (não baixa de novo se já baixou há
            // menos de 1 minuto, e nunca demora mais que ~8s).
            DnsConfig.refresh(applicationContext)

            var dnsVencedor: String? = null
            try {
                // ✅ CORREÇÃO: mesmo esquema da LoginActivity — 1ª rodada
                // com 1 DNS por painel (lâmina), 2ª rodada com os irmãos.
                dnsVencedor = testarGrupoEmParalelo(
                    DnsConfig.primeiraRodada(), novoUsuario, novaSenha, 9, 15_000L
                )
                if (dnsVencedor == null) {
                    dnsVencedor = testarGrupoEmParalelo(
                        DnsConfig.segundaRodada(), novoUsuario, novaSenha, 6, 15_000L
                    )
                }
            } catch (e: Exception) {
                Log.e("VLTV_SETTINGS", "Fase paralela erro: ${e.message}")
            }

            if (dnsVencedor == null) {
                withContext(Dispatchers.Main) {
                    tvStatus.text    = "Tentando servidores alternativos..."
                    tvSubStatus.text = "Aguarde um momento"
                }
                val fimFallback = System.currentTimeMillis() + 30_000L
                for (servidor in SERVERS) {
                    if (System.currentTimeMillis() > fimFallback) break
                    val r = testarServidor(servidor, novoUsuario, novaSenha, clientLento)
                    if (r != null) { dnsVencedor = r; break }
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (dnsVencedor != null) {
                    val dnsFinal = normalizarBaseUrl(dnsVencedor)
                    executarTrocaDeCredenciais(dnsFinal, novoUsuario, novaSenha)
                } else {
                    mostrarDialogInfo(
                        titulo   = "Servidor não encontrado",
                        mensagem = "Não foi possível localizar um servidor ativo para este usuário e senha.\n\nVerifique se as credenciais estão corretas e tente novamente."
                    )
                }
            }
        }
    }

    // ✅ CORREÇÃO: mesmo bug do testarServidor() da LoginActivity e do
    // VpnInterceptor (XtreamApi.kt) — este testarServidor() (usado só no
    // fluxo de "Trocar Credenciais") ainda mandava o User-Agent
    // INCOMPLETO ("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", sem
    // AppleWebKit/Chrome/Safari), então em painéis como supertv.red e
    // sivimcdn.click a troca de credenciais também levava 403 "Access
    // Denied" e caía direto em "Servidor não encontrado", mesmo com
    // usuário/senha corretos. Trocado pro mesmo UA completo de Chrome
    // usado em todo o resto do app, mais Accept-Language.
    // ✅ NOVO: testa uma lista de DNS em paralelo (no máximo
    // maxSimultaneos ao mesmo tempo). Devolve o primeiro que responder
    // com login válido, ou null. Sai assim que todos terminam.
    private suspend fun CoroutineScope.testarGrupoEmParalelo(
        lista: List<String>, user: String, pass: String,
        maxSimultaneos: Int, tetoMs: Long
    ): String? {
        if (lista.isEmpty()) return null
        val canal = Channel<String>(Channel.UNLIMITED)
        val limite = Semaphore(maxSimultaneos)
        val jobs = lista.map { url ->
            launch(Dispatchers.IO) {
                limite.withPermit {
                    val r = testarServidor(url, user, pass, clientRapido)
                    if (r != null) canal.trySend(r)
                }
            }
        }
        var vencedor: String? = null
        val inicio = System.currentTimeMillis()
        while (System.currentTimeMillis() - inicio < tetoMs) {
            val recebido = withTimeoutOrNull(300L) { canal.receive() }
            if (recebido != null) { vencedor = recebido; break }
            if (jobs.all { it.isCompleted }) {
                vencedor = canal.tryReceive().getOrNull()
                break
            }
        }
        jobs.forEach { it.cancel() }
        canal.close()
        return vencedor
    }

    private fun testarServidor(baseUrl: String, user: String, pass: String, httpClient: OkHttpClient): String? {
        val urlBase     = normalizarBaseUrl(baseUrl)
        val urlSemBarra = urlBase.removeSuffix("/")
        return try {
            val request = Request.Builder()
                .url("$urlSemBarra/player_api.php?username=$user&password=$pass")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val valido = body.contains("user_info") &&
                            body.contains("server_info") &&
                            !body.contains("\"auth\":0") &&
                            !body.contains("\"auth\": 0")
                    if (valido) urlBase else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun normalizarBaseUrl(dns: String): String {
        var url = dns.trim()
        if (url.contains("player_api.php")) url = url.substringBefore("player_api.php")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun executarTrocaDeCredenciais(novoDns: String, novoUsuario: String, novaSenha: String) {
        val progressDialog = android.app.Dialog(this)
        progressDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val progressRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(32.dp, 28.dp, 32.dp, 28.dp)
        }
        progressRoot.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { bottomMargin = 14.dp }
        })
        progressRoot.addView(TextView(this).apply {
            text = "Aplicando novas credenciais..."
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
        })
        progressDialog.setContentView(progressRoot)
        progressDialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 14.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.72).toInt()
            attributes = p
        }
        progressDialog.setCancelable(false)
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = database.streamDao()
                dao.deleteAllProfiles()
                dao.deleteAllDownloads()

                // ✅ CORREÇÃO (resquício do login antigo após "Trocar
                // Credenciais"): este fluxo nunca limpava o catálogo em si —
                // vod_streams, series_streams, watch_history e os canais ao
                // vivo (clearLive()) continuavam no Room com os IDs do
                // servidor ANTERIOR, então filmes/séries antigos apareciam
                // "indisponível" depois de logar no servidor novo. Mesma
                // limpeza usada em limparBancoPorTrocaDeUsuario() na
                // LoginActivity.
                dao.clearLive()
                database.openHelper.writableDatabase.execSQL("DELETE FROM vod_streams")
                database.openHelper.writableDatabase.execSQL("DELETE FROM series_streams")
                database.openHelper.writableDatabase.execSQL("DELETE FROM watch_history")

                getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).edit().clear().commit()
                getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit().clear().commit()
                getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit().clear().commit()
                Glide.get(applicationContext).clearDiskCache()
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                    .clear()
                    .putString("dns",      novoDns)
                    .putString("username", novoUsuario)
                    .putString("password", novaSenha)
                    .putBoolean("logout_requested", false)
                    .commit()
                XtreamApi.salvarDns(applicationContext, novoDns)

                // ✅ CORREÇÃO: este fluxo nunca disparava o recarregamento do
                // catálogo pro novo servidor — o ContentRepository continuava
                // com os dados (em memória) do login anterior até o app ser
                // fechado e reaberto. Mesma chamada já usada pela LoginActivity
                // logo após um login normal.
                ContentRepository.recarregar(applicationContext)

                // ✅ Conta trocada = sessão nova; força passar pela tela de perfil.
                SessionManager.encerrarSessao()

                withContext(Dispatchers.Main) {
                    Glide.get(applicationContext).clearMemory()
                    progressDialog.dismiss()
                    mostrarToastPremium("Credenciais atualizadas! Redirecionando...")
                    startActivity(Intent(this@SettingsActivity, ProfilesActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            } catch (e: Exception) {
                Log.e("VLTV_SETTINGS", "Erro ao trocar credenciais: ${e.message}")
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    mostrarToastPremium("Erro ao aplicar credenciais. Tente novamente.")
                }
            }
        }
    }

    private fun carregarInfoPlano() {
        val prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        tvNomePlano.text     = "Verificando plano..."
        tvValidadePlano.text = "Sincronizando com o servidor..."

        val expDateCache = prefs.getString("exp_date", null)
        if (!expDateCache.isNullOrBlank()) {
            aplicarInfoPlano(PlanoUtils.classificarPlano(expDateCache), username, "Active")
        }

        if (username.isBlank() || password.isBlank()) {
            tvNomePlano.text     = "Sem informação"
            tvValidadePlano.text = "Usuário não autenticado"
            return
        }

        XtreamApi.service.login(username, password).enqueue(object : Callback<XtreamLoginResponse> {
            override fun onResponse(call: Call<XtreamLoginResponse>, response: Response<XtreamLoginResponse>) {
                val userInfo = response.body()?.user_info
                if (userInfo == null) {
                    runOnUiThread {
                        tvNomePlano.text     = "Erro ao carregar"
                        tvValidadePlano.text = "Não foi possível contatar o servidor"
                    }
                    return
                }
                prefs.edit().putString("exp_date", userInfo.exp_date ?: "").apply()
                val infoPlano = PlanoUtils.classificarPlano(userInfo.exp_date)
                val status    = userInfo.status ?: "Active"
                runOnUiThread { aplicarInfoPlano(infoPlano, userInfo.username ?: username, status) }
            }

            override fun onFailure(call: Call<XtreamLoginResponse>, t: Throwable) {
                runOnUiThread {
                    if (expDateCache.isNullOrBlank()) {
                        tvNomePlano.text     = "Sem conexão"
                        tvValidadePlano.text = "Não foi possível verificar o plano"
                    }
                    tvValidadePlano.text = tvValidadePlano.text.toString() + " (cache)"
                }
            }
        })
    }

    private fun aplicarInfoPlano(info: PlanoUtils.InfoPlano, username: String, status: String) {
        val corHex = PlanoUtils.corPlano(info)
        val cor    = Color.parseColor(corHex)

        tvNomePlano.text     = info.nomePlano
        tvValidadePlano.text = info.dataFormatada

        tvPlanoBadge.visibility = View.VISIBLE
        tvPlanoBadge.text = when {
            info.isExpirado  -> "EXPIRADO"
            info.isVitalicio -> "VITALÍCIO"
            else             -> info.nomePlano.uppercase().replace("PLANO ", "")
        }
        (tvPlanoBadge.background as? GradientDrawable)?.setColor(cor)
        tvPlanoBadge.setTextColor(if (info.isVitalicio) Color.BLACK else Color.WHITE)

        tvPlanoIcone.text = when {
            info.isExpirado         -> "⛔"
            info.isVitalicio        -> "👑"
            info.diasRestantes <= 7 -> "⚠️"
            else                    -> "⭐"
        }
        tvNomePlano.setTextColor(cor)

        if (!info.isVitalicio && !info.isExpirado && info.diasRestantes > 0) {
            layoutProgressoPlano.visibility = View.VISIBLE
            val totalDias = when {
                info.nomePlano.contains("Mensal")     -> 30L
                info.nomePlano.contains("Trimestral") -> 90L
                info.nomePlano.contains("Semestral")  -> 180L
                info.nomePlano.contains("Anual")      -> 365L
                else                                  -> 365L
            }
            val progresso = ((info.diasRestantes.toFloat() / totalDias) * 100).toInt().coerceIn(0, 100)
            progressPlano.progress = progresso
            progressPlano.progressTintList = android.content.res.ColorStateList.valueOf(cor)
            val diasLabel = if (info.diasRestantes == 1L) "1 dia restante" else "${info.diasRestantes} dias restantes"
            tvDiasRestantes.text = diasLabel
            tvDiasRestantes.setTextColor(cor)
            tvDataExpiracao.text = info.dataFormatada.replace("Válido até ", "")
        } else {
            layoutProgressoPlano.visibility = View.GONE
        }

        layoutInfoExtra.visibility = View.VISIBLE
        tvUsuarioConta.text = "Usuário: $username"
        val statusAtivo = status.equals("Active", ignoreCase = true)
        tvStatusConta.text = if (statusAtivo) "● Ativo" else "● $status"
        tvStatusConta.setTextColor(if (statusAtivo) Color.parseColor("#4CAF50") else Color.parseColor("#FF5252"))
    }

    private fun setupProfilesSection() {
        rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        carregarPerfis()
    }

    // ✅ NOVO: descobre se o perfil ATUALMENTE ativo (currentProfileName) é
    // Kids, consultando o campo isKids do último snapshot de perfis
    // carregado do banco — em vez de checar o NOME. Usado por
    // trocarPerfilAtivo() pra decidir se precisa pedir o PIN de Perfis antes
    // de sair da Área Infantil.
    private fun perfilAtualEhKids(): Boolean =
        listaPerfisAtual.find { it.name == currentProfileName }?.isKids ?: false

    private fun carregarPerfis() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bruto = database.streamDao().getAllProfiles()

            // ✅ Mesma auto-correção (self-heal) aplicada na ProfilesActivity:
            // eleva isKids de false→true quando o NOME já indica um perfil
            // infantil mas o campo no banco ainda não foi migrado. NUNCA
            // reverte true→false — depois disso, renomear
            // "Infantil"→"Vinícius" não perde mais o status de Kids.
            val perfis = bruto.map { p ->
                val pareceKidsPeloNome = p.name.contains("infantil", ignoreCase = true) ||
                                          p.name.contains("kids", ignoreCase = true)
                if (pareceKidsPeloNome && !p.isKids) {
                    val corrigido = p.copy(isKids = true)
                    database.streamDao().updateProfile(corrigido)
                    corrigido
                } else p
            }

            withContext(Dispatchers.Main) {
                listaPerfisAtual = perfis
                // ✅ NOVO: adapter agora recebe também o clique do item
                // "Criar perfil" (círculo tracejado com "+"), sempre
                // exibido como último item da fileira.
                rvProfiles.adapter = SettingsProfileAdapter(
                    perfis,
                    onClickPerfil = { perfil -> mostrarOpcoesEdicao(perfil) },
                    onClickAdicionar = { mostrarDialogNovoPerfilSettings() }
                )
            }
        }
    }

    private fun mostrarOpcoesEdicao(perfil: ProfileEntity) {
        val isAtivo = perfil.name == currentProfileName

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(0, 0, 0, 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp, 24.dp, 24.dp, 16.dp)
        }
        val imgHeader = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(72.dp, 72.dp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#222222"))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        // ✅ Usa drawable local no dialog de opções — sem Glide
        exibirAvatar(imgHeader, perfil.imageUrl)

        val tvNomeHeader = TextView(this).apply {
            text = perfil.name
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp }
        }
        val tvSubtitulo = TextView(this).apply {
            text = if (isAtivo) "Perfil ativo" else "Toque em uma opção"
            textSize = 12f
            setTextColor(if (isAtivo) Color.parseColor("#E50914") else Color.parseColor("#777777"))
            gravity = Gravity.CENTER
        }
        header.addView(imgHeader)
        header.addView(tvNomeHeader)
        header.addView(tvSubtitulo)

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#222222"))
        }

        fun opcao(icone: String, texto: String, cor: Int = Color.WHITE, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                    null, null
                )
                addView(TextView(context).apply {
                    text = icone; textSize = 18f; setTextColor(cor)
                    layoutParams = LinearLayout.LayoutParams(36.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = texto; textSize = 14f; setTextColor(cor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 12.dp
                    }
                })
                setOnClickListener { dialog.dismiss(); onClick() }
            }
        }

        root.addView(header)
        root.addView(divider())
        if (!isAtivo) {
            root.addView(opcao("👤", "Usar este perfil") { trocarPerfilAtivo(perfil) })
            root.addView(divider())
        }
        root.addView(opcao("✏️", "Editar nome") { editarNomePerfil(perfil) })

        // ✅ CORREÇÃO: perfil Kids não pode trocar avatar — fica com o
        // avatar fixo (av_infantil), mesma regra já aplicada na
        // ProfilesActivity. Antes essa opção aparecia pra QUALQUER perfil
        // aqui em Configurações, inclusive o Infantil.
        if (!perfil.isKids) {
            root.addView(divider())
            root.addView(opcao("🖼️", "Trocar avatar") { trocarAvatarPerfil(perfil) })
        }

        if (!isAtivo) {
            root.addView(divider())
            root.addView(opcao("🗑️", "Excluir perfil", Color.parseColor("#FF5252")) {
                confirmarExcluirPerfil(perfil)
            })
        }
        root.addView(divider())
        root.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            attributes = p
        }
        dialog.show()
    }

    // ✅ CORREÇÃO DE SEGURANÇA (2ª camada): mesmo com o roteamento por
    // isKids já certo (perfil Infantil sempre abre KidsActivity), faltava
    // travar o caminho INVERSO — sair do perfil Infantil por aqui, dentro
    // de Configurações, sem pedir nada. Agora, se o perfil ATUAL for Kids
    // (via perfilAtualEhKids()) e o ESCOLHIDO não for, e o
    // ProfileSwitchPinManager estiver ativado, pede o PIN de perfis antes
    // de trocar de verdade.
    //
    // ✅ CORREÇÃO PRINCIPAL: antes essa checagem era feita pelo NOME
    // (contains("infantil")/"kids"), o que quebrava assim que o perfil
    // Infantil era renomeado (ex: "Infantil" → "Vinícius"). Agora usa o
    // campo fixo isKids — tanto do perfil atual quanto do perfil destino.
    private fun trocarPerfilAtivo(perfil: ProfileEntity) {
        val ehPerfilInfantilAtual = perfilAtualEhKids()
        val ehPerfilInfantilDestino = perfil.isKids

        if (ehPerfilInfantilAtual && !ehPerfilInfantilDestino && ProfileSwitchPinManager.isEnabled(this)) {
            pedirPinTrocaPerfil(
                onSucesso = { executarTrocaPerfil(perfil) },
                onCancelar = { }
            )
        } else {
            executarTrocaPerfil(perfil)
        }
    }

    private fun executarTrocaPerfil(perfil: ProfileEntity) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_profile_name", perfil.name)
            putString("last_profile_icon", perfil.imageUrl ?: "")
            apply()
        }
        currentProfileName = perfil.name
        currentProfileIcon = perfil.imageUrl
        // ✅ Trocar de perfil pela tela de Configurações também conta como
        // sessão ativa — não deve pedir seleção de perfil de novo enquanto
        // o processo continuar vivo.
        SessionManager.marcarSessaoAtiva()
        mostrarToastPremium("Perfil alterado para ${perfil.name}")

        // ✅ CORREÇÃO: roteamento agora usa o campo fixo perfil.isKids em
        // vez do NOME do perfil — renomear "Infantil"→"Vinícius" não muda
        // mais o destino da navegação.
        val destino = if (perfil.isKids) KidsActivity::class.java else HomeActivity::class.java

        startActivity(Intent(this, destino).apply {
            putExtra("PROFILE_NAME", perfil.name)
            putExtra("PROFILE_ICON", perfil.imageUrl ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun editarNomePerfil(perfil: ProfileEntity) {
        mostrarDialogInput(
            titulo       = "Editar Nome",
            hint         = "Nome do perfil",
            valorInicial = perfil.name,
            btnPositivo  = "Salvar"
        ) { novoNome ->
            if (novoNome.isBlank()) { mostrarToastPremium("O nome não pode ficar em branco"); return@mostrarDialogInput }
            lifecycleScope.launch(Dispatchers.IO) {
                // ✅ perfil.copy(name = novoNome) preserva isKids automaticamente
                // — renomear o perfil Infantil não tira mais o status Kids.
                val perfilAtualizado = perfil.copy(name = novoNome)
                database.streamDao().updateProfile(perfilAtualizado)
                if (perfil.name == currentProfileName) {
                    getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                        .putString("last_profile_name", novoNome).apply()
                }
                withContext(Dispatchers.Main) {
                    if (perfil.name == currentProfileName) currentProfileName = novoNome
                    mostrarToastPremium("Nome atualizado ✓")
                    carregarPerfis()
                }
            }
        }
    }

    // ✅ Construtor novo: sem apiKey, recebe drawableId em vez de URL
    private fun trocarAvatarPerfil(perfil: ProfileEntity) {
        AvatarSelectionDialog(this) { drawableId ->
            lifecycleScope.launch(Dispatchers.IO) {
                val perfilAtualizado = perfil.copy(imageUrl = drawableId)
                database.streamDao().updateProfile(perfilAtualizado)
                if (perfil.name == currentProfileName) {
                    getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                        .putString("last_profile_icon", drawableId).apply()
                }
                withContext(Dispatchers.Main) {
                    if (perfil.name == currentProfileName) currentProfileIcon = drawableId
                    mostrarToastPremium("Avatar atualizado ✓")
                    carregarPerfis()
                }
            }
        }.show()
    }

    private fun confirmarExcluirPerfil(perfil: ProfileEntity) {
        mostrarDialogConfirmacao(
            titulo      = "Excluir Perfil",
            mensagem    = "Tem certeza que deseja excluir o perfil \"${perfil.name}\"? O histórico e favoritos deste perfil serão perdidos.",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                database.streamDao().deleteProfile(perfil)
                withContext(Dispatchers.Main) { mostrarToastPremium("Perfil excluído"); carregarPerfis() }
            }
        }
    }

    // ✅ Resolve "av_iron_man" → R.drawable.av_iron_man — instantâneo, sem rede
    // ✅ CORRIGIDO: o xfermode SRC_IN precisa ser aplicado num canvas.drawBitmap()
    //    explícito. Chamar drawable.draw(canvas) usa o Paint interno do próprio
    //    Drawable e ignora totalmente o nosso Paint mascarado — por isso o avatar
    //    aparecia quadrado, sem nenhum recorte circular.
    private fun exibirAvatar(imageView: ImageView, drawableId: String?) {
        val resId = if (!drawableId.isNullOrEmpty())
            resources.getIdentifier(drawableId, "drawable", packageName)
        else 0

        val drawable = if (resId != 0)
            ContextCompat.getDrawable(this, resId)
        else
            ContextCompat.getDrawable(this, R.drawable.ic_profile_placeholder)

        if (drawable == null) {
            imageView.setImageDrawable(null)
            return
        }

        val size = imageView.layoutParams?.width
            ?.takeIf { it > 0 }
            ?: (72 * resources.displayMetrics.density).toInt()

        // 1. Renderiza o drawable normalmente num bitmap quadrado (sem máscara ainda)
        val sourceBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(sourceCanvas)

        // 2. Cria o bitmap final, desenha o círculo de máscara e aplica o
        //    bitmap de origem por cima usando canvas.drawBitmap() com o
        //    Paint que tem o xfermode SRC_IN — assim o recorte circular
        //    realmente acontece.
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f

        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)

        imageView.setImageBitmap(output)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    // ✅ NOVO: desenha um círculo tracejado com um "+" no meio — usado no
    // item "Criar perfil" da fileira de perfis em Configurações. Reaproveita
    // o mesmo ImageView (imgProfileItem) do item normal de perfil, então
    // fica visualmente alinhado com os avatares (mesmo tamanho/posição).
    private fun desenharIconeAdicionarPerfil(imageView: ImageView) {
        val size = imageView.layoutParams?.width
            ?.takeIf { it > 0 }
            ?: (72 * resources.displayMetrics.density).toInt()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val strokeWidth = size * 0.045f

        val paintCirculo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = Color.parseColor("#555555")
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(size * 0.07f, size * 0.06f), 0f)
        }
        canvas.drawCircle(center, center, center - strokeWidth, paintCirculo)

        val paintPlus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth * 1.2f
            color = Color.parseColor("#AAAAAA")
            strokeCap = Paint.Cap.ROUND
        }
        val metade = size * 0.20f
        canvas.drawLine(center - metade, center, center + metade, center, paintPlus)
        canvas.drawLine(center, center - metade, center, center + metade, paintPlus)

        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    // ✅ NOVO: em vez de ir direto pro nome, agora pergunta primeiro que TIPO
    // de perfil o usuário quer criar — antes só dava pra criar perfil adulto
    // por aqui (o Infantil só existia se já vindo de fábrica ou criado na
    // ProfilesActivity, que tem essa mesma opção).
    private fun mostrarDialogNovoPerfilSettings() {
        mostrarDialogEscolherTipoPerfil(
            onEscolherAdulto    = { mostrarDialogNomeNovoPerfil(ehInfantil = false) },
            onEscolherInfantil  = { mostrarDialogNomeNovoPerfil(ehInfantil = true) }
        )
    }

    // ✅ NOVO: bottom-sheet-style dialog com as duas opções de tipo de
    // perfil, mesmo padrão visual do "Pergunta Secreta" (lista de opções
    // clicáveis com ícone + título + subtítulo).
    private fun mostrarDialogEscolherTipoPerfil(onEscolherAdulto: () -> Unit, onEscolherInfantil: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(0, 0, 0, 0)
        }

        root.addView(TextView(this).apply {
            text = "Criar Perfil"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setPadding(24.dp, 24.dp, 24.dp, 4.dp)
        })
        root.addView(TextView(this).apply {
            text = "Escolha o tipo de perfil"
            textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(24.dp, 0, 24.dp, 16.dp)
        })

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        root.addView(divider())

        fun opcaoTipo(icone: String, titulo: String, subtitulo: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, null
                )
                addView(TextView(context).apply {
                    text = icone; textSize = 22f
                    layoutParams = LinearLayout.LayoutParams(40.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 12.dp
                    }
                    addView(TextView(context).apply {
                        text = titulo; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(context).apply {
                        text = subtitulo; textSize = 11f
                        setTextColor(Color.parseColor("#888888"))
                    })
                })
                setOnClickListener { dialog.dismiss(); onClick() }
            }
        }

        root.addView(opcaoTipo("👤", "Perfil Adulto", "Acesso completo ao catálogo") { onEscolherAdulto() })
        root.addView(divider())
        root.addView(opcaoTipo("🌈", "Perfil Infantil", "Conteúdo filtrado, com PIN de saída") { onEscolherInfantil() })
        root.addView(divider())

        root.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat()
            })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    // ✅ Pede o nome depois que o tipo já foi escolhido. Pro perfil Infantil,
    // pré-preenche com "Infantil" (pode editar) e usa o avatar padrão do
    // Infantil (av_infantil, mesmo usado nos perfis de fábrica).
    //
    // ✅ CORREÇÃO PRINCIPAL: agora grava isKids = ehInfantil DIRETO no banco
    // — antes essa flag nunca era setada aqui, só o nome ganhava a palavra
    // "Infantil"/"Kids" (texto), e era só esse texto que o resto do app
    // (SettingsActivity, ProfilesActivity, KidsActivity) usava pra
    // reconhecer o perfil como infantil. Mantemos o texto no nome por
    // clareza visual, mas quem manda agora é o campo isKids — renomear o
    // perfil depois não muda mais o comportamento dele.
    private fun mostrarDialogNomeNovoPerfil(ehInfantil: Boolean) {
        mostrarDialogInput(
            titulo       = if (ehInfantil) "Criar Perfil Infantil" else "Criar Perfil",
            hint         = "Nome do perfil",
            valorInicial = if (ehInfantil) "Infantil" else "",
            btnPositivo  = "Criar"
        ) { nomeDigitado ->
            if (nomeDigitado.isBlank()) { mostrarToastPremium("Digite um nome para o perfil"); return@mostrarDialogInput }

            val jaTemPalavraChave = nomeDigitado.contains("infantil", ignoreCase = true) ||
                                     nomeDigitado.contains("kids", ignoreCase = true)
            val nomeFinal = if (ehInfantil && !jaTemPalavraChave) "$nomeDigitado Infantil" else nomeDigitado
            val avatarPadrao = if (ehInfantil) "av_infantil" else "av_iron_man"

            lifecycleScope.launch(Dispatchers.IO) {
                database.streamDao().insertProfile(
                    ProfileEntity(name = nomeFinal, imageUrl = avatarPadrao, isKids = ehInfantil)
                )
                withContext(Dispatchers.Main) {
                    mostrarToastPremium("Perfil criado ✓")
                    carregarPerfis()
                }
            }
        }
    }

    private fun verificarPinParaAcao(descricao: String, onSucesso: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = "🔒 PIN Necessário"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp }
        })
        root.addView(TextView(this).apply {
            text = descricao; textSize = 13f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        })
        val etPinVerifica = EditText(this).apply {
            hint = "••••"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1; textSize = 22f; setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#444444")); gravity = Gravity.CENTER; letterSpacing = 0.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        }
        root.addView(etPinVerifica)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.dp }
            weightSum = 2f
        }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { val sw = findViewById<Switch>(R.id.switchParental); sw?.isChecked = ParentalControlManager.isEnabled(this@SettingsActivity); dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val digitado = etPinVerifica.text.toString()
                if (ParentalControlManager.verifyPin(this@SettingsActivity, digitado)) { dialog.dismiss(); onSucesso() }
                else { etPinVerifica.setText(""); etPinVerifica.setHintTextColor(Color.parseColor("#FF5252")); etPinVerifica.hint = "PIN incorreto" }
            }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.82).toInt(); attributes = p
        }
        dialog.show()
        etPinVerifica.requestFocus()
    }

    // ============================================================================
    // ✅ NOVO FLUXO DE PIN: criar / alterar / esqueci com pergunta secreta
    // ============================================================================

    private lateinit var layoutPinDynamic: LinearLayout
    private lateinit var tvPinSectionLabel: TextView

    // ✅ Desenha a seção de PIN de acordo com o estado atual:
    // - Se ainda não existe PIN customizado (usando o padrão 0000): mostra aviso + botão "Criar PIN"
    // - Se já existe PIN customizado: mostra botões "Alterar PIN" e "Esqueci o PIN"
    private fun renderPinSection() {
        layoutPinDynamic.removeAllViews()

        if (!ParentalControlManager.hasCustomPin(this)) {
            tvPinSectionLabel.text = "Segurança do PIN"

            layoutPinDynamic.addView(TextView(this).apply {
                text = "⚠️ Usando PIN padrão (0000). Crie um PIN pessoal para proteger o controle parental."
                textSize = 12f
                setTextColor(Color.parseColor("#FFAA00"))
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10.dp }
            })

            layoutPinDynamic.addView(criarBotaoPin("Criar PIN agora", corFundo = "#FFFFFF", corTexto = Color.BLACK) {
                mostrarFluxoCriarPin(primeiraVez = true)
            })
        } else {
            tvPinSectionLabel.text = "PIN protegido"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            row.addView(criarBotaoPin("Alterar PIN", corFundo = "#1A1A1A", corTexto = Color.WHITE, comBorda = true) {
                verificarPinParaAcao("Digite o PIN atual para alterá-lo") {
                    mostrarFluxoCriarPin(primeiraVez = false)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginEnd = 6.dp }
            })
            row.addView(criarBotaoPin("Esqueci o PIN", corFundo = "#1A1A1A", corTexto = Color.parseColor("#FF8A80"), comBorda = true) {
                mostrarFluxoRecuperarViaPergunta()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginStart = 6.dp }
            })
            layoutPinDynamic.addView(row)
        }
    }

    private fun criarBotaoPin(texto: String, corFundo: String, corTexto: Int, comBorda: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(corTexto)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 46.dp
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor(corFundo))
                cornerRadius = 8.dp.toFloat()
                if (comBorda) setStroke(1.dp, Color.parseColor("#333333"))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ✅ Dialog com dois campos (novo PIN + confirmação). Ao salvar, se ainda
    // não existir pergunta secreta configurada, força a configuração dela
    // antes de considerar o processo concluído — assim ninguém fica com PIN
    // customizado e sem forma segura de recuperá-lo depois.
    private fun mostrarFluxoCriarPin(primeiraVez: Boolean) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = if (primeiraVez) "Criar PIN" else "Alterar PIN"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        })

        fun campoPin(hintText: String): EditText {
            return EditText(this).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1; textSize = 20f; setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555")); gravity = Gravity.CENTER; letterSpacing = 0.4f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
                setPadding(14.dp, 13.dp, 14.dp, 13.dp)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp }
            }
        }

        val etNovoPin = campoPin("Novo PIN (4 dígitos)")
        val etConfirmarPin = campoPin("Confirmar PIN")
        root.addView(etNovoPin)
        root.addView(etConfirmarPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp }
        }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Salvar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val novoPin = etNovoPin.text.toString().trim()
                val confirmacao = etConfirmarPin.text.toString().trim()
                if (novoPin.length != 4) { mostrarToastPremium("O PIN precisa ter exatamente 4 dígitos"); return@setOnClickListener }
                if (novoPin != confirmacao) { mostrarToastPremium("Os PINs digitados não são iguais"); return@setOnClickListener }

                ParentalControlManager.setPin(this@SettingsActivity, novoPin)
                dialog.dismiss()

                if (!ParentalControlManager.hasSecretQuestion(this@SettingsActivity)) {
                    mostrarToastPremium("PIN salvo ✓ Agora defina uma pergunta secreta")
                    mostrarFluxoDefinirPerguntaSecreta { renderPinSection() }
                } else {
                    mostrarToastPremium("PIN atualizado ✓")
                    renderPinSection()
                }
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        etNovoPin.requestFocus()
    }

    // ✅ Lista de perguntas secretas pré-definidas. A resposta de nenhuma delas
    // deve ser algo que uma criança usando o app conseguiria adivinhar de
    // primeira — por isso não usamos coisas como "seu nome" ou "seu time".
    private val PERGUNTAS_SECRETAS = listOf(
        "Qual o nome do seu primeiro animal de estimação?",
        "Qual o nome da cidade onde você nasceu?",
        "Qual o apelido que você tinha quando criança?",
        "Qual o nome do seu professor(a) favorito(a)?",
        "Personalizada..."
    )

    private fun mostrarFluxoDefinirPerguntaSecreta(aoConcluir: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(0, 0, 0, 0)
        }

        root.addView(TextView(this).apply {
            text = "🔐 Pergunta Secreta"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setPadding(24.dp, 24.dp, 24.dp, 4.dp)
        })
        root.addView(TextView(this).apply {
            text = "Usada para redefinir o PIN caso você esqueça. Escolha algo que só você saiba."
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setLineSpacing(0f, 1.3f)
            setPadding(24.dp, 0, 24.dp, 16.dp)
        })

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        root.addView(divider())

        PERGUNTAS_SECRETAS.forEach { pergunta ->
            root.addView(TextView(this).apply {
                text = pergunta
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, null
                )
                setOnClickListener {
                    dialog.dismiss()
                    if (pergunta == "Personalizada...") {
                        mostrarDialogInput(
                            titulo = "Sua Pergunta",
                            hint = "Digite sua pergunta secreta",
                            btnPositivo = "Próximo"
                        ) { perguntaCustom ->
                            if (perguntaCustom.isBlank()) { mostrarToastPremium("A pergunta não pode ficar em branco"); return@mostrarDialogInput }
                            pedirRespostaEConcluir(perguntaCustom, aoConcluir)
                        }
                    } else {
                        pedirRespostaEConcluir(pergunta, aoConcluir)
                    }
                }
            })
            root.addView(divider())
        }

        root.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.88).toInt(); attributes = p
        }
        dialog.show()
    }

    private fun pedirRespostaEConcluir(pergunta: String, aoConcluir: () -> Unit) {
        mostrarDialogInput(
            titulo = "Resposta Secreta",
            hint = "Digite a resposta",
            btnPositivo = "Salvar"
        ) { resposta ->
            if (resposta.isBlank()) { mostrarToastPremium("A resposta não pode ficar em branco"); return@mostrarDialogInput }
            ParentalControlManager.setSecretQuestion(this, pergunta, resposta)
            mostrarToastPremium("Pergunta secreta configurada ✓")
            aoConcluir()
        }
    }

    // ✅ Fluxo "Esqueci o PIN": pede a resposta da pergunta secreta antes de
    // liberar a criação de um novo PIN. Sem pergunta secreta configurada
    // (situação legada), avisa o usuário em vez de deixar qualquer um resetar.
    private fun mostrarFluxoRecuperarViaPergunta() {
        if (!ParentalControlManager.hasSecretQuestion(this)) {
            mostrarDialogInfo(
                titulo = "Pergunta secreta não configurada",
                mensagem = "Este PIN foi criado antes da pergunta secreta existir. Não é possível recuperá-lo automaticamente — desative e reative o controle parental para configurar uma pergunta secreta."
            )
            return
        }

        val pergunta = ParentalControlManager.getSecretQuestion(this) ?: return

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = "🔐 Recuperar PIN"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        })
        root.addView(TextView(this).apply {
            text = pergunta; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        })
        val etResposta = EditText(this).apply {
            hint = "Sua resposta"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            textSize = 14f; setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        root.addView(etResposta)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val resposta = etResposta.text.toString()
                if (ParentalControlManager.verifySecretAnswer(this@SettingsActivity, resposta)) {
                    dialog.dismiss()
                    mostrarFluxoCriarPin(primeiraVez = false)
                } else {
                    etResposta.setText("")
                    etResposta.setHintTextColor(Color.parseColor("#FF5252"))
                    etResposta.hint = "Resposta incorreta, tente novamente"
                }
            }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        etResposta.requestFocus()
    }

    // ============================================================================
    // ✅ NOVO: "PIN de Perfis" (ProfileSwitchPinManager) — PIN independente do
    // Controle Parental, exclusivo para proteger a troca de perfil ao sair
    // da Área Infantil. Card injetado dinamicamente em onCreate (sem precisar
    // de nenhum id novo no activity_settings.xml).
    // ============================================================================

    // ✅ CORREÇÃO DE LAYOUT: faltavam marginStart/marginEnd de 16dp neste card.
    // Ele é injetado dinamicamente via parent.addView() (não vem do XML), e
    // como o container pai não tem padding horizontal próprio, o card ficava
    // colado nas bordas da tela — MATCH_PARENT sem margem — enquanto os
    // outros cards (cardClearCache, cardAbout, cardTrocarLogin, cardLogout),
    // vindos do activity_settings.xml, já têm layout_marginHorizontal="16dp"
    // e por isso pareciam "menores"/mais curtos. Adicionando a mesma margem
    // de 16dp aqui, o card fica com a largura idêntica aos demais.
    private fun criarCardPinPerfis(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
                marginStart = 16.dp
                marginEnd = 16.dp
            }

            addView(TextView(context).apply {
                text = "🔐"
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 10.dp
                }
                addView(TextView(context).apply {
                    text = "PIN de Perfis"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = textoSubtituloPinPerfis()
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                    tvSubtituloPinPerfis = this
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#555555"))
            })
            setOnClickListener { mostrarConfiguracaoPinPerfis() }
        }
    }

    private fun textoSubtituloPinPerfis(): String {
        return if (ProfileSwitchPinManager.isEnabled(this))
            "Ativado — protege a saída da Área Kids"
        else
            "Desativado"
    }

    private fun atualizarSubtituloCardPinPerfis() {
        tvSubtituloPinPerfis?.text = textoSubtituloPinPerfis()
    }

    // ✅ Tela de configuração completa: ativar/desativar, criar/alterar PIN,
    // pergunta secreta — tudo num dialog próprio, sem depender de views novas
    // no XML. Mesmo padrão visual dos outros dialogs desta Activity.
    private fun mostrarConfiguracaoPinPerfis() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val scrollView = ScrollView(this).apply { overScrollMode = ScrollView.OVER_SCROLL_NEVER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }

        root.addView(TextView(this).apply {
            text = "🔐 PIN de Perfis"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
        })
        root.addView(TextView(this).apply {
            text = "Exige um PIN separado do Controle Parental sempre que alguém tentar sair do perfil Infantil para outro perfil. Não interfere no PIN de conteúdo adulto."
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 18.dp }
        })

        val rowSwitch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp }
        }
        rowSwitch.addView(TextView(this).apply {
            text = "Ativar proteção de saída"
            textSize = 14f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val switchPinPerfis = Switch(this).apply {
            isChecked = ProfileSwitchPinManager.isEnabled(this@SettingsActivity)
        }
        rowSwitch.addView(switchPinPerfis)
        root.addView(rowSwitch)

        val layoutDinamico = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(layoutDinamico)

        fun renderDinamico() {
            layoutDinamico.removeAllViews()
            if (!ProfileSwitchPinManager.isEnabled(this)) return

            if (!ProfileSwitchPinManager.hasCustomPin(this)) {
                layoutDinamico.addView(TextView(this).apply {
                    text = "⚠️ Usando PIN padrão (0000). Crie um PIN pessoal."
                    textSize = 12f; setTextColor(Color.parseColor("#FFAA00")); setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10.dp; topMargin = 4.dp }
                })
                layoutDinamico.addView(criarBotaoPin("Criar PIN agora", "#FFFFFF", Color.BLACK) {
                    mostrarFluxoCriarPinPerfis(primeiraVez = true) { renderDinamico(); atualizarSubtituloCardPinPerfis() }
                })
            } else {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
                row.addView(criarBotaoPin("Alterar PIN", "#1A1A1A", Color.WHITE, true) {
                    pedirPinTrocaPerfil(
                        onSucesso = { mostrarFluxoCriarPinPerfis(primeiraVez = false) { renderDinamico(); atualizarSubtituloCardPinPerfis() } },
                        onCancelar = {}
                    )
                }.apply { layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginEnd = 6.dp } })
                row.addView(criarBotaoPin("Esqueci o PIN", "#1A1A1A", Color.parseColor("#FF8A80"), true) {
                    mostrarRecuperarPinTrocaPerfil(
                        onSucesso = { mostrarFluxoCriarPinPerfis(primeiraVez = false) { renderDinamico(); atualizarSubtituloCardPinPerfis() } },
                        onCancelar = {}
                    )
                }.apply { layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginStart = 6.dp } })
                layoutDinamico.addView(row)
            }
        }
        renderDinamico()

        switchPinPerfis.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ProfileSwitchPinManager.setEnabled(this, true)
                mostrarToastPremium("PIN de Perfis ativado ✓")
                renderDinamico()
                atualizarSubtituloCardPinPerfis()
                if (!ProfileSwitchPinManager.hasCustomPin(this)) {
                    mostrarFluxoCriarPinPerfis(primeiraVez = true) { renderDinamico(); atualizarSubtituloCardPinPerfis() }
                }
            } else {
                // ✅ Desativar também exige o PIN atual — senão a própria
                // criança poderia simplesmente desligar a proteção.
                pedirPinTrocaPerfil(
                    onSucesso = {
                        ProfileSwitchPinManager.setEnabled(this, false)
                        mostrarToastPremium("PIN de Perfis desativado")
                        renderDinamico()
                        atualizarSubtituloCardPinPerfis()
                    },
                    onCancelar = { switchPinPerfis.isChecked = true }
                )
            }
        }

        root.addView(TextView(this).apply {
            text = "Fechar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp).apply { topMargin = 16.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        scrollView.addView(root)
        dialog.setContentView(scrollView)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes
            p.width  = (resources.displayMetrics.widthPixels * 0.88).toInt()
            p.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            attributes = p
        }
        dialog.show()
    }

    // ✅ Dialog com dois campos (novo PIN + confirmação), igual ao fluxo do
    // Controle Parental — mas grava no ProfileSwitchPinManager. Se ainda não
    // existir pergunta secreta PRÓPRIA deste PIN, força configurá-la ao final.
    private fun mostrarFluxoCriarPinPerfis(primeiraVez: Boolean, aoConcluir: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = if (primeiraVez) "Criar PIN de Perfis" else "Alterar PIN de Perfis"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        })

        fun campoPin(hintText: String): EditText {
            return EditText(this).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1; textSize = 20f; setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555")); gravity = Gravity.CENTER; letterSpacing = 0.4f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
                setPadding(14.dp, 13.dp, 14.dp, 13.dp)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp }
            }
        }

        val etNovoPin = campoPin("Novo PIN (4 dígitos)")
        val etConfirmarPin = campoPin("Confirmar PIN")
        root.addView(etNovoPin)
        root.addView(etConfirmarPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp }
        }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); aoConcluir() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Salvar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val novoPin = etNovoPin.text.toString().trim()
                val confirmacao = etConfirmarPin.text.toString().trim()
                if (novoPin.length != 4) { mostrarToastPremium("O PIN precisa ter exatamente 4 dígitos"); return@setOnClickListener }
                if (novoPin != confirmacao) { mostrarToastPremium("Os PINs digitados não são iguais"); return@setOnClickListener }

                ProfileSwitchPinManager.setPin(this@SettingsActivity, novoPin)
                dialog.dismiss()

                if (!ProfileSwitchPinManager.hasSecretQuestion(this@SettingsActivity)) {
                    mostrarToastPremium("PIN salvo ✓ Agora defina uma pergunta secreta")
                    mostrarFluxoDefinirPerguntaSecretaPerfis(aoConcluir)
                } else {
                    mostrarToastPremium("PIN atualizado ✓")
                    aoConcluir()
                }
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        etNovoPin.requestFocus()
    }

    // ✅ Reaproveita a mesma lista de perguntas pré-definidas (PERGUNTAS_SECRETAS)
    // já usada pelo Controle Parental — a pergunta/resposta em si é guardada
    // separadamente, dentro do ProfileSwitchPinManager.
    private fun mostrarFluxoDefinirPerguntaSecretaPerfis(aoConcluir: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(0, 0, 0, 0)
        }

        root.addView(TextView(this).apply {
            text = "🔐 Pergunta Secreta"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setPadding(24.dp, 24.dp, 24.dp, 4.dp)
        })
        root.addView(TextView(this).apply {
            text = "Usada para redefinir o PIN de Perfis caso você esqueça. Escolha algo que só você saiba."
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setLineSpacing(0f, 1.3f)
            setPadding(24.dp, 0, 24.dp, 16.dp)
        })

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        root.addView(divider())

        PERGUNTAS_SECRETAS.forEach { pergunta ->
            root.addView(TextView(this).apply {
                text = pergunta
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, null
                )
                setOnClickListener {
                    dialog.dismiss()
                    if (pergunta == "Personalizada...") {
                        mostrarDialogInput(
                            titulo = "Sua Pergunta",
                            hint = "Digite sua pergunta secreta",
                            btnPositivo = "Próximo"
                        ) { perguntaCustom ->
                            if (perguntaCustom.isBlank()) { mostrarToastPremium("A pergunta não pode ficar em branco"); return@mostrarDialogInput }
                            pedirRespostaEConcluirPerfis(perguntaCustom, aoConcluir)
                        }
                    } else {
                        pedirRespostaEConcluirPerfis(pergunta, aoConcluir)
                    }
                }
            })
            root.addView(divider())
        }

        root.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); aoConcluir() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.88).toInt(); attributes = p
        }
        dialog.show()
    }

    private fun pedirRespostaEConcluirPerfis(pergunta: String, aoConcluir: () -> Unit) {
        mostrarDialogInput(
            titulo = "Resposta Secreta",
            hint = "Digite a resposta",
            btnPositivo = "Salvar"
        ) { resposta ->
            if (resposta.isBlank()) { mostrarToastPremium("A resposta não pode ficar em branco"); return@mostrarDialogInput }
            ProfileSwitchPinManager.setSecretQuestion(this, pergunta, resposta)
            mostrarToastPremium("Pergunta secreta configurada ✓")
            aoConcluir()
        }
    }

    // ✅ Verificação de PIN de Perfis — usada tanto pela troca de perfil em
    // trocarPerfilAtivo() quanto pelos botões "Alterar"/"Desativar" dentro
    // da própria tela de configuração do PIN de Perfis.
    private fun pedirPinTrocaPerfil(onSucesso: () -> Unit, onCancelar: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = "🔒 PIN de Perfis"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp }
        })
        root.addView(TextView(this).apply {
            text = "Digite o PIN para continuar"; textSize = 13f; setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        })
        val etPin = EditText(this).apply {
            hint = "••••"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1; textSize = 22f; setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#444444")); gravity = Gravity.CENTER; letterSpacing = 0.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        }
        root.addView(etPin)

        root.addView(TextView(this).apply {
            text = "Esqueci o PIN"
            textSize = 12f; setTextColor(Color.parseColor("#4FC3F7")); gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp }
            setOnClickListener {
                dialog.dismiss()
                mostrarRecuperarPinTrocaPerfil(onSucesso = onSucesso, onCancelar = onCancelar)
            }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.dp }
        }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onCancelar() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val digitado = etPin.text.toString()
                if (ProfileSwitchPinManager.verifyPin(this@SettingsActivity, digitado)) {
                    dialog.dismiss(); onSucesso()
                } else {
                    etPin.setText("")
                    etPin.setHintTextColor(Color.parseColor("#FF5252"))
                    etPin.hint = "PIN incorreto"
                }
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.82).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onCancelar() }
        dialog.show()
        etPin.requestFocus()
    }

    // ✅ Fluxo "Esqueci o PIN" do PIN de Perfis — exige a resposta da pergunta
    // secreta PRÓPRIA desse PIN (guardada no ProfileSwitchPinManager, nunca
    // a do Controle Parental).
    private fun mostrarRecuperarPinTrocaPerfil(onSucesso: () -> Unit, onCancelar: () -> Unit) {
        if (!ProfileSwitchPinManager.hasSecretQuestion(this)) {
            mostrarDialogInfo(
                titulo = "Pergunta secreta não configurada",
                mensagem = "O PIN de Perfis foi ativado, mas ainda não tem pergunta secreta configurada. Abra Configurações → PIN de Perfis → Alterar PIN pra configurar uma."
            )
            onCancelar()
            return
        }

        val pergunta = ProfileSwitchPinManager.getSecretQuestion(this) ?: return

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = "🔐 Recuperar PIN de Perfis"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        })
        root.addView(TextView(this).apply {
            text = pergunta; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        })
        val etResposta = EditText(this).apply {
            hint = "Sua resposta"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            textSize = 14f; setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        root.addView(etResposta)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onCancelar() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val resposta = etResposta.text.toString()
                if (ProfileSwitchPinManager.verifySecretAnswer(this@SettingsActivity, resposta)) {
                    dialog.dismiss(); onSucesso()
                } else {
                    etResposta.setText("")
                    etResposta.setHintTextColor(Color.parseColor("#FF5252"))
                    etResposta.hint = "Resposta incorreta, tente novamente"
                }
            }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        etResposta.requestFocus()
    }

    private fun mostrarDialogConfirmacao(titulo: String, mensagem: String, btnPositivo: String, corPositivo: String = "#FFFFFF", onConfirmar: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141414")); setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        })
        root.addView(TextView(this).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20.dp }
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val corBtnPos = try { Color.parseColor(corPositivo) } catch (e: Exception) { Color.WHITE }
        val isDestructive = corPositivo == "#FF5252"
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat(); setStroke(1.dp, Color.parseColor("#2A2A2A")) }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = btnPositivo; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDestructive) Color.WHITE else Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(corBtnPos); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss(); onConfirmar() }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    private fun mostrarDialogInfo(titulo: String, mensagem: String) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141414")); setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp }
        })
        root.addView(TextView(this).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20.dp }
        })
        root.addView(TextView(this).apply {
            text = "OK"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.82).toInt(); attributes = p
        }
        dialog.show()
    }

    private fun mostrarDialogInput(titulo: String, hint: String, valorInicial: String = "", btnPositivo: String = "Confirmar", onConfirmar: (String) -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141414")); setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        })
        val input = EditText(this).apply {
            this.hint = hint; setText(valorInicial); setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            textSize = 15f; setSingleLine(true)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat(); setStroke(1.dp, Color.parseColor("#333333")) }
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        root.addView(input)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat(); setStroke(1.dp, Color.parseColor("#2A2A2A")) }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = btnPositivo; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss(); onConfirmar(input.text.toString().trim()) }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        input.requestFocus()
    }

    private fun mostrarToastPremium(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    inner class SettingsProfileAdapter(
        private val list: List<ProfileEntity>,
        private val onClickPerfil: (ProfileEntity) -> Unit,
        private val onClickAdicionar: () -> Unit
    ) : RecyclerView.Adapter<SettingsProfileAdapter.VH>() {

        private val VIEW_TYPE_PERFIL = 0
        private val VIEW_TYPE_ADICIONAR = 1

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgProfileItem)
            val tvName: TextView = v.findViewById(R.id.tvProfileNameItem)
        }

        override fun getItemViewType(position: Int): Int =
            if (position == list.size) VIEW_TYPE_ADICIONAR else VIEW_TYPE_PERFIL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile_settings, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            // ✅ NOVO: último item da fileira é sempre "Criar perfil" — círculo
            // tracejado com "+" no meio, do lado do último perfil (ex: Infantil).
            if (getItemViewType(position) == VIEW_TYPE_ADICIONAR) {
                holder.img.setBackgroundResource(0)
                desenharIconeAdicionarPerfil(holder.img)
                holder.tvName.text = "Criar perfil"
                holder.tvName.setTextColor(Color.parseColor("#888888"))
                holder.tvName.typeface = Typeface.DEFAULT
                holder.itemView.alpha = 1.0f
                holder.itemView.setOnClickListener { onClickAdicionar() }
                return
            }

            val perfil = list[position]
            holder.tvName.text = perfil.name

            // ✅ Usa drawable local — sem Glide, sem rede, instantâneo
            exibirAvatar(holder.img, perfil.imageUrl)

            val isAtivo = perfil.name == currentProfileName
            holder.itemView.alpha = if (isAtivo) 1.0f else 0.55f
            holder.img.setBackgroundResource(if (isAtivo) R.drawable.bg_profile_border else 0)
            holder.tvName.setTextColor(if (isAtivo) Color.WHITE else Color.parseColor("#888888"))
            holder.tvName.typeface = if (isAtivo) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.itemView.setOnClickListener { onClickPerfil(perfil) }
        }

        override fun getItemCount() = list.size + 1
    }
}
