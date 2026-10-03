package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.CategoryEntity
import com.vltv.play.data.VodEntity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VodActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var gridCachePrefs: SharedPreferences

    // Controle de "última sincronização" por categoria, salvo em
    // SharedPreferences (sobrevive entre aberturas da Activity/app).
    private lateinit var syncPrefs: SharedPreferences
    private val SYNC_STALE_MS = 6 * 60 * 60 * 1000L // 6 horas

    // ✅ NOVO: depois que o TMDB responde "esse filme não tem logo", o app
    // lembra por 7 dias e não pergunta de novo a cada rolagem.
    private val SEM_LOGO_TTL_MS = 7L * 24 * 60 * 60 * 1000

    // ✅ NOVO: no máximo 3 buscas de logo ao mesmo tempo (antes não havia
    // limite: rolar a lista disparava dezenas de chamadas juntas, brigando
    // por banda com os pôsteres e com a API do servidor).
    private val logoSemaphore = Semaphore(3)

    // ✅ NOVO: Regex criada UMA vez (antes era recriada a cada chamada,
    // milhares de vezes por ordenação).
    private val regexAno = Regex("\\b(19|20)\\d{2}\\b")

    // Cache em memória da sessão — evita bater na rede duas vezes para a mesma categoria
    private val moviesCache = mutableMapOf<String, List<VodStream>>()
    private var categoryAdapter: VodCategoryAdapter? = null

    // Adapter único — nunca recriado, atualizado via DiffUtil
    private var moviesAdapter: VodAdapter? = null

    private val logoMemoryCache = mutableMapOf<String, String>()
    private var ultimaCategoriaId: String? = null
    private var ultimaCategoriaNome: String? = null

    // Guard de race condition
    private var categoriaAtualId: String? = null

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var bottomNavigation: BottomNavigationView? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    // Detecção de TV centralizada em DeviceUtils.kt (context.isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    // ✅ Filtro central de conteúdo adulto para FILMES. Agora é chamado
    // DENTRO do submitList do adapter (em thread de fundo), então quem
    // chama só entrega a lista crua.
    private fun filtrarFilmesAdultos(lista: List<VodStream>): List<VodStream> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) || ParentalControlManager.isAdultName(it.title) }
        else lista
    }

    // ✅ Filtro central de conteúdo adulto para CATEGORIAS
    private fun filtrarCategoriasAdultas(lista: List<LiveCategory>): List<LiveCategory> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    // Extrai o ano (19xx ou 20xx) do nome do filme. Aceita String? porque o
    // servidor Xtream às vezes manda "name" nulo.
    private fun extrairAnoFilme(nome: String?): Int {
        if (nome.isNullOrEmpty()) return 0
        return regexAno.find(nome)?.value?.toIntOrNull() ?: 0
    }

    // ✅ NOVO: calcula o ano UMA vez por filme e depois ordena (antes o
    // sortedByDescending chamava a Regex várias vezes por item, na thread
    // principal). Ordem estável: filmes sem ano ficam no final.
    private fun ordenarPorAno(lista: List<VodStream>): List<VodStream> =
        lista.map { extrairAnoFilme(it.name) to it }
            .sortedByDescending { it.first }
            .map { it.second }

    private fun categoriaEstaFresca(categoriaId: String): Boolean {
        val ultimaSync = syncPrefs.getLong("sync_$categoriaId", 0L)
        return (System.currentTimeMillis() - ultimaSync) < SYNC_STALE_MS
    }

    private fun marcarCategoriaSincronizada(categoriaId: String) {
        syncPrefs.edit().putLong("sync_$categoriaId", System.currentTimeMillis()).apply()
    }

    private fun paraVodStreams(lista: List<VodEntity>): List<VodStream> =
        lista.map {
            VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating, it.added, it.tmdb_release_date)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        TemaVisualManager.aplicarEm(this, findViewById(R.id.overlayTemaSazonal))

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (this.isTelevisionDevice()) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        rvCategories    = findViewById(R.id.rvCategories)
        rvMovies        = findViewById(R.id.rvChannels)
        progressBar     = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        gridCachePrefs  = getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)
        syncPrefs       = getSharedPreferences("vltv_vod_sync", Context.MODE_PRIVATE)

        setupBottomNavigation()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        findViewById<View>(R.id.etSearchContent)?.apply {
            isFocusableInTouchMode = false
            setOnClickListener {
                startActivity(Intent(this@VodActivity, SearchActivity::class.java).apply {
                    putExtra("initial_query", "")
                    putExtra("PROFILE_NAME", currentProfile)
                    putExtra("tipo_pesquisa", "filmes")
                })
            }
        }

        prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        if (this.isTelevisionDevice()) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 3)
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER

        if (this.isTelevisionDevice()) {
            rvCategories.isFocusable = true
            rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rvMovies.isFocusable = true
            rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            rvCategories.isFocusable = false
            rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rvMovies.isFocusable = false
            rvMovies.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(100)

        // Adapter criado UMA vez — nunca recriado
        moviesAdapter = VodAdapter(
            onItemClick     = { abrirDetalhes(it) },
            onDownloadClick = { mostrarMenuDownload(it) }
        )
        rvMovies.adapter = moviesAdapter

        // Última categoria salva
        val catPrefs = getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE)
        ultimaCategoriaId   = catPrefs.getString("ultima_cat_id", null)
        ultimaCategoriaNome = catPrefs.getString("ultima_cat_nome", null)

        // ── CARREGAMENTO INSTANTÂNEO DE FILMES (quando o repositório já está pronto) ──
        val catId = ultimaCategoriaId
        if (catId != null) {
            val filmesEmMemoria = ContentRepository.getVodsByCategory(catId)
            if (filmesEmMemoria.isNotEmpty()) {
                categoriaAtualId = catId
                if (ultimaCategoriaNome != null) tvCategoryTitle.text = ultimaCategoriaNome
                // Filtro, ordenação, diff e pré-carga de capas acontecem
                // dentro do submitList (em thread de fundo).
                moviesAdapter?.submitList(paraVodStreams(filmesEmMemoria))
            }
        }

        // ── CARREGAMENTO INSTANTÂNEO DE CATEGORIAS ───────────────────────────
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categoriasSalvas = database.streamDao().getCategoriesByType("vod")
                if (categoriasSalvas.isNotEmpty()) {
                    val cats = mutableListOf<LiveCategory>()
                    cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                    cats.addAll(categoriasSalvas.map {
                        LiveCategory(category_id = it.category_id, category_name = it.category_name)
                    })
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) aplicarCategorias(cats)
                    }
                }
                // Sempre busca da rede em background para manter atualizado
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home      -> { finish(); true }
                R.id.nav_search    -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_novidades -> {
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_profile   -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = vltvPrefs.getString("last_profile_name", currentProfile) ?: currentProfile
        currentProfileIcon = vltvPrefs.getString("last_profile_icon", currentProfileIcon)
            ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
    }

    // Usa lifecycleScope: a coroutine é cancelada junto com a Activity,
    // evitando "You cannot start a load for a destroyed activity".
    private fun preLoadImages(filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            filmes.take(30).forEach { vod ->
                val url = vod.icon ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Glide.with(this@VodActivity)
                        .asBitmap().load(url)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .preload(240, 360)
                }
            }
        }
    }

    // ✅ NOVO: leitura de URL com timeout (antes URL.readText() não tinha
    // timeout nenhum e podia ficar pendurada).
    private fun lerUrl(url: String): String {
        val c = URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // Devolve:
    //  - a URL do logo, se achou;
    //  - "" (vazio) se o TMDB respondeu e o filme NÃO tem logo (vira cache negativo);
    //  - null se deu erro de rede (não guarda nada, tenta de novo depois).
    private suspend fun searchTmdbLogoVod(rawName: String): String? {
        val apiKey = TmdbConfig.API_KEY
        val year = regexAno.find(rawName)?.value
        val cleanName = TituloCleaner.limparParaBusca(rawName)
        return try {
            var url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey" +
                    "&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR&include_adult=false"
            if (year != null) url += "&year=$year"
            val results = JSONObject(lerUrl(url)).getJSONArray("results")
            if (results.length() == 0) return ""
            val id = results.getJSONObject(0).getString("id")
            val logos = JSONObject(
                lerUrl("https://api.themoviedb.org/3/movie/$id/images?api_key=$apiKey&include_image_language=pt,en,null")
            ).getJSONArray("logos")
            if (logos.length() == 0) return ""
            var path: String? = null
            for (i in 0 until logos.length()) {
                if (logos.getJSONObject(i).optString("iso_639_1") == "pt") {
                    path = logos.getJSONObject(i).getString("file_path"); break
                }
            }
            if (path == null) path = logos.getJSONObject(0).getString("file_path")
            "https://cdn.vltvplay.tech/t/p/w500$path"
        } catch (e: Exception) { null }
    }

    /**
     * Busca categorias da REDE em background.
     * Salva no banco para a próxima abertura ser instantânea.
     */
    private fun carregarCategoriasRede() {
        XtreamApi.service.getVodCategories(username, password)
            .enqueue(object : retrofit2.Callback<ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val lista = mutableListOf<LiveCategory>()
                        val gson = Gson()
                        if (rawJson.trim().startsWith("[")) {
                            val type = object : TypeToken<List<LiveCategory>>() {}.type
                            lista.addAll(gson.fromJson(rawJson, type))
                        } else if (rawJson.trim().startsWith("{")) {
                            val obj = JSONObject(rawJson)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                            }
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val entities = lista.map {
                                    CategoryEntity(it.category_id, it.category_name, "vod")
                                }
                                database.streamDao().deleteCategoriesByType("vod")
                                database.streamDao().insertCategories(entities)
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        val cats = mutableListOf<LiveCategory>()
                        cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                        cats.addAll(lista)

                        // Só reaplica se o adapter ainda não tem categorias
                        if (categoryAdapter == null) {
                            aplicarCategorias(cats)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        if (isFinishing || isDestroyed) return

        val categorias = filtrarCategoriasAdultas(categoriasOriginais)

        val catSalvaId = ultimaCategoriaId
        val indexInicial = if (catSalvaId != null) {
            val idx = categorias.indexOfFirst { it.id == catSalvaId }
            if (idx >= 0) idx else if (categorias.size > 1) 1 else 0
        } else {
            if (categorias.size > 1) 1 else 0
        }

        categoryAdapter = VodCategoryAdapter(categorias, indexInicial) { categoria ->
            salvarUltimaCategoria(categoria)
            if (categoria.id == "FAV") carregarFilmesFavoritos()
            else carregarFilmes(categoria)
        }
        rvCategories.adapter = categoryAdapter

        val categoriaAlvo = categorias.getOrNull(indexInicial)
            ?.takeIf { it.id != "FAV" }
            ?: categorias.firstOrNull { it.id != "FAV" }

        if (categoriaAlvo != null) {
            tvCategoryTitle.text = categoriaAlvo.name
            if (categoriaAlvo.id == categoriaAtualId) {
                atualizarEmBackground(categoriaAlvo)
            } else {
                carregarFilmes(categoriaAlvo)
            }
        }
    }

    private fun salvarUltimaCategoria(categoria: LiveCategory) {
        ultimaCategoriaId   = categoria.id
        ultimaCategoriaNome = categoria.name
        getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE).edit()
            .putString("ultima_cat_id", categoria.id)
            .putString("ultima_cat_nome", categoria.name)
            .apply()
    }

    // Atualização em segundo plano. Só roda se a categoria não estiver
    // "fresca" (6h) e SÓ DEPOIS que o ContentRepository estiver pronto
    // (sem ele não dá pra saber o que já existe, e a gravação poderia
    // apagar logos/selos já calculados).
    private fun atualizarEmBackground(categoria: LiveCategory) {
        if (moviesCache.containsKey(categoria.id)) return
        if (categoriaEstaFresca(categoria.id)) return
        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (!isFinishing && !isDestroyed && categoriaAtualId == categoria.id) {
                    atualizarEmBackground(categoria)
                }
            }
            return
        }
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        // rolarTopo = false: a atualização de fundo não pode
                        // jogar o usuário de volta pro topo enquanto ele navega.
                        moviesAdapter?.submitList(filmes, rolarTopo = false)
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {}
            })
    }

    // ✅ NOVO: lê SÓ a categoria pedida direto do Room (consulta local,
    // milissegundos). Usada quando o ContentRepository ainda não terminou
    // de carregar o catálogo inteiro — antes a tela ficava vazia
    // esperando ele.
    private suspend fun lerVodsDoRoom(categoryId: String): List<VodStream> =
        paraVodStreams(database.streamDao().getVodsByCategory(categoryId))

    private fun carregarFilmes(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        categoriaAtualId = categoria.id
        salvarUltimaCategoria(categoria)

        // 1. Cache de memória da sessão — instantâneo
        moviesCache[categoria.id]?.let {
            moviesAdapter?.submitList(it); return
        }

        // 2. ContentRepository — O(1), instantâneo (quando já está pronto)
        if (ContentRepository.pronto) {
            val emRepositorio = ContentRepository.getVodsByCategory(categoria.id)
            if (emRepositorio.isNotEmpty()) {
                moviesAdapter?.submitList(paraVodStreams(emRepositorio))
                atualizarEmBackground(categoria)
                return
            }
            carregarFilmesDaRede(categoria)
            return
        }

        // 3. ✅ CORRIGIDO: repositório ainda carregando → lê só esta
        // categoria direto do Room (rápido) em vez de esperar o catálogo
        // inteiro. Se a leitura falhar por qualquer motivo, cai no
        // comportamento antigo (espera o repositório).
        lifecycleScope.launch(Dispatchers.IO) {
            val locais = try { lerVodsDoRoom(categoria.id) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed || categoriaAtualId != categoria.id) return@withContext
                when {
                    locais == null -> ContentRepository.aoFicarPronto {
                        if (!isFinishing && !isDestroyed && categoriaAtualId == categoria.id) {
                            carregarFilmes(categoria)
                        }
                    }
                    locais.isNotEmpty() -> {
                        moviesAdapter?.submitList(locais)
                        atualizarEmBackground(categoria)
                    }
                    else -> carregarFilmesDaRede(categoria)
                }
            }
        }
    }

    // Sem dados locais — primeira instalação
    private fun carregarFilmesDaRede(categoria: LiveCategory) {
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        moviesAdapter?.submitList(filmes)
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    // ✅ CORRIGIDO: antes recriava TODOS os filmes só com os campos básicos
    // e gravava tudo de novo, o que podia apagar logo/selos/TMDB já
    // calculados (tanto no banco quanto na memória, afetando a Home).
    // Agora:
    //  - filme que já existe e não mudou (nome, capa, extensão) mantém o
    //    objeto original COMPLETO (com tudo que o TMDB já calculou);
    //  - só filmes novos ou alterados vão pro banco.
    private fun salvarNoBancoERepositorio(categoryId: String, filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val existentes = ContentRepository.getVodsByCategory(categoryId).associateBy { it.stream_id }
                // Só monta o mapa geral se aparecer filme fora desta categoria
                // (ex.: mudou de categoria no provedor).
                val globais by lazy { ContentRepository.vods.associateBy { it.stream_id } }
                val agoraSeg = System.currentTimeMillis() / 1000
                val mesclada = ArrayList<VodEntity>(filmes.size)
                val paraGravar = ArrayList<VodEntity>()

                for (f in filmes) {
                    val antigo = existentes[f.stream_id] ?: globais[f.stream_id]
                    if (antigo != null &&
                        antigo.category_id == categoryId &&
                        antigo.name == f.name &&
                        antigo.stream_icon == f.stream_icon &&
                        antigo.container_extension == f.container_extension
                    ) {
                        mesclada.add(antigo)
                    } else {
                        // Como o insert do DAO é REPLACE, a linha gravada
                        // precisa estar COMPLETA: se o filme já existia, usa
                        // copy() do original (mantém logo, TMDB, Top10, etc.)
                        // trocando só o que veio novo do provedor.
                        val nova = if (antigo != null) {
                            antigo.copy(
                                name = f.name, title = f.title, stream_icon = f.stream_icon,
                                container_extension = f.container_extension, rating = f.rating,
                                category_id = categoryId
                            )
                        } else {
                            VodEntity(
                                f.stream_id, f.name, f.title, f.stream_icon,
                                f.container_extension, f.rating, categoryId,
                                if (f.added > 0) f.added else agoraSeg
                            )
                        }
                        mesclada.add(nova)
                        paraGravar.add(nova)
                    }
                }

                if (paraGravar.isEmpty() && mesclada.size == existentes.size) return@launch

                // NonCancellable: se o usuário sair da tela no meio, a
                // gravação termina inteira em vez de ficar pela metade.
                withContext(NonCancellable) {
                    if (paraGravar.isNotEmpty()) database.streamDao().insertVodStreams(paraGravar)
                    ContentRepository.atualizarCategoriaVod(categoryId, mesclada)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val p = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        return p.getStringSet("${currentProfile}_favoritos", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun carregarFilmesFavoritos() {
        categoriaAtualId = "FAV"
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        if (favIds.isEmpty()) { moviesAdapter?.submitList(emptyList()); return }
        val listaNoCache = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) {
            moviesAdapter?.submitList(listaNoCache); return
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = "0")
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val todos = response.body()!!
                    moviesCache["ALL_FOR_FAV"] = todos
                    val favs = todos.filter { favIds.contains(it.id) }
                    if (categoriaAtualId == "FAV") {
                        moviesAdapter?.submitList(favs)
                    }
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (categoriaAtualId == "FAV") moviesAdapter?.submitList(listaNoCache)
                }
            })
    }

    private fun abrirDetalhes(filme: VodStream) {
        startActivity(Intent(this, DetailsActivity::class.java).apply {
            putExtra("stream_id", filme.id)
            putExtra("name", filme.name)
            putExtra("icon", filme.icon)
            putExtra("rating", filme.rating ?: "0.0")
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("PROFILE_ICON", currentProfileIcon)
        })
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download)
                Toast.makeText(this, "Baixando: ${filme.name}", Toast.LENGTH_LONG).show()
            true
        }
        popup.show()
    }

    // =========================================================================
    // ADAPTER DE CATEGORIAS — chips estilo pill
    // =========================================================================
    inner class VodCategoryAdapter(
        private val list: List<LiveCategory>,
        initialSelectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {

        private var selectedPos = initialSelectedPos

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            val chip = h.tvName
            chip.text = item.name
            val isSel = selectedPos == p

            fun aplicarEstiloBase() {
                if (isSel) {
                    chip.setBackgroundResource(R.drawable.bg_chip_selected)
                    chip.setTextColor(Color.WHITE)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                    chip.setTextColor(chip.context.getColor(R.color.gray_text))
                }
            }
            aplicarEstiloBase()

            val isTV = this@VodActivity.isTelevisionDevice()
            h.itemView.isFocusable = isTV
            h.itemView.isClickable = true
            if (isTV) {
                h.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        chip.setTextColor(Color.WHITE)
                        chip.setBackgroundResource(R.drawable.bg_chip_focused)
                        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        aplicarEstiloBase()
                    }
                }
            }
            h.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = h.adapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    // =========================================================================
    // ADAPTER DE FILMES — filtro + ordenação + DiffUtil em thread de fundo
    // =========================================================================
    inner class VodAdapter(
        private val onItemClick: (VodStream) -> Unit,
        private val onDownloadClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<VodAdapter.VH>() {

        // Lista imutável, só trocada na thread principal.
        private var items: List<VodStream> = emptyList()

        // Cada submitList ganha um número; só o mais recente é aplicado
        // (resposta atrasada de categoria anterior é descartada).
        private var versaoSubmit = 0

        // ✅ CORRIGIDO: o trabalho pesado (filtro adulto, ordenar por ano,
        // calcular o diff) agora roda em Dispatchers.Default. Na thread
        // principal só entra a aplicação do resultado. Antes tudo isso
        // acontecia na main thread e travava a tela.
        // rolarTopo = false é usado nas atualizações de fundo.
        fun submitList(novaLista: List<VodStream>, rolarTopo: Boolean = true) {
            val versao = ++versaoSubmit
            val antigos = items
            lifecycleScope.launch(Dispatchers.Default) {
                val ordenada = ordenarPorAno(filtrarFilmesAdultos(novaLista))
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = antigos.size
                    override fun getNewListSize() = ordenada.size
                    override fun areItemsTheSame(o: Int, n: Int) = antigos[o].id == ordenada[n].id
                    override fun areContentsTheSame(o: Int, n: Int) =
                        antigos[o].name == ordenada[n].name && antigos[o].icon == ordenada[n].icon
                }, false)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed || versao != versaoSubmit) return@withContext
                    items = ordenada
                    diff.dispatchUpdatesTo(this@VodAdapter)
                    if (rolarTopo) rvMovies.scrollToPosition(0)
                    preLoadImages(ordenada)
                }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView     = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView   = v.findViewById(R.id.imgLogo)
            var job: Job? = null
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_vod, p, false))

        // Item saiu da tela → cancela a busca de logo que ainda não terminou.
        override fun onViewRecycled(holder: VH) {
            holder.job?.cancel()
            holder.job = null
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(h: VH, p: Int) {
            h.job?.cancel()
            val item = items[p]

            h.tvName.text = item.name
            h.tvName.visibility  = View.VISIBLE
            h.imgLogo.setImageDrawable(null)
            h.imgLogo.visibility = View.INVISIBLE

            Glide.with(h.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(240, 360)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .centerCrop()
                .into(h.imgPoster)

            val memCached = logoMemoryCache[item.name]
            if (memCached != null) {
                h.tvName.visibility  = View.GONE
                h.imgLogo.visibility = View.VISIBLE
                Glide.with(h.itemView.context).load(memCached)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
            } else {
                val diskCached = gridCachePrefs.getString("logo_${item.name}", null)
                if (diskCached != null) {
                    logoMemoryCache[item.name] = diskCached
                    h.tvName.visibility  = View.GONE
                    h.imgLogo.visibility = View.VISIBLE
                    Glide.with(h.itemView.context).load(diskCached)
                        .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
                } else {
                    // ✅ Só busca se o TMDB não disse "sem logo" nos últimos 7 dias.
                    val semLogoEm = gridCachePrefs.getLong("semlogo_${item.name}", 0L)
                    val deveBuscar = semLogoEm == 0L ||
                        System.currentTimeMillis() - semLogoEm > SEM_LOGO_TTL_MS

                    if (deveBuscar) {
                        h.job = lifecycleScope.launch(Dispatchers.IO) {
                            // Espera um instante: se o item sair da tela
                            // (rolagem rápida), o job é cancelado aqui e
                            // nenhuma chamada de rede é feita.
                            delay(250)
                            // No máximo 3 buscas simultâneas.
                            val url = logoSemaphore.withPermit { searchTmdbLogoVod(item.name) }
                            when {
                                url == null -> { /* erro de rede: tenta de novo outra hora */ }
                                url.isEmpty() -> gridCachePrefs.edit()
                                    .putLong("semlogo_${item.name}", System.currentTimeMillis())
                                    .apply()
                                else -> {
                                    logoMemoryCache[item.name] = url
                                    gridCachePrefs.edit().putString("logo_${item.name}", url).apply()
                                    withContext(Dispatchers.Main) {
                                        if (isFinishing || isDestroyed) return@withContext
                                        // Confere se esse mesmo item ainda está nessa posição.
                                        val pos = h.adapterPosition
                                        if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.name == item.name) {
                                            h.tvName.visibility  = View.GONE
                                            h.imgLogo.visibility = View.VISIBLE
                                            Glide.with(h.itemView.context).load(url)
                                                .override(200, 110)
                                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                                .dontAnimate().into(h.imgLogo)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            h.itemView.isFocusable = this@VodActivity.isTelevisionDevice()
            h.itemView.isClickable = true
            h.itemView.setOnClickListener { onItemClick(item) }

            if (this@VodActivity.isTelevisionDevice()) {
                h.itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(16f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.VISIBLE
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.INVISIBLE
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
