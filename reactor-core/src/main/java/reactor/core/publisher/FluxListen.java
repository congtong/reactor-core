/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.function.Function;

import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * A generic per-Subscription side effect {@link Flux} that notifies a {@link SequenceListener} of most events.
 *
 * @author Simon Baslé
 */
final class FluxListen<T> extends InternalFluxOperator<T, T> {

	final Function<ContextView, SequenceListener<T>> observerGenerator;

	FluxListen(Flux<? extends T> source, Function<ContextView, SequenceListener<T>> observerGenerator) {
		super(source);
		this.observerGenerator = observerGenerator;
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		//if the SequenceObserver cannot be created, all we can do is error the subscriber.
		//after it is created, in case doFirst fails we can additionally try to invoke doFinally.
		//note that if the later handler also fails, then that exception is thrown.
		SequenceListener<T> sequenceListener;
		try {
			//TODO replace currentContext() with contextView() when available
			sequenceListener = observerGenerator.apply(actual.currentContext().readOnly());
		}
		catch (Throwable generatorError) {
			Operators.error(actual, generatorError);
			return null;
		}

		try {
			sequenceListener.doFirst();
		}
		catch (Throwable observerError) {
			Operators.error(actual, observerError);
			sequenceListener.doFinally(SignalType.ON_ERROR);
			return null;
		}
		return new SequenceObserverSubscriber<>(actual, sequenceListener);
	}

	static final class SequenceObserverSubscriber<T> implements InnerOperator<T, T> {

		final CoreSubscriber<? super T> actual;
		final SequenceListener<T>       observer;

		boolean done;
		Subscription s;

		SequenceObserverSubscriber(CoreSubscriber<? super T> actual, SequenceListener<T> sequenceListener) {
			this.actual = actual;
			this.observer = sequenceListener;
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				try {
					observer.doOnSubscription();
				}
				catch (Throwable observerError) {
					s.cancel();
					Operators.error(actual, observerError);
					return;
				}
				actual.onSubscribe(s);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				try {
					observer.doOnMalformedOnNext(t);
				}
				catch (Throwable observerError) {
					Operators.onErrorDropped(observerError, actual.currentContext());
				}
				finally {
					Operators.onNextDropped(t, currentContext());
				}
				return;
			}
			try {
				observer.doOnNext(t);
			}
			catch (Throwable error) {
				s.cancel();
				actual.onError(error); //TODO wrap ? hooks ?
				return;
			}
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				try {
					observer.doOnMalformedOnError(t);
				}
				catch (Throwable observerError) {
					Operators.onErrorDropped(observerError, actual.currentContext());
				}
				finally {
					Operators.onErrorDropped(t, currentContext());
				}
				return;
			}
			done = true;

			try {
				observer.doOnError(t);
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(Exceptions.multiple(t, observerError));
				return;
			}

			//doFinally is always invoked, even if either actual.onError() or the doAfterError hook fails
			Context ctx = actual.currentContext();
			try {
				actual.onError(t);

				try {
					observer.doAfterError(t);
				}
				catch (Throwable observerError) {
					Operators.onErrorDropped(observerError, ctx);
				}
			}
			finally {
				doFinally(SignalType.ON_ERROR, ctx);
			}
		}

		@Override
		public void onComplete() {
			if (done) {
				try {
					observer.doOnMalformedOnComplete();
				}
				catch (Throwable observerError) {
					Operators.onErrorDropped(observerError, actual.currentContext());
				}
				return;
			}
			done = true;

			try {
				observer.doOnComplete();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}

			//doFinally is always invoked, even if either actual.onComplete() or the doAfterComplete hook fails
			Context ctx = actual.currentContext();
			try {
				actual.onComplete();

				try {
					observer.doAfterComplete();
				}
				catch (Throwable observerError) {
					Operators.onErrorDropped(observerError, ctx);
				}
			}
			finally {
				doFinally(SignalType.ON_COMPLETE, ctx);
			}
		}

		void doFinally(SignalType terminationType, Context context) {
			try {
				observer.doFinally(terminationType);
			}
			catch (Throwable observerError) {
				Operators.onErrorDropped(observerError, context);
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				try {
					observer.doOnRequest(n);
				}
				catch (Throwable observerError) {
					s.cancel();
					actual.onError(observerError);
					return;
				}
				s.request(n);
			}
		}

		@Override
		public void cancel() {
			Context ctx = actual.currentContext();
			try {
				observer.doOnCancel();
			}
			catch (Throwable observerError) {
				try {
					s.cancel();
					actual.onError(observerError);
				}
				finally {
					doFinally(SignalType.CANCEL, ctx);
				}
				return;
			}

			try {
				s.cancel();
			}
			finally {
				doFinally(SignalType.CANCEL, ctx);
			}
		}
	}
}
