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

import java.util.function.BiFunction;
import java.util.function.Function;

import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.util.context.ContextView;

/**
 * @author Simon Baslé
 */
final class FluxObserveWith<T, STATE> extends InternalFluxOperator<T, T> {

	final BiFunction<STATE, ContextView, SequenceObserver<T>> observerGenerator;
	final STATE state;

	FluxObserveWith(Flux<? extends T> source, Function<? super Flux<? extends T>, STATE> stateGenerator,
					BiFunction<STATE, ContextView, Flux.SequenceObserver<T>> observerGenerator) {
		super(source);
		this.observerGenerator = observerGenerator;
		this.state = stateGenerator.apply(source);
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		SequenceObserver<T> sequenceObserver = observerGenerator.apply(this.state, actual.currentContext().readOnly()); //FIXME replace with contextView()
		return new SequenceObserverSubscriber<>(actual, sequenceObserver, false);
	}

	static final class SequenceObserverSubscriber<T> implements InnerOperator<T, T> {

		final CoreSubscriber<? super T> actual;
		final SequenceObserver<T> observer;
		final boolean             isMono;

		boolean done;
		Subscription s;

		SequenceObserverSubscriber(CoreSubscriber<? super T> actual, SequenceObserver<T> sequenceObserver, boolean isMono) {
			this.actual = actual;
			this.observer = sequenceObserver;
			this.isMono = isMono; //FIXME actually use
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				observer.onSubscription();
				actual.onSubscribe(s);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t, currentContext());
				return;
			}
			try {
				observer.onNext(t);
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
				Operators.onErrorDropped(t, currentContext());
				return;
			}
			done = true;

			try {
				observer.onError(t);
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(Exceptions.multiple(t, observerError));
				return;
			}

			try {
				observer.onTerminateOrCancel();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return; //TODO observe malformed ?
			}
			done = true;

			try {
				observer.onComplete();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}

			try {
				observer.onTerminateOrCancel();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}

			actual.onComplete();
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				try {
					observer.onRequest(n);
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
			try {
				observer.onCancel();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}


			try {
				observer.onTerminateOrCancel();
			}
			catch (Throwable observerError) {
				s.cancel();
				actual.onError(observerError);
				return;
			}

			s.cancel();
		}
	}
}
