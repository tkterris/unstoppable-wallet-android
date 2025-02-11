package io.horizontalsystems.bankwallet.modules.transactionInfo

import io.horizontalsystems.bankwallet.core.Clearable
import io.horizontalsystems.bankwallet.core.ITransactionsAdapter
import io.horizontalsystems.bankwallet.core.subscribeIO
import io.horizontalsystems.bankwallet.entities.CurrencyValue
import io.horizontalsystems.bankwallet.entities.transactionrecords.TransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.binancechain.BinanceChainIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.binancechain.BinanceChainOutgoingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.bitcoin.BitcoinIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.bitcoin.BitcoinOutgoingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.evm.*
import io.horizontalsystems.bankwallet.modules.transactions.FilterTransactionType
import io.horizontalsystems.bankwallet.modules.transactions.TransactionSource
import io.horizontalsystems.core.ICurrencyManager
import io.horizontalsystems.marketkit.MarketKit
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import java.math.BigDecimal

class TransactionInfoService(
    private val transactionRecord: TransactionRecord,
    private val adapter: ITransactionsAdapter,
    private val marketKit: MarketKit,
    private val currencyManager: ICurrencyManager
) : Clearable {

    val transactionHash: String get() = transactionRecord.transactionHash
    val source: TransactionSource get() = transactionRecord.source

    private val disposables = CompositeDisposable()

    private val transactionInfoItemSubject = BehaviorSubject.create<TransactionInfoItem>()
    val transactionInfoItemObservable: Observable<TransactionInfoItem> = transactionInfoItemSubject

    private var transactionInfoItem = TransactionInfoItem(
        transactionRecord,
        adapter.lastBlockInfo,
        TransactionInfoModule.ExplorerData(adapter.explorerTitle, adapter.getTransactionUrl(transactionRecord.transactionHash)),
        mapOf()
    )

    private val coinUidsForRates: List<String>
        get() {
            val coinUids = mutableListOf<String?>()

            val txCoinTypes = when (val tx = transactionRecord) {
                is EvmIncomingTransactionRecord -> listOf(tx.value.coinUid)
                is EvmOutgoingTransactionRecord -> listOf(tx.fee?.coinUid, tx.value.coinUid)
                is SwapTransactionRecord -> listOf(
                    tx.fee,
                    tx.valueIn,
                    tx.valueOut
                ).map { it?.coinUid }
                is UnknownSwapTransactionRecord -> listOf(
                    tx.fee,
                    tx.valueIn,
                    tx.valueOut
                ).map { it?.coinUid }
                is ApproveTransactionRecord -> listOf(tx.fee?.coinUid, tx.value.coinUid)
                is ContractCallTransactionRecord -> {
                    val tempCoinUidList = mutableListOf<String>()
                    tempCoinUidList.addAll(tx.incomingEvents.map { it.value.coinUid })
                    tempCoinUidList.addAll(tx.outgoingEvents.map { it.value.coinUid })
                    tempCoinUidList
                }
                is ExternalContractCallTransactionRecord -> {
                    val tempCoinUidList = mutableListOf<String>()
                    tempCoinUidList.addAll(tx.incomingEvents.map { it.value.coinUid })
                    tempCoinUidList.addAll(tx.outgoingEvents.map { it.value.coinUid })
                    tempCoinUidList
                }
                is BitcoinIncomingTransactionRecord -> listOf(tx.value.coinUid)
                is BitcoinOutgoingTransactionRecord -> listOf(
                    tx.fee,
                    tx.value
                ).map { it?.coinUid }
                is BinanceChainIncomingTransactionRecord -> listOf(tx.value.coinUid)
                is BinanceChainOutgoingTransactionRecord -> listOf(
                    tx.fee,
                    tx.value
                ).map { it.coinUid }
                else -> emptyList()
            }

            (transactionRecord as? EvmTransactionRecord)?.let { transactionRecord ->
                if (!transactionRecord.foreignTransaction) {
                    coinUids.add(transactionRecord.fee?.coinUid)
                }
            }

            coinUids.addAll(txCoinTypes)

            return coinUids.filterNotNull().filter { it.isNotBlank() }.distinct()
        }

    init {
        transactionInfoItemSubject.onNext(transactionInfoItem)

        fetchRates()
            .subscribeIO {
                handleRates(it)
            }
            .let {
                disposables.add(it)
            }

        adapter.getTransactionRecordsFlowable(null, FilterTransactionType.All)
            .flatMap {
                val record = it.find { it == transactionRecord }
                if (record != null) {
                    Flowable.just(record)
                } else {
                    Flowable.empty()
                }
            }
            .subscribeIO {
                handleRecordUpdate(it)
            }
            .let {
                disposables.add(it)
            }

        adapter.lastBlockUpdatedFlowable
            .subscribeIO {
                handleLastBlockUpdate()
            }
            .let {
                disposables.add(it)
            }
    }

    private fun fetchRates(): Single<Map<String, CurrencyValue>> {
        val coinUids = coinUidsForRates
        val timestamp = transactionRecord.timestamp
        val flowables: List<Single<Pair<String, CurrencyValue>>> = coinUids.map { coinUid ->
            marketKit.coinHistoricalPriceSingle(coinUid, currencyManager.baseCurrency.code, timestamp)
                .onErrorResumeNext(Single.just(BigDecimal.ZERO)) //provide default value on error
                .map {
                    Pair(coinUid, CurrencyValue(currencyManager.baseCurrency, it))
                }
        }

        return Single
            .zip(flowables) { array ->
                array.mapNotNull {
                    it as Pair<String, CurrencyValue>

                    if (it.second.value == BigDecimal.ZERO) {
                        null
                    } else {
                        it.first to it.second
                    }
                }.toMap()
            }
    }

    @Synchronized
    private fun handleLastBlockUpdate() {
        transactionInfoItem = transactionInfoItem.copy(lastBlockInfo = adapter.lastBlockInfo)
        transactionInfoItemSubject.onNext(transactionInfoItem)
    }

    @Synchronized
    private fun handleRecordUpdate(transactionRecord: TransactionRecord) {
        transactionInfoItem = transactionInfoItem.copy(record = transactionRecord)
        transactionInfoItemSubject.onNext(transactionInfoItem)
    }

    @Synchronized
    private fun handleRates(rates: Map<String, CurrencyValue>) {
        transactionInfoItem = transactionInfoItem.copy(rates = rates)
        transactionInfoItemSubject.onNext(transactionInfoItem)
    }

    fun getRaw(): String? {
        return adapter.getRawTransaction(transactionRecord.transactionHash)
    }

    override fun clear() {
        disposables.clear()
    }

}
