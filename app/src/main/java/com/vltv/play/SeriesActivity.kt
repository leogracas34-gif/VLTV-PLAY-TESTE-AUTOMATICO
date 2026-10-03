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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.CategoryEntity
import com.vltv.play.data.SeriesEntity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeriesActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var bottomNavigation: BottomNavigationView? = null

    private var username = ""
    private var password = ""
    private lateinit var seriesCachePrefs: SharedPreferences

    // Controle de "última sincronização" por categoria (sobrevive entre
    // aberturas da Activity/app).
    private lateinit var syncPrefs: SharedPreferences
    private val SYNC_STALE_MS = 6 * 60 * 60 * 1000L // 6 horas

    // ✅ NOVO: depois que o TMDB responde "essa série não tem logo", o app
    // lembra por 7 dias e não pergunta de novo a cada rolagem.
    private val SEM_LOGO_TTL_MS = 7L * 24 * 60 * 60 * 1000

    // ✅ NOVO: no máximo 3 buscas de logo ao mesmo tempo.
    private val logoSemaphore = Semaphore(3)

    // ✅ NOVO: Regex criada UMA vez (antes era recriada a cada chamada).
    private val regexAno = Regex("\\b(19|20)\\d{2}\\b")

    private val seriesCache = mutableMapOf<String, List<SeriesStream>>()
    private val logoMemoryCache = mutableMapOf<String, String>()

    private var categoryAdapter: SeriesCategoryAdapter? = null

    // Adapter único — nunca recriado, atualizado via DiffUtil
    private var seriesAdapter: SeriesAdapter? = null

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var ultimaCategoriaId: String? = null
    private var ultimaCategoriaNome: String? = null

    // Guard de race condition
    private var categoriaAtualId: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    // Detecção de TV centralizada em DeviceUtils.kt (context.isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    // Filtro central de conteúdo adulto para SÉRIES. Agora é chamado
    // DENTRO do submitList do adapter (thread de fundo).
    private fun filtrarSeriesAdultas(lista: List<SeriesStream>): List<SeriesStream> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    // Filtro central de conteúdo adulto para CATEGORIAS
    private fun filtrarCategoriasAdultas(lista: List<LiveCategory>): List<LiveCategory> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    // Extrai o ano (19xx ou 20xx) do nome da série. Sem ano → 0 (vai pro final).
    private fun extrairAnoSerie(nome: String?): Int {
        if (nome.isNullOrEmpty()) return 0
        return regexAno.find(nome)?.value?.toIntOrNull() ?: 0
    }

    // ✅ NOVO: calcula o ano UMA vez por série e depois ordena (antes o
    // sortedByDescending chamava a Regex várias vezes por item, na thread
    // principal). Ordem estável.
    private fun ordenarPorAno(lista: List<SeriesStream>): List<SeriesStream> =
        lista.map { extrairAnoSerie(it.name) to it }
            .sortedByDescending { it.first }
            .map { it.second }

    private fun categoriaEstaFresca(categoriaId: String): Boolean {
        val ultimaSync = syncPrefs.getLong("sync_$categoriaId", 0L)
        return (System.currentTimeMillis() - ultimaSync) < SYNC_STALE_MS
    }

    private fun marcarCategoriaSincronizada(categoriaId: String) {
        syncPrefs.edit().putLong("sync_$categoriaId", System.currentTimeMillis()).apply()
    }

    private fun paraStreams(lista: List<SeriesEntity>): List<SeriesStream> =
        lista.map {
            SeriesStream(
                it.series_id, it.name, it.cover, it.rating, it.last_modified,
                it.tmdb_release_date, it.is_nova_temporada == 1, it.is_novo_episodio == 1,
                it.tmdb_proxima_temporada_data
            )
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
        rvSeries        = findViewById(R.id.rvChannels)
        progressBar     = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        seriesCachePrefs = getSharedPreferences("vltv_series_cache", Context.MODE_PRIVATE)
        syncPrefs         = getSharedPreferences("vltv_series_sync", Context.MODE_PRIVATE)

        setupBottomNavigation()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        findViewById<View>(R.id.etSearchContent)?.apply {
            isFocusableInTouchMode = false
            setOnClickListener {
                startActivity(Intent(this@SeriesActivity, SearchActivity::class.java).apply {
                    putExtra("initial_query", "")
                    putExtra("PROFILE_NAME", currentProfile)
                    putExtra("tipo_pesquisa", "series")
                })
            }
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        if (this.isTelevisionDevice()) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvSeries.layoutManager = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvSeries.layoutManager = GridLayoutManager(this, 3)
            bottomNavigation?.visibility = View.VISIBLE
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(60)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER

        if (this.isTelevisionDevice()) {
            rvCategories.isFocusable = true
            rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rvSeries.isFocusable = true
            rvSeries.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            rvCategories.isFocusable = false
            rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rvSeries.isFocusable = false
            rvSeries.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        rvSeries.setHasFixedSize(true)
        rvSeries.setItemViewCacheSize(100)

        // Adapter criado UMA vez — nunca recriado
        seriesAdapter = SeriesAdapter { abrirDetalhesSerie(it) }
        rvSeries.adapter = seriesAdapter

        // Última categoria salva
        val catPrefs = getSharedPreferences("vltv_series_prefs", Context.MODE_PRIVATE)
        ultimaCategoriaId   = catPrefs.getString("ultima_cat_id", null)
        ultimaCategoriaNome = catPrefs.getString("ultima_cat_nome", null)

        // ── CARREGAMENTO INSTANTÂNEO DE SÉRIES (quando o repositório já está pronto) ──
        val catId = ultimaCategoriaId
        if (catId != null) {
            val seriesEmMemoria = ContentRepository.getSeriesByCategory(catId)
            if (seriesEmMemoria.isNotEmpty()) {
                categoriaAtualId = catId
                if (ultimaCategoriaNome != null) tvCategoryTitle.text = ultimaCategoriaNome
                // ✅ CORRIGIDO: agora monta a série COMPLETA (selos/datas do
                // TMDB) e aplica o MESMO filtro de recentes das outras
                // cargas — antes aparecia sem o filtro de 90 dias aqui e
                // com o filtro depois, e a lista "mudava sozinha".
                seriesAdapter?.submitList(paraStreams(seriesEmMemoria), aplicarRecentes = true)
            }
        }

        // ── CARREGAMENTO INSTANTÂNEO DE CATEGORIAS ───────────────────────────
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categoriasSalvas = database.streamDao().getCategoriesByType("series")
                if (categoriasSalvas.isNotEmpty()) {
                    val cats = mutableListOf<LiveCategory>()
                    cats.add(LiveCategory(category_id = "FAV_SERIES", category_name = "FAVORITOS"))
                    cats.addAll(categoriasSalvas.map {
                        LiveCategory(category_id = it.category_id, category_name = it.category_name)
                    })
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) aplicarCategorias(cats)
                    }
                }
                // Sempre busca da rede em background
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
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_novidades -> {
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_profile -> {
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
    private fun preLoadImages(series: List<SeriesStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            series.take(30).forEach { s ->
                val url = s.icon ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Glide.with(this@SeriesActivity)
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
    // timeout nenhum).
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
    //  - "" (vazio) se o TMDB respondeu e a série NÃO tem logo (vira cache negativo);
    //  - null se deu erro de rede (não guarda nada, tenta de novo depois).
    private suspend fun searchTmdbLogoSeries(rawName: String): String? {
        val apiKey = TmdbConfig.API_KEY
        val cleanName = TituloCleaner.limparParaBusca(rawName)
        return try {
            val query = URLEncoder.encode(cleanName, "UTF-8")
            val searchJson = lerUrl(
                "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$query&language=pt-BR&region=BR"
            )
            val results = JSONObject(searchJson).getJSONArray("results")
            if (results.length() == 0) return ""
            var best = results.getJSONObject(0)
            for (j in 0 until results.length()) {
                val obj = results.getJSONObject(j)
                if (obj.optString("name","").equals(cleanName, ignoreCase = true)) { best = obj; break }
            }
            val id = best.getString("id")
            val imagesJson = lerUrl(
                "https://api.themoviedb.org/3/tv/$id/images?api_key=$apiKey&include_image_language=pt,en,null"
            )
            val logos = JSONObject(imagesJson).getJSONArray("logos")
            if (logos.length() == 0) return ""
            var path = ""
            for (i in 0 until logos.length()) {
                val lg = logos.getJSONObject(i)
                if (lg.optString("iso_639_1") == "pt") { path = lg.getString("file_path"); break }
            }
            if (path.isEmpty()) path = logos.getJSONObject(0).getString("file_path")
            "https://cdn.vltvplay.tech/t/p/w500$path"
        } catch (e: Exception) { null }
    }

    private fun salvarUltimaCategoria(categoria: LiveCategory) {
        ultimaCategoriaId   = categoria.id
        ultimaCategoriaNome = categoria.name
        getSharedPreferences("vltv_series_prefs", Context.MODE_PRIVATE).edit()
            .putString("ultima_cat_id", categoria.id)
            .putString("ultima_cat_nome", categoria.name)
            .apply()
    }

    /**
     * Busca categorias da REDE em background.
     * Salva no banco para a próxima abertura ser instantânea.
     */
    private fun carregarCategoriasRede() {
        XtreamApi.service.getSeriesCategories(username, password)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val lista = mutableListOf<LiveCategory>()
                        val gson = Gson()
                        if (rawJson.trim().startsWith("[")) {
                            val type = object : TypeToken<List<LiveCategory>>() {}.type
                            lista.addAll(gson.fromJson(rawJson, type))
                        } else if (rawJson.trim().startsWith("{")) {
                            val obj = JSONObject(rawJson); val keys = obj.keys()
                            while (keys.hasNext()) {
                                lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                            }
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val entities = lista.map {
                                    CategoryEntity(it.category_id, it.category_name, "series")
                                }
                                database.streamDao().deleteCategoriesByType("series")
                                database.streamDao().insertCategories(entities)
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        val cats = mutableListOf<LiveCategory>()
                        cats.add(LiveCategory(category_id = "FAV_SERIES", category_name = "FAVORITOS"))
                        cats.addAll(lista)

                        // Só reaplica se o banco estava vazio (adapter ainda não criado)
                        if (categoryAdapter == null) {
                            aplicarCategorias(cats)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        if (isFinishing || isDestroyed) return

        val categorias = filtrarCategoriasAdultas(categoriasOriginais)
        if (categorias.isEmpty()) return

        val catSalvaId = ultimaCategoriaId
        val indexInicial = if (catSalvaId != null) {
            val idx = categorias.indexOfFirst { it.id == catSalvaId }
            if (idx >= 0) idx else if (categorias.size > 1) 1 else 0
        } else {
            if (categorias.size > 1) 1 else 0
        }

        categoryAdapter = SeriesCategoryAdapter(categorias, indexInicial) { categoria ->
            salvarUltimaCategoria(categoria)
            if (categoria.id == "FAV_SERIES") carregarSeriesFavoritas()
            else carregarSeries(categoria)
        }
        rvCategories.adapter = categoryAdapter

        val categoriaAlvo = categorias.getOrNull(indexInicial)
            ?.takeIf { it.id != "FAV_SERIES" }
            ?: categorias.firstOrNull { it.id != "FAV_SERIES" }

        if (categoriaAlvo != null) {
            tvCategoryTitle.text = categoriaAlvo.name
            if (categoriaAlvo.id == categoriaAtualId) {
                atualizarEmBackground(categoriaAlvo)
            } else {
                carregarSeries(categoriaAlvo)
            }
        }
    }

    // Atualização em segundo plano. Só roda se a categoria não estiver
    // "fresca" (6h) e SÓ DEPOIS que o ContentRepository estiver pronto
    // (sem ele não dá pra saber o que já existe, e a gravação poderia
    // apagar selos/dados do TMDB já calculados).
    private fun atualizarEmBackground(categoria: LiveCategory) {
        if (seriesCache.containsKey(categoria.id)) return
        if (categoriaEstaFresca(categoria.id)) return
        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (!isFinishing && !isDestroyed && categoriaAtualId == categoria.id) {
                    atualizarEmBackground(categoria)
                }
            }
            return
        }
        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    if (!response.isSuccessful || response.body() == null) return
                    val series = response.body()!!
                    seriesCache[categoria.id] = series
                    // ✅ Aqui NÃO exibe a lista crua da rede (ela não traz os
                    // selos/datas do TMDB e o filtro de recentes esconderia
                    // séries que estavam aparecendo). Quem exibe é o
                    // salvarNoBancoERepositorio, já com a lista mesclada
                    // (reexibir = true) e sem rolar a tela pro topo.
                    salvarNoBancoERepositorio(categoria.id, series, reexibir = true)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
            })
    }

    // ✅ NOVO: lê SÓ a categoria pedida direto do Room (consulta local,
    // milissegundos). Usada quando o ContentRepository ainda não terminou
    // de carregar o catálogo inteiro — antes a tela ficava vazia
    // esperando ele.
    private suspend fun lerSeriesDoRoom(categoryId: String): List<SeriesStream> =
        paraStreams(database.streamDao().getSeriesByCategory(categoryId))

    private fun carregarSeries(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        categoriaAtualId = categoria.id
        salvarUltimaCategoria(categoria)

        // 1. Cache de memória da sessão — instantâneo
        seriesCache[categoria.id]?.let {
            seriesAdapter?.submitList(it, aplicarRecentes = true); return
        }

        // 2. ContentRepository — O(1), instantâneo (quando já está pronto)
        if (ContentRepository.pronto) {
            val emRepositorio = ContentRepository.getSeriesByCategory(categoria.id)
            if (emRepositorio.isNotEmpty()) {
                seriesAdapter?.submitList(paraStreams(emRepositorio), aplicarRecentes = true)
                atualizarEmBackground(categoria)
                return
            }
            carregarSeriesDaRede(categoria)
            return
        }

        // 3. ✅ CORRIGIDO: repositório ainda carregando → lê só esta
        // categoria direto do Room (rápido) em vez de esperar o catálogo
        // inteiro. Se a leitura falhar por qualquer motivo, cai no
        // comportamento antigo (espera o repositório).
        lifecycleScope.launch(Dispatchers.IO) {
            val locais = try { lerSeriesDoRoom(categoria.id) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed || categoriaAtualId != categoria.id) return@withContext
                when {
                    locais == null -> ContentRepository.aoFicarPronto {
                        if (!isFinishing && !isDestroyed && categoriaAtualId == categoria.id) {
                            carregarSeries(categoria)
                        }
                    }
                    locais.isNotEmpty() -> {
                        seriesAdapter?.submitList(locais, aplicarRecentes = true)
                        atualizarEmBackground(categoria)
                    }
                    else -> carregarSeriesDaRede(categoria)
                }
            }
        }
    }

    // Sem dados locais — primeira instalação
    private fun carregarSeriesDaRede(categoria: LiveCategory) {
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val series = response.body()!!
                    seriesCache[categoria.id] = series
                    if (categoriaAtualId == categoria.id) {
                        seriesAdapter?.submitList(series, aplicarRecentes = true)
                    }
                    salvarNoBancoERepositorio(categoria.id, series, reexibir = false)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    // "last_modified" é a data que o SEU provedor atualizou o arquivo, não a
    // data real de lançamento — então a série é considerada "recente" quando
    // qualquer um for verdade: (a) estreia real (TMDB) nos últimos 3 meses,
    // (b) selo de Nova Temporada/Novo Episódio ativo, (c) próxima temporada
    // anunciada. Só cai pro "last_modified" cru quando nada disso existe.
    private fun paraEpocaSegundos(valor: Long): Long =
        if (valor > 9_999_999_999L) valor / 1000 else valor

    // ✅ CORRIGIDO: o SimpleDateFormat é criado UMA vez por chamada (antes
    // era criado de novo para cada série da lista).
    private fun filtrarRecentes(lista: List<SeriesStream>): List<SeriesStream> {
        val limiteMs = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val hoje = fmt.format(Date())
        return lista.filter { s ->
            val dataTmdb = s.tmdb_release_date?.let {
                try { fmt.parse(it)?.time } catch (e: Exception) { null }
            }
            when {
                s.is_nova_temporada || s.is_novo_episodio -> true
                !s.tmdb_proxima_temporada_data.isNullOrEmpty() && s.tmdb_proxima_temporada_data > hoje -> true
                dataTmdb != null -> dataTmdb >= limiteMs
                s.last_modified == 0L -> true
                else -> paraEpocaSegundos(s.last_modified) * 1000 >= limiteMs
            }
        }
    }

    // ✅ CORRIGIDO: antes recriava TODAS as séries só com os campos básicos
    // e gravava tudo de novo, o que podia apagar selos/datas do TMDB já
    // calculados (no banco e na memória, afetando a Home). Agora:
    //  - série que já existe e não mudou (nome, capa) mantém o objeto
    //    original COMPLETO (com tudo que o TMDB já calculou);
    //  - só séries novas ou alteradas vão pro banco.
    // reexibir = true: depois de mesclar, atualiza a tela com a lista
    // mesclada (sem rolar pro topo).
    private fun salvarNoBancoERepositorio(categoryId: String, series: List<SeriesStream>, reexibir: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val existentes = ContentRepository.getSeriesByCategory(categoryId).associateBy { it.series_id }
                // Só monta o mapa geral se aparecer série fora desta categoria
                // (ex.: mudou de categoria no provedor).
                val globais by lazy { ContentRepository.series.associateBy { it.series_id } }
                val agoraSeg = System.currentTimeMillis() / 1000
                val mesclada = ArrayList<SeriesEntity>(series.size)
                val paraGravar = ArrayList<SeriesEntity>()

                for (s in series) {
                    val antigo = existentes[s.series_id] ?: globais[s.series_id]
                    val novoLastModified = if (s.last_modified > 0) s.last_modified else agoraSeg
                    if (antigo != null &&
                        antigo.category_id == categoryId &&
                        antigo.name == s.name &&
                        antigo.cover == s.cover &&
                        (s.last_modified <= 0 || antigo.last_modified == s.last_modified)
                    ) {
                        mesclada.add(antigo)
                    } else {
                        // Como o insert do DAO é REPLACE, a linha gravada
                        // precisa estar COMPLETA: se a série já existia, usa
                        // copy() do original (mantém logo, selos de Nova
                        // Temporada/Episódio, TMDB, Top10, etc.) trocando só
                        // o que veio novo do provedor.
                        val nova = if (antigo != null) {
                            antigo.copy(
                                name = s.name, cover = s.cover, rating = s.rating,
                                category_id = categoryId,
                                last_modified = if (s.last_modified > 0) s.last_modified else antigo.last_modified
                            )
                        } else {
                            SeriesEntity(
                                s.series_id, s.name, s.cover, s.rating, categoryId, novoLastModified
                            )
                        }
                        mesclada.add(nova)
                        paraGravar.add(nova)
                    }
                }

                val houveMudanca = paraGravar.isNotEmpty() || mesclada.size != existentes.size
                if (houveMudanca) {
                    // NonCancellable: se o usuário sair da tela no meio, a
                    // gravação termina inteira em vez de ficar pela metade.
                    withContext(NonCancellable) {
                        if (paraGravar.isNotEmpty()) database.streamDao().insertSeriesStreams(paraGravar)
                        ContentRepository.atualizarCategoriaSeries(categoryId, mesclada)
                    }
                }

                if (reexibir) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && categoriaAtualId == categoryId) {
                            seriesAdapter?.submitList(paraStreams(mesclada), aplicarRecentes = true, rolarTopo = false)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarSeriesFavoritas() {
        categoriaAtualId = "FAV_SERIES"
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavSeries(this)
        if (favIds.isEmpty()) { seriesAdapter?.submitList(emptyList(), aplicarRecentes = false); return }
        val listaNoCache = seriesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) {
            seriesAdapter?.submitList(listaNoCache, aplicarRecentes = false); return
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getSeries(username, password, categoryId = "0")
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val todas = response.body()!!
                    seriesCache["ALL_FOR_FAV"] = todas
                    val favs = todas.filter { favIds.contains(it.id) }
                    if (categoriaAtualId == "FAV_SERIES") {
                        seriesAdapter?.submitList(favs, aplicarRecentes = false)
                    }
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (categoriaAtualId == "FAV_SERIES") seriesAdapter?.submitList(listaNoCache, aplicarRecentes = false)
                }
            })
    }

    private fun abrirDetalhesSerie(serie: SeriesStream) {
        startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
            putExtra("series_id", serie.id)
            putExtra("name", serie.name)
            putExtra("icon", serie.icon)
            putExtra("rating", serie.rating ?: "0.0")
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("PROFILE_ICON", currentProfileIcon)
        })
    }

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val p = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        return (p.getStringSet("${currentProfile}_fav_series", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    // =========================================================================
    // ADAPTER DE CATEGORIAS — chips estilo pill
    // =========================================================================
    inner class SeriesCategoryAdapter(
        private val list: List<LiveCategory>,
        private var selectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val chip = holder.tvName
            chip.text = item.name
            val isSel = selectedPos == position

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

            val isTV = this@SeriesActivity.isTelevisionDevice()
            holder.itemView.isFocusable = isTV
            holder.itemView.isClickable = true
            if (isTV) {
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
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
            holder.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = holder.adapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    // =========================================================================
    // ADAPTER DE SÉRIES — filtros + ordenação + DiffUtil em thread de fundo
    // =========================================================================
    inner class SeriesAdapter(
        private val onClick: (SeriesStream) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        // Lista imutável, só trocada na thread principal.
        private var items: List<SeriesStream> = emptyList()

        // Cada submitList ganha um número; só o mais recente é aplicado.
        private var versaoSubmit = 0

        // ✅ CORRIGIDO: o trabalho pesado (filtro adulto, filtro de recentes,
        // ordenar por ano, calcular o diff) agora roda em Dispatchers.Default.
        // Na thread principal só entra a aplicação do resultado.
        // aplicarRecentes = false nos favoritos (mostram tudo).
        // rolarTopo = false nas atualizações de fundo.
        fun submitList(
            novaLista: List<SeriesStream>,
            aplicarRecentes: Boolean,
            rolarTopo: Boolean = true
        ) {
            val versao = ++versaoSubmit
            val antigos = items
            lifecycleScope.launch(Dispatchers.Default) {
                var base = filtrarSeriesAdultas(novaLista)
                if (aplicarRecentes) base = filtrarRecentes(base)
                val ordenada = ordenarPorAno(base)
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
                    diff.dispatchUpdatesTo(this@SeriesAdapter)
                    if (rolarTopo) rvSeries.scrollToPosition(0)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))

        // Item saiu da tela → cancela a busca de logo que ainda não terminou.
        override fun onViewRecycled(holder: VH) {
            holder.job?.cancel()
            holder.job = null
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.job?.cancel()
            val item = items[position]

            holder.tvName.text = item.name
            holder.tvName.visibility = View.VISIBLE
            holder.imgLogo.setImageDrawable(null)
            holder.imgLogo.visibility = View.INVISIBLE
            holder.itemView.findViewById<View?>(R.id.imgDownload)?.visibility = View.GONE

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(240, 360)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .centerCrop()
                .into(holder.imgPoster)

            val memCached = logoMemoryCache[item.name]
            if (memCached != null) {
                holder.tvName.visibility = View.GONE
                holder.imgLogo.visibility = View.VISIBLE
                Glide.with(holder.itemView.context).load(memCached)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(holder.imgLogo)
            } else {
                val diskCached = seriesCachePrefs.getString("logo_${item.name}", null)
                if (diskCached != null) {
                    logoMemoryCache[item.name] = diskCached
                    holder.tvName.visibility = View.GONE
                    holder.imgLogo.visibility = View.VISIBLE
                    Glide.with(holder.itemView.context).load(diskCached)
                        .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(holder.imgLogo)
                } else {
                    // ✅ Só busca se o TMDB não disse "sem logo" nos últimos 7 dias.
                    val semLogoEm = seriesCachePrefs.getLong("semlogo_${item.name}", 0L)
                    val deveBuscar = semLogoEm == 0L ||
                        System.currentTimeMillis() - semLogoEm > SEM_LOGO_TTL_MS

                    if (deveBuscar) {
                        holder.job = lifecycleScope.launch(Dispatchers.IO) {
                            // Espera um instante: se o item sair da tela
                            // (rolagem rápida), o job é cancelado aqui e
                            // nenhuma chamada de rede é feita.
                            delay(250)
                            // No máximo 3 buscas simultâneas.
                            val url = logoSemaphore.withPermit { searchTmdbLogoSeries(item.name) }
                            when {
                                url == null -> { /* erro de rede: tenta de novo outra hora */ }
                                url.isEmpty() -> seriesCachePrefs.edit()
                                    .putLong("semlogo_${item.name}", System.currentTimeMillis())
                                    .apply()
                                else -> {
                                    logoMemoryCache[item.name] = url
                                    seriesCachePrefs.edit().putString("logo_${item.name}", url).apply()
                                    withContext(Dispatchers.Main) {
                                        if (isFinishing || isDestroyed) return@withContext
                                        // Confere se esse mesmo item ainda está nessa posição.
                                        val pos = holder.adapterPosition
                                        if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.name == item.name) {
                                            holder.tvName.visibility = View.GONE
                                            holder.imgLogo.visibility = View.VISIBLE
                                            Glide.with(holder.itemView.context).load(url)
                                                .override(200, 110)
                                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                                .dontAnimate().into(holder.imgLogo)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val isTV = holder.itemView.context.isTelevisionDevice()
            holder.itemView.isFocusable = isTV
            holder.itemView.isClickable = true
            if (isTV) {
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        holder.tvName.setTextColor(Color.YELLOW)
                        view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(160).start()
                        view.elevation = 20f
                        view.setBackgroundResource(R.drawable.bg_focus_neon)
                    } else {
                        holder.tvName.setTextColor(Color.WHITE)
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        view.elevation = 4f
                        view.setBackgroundResource(0)
                    }
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
