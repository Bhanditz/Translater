package com.grekov.translate.presentation.core.elm

import com.grekov.translate.domain.elm.BatchCmd
import com.grekov.translate.domain.elm.Cmd
import com.grekov.translate.domain.elm.ErrorMsg
import com.grekov.translate.domain.elm.Idle
import com.grekov.translate.domain.elm.Msg
import com.grekov.translate.domain.elm.None
import com.grekov.translate.domain.elm.OneShotCmd
import com.grekov.translate.domain.interactor.base.ElmSubscription
import com.grekov.translate.presentation.core.utils.lazyLog
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import paperparcel.PaperParcel
import paperparcel.PaperParcelable
import timber.log.Timber
import java.util.ArrayDeque

sealed class AbstractState(open val screen: Screen)
@PaperParcel
open class State(screen: Screen) : AbstractState(screen), PaperParcelable {
    companion object {
        @JvmField
        val CREATOR = PaperParcelState.CREATOR
    }
}


interface Component<S : State> {

    fun update(msg: Msg, state: S): Pair<S, Cmd>

    fun render(state: S)

    fun call(cmd: Cmd): Single<Msg>

    fun sub(state: S)

}

interface TimeTravel {

    fun travel(screen: Screen, state: State)
}


class Program<S : State>(private val outputScheduler: Scheduler) {

    private val msgRelay: BehaviorRelay<Msg> = BehaviorRelay.create()
    private var msgQueue = ArrayDeque<Msg>()
    private var disposableMap: MutableMap<String, Disposable> = mutableMapOf()
    private lateinit var state: S
    var restoredState: S? = null
    private lateinit var component: Component<S>
    private var lock: Boolean = false
    var timeTraveller: TimeTraveller? = null

    constructor(outputScheduler: Scheduler, tt: TimeTraveller) : this(outputScheduler) {
        this.timeTraveller = tt
        if (::component.isInitialized && component is TimeTravel) {
            timeTraveller?.stateRelay
                ?.observeOn(outputScheduler)
                ?.subscribe({ (screen, state) ->
                    (component as TimeTravel).travel(screen, state)
                })
        }
    }

    fun init(initialState: S, component: Component<S>): Disposable {
        this.component = component
        this.state = initialState
        subscribeToSub(initialState)
        return msgRelay
            .observeOn(outputScheduler)
            .map { msg ->
                lazyLog { Timber.d("elm reduce msg:${msg.javaClass.simpleName} ") }
                val updateResult = component.update(msg, this.state)
                val newState = updateResult.first

                timeTraveller?.consoleRecords?.add(
                    ConsoleTimeRecord(
                        newState.screen,
                        msg.javaClass.simpleName,
                        newState.toString()
                    )
                )
                timeTraveller?.records?.add(TimeRecord(newState.screen, msg, newState))

                this.state = newState
                if (msgQueue.size > 0) {
                    msgQueue.removeFirst()
                    lazyLog { Timber.d("elm remove from queue:${msg.javaClass.simpleName}") }
                }

                lock = false
                component.render(newState)
                component.sub(newState)
                loop()
                return@map updateResult
            }
            .filter { (_, cmd) -> cmd !is None }
            .observeOn(Schedulers.io())
            .flatMap { (_, cmd) ->
                lazyLog { Timber.d("elm call cmd:$cmd") }
                call(cmd)
            }
            .observeOn(outputScheduler)
            .subscribe({ msg ->
                when (msg) {
                    is Idle -> {
                    }
                    else -> msgQueue.addLast(msg)
                }

                loop()
            })
    }

    fun call(cmd: Cmd): Observable<Msg> {
        return when (cmd) {
            is BatchCmd ->
                Observable.merge(cmd.cmds.map {
                    cmdCall(it)
                })
            else -> cmdCall(cmd)
        }
    }

    private fun cmdCall(cmd: Cmd): Observable<Msg> {
        return when (cmd) {
            is OneShotCmd -> Observable.just(cmd.msg)
            else -> component.call(cmd)
                .onErrorResumeNext { err -> Single.just(ErrorMsg(err, cmd)) }
                .toObservable()
        }
    }

    private fun subscribeToSub(state: S) {
        component.sub(state)
    }

    fun getState(): S {
        return state
    }

    private fun loop() {
        if (timeTraveller?.adventureMode == true) return

        lazyLog { Timber.d("elm loop queue size:${msgQueue.size}") }
        lazyLog { msgQueue.forEach { Timber.d("elm in queue:${it.javaClass.simpleName}") } }
        if (!lock) {
            if (msgQueue.size > 0) {
                lock = true
                lazyLog { Timber.d("elm accept from loop ${msgQueue.first}") }
                msgRelay.accept(msgQueue.first)
            }
        }
    }

    fun accept(msg: Msg) {
        if (timeTraveller?.adventureMode == true) return

        msgQueue.addLast(msg)
        lazyLog { Timber.d("elm add msg: ${msg.javaClass.simpleName} queue size:${msgQueue.size}") }
        lazyLog { msgQueue.forEach { Timber.d("elm accept in queue:${it.javaClass.simpleName}") } }
        if (!lock && msgQueue.size == 1) {
            lock = true
            lazyLog { Timber.d("elm accept event:${msg.javaClass.simpleName}") }
            msgRelay.accept(msgQueue.first)
        }
    }

    fun render() {
        component.render(this.state)
    }

    fun destroy() {
        disposableMap.forEach { (_, disposable) -> if (!disposable.isDisposed) disposable.dispose() }
    }

    fun <T : Msg, P> addSub(
        useCaseStream: ElmSubscription<T, P>,
        params: P
    ) {
        val (sub, created) = useCaseStream.getObservable(params)
        if (created) {
            var disposable = disposableMap[useCaseStream.javaClass.canonicalName]
            disposable?.dispose()
            disposableMap.put(useCaseStream.javaClass.canonicalName,
                sub.subscribe { msg -> accept(msg) })
        }
    }

    fun <T : Msg, P> removeSub(useCaseStream: ElmSubscription<T, P>) {
        var disposable = disposableMap[useCaseStream.javaClass.canonicalName]
        if (disposable?.isDisposed != true) {
            disposable?.dispose()
        }
    }

}