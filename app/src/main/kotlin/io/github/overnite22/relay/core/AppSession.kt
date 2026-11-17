package io.github.overnite22.relay.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.overnite22.relay.kit.ConnectionTest
import io.github.overnite22.relay.kit.Credential
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.HttpTransport
import io.github.overnite22.relay.kit.OkHttpTransport
import io.github.overnite22.relay.kit.RelayClient
import io.github.overnite22.relay.kit.ServerAccount
import io.github.overnite22.relay.kit.ServerInfo
import io.github.overnite22.relay.kit.SessionTicket
import io.github.overnite22.relay.kit.Sorting
import io.github.overnite22.relay.kit.Tree
import io.github.overnite22.relay.kit.entryInput
import io.github.overnite22.relay.kit.guac.GuacTransport
import io.github.overnite22.relay.kit.guac.RelayTunnelTransport
import io.github.overnite22.relay.kit.saveEntry
import io.github.overnite22.relay.sessions.SessionController
import io.github.overnite22.relay.sessions.SessionKeepAliveService
import io.github.overnite22.relay.sessions.SessionTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** État global : compte appairé, client API, inventaire, disponibilité et sessions ouvertes. */
class AppSession(
    val context: Context,
    private val store: AccountStore,
    val prefs: Prefs,
    private val transport: HttpTransport? = null,
) {
    enum class Phase { LOADING, UNPAIRED, PAIRED }

    val scope: CoroutineScope = MainScope()

    var phase by mutableStateOf(Phase.LOADING); private set
    var client by mutableStateOf<RelayClient?>(null); private set
    var account by mutableStateOf<ServerAccount?>(null); private set
    var info by mutableStateOf<ServerInfo?>(null); private set
    var tree by mutableStateOf(Tree.EMPTY); private set
    var status by mutableStateOf<Map<String, ConnectionTest>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null)
    var recentIds by mutableStateOf(prefs.recentIds); private set
    var credentials by mutableStateOf<List<Credential>>(emptyList()); private set

    /** Sessions ouvertes (elles restent connectées quand on les réduit). */
    val sessions = mutableStateListOf<SessionController>()
    /** Session affichée en plein écran (null = sessions réduites). */
    var presentedSessionId by mutableStateOf<String?>(null)
    /** Serveurs cochés pour la diffusion de commandes. */
    var commandTargets by mutableStateOf<Set<String>>(emptySet())
    /** Commande à reprendre dans l'éditeur (historique, menu d'un dossier). */
    var commandDraft by mutableStateOf<Pair<String, Boolean>?>(null)
    /** Onglet demandé par une action (ex. « Commande sur ce dossier »). */
    var requestedTab by mutableStateOf<String?>(null)

    /** État de l'onglet Commandes (conservé pendant la navigation). */
    val commands by lazy { io.github.overnite22.relay.ui.CommandsModel(this) }

    private var unlockedUntil = 0L

    /** Remplace le tunnel WebSocket (tests : flux Guacamole scripté). */
    var tunnelOverride: ((SessionTicket) -> GuacTransport)? = null

    fun restore() {
        if (phase != Phase.LOADING) return
        val saved = runCatching { store.load() }.getOrNull()
        if (saved != null) activate(saved) else phase = Phase.UNPAIRED
    }

    private fun makeClient(account: ServerAccount) = RelayClient(account, transport ?: OkHttpTransport())

    /** Enregistre l'appairage après vérification auprès du serveur (`/api/me`). */
    suspend fun pair(account: ServerAccount): ServerInfo {
        val c = makeClient(account)
        val me = c.me()
        store.save(account)
        activate(account, c, me)
        return me
    }

    fun unpair() {
        runCatching { store.delete() }
        for (s in sessions) s.close()
        sessions.clear()
        presentedSessionId = null
        client = null
        account = null
        info = null
        tree = Tree.EMPTY
        status = emptyMap()
        credentials = emptyList()
        unlockedUntil = 0
        errorMessage = null
        phase = Phase.UNPAIRED
        updateKeepAlive()
    }

    private fun activate(account: ServerAccount, c: RelayClient? = null, me: ServerInfo? = null) {
        this.account = account
        client = c ?: makeClient(account)
        info = me
        phase = Phase.PAIRED
    }

    /** Recharge l'inventaire et la disponibilité. */
    suspend fun refresh() {
        val c = client ?: return
        if (loading) return
        loading = true
        try {
            coroutineScope {
                val t = async { c.tree() }
                val s = async { runCatching { c.connectionStatus() }.getOrNull() }
                tree = t.await()
                s.await()?.let { status = it }
            }
            if (info == null) info = runCatching { c.me() }.getOrNull()
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            loading = false
        }
    }

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    suspend fun loadCredentials() {
        val c = client ?: return
        try {
            credentials = c.credentials().sortedWith(Sorting.byName { it.name })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    fun credential(id: String?): Credential? = id?.let { cid -> credentials.firstOrNull { it.id == cid } }

    val presentedSession: SessionController? get() = sessions.firstOrNull { it.uid == presentedSessionId }

    /** Ouvre une session, ou ramène au premier plan celle déjà ouverte vers la même cible. */
    fun open(target: SessionTarget) {
        sessions.firstOrNull { it.target.id == target.id && !it.isEnded }?.let {
            presentedSessionId = it.uid
            return
        }
        val controller = SessionController(target, this)
        sessions.add(controller)
        presentedSessionId = controller.uid
        updateKeepAlive()
    }

    /** Ouvre plusieurs entrées (espace de travail) et affiche la première. */
    fun openAll(entries: List<Entry>) {
        val targets = entries.filter { it.kind.opensNatively }.map { SessionTarget.ForEntry(it, false) }
        val first = targets.firstOrNull() ?: return
        for (t in targets.drop(1)) {
            if (sessions.none { it.target.id == t.id && !it.isEnded }) sessions.add(SessionController(t, this))
        }
        open(first)
    }

    fun closeSession(controller: SessionController) {
        controller.close()
        sessions.remove(controller)
        if (presentedSessionId == controller.uid) presentedSessionId = sessions.lastOrNull()?.uid
        updateKeepAlive()
    }

    fun minimizeSessions() {
        presentedSessionId = null
    }

    /** Notification « sessions ouvertes » : garde le processus et les tunnels actifs en arrière-plan. */
    fun updateKeepAlive() {
        SessionKeepAliveService.update(context, if (prefs.keepAlive) sessions.size else 0)
    }

    /** Vrai si les secrets peuvent être affichés sans nouvelle authentification (fenêtre de 60 s). */
    val secretsUnlocked: Boolean get() = System.currentTimeMillis() < unlockedUntil
    fun markUnlocked() {
        unlockedUntil = System.currentTimeMillis() + 60_000
    }
    fun lockSecrets() {
        unlockedUntil = 0
    }

    /** Copie d'une entrée (réglages compris), nommée « … (copie) ». */
    suspend fun duplicate(entry: Entry) {
        val c = client ?: return
        try {
            val input = c.entryInput(entry.id)
            c.saveEntry(input.copy(name = input.name + " (copie)", favorite = false))
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    /** Reteste le port des entrées (une fois par adresse), 6 à la fois ; progress(fait, total). */
    suspend fun retest(entries: List<Entry>, progress: (Int, Int) -> Unit) {
        val c = client ?: return
        val seen = HashSet<String>()
        val targets = entries.filter { it.kind.isMonitored && seen.add(it.target) }
        var done = 0
        progress(0, targets.size)
        val gate = Semaphore(6)
        coroutineScope {
            for (e in targets) {
                launch {
                    gate.withPermit { runCatching { c.testConnection(e.id) } }
                    done++
                    progress(done, targets.size)
                }
            }
        }
        refreshStatus()
    }

    suspend fun refreshStatus() {
        val c = client ?: return
        runCatching { c.connectionStatus() }.getOrNull()?.let { status = it }
    }

    /** Tunnel Guacamole d'une session, authentifié par le jeton d'appareil. */
    fun makeTransport(ticket: SessionTicket): GuacTransport? {
        tunnelOverride?.let { return it(ticket) }
        val c = client ?: return null
        val a = account ?: return null
        return RelayTunnelTransport(c.tunnelUrl(ticket.token), a.token)
    }

    fun noteOpened(entryId: String) {
        recentIds = (listOf(entryId) + recentIds.filter { it != entryId }).take(20)
        prefs.recentIds = recentIds
    }

    val recentEntries: List<Entry> get() = recentIds.mapNotNull { tree.entry(it) }
    val favoriteEntries: List<Entry> get() = tree.entries.filter { it.favorite }.sortedWith(Sorting.byName { it.name })

    suspend fun toggleFavorite(entry: Entry) {
        val c = client ?: return
        try {
            c.setFavorite(entry, !entry.favorite)
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    /** Lance une action asynchrone et affiche son erreur éventuelle dans le bandeau. */
    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }
}
