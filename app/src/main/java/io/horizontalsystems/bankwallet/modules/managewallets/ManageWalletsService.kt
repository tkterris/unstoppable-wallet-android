package io.horizontalsystems.bankwallet.modules.managewallets

import io.horizontalsystems.bankwallet.core.*
import io.horizontalsystems.bankwallet.core.managers.RestoreSettings
import io.horizontalsystems.bankwallet.entities.*
import io.horizontalsystems.bankwallet.modules.enablecoin.EnableCoinService
import io.horizontalsystems.marketkit.MarketKit
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.FullCoin
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject

class ManageWalletsService(
    private val marketKit: MarketKit,
    private val walletManager: IWalletManager,
    accountManager: IAccountManager,
    private val enableCoinService: EnableCoinService
) : Clearable {

    val itemsObservable = PublishSubject.create<List<Item>>()
    var items: List<Item> = listOf()
        private set(value) {
            field = value
            itemsObservable.onNext(value)
        }

    val cancelEnableCoinObservable = PublishSubject.create<Coin>()

    private val account: Account? = accountManager.activeAccount
    private var wallets = setOf<Wallet>()
    private var fullCoins = listOf<FullCoin>()

    private val disposables = CompositeDisposable()

    private var filter: String = ""

    init {
        walletManager.activeWalletsUpdatedObservable
            .subscribeIO {
                handleUpdated(it)
            }
            .let {
                disposables.add(it)
            }

        enableCoinService.enableCoinObservable
            .subscribeIO { (configuredPlatformCoins, settings) ->
                handleEnableCoin(configuredPlatformCoins, settings)
            }.let {
                disposables.add(it)
            }

        enableCoinService.cancelEnableCoinObservable
            .subscribeIO { fullCoin ->
                handleCancelEnable(fullCoin)
            }.let { disposables.add(it) }


        sync(walletManager.activeWallets)
        syncFullCoins()
        sortFullCoins()
        syncState()
    }

    private fun isEnabled(coin: Coin): Boolean {
        return wallets.any { it.coin == coin }
    }

    private fun sync(walletList: List<Wallet>) {
        wallets = walletList.toSet()
    }

    private fun fetchFullCoins(): List<FullCoin> {
        return if (filter.isBlank()) {
            val featuredFullCoins =
                marketKit.fullCoins("", 100).toMutableList().filter { it.supportedPlatforms.isNotEmpty() }

            val featuredCoins = featuredFullCoins.map { it.coin }
            val enabledFullCoins = marketKit.fullCoins(
                coinUids = wallets.filter { !featuredCoins.contains(it.coin) }.map { it.coin.uid }
            )
            val customFullCoins = wallets.filter { it.platformCoin.isCustom }.map { it.platformCoin.fullCoin }

            featuredFullCoins + enabledFullCoins + customFullCoins
        } else {
            marketKit.fullCoins(filter, 20).toMutableList()
        }
    }

    private fun syncFullCoins() {
        fullCoins = fetchFullCoins()
    }

    private fun sortFullCoins() {
        fullCoins = fullCoins.sortedByFilter(filter) {
            isEnabled(it.coin)
        }
    }

    private fun item(fullCoin: FullCoin): Item {
        val itemState = if (fullCoin.supportedPlatforms.isEmpty()) {
            ItemState.Unsupported
        } else {
            val enabled = isEnabled(fullCoin.coin)
            ItemState.Supported(
                enabled = enabled,
                hasSettings = enabled && hasSettingsOrPlatforms(fullCoin)
            )
        }

        return Item(fullCoin, itemState)
    }

    private fun hasSettingsOrPlatforms(fullCoin: FullCoin): Boolean {
        val supportedPlatforms = fullCoin.supportedPlatforms

        return if (supportedPlatforms.size == 1) {
            val platform = supportedPlatforms[0]
            platform.coinType.coinSettingTypes.isNotEmpty()
        } else {
            true
        }
    }

    private fun syncState() {
        items = fullCoins.map { item(it) }
    }

    private fun handleUpdated(wallets: List<Wallet>) {
        sync(wallets)

        val newFullCons = fetchFullCoins()
        if (newFullCons.size > fullCoins.size) {
            fullCoins = newFullCons
            sortFullCoins()
        }

        syncState()
    }

    private fun handleEnableCoin(
        configuredPlatformCoins: List<ConfiguredPlatformCoin>, restoreSettings: RestoreSettings
    ) {
        val account = this.account ?: return
        val coin = configuredPlatformCoins.firstOrNull()?.platformCoin?.coin ?: return

        if (restoreSettings.isNotEmpty() && configuredPlatformCoins.size == 1) {
            enableCoinService.save(restoreSettings, account, configuredPlatformCoins.first().platformCoin.coinType)
        }

        val existingWallets = wallets.filter { it.coin == coin }
        val existingConfiguredPlatformCoins = existingWallets.map { it.configuredPlatformCoin }
        val newConfiguredPlatformCoins = configuredPlatformCoins.minus(existingConfiguredPlatformCoins)

        val removedWallets = existingWallets.filter { !configuredPlatformCoins.contains(it.configuredPlatformCoin) }
        val newWallets = newConfiguredPlatformCoins.map { Wallet(it, account) }

        if (newWallets.isNotEmpty() || removedWallets.isNotEmpty()) {
            walletManager.handle(newWallets, removedWallets)
        }
    }

    private fun handleCancelEnable(fullCoin: FullCoin) {
        if (!isEnabled(fullCoin.coin)) {
            cancelEnableCoinObservable.onNext(fullCoin.coin)
        }
    }

    fun setFilter(filter: String) {
        this.filter = filter

        syncFullCoins()
        sortFullCoins()
        syncState()
    }

    fun enable(uid: String) {
        val fullCoin = fullCoins.firstOrNull { it.coin.uid == uid } ?: return
        enable(fullCoin)
    }

    fun enable(fullCoin: FullCoin) {
        enableCoinService.enable(fullCoin, account)
    }

    fun disable(uid: String) {
        val walletsToDelete = wallets.filter { it.coin.uid == uid }
        walletManager.delete(walletsToDelete)
    }

    fun configure(uid: String) {
        val fullCoin = fullCoins.firstOrNull { it.coin.uid == uid } ?: return
        val coinWallets = wallets.filter { it.coin == fullCoin.coin }
        enableCoinService.configure(fullCoin, coinWallets.map { it.configuredPlatformCoin })
    }

    override fun clear() {
        disposables.clear()
    }

    data class Item(
        val fullCoin: FullCoin,
        val state: ItemState
    )

    sealed class ItemState {
        object Unsupported : ItemState()
        class Supported(val enabled: Boolean, val hasSettings: Boolean) : ItemState()
    }
}
