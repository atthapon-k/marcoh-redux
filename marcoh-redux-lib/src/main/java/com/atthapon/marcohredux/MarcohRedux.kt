package com.atthapon.marcohredux

import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.annotations.CheckReturnValue
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject

interface Action

interface State

interface Reducer<S : State> {

    fun reduce(currentState: S, action: Action): S
}

interface Middleware<S : State> {

    fun performBeforeReducingState(currentState: S, action: Action) {}

    fun performAfterReducingState(action: Action, nextState: S) {}
}

interface StoreType<S : State> {

    val states: Observable<Pair<S, Action>>

    val indistinctStates: Observable<Pair<S, Action>>

    var replaceReducer: (S, Action) -> S

    fun dispatch(action: Action)

    fun dispatch(actions: Observable<out Action>): Disposable

    fun addMiddleware(middleware: Middleware<S>)

    fun removeMiddleware(middleware: Middleware<S>): Boolean
}

class Store<S : State>(
    initialState: S,
    reducer: Reducer<S>,
    defaultScheduler: Scheduler = Schedulers.single()
) : StoreType<S> {

    object NoAction : Action

    private var actionSubject = PublishSubject.create<Action>()

    override val states: Observable<Pair<S, Action>>
        get() = _states.distinctUntilChanged()

    override val indistinctStates: Observable<Pair<S, Action>>
        get() = _states

    private val _states: Observable<Pair<S, Action>>

    private val middlewares = mutableListOf<Middleware<S>>()

    // By default, this is doing nothing, just passing the reduced state
    override var replaceReducer: (S, Action) -> S = { reducedState, _ -> reducedState }

    init {
        _states = actionSubject
            .scan(initialState to NoAction as Action) { (state, _), action ->
                middlewares.onEach { it.performBeforeReducingState(state, action) }
                val reducedState = reducer.reduce(state, action)
                val nextState = replaceReducer(reducedState, action)
                nextState to action
            }
            .doAfterNext { next ->
                val (nextState, latestAction) = next
                middlewares.onEach { it.performAfterReducingState(latestAction, nextState) }
            }
            .map {
                it
            }
            .subscribeOn(defaultScheduler)
            .replay(1)
            .autoConnect()
    }

    override fun dispatch(action: Action) {
        try {
            actionSubject.onNext(action)
        } catch (ex: Exception) {
            actionSubject = PublishSubject.create()
            actionSubject.onNext(action)
        }
    }

    @CheckReturnValue
    override fun dispatch(actions: Observable<out Action>): Disposable =
        actions.subscribe(actionSubject::onNext)

    override fun addMiddleware(middleware: Middleware<S>) {
        middlewares.add(middleware)
    }

    override fun removeMiddleware(middleware: Middleware<S>) = middlewares.remove(middleware)
}